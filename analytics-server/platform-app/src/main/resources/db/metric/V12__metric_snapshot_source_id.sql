-- V12: persist the business source identity on each metric snapshot.
-- source_id references source_registry.id in analytics_meta conceptually; no FK is used because
-- analytics_meta and analytics_metric may be hosted independently.
-- Existing snapshots intentionally remain NULL: current runtime-profile bindings are mutable and
-- cannot establish historical ownership. New publish callers must pass the source_id captured
-- when the pipeline run is created.

ALTER TABLE metric_snapshot
    ADD COLUMN source_id BIGINT NULL
        COMMENT 'Business source_registry.id; NULL means historical or unattributed source';
