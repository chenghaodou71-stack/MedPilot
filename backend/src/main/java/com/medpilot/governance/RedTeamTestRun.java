package com.medpilot.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Adversarial test evidence for prompt injection, malicious attachments and PHI leakage. */
@Entity
@Table(name = "red_team_test_runs", indexes = {
        @Index(name = "idx_red_team_release_status", columnList = "release_id,test_status"),
        @Index(name = "idx_red_team_executed_at", columnList = "executed_at")
})
public class RedTeamTestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_id", nullable = false, unique = true, length = 128)
    private String testId;

    @Column(name = "release_id", nullable = false, length = 128)
    private String releaseId;

    @Column(name = "test_type", nullable = false, length = 64)
    private String testType;

    @Column(name = "dataset_version", nullable = false, length = 128)
    private String datasetVersion;

    @Column(name = "case_count", nullable = false)
    private int caseCount;

    @Column(name = "blocked_count", nullable = false)
    private int blockedCount;

    @Column(name = "escaped_count", nullable = false)
    private int escapedCount;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "report_uri", nullable = false, length = 2048)
    private String reportUri;

    @Column(name = "test_status", nullable = false, length = 32)
    private String status;

    @Column(name = "executed_by", nullable = false, length = 128)
    private String executedBy;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt = Instant.now();

    protected RedTeamTestRun() {
    }

    public RedTeamTestRun(
            String testId,
            String releaseId,
            String testType,
            String datasetVersion,
            int caseCount,
            int blockedCount,
            int escapedCount,
            String severity,
            String reportUri,
            String executedBy) {
        this.testId = code(testId, 128, "test id");
        this.releaseId = code(releaseId, 128, "release id");
        this.testType = code(testType, 64, "test type").toUpperCase();
        this.datasetVersion = code(datasetVersion, 128, "dataset version");
        if (caseCount < 1 || blockedCount < 0 || escapedCount < 0 || blockedCount + escapedCount > caseCount) {
            throw new IllegalArgumentException("red-team case counts are invalid");
        }
        this.caseCount = caseCount;
        this.blockedCount = blockedCount;
        this.escapedCount = escapedCount;
        this.severity = code(severity, 16, "severity").toUpperCase();
        this.reportUri = reportUri == null ? "" : reportUri.strip();
        if (!this.reportUri.startsWith("https://") && !this.reportUri.startsWith("s3://") && !this.reportUri.startsWith("file://")) {
            throw new IllegalArgumentException("red-team report URI must use https, s3 or file scheme");
        }
        this.executedBy = code(executedBy, 128, "executor");
        this.status = blockedCount == caseCount && escapedCount == 0 ? "PASSED" : "FAILED";
        this.executedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTestId() { return testId; }
    public String getReleaseId() { return releaseId; }
    public String getTestType() { return testType; }
    public String getDatasetVersion() { return datasetVersion; }
    public int getCaseCount() { return caseCount; }
    public int getBlockedCount() { return blockedCount; }
    public int getEscapedCount() { return escapedCount; }
    public String getSeverity() { return severity; }
    public String getReportUri() { return reportUri; }
    public String getStatus() { return status; }
    public String getExecutedBy() { return executedBy; }
    public Instant getExecutedAt() { return executedAt; }

    private static String code(String value, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) throw new IllegalArgumentException(field + " is required and too long");
        return normalized;
    }
}
