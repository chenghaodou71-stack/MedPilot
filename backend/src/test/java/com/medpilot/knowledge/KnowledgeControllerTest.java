package com.medpilot.knowledge;

import com.medpilot.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeControllerTest {

    private final AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();

    @Test
    void listsVersionsAndCurrentActiveVersion() {
        KnowledgeController controller = controllerReturning(
                HttpStatus.OK,
                """
                {"current":"v2","versions":[{"version":"v2","active":true}]}
                """);

        ResponseEntity<ApiResponse<Object>> response = controller.versions().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedRequest.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(capturedRequest.get().url().getPath()).isEqualTo("/knowledge/versions");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("current")).isEqualTo("v2");
    }

    @Test
    void buildsDraftVersion() {
        KnowledgeController controller = controllerReturning(
                HttpStatus.CREATED,
                """
                {"version":"v3","document_count":2,"chunk_count":8}
                """);

        ResponseEntity<ApiResponse<Object>> response = controller.buildVersion().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedRequest.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(capturedRequest.get().url().getPath()).isEqualTo("/knowledge/versions/build");
    }

    @Test
    void activatesSelectedVersion() {
        KnowledgeController controller = controllerReturning(
                HttpStatus.OK,
                """
                {"active":"v3","manifest":{"version":"v3"}}
                """);

        ResponseEntity<ApiResponse<Object>> response = controller.activateVersion("v3").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedRequest.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(capturedRequest.get().url().getPath())
                .isEqualTo("/knowledge/versions/v3/activate");
    }

    @Test
    void preservesUpstreamActivationErrorStatus() {
        KnowledgeController controller = controllerReturning(
                HttpStatus.NOT_FOUND,
                """
                {"detail":"version 'missing' not found"}
                """);

        ResponseEntity<ApiResponse<Object>> response = controller.activateVersion("missing").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error()).contains("version 'missing' not found");
    }

    @Test
    void reportsBadGatewayWhenVersionServiceIsUnavailable() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException("offline")));
        KnowledgeController controller = new KnowledgeController(
                builder.baseUrl("http://ai-service.test").build());

        ResponseEntity<ApiResponse<Object>> response = controller.versions().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("ai-service unreachable");
        assertThat(response.getBody().error()).doesNotContain("offline");
    }

    private KnowledgeController controllerReturning(HttpStatus status, String body) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedRequest.set(request);
                    ClientResponse response = ClientResponse
                            .create(status, ExchangeStrategies.withDefaults())
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build();
                    return Mono.just(response);
                });
        return new KnowledgeController(builder.baseUrl("http://ai-service.test").build());
    }
}
