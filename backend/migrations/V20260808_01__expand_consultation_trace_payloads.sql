-- Forward-only widening migration. Reverting to TINYTEXT would truncate encrypted traces.
-- Apply before deploying the matching ConsultationTrace entity mapping in non-dev environments.
ALTER TABLE consultation_traces
    MODIFY COLUMN events_json LONGTEXT NOT NULL,
    MODIFY COLUMN citations_json LONGTEXT NOT NULL;
