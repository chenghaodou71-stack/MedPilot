package com.medpilot.consult;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import com.medpilot.health.HealthProfileContextService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.medpilot.common.AiServiceUnavailableException;
import com.medpilot.common.AiServiceRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.beans.factory.annotation.Value;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConsultController {

    private final AiConsultClient aiClient;
    private final SessionOwnershipService ownership;
    private final ConsultationPersistenceService persistence;
    private final UserRepository users;
    private final HealthProfileContextService healthProfiles;
    private final ObjectMapper mapper;

    @Value("${medpilot.ai.retry.max-retries:1}")
    private int maxAiRetries = 1;

    @Value("${medpilot.ai.retry.backoff-ms:100}")
    private long retryBackoffMs = 100L;

    public ConsultController(
            AiConsultClient aiClient,
            SessionOwnershipService ownership,
            ConsultationPersistenceService persistence,
            UserRepository users,
            HealthProfileContextService healthProfiles,
            ObjectMapper mapper) {
        this.aiClient = aiClient;
        this.ownership = ownership;
        this.persistence = persistence;
        this.users = users;
        this.healthProfiles = healthProfiles;
        this.mapper = mapper;
    }

    @PostMapping(value = "/consult", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Mono<ResponseEntity<Flux<String>>> consult(
            @Valid @RequestBody ConsultRequest request,
            Authentication authentication) {
        User user = users.findByUsername(authentication.getName())
                .orElseThrow(() -> new SecurityException("authenticated user not found"));
        ownership.claim(request.sessionId(), user.getId());
        Map<String, String> healthContext = healthProfiles.resolveForUser(user.getId());

        ConsultationEventAccumulator accumulator =
                new ConsultationEventAccumulator(mapper, request.sessionId());
        String requestJson = writeRequest(request);

        Flux<String> upstream = retryBeforeFirstEvent(
                request.text(), request.sessionId(), healthContext).cache();
        Flux<String> responseBody = processStream(
                upstream, user.getId(), request.text(), requestJson, accumulator);

        return upstream.next()
                .switchIfEmpty(Mono.error(new AiServiceUnavailableException()))
                .doOnError(error -> persistFailureSafely(
                        user.getId(), accumulator, initialFailureCode(error)))
                .onErrorMap(this::mapInitialUpstreamFailure)
                .thenReturn(ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_NDJSON)
                        .body(responseBody));
    }

    private Flux<String> processStream(
            Flux<String> upstream,
            Long userId,
            String requestText,
            String requestJson,
            ConsultationEventAccumulator accumulator) {
        AtomicBoolean persisted = new AtomicBoolean(false);
        return upstream
                .map(line -> {
                    try {
                        accumulator.accept(line);
                    } catch (RuntimeException error) {
                        throw new StreamFailure("INVALID_UPSTREAM_EVENT", error);
                    }
                    if (accumulator.isTerminalError()) {
                        persistFailureSafely(
                                userId,
                                accumulator,
                                accumulator.failureCode());
                        persisted.set(true);
                    } else if (accumulator.isDone()) {
                        try {
                            persistence.persist(
                                    userId, requestText, requestJson, accumulator.finish());
                            persisted.set(true);
                        } catch (RuntimeException error) {
                            throw new StreamFailure("PERSISTENCE_ERROR", error);
                        }
                    }
                    return line.stripTrailing() + "\n";
                })
                .concatWith(Mono.defer(() -> {
                    if (persisted.get() || accumulator.isTerminalError()) {
                        return Mono.empty();
                    }
                    return Mono.error(new StreamFailure(
                            "INCOMPLETE_STREAM",
                            new IllegalStateException("stream ended without a done event")));
                }))
                .onErrorResume(error -> {
                    String code = error instanceof StreamFailure failure
                            ? failure.code
                            : "UPSTREAM_STREAM_ERROR";
                    if (!persisted.get()) {
                        persistFailureSafely(userId, accumulator, code);
                        persisted.set(true);
                    }
                    return Flux.just(accumulator.failureEvent(code) + "\n");
                });
    }

    /** Retry only failures that happen before the first event reaches the client. */
    private Flux<String> retryBeforeFirstEvent(
            String text,
            String sessionId,
            Map<String, String> healthContext) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        Map<String, String> stableContext = healthContext == null
                ? Map.of()
                : Map.copyOf(healthContext);
        Supplier<Flux<String>> source = () -> aiClient.consult(text, sessionId, stableContext)
                .doOnNext(ignored -> emitted.set(true));
        Flux<String> deferred = Flux.defer(source);
        int retries = Math.max(0, maxAiRetries);
        if (retries == 0) {
            return deferred;
        }
        Duration backoff = Duration.ofMillis(Math.max(1L, retryBackoffMs));
        return deferred.retryWhen(Retry.backoff(retries, backoff)
                .filter(error -> !emitted.get() && isRetryableBeforeFirstEvent(error))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private boolean isRetryableBeforeFirstEvent(Throwable error) {
        if (error instanceof AiServiceRequestException || error instanceof IllegalArgumentException) {
            return false;
        }
        if (error instanceof WebClientResponseException response) {
            return response.getStatusCode().is5xxServerError();
        }
        return error instanceof AiServiceUnavailableException
                || error instanceof WebClientRequestException
                || error instanceof IOException
                || error instanceof java.util.concurrent.TimeoutException;
    }

    private String initialFailureCode(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            return "AI_HTTP_" + response.getStatusCode().value();
        }
        if (error instanceof AiServiceRequestException requestError) {
            return "AI_HTTP_" + requestError.getStatus().value();
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "inference_timeout";
        }
        if (error instanceof AiServiceUnavailableException) {
            return "AI_SERVICE_UNAVAILABLE";
        }
        return "AI_INITIAL_FAILURE";
    }

    private void persistFailureSafely(
            Long userId,
            ConsultationEventAccumulator accumulator,
            String code) {
        try {
            persistence.persistFailure(userId, accumulator.failureSnapshot(code));
        } catch (RuntimeException ignored) {
            // A database outage must not hide the actionable upstream error from the client.
        }
    }

    private RuntimeException mapInitialUpstreamFailure(Throwable error) {
        if (error instanceof AiServiceUnavailableException unavailable) {
            return unavailable;
        }
        if (error instanceof AiServiceRequestException requestError) {
            return requestError;
        }
        if (error instanceof WebClientResponseException responseError) {
            int status = responseError.getStatusCode().value();
            return switch (status) {
                case 409 -> new AiServiceRequestException(
                        HttpStatus.CONFLICT,
                        "该问诊会话正在处理中",
                        responseError);
                case 413 -> new AiServiceRequestException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "请求内容超过 1 MiB 限制",
                        responseError);
                case 429 -> new AiServiceRequestException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "AI 服务当前繁忙，请稍后重试",
                        responseError);
                default -> new AiServiceUnavailableException(responseError);
            };
        }
        return new AiServiceUnavailableException(error);
    }

    private String writeRequest(ConsultRequest request) {
        try {
            return mapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize validated request", ex);
        }
    }

    public record ConsultRequest(
            @NotBlank String text,
            @JsonProperty("session_id") @NotBlank String sessionId) {
    }

    private static final class StreamFailure extends RuntimeException {
        private final String code;

        private StreamFailure(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }
    }
}
