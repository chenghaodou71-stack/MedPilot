CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
);

CREATE TABLE consultation_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    last_active_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consultation_sessions_session (session_id)
);

CREATE TABLE consultation_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    trace_id VARCHAR(36) NULL,
    symptoms TEXT NULL,
    department VARCHAR(64) NULL,
    risk_level VARCHAR(16) NULL,
    confidence DOUBLE NULL,
    support_score DOUBLE NULL,
    abstained BOOLEAN NOT NULL DEFAULT FALSE,
    urgency TEXT NULL,
    matched_rule VARCHAR(128) NULL,
    triage_factors TEXT NULL,
    explanation TEXT NULL,
    answer TEXT NULL,
    citations TEXT NULL,
    conversation_history TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consultation_records_trace (trace_id),
    KEY idx_consultation_records_user_created (user_id, created_at),
    KEY idx_consultation_records_session (session_id)
);

CREATE TABLE consultation_traces (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trace_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    events_json LONGTEXT NOT NULL,
    citations_json LONGTEXT NOT NULL,
    terminal_phase VARCHAR(32) NOT NULL,
    followup_pending BOOLEAN NOT NULL,
    failure_code VARCHAR(64) NULL,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consultation_traces_trace (trace_id),
    KEY idx_consultation_traces_session (session_id),
    KEY idx_consultation_traces_created (created_at)
);

CREATE TABLE health_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    profile_json TEXT NOT NULL,
    consent_granted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_health_profiles_user (user_id)
);

CREATE TABLE follow_up_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    record_id BIGINT NULL,
    title TEXT NOT NULL,
    notes TEXT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_follow_up_tasks_user_due (user_id, due_at)
);

CREATE TABLE consultation_attachments (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    storage_key VARCHAR(64) NOT NULL,
    original_filename TEXT NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    extracted_text TEXT NULL,
    draft_text TEXT NULL,
    confirmed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_consultation_attachments_storage (storage_key),
    KEY idx_consultation_attachments_owner_session (user_id, session_id, created_at),
    KEY idx_consultation_attachments_expiry (expires_at)
);

CREATE TABLE audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL,
    actor_username VARCHAR(64) NULL,
    actor_role VARCHAR(32) NULL,
    method VARCHAR(16) NOT NULL,
    action VARCHAR(160) NOT NULL,
    status INT NOT NULL,
    success BOOLEAN NOT NULL,
    request_id VARCHAR(64) NULL,
    ip_hash VARCHAR(64) NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_logs_event (event_id),
    KEY idx_audit_created_at (created_at),
    KEY idx_audit_actor (actor_username)
);
