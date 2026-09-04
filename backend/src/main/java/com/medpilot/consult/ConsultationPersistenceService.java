package com.medpilot.consult;

import com.medpilot.clinicalreview.ClinicalReview;
import com.medpilot.clinicalreview.ClinicalReviewRepository;
import com.medpilot.hospital.PatientEncounterContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsultationPersistenceService {

    private final ConsultationTraceRepository traces;
    private final ConsultationRecordRepository records;
    private final ConsultationMessageService messages;
    private final ClinicalReviewRepository clinicalReviews;

    @Autowired
    public ConsultationPersistenceService(
            ConsultationTraceRepository traces,
            ConsultationRecordRepository records,
            ConsultationMessageService messages,
            ClinicalReviewRepository clinicalReviews) {
        this.traces = traces;
        this.records = records;
        this.messages = messages;
        this.clinicalReviews = clinicalReviews;
    }

    /** Kept for small isolated adapters that do not enable the review module. */
    public ConsultationPersistenceService(
            ConsultationTraceRepository traces,
            ConsultationRecordRepository records,
            ConsultationMessageService messages) {
        this(traces, records, messages, null);
    }

    /** Backward-compatible constructor for legacy unit adapters. */
    public ConsultationPersistenceService(
            ConsultationTraceRepository traces,
            ConsultationRecordRepository records) {
        this(traces, records, null, null);
    }

    @Transactional
    public void persist(
            Long userId,
            String requestText,
            String requestJson,
            ConsultationEventAccumulator.Snapshot snapshot) {
        persist(userId, requestText, requestJson, snapshot, null);
    }

    @Transactional
    public void persist(
            Long userId,
            String requestText,
            String requestJson,
            ConsultationEventAccumulator.Snapshot snapshot,
            PatientEncounterContext patientContext) {
        if (traces.existsByTraceId(snapshot.traceId())) {
            return;
        }

        traces.save(new ConsultationTrace(userId, snapshot));
        if (messages != null && snapshot.answer() != null && !snapshot.answer().isBlank()) {
            messages.appendAssistant(
                    userId, snapshot.sessionId(), snapshot.traceId(), snapshot.answer());
        }
        if (!snapshot.shouldCreateRecord()) {
            return;
        }

        ConsultationRecord record = new ConsultationRecord(userId, snapshot.sessionId());
        record.setTraceId(snapshot.traceId());
        if (patientContext != null) {
            record.setPatientContext(
                    patientContext.patientMpiId(),
                    patientContext.encounterNumber(),
                    patientContext.organizationCode(),
                    patientContext.campusCode(),
                    patientContext.departmentCode());
        }
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
        String history = messages == null
                ? "[]"
                : messages.conversationHistoryJson(userId, snapshot.sessionId());
        record.setConversationHistory("[]".equals(history) ? requestJson : history);
        ConsultationRecord saved = records.save(record);
        // The raw AI result is immutable from the review workflow. Persist a
        // separate safety-gate row in the same transaction and keep creation
        // idempotent for retries or duplicate stream delivery.
        if (clinicalReviews != null && saved.getId() != null
                && clinicalReviews.findByConsultationRecordId(saved.getId()).isEmpty()) {
            clinicalReviews.save(new ClinicalReview(saved));
        }
    }

    /** Persist a failed execution for audit/operations, without creating a patient result. */
    @Transactional
    public void persistFailure(Long userId, ConsultationEventAccumulator.Snapshot snapshot) {
        if (traces.existsByTraceId(snapshot.traceId())) {
            return;
        }
        traces.save(new ConsultationTrace(userId, snapshot));
    }

    /** Persist a cancelled trace without creating a clinical result. */
    @Transactional
    public void persistCancellation(Long userId, ConsultationEventAccumulator.Snapshot snapshot) {
        if (!"cancelled".equals(snapshot.terminalPhase())) {
            throw new IllegalArgumentException("cancellation snapshot must be terminal cancelled");
        }
        if (traces.existsByTraceId(snapshot.traceId())) return;
        traces.save(new ConsultationTrace(userId, snapshot));
    }
}
