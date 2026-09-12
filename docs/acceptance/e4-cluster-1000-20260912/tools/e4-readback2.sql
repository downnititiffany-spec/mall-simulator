-- E4 独立回读（补充批）：DWD 三表 + DWS 其余三表
SELECT 'DWD_order_detail'   AS tbl, count(*) AS c FROM dw_dwd.dwd_order_detail        WHERE dt='20260901';
SELECT 'DWD_user_behavior'  AS tbl, count(*) AS c FROM dw_dwd.dwd_user_behavior_detail WHERE dt='20260901';
SELECT 'DWD_reject'         AS tbl, count(*) AS c FROM dw_dwd.dwd_reject_record       WHERE dt='20260901';
SELECT 'DWS_trade_day'      AS tbl, count(*) AS c FROM dw_dws.dws_trade_day           WHERE dt='20260901';
SELECT 'DWS_user_behavior'  AS tbl, count(*) AS c FROM dw_dws.dws_user_behavior_day    WHERE dt='20260901';
SELECT 'DWS_user_trade_period' AS tbl, count(*) AS c FROM dw_dws.dws_user_trade_period WHERE dt='20260901';
SELECT 'DWD_TOTAL' AS tbl, sum(c) AS c FROM (
  SELECT count(*) AS c FROM dw_dwd.dwd_order_detail        WHERE dt='20260901'
  UNION ALL SELECT count(*) FROM dw_dwd.dwd_user_behavior_detail WHERE dt='20260901'
  UNION ALL SELECT count(*) FROM dw_dwd.dwd_reject_record       WHERE dt='20260901'
) t;
