package com.medpilot.clinicalreview;

import com.medpilot.consult.ConsultationRecord;
import com.medpilot.consult.ConsultationRecordRepository;
import com.medpilot.hospital.HospitalRecordAccessService;
import com.medpilot.user.Role;
import com.medpilot.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Application service for the clinical safety gate. Every operation performs
 * the same staff/MFA/care-relationship checks, including queue listing, so a
 * reviewer cannot use a list endpoint to discover unrelated patients.
 */
@Service
public class ClinicalReviewService {

    private final ClinicalReviewRepository reviews;
    private final ConsultationRecordRepository records;
    private final HospitalRecordAccessService recordAccess;

    public ClinicalReviewService(
            ClinicalReviewRepository reviews,
            ConsultationRecordRepository records,
            HospitalRecordAccessService recordAccess) {
        this.reviews = reviews;
        this.records = records;
        this.recordAccess = recordAccess;
    }

    /** Idempotently creates the safety gate after a final AI record is stored. */
    @Transactional
    public ClinicalReview ensureForRecord(ConsultationRecord record) {
        if (record == null || record.getId() == null) {
            throw new IllegalArgumentException("a persisted consultation record is required");
        }
        return reviews.findByConsultationRecordId(record.getId())
                .orElseGet(() -> reviews.save(new ClinicalReview(record)));
    }

    /** Compatibility alias used by persistence adapters and integration code. */
    @Transactional
    public ClinicalReview createForRecord(ConsultationRecord record) {
        return ensureForRecord(record);
    }

    @Transactional(readOnly = true)
    public List<ClinicalReview> list(User actor, ClinicalReviewStatus status) {
        requireClinicalReviewer(actor);
        List<ClinicalReview> candidates = status == null
                ? reviews.findAllByOrderByCreatedAtDesc()
                : reviews.findByStatusOrderByCreatedAtDesc(status);
        return candidates.stream()
                .filter(review -> readableReview(actor, review))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClinicalReview get(User actor, String idOrReviewId) {
        requireClinicalReviewer(actor);
        ClinicalReview review = find(idOrReviewId);
        requireReadable(actor, review);
        return review;
    }

    @Transactional
    public ClinicalReview claim(User actor, String idOrReviewId) {
        requireClinicalReviewer(actor);
        ClinicalReview review = find(idOrReviewId);
        ConsultationRecord record = requireReadable(actor, review);
        rejectSelfReview(actor, record);
        review.claim(actor, Instant.now());
        return reviews.save(review);
    }

    @Transactional
    public ClinicalReview decide(
            User actor,
            String idOrReviewId,
            ClinicalReviewDecision decision,
            String finalDepartment,
            String finalRiskLevel,
            String finalUrgency,
            String reason) {
        requireClinicalReviewer(actor);
        ClinicalReview review = find(idOrReviewId);
        ConsultationRecord record = requireReadable(actor, review);
        rejectSelfReview(actor, record);
        review.decide(
                actor,
                decision,
                finalDepartment,
                finalRiskLevel,
                finalUrgency,
                reason,
                Instant.now());
        return reviews.save(review);
    }

    private ClinicalReview find(String idOrReviewId) {
        String normalized = idOrReviewId == null ? "" : idOrReviewId.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException("clinical review id is required");
        try {
            return reviews.findById(Long.valueOf(normalized))
                    .orElseThrow(() -> new IllegalArgumentException("clinical review not found"));
        } catch (NumberFormatException ignored) {
            return reviews.findByReviewId(normalized)
                    .orElseThrow(() -> new IllegalArgumentException("clinical review not found"));
        }
    }

    private ConsultationRecord requireReadable(User actor, ClinicalReview review) {
        ConsultationRecord record = records.findById(review.getConsultationRecordId())
                .orElseThrow(() -> new IllegalArgumentException("consultation record not found"));
        if (!recordAccess.canRead(actor, record)) {
            throw new SecurityException("clinical review is outside the active care relationship");
        }
        return record;
    }

    private boolean readableReview(User actor, ClinicalReview review) {
        try {
            ConsultationRecord record = requireReadable(actor, review);
            return !actor.getId().equals(record.getUserId());
        } catch (RuntimeException ignored) {
            // Filtering is deliberately fail-closed for stale or inaccessible records.
            return false;
        }
    }

    private void rejectSelfReview(User actor, ConsultationRecord record) {
        if (actor.getId().equals(record.getUserId())) {
            throw new SecurityException("a clinician cannot review their own consultation result");
        }
    }

    private static void requireClinicalReviewer(User actor) {
        if (actor == null || actor.getId() == null || !actor.isLoginEligibleAt(Instant.now())) {
            throw new SecurityException("authenticated clinician is required");
        }
        Role role = actor.getRole();
        if (role != Role.DOCTOR && role != Role.REVIEWER) {
            throw new SecurityException("only a doctor or clinical reviewer can operate a review");
        }
        if (!actor.hasHospitalStaffProfile() || actor.getEmployeeNumber() == null
                || actor.getEmployeeNumber().isBlank()) {
            throw new SecurityException("clinical review requires a verified hospital staff profile");
        }
        if (actor.getMfaAssuranceLevel() < 2) {
            throw new SecurityException("clinical review requires MFA assurance level 2 or higher");
        }
    }
}
