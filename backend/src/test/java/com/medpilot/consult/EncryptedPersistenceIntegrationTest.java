package com.medpilot.consult;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EncryptedPersistenceIntegrationTest {

    private static final String TRACE_ID = "7d76223a-aa5c-4e93-b00f-0a288060af19";
    private static final String SESSION_ID = "1179673a-c983-47e4-9715-f2d9548f469a";

    @Autowired ConsultationPersistenceService service;
    @Autowired ConsultationTraceRepository traces;
    @Autowired ConsultationRecordRepository records;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void clear() {
        records.deleteAll();
        traces.deleteAll();
    }

    @Test
    void storesSensitiveMedicalFieldsAsAuthenticatedCiphertext() {
        var snapshot = new ConsultationEventAccumulator.Snapshot(
                TRACE_ID, SESSION_ID, "sensitive symptom", "department", "medium", 0.8,
                "soon", null, "sensitive answer", "[{\"source\":\"sensitive citation\"}]",
                "[{\"detail\":\"sensitive event\"}]", false, "completed", true);

        service.persist(2L, "request symptom", "{\"text\":\"sensitive conversation\"}", snapshot);
        entityManager.flush();

        Map<String, Object> rawRecord = jdbc.queryForMap(
                "select symptoms, answer, citations, conversation_history from consultation_records where trace_id = ?",
                TRACE_ID);
        Map<String, Object> rawTrace = jdbc.queryForMap(
                "select events_json, citations_json from consultation_traces where trace_id = ?",
                TRACE_ID);

        assertThat(rawRecord.values()).allSatisfy(value ->
                assertThat(String.valueOf(value)).startsWith("enc:v1:").doesNotContain("sensitive"));
        assertThat(rawTrace.values()).allSatisfy(value ->
                assertThat(String.valueOf(value)).startsWith("enc:v1:").doesNotContain("sensitive"));

        entityManager.clear();
        assertThat(records.findByTraceId(TRACE_ID).orElseThrow().getAnswer())
                .isEqualTo("sensitive answer");
        assertThat(traces.findByTraceId(TRACE_ID).orElseThrow().getEventsJson())
                .contains("sensitive event");
    }

    @Test
    void storesEncryptedTracePayloadsLargerThanTinyText() {
        String largeEventValue = "event-" + "x".repeat(4_096);
        String largeCitationValue = "citation-" + "y".repeat(4_096);
        String eventsJson = "[{\"detail\":\"" + largeEventValue + "\"}]";
        String citationsJson = "[{\"quote\":\"" + largeCitationValue + "\"}]";
        var snapshot = new ConsultationEventAccumulator.Snapshot(
                "8e87334b-bb6d-4fa4-c11f-1b399171bf20",
                "2280784b-d094-48f5-a26f-03e0659f570b",
                null, null, null, null, null, null, null,
                citationsJson, eventsJson, false, "escalated", false);

        service.persist(2L, "emergency request", "[]", snapshot);
        entityManager.flush();
        entityManager.clear();

        ConsultationTrace stored = traces.findByTraceId(snapshot.traceId()).orElseThrow();
        assertThat(stored.getEventsJson()).isEqualTo(eventsJson);
        assertThat(stored.getCitationsJson()).isEqualTo(citationsJson);
        Map<String, Object> rawTrace = jdbc.queryForMap(
                "select events_json, citations_json from consultation_traces where trace_id = ?",
                snapshot.traceId());
        assertThat(String.valueOf(rawTrace.get("events_json"))).hasSizeGreaterThan(255);
        assertThat(String.valueOf(rawTrace.get("citations_json"))).hasSizeGreaterThan(255);
    }
}
