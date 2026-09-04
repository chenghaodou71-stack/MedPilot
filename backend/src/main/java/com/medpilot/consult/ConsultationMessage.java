package com.medpilot.consult;

import com.medpilot.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** One durable user or assistant turn in a consultation session. */
@Entity
@Table(name = "consultation_messages", indexes = {
        @Index(name = "idx_consultation_messages_session_order",
                columnList = "session_id, user_id, created_at, id")
})
public class ConsultationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String content;

    @Column(name = "trace_id", length = 36)
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ConsultationMessage() {
    }

    ConsultationMessage(Long userId, String sessionId, String role, String content, String traceId) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.traceId = traceId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getTraceId() { return traceId; }
    public Instant getCreatedAt() { return createdAt; }
}
