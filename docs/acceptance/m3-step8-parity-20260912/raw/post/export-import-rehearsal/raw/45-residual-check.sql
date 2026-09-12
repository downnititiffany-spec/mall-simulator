-- M3 rehearsal: residual / half-written data probe after the three failure paths
-- A = S20260901_47FX (wrong export dir), B = S20260901_47RH (row-count mismatch), C = S20260901_47RC (illegal column)
SELECT '== A. metric_snapshot rows for failed attempts ==' AS section;
SELECT snapshot_id, status, active_flag, version, definition_version, LEFT(failure_reason,160) AS failure_reason_head
FROM metric_snapshot WHERE snapshot_id IN ('S20260901_47FX','S20260901_47RH','S20260901_47RC')
ORDER BY snapshot_id;

SELECT '== B. residual ADS rows for failed snapshot ids (expect 0 each) ==' AS section;
SELECT 'S20260901_47FX' AS sid, (SELECT COUNT(*) FROM ads_operation_overview_m WHERE snapshot_id='S20260901_47FX')
  + (SELECT COUNT(*) FROM ads_sale_trend_m WHERE snapshot_id='S20260901_47FX')
  + (SELECT COUNT(*) FROM ads_behavior_funnel_m WHERE snapshot_id='S20260901_47FX')
  + (SELECT COUNT(*) FROM ads_active_trend_m WHERE snapshot_id='S20260901_47FX')
  + (SELECT COUNT(*) FROM ads_hot_product_m WHERE snapshot_id='S20260901_47FX')
  + (SELECT COUNT(*) FROM ads_product_conversion_m WHERE snapshot_id='S20260901_47FX')
  + (SELECT COUNT(*) FROM ads_user_profile_m WHERE snapshot_id='S20260901_47FX')
  + (SELECT COUNT(*) FROM ads_data_quality_m WHERE snapshot_id='S20260901_47FX') AS residual_ads_rows
UNION ALL SELECT 'S20260901_47RH',
  (SELECT COUNT(*) FROM ads_operation_overview_m WHERE snapshot_id='S20260901_47RH')
  + (SELECT COUNT(*) FROM ads_sale_trend_m WHERE snapshot_id='S20260901_47RH')
  + (SELECT COUNT(*) FROM ads_behavior_funnel_m WHERE snapshot_id='S20260901_47RH')
  + (SELECT COUNT(*) FROM ads_active_trend_m WHERE snapshot_id='S20260901_47RH')
  + (SELECT COUNT(*) FROM ads_hot_product_m WHERE snapshot_id='S20260901_47RH')
  + (SELECT COUNT(*) FROM ads_product_conversion_m WHERE snapshot_id='S20260901_47RH')
  + (SELECT COUNT(*) FROM ads_user_profile_m WHERE snapshot_id='S20260901_47RH')
  + (SELECT COUNT(*) FROM ads_data_quality_m WHERE snapshot_id='S20260901_47RH')
UNION ALL SELECT 'S20260901_47RC',
  (SELECT COUNT(*) FROM ads_operation_overview_m WHERE snapshot_id='S20260901_47RC')
  + (SELECT COUNT(*) FROM ads_sale_trend_m WHERE snapshot_id='S20260901_47RC')
  + (SELECT COUNT(*) FROM ads_behavior_funnel_m WHERE snapshot_id='S20260901_47RC')
  + (SELECT COUNT(*) FROM ads_active_trend_m WHERE snapshot_id='S20260901_47RC')
  + (SELECT COUNT(*) FROM ads_hot_product_m WHERE snapshot_id='S20260901_47RC')
  + (SELECT COUNT(*) FROM ads_product_conversion_m WHERE snapshot_id='S20260901_47RC')
  + (SELECT COUNT(*) FROM ads_user_profile_m WHERE snapshot_id='S20260901_47RC')
  + (SELECT COUNT(*) FROM ads_data_quality_m WHERE snapshot_id='S20260901_47RC');

SELECT '== C. residual metric_value rows for failed snapshot ids (expect 0 each) ==' AS section;
SELECT snapshot_id, COUNT(*) AS metric_value_rows FROM metric_value
WHERE snapshot_id IN ('S20260901_47FX','S20260901_47RH','S20260901_47RC')
GROUP BY snapshot_id;

SELECT '== D. ACTIVE pointer after all failure paths (must still be S20260901_47) ==' AS section;
SELECT snapshot_id, status, active_flag, version, published_at FROM metric_snapshot WHERE active_flag = 1;

SELECT '== E. ACTIVE snapshot data intact? ==' AS section;
SELECT (SELECT COUNT(*) FROM metric_value WHERE snapshot_id='S20260901_47') AS metric_values,
       (SELECT metric_value FROM metric_value WHERE snapshot_id='S20260901_47' AND metric_code='gmv') AS gmv,
       (SELECT metric_value FROM metric_value WHERE snapshot_id='S20260901_47' AND metric_code='paid_order_cnt') AS paid_order_cnt,
       (SELECT uv FROM ads_operation_overview_m WHERE snapshot_id='S20260901_47') AS uv,
       (SELECT COUNT(*) FROM ads_hot_product_m WHERE snapshot_id='S20260901_47') AS hot_product_rows;

SELECT '== F. whole-DB snapshot inventory (all rows in the isolated DB) ==' AS section;
SELECT snapshot_id, status, active_flag, version FROM metric_snapshot ORDER BY id;
SELECT COUNT(*) AS total_metric_value_rows FROM metric_value;
