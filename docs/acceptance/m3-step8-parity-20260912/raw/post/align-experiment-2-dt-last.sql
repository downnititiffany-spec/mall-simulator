-- P2-03 假设二验证 · 实验 2：把静态分区列 dt 移到末尾（其余完全不变）——应写入成功
INSERT OVERWRITE TABLE dw_exp.t_align_bad PARTITION (dt)
SELECT CAST(1 AS BIGINT), CAST(2 AS BIGINT), CAST(3 AS BIGINT), CAST(4 AS BIGINT),
       CAST(5 AS INT), CAST(6 AS DECIMAL(18,2)), CAST(7 AS DECIMAL(18,2)), CAST(8 AS DECIMAL(18,2)),
       CAST('x' AS STRING), CAST(NULL AS TIMESTAMP), CAST('2026-09-01' AS STRING), CAST('c' AS STRING),
       CAST(NULL AS TIMESTAMP), CAST(9 AS DECIMAL(18,2)), CAST(10 AS DECIMAL(18,2)),
       CAST(11 AS DECIMAL(18,2)), CAST(12 AS DECIMAL(18,2)),
       CAST(1 AS INT), CAST(0 AS INT),
       CAST(111 AS BIGINT), CAST(222 AS BIGINT), CAST(333 AS BIGINT),
       CAST('20260901' AS STRING);

-- 落表后读回：验证 23 列逐列落位正确（user_key=111/product_key=222/category_key=333/dt=20260901）
SELECT 'EXPERIMENT2_OK' AS marker, user_key, product_key, category_key, dt, final_refunded_flag
FROM dw_exp.t_align_bad;

DROP TABLE IF EXISTS dw_exp.t_align_bad;
DROP DATABASE IF EXISTS dw_exp;
