-- Expand-only hospital identity and clinical-record access foundation.
-- Backfill is intentionally left to the hospital IdP/MPI/EMR integration.

ALTER TABLE users
    MODIFY COLUMN password_hash VARCHAR(255) NULL,
    ADD COLUMN identity_provider VARCHAR(16) NOT NULL DEFAULT 'LOCAL' AFTER password_hash,
    ADD COLUMN external_subject VARCHAR(255) NULL AFTER identity_provider,
    ADD COLUMN employee_number VARCHAR(64) NULL AFTER external_subject,
    ADD COLUMN organization_code VARCHAR(64) NULL AFTER employee_number,
    ADD COLUMN campus_code VARCHAR(64) NULL AFTER organization_code,
    ADD COLUMN department_code VARCHAR(64) NULL AFTER campus_code,
    ADD COLUMN patient_mpi_id VARCHAR(128) NULL AFTER department_code,
    ADD COLUMN mfa_assurance_level INT NOT NULL DEFAULT 0 AFTER patient_mpi_id,
    ADD COLUMN last_authenticated_at TIMESTAMP(6) NULL AFTER mfa_assurance_level,
    ADD COLUMN account_expires_at TIMESTAMP(6) NULL AFTER last_authenticated_at,
    ADD COLUMN local_password_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER account_expires_at,
    ADD UNIQUE KEY uk_users_federated_subject (identity_provider, external_subject),
    ADD KEY idx_users_patient_mpi (patient_mpi_id),
    ADD KEY idx_users_employee_number (employee_number);

CREATE TABLE patients (
    id BIGINT NOT NULL AUTO_INCREMENT,
    mpi_id VARCHAR(128) NOT NULL,
    organization_code VARCHAR(64) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    source_updated_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_patients_mpi (mpi_id),
    KEY idx_patients_organization_active (organization_code, active)
);

CREATE TABLE patient_encounters (
    id BIGINT NOT NULL AUTO_INCREMENT,
    patient_id BIGINT NOT NULL,
    encounter_number VARCHAR(128) NOT NULL,
    organization_code VARCHAR(64) NOT NULL,
    campus_code VARCHAR(64) NOT NULL,
    department_code VARCHAR(64) NOT NULL,
    responsible_clinician_user_id BIGINT NULL,
    encounter_status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    ended_at TIMESTAMP(6) NULL,
    source_system VARCHAR(64) NOT NULL,
    source_updated_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_patient_encounters_org_number (organization_code, encounter_number),
    KEY idx_patient_encounters_patient_status (patient_id, encounter_status),
    KEY idx_patient_encounters_clinician (responsible_clinician_user_id, started_at),
    CONSTRAINT fk_patient_encounters_patient
        FOREIGN KEY (patient_id) REFERENCES patients(id)
);

CREATE TABLE patient_care_relationships (
    id BIGINT NOT NULL AUTO_INCREMENT,
    patient_mpi_id VARCHAR(128) NOT NULL,
    clinician_user_id BIGINT NOT NULL,
    organization_code VARCHAR(64) NOT NULL,
    campus_code VARCHAR(64) NOT NULL,
    department_code VARCHAR(64) NOT NULL,
    relationship_type VARCHAR(32) NOT NULL,
    source_system VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from TIMESTAMP(6) NOT NULL,
    valid_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_care_relationship_access (
        clinician_user_id, patient_mpi_id, organization_code, campus_code, department_code, active, valid_from),
    KEY idx_care_relationship_patient (patient_mpi_id, active)
);

CREATE TABLE break_glass_accesses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    access_id VARCHAR(36) NOT NULL,
    clinician_user_id BIGINT NOT NULL,
    patient_mpi_id VARCHAR(128) NOT NULL,
    organization_code VARCHAR(64) NOT NULL,
    campus_code VARCHAR(64) NOT NULL,
    department_code VARCHAR(64) NOT NULL,
    purpose VARCHAR(48) NOT NULL,
    reason LONGTEXT NOT NULL,
    granted_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_break_glass_access_id (access_id),
    KEY idx_break_glass_access (
        clinician_user_id, patient_mpi_id, organization_code, campus_code, department_code, expires_at),
    KEY idx_break_glass_expiry (expires_at)
);

ALTER TABLE consultation_records
    ADD COLUMN patient_mpi_id VARCHAR(128) NULL AFTER user_id,
    ADD COLUMN encounter_number VARCHAR(128) NULL AFTER patient_mpi_id,
    ADD COLUMN organization_code VARCHAR(64) NULL AFTER encounter_number,
    ADD COLUMN campus_code VARCHAR(64) NULL AFTER organization_code,
    ADD COLUMN encounter_department_code VARCHAR(64) NULL AFTER campus_code,
    ADD KEY idx_consultation_records_patient_access (
        patient_mpi_id, organization_code, campus_code, encounter_department_code, created_at);
