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
import java.util.UUID;

/** Explicit emergency exception that grants time-bounded patient-record read access. */
@Entity
@Table(name = "break_glass_accesses")
public class BreakGlassAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "access_id", nullable = false, unique = true, length = 36)
    private String accessId;

    @Column(name = "clinician_user_id", nullable = false)
    private Long clinicianUserId;

    @Column(name = "patient_mpi_id", nullable = false, length = 128)
    private String patientMpiId;

    @Column(name = "organization_code", nullable = false, length = 64)
    private String organizationCode;

    @Column(name = "campus_code", nullable = false, length = 64)
    private String campusCode;

    @Column(name = "department_code", nullable = false, length = 64)
    private String departmentCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private BreakGlassPurpose purpose;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String reason;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BreakGlassAccess() {
    }

    public BreakGlassAccess(
            Long clinicianUserId,
            String patientMpiId,
            String organizationCode,
            String campusCode,
            String departmentCode,
            BreakGlassPurpose purpose,
            String reason,
            Instant grantedAt,
            Instant expiresAt) {
        if (clinicianUserId == null) throw new IllegalArgumentException("clinician user id is required");
        this.clinicianUserId = clinicianUserId;
        this.patientMpiId = requiredCode(patientMpiId, 128, "patient MPI id");
        this.organizationCode = requiredCode(organizationCode, 64, "organization code");
        this.campusCode = requiredCode(campusCode, 64, "campus code");
        this.departmentCode = requiredCode(departmentCode, 64, "department code");
        if (purpose == null) throw new IllegalArgumentException("break-glass purpose is required");
        this.purpose = purpose;
        this.reason = requiredReason(reason);
        this.grantedAt = grantedAt == null ? Instant.now() : grantedAt;
        if (expiresAt == null || !expiresAt.isAfter(this.grantedAt)) {
            throw new IllegalArgumentException("break-glass expiry must be after grant time");
        }
        this.expiresAt = expiresAt;
        this.accessId = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getAccessId() {
        return accessId;
    }

    public Long getClinicianUserId() {
        return clinicianUserId;
    }

    public String getPatientMpiId() {
        return patientMpiId;
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

    public BreakGlassPurpose getPurpose() {
        return purpose;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isActiveAt(Instant at) {
        return revokedAt == null && !grantedAt.isAfter(at) && expiresAt.isAfter(at);
    }

    public void revoke(Instant at) {
        this.revokedAt = at == null ? Instant.now() : at;
    }

    private static String requiredCode(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }

    private static String requiredReason(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < 10 || normalized.length() > 2_000) {
            throw new IllegalArgumentException("break-glass reason must contain 10 to 2000 characters");
        }
        return normalized;
    }
}
