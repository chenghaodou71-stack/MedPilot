package com.medpilot.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_provider", nullable = false, length = 16)
    private IdentityProvider identityProvider = IdentityProvider.LOCAL;

    @Column(name = "external_subject", length = 255)
    private String externalSubject;

    @Column(name = "employee_number", length = 64)
    private String employeeNumber;

    @Column(name = "organization_code", length = 64)
    private String organizationCode;

    @Column(name = "campus_code", length = 64)
    private String campusCode;

    @Column(name = "department_code", length = 64)
    private String departmentCode;

    @Column(name = "patient_mpi_id", length = 128)
    private String patientMpiId;

    @Column(name = "mfa_assurance_level", nullable = false)
    private int mfaAssuranceLevel;

    @Column(name = "last_authenticated_at")
    private Instant lastAuthenticatedAt;

    @Column(name = "account_expires_at")
    private Instant accountExpiresAt;

    @Column(name = "local_password_enabled", nullable = false)
    private boolean localPasswordEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected User() {
    }

    public User(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = true;
        this.identityProvider = IdentityProvider.LOCAL;
        this.localPasswordEnabled = true;
        this.mfaAssuranceLevel = 0;
        this.tokenVersion = 0L;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public IdentityProvider getIdentityProvider() {
        return identityProvider;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
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

    public String getPatientMpiId() {
        return patientMpiId;
    }

    public int getMfaAssuranceLevel() {
        return mfaAssuranceLevel;
    }

    public Instant getLastAuthenticatedAt() {
        return lastAuthenticatedAt;
    }

    public Instant getAccountExpiresAt() {
        return accountExpiresAt;
    }

    public boolean isLocalPasswordEnabled() {
        return localPasswordEnabled;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isLoginEligibleAt(Instant now) {
        return active && (accountExpiresAt == null || accountExpiresAt.isAfter(now));
    }

    public boolean hasHospitalStaffProfile() {
        return isPresent(employeeNumber)
                && isPresent(organizationCode)
                && isPresent(campusCode)
                && isPresent(departmentCode);
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setFederatedIdentity(IdentityProvider provider, String subject) {
        if (provider == null || provider == IdentityProvider.LOCAL) {
            throw new IllegalArgumentException("federated identity provider is required");
        }
        this.identityProvider = provider;
        this.externalSubject = requiredCode(subject, 255, "external subject");
        this.localPasswordEnabled = false;
    }

    public void setHospitalStaffProfile(
            String employeeNumber,
            String organizationCode,
            String campusCode,
            String departmentCode) {
        this.employeeNumber = requiredCode(employeeNumber, 64, "employee number");
        this.organizationCode = requiredCode(organizationCode, 64, "organization code");
        this.campusCode = requiredCode(campusCode, 64, "campus code");
        this.departmentCode = requiredCode(departmentCode, 64, "department code");
        this.patientMpiId = null;
    }

    public void setPatientIdentity(String patientMpiId, String organizationCode) {
        this.patientMpiId = requiredCode(patientMpiId, 128, "patient MPI id");
        this.organizationCode = requiredCode(organizationCode, 64, "organization code");
        this.employeeNumber = null;
        this.campusCode = null;
        this.departmentCode = null;
    }

    public void setMfaAssuranceLevel(int mfaAssuranceLevel) {
        if (mfaAssuranceLevel < 0 || mfaAssuranceLevel > 9) {
            throw new IllegalArgumentException("MFA assurance level must be between 0 and 9");
        }
        this.mfaAssuranceLevel = mfaAssuranceLevel;
    }

    public void setAccountExpiresAt(Instant accountExpiresAt) {
        this.accountExpiresAt = accountExpiresAt;
    }

    public void setLocalPasswordEnabled(boolean localPasswordEnabled) {
        this.localPasswordEnabled = localPasswordEnabled;
    }

    public void markAuthenticated(Instant authenticatedAt) {
        this.lastAuthenticatedAt = authenticatedAt == null ? Instant.now() : authenticatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public void revokeTokens() {
        tokenVersion++;
    }

    private static String requiredCode(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
