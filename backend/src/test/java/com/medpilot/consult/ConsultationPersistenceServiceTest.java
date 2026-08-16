package com.medpilot.consult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(ConsultationPersistenceService.class)
@ActiveProfiles("test")
class ConsultationPersistenceServiceTest {

    private static final String TRACE_ID = "2c293933-6590-4bfc-b0e8-507d3063c90b";
    private static final String SESSION_ID = "1779673a-c983-47e4-9715-f2d9548f469a";
    private static final String CITATIONS = """
            [{"citation_id":"resp-1#0","doc_id":"resp-1","chunk_id":"resp-1#0","source":"呼吸指南","department":"呼吸内科","quote":"咳嗽伴发热应评估。","score":0.86,"index_version":"v1"}]
            """;
    private static final String EVENTS = """
            [{"protocol_version":"1.0","trace_id":"2c293933-6590-4bfc-b0e8-507d3063c90b","sequence":1,"type":"done"}]
            """;

    @Autowired
    ConsultationPersistenceService service;

    @Autowired
    ConsultationTraceRepository traces;

    @Autowired
    ConsultationRecordRepository records;

    @BeforeEach
    void clear() {
        records.deleteAll();
        traces.deleteAll();
    }

    @Test
    void finalAnswerPersistsTraceAndRecordInOneOperation() {
        service.persist(2L, "咳嗽三天", "{\"text\":\"咳嗽三天\"}", finalSnapshot());

        ConsultationTrace trace = traces.findByTraceId(TRACE_ID).orElseThrow();
        ConsultationRecord record = records.findByTraceId(TRACE_ID).orElseThrow();
        assertThat(trace.getUserId()).isEqualTo(2L);
        assertThat(trace.getEventsJson()).isEqualTo(EVENTS);
        assertThat(trace.getCitationsJson()).isEqualTo(CITATIONS);
        assertThat(record.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(record.getCitations()).isEqualTo(CITATIONS);
        assertThat(record.getSymptoms()).isEqualTo("咳嗽");
        assertThat(record.getSupportScore()).isEqualTo(0.74);
        assertThat(record.getExplanation()).contains("证据");
    }

    @Test
    void followupDonePersistsTraceButNotFinalRecord() {
        ConsultationEventAccumulator.Snapshot followup = new ConsultationEventAccumulator.Snapshot(
                TRACE_ID, SESSION_ID, "头痛", null, null, null, null, null, null,
                "[]", EVENTS, true, "completed", false
        );

        service.persist(2L, "头痛", "{\"text\":\"头痛\"}", followup);

        assertThat(traces.findByTraceId(TRACE_ID)).isPresent();
        assertThat(records.findByTraceId(TRACE_ID)).isEmpty();
    }

    @Test
    void duplicateTraceIsIdempotent() {
        service.persist(2L, "咳嗽三天", "{}", finalSnapshot());
        service.persist(2L, "咳嗽三天", "{}", finalSnapshot());

        assertThat(traces.count()).isEqualTo(1);
        assertThat(records.count()).isEqualTo(1);
    }

    @Test
    void failureTracePersistsWithoutCreatingAClinicalRecord() {
        var failure = new ConsultationEventAccumulator.Snapshot(
                TRACE_ID,
                SESSION_ID,
                "headache",
                null,
                null,
                null,
                null,
                null,
                null,
                "[]",
                "[{\"type\":\"error\",\"data\":{\"code\":\"inference_timeout\"}}]",
                false,
                "failed",
                false,
                null,
                null,
                "[]",
                false,
                "inference_timeout",
                900L
        );

        service.persistFailure(2L, failure);

        ConsultationTrace trace = traces.findByTraceId(TRACE_ID).orElseThrow();
        assertThat(trace.getFailureCode()).isEqualTo("inference_timeout");
        assertThat(trace.getTotalDurationMs()).isEqualTo(900L);
        assertThat(records.findByTraceId(TRACE_ID)).isEmpty();
    }

    private ConsultationEventAccumulator.Snapshot finalSnapshot() {
        return new ConsultationEventAccumulator.Snapshot(
                TRACE_ID,
                SESSION_ID,
                "咳嗽",
                "呼吸内科",
                "中",
                0.86,
                "建议尽快就诊",
                null,
                "建议前往呼吸内科。",
                CITATIONS,
                EVENTS,
                false,
                "completed",
                true,
                0.74,
                "检索证据主要支持呼吸内科；该分数不是临床准确率。",
                "[{\"kind\":\"evidence\",\"label\":\"呼吸指南\",\"support\":0.74}]",
                false
        );
    }
}
