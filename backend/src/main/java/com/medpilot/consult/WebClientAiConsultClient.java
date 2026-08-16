package com.medpilot.consult;

import com.medpilot.config.AiServiceClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WebClientAiConsultClient implements AiConsultClient {

    private final WebClient client;

    public WebClientAiConsultClient(
            @Qualifier(AiServiceClientConfig.CLIENT_BEAN) WebClient client) {
        this.client = client;
    }

    @Override
    public Flux<String> consult(
            String text,
            String sessionId,
            Map<String, String> healthContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("session_id", sessionId);
        if (healthContext != null && !healthContext.isEmpty()) {
            payload.put("health_context", Map.copyOf(healthContext));
        }
        return client.post()
                .uri("/consult")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class);
    }
}
