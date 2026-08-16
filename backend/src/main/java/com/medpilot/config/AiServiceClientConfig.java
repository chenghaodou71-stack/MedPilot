package com.medpilot.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AiServiceClientConfig {

    public static final String CLIENT_BEAN = "aiServiceWebClient";
    public static final String SERVICE_TOKEN_HEADER = "X-MedPilot-Service-Token";

    @Bean
    @Qualifier(CLIENT_BEAN)
    public WebClient aiServiceWebClient(
            WebClient.Builder builder,
            @Value("${medpilot.ai-service-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${medpilot.ai-service-token}") String serviceToken) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalStateException("MEDPILOT_AI_SERVICE_TOKEN is required");
        }
        return builder
                .baseUrl(baseUrl)
                .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken)
                .build();
    }
}
