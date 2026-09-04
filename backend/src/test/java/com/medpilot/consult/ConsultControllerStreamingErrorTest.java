package com.medpilot.consult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.common.AiServiceUnavailableException;
import com.medpilot.user.Role;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import com.medpilot.health.HealthProfileContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsultControllerStreamingErrorTest {

    private static final String SESSION_ID = "1779673a-c983-47e4-9715-f2d9548f469a";
    private static final String TRACE_ID = "2c293933-6590-4bfc-b0e8-507d3063c90b";

    private final AiConsultClient aiClient = mock(AiConsultClient.class);
    private final SessionOwnershipService ownership = mock(SessionOwnershipService.class);
    private final ConsultationPersistenceService persistence = mock(ConsultationPersistenceService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final HealthProfileContextService healthProfiles = mock(HealthProfileContextService.class);
    private final Authentication authentication = mock(Authentication.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ConsultController controller;

    @BeforeEach
    void setUp() {
        when(authentication.getName()).thenReturn("user");
        User user = mock(User.class);
        when(user.getId()).thenReturn(2L);
        when(users.findByUsername("user")).thenReturn(Optional.of(user));
        when(healthProfiles.resolveForUser(2L)).thenReturn(Map.of());
        controller = new ConsultController(
                aiClient, ownership, persistence, users, healthProfiles, mapper);
    }

    @Test
    void mapsAnUpstreamFailureBeforeTheFirstEventToBadGatewayException() {
        when(aiClient.openConsult("text", SESSION_ID, Map.of(), List.of()))
                .thenReturn(Mono.error(new IllegalStateException("offline")));

        assertThatThrownBy(() -> controller.consult(request(), authentication).block())
                .isInstanceOf(AiServiceUnavailableException.class)
                .hasMessageNotContaining("offline");
        verify(persistence).persistFailure(anyLong(), any());
    }

    @Test
    void turnsAnUpstreamFailureAfterStreamingStartsIntoAProtocolErrorEvent() throws Exception {
        when(aiClient.openConsult("text", SESSION_ID, Map.of(), List.of())).thenReturn(Mono.just(Flux.concat(
                Flux.just(nodeStarted()),
                Flux.error(new IllegalStateException("connection reset")))));

        ResponseEntity<Flux<String>> response = controller.consult(request(), authentication).block();
        List<String> lines = response.getBody().collectList().block();

        assertThat(lines).hasSize(2);
        JsonNode error = mapper.readTree(lines.get(1));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("status").asText()).isEqualTo("error");
        assertThat(error.path("trace_id").asText()).isEqualTo(TRACE_ID);
        assertThat(error.path("session_id").asText()).isEqualTo(SESSION_ID);
        assertThat(error.path("data").path("code").asText()).isEqualTo("UPSTREAM_STREAM_ERROR");
        assertThat(lines.get(1)).doesNotContain("connection reset");
    }

    @Test
    void reportsPersistenceFailureInsteadOfSilentlyCompleting() throws Exception {
        when(aiClient.openConsult("text", SESSION_ID, Map.of(), List.of()))
                .thenReturn(Mono.just(Flux.just(done())));
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistence).persist(anyLong(), any(), any(), any());

        ResponseEntity<Flux<String>> response = controller.consult(request(), authentication).block();
        List<String> lines = response.getBody().collectList().block();

        assertThat(lines).hasSize(1);
        JsonNode error = mapper.readTree(lines.get(0));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("data").path("code").asText()).isEqualTo("PERSISTENCE_ERROR");
        assertThat(lines.get(0)).doesNotContain("database unavailable");
    }

    @Test
    void forwardsOneUpstreamTerminalErrorWithoutAppendingAnother() throws Exception {
        when(aiClient.openConsult("text", SESSION_ID, Map.of(), List.of()))
                .thenReturn(Mono.just(Flux.just(error())));

        ResponseEntity<Flux<String>> response = controller.consult(request(), authentication).block();
        List<String> lines = response.getBody().collectList().block();

        assertThat(lines).hasSize(1);
        JsonNode error = mapper.readTree(lines.get(0));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("data").path("code").asText())
                .isEqualTo("inference_timeout");
        verify(persistence).persistFailure(anyLong(), any());
    }

    @Test
    void retriesATransientFailureBeforeTheFirstEvent() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        when(aiClient.openConsult("text", SESSION_ID, Map.of(), List.of())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                return Mono.error(new AiServiceUnavailableException());
            }
            return Mono.just(Flux.just(done()));
        });

        ResponseEntity<Flux<String>> response = controller.consult(request(), authentication).block();
        List<String> lines = response.getBody().collectList().block();

        assertThat(attempts).hasValue(2);
        assertThat(lines).hasSize(1);
        assertThat(mapper.readTree(lines.get(0)).path("type").asText()).isEqualTo("done");
    }

    @Test
    void neverRetriesAfterAnEventHasBeenEmitted() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        when(aiClient.openConsult("text", SESSION_ID, Map.of(), List.of())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            return Mono.just(Flux.concat(Flux.just(nodeStarted()),
                    Flux.error(new IllegalStateException("mid-stream failure"))));
        });

        ResponseEntity<Flux<String>> response = controller.consult(request(), authentication).block();
        List<String> lines = response.getBody().collectList().block();

        assertThat(attempts).hasValue(1);
        assertThat(lines).hasSize(2);
        assertThat(mapper.readTree(lines.get(1)).path("data").path("code").asText())
                .isEqualTo("UPSTREAM_STREAM_ERROR");
        verify(persistence).persistFailure(anyLong(), any());
    }

    @Test
    void forwardsTheEligibleProfileOnceWithoutMutatingTheOriginalText() throws Exception {
        Map<String, String> expectedContext = Map.of(
                "allergies", "青霉素",
                "conditions", "哮喘");
        when(healthProfiles.resolveForUser(2L)).thenReturn(expectedContext);
        AtomicReference<Map<String, String>> receivedContext = new AtomicReference<>();
        when(aiClient.openConsult("text", SESSION_ID, expectedContext, List.of())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, String> context = invocation.getArgument(2);
            receivedContext.set(context);
            return Mono.just(Flux.just(done()));
        });

        ResponseEntity<Flux<String>> response = controller.consult(request(), authentication).block();
        response.getBody().collectList().block();

        assertThat(receivedContext).hasValue(expectedContext);
        verify(healthProfiles).resolveForUser(2L);
    }

    private ConsultController.ConsultRequest request() {
        return new ConsultController.ConsultRequest("text", SESSION_ID);
    }

    private String nodeStarted() {
        return envelope(1, "\"type\":\"node\",\"node\":\"extract\",\"status\":\"started\"",
                "collecting");
    }

    private String done() {
        return envelope(1, "\"type\":\"done\",\"status\":\"completed\"", "completed");
    }

    private String error() {
        return envelope(
                1,
                "\"type\":\"error\",\"status\":\"error\"",
                "failed",
                "{\"code\":\"inference_timeout\",\"detail\":\"consultation timed out\"}");
    }

    private String envelope(int sequence, String fields, String phase) {
        return envelope(sequence, fields, phase, "{}");
    }

    private String envelope(int sequence, String fields, String phase, String data) {
        return "{\"protocol_version\":\"1.0\",\"trace_id\":\"" + TRACE_ID
                + "\",\"session_id\":\"" + SESSION_ID + "\",\"sequence\":" + sequence
                + "," + fields + ",\"elapsed_ms\":1,\"state\":{\"intent\":\"medical_consult\","
                + "\"phase\":\"" + phase + "\",\"turn_count\":1,\"history_mode\":\"full\"},"
                + "\"data\":" + data + "}";
    }
}
