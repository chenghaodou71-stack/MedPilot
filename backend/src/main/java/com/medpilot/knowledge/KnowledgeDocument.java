package com.medpilot.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** MySQL-owned lifecycle metadata for a document whose content is indexed by the AI service. */
@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument {

    @Id
    @Column(name = "doc_id", length = 128)
    private String docId;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false, length = 32)
    private String department;

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    @Column(nullable = false, length = 256)
    private String institution;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "published_date", length = 10)
    private String publishedDate;

    @Column(name = "source_version", length = 256)
    private String sourceVersion;

    @Column(name = "license_name", length = 512)
    private String license;

    @Column(name = "original_filename", length = 512)
    private String originalFilename;

    @Column(name = "media_type", length = 128)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(name = "parsing_status", nullable = false, length = 32)
    private String parsingStatus;

    @Column(name = "vector_status", nullable = false, length = 32)
    private String vectorStatus;

    @Column(name = "review_status", nullable = false, length = 16)
    private String reviewStatus;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "processing_error", columnDefinition = "TEXT")
    private String processingError;

    @Column(length = 128)
    private String reviewer;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeDocument() {
    }

    public KnowledgeDocument(String docId) {
        this.docId = docId;
        this.title = docId;
        this.department = "未知";
        this.sourceType = "OTHER";
        this.institution = "未知";
        this.url = "";
        this.checksum = "";
        this.parsingStatus = "parsing";
        this.vectorStatus = "pending";
        this.reviewStatus = "pending";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void apply(Map<String, ?> values, UploadMetadata upload) {
        title = text(values, "title", title);
        department = text(values, "department", department);
        sourceType = text(values, "source_type", text(values, "source", sourceType));
        institution = text(values, "institution", institution);
        url = text(values, "url", url);
        publishedDate = text(values, "published_date", publishedDate);
        sourceVersion = text(values, "version", sourceVersion);
        license = text(values, "license", license);
        checksum = text(values, "checksum", checksum);
        reviewStatus = text(values, "review_status", reviewStatus);
        reviewer = text(values, "reviewer", reviewer);
        reviewedAt = instant(values.get("reviewed_at"), reviewedAt);
        chunkCount = integer(values.get("chunk_count"), integer(values.get("chunks"), chunkCount));
        parsingStatus = text(values, "parsing_status", "completed").toLowerCase();
        vectorStatus = text(values, "vector_status",
                Boolean.TRUE.equals(values.get("active")) || chunkCount > 0
                        ? "completed" : "pending").toLowerCase();
        processingError = text(values, "failure_summary",
                text(values, "processing_error", null));
        if (upload != null) {
            originalFilename = upload.originalFilename();
            mediaType = upload.mediaType();
            sizeBytes = upload.sizeBytes();
        }
        updatedAt = Instant.now();
    }

    public void markParsingFailed(String message, UploadMetadata upload) {
        if (upload != null) {
            originalFilename = upload.originalFilename();
            mediaType = upload.mediaType();
            sizeBytes = upload.sizeBytes();
        }
        parsingStatus = "failed";
        vectorStatus = "failed";
        processingError = message == null ? "document parsing failed" : message.substring(0, Math.min(1000, message.length()));
        updatedAt = Instant.now();
    }

    public void applyReview(String action, String reviewerName, Map<String, ?> values) {
        reviewStatus = "approve".equals(action) ? "approved" : "rejected";
        reviewer = reviewerName;
        reviewedAt = Instant.now();
        apply(values, null);
        if ("rejected".equals(reviewStatus)) vectorStatus = "pending";
    }

    public Map<String, Object> view() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("doc_id", docId);
        result.put("title", title);
        result.put("department", department);
        result.put("source", sourceType);
        result.put("source_type", sourceType);
        result.put("institution", institution);
        result.put("url", url);
        result.put("published_date", publishedDate);
        result.put("version", sourceVersion);
        result.put("license", license);
        result.put("original_filename", originalFilename);
        result.put("media_type", mediaType);
        result.put("size_bytes", sizeBytes);
        result.put("checksum", checksum);
        result.put("parsing_status", parsingStatus);
        result.put("vector_status", vectorStatus);
        result.put("review_status", reviewStatus);
        result.put("chunk_count", chunkCount);
        result.put("processing_error", processingError);
        result.put("reviewer", reviewer);
        result.put("reviewed_at", reviewedAt == null ? null : reviewedAt.toString());
        result.put("created_at", createdAt.toString());
        result.put("updated_at", updatedAt.toString());
        result.put("active", "completed".equals(vectorStatus) && "approved".equals(reviewStatus));
        return result;
    }

    public String getDocId() { return docId; }
    public String getParsingStatus() { return parsingStatus; }
    public String getVectorStatus() { return vectorStatus; }
    public String getSourceType() { return sourceType; }
    public String getReviewStatus() { return reviewStatus; }
    public int getChunkCount() { return chunkCount; }

    private static String text(Map<String, ?> values, String key, String fallback) {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) return fallback;
        return value.toString().trim();
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Instant instant(Object value, Instant fallback) {
        if (value == null || value.toString().isBlank()) return fallback;
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public record UploadMetadata(String originalFilename, String mediaType, long sizeBytes) {
    }
}
