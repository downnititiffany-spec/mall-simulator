-- Nullable fields preserve historical logs without inventing request/source identities.
-- Fingerprints are non-unique: retries/independent repeated calls must each remain auditable.
ALTER TABLE ai_call_log
    ADD COLUMN source_id BIGINT NULL,
    ADD COLUMN snapshot_id VARCHAR(64) NULL,
    ADD COLUMN request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD KEY idx_ai_call_fingerprint (request_fingerprint),
    ADD KEY idx_ai_call_source_snapshot (source_id, snapshot_id);
