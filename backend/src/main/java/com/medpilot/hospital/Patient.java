package com.medpilot.hospital;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Minimal patient master-index reference. Direct identity attributes stay in the hospital MPI. */
@Entity
@Table(name = "patients")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mpi_id", nullable = false, unique = true, length = 128)
    private String mpiId;

    @Column(name = "organization_code", nullable = false, length = 64)
    private String organizationCode;

    @Column(name = "source_system", nullable = false, length = 64)
    private String sourceSystem;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Patient() {
    }

    public Patient(String mpiId, String organizationCode, String sourceSystem) {
        this.mpiId = requiredCode(mpiId, 128, "MPI id");
        this.organizationCode = requiredCode(organizationCode, 64, "organization code");
        this.sourceSystem = requiredCode(sourceSystem, 64, "source system");
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getMpiId() {
        return mpiId;
    }

    public String getOrganizationCode() {
        return organizationCode;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public void updateFromSource(boolean active, Instant sourceUpdatedAt) {
        this.active = active;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.updatedAt = Instant.now();
    }

    private static String requiredCode(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }
}
