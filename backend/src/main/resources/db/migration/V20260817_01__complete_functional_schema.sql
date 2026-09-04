ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 AFTER active;

CREATE TABLE consultation_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    trace_id VARCHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_consultation_messages_session_order (session_id, user_id, created_at, id)
);

CREATE TABLE knowledge_documents (
    doc_id VARCHAR(128) NOT NULL,
    title VARCHAR(512) NOT NULL,
    department VARCHAR(32) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    institution VARCHAR(256) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    published_date VARCHAR(10) NULL,
    source_version VARCHAR(256) NULL,
    license_name VARCHAR(512) NULL,
    original_filename VARCHAR(512) NULL,
    media_type VARCHAR(128) NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    checksum VARCHAR(64) NOT NULL,
    parsing_status VARCHAR(32) NOT NULL,
    vector_status VARCHAR(32) NOT NULL,
    review_status VARCHAR(16) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    processing_error TEXT NULL,
    reviewer VARCHAR(128) NULL,
    reviewed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (doc_id),
    KEY idx_knowledge_documents_status (review_status, parsing_status, vector_status),
    KEY idx_knowledge_documents_updated (updated_at)
);

ALTER TABLE consultation_records
    MODIFY COLUMN symptoms LONGTEXT NULL,
    MODIFY COLUMN triage_factors LONGTEXT NULL,
    MODIFY COLUMN explanation LONGTEXT NULL,
    MODIFY COLUMN answer LONGTEXT NULL,
    MODIFY COLUMN citations LONGTEXT NULL,
    MODIFY COLUMN conversation_history LONGTEXT NULL;

ALTER TABLE consultation_attachments
    MODIFY COLUMN original_filename LONGTEXT NOT NULL,
    MODIFY COLUMN extracted_text LONGTEXT NULL,
    MODIFY COLUMN draft_text LONGTEXT NULL;

ALTER TABLE health_profiles
    MODIFY COLUMN profile_json LONGTEXT NOT NULL;

ALTER TABLE follow_up_tasks
    MODIFY COLUMN title LONGTEXT NOT NULL,
    MODIFY COLUMN notes LONGTEXT NULL;
