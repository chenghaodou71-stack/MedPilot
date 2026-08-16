package com.medpilot.consult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.http.HttpMethod;
import org.springframework.util.MimeType;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.ClientRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebClientAiConsultClientHealthContextTest {

    @Test
    void sendsHealthContextAsAnIndependentFieldWithoutChangingUserText() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> captureBody(request)
                        .doOnNext(requestBody::set)
                        .thenReturn(ClientResponse.create(HttpStatus.OK).body("{}\n").build()))
                .build();
        WebClientAiConsultClient ai = new WebClientAiConsultClient(client);
        Map<String, String> context = new LinkedHashMap<>();
        context.put("allergies", "青霉素");
        context.put("conditions", "哮喘");

        ai.consult("当前咳嗽", "session-1", context).collectList().block();

        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertThat(body.path("text").asText()).isEqualTo("当前咳嗽");
        assertThat(body.path("health_context").path("allergies").asText()).isEqualTo("青霉素");
        assertThat(body.path("health_context").path("conditions").asText()).isEqualTo("哮喘");
    }

    @Test
    void omitsEmptyHealthContextInsteadOfSendingAnImplicitProfile() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> captureBody(request)
                        .doOnNext(requestBody::set)
                        .thenReturn(ClientResponse.create(HttpStatus.OK).body("{}\n").build()))
                .build();

        new WebClientAiConsultClient(client)
                .consult("当前咳嗽", "session-1", Map.of())
                .collectList().block();

        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertThat(body.has("health_context")).isFalse();
    }

    private Mono<String> captureBody(ClientRequest request) {
        MockClientHttpRequest response = new MockClientHttpRequest(
                HttpMethod.POST, java.net.URI.create("http://localhost/consult"));
        java.util.concurrent.atomic.AtomicReference<String> captured =
                new java.util.concurrent.atomic.AtomicReference<>("");
        response.setWriteHandler(body -> DataBufferUtils.join(body)
                .doOnNext(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    captured.set(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                })
                .then());
        BodyInserter.Context context = new BodyInserter.Context() {
            @Override
            public java.util.List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public java.util.Optional<org.springframework.http.server.reactive.ServerHttpRequest>
            serverRequest() {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Map<String, Object> hints() {
                return java.util.Map.of();
            }
        };
        return request.body().insert(response, context)
                .then(Mono.fromSupplier(captured::get));
    }
}
