package com.medpilot.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Change-control record. Execution is impossible until an independent approval exists. */
@Entity
@Table(name = "governance_changes", indexes = {
        @Index(name = "idx_governance_changes_status", columnList = "change_status"),
        @Index(name = "idx_governance_changes_target", columnList = "target_type,target_id")
})
public class GovernanceChange {

    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";
    public static final String EXECUTED = "EXECUTED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "change_id", nullable = false, unique = true, length = 128)
    private String changeId;

    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 128)
    private String targetId;

    @Column(name = "change_type", nullable = false, length = 64)
    private String changeType;

    @Column(name = "risk_level", nullable = false, length = 16)
    private String riskLevel;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String reason;

    @Column(name = "validation_evidence", nullable = false, columnDefinition = "LONGTEXT")
    private String validationEvidence;

    @Column(name = "rollback_plan", nullable = false, columnDefinition = "LONGTEXT")
    private String rollbackPlan;

    @Column(name = "change_status", nullable = false, length = 32)
    private String status = DRAFT;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    @Column(name = "approved_by", length = 128)
    private String approvedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    protected GovernanceChange() {
    }

    public GovernanceChange(
            String changeId,
            String targetType,
            String targetId,
            String changeType,
            String riskLevel,
            String reason,
            String validationEvidence,
            String rollbackPlan,
            String requestedBy) {
        this.changeId = code(changeId, 128, "change id");
        this.targetType = code(targetType, 64, "target type");
        this.targetId = code(targetId, 128, "target id");
        this.changeType = code(changeType, 64, "change type");
        this.riskLevel = code(riskLevel, 16, "risk level").toUpperCase();
        if (!("LOW".equals(this.riskLevel) || "MEDIUM".equals(this.riskLevel) || "HIGH".equals(this.riskLevel) || "CRITICAL".equals(this.riskLevel))) {
            throw new IllegalArgumentException("risk level must be LOW, MEDIUM, HIGH or CRITICAL");
        }
        this.reason = code(reason, 20_000, "change reason");
        this.validationEvidence = code(validationEvidence, 20_000, "validation evidence");
        this.rollbackPlan = code(rollbackPlan, 20_000, "rollback plan");
        this.requestedBy = code(requestedBy, 128, "requester");
        this.requestedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getChangeId() { return changeId; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getChangeType() { return changeType; }
    public String getRiskLevel() { return riskLevel; }
    public String getReason() { return reason; }
    public String getValidationEvidence() { return validationEvidence; }
    public String getRollbackPlan() { return rollbackPlan; }
    public String getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getExecutedAt() { return executedAt; }
    public Instant getRolledBackAt() { return rolledBackAt; }

    public void approve(String reviewer) {
        if (!DRAFT.equals(status)) throw new IllegalStateException("only a draft change can be approved");
        if (requestedBy.equals(reviewer)) throw new SecurityException("change requester cannot approve their own change");
        status = APPROVED;
        approvedBy = code(reviewer, 128, "approver");
        approvedAt = Instant.now();
    }

    public void reject(String reviewer) {
        if (!DRAFT.equals(status)) throw new IllegalStateException("only a draft change can be rejected");
        approvedBy = code(reviewer, 128, "reviewer");
        status = REJECTED;
        approvedAt = Instant.now();
    }

    public void execute() {
        if (!APPROVED.equals(status)) throw new IllegalStateException("only an approved change can be executed");
        status = EXECUTED;
        executedAt = Instant.now();
    }

    public void execute(String executor) {
        String identity = code(executor, 128, "executor");
        if (identity.equals(approvedBy)) throw new SecurityException("change approver cannot execute the same change");
        execute();
    }

    public void rollback() {
        if (!EXECUTED.equals(status)) throw new IllegalStateException("only an executed change can be rolled back");
        status = ROLLED_BACK;
        rolledBackAt = Instant.now();
    }

    public void rollback(String executor) {
        code(executor, 128, "rollback operator");
        rollback();
    }

    private static String code(String value, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) throw new IllegalArgumentException(field + " is required and too long");
        return normalized;
    }
}
