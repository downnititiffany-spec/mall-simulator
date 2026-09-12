-- ADS 暂存表按 snapshot_id / dt 分布  2026-09-12 09:35:15
SELECT 'dw_ads.ads_active_trend__staging' AS staging_table, snapshot_id, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_active_trend__staging GROUP BY snapshot_id, dt ORDER BY snapshot_id, dt;
SELECT 'dw_ads.ads_behavior_funnel__staging' AS staging_table, snapshot_id, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_behavior_funnel__staging GROUP BY snapshot_id, dt ORDER BY snapshot_id, dt;
SELECT 'dw_ads.ads_data_quality__staging' AS staging_table, snapshot_id, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_data_quality__staging GROUP BY snapshot_id, dt ORDER BY snapshot_id, dt;
SELECT 'dw_ads.ads_hot_product__staging' AS staging_table, snapshot_id, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_hot_product__staging GROUP BY snapshot_id, dt ORDER BY snapshot_id, dt;
SELECT 'dw_ads.ads_operation_overview__staging' AS staging_table, snapshot_id, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_operation_overview__staging GROUP BY snapshot_id, dt ORDER BY snapshot_id, dt;
SELECT 'dw_ads.ads_product_conversion__staging' AS staging_table, snapshot_id, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_product_conversion__staging GROUP BY snapshot_id, dt ORDER BY snapshot_id, dt;
SELECT 'dw_ads.ads_sale_trend__staging' AS staging_table, snapshot_id, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_sale_trend__staging GROUP BY snapshot_id, dt ORDER BY snapshot_id, dt;
SELECT 'dw_ads.ads_user_profile__staging' AS staging_table, snapshot_id, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_user_profile__staging GROUP BY snapshot_id, dt ORDER BY snapshot_id, dt;
SELECT 'dw_ads.ads_active_trend' AS formal_table, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_active_trend GROUP BY dt ORDER BY dt;
SELECT 'dw_ads.ads_behavior_funnel' AS formal_table, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_behavior_funnel GROUP BY dt ORDER BY dt;
SELECT 'dw_ads.ads_data_quality' AS formal_table, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_data_quality GROUP BY dt ORDER BY dt;
SELECT 'dw_ads.ads_hot_product' AS formal_table, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_hot_product GROUP BY dt ORDER BY dt;
SELECT 'dw_ads.ads_operation_overview' AS formal_table, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_operation_overview GROUP BY dt ORDER BY dt;
SELECT 'dw_ads.ads_product_conversion' AS formal_table, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_product_conversion GROUP BY dt ORDER BY dt;
SELECT 'dw_ads.ads_sale_trend' AS formal_table, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_sale_trend GROUP BY dt ORDER BY dt;
SELECT 'dw_ads.ads_user_profile' AS formal_table, dt, COUNT(*) AS row_cnt FROM dw_ads.ads_user_profile GROUP BY dt ORDER BY dt;
