package com.medpilot.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;

import java.time.Duration;

@Configuration
public class AiServiceClientConfig {

    public static final String CLIENT_BEAN = "aiServiceWebClient";
    public static final String SERVICE_TOKEN_HEADER = "X-MedPilot-Service-Token";

    @Bean
    @Qualifier(CLIENT_BEAN)
    public WebClient aiServiceWebClient(
            WebClient.Builder builder,
            @Value("${medpilot.ai-service-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${medpilot.ai-service-token}") String serviceToken,
            @Value("${medpilot.ai-connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${medpilot.ai-response-timeout-seconds:120}") long responseTimeoutSeconds) {
        return buildClient(builder, baseUrl, serviceToken, connectTimeoutMs, responseTimeoutSeconds);
    }

    public WebClient aiServiceWebClient(
            WebClient.Builder builder,
            String baseUrl,
            String serviceToken) {
        return buildClient(builder, baseUrl, serviceToken, 3_000, 120);
    }

    private WebClient buildClient(
            WebClient.Builder builder,
            String baseUrl,
            String serviceToken,
            int connectTimeoutMs,
            long responseTimeoutSeconds) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalStateException("MEDPILOT_AI_SERVICE_TOKEN is required");
        }
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(100, connectTimeoutMs))
                .responseTimeout(Duration.ofSeconds(Math.max(1, responseTimeoutSeconds)));
        return builder
                .baseUrl(baseUrl)
                .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
