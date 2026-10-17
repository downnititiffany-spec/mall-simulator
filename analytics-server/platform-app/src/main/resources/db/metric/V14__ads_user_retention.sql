-- WP-F9a: add user retention table (D+1, D+7, D+30)
CREATE TABLE ads_user_retention_m (
    snapshot_id       VARCHAR(64)   NOT NULL,
    dt                VARCHAR(16)   NOT NULL,
    cohort_user_count BIGINT        NOT NULL,
    retained_d1       BIGINT        NULL,
    retention_d1      DECIMAL(8,4)  NULL,
    retained_d7       BIGINT        NULL,
    retention_d7      DECIMAL(8,4)  NULL,
    retained_d30      BIGINT        NULL,
    retention_d30     DECIMAL(8,4)  NULL,
    PRIMARY KEY (snapshot_id, dt),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS user retention D+1/D+7/D+30';
