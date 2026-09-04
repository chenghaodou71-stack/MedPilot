package com.medpilot.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Immutable model-release evidence and the controlled promotion state. */
@Entity
@Table(name = "model_releases", indexes = {
        @Index(name = "idx_model_releases_status", columnList = "release_status"),
        @Index(name = "idx_model_releases_model_version", columnList = "model_name,model_version")
})
public class ModelRelease {

    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";
    public static final String FROZEN = "FROZEN";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false, unique = true, length = 128)
    private String releaseId;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "model_version", nullable = false, length = 128)
    private String modelVersion;

    @Column(name = "weight_sha256", nullable = false, length = 64)
    private String weightSha256;

    @Column(name = "artifact_signature", nullable = false, length = 4096)
    private String artifactSignature;

    @Column(name = "signature_algorithm", nullable = false, length = 64)
    private String signatureAlgorithm;

    @Column(name = "prompt_version", nullable = false, length = 128)
    private String promptVersion;

    @Column(name = "embedding_version", nullable = false, length = 128)
    private String embeddingVersion;

    @Column(name = "knowledge_index_version", nullable = false, length = 128)
    private String knowledgeIndexVersion;

    @Column(name = "scope", nullable = false, length = 1024)
    private String scope;

    @Column(name = "gpu_baseline_json", nullable = false, columnDefinition = "LONGTEXT")
    private String gpuBaselineJson;

    @Column(name = "release_status", nullable = false, length = 32)
    private String status = DRAFT;

    @Column(name = "rollback_target_release_id", length = 128)
    private String rollbackTargetReleaseId;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "approved_by", length = 128)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ModelRelease() {
    }

    public ModelRelease(
            String releaseId,
            String modelName,
            String modelVersion,
            String weightSha256,
            String artifactSignature,
            String signatureAlgorithm,
            String promptVersion,
            String embeddingVersion,
            String knowledgeIndexVersion,
            String scope,
            String gpuBaselineJson,
            String createdBy) {
        this.releaseId = code(releaseId, 128, "release id");
        this.modelName = code(modelName, 128, "model name");
        this.modelVersion = code(modelVersion, 128, "model version");
        this.weightSha256 = sha256(weightSha256);
        this.artifactSignature = code(artifactSignature, 4096, "artifact signature");
        this.signatureAlgorithm = code(signatureAlgorithm, 64, "signature algorithm");
        this.promptVersion = code(promptVersion, 128, "prompt version");
        this.embeddingVersion = code(embeddingVersion, 128, "embedding version");
        this.knowledgeIndexVersion = code(knowledgeIndexVersion, 128, "knowledge index version");
        this.scope = code(scope, 1024, "release scope");
        this.gpuBaselineJson = code(gpuBaselineJson, 100_000, "GPU baseline");
        this.createdBy = code(createdBy, 128, "creator");
        this.status = DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getReleaseId() { return releaseId; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public String getWeightSha256() { return weightSha256; }
    public String getArtifactSignature() { return artifactSignature; }
    public String getSignatureAlgorithm() { return signatureAlgorithm; }
    public String getPromptVersion() { return promptVersion; }
    public String getEmbeddingVersion() { return embeddingVersion; }
    public String getKnowledgeIndexVersion() { return knowledgeIndexVersion; }
    public String getScope() { return scope; }
    public String getGpuBaselineJson() { return gpuBaselineJson; }
    public String getStatus() { return status; }
    public String getRollbackTargetReleaseId() { return rollbackTargetReleaseId; }
    public String getCreatedBy() { return createdBy; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getFrozenAt() { return frozenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void approve(String reviewer) {
        if (!DRAFT.equals(status)) throw new IllegalStateException("only a draft release can be approved");
        String reviewerIdentity = code(reviewer, 128, "approver");
        if (createdBy.equals(reviewerIdentity)) {
            throw new SecurityException("release creator cannot approve the same release");
        }
        this.status = APPROVED;
        this.approvedBy = reviewerIdentity;
        this.approvedAt = Instant.now();
        touch();
    }

    public void freeze() {
        if (!APPROVED.equals(status)) throw new IllegalStateException("only an approved release can be frozen");
        this.status = FROZEN;
        this.frozenAt = Instant.now();
        touch();
    }

    public void rollbackTo(String targetReleaseId) {
        if (!(FROZEN.equals(status) || APPROVED.equals(status))) {
            throw new IllegalStateException("only an approved or frozen release can be rolled back");
        }
        this.rollbackTargetReleaseId = code(targetReleaseId, 128, "rollback target");
        this.status = ROLLED_BACK;
        touch();
    }

    public void retire() {
        if (ROLLED_BACK.equals(status)) throw new IllegalStateException("rolled-back release is already terminal");
        this.status = RETIRED;
        touch();
    }

    private void touch() { this.updatedAt = Instant.now(); }

    private static String code(String value, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }

    private static String sha256(String value) {
        String normalized = code(value, 64, "weight SHA-256").toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("weight SHA-256 must be 64 hexadecimal characters");
        }
        return normalized;
    }
}
