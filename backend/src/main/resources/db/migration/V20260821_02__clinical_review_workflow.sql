-- Clinical safety gate. The AI consultation row remains immutable; clinician
-- decisions are append-oriented workflow fields in a separate aggregate.
-- This forward-only production migration is intentionally irreversible; use a
-- separately reviewed forward migration for any retirement or data export.
CREATE TABLE clinical_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_id VARCHAR(36) NOT NULL,
    consultation_record_id BIGINT NOT NULL,
    patient_mpi_id VARCHAR(128) NULL,
    organization_code VARCHAR(64) NULL,
    campus_code VARCHAR(64) NULL,
    encounter_department_code VARCHAR(64) NULL,
    ai_trace_id VARCHAR(36) NULL,
    original_department VARCHAR(128) NULL,
    original_risk_level VARCHAR(32) NULL,
    original_urgency TEXT NULL,
    status VARCHAR(32) NOT NULL,
    decision VARCHAR(16) NULL,
    claimed_by_user_id BIGINT NULL,
    reviewer_user_id BIGINT NULL,
    reviewer_employee_number VARCHAR(64) NULL,
    claimed_at TIMESTAMP(6) NULL,
    final_department VARCHAR(128) NULL,
    final_risk_level VARCHAR(32) NULL,
    final_urgency TEXT NULL,
    decision_reason LONGTEXT NULL,
    decided_at TIMESTAMP(6) NULL,
    emergency_escalated_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_clinical_reviews_review_id (review_id),
    UNIQUE KEY uk_clinical_reviews_record (consultation_record_id),
    KEY idx_clinical_reviews_queue (status, created_at),
    KEY idx_clinical_reviews_reviewer (claimed_by_user_id, status),
    KEY idx_clinical_reviews_patient_access (
        patient_mpi_id, organization_code, campus_code, encounter_department_code),
    CONSTRAINT fk_clinical_reviews_record
        FOREIGN KEY (consultation_record_id) REFERENCES consultation_records(id)
);
