package com.medpilot.consult;

import com.medpilot.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Immutable snapshot of one successfully terminated AI execution trace. */
@Entity
@Table(name = "consultation_traces")
public class ConsultationTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, unique = true, length = 36)
    private String traceId;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "events_json", nullable = false, columnDefinition = "LONGTEXT")
    @jakarta.persistence.Convert(converter = EncryptedStringConverter.class)
    private String eventsJson;

    @Column(name = "citations_json", nullable = false, columnDefinition = "LONGTEXT")
    @jakarta.persistence.Convert(converter = EncryptedStringConverter.class)
    private String citationsJson;

    @Column(name = "terminal_phase", nullable = false, length = 32)
    private String terminalPhase;

    @Column(name = "followup_pending", nullable = false)
    private boolean followupPending;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "total_duration_ms", nullable = false)
    private long totalDurationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ConsultationTrace() {
    }

    ConsultationTrace(Long userId, ConsultationEventAccumulator.Snapshot snapshot) {
        this.userId = userId;
        this.traceId = snapshot.traceId();
        this.sessionId = snapshot.sessionId();
        this.eventsJson = snapshot.eventsJson();
        this.citationsJson = snapshot.citationsJson();
        this.terminalPhase = snapshot.terminalPhase();
        this.followupPending = snapshot.followupPending();
        this.failureCode = snapshot.failureCode();
        this.totalDurationMs = snapshot.totalDurationMs();
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public String getEventsJson() { return eventsJson; }
    public String getCitationsJson() { return citationsJson; }
    public String getTerminalPhase() { return terminalPhase; }
    public boolean isFollowupPending() { return followupPending; }
    public String getFailureCode() { return failureCode; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public Instant getCreatedAt() { return createdAt; }
}
