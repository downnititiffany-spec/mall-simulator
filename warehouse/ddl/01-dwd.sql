-- =====================================================================
-- DWD 明细数据层（§6.3）
-- 清洗：时间统一、枚举映射、空值过滤、event_id 去重、维度补充；
-- 异常数据进入 dwd_reject_record，不混入正式明细。
-- 金额 DECIMAL(18,2)；event_time 决定指标归属日。
-- =====================================================================
CREATE DATABASE IF NOT EXISTS dw_dwd COMMENT 'DWD 明细数据层';

-- 用户行为明细（唯一事实：每个行为一行，event_id 去重后）
CREATE EXTERNAL TABLE IF NOT EXISTS dw_dwd.dwd_user_behavior_detail (
    behavior_id   STRING  COMMENT '= event_id',
    user_id       BIGINT,
    product_id    BIGINT,
    category_id   BIGINT  COMMENT '商品维表补充',
    behavior_type STRING  COMMENT 'view/favorite/cart_add/cart_remove/search',
    event_time    TIMESTAMP,
    event_date    STRING  COMMENT 'yyyy-MM-dd',
    event_hour    INT,
    city_level    STRING  COMMENT '用户维表补充',
    channel       STRING,
    session_id    STRING,
    source_batch_id BIGINT
)
COMMENT '用户行为明细（去重、标准枚举、维度补充）'
PARTITIONED BY (dt STRING COMMENT '业务日期 yyyyMMdd')
STORED AS PARQUET
LOCATION '/user/hive/warehouse/dw_dwd.db/dwd_user_behavior_detail';

-- 订单明细（状态展开：同 order_id 的事件按 event_time 重建最新状态）
CREATE EXTERNAL TABLE IF NOT EXISTS dw_dwd.dwd_order_detail (
    order_id      BIGINT,
    user_id       BIGINT,
    product_id    BIGINT,
    category_id   BIGINT,
    quantity      INT,
    unit_price    DECIMAL(18,2),
    discount      DECIMAL(18,2),
    amount        DECIMAL(18,2) COMMENT '=quantity×unit_price−discount',
    order_status  STRING  COMMENT 'CREATED/PAID/COMPLETED/CANCELLED/REFUNDING/REFUNDED',
    order_time    TIMESTAMP,
    order_date    STRING,
    city_level    STRING,
    paid_at       TIMESTAMP COMMENT 'order_paid 事件时间',
    refund_amount DECIMAL(18,2) COMMENT '累计退款金额（refund_completed 汇总）',
    final_paid_flag INT COMMENT '1=有效支付（口径依据，§21.3）',
    final_refunded_flag INT COMMENT '1=完全退款（有效复购率排除）'
)
COMMENT '订单明细（状态展开+金额核对）'
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/dw_dwd.db/dwd_order_detail';

-- 拒绝记录（清洗时隔离的异常数据，人工/程序复审）
CREATE EXTERNAL TABLE IF NOT EXISTS dw_dwd.dwd_reject_record (
    reject_id     STRING,
    source_table  STRING,
    reject_reason STRING COMMENT '枚举：EMPTY_FIELD/DUPLICATE_EVENT/BAD_ENUM/BAD_AMOUNT/FUTURE_TIME',
    raw_payload   STRING,
    reject_time   TIMESTAMP
)
COMMENT '清洗拒绝记录'
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/dw_dwd.db/dwd_reject_record';