package com.medpilot.health;

import com.medpilot.common.ApiResponse;
import com.medpilot.config.AiServiceClientConfig;
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

    public HealthController(
            @Qualifier(AiServiceClientConfig.CLIENT_BEAN) WebClient aiClient) {
        this.aiClient = aiClient;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("backend", "ok"));
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
