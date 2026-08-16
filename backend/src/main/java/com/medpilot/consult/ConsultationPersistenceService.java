package com.medpilot.consult;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsultationPersistenceService {

    private final ConsultationTraceRepository traces;
    private final ConsultationRecordRepository records;

    public ConsultationPersistenceService(
            ConsultationTraceRepository traces,
            ConsultationRecordRepository records) {
        this.traces = traces;
        this.records = records;
    }

    @Transactional
    public void persist(
            Long userId,
            String requestText,
            String requestJson,
            ConsultationEventAccumulator.Snapshot snapshot) {
        if (traces.existsByTraceId(snapshot.traceId())) {
            return;
        }

        traces.save(new ConsultationTrace(userId, snapshot));
        if (!snapshot.shouldCreateRecord()) {
            return;
        }

        ConsultationRecord record = new ConsultationRecord(userId, snapshot.sessionId());
        record.setTraceId(snapshot.traceId());
        record.setSymptoms(snapshot.symptoms() != null ? snapshot.symptoms() : requestText);
        record.setDepartment(snapshot.department());
        record.setRiskLevel(snapshot.riskLevel());
        record.setConfidence(snapshot.confidence());
        record.setSupportScore(snapshot.supportScore());
        record.setAbstained(snapshot.abstained());
        record.setUrgency(snapshot.urgency());
        record.setMatchedRule(snapshot.matchedRule());
        record.setExplanation(snapshot.explanation());
        record.setTriageFactors(snapshot.triageFactorsJson());
        record.setAnswer(snapshot.answer());
        record.setCitations(snapshot.citationsJson());
        record.setConversationHistory(requestJson);
        records.save(record);
    }

    /** Persist a failed execution for audit/operations, without creating a patient result. */
    @Transactional
    public void persistFailure(Long userId, ConsultationEventAccumulator.Snapshot snapshot) {
        if (traces.existsByTraceId(snapshot.traceId())) {
            return;
        }
        traces.save(new ConsultationTrace(userId, snapshot));
    }
}
