package com.medpilot.consult;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsultControllerEventParsingTest {

    private static final String SESSION_ID = "1779673a-c983-47e4-9715-f2d9548f469a";
    private static final String TRACE_ID = "2c293933-6590-4bfc-b0e8-507d3063c90b";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsCompleteProtocolAndKeepsStructuredEvidence() throws Exception {
        ConsultationEventAccumulator accumulator = accumulator();
        List<String> events = List.of(
                node(1, "safety_screen", "started", "screening", "{}"),
                node(2, "safety_screen", "completed", "screening",
                        "{\"safety\":{\"matched\":false}}"),
                node(3, "extract", "started", "collecting", "{}"),
                node(4, "extract", "completed", "collecting",
                        "{\"symptoms\":{\"symptoms\":[\"咳嗽\",\"低热\"],\"raw_text\":\"咳嗽三天\"}}"),
                node(5, "retrieve", "started", "retrieving", "{}"),
                node(6, "retrieve", "completed", "retrieving",
                        "{\"evidence\":[{\"citation_id\":\"resp-1#0\",\"doc_id\":\"resp-1\",\"chunk_id\":\"resp-1#0\",\"source\":\"呼吸指南\",\"department\":\"呼吸内科\",\"quote\":\"咳嗽伴发热应评估。\",\"score\":0.86,\"index_version\":\"v1\"}]}"),
                node(7, "classify", "started", "triaging", "{}"),
                node(8, "classify", "completed", "triaging",
                        "{\"triage\":{\"department\":\"呼吸内科\",\"risk_level\":\"中\",\"confidence\":0.86,\"urgency\":\"建议尽快就诊\",\"matched_rule\":null}}"),
                node(9, "compose", "started", "composing", "{}"),
                node(10, "compose", "completed", "composing",
                        "{\"answer\":{\"text\":\"建议前往呼吸内科。\",\"citations\":[{\"citation_id\":\"resp-1#0\",\"doc_id\":\"resp-1\",\"chunk_id\":\"resp-1#0\",\"source\":\"呼吸指南\",\"department\":\"呼吸内科\",\"quote\":\"咳嗽伴发热应评估。\",\"score\":0.86,\"index_version\":\"v1\"}],\"safety_boundary\":\"不替代执业医生。\"}}"),
                done(11, "completed")
        );

        events.forEach(accumulator::accept);
        ConsultationEventAccumulator.Snapshot snapshot = accumulator.finish();

        assertThat(snapshot.traceId()).isEqualTo(TRACE_ID);
        assertThat(snapshot.sessionId()).isEqualTo(SESSION_ID);
        assertThat(snapshot.symptoms()).isEqualTo("咳嗽、低热");
        assertThat(snapshot.department()).isEqualTo("呼吸内科");
        assertThat(snapshot.answer()).isEqualTo("建议前往呼吸内科。");
        assertThat(snapshot.shouldCreateRecord()).isTrue();
        assertThat(mapper.readTree(snapshot.citationsJson())).hasSize(1);
        assertThat(mapper.readTree(snapshot.citationsJson()).get(0).path("quote").asText())
                .isEqualTo("咳嗽伴发热应评估。");
        assertThat(mapper.readTree(snapshot.eventsJson())).hasSize(11);
    }

    @Test
    void acceptsFollowupDoneButDoesNotCreateFinalRecord() {
        ConsultationEventAccumulator accumulator = accumulator();
        accumulator.accept(node(1, "safety_screen", "started", "screening", "{}"));
        accumulator.accept(node(2, "safety_screen", "completed", "screening", "{}"));
        accumulator.accept(node(3, "extract", "started", "collecting", "{}"));
        accumulator.accept(node(4, "extract", "completed", "collecting",
                "{\"symptoms\":{\"symptoms\":[\"头痛\"]}}"));
        accumulator.accept(node(5, "ask_followup", "started", "awaiting_followup", "{}"));
        accumulator.accept(node(6, "ask_followup", "completed", "awaiting_followup",
                "{\"followup\":{\"question\":\"持续多久了？\"}}"));
        accumulator.accept(done(7, "completed"));

        ConsultationEventAccumulator.Snapshot snapshot = accumulator.finish();

        assertThat(snapshot.followupPending()).isTrue();
        assertThat(snapshot.shouldCreateRecord()).isFalse();
    }

    @Test
    void rejectsOutOfOrderSequence() {
        ConsultationEventAccumulator accumulator = accumulator();
        accumulator.accept(node(1, "extract", "started", "collecting", "{}"));

        assertThatThrownBy(() -> accumulator.accept(
                node(3, "extract", "completed", "collecting", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }

    @Test
    void rejectsMismatchedTraceOrSession() {
        ConsultationEventAccumulator accumulator = accumulator();
        accumulator.accept(node(1, "extract", "started", "collecting", "{}"));

        String wrongSession = node(2, "extract", "completed", "collecting", "{}")
                .replace(SESSION_ID, "b4d3f238-f0d5-4a51-a232-e0316ca0b4e9");
        assertThatThrownBy(() -> accumulator.accept(wrongSession))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session_id");
    }

    @Test
    void rejectsDoneWhenNodeIsStillRunning() {
        ConsultationEventAccumulator accumulator = accumulator();
        accumulator.accept(node(1, "extract", "started", "collecting", "{}"));
        accumulator.accept(done(2, "completed"));

        assertThatThrownBy(accumulator::finish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unfinished");
    }

    @Test
    void rejectsEventsAfterDone() {
        ConsultationEventAccumulator accumulator = accumulator();
        accumulator.accept(done(1, "completed"));

        assertThatThrownBy(() -> accumulator.accept(
                node(2, "extract", "started", "collecting", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("done");
    }

    @Test
    void rejectsUnknownStateEnum() {
        ConsultationEventAccumulator accumulator = accumulator();
        String invalid = node(1, "extract", "started", "collecting", "{}")
                .replace("\"collecting\"", "\"pending\"");

        assertThatThrownBy(() -> accumulator.accept(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phase");
    }

    private ConsultationEventAccumulator accumulator() {
        return new ConsultationEventAccumulator(mapper, SESSION_ID);
    }

    private String node(int sequence, String node, String status, String phase, String data) {
        return "{\"protocol_version\":\"1.0\",\"trace_id\":\"" + TRACE_ID
                + "\",\"session_id\":\"" + SESSION_ID + "\",\"sequence\":" + sequence
                + ",\"type\":\"node\",\"node\":\"" + node + "\",\"status\":\""
                + status + "\",\"elapsed_ms\":1,\"state\":{\"intent\":\"medical_consult\","
                + "\"phase\":\"" + phase + "\",\"turn_count\":1,\"history_mode\":\"full\"},"
                + "\"data\":" + data + "}";
    }

    private String done(int sequence, String phase) {
        return "{\"protocol_version\":\"1.0\",\"trace_id\":\"" + TRACE_ID
                + "\",\"session_id\":\"" + SESSION_ID + "\",\"sequence\":" + sequence
                + ",\"type\":\"done\",\"status\":\"completed\",\"elapsed_ms\":0,"
                + "\"state\":{\"intent\":\"medical_consult\",\"phase\":\"" + phase
                + "\",\"turn_count\":1,\"history_mode\":\"full\"},\"data\":{}}";
    }
}
