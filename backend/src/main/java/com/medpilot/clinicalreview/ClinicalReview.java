package com.medpilot.clinicalreview;

import com.medpilot.consult.ConsultationRecord;
import com.medpilot.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A separately persisted clinical safety decision for an immutable AI result.
 * The consultation record remains the source of the raw model output; this
 * aggregate stores only workflow state and the clinician's final fields.
 */
@Entity
@Table(name = "clinical_reviews")
public class ClinicalReview {

    private static final int MAX_REASON = 2_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, unique = true, length = 36)
    private String reviewId;

    @Column(name = "consultation_record_id", nullable = false, unique = true)
    private Long consultationRecordId;

    @Column(name = "patient_mpi_id", length = 128)
    private String patientMpiId;

    @Column(name = "organization_code", length = 64)
    private String organizationCode;

    @Column(name = "campus_code", length = 64)
    private String campusCode;

    @Column(name = "encounter_department_code", length = 64)
    private String encounterDepartmentCode;

    @Column(name = "ai_trace_id", length = 36)
    private String aiTraceId;

    @Column(name = "original_department", length = 128)
    private String originalDepartment;

    @Column(name = "original_risk_level", length = 32)
    private String originalRiskLevel;

    @Column(name = "original_urgency", columnDefinition = "TEXT")
    private String originalUrgency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ClinicalReviewStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ClinicalReviewDecision decision;

    @Column(name = "claimed_by_user_id")
    private Long claimedByUserId;

    @Column(name = "reviewer_user_id")
    private Long reviewerUserId;

    @Column(name = "reviewer_employee_number", length = 64)
    private String reviewerEmployeeNumber;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "final_department", length = 128)
    private String finalDepartment;

    @Column(name = "final_risk_level", length = 32)
    private String finalRiskLevel;

    @Column(name = "final_urgency", columnDefinition = "TEXT")
    private String finalUrgency;

    @Column(name = "decision_reason", columnDefinition = "LONGTEXT")
    private String decisionReason;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "emergency_escalated_at")
    private Instant emergencyEscalatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ClinicalReview() {
    }

    public ClinicalReview(ConsultationRecord record) {
        this(record, Instant.now());
    }

    ClinicalReview(ConsultationRecord record, Instant now) {
        if (record == null || record.getId() == null) {
            throw new IllegalArgumentException("a persisted consultation record is required");
        }
        Instant timestamp = now == null ? Instant.now() : now;
        this.reviewId = UUID.randomUUID().toString();
        this.consultationRecordId = record.getId();
        this.patientMpiId = record.getPatientMpiId();
        this.organizationCode = record.getOrganizationCode();
        this.campusCode = record.getCampusCode();
        this.encounterDepartmentCode = record.getEncounterDepartmentCode();
        this.aiTraceId = record.getTraceId();
        this.originalDepartment = record.getDepartment();
        this.originalRiskLevel = record.getRiskLevel();
        this.originalUrgency = record.getUrgency();
        this.createdAt = timestamp;
        this.updatedAt = timestamp;
        if (isHighRisk(record.getRiskLevel())
                || isEmergency(record.getUrgency())
                || isEmergencyRule(record.getMatchedRule())) {
            this.status = ClinicalReviewStatus.EMERGENCY_ESCALATED;
            this.decision = ClinicalReviewDecision.ESCALATE;
            this.decisionReason = "系统识别到高风险/急诊信号，已立即转人工急诊流程";
            this.emergencyEscalatedAt = timestamp;
        } else {
            this.status = ClinicalReviewStatus.PENDING_REVIEW;
        }
    }

    public Long getId() { return id; }
    public String getReviewId() { return reviewId; }
    public Long getConsultationRecordId() { return consultationRecordId; }
    public String getPatientMpiId() { return patientMpiId; }
    public String getOrganizationCode() { return organizationCode; }
    public String getCampusCode() { return campusCode; }
    public String getEncounterDepartmentCode() { return encounterDepartmentCode; }
    public String getAiTraceId() { return aiTraceId; }
    public String getOriginalDepartment() { return originalDepartment; }
    public String getOriginalRiskLevel() { return originalRiskLevel; }
    public String getOriginalUrgency() { return originalUrgency; }
    public ClinicalReviewStatus getStatus() { return status; }
    public ClinicalReviewDecision getDecision() { return decision; }
    public Long getClaimedByUserId() { return claimedByUserId; }
    public Long getReviewerUserId() { return reviewerUserId; }
    public String getReviewerEmployeeNumber() { return reviewerEmployeeNumber; }
    public Instant getClaimedAt() { return claimedAt; }
    public String getFinalDepartment() { return finalDepartment; }
    public String getFinalRiskLevel() { return finalRiskLevel; }
    public String getFinalUrgency() { return finalUrgency; }
    public String getDecisionReason() { return decisionReason; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getEmergencyEscalatedAt() { return emergencyEscalatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    /** Stable aliases for API adapters that use record/review terminology. */
    public Long getRecordId() { return consultationRecordId; }
    public ClinicalReviewStatus getReviewStatus() { return status; }
    public Long getReviewerId() { return reviewerUserId; }
    public String getReason() { return decisionReason; }

    public boolean isTerminal() {
        return status == ClinicalReviewStatus.CLINICIAN_CONFIRMED
                || status == ClinicalReviewStatus.CLINICIAN_MODIFIED
                || status == ClinicalReviewStatus.REJECTED
                || status == ClinicalReviewStatus.SYSTEM_FALLBACK;
    }

    public boolean canBeClaimedBy(Long userId) {
        if (status == ClinicalReviewStatus.EMERGENCY_ESCALATED) {
            // An escalation is a hand-off point: a different on-call clinician
            // must be able to claim it while the emergency state remains visible.
            return true;
        }
        return status == ClinicalReviewStatus.PENDING_REVIEW
                && (claimedByUserId == null || claimedByUserId.equals(userId));
    }

    public void claim(User reviewer, Instant now) {
        requireReviewer(reviewer);
        if (status == ClinicalReviewStatus.IN_REVIEW && reviewer.getId().equals(claimedByUserId)) {
            return;
        }
        if (!canBeClaimedBy(reviewer.getId())) {
            throw new IllegalArgumentException("clinical review is not available for claim");
        }
        Instant timestamp = now == null ? Instant.now() : now;
        this.status = ClinicalReviewStatus.IN_REVIEW;
        this.claimedByUserId = reviewer.getId();
        this.reviewerUserId = reviewer.getId();
        this.reviewerEmployeeNumber = reviewer.getEmployeeNumber();
        this.claimedAt = timestamp;
        this.updatedAt = timestamp;
    }

    public void decide(
            User reviewer,
            ClinicalReviewDecision action,
            String finalDepartment,
            String finalRiskLevel,
            String finalUrgency,
            String reason,
            Instant now) {
        requireReviewer(reviewer);
        if (status != ClinicalReviewStatus.IN_REVIEW
                || !reviewer.getId().equals(claimedByUserId)) {
            throw new IllegalArgumentException("clinical review must be claimed by the same reviewer");
        }
        if (action == null) throw new IllegalArgumentException("decision is required");
        String normalizedReason = normalizeReason(reason);
        Instant timestamp = now == null ? Instant.now() : now;
        this.decision = action;
        this.reviewerUserId = reviewer.getId();
        this.reviewerEmployeeNumber = reviewer.getEmployeeNumber();
        this.decisionReason = normalizedReason;
        this.decidedAt = timestamp;
        this.updatedAt = timestamp;
        switch (action) {
            case CONFIRM -> {
                this.status = ClinicalReviewStatus.CLINICIAN_CONFIRMED;
                this.finalDepartment = originalDepartment;
                this.finalRiskLevel = originalRiskLevel;
                this.finalUrgency = originalUrgency;
            }
            case MODIFY -> {
                this.finalDepartment = requiredCode(
                        valueOrOriginal(finalDepartment, originalDepartment), 128, "final department");
                this.finalRiskLevel = requiredCode(
                        valueOrOriginal(finalRiskLevel, originalRiskLevel), 32, "final risk level");
                this.finalUrgency = requiredCode(
                        valueOrOriginal(finalUrgency, originalUrgency), 2_000, "final urgency");
                this.status = ClinicalReviewStatus.CLINICIAN_MODIFIED;
            }
            case REJECT -> {
                this.status = ClinicalReviewStatus.REJECTED;
                this.finalDepartment = null;
                this.finalRiskLevel = null;
                this.finalUrgency = null;
            }
            case ESCALATE -> {
                this.status = ClinicalReviewStatus.EMERGENCY_ESCALATED;
                this.finalDepartment = null;
                this.finalRiskLevel = null;
                this.finalUrgency = null;
                this.emergencyEscalatedAt = timestamp;
            }
        }
    }

    private static void requireReviewer(User reviewer) {
        if (reviewer == null || reviewer.getId() == null) {
            throw new SecurityException("authenticated clinician is required");
        }
        if (reviewer.getRole() != com.medpilot.user.Role.DOCTOR
                && reviewer.getRole() != com.medpilot.user.Role.REVIEWER) {
            throw new SecurityException("only a doctor or clinical reviewer can operate a review");
        }
        if (!reviewer.hasHospitalStaffProfile() || reviewer.getMfaAssuranceLevel() < 2) {
            throw new SecurityException("clinical review requires a hospital staff profile and MFA level 2");
        }
        if (reviewer.getEmployeeNumber() == null || reviewer.getEmployeeNumber().isBlank()) {
            throw new SecurityException("clinical review requires a verified employee number");
        }
    }

    public static boolean isHighRisk(String risk) {
        if (risk == null) return false;
        String normalized = risk.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("高")
                || normalized.contains("危急")
                || normalized.contains("critical")
                || normalized.contains("high")
                || normalized.contains("red")
                || normalized.equals("4")
                || normalized.equals("5");
    }

    private static boolean isEmergency(String urgency) {
        if (urgency == null) return false;
        String normalized = urgency.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("立即") || normalized.contains("急诊")
                || normalized.contains("emergency") || normalized.contains("immediate");
    }

    private static boolean isEmergencyRule(String matchedRule) {
        if (matchedRule == null) return false;
        String normalized = matchedRule.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("red_flag") || normalized.contains("red-flag")
                || normalized.contains("emergency") || normalized.contains("危急")
                || normalized.contains("急诊");
    }

    private static String normalizeReason(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_REASON) {
            throw new IllegalArgumentException("decision reason must contain 1 to 2000 characters");
        }
        return normalized;
    }

    private static String requiredCode(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }

    private static String valueOrOriginal(String candidate, String original) {
        return candidate == null || candidate.isBlank() ? original : candidate;
    }
}
