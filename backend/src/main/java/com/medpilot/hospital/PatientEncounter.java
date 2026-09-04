package com.medpilot.hospital;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Encounter reference synchronized from the hospital registration/EMR system. */
@Entity
@Table(name = "patient_encounters")
public class PatientEncounter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "encounter_number", nullable = false, length = 128)
    private String encounterNumber;

    @Column(name = "organization_code", nullable = false, length = 64)
    private String organizationCode;

    @Column(name = "campus_code", nullable = false, length = 64)
    private String campusCode;

    @Column(name = "department_code", nullable = false, length = 64)
    private String departmentCode;

    @Column(name = "responsible_clinician_user_id")
    private Long responsibleClinicianUserId;

    @Column(name = "encounter_status", nullable = false, length = 32)
    private String encounterStatus;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "source_system", nullable = false, length = 64)
    private String sourceSystem;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PatientEncounter() {
    }

    public PatientEncounter(
            Long patientId,
            String encounterNumber,
            String organizationCode,
            String campusCode,
            String departmentCode,
            String encounterStatus,
            Instant startedAt,
            String sourceSystem) {
        if (patientId == null) throw new IllegalArgumentException("patient id is required");
        this.patientId = patientId;
        this.encounterNumber = requiredCode(encounterNumber, 128, "encounter number");
        this.organizationCode = requiredCode(organizationCode, 64, "organization code");
        this.campusCode = requiredCode(campusCode, 64, "campus code");
        this.departmentCode = requiredCode(departmentCode, 64, "department code");
        this.encounterStatus = requiredCode(encounterStatus, 32, "encounter status");
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
        this.sourceSystem = requiredCode(sourceSystem, 64, "source system");
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public String getEncounterNumber() {
        return encounterNumber;
    }

    public String getOrganizationCode() {
        return organizationCode;
    }

    public String getCampusCode() {
        return campusCode;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public String getEncounterStatus() {
        return encounterStatus;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public boolean isOpenAt(Instant at) {
        Instant point = at == null ? Instant.now() : at;
        return startedAt != null
                && !startedAt.isAfter(point)
                && (endedAt == null || endedAt.isAfter(point))
                && !"CANCELLED".equalsIgnoreCase(encounterStatus)
                && !"CLOSED".equalsIgnoreCase(encounterStatus);
    }

    private static String requiredCode(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }
}
