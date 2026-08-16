package com.medpilot.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Operational audit event. Request bodies, tokens and medical text are never stored. */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_actor", columnList = "actor_username")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "actor_username", length = 64)
    private String actorUsername;

    @Column(name = "actor_role", length = 32)
    private String actorRole;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, length = 160)
    private String action;

    @Column(nullable = false)
    private int status;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AuditLog() { }

    public AuditLog(String eventId, String actorUsername, String actorRole,
                    String method, String action, int status, boolean success,
                    String requestId, String ipHash, long durationMs) {
        this.eventId = eventId;
        this.actorUsername = actorUsername;
        this.actorRole = actorRole;
        this.method = method;
        this.action = action;
        this.status = status;
        this.success = success;
        this.requestId = requestId;
        this.ipHash = ipHash;
        this.durationMs = Math.max(0L, durationMs);
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getActorUsername() { return actorUsername; }
    public String getActorRole() { return actorRole; }
    public String getMethod() { return method; }
    public String getAction() { return action; }
    public int getStatus() { return status; }
    public boolean isSuccess() { return success; }
    public String getRequestId() { return requestId; }
    public String getIpHash() { return ipHash; }
    public long getDurationMs() { return durationMs; }
    public Instant getCreatedAt() { return createdAt; }
}
