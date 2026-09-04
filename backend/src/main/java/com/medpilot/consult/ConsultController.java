package com.medpilot.consult;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import com.medpilot.health.HealthProfileContextService;
import com.medpilot.monitor.LiveTraceRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConsultController {

    private final AiConsultClient aiClient;
    private final SessionOwnershipService ownership;
    private final ConsultationPersistenceService persistence;
    private final ConsultationMessageService messages;
    private final LiveTraceRegistry liveTraces;
    private final UserRepository users;
    private final HealthProfileContextService healthProfiles;
    private final ObjectMapper mapper;

    @Value("${medpilot.ai.retry.max-retries:1}")
    private int maxAiRetries = 1;

    @Value("${medpilot.ai.retry.backoff-ms:100}")
    private long retryBackoffMs = 100L;

    @Autowired
    public ConsultController(
            AiConsultClient aiClient,
            SessionOwnershipService ownership,
            ConsultationPersistenceService persistence,
            ConsultationMessageService messages,
            LiveTraceRegistry liveTraces,
            UserRepository users,
            HealthProfileContextService healthProfiles,
            ObjectMapper mapper) {
        this.aiClient = aiClient;
        this.ownership = ownership;
        this.persistence = persistence;
        this.messages = messages;
        this.liveTraces = liveTraces;
        this.users = users;
        this.healthProfiles = healthProfiles;
        this.mapper = mapper;
    }

    /** Kept for isolated legacy tests; the application always uses the full constructor. */
    ConsultController(
            AiConsultClient aiClient,
            SessionOwnershipService ownership,
            ConsultationPersistenceService persistence,
            UserRepository users,
            HealthProfileContextService healthProfiles,
            ObjectMapper mapper) {
        this(aiClient, ownership, persistence, null, null, users, healthProfiles, mapper);
    }

    @PostMapping(value = "/consult", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Mono<ResponseEntity<Flux<String>>> consult(
            @Valid @RequestBody ConsultRequest request,
            Authentication authentication) {
        User user = users.findByUsername(authentication.getName())
                .orElseThrow(() -> new SecurityException("authenticated user not found"));
        ownership.claim(request.sessionId(), user.getId());
        Map<String, String> healthContext = healthProfiles.resolveForUser(user.getId());
        List<AiConsultClient.HistoryMessage> history = messages == null
                ? List.of()
                : messages.historyFor(user.getId(), request.sessionId());
        if (messages != null) {
            messages.appendUser(user.getId(), request.sessionId(), request.text());
        }

        ConsultationEventAccumulator accumulator =
                new ConsultationEventAccumulator(mapper, request.sessionId());
        String requestJson = writeRequest(request);
        LiveTraceRegistry.Handle live = liveTraces == null
                ? null
                : liveTraces.start(request.sessionId(), user.getId());

        return openWithRetry(
                        request.text(), request.sessionId(), healthContext, history)
                .map(upstream -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_NDJSON)
                        .body(processStream(
                                upstream,
                                user.getId(),
                                request.text(),
                                requestJson,
                                accumulator,
                                live)))
                .doOnError(error -> persistInitialFailure(
                        user.getId(), accumulator, live, initialFailureCode(error)))
                .onErrorMap(this::mapInitialUpstreamFailure)
                .doOnCancel(() -> persistCancellationSafely(user.getId(), accumulator, live));
    }

    private Flux<String> processStream(
            Flux<String> upstream,
            Long userId,
            String requestText,
            String requestJson,
            ConsultationEventAccumulator accumulator,
            LiveTraceRegistry.Handle live) {
        AtomicBoolean terminated = new AtomicBoolean(false);
        AtomicBoolean subscribed = new AtomicBoolean(false);
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                return Flux.error(new IllegalStateException(
                        "consultation response body can only be subscribed once"));
            }
            return upstream
                .map(line -> {
                    try {
                        accumulator.accept(line);
                    } catch (RuntimeException error) {
                        throw new StreamFailure("INVALID_UPSTREAM_EVENT", error);
                    }
                    publishLive(live, accumulator.traceId(), line);
                    if (accumulator.isTerminalError()) {
                        if (terminated.compareAndSet(false, true)) {
                            persistFailureSafely(
                                    userId, accumulator, live, accumulator.failureCode());
                        }
                    } else if (accumulator.isDone()
                            && terminated.compareAndSet(false, true)) {
                        try {
                            persistence.persist(
                                    userId, requestText, requestJson, accumulator.finish());
                            completeLive(live, accumulator.traceId());
                        } catch (RuntimeException error) {
                            persistFailureSafely(
                                    userId, accumulator, live, "PERSISTENCE_ERROR");
                            throw new StreamFailure("PERSISTENCE_ERROR", error);
                        }
                    }
                    return line.stripTrailing() + "\n";
                })
                .concatWith(Mono.defer(() -> {
                    if (terminated.get() || accumulator.isTerminalError()) {
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
                    if (terminated.compareAndSet(false, true)) {
                        persistFailureSafely(userId, accumulator, live, code);
                    }
                    return Flux.just(accumulator.failureEvent(code) + "\n");
                })
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.CANCEL
                            && terminated.compareAndSet(false, true)) {
                        persistCancellationSafely(userId, accumulator, live);
                    }
                });
        });
    }

    /** Opening resolves upstream HTTP status without subscribing to the streaming body. */
    private Mono<Flux<String>> openWithRetry(
            String text,
            String sessionId,
            Map<String, String> healthContext,
            List<AiConsultClient.HistoryMessage> history) {
        Map<String, String> stableContext = healthContext == null
                ? Map.of()
                : Map.copyOf(healthContext);
        List<AiConsultClient.HistoryMessage> stableHistory = history == null
                ? List.of()
                : List.copyOf(history);
        Supplier<Mono<Flux<String>>> source = () -> {
            Mono<Flux<String>> opened = aiClient.openConsult(
                    text, sessionId, stableContext, stableHistory);
            // Mockito and legacy adapters can return null for the new default hook.
            return opened != null
                    ? opened
                    : Mono.just(aiClient.consult(text, sessionId, stableContext));
        };
        Mono<Flux<String>> deferred = Mono.defer(source);
        int retries = Math.max(0, maxAiRetries);
        if (retries == 0) {
            return deferred;
        }
        Duration backoff = Duration.ofMillis(Math.max(1L, retryBackoffMs));
        return deferred.retryWhen(Retry.backoff(retries, backoff)
                .filter(this::isRetryableBeforeFirstEvent)
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
            LiveTraceRegistry.Handle live,
            String code) {
        ConsultationEventAccumulator.Snapshot snapshot = accumulator.failureSnapshot(code);
        try {
            persistence.persistFailure(userId, snapshot);
        } catch (RuntimeException ignored) {
            // A database outage must not hide the actionable upstream error from the client.
        }
        failLive(live, snapshot.traceId(), code);
    }

    private void persistInitialFailure(
            Long userId,
            ConsultationEventAccumulator accumulator,
            LiveTraceRegistry.Handle live,
            String code) {
        persistFailureSafely(userId, accumulator, live, code);
    }

    private void persistCancellationSafely(
            Long userId,
            ConsultationEventAccumulator accumulator,
            LiveTraceRegistry.Handle live) {
        ConsultationEventAccumulator.Snapshot snapshot = accumulator.cancelledSnapshot();
        try {
            persistence.persistCancellation(userId, snapshot);
        } catch (RuntimeException ignored) {
            // Cancellation must still release the upstream connection when persistence is down.
        }
        cancelLive(live, snapshot.traceId());
    }

    private void publishLive(LiveTraceRegistry.Handle live, String traceId, String line) {
        if (liveTraces != null && live != null) liveTraces.publish(live, traceId, line);
    }

    private void completeLive(LiveTraceRegistry.Handle live, String traceId) {
        if (liveTraces != null && live != null) liveTraces.complete(live, traceId);
    }

    private void failLive(LiveTraceRegistry.Handle live, String traceId, String code) {
        if (liveTraces != null && live != null) liveTraces.fail(live, traceId, code);
    }

    private void cancelLive(LiveTraceRegistry.Handle live, String traceId) {
        if (liveTraces != null && live != null) liveTraces.cancel(live, traceId);
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
