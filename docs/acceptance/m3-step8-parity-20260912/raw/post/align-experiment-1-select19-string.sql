-- P2-03 假设二验证：INSERT 与 DDL 位置对齐（只读 DDL + 一次性实验表，不碰 dw_dwd/dwd_order_detail）
-- 结论目标：证明「静态分区未解析 ⇒ dt 仍占一个数据列位置」且「SELECT 第 19 项 = STRING ⇒ 报 user_key STRING→BIGINT」
CREATE DATABASE IF NOT EXISTS dw_exp COMMENT 'P2-03-m2 一次性实验库（可删）';

DROP TABLE IF EXISTS dw_exp.t_align_bad;
CREATE TABLE dw_exp.t_align_bad (
  order_id BIGINT, user_id BIGINT, product_id BIGINT, category_id BIGINT,
  quantity INT, unit_price DECIMAL(18,2), discount DECIMAL(18,2), amount DECIMAL(18,2),
  order_status STRING, order_time TIMESTAMP, order_date STRING, city_level STRING,
  paid_at TIMESTAMP, order_amount DECIMAL(18,2), paid_amount DECIMAL(18,2),
  refund_amount DECIMAL(18,2), net_paid_amount DECIMAL(18,2),
  final_paid_flag INT, final_refunded_flag INT,
  user_key BIGINT, product_key BIGINT, category_key BIGINT)
USING parquet PARTITIONED BY (dt STRING);

-- 实验 1：复刻现网 SELECT 的位置（dt 在第 19 项）——应报 CANNOT_SAFELY_CAST user_key
INSERT OVERWRITE TABLE dw_exp.t_align_bad PARTITION (dt)
SELECT CAST(1 AS BIGINT), CAST(2 AS BIGINT), CAST(3 AS BIGINT), CAST(4 AS BIGINT),
       CAST(5 AS INT), CAST(6 AS DECIMAL(18,2)), CAST(7 AS DECIMAL(18,2)), CAST(8 AS DECIMAL(18,2)),
       CAST('x' AS STRING), CAST(NULL AS TIMESTAMP), CAST('2026-09-01' AS STRING), CAST('c' AS STRING),
       CAST(NULL AS TIMESTAMP), CAST(9 AS DECIMAL(18,2)), CAST(10 AS DECIMAL(18,2)),
       CAST(11 AS DECIMAL(18,2)), CAST(12 AS DECIMAL(18,2)),
       CAST(1 AS INT), CAST(0 AS INT),
       CAST('20260901' AS STRING),
       CAST(111 AS BIGINT), CAST(222 AS BIGINT), CAST(333 AS BIGINT);
SELECT 'EXPERIMENT1_SELECT19_IS_STRING' AS marker, COUNT(*) AS rows_written FROM dw_exp.t_align_bad;
