SELECT '### formal dt=20260903 (应为空：质量失败未发布)' AS s;
SELECT dt, COUNT(*) AS rows FROM dw_ads.ads_operation_overview WHERE dt='20260903' GROUP BY dt;
SELECT '### formal dt=20260901 (黄金数据应完好)' AS s;
SELECT dt, pv, uv, dau, order_count, sale_amount, refund_rate FROM dw_ads.ads_operation_overview WHERE dt='20260901';
SELECT '### staging 快照分区（失败运行的隔离证据）' AS s;
SELECT snapshot_id, dt, COUNT(*) AS rows FROM dw_ads.ads_operation_overview__staging GROUP BY snapshot_id, dt;
SELECT '### dws_trade_day dt=20260903 (失败运行中间层，隔离业务日期)' AS s;
SELECT dt, order_count, sale_amount, refund_amount FROM dw_dws.dws_trade_day WHERE dt IN ('20260901','20260903') ORDER BY dt;
