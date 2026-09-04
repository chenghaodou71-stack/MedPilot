package com.medpilot.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Clinical-safety incident and corrective/preventive-action (CAPA) record. */
@Entity
@Table(name = "safety_incidents", indexes = {
        @Index(name = "idx_safety_incident_status", columnList = "incident_status"),
        @Index(name = "idx_safety_incident_release", columnList = "release_id,detected_at")
})
public class SafetyIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false, unique = true, length = 128)
    private String incidentId;

    @Column(name = "release_id", nullable = false, length = 128)
    private String releaseId;

    @Column(name = "incident_type", nullable = false, length = 64)
    private String incidentType;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String summary;

    @Column(name = "root_cause", columnDefinition = "LONGTEXT")
    private String rootCause;

    @Column(name = "corrective_action", columnDefinition = "LONGTEXT")
    private String correctiveAction;

    @Column(name = "incident_status", nullable = false, length = 32)
    private String status = "OPEN";

    @Column(name = "owner", nullable = false, length = 128)
    private String owner;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "evidence_uri", length = 2048)
    private String evidenceUri;

    protected SafetyIncident() {
    }

    public SafetyIncident(
            String incidentId,
            String releaseId,
            String incidentType,
            String severity,
            String summary,
            String owner,
            Instant detectedAt,
            Instant dueAt,
            String evidenceUri) {
        this.incidentId = code(incidentId, 128, "incident id");
        this.releaseId = code(releaseId, 128, "release id");
        this.incidentType = code(incidentType, 64, "incident type");
        this.severity = code(severity, 16, "severity").toUpperCase();
        this.summary = code(summary, 20_000, "incident summary");
        this.owner = code(owner, 128, "CAPA owner");
        this.detectedAt = detectedAt == null ? Instant.now() : detectedAt;
        this.dueAt = dueAt;
        this.evidenceUri = evidenceUri == null ? null : evidenceUri.strip();
        this.status = "OPEN";
    }

    public Long getId() { return id; }
    public String getIncidentId() { return incidentId; }
    public String getReleaseId() { return releaseId; }
    public String getIncidentType() { return incidentType; }
    public String getSeverity() { return severity; }
    public String getSummary() { return summary; }
    public String getRootCause() { return rootCause; }
    public String getCorrectiveAction() { return correctiveAction; }
    public String getStatus() { return status; }
    public String getOwner() { return owner; }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getDueAt() { return dueAt; }
    public Instant getClosedAt() { return closedAt; }
    public String getEvidenceUri() { return evidenceUri; }

    public void close(String rootCause, String correctiveAction) {
        if ("CLOSED".equals(status)) throw new IllegalStateException("incident is already closed");
        this.rootCause = code(rootCause, 20_000, "root cause");
        this.correctiveAction = code(correctiveAction, 20_000, "corrective action");
        this.status = "CLOSED";
        this.closedAt = Instant.now();
    }

    public void acknowledge() {
        if (!"OPEN".equals(status)) throw new IllegalStateException("only an open incident can be acknowledged");
        status = "ACKNOWLEDGED";
    }

    private static String code(String value, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) throw new IllegalArgumentException(field + " is required and too long");
        return normalized;
    }
}
