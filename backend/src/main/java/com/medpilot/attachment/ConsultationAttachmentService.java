package com.medpilot.attachment;

import com.medpilot.consult.SessionOwnershipService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

@Service
public class ConsultationAttachmentService {

    private static final int MAX_DRAFT_CHARS = 4000;

    private final ConsultationAttachmentRepository repository;
    private final ConsultationAttachmentStorage storage;
    private final SessionOwnershipService ownership;
    private final int retentionDays;

    public ConsultationAttachmentService(
            ConsultationAttachmentRepository repository,
            ConsultationAttachmentStorage storage,
            SessionOwnershipService ownership,
            @Value("${medpilot.attachments.retention-days:30}") int retentionDays) {
        this.repository = repository;
        this.storage = storage;
        this.ownership = ownership;
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalStateException("attachment retention must be between 1 and 3650 days");
        }
        this.retentionDays = retentionDays;
    }

    @Transactional
    public ConsultationAttachment upload(Long userId, String sessionId, MultipartFile file) {
        String canonicalSession = ownership.claim(sessionId, userId).getSessionId();
        ConsultationAttachmentStorage.StoredAttachment stored = storage.store(file);
        String draft = buildDraft(stored);
        try {
            return repository.save(new ConsultationAttachment(
                    userId, canonicalSession, stored, draft, retentionDays));
        } catch (RuntimeException exception) {
            storage.delete(stored.storageKey());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<ConsultationAttachment> list(Long userId, String sessionId) {
        String canonicalSession = ownership.claim(sessionId, userId).getSessionId();
        return repository.findBySessionIdAndUserIdOrderByCreatedAtAsc(canonicalSession, userId);
    }

    @Transactional
    public ConsultationAttachment confirm(Long userId, String attachmentId, String draftText) {
        ConsultationAttachment attachment = own(userId, attachmentId);
        String normalized = normalizeDraft(draftText);
        attachment.confirm(normalized);
        return repository.save(attachment);
    }

    @Transactional
    public void delete(Long userId, String attachmentId) {
        ConsultationAttachment attachment = own(userId, attachmentId);
        storage.delete(attachment.getStorageKey());
        repository.delete(attachment);
    }

    @Transactional
    public int purgeExpired(Instant now) {
        List<ConsultationAttachment> expired = repository.findByExpiresAtBefore(now);
        for (ConsultationAttachment attachment : expired) {
            storage.delete(attachment.getStorageKey());
            repository.delete(attachment);
        }
        return expired.size();
    }

    private ConsultationAttachment own(Long userId, String attachmentId) {
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new IllegalArgumentException("attachment_id is required");
        }
        return repository.findByIdAndUserId(attachmentId, userId)
                .orElseThrow(() -> new SecurityException("Attachment access denied"));
    }

    private String buildDraft(ConsultationAttachmentStorage.StoredAttachment stored) {
        String extracted = stored.extractedText() == null ? "" : stored.extractedText().strip();
        if (stored.kind() == AttachmentKind.TEXT && !extracted.isBlank()) {
            return extracted;
        }
        return switch (stored.kind()) {
            case IMAGE -> limit(String.format(
                    "我上传了一张图片“%s”。系统不会根据图片自动诊断，请补充希望医生关注的部位、时间和变化：",
                    stored.originalFilename()));
            case AUDIO -> limit(String.format(
                    "我上传了一段音频“%s”。系统不会根据音频自动诊断，请先确认并补充症状文字描述：",
                    stored.originalFilename()));
            case TEXT -> limit(String.format(
                    "附件“%s”未提取到可读文本，请核对文件内容并补充说明：",
                    stored.originalFilename()));
        };
    }

    private String normalizeDraft(String value) {
        if (value == null) {
            throw new IllegalArgumentException("draftText is required");
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("draftText must not be blank");
        }
        if (normalized.length() > MAX_DRAFT_CHARS) {
            throw new IllegalArgumentException("draftText exceeds the 4000 character limit");
        }
        return normalized;
    }

    private String limit(String value) {
        return value.length() <= MAX_DRAFT_CHARS
                ? value
                : value.substring(0, MAX_DRAFT_CHARS);
    }
}
