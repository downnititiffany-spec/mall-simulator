-- =====================================================================
-- DWD 明细数据层（§6.3）
-- 清洗：时间统一、枚举映射、空值过滤、event_id 去重、维度补充；
-- 异常数据进入 dwd_reject_record，不混入正式明细。
-- 金额 DECIMAL(18,2)；event_time 决定指标归属日。
-- 库名：${WAREHOUSE_PREFIX}_dwd，缺省源 A 用 dw（beeline --hivevar WAREHOUSE_PREFIX=dw -f 01-dwd.sql）；规则见 contract-specs/specs/warehouse-namespace.v1.json
-- =====================================================================
CREATE DATABASE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dwd COMMENT 'DWD 明细数据层';

-- 用户行为明细（唯一事实：每个行为一行，event_id 去重后）
-- P2-03 代理键（裁决 D-083…D-092）：`*_key` 列为**新增**列，一律追加在列尾（INSERT 按位置对齐）。
--   两套 id 列语义必须显式区分，不得互相替代：
--     *_id  = 旧口径编码（IdCodec 剥字母转 BIGINT；含 -1 哨兵；**已摧毁前缀命名空间**，
--             U00000001 与 O00000001 都折叠成 1）——下游 DWS/ADS 现依赖它，未删（D-094）
--     *_key = 契约口径代理键（SHA-256 前 8 字节清符号位；空/缺 → NULL，**永不落 -1**；跨源不合并）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dwd.dwd_user_behavior_detail (
    behavior_id   STRING  COMMENT '= event_id',
    user_id       BIGINT  COMMENT '旧口径编码（含哨兵，见文件头 *_id/*_key 语义说明）',
    product_id    BIGINT  COMMENT '旧口径编码（含哨兵，同上）',
    category_id   BIGINT  COMMENT '商品维表补充（旧口径，含 -1 哨兵）',
    behavior_type STRING  COMMENT 'view/favorite/cart_add/cart_remove/search',
    event_time    TIMESTAMP,
    event_date    STRING  COMMENT 'yyyy-MM-dd',
    event_hour    INT,
    city_level    STRING  COMMENT '用户维表补充',
    channel       STRING,
    session_id    STRING,
    source_batch_id BIGINT,
    user_key      BIGINT  COMMENT 'P2-03 代理键（user）：sha256(源编码|user|NORMALIZED_ID) 前 8 字节清符号位；空/缺=NULL',
    product_key   BIGINT  COMMENT 'P2-03 代理键（product）：同上；空/缺=NULL',
    category_key  BIGINT  COMMENT 'P2-03 代理键（category）：取自 dim_product.category_key；维表未命中=NULL'
)
COMMENT '用户行为明细（去重、标准枚举、维度补充）'
PARTITIONED BY (dt STRING COMMENT '业务日期 yyyyMMdd')
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dwd.db/dwd_user_behavior_detail';

-- 订单明细（状态展开：同 order_id 的事件按 event_time 重建最新状态）
-- 注意：**没有** `order_key`。契约实体枚举为 {user, product, category, brand, coupon}，无 `order`，
-- 按裁决 D-093 订单级身份一律不得套用代理键算法（登记为契约缺口，须走契约升版）。
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dwd.dwd_order_detail (
    order_id      BIGINT  COMMENT '旧口径编码（订单级**无**代理键，见文件头 D-093 说明）',
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
    order_amount  DECIMAL(18,2) COMMENT '订单优惠后应付总额（order_created.total_amount）',
    paid_amount   DECIMAL(18,2) COMMENT '实际成功支付金额（order_paid.amount，refund_id 去重后最新）',
    refund_amount DECIMAL(18,2) COMMENT '累计已完成退款金额（refund_completed 按 refund_id 去重汇总）',
    net_paid_amount DECIMAL(18,2) COMMENT '=paid_amount−refund_amount（净销售口径）',
    final_paid_flag INT COMMENT '1=有效支付（口径依据，§21.3）',
    final_refunded_flag INT COMMENT '1=完全退款（有效复购率排除）',
    user_key      BIGINT  COMMENT 'P2-03 代理键（user）；空/缺=NULL',
    product_key   BIGINT  COMMENT 'P2-03 代理键（product）；空/缺=NULL',
    category_key  BIGINT  COMMENT 'P2-03 代理键（category）：取自 dim_product.category_key；维表未命中=NULL'
)
COMMENT '订单明细（状态展开+金额核对，§11.3）'
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dwd.db/dwd_order_detail';

-- 拒绝记录（清洗时隔离的异常数据，人工/程序复审）
CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dwd.dwd_reject_record (
    reject_id     STRING,
    source_table  STRING,
    reject_reason STRING COMMENT '枚举：EMPTY_FIELD/DUPLICATE_EVENT/BAD_ENUM/BAD_AMOUNT/FUTURE_TIME',
    raw_payload   STRING,
    reject_time   TIMESTAMP
)
COMMENT '清洗拒绝记录'
PARTITIONED BY (dt STRING)
STORED AS PARQUET
LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dwd.db/dwd_reject_record';