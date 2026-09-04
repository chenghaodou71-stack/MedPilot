package com.medpilot.health;

import com.medpilot.common.ApiResponse;
import com.medpilot.runtime.RedisRuntimeState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void requiredRedisFailureMakesBackendHealthDegraded() {
        RedisRuntimeState sharedState = mock(RedisRuntimeState.class);
        when(sharedState.isRequired()).thenReturn(true);
        when(sharedState.shouldUseSharedState()).thenReturn(true);
        when(sharedState.isEnabled()).thenReturn(true);
        when(sharedState.isAvailable()).thenReturn(false);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                new HealthController(WebClient.builder().build(), sharedState).health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = response.getBody().data();
        assertThat(data).containsKey("shared_state");
        Map<?, ?> state = (Map<?, ?>) data.get("shared_state");
        assertThat(state.get("required")).isEqualTo(true);
        assertThat(state.get("ok")).isEqualTo(false);
    }

    @Test
    void preservesReadinessStatusAndComponentDetails() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse
                        .create(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"status":"degraded","components":{
                                  "ollama":{"ok":false},
                                  "chat_model":{"ok":true},
                                  "embed_model":{"ok":true},
                                  "index":{"ok":true}
                                }}
                                """)
                        .build()))
                .build();

        ResponseEntity<ApiResponse<Object>> response =
                new HealthController(client).aiHealth().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error()).isEqualTo("AI service not ready");
        assertThat(response.getBody().data()).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("status")).isEqualTo("degraded");
        assertThat(data.get("components")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) data.get("components");
        assertThat(components).containsKeys(
                "ollama", "chat_model", "embed_model", "index");
    }

    @Test
    void mapsAnUnreachableAiServiceToBadGateway() {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException("private host")))
                .build();

        ResponseEntity<ApiResponse<Object>> response =
                new HealthController(client).aiHealth().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().error()).isEqualTo("ai-service unavailable");
    }
}
