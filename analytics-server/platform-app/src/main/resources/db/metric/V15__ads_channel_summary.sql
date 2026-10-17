-- WP-F9b: add channel summary table (behavior-side channel funnel drilldown)
CREATE TABLE ads_channel_summary_m (
    snapshot_id  VARCHAR(64)   NOT NULL,
    dt           VARCHAR(16)   NOT NULL,
    channel      VARCHAR(64)   NOT NULL,
    pv           BIGINT        NOT NULL,
    view_users   BIGINT        NOT NULL,
    intent_users BIGINT        NOT NULL,
    cart_users   BIGINT        NOT NULL,
    intent_rate  DECIMAL(8,4)  NULL,
    cart_rate    DECIMAL(8,4)  NULL,
    PRIMARY KEY (snapshot_id, dt, channel),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS channel summary (behavior-side)';
