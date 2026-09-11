-- =====================================================================
-- 维度表（§6.3.3）
-- 小维表广播连接；dim_metric 是指标口径字典的 Hive 侧副本（源头在平台 MySQL）。
-- 库名：${WAREHOUSE_PREFIX}_dim，缺省源 A 用 dw（beeline --hivevar WAREHOUSE_PREFIX=dw -f 02-dims.sql）；规则见 contract-specs/specs/warehouse-namespace.v1.json
-- =====================================================================
CREATE DATABASE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dim COMMENT '维度层';

CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dim.dim_user (
    user_id       BIGINT,
    age_group     STRING,
    city_level    STRING,
    member_level  STRING,
    register_date STRING COMMENT 'yyyy-MM-dd',
    register_time TIMESTAMP,
    source_batch_id BIGINT COMMENT '来源采集批次（§11.2）'
)
COMMENT '用户维度（模拟属性，无真实个人信息）'
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dim.db/dim_user';

CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dim.dim_product (
    product_id   BIGINT,
    product_name STRING,
    category_id  BIGINT,
    category_name STRING,
    parent_category_id BIGINT COMMENT '一级分类',
    parent_category_name STRING,
    brand_id     BIGINT,
    price        DECIMAL(18,2),
    cost         DECIMAL(18,2),
    status       STRING,
    source_batch_id BIGINT COMMENT '来源采集批次（§11.2）'
)
COMMENT '商品维度（含一级/二级分类归属）'
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dim.db/dim_product';

CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dim.dim_date (
    date_key    STRING COMMENT 'yyyy-MM-dd',
    year        INT,
    month       INT,
    day         INT,
    week_of_year INT,
    quarter     INT,
    day_of_week INT COMMENT '1-7',
    is_weekend  INT COMMENT '1=周末'
)
COMMENT '日期维度'
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dim.db/dim_date';

CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dim.dim_region (
    region_code STRING,
    region_name STRING,
    city_level  STRING COMMENT 'tier1/tier2/tier3/other'
)
COMMENT '地区维度（模拟城市等级，不存真实地址）'
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dim.db/dim_region';

CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dim.dim_metric (
    metric_code       STRING,
    metric_name       STRING,
    formula           STRING COMMENT '计算口径公式',
    grain             STRING COMMENT '统计粒度',
    default_time_field STRING,
    allowed_dimensions STRING,
    definition_version STRING COMMENT '指标字典版本（v1...）'
)
COMMENT '指标口径字典（Hive 侧副本，唯一来源在平台 MySQL metric_definition）'
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dim.db/dim_metric';