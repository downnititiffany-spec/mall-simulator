-- V38: immutable owner-scoped business report instances; artifact bytes remain in the metadata database.
CREATE TABLE report_instance (
    id BIGINT NOT NULL AUTO_INCREMENT,
    template_code VARCHAR(48) NOT NULL,
    template_version VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    definition_version VARCHAR(32) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    prompt_version VARCHAR(32) NULL,
    creator_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'READY/FAILED',
    artifact_uri VARCHAR(160) NULL,
    artifact_checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    artifact_json LONGTEXT NULL,
    stable_error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_report_creator_time (creator_id, created_at, id),
    KEY idx_report_source_snapshot (source_id, snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变业务报告实例及结构化JSON制品';
