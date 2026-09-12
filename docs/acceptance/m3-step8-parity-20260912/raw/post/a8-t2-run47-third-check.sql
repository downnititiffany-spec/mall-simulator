-- A8 T2 第三次复核（run 47 产物）+ 本地产物存在性
SELECT 'A8T2_TOTAL_ROWS' AS k, CAST(COUNT(*) AS STRING) AS v FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'A8T2_product_key_notnull', CAST(COUNT(product_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'A8T2_category_key_notnull', CAST(COUNT(category_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'A8T2_brand_key_notnull', CAST(COUNT(brand_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'A8T2_parent_category_key_notnull', CAST(COUNT(parent_category_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'A8T2_distinct_product_key', CAST(COUNT(DISTINCT product_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'A8T2_distinct_parent_category_key', CAST(COUNT(DISTINCT parent_category_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901';

-- run 47 DWD 明细业务日切片（仅 20260901 分区，证明 tdw 真实落数）
SELECT 'DWD_ORDER_DETAIL_dt20260901_rows' AS k, CAST(COUNT(*) AS STRING) AS v FROM dw_dwd.dwd_order_detail WHERE dt='20260901'
UNION ALL SELECT 'DWD_ORDER_DETAIL_dt20260901_user_key_notnull', CAST(COUNT(user_key) AS STRING) FROM dw_dwd.dwd_order_detail WHERE dt='20260901'
UNION ALL SELECT 'DWD_ORDER_DETAIL_dt20260901_product_key_notnull', CAST(COUNT(product_key) AS STRING) FROM dw_dwd.dwd_order_detail WHERE dt='20260901'
UNION ALL SELECT 'DWD_ORDER_DETAIL_dt20260901_category_key_notnull', CAST(COUNT(category_key) AS STRING) FROM dw_dwd.dwd_order_detail WHERE dt='20260901'
UNION ALL SELECT 'DWD_USER_BEHAVIOR_dt20260901_rows', CAST(COUNT(*) AS STRING) FROM dw_dwd.dwd_user_behavior_detail WHERE dt='20260901'
UNION ALL SELECT 'DWD_USER_BEHAVIOR_dt20260901_user_key_notnull', CAST(COUNT(user_key) AS STRING) FROM dw_dwd.dwd_user_behavior_detail WHERE dt='20260901';
