-- R7-2（V2.0 §17.3 / §24.4）ADS 服务表补齐：8 张 Hive ADS 对应的 MySQL 宽表。
-- 本期 Hive 侧只有 8 张 ADS，因此 ads_category_sale_m / ads_region_sale_m **不建**
-- （无 Hive 来源就先建空表 = 造假数据，禁止）。等 spark-jobs 产出对应 ADS 后再补迁移。
-- 主键一律含 snapshot_id（禁止 dt 单列主键）；主键已含 snapshot_id，故只补跨快照用的 idx_dt。

-- 活跃趋势（Hive ads_active_trend）
CREATE TABLE ads_active_trend_m (
    snapshot_id    VARCHAR(64) NOT NULL,
    dt             VARCHAR(16) NOT NULL,
    dau            BIGINT      NOT NULL DEFAULT 0,
    behavior_count BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (snapshot_id, dt),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS 日活跃趋势';

-- 热门商品（Hive ads_hot_product；rank_no 为榜单名次，主键组成部分）
CREATE TABLE ads_hot_product_m (
    snapshot_id  VARCHAR(64)   NOT NULL,
    dt           VARCHAR(16)   NOT NULL,
    product_id   BIGINT        NOT NULL,
    product_name VARCHAR(200)  NOT NULL DEFAULT '',
    heat_score   DECIMAL(18,4) NOT NULL DEFAULT 0,
    pv           BIGINT        NOT NULL DEFAULT 0,
    fav          BIGINT        NOT NULL DEFAULT 0,
    cart         BIGINT        NOT NULL DEFAULT 0,
    buy          BIGINT        NOT NULL DEFAULT 0,
    rank_no      INT           NOT NULL,
    PRIMARY KEY (snapshot_id, dt, rank_no),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS 热门商品热度榜';

-- 商品转化（Hive ads_product_conversion）
CREATE TABLE ads_product_conversion_m (
    snapshot_id     VARCHAR(64)  NOT NULL,
    dt              VARCHAR(16)  NOT NULL,
    product_id      BIGINT       NOT NULL,
    pv_users        BIGINT       NOT NULL DEFAULT 0,
    buy_users       BIGINT       NOT NULL DEFAULT 0,
    conversion_rate DECIMAL(8,4) NULL,
    PRIMARY KEY (snapshot_id, dt, product_id),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS 商品转化';

-- 用户画像/RFM（Hive ads_user_profile；不含敏感个人标识，仅 user_id 与聚合分层）
CREATE TABLE ads_user_profile_m (
    snapshot_id       VARCHAR(64) NOT NULL,
    dt                VARCHAR(16) NOT NULL,
    user_id           BIGINT      NOT NULL,
    `r`               INT         NOT NULL DEFAULT 0 COMMENT 'R 分（1..5）',
    `f`               INT         NOT NULL DEFAULT 0 COMMENT 'F 分（1..5）',
    `m`               INT         NOT NULL DEFAULT 0 COMMENT 'M 分（1..5）',
    value_group       VARCHAR(32) NOT NULL DEFAULT '' COMMENT 'RFM 八类价值分组',
    active_level      VARCHAR(32) NOT NULL DEFAULT '',
    favorite_category BIGINT      NOT NULL DEFAULT 0 COMMENT '偏好分类 id',
    last_active_date  VARCHAR(32) NOT NULL DEFAULT '',
    last_buy_date     VARCHAR(32) NOT NULL DEFAULT '',
    lifecycle_state   VARCHAR(32) NOT NULL DEFAULT '' COMMENT '活跃/沉默/流失风险',
    rule_version      VARCHAR(32) NOT NULL DEFAULT '',
    calc_date         VARCHAR(32) NOT NULL DEFAULT '',
    PRIMARY KEY (snapshot_id, dt, user_id),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS 用户画像（RFM 分层）';

-- 数据质量（Hive ads_data_quality；发布对账证据）
CREATE TABLE ads_data_quality_m (
    snapshot_id VARCHAR(64)   NOT NULL,
    dt          VARCHAR(16)   NOT NULL,
    rule_code   VARCHAR(64)   NOT NULL,
    check_count BIGINT        NOT NULL DEFAULT 0,
    error_count BIGINT        NOT NULL DEFAULT 0,
    error_rate  DECIMAL(12,6) NOT NULL DEFAULT 0,
    passed      INT           NOT NULL DEFAULT 0 COMMENT '1=通过 0=未通过',
    threshold   VARCHAR(200)  NOT NULL DEFAULT '',
    PRIMARY KEY (snapshot_id, dt, rule_code),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS 数据质量结果';
