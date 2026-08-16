CREATE TABLE IF NOT EXISTS health_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    profile_json TEXT NOT NULL,
    consent_granted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_health_profiles_user (user_id)
);

CREATE TABLE IF NOT EXISTS follow_up_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    record_id BIGINT NULL,
    title TEXT NOT NULL,
    notes TEXT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_follow_up_tasks_user_due (user_id, due_at)
);
