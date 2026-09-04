package com.medpilot.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Provenance and clinical-review register for every knowledge source. */
@Entity
@Table(name = "knowledge_source_register", indexes = {
        @Index(name = "idx_source_register_status", columnList = "review_status"),
        @Index(name = "idx_source_register_doc", columnList = "doc_id")
})
public class KnowledgeSourceRegister {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false, unique = true, length = 128)
    private String sourceId;

    @Column(name = "doc_id", nullable = false, length = 128)
    private String docId;

    @Column(nullable = false, length = 256)
    private String publisher;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "domestic_official", nullable = false)
    private boolean domesticOfficial;

    @Column(name = "publication_date", nullable = false, length = 10)
    private String publicationDate;

    @Column(name = "source_version", nullable = false, length = 256)
    private String sourceVersion;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(name = "applicable_scope", nullable = false, length = 1024)
    private String applicableScope;

    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus = "PENDING";

    @Column(name = "reviewer", length = 128)
    private String reviewer;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected KnowledgeSourceRegister() {
    }

    public KnowledgeSourceRegister(
            String sourceId,
            String docId,
            String publisher,
            String title,
            String url,
            boolean domesticOfficial,
            String publicationDate,
            String sourceVersion,
            String checksum,
            String applicableScope,
            Instant expiresAt) {
        this.sourceId = code(sourceId, 128, "source id");
        this.docId = code(docId, 128, "document id");
        this.publisher = code(publisher, 256, "publisher");
        this.title = code(title, 512, "source title");
        this.url = https(url);
        this.domesticOfficial = domesticOfficial;
        this.publicationDate = date(publicationDate);
        this.sourceVersion = code(sourceVersion, 256, "source version");
        this.checksum = sha256(checksum);
        this.applicableScope = code(applicableScope, 1024, "applicable scope");
        this.expiresAt = expiresAt;
        this.reviewStatus = "PENDING";
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSourceId() { return sourceId; }
    public String getDocId() { return docId; }
    public String getPublisher() { return publisher; }
    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public boolean isDomesticOfficial() { return domesticOfficial; }
    public String getPublicationDate() { return publicationDate; }
    public String getSourceVersion() { return sourceVersion; }
    public String getChecksum() { return checksum; }
    public String getApplicableScope() { return applicableScope; }
    public String getReviewStatus() { return reviewStatus; }
    public String getReviewer() { return reviewer; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void review(String action, String reviewer) {
        String normalized = code(action, 16, "review action").toLowerCase();
        if ("approve".equals(normalized)) {
            reviewStatus = "APPROVED";
        } else if ("reject".equals(normalized)) {
            reviewStatus = "REJECTED";
        } else {
            throw new IllegalArgumentException("review action must be approve or reject");
        }
        this.reviewer = code(reviewer, 128, "reviewer");
        this.reviewedAt = Instant.now();
    }

    public boolean isActiveAt(Instant now) {
        return "APPROVED".equals(reviewStatus) && (expiresAt == null || expiresAt.isAfter(now));
    }

    private static String code(String value, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) throw new IllegalArgumentException(field + " is required and too long");
        return normalized;
    }

    private static String https(String value) {
        String normalized = code(value, 2048, "source URL");
        if (!normalized.startsWith("https://")) throw new IllegalArgumentException("source URL must use HTTPS");
        return normalized;
    }

    private static String date(String value) {
        String normalized = code(value, 10, "publication date");
        if (!normalized.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("publication date must be ISO date");
        return normalized;
    }

    private static String sha256(String value) {
        String normalized = code(value, 64, "source checksum").toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("source checksum must be hexadecimal SHA-256");
        return normalized;
    }
}
