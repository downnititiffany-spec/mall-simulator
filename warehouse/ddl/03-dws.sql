-- =====================================================================
-- DWS 汇总数据层（§6.4）
-- 主题宽表，供多个 ADS 应用指标复用；口径在此层固定（有效支付/去重用户）。
-- 库名：${WAREHOUSE_PREFIX}_dws，缺省源 A 用 dw（beeline --hivevar WAREHOUSE_PREFIX=dw -f 03-dws.sql）；规则见 contract-specs/specs/warehouse-namespace.v1.json
-- =====================================================================
CREATE DATABASE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dws COMMENT 'DWS 汇总数据层';

-- 用户×日期行为宽表
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dws.dws_user_behavior_day (
    user_id      BIGINT,
    pv           BIGINT,
    fav          BIGINT,
    cart         BIGINT,
    buy          BIGINT,
    search       BIGINT,
    active_hours INT COMMENT '活跃小时数'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dws.db/dws_user_behavior_day';

-- 行为漏斗（日期×(可选分类/渠道)）：各阶段去重用户数与转化率（§21.4 宽松口径）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dws.dws_behavior_funnel_day (
    category_id    BIGINT COMMENT '-1=全站',
    channel        STRING COMMENT 'all=全渠道',
    view_users     BIGINT,
    intent_users   BIGINT COMMENT '收藏或加购用户',
    order_users    BIGINT COMMENT '创建订单用户',
    pay_users      BIGINT COMMENT '支付成功用户',
    intent_rate    DECIMAL(8,4) COMMENT 'intent/view，分母0→null',
    order_rate     DECIMAL(8,4),
    pay_rate       DECIMAL(8,4),
    overall_buy_rate DECIMAL(8,4) COMMENT 'pay/view（整体购买转化率）'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dws.db/dws_behavior_funnel_day';

-- 商品×日期行为宽表（热度在 ADS 或本层计算均可；本层存原始计数）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dws.dws_product_behavior_day (
    product_id BIGINT,
    category_id BIGINT,
    pv BIGINT,
    uv BIGINT COMMENT '浏览去重用户',
    fav BIGINT,
    cart BIGINT,
    buy BIGINT COMMENT '支付件数'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dws.db/dws_product_behavior_day';

-- 商品×日期销售宽表
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dws.dws_product_sale_day (
    product_id   BIGINT,
    category_id  BIGINT,
    sale_count   BIGINT COMMENT '支付商品件数',
    sale_amount  DECIMAL(18,2) COMMENT '支付金额',
    buyer_count  BIGINT COMMENT '去重支付用户'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dws.db/dws_product_sale_day';

-- 平台×日期交易汇总
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dws.dws_trade_day (
    order_count     BIGINT COMMENT '支付订单数',
    buyer_count     BIGINT,
    sale_amount     DECIMAL(18,2) COMMENT 'GMV=支付金额',
    refund_amount   DECIMAL(18,2),
    net_sale_amount DECIMAL(18,2) COMMENT 'GMV-退款',
    avg_order_value DECIMAL(18,2) COMMENT 'GMV/支付订单数，0订单→null'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dws.db/dws_trade_day';

-- 用户×统计周期交易汇总（复购/RFM 输入）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dws.dws_user_trade_period (
    user_id      BIGINT,
    last_buy_date STRING COMMENT '观察期最近有效支付日',
    order_count  BIGINT COMMENT '有效支付订单数',
    sale_amount  DECIMAL(18,2),
    period_start STRING,
    period_end   STRING,
    valid_order_count BIGINT COMMENT '观察期内有效购买订单数（剔除全额退款，S3-03 复购率分子用）'
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dws.db/dws_user_trade_period';

-- 地区×日期销售汇总
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dws.dws_region_sale_day (
    region      STRING COMMENT '城市等级',
    buyer_count BIGINT,
    order_count BIGINT,
    sale_amount DECIMAL(18,2)
)
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dws.db/dws_region_sale_day';