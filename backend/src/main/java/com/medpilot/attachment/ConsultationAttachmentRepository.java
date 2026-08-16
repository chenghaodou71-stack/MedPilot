package com.medpilot.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConsultationAttachmentRepository
        extends JpaRepository<ConsultationAttachment, String> {

    Optional<ConsultationAttachment> findByIdAndUserId(String id, Long userId);

    List<ConsultationAttachment> findBySessionIdAndUserIdOrderByCreatedAtAsc(
            String sessionId, Long userId);

    List<ConsultationAttachment> findByExpiresAtBefore(Instant cutoff);
}
