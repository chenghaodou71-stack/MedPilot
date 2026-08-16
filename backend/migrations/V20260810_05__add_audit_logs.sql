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
    UNIQUE KEY uk_audit_event_id (event_id),
    KEY idx_audit_created_at (created_at),
    KEY idx_audit_actor (actor_username)
);
