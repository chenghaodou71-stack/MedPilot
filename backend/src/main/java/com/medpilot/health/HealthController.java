package com.medpilot.health;

import com.medpilot.common.ApiResponse;
import com.medpilot.config.AiServiceClientConfig;
import com.medpilot.runtime.RedisRuntimeState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final WebClient aiClient;
    private final RedisRuntimeState sharedState;

    /** Constructor retained for focused unit tests that only exercise AI health. */
    public HealthController(
            WebClient aiClient) {
        this(aiClient, null);
    }

    @Autowired
    public HealthController(
            @Qualifier(AiServiceClientConfig.CLIENT_BEAN) WebClient aiClient,
            RedisRuntimeState sharedState) {
        this.aiClient = aiClient;
        this.sharedState = sharedState;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        boolean redisRequired = sharedState != null && sharedState.isRequired();
        boolean redisConfigured = sharedState == null
                || !sharedState.shouldUseSharedState()
                || sharedState.isEnabled();
        boolean redisAvailable = sharedState == null || sharedState.isAvailable();
        boolean ready = !redisRequired || (redisConfigured && redisAvailable);

        Map<String, Object> redis = Map.of(
                "ok", redisAvailable && redisConfigured,
                "required", redisRequired,
                "configured", redisConfigured,
                "status", !redisConfigured
                        ? "hmac_secret_missing"
                        : (redisAvailable ? "online" : "unavailable"));
        Map<String, Object> data = Map.of(
                "backend", ready ? "ok" : "degraded",
                "shared_state", redis);
        if (!ready) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.fail("shared Redis state is unavailable", data));
        }
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /** 全链路验证：backend -> ai-service /health。 */
    @GetMapping("/health/ai")
    public Mono<ResponseEntity<ApiResponse<Object>>> aiHealth() {
        return aiClient.get().uri("/health")
                .exchangeToMono(response -> response.bodyToMono(Object.class)
                        .defaultIfEmpty(Map.of())
                        .map(body -> {
                            if (response.statusCode().is2xxSuccessful()) {
                                return ResponseEntity.ok(ApiResponse.ok(body));
                            }
                            if (response.statusCode().value()
                                    == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                        .body(ApiResponse.fail("AI service not ready", body));
                            }
                            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                    .body(ApiResponse.fail("ai-service unavailable"));
                        }))
                .onErrorResume(error -> Mono.just(ResponseEntity
                        .status(HttpStatus.BAD_GATEWAY)
                        .body(ApiResponse.fail("ai-service unavailable"))));
    }
}
