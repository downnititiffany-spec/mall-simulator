-- M3 rehearsal: independent read-only re-check of the isolated import (run 1 / run 2)
-- Source of truth: analytics_verify_m3_parity (isolated DB created for this rehearsal)
SET @s = 'S20260901_47';

SELECT '== 1. metric_snapshot row ==' AS section;
SELECT id, snapshot_id, runtime_profile_id, runtime_profile_version, business_time, pipeline_run_id,
       status, version, definition_version, source, active_flag, published_at, created_at
FROM metric_snapshot WHERE snapshot_id = @s;

SELECT '== 2. ADS wide-table row counts (per snapshot) ==' AS section;
SELECT 'ads_operation_overview_m' AS tbl, COUNT(*) AS rows_n FROM ads_operation_overview_m WHERE snapshot_id=@s
UNION ALL SELECT 'ads_sale_trend_m', COUNT(*) FROM ads_sale_trend_m WHERE snapshot_id=@s
UNION ALL SELECT 'ads_behavior_funnel_m', COUNT(*) FROM ads_behavior_funnel_m WHERE snapshot_id=@s
UNION ALL SELECT 'ads_active_trend_m', COUNT(*) FROM ads_active_trend_m WHERE snapshot_id=@s
UNION ALL SELECT 'ads_hot_product_m', COUNT(*) FROM ads_hot_product_m WHERE snapshot_id=@s
UNION ALL SELECT 'ads_product_conversion_m', COUNT(*) FROM ads_product_conversion_m WHERE snapshot_id=@s
UNION ALL SELECT 'ads_user_profile_m', COUNT(*) FROM ads_user_profile_m WHERE snapshot_id=@s
UNION ALL SELECT 'ads_data_quality_m', COUNT(*) FROM ads_data_quality_m WHERE snapshot_id=@s
UNION ALL SELECT 'TOTAL_8_TABLES', (SELECT COUNT(*) FROM ads_operation_overview_m WHERE snapshot_id=@s)
   + (SELECT COUNT(*) FROM ads_sale_trend_m WHERE snapshot_id=@s)
   + (SELECT COUNT(*) FROM ads_behavior_funnel_m WHERE snapshot_id=@s)
   + (SELECT COUNT(*) FROM ads_active_trend_m WHERE snapshot_id=@s)
   + (SELECT COUNT(*) FROM ads_hot_product_m WHERE snapshot_id=@s)
   + (SELECT COUNT(*) FROM ads_product_conversion_m WHERE snapshot_id=@s)
   + (SELECT COUNT(*) FROM ads_user_profile_m WHERE snapshot_id=@s)
   + (SELECT COUNT(*) FROM ads_data_quality_m WHERE snapshot_id=@s);

SELECT '== 3. key metric values (original text) ==' AS section;
SELECT snapshot_id, metric_code, metric_value, unit, period, definition_version
FROM metric_value WHERE snapshot_id=@s ORDER BY metric_code;

SELECT '== 4. key business indicators ==' AS section;
SELECT snapshot_id, dt, pv, uv, dau, order_count, sale_amount AS gmv, net_sale_amount,
       avg_order_value, refund_rate, full_refund_rate
FROM ads_operation_overview_m WHERE snapshot_id=@s;

SELECT '== 5. funnel (buy_rate source) ==' AS section;
SELECT snapshot_id, dt, stage, user_count, conversion_rate, overall_buy_rate
FROM ads_behavior_funnel_m WHERE snapshot_id=@s ORDER BY stage;

SELECT '== 6. duplicate-key probe: same snapshot twice? ==' AS section;
SELECT snapshot_id, COUNT(*) AS snapshot_rows_in_metric_value FROM metric_value GROUP BY snapshot_id;
SELECT COUNT(*) AS duplicate_grain_rows FROM (
  SELECT snapshot_id, metric_code, period, COUNT(*) c FROM metric_value
  GROUP BY snapshot_id, metric_code, period HAVING c > 1) x;
