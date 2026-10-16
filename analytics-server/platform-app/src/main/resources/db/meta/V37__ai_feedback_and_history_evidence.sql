-- V37: append-only, owner-scoped AI feedback with evidence lineage.
-- Historical rows keep NULL lineage; feedback is accepted only for newly anchored EXECUTED rows.
ALTER TABLE ai_query_history
    ADD COLUMN source_id BIGINT NULL,
    ADD COLUMN definition_version VARCHAR(32) NULL,
    ADD COLUMN evidence_id VARCHAR(64) NULL,
    ADD KEY idx_ai_history_evidence (evidence_id);

CREATE TABLE ai_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    history_id BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    rating VARCHAR(8) NOT NULL COMMENT 'GOOD/BAD',
    reason_code VARCHAR(48) NOT NULL,
    reason_code_version VARCHAR(16) NOT NULL DEFAULT 'v1',
    source_id BIGINT NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    definition_version VARCHAR(32) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_feedback_history (history_id),
    KEY idx_ai_feedback_user_time (user_id, created_at, id),
    KEY idx_ai_feedback_source_snapshot (source_id, snapshot_id),
    CONSTRAINT fk_ai_feedback_history FOREIGN KEY (history_id) REFERENCES ai_query_history (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI问数反馈及其不可变证据锚点';
