package com.medpilot.hospital;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Time-bounded care-team membership imported from a trusted hospital source. */
@Entity
@Table(name = "patient_care_relationships")
public class PatientCareRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_mpi_id", nullable = false, length = 128)
    private String patientMpiId;

    @Column(name = "clinician_user_id", nullable = false)
    private Long clinicianUserId;

    @Column(name = "organization_code", nullable = false, length = 64)
    private String organizationCode;

    @Column(name = "campus_code", nullable = false, length = 64)
    private String campusCode;

    @Column(name = "department_code", nullable = false, length = 64)
    private String departmentCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 32)
    private CareRelationshipType relationshipType;

    @Column(name = "source_system", nullable = false, length = 64)
    private String sourceSystem;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PatientCareRelationship() {
    }

    public PatientCareRelationship(
            String patientMpiId,
            Long clinicianUserId,
            String organizationCode,
            String campusCode,
            String departmentCode,
            CareRelationshipType relationshipType,
            String sourceSystem,
            Instant validFrom,
            Instant validUntil) {
        if (clinicianUserId == null) throw new IllegalArgumentException("clinician user id is required");
        this.patientMpiId = requiredCode(patientMpiId, 128, "patient MPI id");
        this.clinicianUserId = clinicianUserId;
        this.organizationCode = requiredCode(organizationCode, 64, "organization code");
        this.campusCode = requiredCode(campusCode, 64, "campus code");
        this.departmentCode = requiredCode(departmentCode, 64, "department code");
        if (relationshipType == null) throw new IllegalArgumentException("relationship type is required");
        this.relationshipType = relationshipType;
        this.sourceSystem = requiredCode(sourceSystem, 64, "source system");
        this.validFrom = validFrom == null ? Instant.now() : validFrom;
        if (validUntil != null && !validUntil.isAfter(this.validFrom)) {
            throw new IllegalArgumentException("valid until must be after valid from");
        }
        this.validUntil = validUntil;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getPatientMpiId() {
        return patientMpiId;
    }

    public Long getClinicianUserId() {
        return clinicianUserId;
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

    public boolean isActiveAt(Instant at) {
        return active && !validFrom.isAfter(at) && (validUntil == null || validUntil.isAfter(at));
    }

    private static String requiredCode(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }
}
