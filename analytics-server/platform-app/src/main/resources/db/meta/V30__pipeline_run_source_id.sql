-- G31-03.3: freeze source_registry.id on each pipeline run.
-- Historical rows remain NULL: their original source cannot be safely inferred from
-- the mutable runtime_profile.source_id and must never be guessed during retry/recovery.
ALTER TABLE pipeline_run
    ADD COLUMN source_id BIGINT NULL
        COMMENT 'Frozen source_registry.id; NULL means historical/unknown and is not backfilled',
    ADD KEY idx_pipeline_run_source_id (source_id);
