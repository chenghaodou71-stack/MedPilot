package com.medpilot.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Evidence that a frozen model can be restored without changing patient records. */
@Entity
@Table(name = "rollback_drill_runs", indexes = {
        @Index(name = "idx_rollback_drill_release_time", columnList = "release_id,drilled_at"),
        @Index(name = "idx_rollback_drill_status", columnList = "drill_status")
})
public class RollbackDrillRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "drill_id", nullable = false, unique = true, length = 128)
    private String drillId;

    @Column(name = "release_id", nullable = false, length = 128)
    private String releaseId;

    @Column(name = "rollback_target_release_id", nullable = false, length = 128)
    private String rollbackTargetReleaseId;

    @Column(name = "drill_status", nullable = false, length = 32)
    private String status;

    @Column(name = "recovery_duration_seconds", nullable = false)
    private int recoveryDurationSeconds;

    @Column(name = "evidence_uri", nullable = false, length = 2048)
    private String evidenceUri;

    @Column(name = "data_integrity_check", nullable = false)
    private boolean dataIntegrityCheck;

    @Column(name = "drilled_by", nullable = false, length = 128)
    private String drilledBy;

    @Column(name = "drilled_at", nullable = false)
    private Instant drilledAt = Instant.now();

    protected RollbackDrillRun() {
    }

    public RollbackDrillRun(
            String drillId,
            String releaseId,
            String rollbackTargetReleaseId,
            int recoveryDurationSeconds,
            String evidenceUri,
            boolean dataIntegrityCheck,
            String drilledBy) {
        this.drillId = code(drillId, 128, "rollback drill id");
        this.releaseId = code(releaseId, 128, "release id");
        this.rollbackTargetReleaseId = code(rollbackTargetReleaseId, 128, "rollback target release id");
        if (recoveryDurationSeconds < 0) throw new IllegalArgumentException("recovery duration cannot be negative");
        this.recoveryDurationSeconds = recoveryDurationSeconds;
        this.evidenceUri = uri(evidenceUri);
        this.dataIntegrityCheck = dataIntegrityCheck;
        this.drilledBy = code(drilledBy, 128, "drill operator");
        this.status = dataIntegrityCheck ? "PASSED" : "FAILED";
        this.drilledAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getDrillId() { return drillId; }
    public String getReleaseId() { return releaseId; }
    public String getRollbackTargetReleaseId() { return rollbackTargetReleaseId; }
    public String getStatus() { return status; }
    public int getRecoveryDurationSeconds() { return recoveryDurationSeconds; }
    public String getEvidenceUri() { return evidenceUri; }
    public boolean isDataIntegrityCheck() { return dataIntegrityCheck; }
    public String getDrilledBy() { return drilledBy; }
    public Instant getDrilledAt() { return drilledAt; }

    private static String code(String value, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) throw new IllegalArgumentException(field + " is required and too long");
        return normalized;
    }

    private static String uri(String value) {
        String normalized = code(value, 2048, "rollback evidence URI");
        if (!(normalized.startsWith("https://") || normalized.startsWith("s3://") || normalized.startsWith("file://"))) {
            throw new IllegalArgumentException("rollback evidence URI must use https, s3 or file scheme");
        }
        return normalized;
    }
}
