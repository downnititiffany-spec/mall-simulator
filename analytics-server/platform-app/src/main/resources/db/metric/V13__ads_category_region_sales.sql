-- N31-02 leg C: add real category and city-level sales mirrors for Hive ADS producers.
-- Historical migrations V1-V12 remain immutable. Both tables retain snapshot history;
-- parent category is descriptive only and is not a duplicate aggregation level.

CREATE TABLE ads_category_sale_m (
    snapshot_id          VARCHAR(64)   NOT NULL,
    dt                   VARCHAR(16)   NOT NULL,
    category_id          BIGINT        NOT NULL COMMENT '-1 is the single unknown/unmapped category bucket',
    category_name        VARCHAR(128)  NOT NULL,
    parent_category_id   BIGINT        NOT NULL DEFAULT -1,
    parent_category_name VARCHAR(128)  NOT NULL DEFAULT '',
    sale_count           BIGINT        NOT NULL DEFAULT 0 COMMENT 'Sold quantity, not order count',
    sale_amount          DECIMAL(18,2) NOT NULL DEFAULT 0,
    net_sale_amount      DECIMAL(18,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (snapshot_id, dt, category_id),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS leaf-category sales by business day';

CREATE TABLE ads_region_sale_m (
    snapshot_id     VARCHAR(64)   NOT NULL,
    dt              VARCHAR(16)   NOT NULL,
    region          VARCHAR(64)   NOT NULL COMMENT 'City level from city_level, not administrative geography',
    sale_amount     DECIMAL(18,2) NOT NULL DEFAULT 0,
    net_sale_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (snapshot_id, dt, region),
    KEY idx_dt (dt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADS city-level sales by business day';
