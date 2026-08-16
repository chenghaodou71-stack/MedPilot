ALTER TABLE consultation_records
    ADD COLUMN support_score DOUBLE NULL AFTER confidence,
    ADD COLUMN abstained BOOLEAN NOT NULL DEFAULT FALSE AFTER support_score,
    ADD COLUMN triage_factors TEXT NULL AFTER matched_rule,
    ADD COLUMN explanation TEXT NULL AFTER triage_factors;
