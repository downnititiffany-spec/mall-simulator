SELECT 'TOTAL_ROWS' k, CAST(COUNT(*) AS STRING) v FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'product_key_notnull', CAST(COUNT(product_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'category_key_notnull', CAST(COUNT(category_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'brand_key_notnull', CAST(COUNT(brand_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'parent_category_key_notnull', CAST(COUNT(parent_category_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901'
UNION ALL SELECT 'distinct_product_key', CAST(COUNT(DISTINCT product_key) AS STRING) FROM dw_dim.dim_product WHERE dt='20260901';