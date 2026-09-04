package com.medpilot.attachment;

import com.medpilot.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "consultation_attachments")
public class ConsultationAttachment {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "storage_key", nullable = false, unique = true, length = 64)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, columnDefinition = "LONGTEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String originalFilename;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AttachmentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AttachmentStatus status;

    @Column(name = "extracted_text", columnDefinition = "LONGTEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String extractedText;

    @Column(name = "draft_text", columnDefinition = "LONGTEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String draftText;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ConsultationAttachment() {
    }

    public ConsultationAttachment(
            Long userId,
            String sessionId,
            ConsultationAttachmentStorage.StoredAttachment stored,
            String draftText,
            int retentionDays) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.sessionId = sessionId;
        this.storageKey = stored.storageKey();
        this.originalFilename = stored.originalFilename();
        this.mediaType = stored.mediaType();
        this.sizeBytes = stored.sizeBytes();
        this.sha256 = stored.sha256();
        this.kind = stored.kind();
        this.status = AttachmentStatus.AWAITING_CONFIRMATION;
        this.extractedText = stored.extractedText();
        this.draftText = draftText;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plus(retentionDays, ChronoUnit.DAYS);
    }

    public void confirm(String confirmedDraft) {
        this.draftText = confirmedDraft;
        this.status = AttachmentStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
    }

    public String getId() { return id; }
    public Long getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalFilename() { return originalFilename; }
    public String getMediaType() { return mediaType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public AttachmentKind getKind() { return kind; }
    public AttachmentStatus getStatus() { return status; }
    public String getExtractedText() { return extractedText; }
    public String getDraftText() { return draftText; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
