-- =====================================================================
-- ADS 应用数据层（§6.5）
-- 面向页面与 AI 查询的高度聚合指标；字段英文命名+中文语义（dim_metric/语义表）。
-- 快照发布：作业先写临时分区，质量校验通过后切换正式分区（阶段 6）。
-- 库名：${WAREHOUSE_PREFIX}_ads，缺省源 A 用 dw（beeline --hivevar WAREHOUSE_PREFIX=dw -f 04-ads.sql）；规则见 contract-specs/specs/warehouse-namespace.v1.json
-- =====================================================================
CREATE DATABASE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads COMMENT 'ADS 应用数据层';

-- 运营大盘
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_operation_overview (
    pv           BIGINT,
    uv           BIGINT,
    dau          BIGINT,
    order_count  BIGINT,
    sale_amount  DECIMAL(18,2),
    net_sale_amount DECIMAL(18,2),
    avg_order_value DECIMAL(18,2),
    refund_rate  DECIMAL(8,4) COMMENT '退款订单数/支付订单数',
    snapshot_id  STRING COMMENT '指标快照ID（阶段6发布）'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_operation_overview';

-- 转化漏斗（页面/AI 直接使用；dt 仅作分区列，不设普通列）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_behavior_funnel (
    stage          STRING COMMENT 'view/intent/order/pay',
    user_count     BIGINT,
    conversion_rate DECIMAL(8,4) COMMENT '后一阶段/前一阶段，首阶段=1',
    overall_buy_rate DECIMAL(8,4)
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_behavior_funnel';

-- 活跃趋势（dt 仅作分区列，不设普通列）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_active_trend (
    dau            BIGINT,
    behavior_count BIGINT
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_active_trend';

-- 热门商品排行（rank_no 按 heat_score 降序）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_hot_product (
    product_id   BIGINT,
    product_name STRING,
    heat_score   DECIMAL(18,4),
    pv           BIGINT,
    fav          BIGINT,
    cart         BIGINT,
    buy          BIGINT,
    rank_no      INT
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_hot_product';

-- 商品转化
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_product_conversion (
    product_id      BIGINT,
    pv_users        BIGINT,
    buy_users       BIGINT,
    conversion_rate DECIMAL(8,4) COMMENT 'buy_users/pv_users，分母0→null'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_product_conversion';

-- 销售趋势
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_sale_trend (
    order_count     BIGINT,
    buyer_count     BIGINT,
    sale_amount     DECIMAL(18,2),
    avg_order_value DECIMAL(18,2)
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_sale_trend';

-- 分类销售
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_category_sale (
    category_id   BIGINT,
    category_name STRING,
    sale_count    BIGINT,
    sale_amount   DECIMAL(18,2),
    amount_ratio  DECIMAL(8,4) COMMENT '分类金额/全站金额'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_category_sale';

-- 地区销售
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_region_sale (
    region      STRING,
    buyer_count BIGINT,
    sale_amount DECIMAL(18,2)
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_region_sale';

-- 用户画像（RFM 规则分层，§21.6；definition_version 必须随结果保存；S3-01 起随分档落 R/F/M 原值与观察窗口，§11.4 L447）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_user_profile (
    user_id         BIGINT,
    r               INT COMMENT '反向五分位1-5',
    f               INT,
    m               INT,
    value_group     STRING COMMENT '重要价值/重要发展/重要保持/重要挽留/一般价值/一般发展/一般保持/一般挽留',
    active_level    STRING COMMENT '高/中/低',
    favorite_category BIGINT,
    last_active_date STRING,
    last_buy_date   STRING,
    lifecycle_state STRING COMMENT '新用户/活跃/沉默/流失风险',
    rule_version    STRING COMMENT '规则版本',
    calc_date       STRING,
    r_days          INT COMMENT 'R 原值：观察窗口末日-末次购买日（天，越小越近）',
    f_count         BIGINT COMMENT 'F 原值：观察窗口内有效支付订单数',
    m_amount        DECIMAL(18,2) COMMENT 'M 原值：观察窗口内有效支付金额',
    period_start    STRING COMMENT '观察窗口起（yyyy-MM-dd，本次评分实际使用）',
    period_end      STRING COMMENT '观察窗口止（yyyy-MM-dd，本次评分实际使用）'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_user_profile';

-- 数据质量大盘（§5.4）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_data_quality (
    rule_code   STRING,
    check_count BIGINT,
    error_count BIGINT,
    error_rate  DECIMAL(8,6),
    passed      INT COMMENT '1=通过 0=未通过',
    threshold   STRING
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ads.db/ads_data_quality';