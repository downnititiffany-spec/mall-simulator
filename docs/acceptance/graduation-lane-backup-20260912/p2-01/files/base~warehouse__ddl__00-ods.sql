-- =====================================================================
-- ODS 原始数据层（§6.2）
-- Landing 之上建立的外部表：原样保留源字段，只增加采集审计字段。
-- 分区：dt（业务日期 yyyyMMdd）/ hour。
-- 数据从不从 Flume/DataX 直接进入 DWD；ODS 是所有 Spark 清洗任务的唯一正式入口。
-- 库名：${WAREHOUSE_PREFIX}_ods，缺省源 A 用 dw（beeline --hivevar WAREHOUSE_PREFIX=dw -f 00-ods.sql）；规则见 contract-specs/specs/warehouse-namespace.v1.json
-- =====================================================================
CREATE DATABASE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ods COMMENT 'ODS 原始数据层';

-- 用户事件（user_registered）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ods.ods_user_event (
    event_id        STRING  COMMENT '全局唯一事件ID',
    event_type      STRING,
    event_time      STRING  COMMENT '业务时间(ISO-8601带时区)',
    ingest_time     STRING  COMMENT '采集时间，链路延迟=ingest_time-event_time',
    source_system   STRING,
    schema_version  STRING  COMMENT '未知版本隔离，不发布',
    trace_id        STRING,
    payload_user_id         STRING,
    payload_age_group       STRING,
    payload_city_level      STRING,
    payload_member_level    STRING,
    payload_register_time   STRING,
    source_file     STRING  COMMENT '来源 Landing 文件',
    ingest_batch_id BIGINT  COMMENT '采集批次号'
)
COMMENT '用户事件原始层'
PARTITIONED BY (dt STRING COMMENT '业务日期 yyyyMMdd', hour STRING COMMENT '小时 HH')
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ods.db/ods_user_event';

-- 商品事件（product_created / product_updated）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ods.ods_product_event (
    event_id            STRING,
    event_type          STRING,
    event_time          STRING,
    ingest_time         STRING,
    source_system       STRING,
    schema_version      STRING,
    trace_id            STRING,
    payload_product_id  STRING,
    payload_product_name STRING,
    payload_category_id STRING,
    payload_category_name STRING,
    payload_parent_category_id STRING,
    payload_parent_category_name STRING,
    payload_brand_id    STRING,
    payload_price       DECIMAL(18,2),
    payload_cost        DECIMAL(18,2),
    payload_status      STRING,
    source_file         STRING,
    ingest_batch_id     BIGINT
)
COMMENT '商品事件原始层'
PARTITIONED BY (dt STRING, hour STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ods.db/ods_product_event';

-- 用户行为事件（behavior）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ods.ods_behavior_event (
    event_id            STRING,
    event_type          STRING,
    event_time          STRING,
    ingest_time         STRING,
    source_system       STRING,
    schema_version      STRING,
    trace_id            STRING,
    payload_user_id     STRING,
    payload_product_id  STRING,
    payload_session_id  STRING,
    payload_behavior_type STRING COMMENT 'view/favorite/cart_add/cart_remove/search',
    payload_channel     STRING,
    source_file         STRING,
    ingest_batch_id     BIGINT
)
COMMENT '用户行为事件原始层'
PARTITIONED BY (dt STRING, hour STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ods.db/ods_behavior_event';

-- 交易事件（order_created/order_paid/order_cancelled/refund_created/refund_completed/stock_*）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_ods.ods_trade_event (
    event_id            STRING,
    event_type          STRING,
    event_time          STRING,
    ingest_time         STRING,
    source_system       STRING,
    schema_version      STRING,
    trace_id            STRING,
    payload_order_id    STRING,
    payload_user_id     STRING,
    payload_payment_id  STRING,
    payload_refund_id   STRING,
    payload_product_id  STRING,
    payload_amount      DECIMAL(18,2) COMMENT '金额字符串转 DECIMAL 后存放',
    payload_total_amount DECIMAL(18,2),
    payload_status      STRING,
    payload_reason      STRING,
    payload_items       STRING  COMMENT 'order_created 的 items JSON 数组（DWD 展开）',
    source_file         STRING,
    ingest_batch_id     BIGINT
)
COMMENT '交易事件原始层'
PARTITIONED BY (dt STRING, hour STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_ods.db/ods_trade_event';