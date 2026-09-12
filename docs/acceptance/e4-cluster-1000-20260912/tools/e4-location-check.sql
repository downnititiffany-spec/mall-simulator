-- E4 修复轮 §9 缺口关闭：8 张正式 ADS 表的 dt 分区 LOCATION **逐条**独立实测
-- 手段：本地 spark-sql 只读客户端 → 集群 thrift://node01:9083 → DESCRIBE FORMATTED 读分区 SD location
-- 判据：Location 必须逐条 = hdfs://node01:8020/graduation/warehouse/dw_ads.db/<t>__staging/snapshot_id=S20260901E4/dt=20260901
DESCRIBE FORMATTED dw_ads.ads_operation_overview PARTITION (dt='20260901');
DESCRIBE FORMATTED dw_ads.ads_sale_trend         PARTITION (dt='20260901');
DESCRIBE FORMATTED dw_ads.ads_behavior_funnel    PARTITION (dt='20260901');
DESCRIBE FORMATTED dw_ads.ads_active_trend       PARTITION (dt='20260901');
DESCRIBE FORMATTED dw_ads.ads_hot_product        PARTITION (dt='20260901');
DESCRIBE FORMATTED dw_ads.ads_product_conversion PARTITION (dt='20260901');
DESCRIBE FORMATTED dw_ads.ads_user_profile       PARTITION (dt='20260901');
DESCRIBE FORMATTED dw_ads.ads_data_quality       PARTITION (dt='20260901');
