package com.medpilot.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Versioned, de-identified clinical evaluation evidence for a model release. */
@Entity
@Table(name = "clinical_evaluation_runs", indexes = {
        @Index(name = "idx_evaluation_release_status", columnList = "release_id,evaluation_status"),
        @Index(name = "idx_evaluation_created_at", columnList = "created_at")
})
public class ClinicalEvaluationRun {

    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, unique = true, length = 128)
    private String runId;

    @Column(name = "release_id", nullable = false, length = 128)
    private String releaseId;

    @Column(name = "dataset_version", nullable = false, length = 128)
    private String datasetVersion;

    @Column(name = "dataset_sha256", nullable = false, length = 64)
    private String datasetSha256;

    @Column(name = "de_identification_method", nullable = false, length = 512)
    private String deIdentificationMethod;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(nullable = false)
    private double sensitivity;

    @Column(nullable = false)
    private double specificity;

    @Column(name = "false_negative_count", nullable = false)
    private int falseNegativeCount;

    @Column(name = "incorrect_routing_count", nullable = false)
    private int incorrectRoutingCount;

    @Column(name = "abstention_rate", nullable = false)
    private double abstentionRate;

    @Column(name = "thresholds_json", nullable = false, columnDefinition = "LONGTEXT")
    private String thresholdsJson;

    @Column(name = "evaluation_status", nullable = false, length = 32)
    private String status;

    @Column(name = "evidence_uri", nullable = false, length = 2048)
    private String evidenceUri;

    @Column(name = "evaluated_by", nullable = false, length = 128)
    private String evaluatedBy;

    @Column(name = "reviewed_by", length = 128)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ClinicalEvaluationRun() {
    }

    public ClinicalEvaluationRun(
            String runId,
            String releaseId,
            String datasetVersion,
            String datasetSha256,
            String deIdentificationMethod,
            int sampleCount,
            double sensitivity,
            double specificity,
            int falseNegativeCount,
            int incorrectRoutingCount,
            double abstentionRate,
            String thresholdsJson,
            String evidenceUri,
            String evaluatedBy) {
        this.runId = code(runId, 128, "evaluation run id");
        this.releaseId = code(releaseId, 128, "release id");
        this.datasetVersion = code(datasetVersion, 128, "dataset version");
        this.datasetSha256 = sha256(datasetSha256, "dataset SHA-256");
        this.deIdentificationMethod = code(deIdentificationMethod, 512, "de-identification method");
        if (sampleCount < 1) throw new IllegalArgumentException("sample count must be positive");
        this.sampleCount = sampleCount;
        this.sensitivity = rate(sensitivity, "sensitivity");
        this.specificity = rate(specificity, "specificity");
        if (falseNegativeCount < 0 || incorrectRoutingCount < 0) {
            throw new IllegalArgumentException("error counts cannot be negative");
        }
        this.falseNegativeCount = falseNegativeCount;
        this.incorrectRoutingCount = incorrectRoutingCount;
        this.abstentionRate = rate(abstentionRate, "abstention rate");
        this.thresholdsJson = code(thresholdsJson, 100_000, "blocking thresholds");
        this.evidenceUri = httpsOrInternal(evidenceUri, "evaluation evidence URI");
        this.evaluatedBy = code(evaluatedBy, 128, "evaluator");
        this.status = falseNegativeCount == 0 && incorrectRoutingCount == 0 ? PASSED : FAILED;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public String getReleaseId() { return releaseId; }
    public String getDatasetVersion() { return datasetVersion; }
    public String getDatasetSha256() { return datasetSha256; }
    public String getDeIdentificationMethod() { return deIdentificationMethod; }
    public int getSampleCount() { return sampleCount; }
    public double getSensitivity() { return sensitivity; }
    public double getSpecificity() { return specificity; }
    public int getFalseNegativeCount() { return falseNegativeCount; }
    public int getIncorrectRoutingCount() { return incorrectRoutingCount; }
    public double getAbstentionRate() { return abstentionRate; }
    public String getThresholdsJson() { return thresholdsJson; }
    public String getStatus() { return status; }
    public String getEvidenceUri() { return evidenceUri; }
    public String getEvaluatedBy() { return evaluatedBy; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void review(String action, String reviewer) {
        if (APPROVED.equals(status) || REJECTED.equals(status)) {
            throw new IllegalStateException("evaluation has already been reviewed");
        }
        String reviewerIdentity = code(reviewer, 128, "reviewer");
        if (evaluatedBy.equals(reviewerIdentity)) {
            throw new SecurityException("evaluation author cannot review the same evaluation");
        }
        String normalized = code(action, 16, "review action").toLowerCase();
        if ("approve".equals(normalized)) {
            if (!PASSED.equals(status)) throw new IllegalStateException("failed evaluation cannot be approved");
            status = APPROVED;
        } else if ("reject".equals(normalized)) {
            status = REJECTED;
        } else {
            throw new IllegalArgumentException("review action must be approve or reject");
        }
        reviewedBy = reviewerIdentity;
        reviewedAt = Instant.now();
    }

    private static String code(String value, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }

    private static String sha256(String value, String field) {
        String normalized = code(value, 64, field).toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " must be hexadecimal SHA-256");
        return normalized;
    }

    private static double rate(double value, String field) {
        if (Double.isNaN(value) || value < 0 || value > 1) throw new IllegalArgumentException(field + " must be between 0 and 1");
        return value;
    }

    private static String httpsOrInternal(String value, String field) {
        String normalized = code(value, 2048, field);
        if (!(normalized.startsWith("https://") || normalized.startsWith("s3://") || normalized.startsWith("file://"))) {
            throw new IllegalArgumentException(field + " must use https, s3 or file scheme");
        }
        return normalized;
    }
}
