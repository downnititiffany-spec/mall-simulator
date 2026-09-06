# Hive 四层数据仓库（阶段 5 — DDL 资产）

## 分层与职责（§6.1）

| 层 | 库名 | 作用 | 使用者 |
|---|---|---|---|
| ODS | `dw_ods` | 原始留存：Landing 之上外部表，只增加审计字段，按 dt/hour 分区 | 追溯、重放、Schema 演进 |
| DWD | `dw_dwd` | 统一业务明细：清洗、event_id 去重、维度补充、状态展开；异常进 `dwd_reject_record` | Spark 主题计算 |
| DWS | `dw_dws` | 主题宽表：用户/漏斗/商品/交易/用户周期/地区 | ADS 复用 |
| ADS | `dw_ads` | 应用指标：页面与 AI 查询直接使用，口径固定 | API、可视化、AI |

## 血缘（首批作业，与 spark-jobs 对应）

```text
Landing/events -> ods_behavior_event/ods_trade_event/ods_user_event/ods_product_event
   EventOdsLoadJob（odl）
   -> dwd_user_behavior_detail（bdw）
      -> dws_user_behavior_day / dws_behavior_funnel_day / dws_product_behavior_day（usw）
         -> ads_operation_overview / ads_active_trend / ads_hot_product / ads_product_conversion
            / ads_behavior_funnel（fna, ods 侧独立依赖 dws_behavior_funnel_day）
   -> dwd_order_detail（tdw）-> dws_trade_day / dws_product_sale_day / dws_region_sale_day
      / dws_user_trade_period -> ads_sale_trend / ads_category_sale / ads_region_sale / ads_user_profile
```

## 关键口径（与 docs/contracts/metric-dictionary.md 一致）

* 金额 `DECIMAL(18,2)`；`event_time` 决定归属日（dt）；`ingest_time` 只用于延迟统计。
* 有效支付口径：`final_paid_flag=1`（dwd_order_detail）；退款口径：`final_refunded_flag=1`。
* 漏斗默认"宽松用户口径"（view/intent/order/pay 各自独立去重计数）；会话顺序漏斗为增强实验。
* 分母为 0 的比率返回 NULL（页面显示"无可计算数据"），禁止 0% 或无穷。
* ADS 字段英文命名 + 中文语义登记在 `dw_dim.dim_metric` 与平台语义层。

## 与 MySQL 指标库的关系（§3.4）

Hive ADS 是权威历史与复杂分析的来源；MySQL 指标库只承载已发布的高频 ADS 快照
（阶段 6 `MetricPublisher` 同步），**不是第五层数仓**。

## 存储设定（§6.7）

* 全部 Parquet + Snappy；按日分区 dt=yyyyMMdd（hour 仅 ODS 保留）。
* 小维表（dim_*）spark-submit 时广播连接；禁止无条件广播大事实表。
* 每日任务完成后合并过小文件（repartition/coalesce，§7.8）。
* 表清单与 §6.2-§6.5 完全对应：ODS 4 + DWD 3 + DIM 5 + DWS 7 + ADS 10。