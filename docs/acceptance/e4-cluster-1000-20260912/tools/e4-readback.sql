-- E4 独立回读（不经作业自报）：本地 Spark 客户端 → 集群 thrift metastore 9083 → HDFS 真读
-- 用途：对 odl/p2-01 的 ODS 分行数、pub 的正式 ADS 分区行数做**独立**核对
SHOW TABLES IN dw_ods;
SHOW TABLES IN dw_dwd;
SHOW TABLES IN dw_dim;
SHOW TABLES IN dw_dws;
SHOW TABLES IN dw_ads;
SELECT 'ODS_behavior' AS tbl, count(*) AS c FROM dw_ods.ods_behavior_event WHERE dt='20260901';
SELECT 'ODS_product'  AS tbl, count(*) AS c FROM dw_ods.ods_product_event  WHERE dt='20260901';
SELECT 'ODS_trade'    AS tbl, count(*) AS c FROM dw_ods.ods_trade_event    WHERE dt='20260901';
SELECT 'ODS_user'     AS tbl, count(*) AS c FROM dw_ods.ods_user_event     WHERE dt='20260901';
SELECT 'DIM_user'     AS tbl, count(*) AS c FROM dw_dim.dim_user           WHERE dt='20260901';
SELECT 'DIM_product'  AS tbl, count(*) AS c FROM dw_dim.dim_product        WHERE dt='20260901';
SELECT 'DWS_funnel'   AS tbl, count(*) AS c FROM dw_dws.dws_behavior_funnel_day WHERE dt='20260901';
SELECT 'DWS_prod_beh' AS tbl, count(*) AS c FROM dw_dws.dws_product_behavior_day WHERE dt='20260901';
SELECT 'DWS_prod_sale' AS tbl, count(*) AS c FROM dw_dws.dws_product_sale_day WHERE dt='20260901';
SELECT 'DWS_region'   AS tbl, count(*) AS c FROM dw_dws.dws_region_sale_day WHERE dt='20260901';
SELECT 'ADS_active_trend'      AS tbl, count(*) AS c FROM dw_ads.ads_active_trend      WHERE dt='20260901';
SELECT 'ADS_behavior_funnel'   AS tbl, count(*) AS c FROM dw_ads.ads_behavior_funnel   WHERE dt='20260901';
SELECT 'ADS_data_quality'      AS tbl, count(*) AS c FROM dw_ads.ads_data_quality      WHERE dt='20260901';
SELECT 'ADS_hot_product'       AS tbl, count(*) AS c FROM dw_ads.ads_hot_product       WHERE dt='20260901';
SELECT 'ADS_operation_overview' AS tbl, count(*) AS c FROM dw_ads.ads_operation_overview WHERE dt='20260901';
SELECT 'ADS_product_conversion' AS tbl, count(*) AS c FROM dw_ads.ads_product_conversion WHERE dt='20260901';
SELECT 'ADS_sale_trend'        AS tbl, count(*) AS c FROM dw_ads.ads_sale_trend        WHERE dt='20260901';
SELECT 'ADS_user_profile'      AS tbl, count(*) AS c FROM dw_ads.ads_user_profile      WHERE dt='20260901';
SELECT 'ADS_FORMAL_TOTAL'      AS tbl, sum(c) AS c FROM (
  SELECT count(*) AS c FROM dw_ads.ads_active_trend      WHERE dt='20260901'
  UNION ALL SELECT count(*) FROM dw_ads.ads_behavior_funnel   WHERE dt='20260901'
  UNION ALL SELECT count(*) FROM dw_ads.ads_data_quality      WHERE dt='20260901'
  UNION ALL SELECT count(*) FROM dw_ads.ads_hot_product       WHERE dt='20260901'
  UNION ALL SELECT count(*) FROM dw_ads.ads_operation_overview WHERE dt='20260901'
  UNION ALL SELECT count(*) FROM dw_ads.ads_product_conversion WHERE dt='20260901'
  UNION ALL SELECT count(*) FROM dw_ads.ads_sale_trend        WHERE dt='20260901'
  UNION ALL SELECT count(*) FROM dw_ads.ads_user_profile      WHERE dt='20260901'
) t;
