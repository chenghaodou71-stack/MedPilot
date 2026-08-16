-- Persist operational outcome metadata without exposing encrypted event payloads to SQL filters.
ALTER TABLE consultation_traces
    ADD COLUMN failure_code VARCHAR(64) NULL,
    ADD COLUMN total_duration_ms BIGINT NOT NULL DEFAULT 0;
