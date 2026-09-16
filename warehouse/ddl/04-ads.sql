-- =====================================================================
-- ADS 应用数据层（§6.5）
-- 面向页面与 AI 查询的高度聚合指标；字段英文命名+中文语义（dim_metric/语义表）。
-- 快照发布：作业先写临时分区，质量校验通过后切换正式分区（阶段 6）。
-- 库名：${WAREHOUSE_PREFIX}_ads，缺省源 A 用 dw（beeline --hivevar WAREHOUSE_PREFIX=dw -f 04-ads.sql）；规则见 contract-specs/specs/warehouse-namespace.v1.json
-- =====================================================================
CREATE DATABASE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads COMMENT 'ADS 应用数据层';

-- 运营大盘
-- full_refund_rate（R7-0 口径统一）、repeat_rate/repeat_period_start/repeat_period_end（S3-03，
-- 设计 §11.2 L433 复购率+观察期声明）：静态 DDL 与运行时 DDL（spark-jobs/LocalSchemaInitJob）逐列对齐。
-- 注：本块的 snapshot_id 列定位与运行时（snapshot_id 只在 __staging 分区）仍不一致，属已登记
-- 缺陷 D-09/V25-C01（静态 DDL 与运行时 DDL 漂移），不在本轮范围内。
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_operation_overview (
    pv           BIGINT,
    uv           BIGINT,
    dau          BIGINT,
    order_count  BIGINT,
    sale_amount  DECIMAL(18,2),
    net_sale_amount DECIMAL(18,2),
    avg_order_value DECIMAL(18,2),
    refund_rate  DECIMAL(8,4) COMMENT '退款订单数/支付订单数',
    full_refund_rate DECIMAL(8,4) COMMENT '全额退款订单数/支付订单数',
    repeat_rate  DECIMAL(8,4) COMMENT '有效复购率=有效购买≥2次用户数/支付用户数（全退不算有效购买，S3-03）',
    repeat_period_start STRING COMMENT '复购率观察期起点（ISO yyyy-MM-dd，来自 DWS 行声明）',
    repeat_period_end   STRING COMMENT '复购率观察期终点（ISO yyyy-MM-dd，来自 DWS 行声明）',
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
    overall_buy_rate DECIMAL(8,4),
    overall_cart_rate DECIMAL(8,4) COMMENT 'cart_add/view（整体加购率），四行同值，浏览为0→null'
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
-- net_sale_amount（S3-02，设计 §9.3 L333 / §11.2 L428）：净销售额 = 销售额 − 已支付订单退款额，
-- 与 ads_operation_overview.net_sale_amount 同义同源（同一 dt 必须逐值相等）。
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ads.ads_sale_trend (
    order_count     BIGINT,
    buyer_count     BIGINT,
    sale_amount     DECIMAL(18,2),
    avg_order_value DECIMAL(18,2),
    net_sale_amount DECIMAL(18,2) COMMENT '净销售额：销售额-已支付订单退款额（与 ads_operation_overview 同口径）'
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