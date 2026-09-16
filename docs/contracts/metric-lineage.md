# 指标血缘表（输入事件 → DWD → DWS → ADS → MySQL → 页面/字典）

> 依据：指导书 V2.0 §24.2（"为每个指标建立输入事件→DWD 字段→DWS 字段→ADS 字段→MySQL 字段的 lineage 表"）、
> §13.1 指标字典、§18.2 页面模块、§18.5 一致性验收。
> 权威口径来源：`analytics_meta.metric_definition`（字典，16 行）+ `spark-jobs/.../sql/AdsSql.scala`（计算）。
> 本表是 R7-4 的交付物之一；**页面标签、字典公式、ADS 列、MySQL 列、论文公式必须与本表逐行一致**。

## 1. 全链路血缘（已落地的 12 个指标）

图例：`DWD/DWS/ADS` 为 Hive 表（库前缀 `dw_dwd` / `dw_dws` / `dw_ads`），
`MySQL` 为 `analytics_metric.ads_*_m`（宽表）与 `metric_value`（指标值），
`metric_value` 只写字典码，`period = day:YYYY-MM-DD`。

| # | 指标码 | 指标名 | 输入事件 / 源 | DWD 字段 | DWS 字段 | ADS 字段 | MySQL 字段 | 字典版本 | 页面 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `pv` | 浏览量 | `behavior` 事件（`behavior_type='view'`） | `dwd_user_behavior_detail.behavior_type` | —（直接取 DWD） | `ads_operation_overview.pv` | `ads_operation_overview_m.pv` + `metric_value(pv)` | v1 | 运营总览、用户行为 |
| 2 | `uv` | 浏览用户数 | `behavior` 事件 | `dwd_user_behavior_detail.user_id`（仅 view） | — | `ads_operation_overview.uv` | `ads_operation_overview_m.uv` + `metric_value(uv)` | v1 | 运营总览 |
| 3 | `dau` | 日活跃用户数 | 全部有效行为事件 | `dwd_user_behavior_detail.user_id` | — | `ads_operation_overview.dau`、`ads_active_trend.dau` | `ads_operation_overview_m.dau`、`ads_active_trend_m.dau` + `metric_value(dau)` | v1 | 运营总览、用户行为 |
| 4 | `paid_order_cnt` | 支付订单数 | `order`/`payment` 事件 → `OrderTradeCompiler` | `dwd_order_detail.final_paid_flag`、`order_id` | `dws_trade_day.order_count` | `ads_operation_overview.order_count` | `ads_operation_overview_m.order_count` + `metric_value(paid_order_cnt)` | v1 | 运营总览、销售分析 |
| 5 | `gmv` | 销售额(GMV) | `payment` 事件（`paid_amount`） | `dwd_order_detail.paid_amount` | `dws_trade_day.sale_amount` | `ads_operation_overview.sale_amount` | `ads_operation_overview_m.sale_amount` + `metric_value(gmv)` | v1 | 运营总览、销售分析 |
| 6 | `net_sale` | 净销售额 | `payment` + `refund_completed` 事件 | `dwd_order_detail.paid_amount`、`refund_amount` | `dws_trade_day.net_sale_amount` | `ads_operation_overview.net_sale_amount`、`ads_sale_trend.net_sale_amount`（S3-02 补，同源同口径） | `ads_operation_overview_m.net_sale_amount`、`ads_sale_trend_m.net_sale_amount` + `metric_value(net_sale)` | v1 | 运营总览、销售分析 |
| 7 | `avg_order_value` | 客单价 | 同 4/5 | `final_paid_flag`、`paid_amount` | `dws_trade_day.avg_order_value` | `ads_operation_overview.avg_order_value`、`ads_sale_trend.avg_order_value` | `ads_operation_overview_m.avg_order_value`、`ads_sale_trend_m.avg_order_value` + `metric_value(avg_order_value)` | v1 | 运营总览、销售分析 |
| 8 | `refund_rate` | 退款率 | `refund_completed` 事件（按 `refund_id` 去重） | `dwd_order_detail.final_paid_flag=1 AND refund_amount>0` | `dws_trade_day.order_count`（分母） | `ads_operation_overview.refund_rate` | `ads_operation_overview_m.refund_rate` + `metric_value(refund_rate)` | **v2** | 运营总览、销售分析 |
| 9 | `full_refund_rate` | 全额退款率 | `refund_completed` 事件（全额） | `dwd_order_detail.final_refunded_flag=1` | 同上（分母） | `ads_operation_overview.full_refund_rate` | `ads_operation_overview_m.full_refund_rate` + `metric_value(full_refund_rate)` | v1 | 销售分析 |
| 10 | `buy_rate` | 购买转化率 | `behavior` + `order`/`payment` 事件 | `user_id`、`final_paid_flag` | `dws_behavior_funnel_day.{view_users, pay_users}` | `ads_behavior_funnel.overall_buy_rate`、`pay.conversion_rate` | `ads_behavior_funnel_m.overall_buy_rate`/`conversion_rate` + `metric_value(buy_rate)` | v1 | 用户行为（漏斗） |
| 11 | `product_heat` | 商品热度 | `behavior` 事件（view/favorite/cart_add）+ `order` 事件 | `product_id`、`behavior_type` | `dws_product_behavior_day.{pv, fav, cart, buy}` | `ads_hot_product.heat_score`（`1·ln(1+PV)+2·ln(1+收藏)+3·ln(1+加购)+5·ln(1+支付件数)`） | `ads_hot_product_m.heat_score`（不进 `metric_value`） | v1 | 商品分析 |
| 12 | `user_value_level` | 用户价值等级 | `order`/`payment` 事件 + `behavior` 事件（偏好分类） | `dwd_order_detail`、`dwd_user_behavior_detail.category_id`、`event_date` | `dws_user_trade_period.{last_buy_date, order_count, sale_amount}`（`NTILE(5)` 近似五分位） | `ads_user_profile.value_group`（八类） | `ads_user_profile_m.value_group`（不进 `metric_value`） | v1 | 用户分群 |

补充血缘（非字典码，但页面/运维使用）：

| 指标 | 输入 | DWD | DWS | ADS | MySQL | 页面 |
|---|---|---|---|---|---|---|
| 行为构成 / 活跃趋势 | `behavior` 事件 | `dwd_user_behavior_detail.{behavior_type, user_id, dt}` | — | `ads_active_trend.behavior_count` | `ads_active_trend_m.behavior_count` | 用户行为 |
| 漏斗各阶 | 见 #10 | `dwd_user_behavior_detail`、`dwd_order_detail` | `dws_behavior_funnel_day` | `ads_behavior_funnel.{stage, user_count, conversion_rate}` | `ads_behavior_funnel_m` | 用户行为 |
| 商品转化 | 见 #11 + 商品销售 | `product_id` | `dws_product_behavior_day.uv`、`dws_product_sale_day.buyer_count` | `ads_product_conversion.{pv_users, buy_users, conversion_rate}` | `ads_product_conversion_m` | 商品分析 |
| 质量大盘 | ODS/DWD 统计 | `dwd_order_detail`、`dwd_user_behavior_detail`、`dwd_reject_record` | — | `ads_data_quality.*`（4 规则） | `ads_data_quality_m` | 运维中心 |
| 发布血缘（§16） | 一次 `pipeline_run` | — | — | Hive 正式分区 `snapshot_id` | `metric_snapshot.pipeline_run_id` | 运维中心 |

## 2. 字典存在但**尚无 ADS 承载**的指标（如实登记，不得当成已实现）

| 指标码 | 指标名 | 字典公式 | 现状 | 原因 / 归属 |
|---|---|---|---|---|
| `cart_rate` | 加购率 | 加购用户数/浏览用户数 | **未落地**：ADS/MySQL/`metric_value` 均无该码 | 需在 DWS 漏斗增加 `cart_users` 并扩 `ads_behavior_funnel`，R8/R9 或论文口径裁剪时处理 |
| `repeat_rate` | 复购率(有效) | 支付订单数≥2 的用户数/支付用户数 | **未落地** | DWS `dws_user_trade_period` 已有 `order_count`，缺 ADS 列与字典到页面的通路 |
| `stock_days` | 库存覆盖天数 | 可售库存/日均销量 | **未落地** | 库存不在事件流内（商城库存表属商城侧责任，§84 责任边界），分析平台不越界取数 |
| `stock_shortage_rate` | 缺货率 | 缺货商品数/在售商品数 | **未落地** | 同上 |

> 结论：字典 16 行 = 12 行已全链路落地 + 4 行明确未落地。
> 页面不得展示这 4 个码的数值（没有来源就不显示），AI 语义目录（R8）登记时也必须排除它们。

## 3. 一致性校验锚点（§18.5，黄金 55 行夹具 / 快照 `S20260901_24`）

| 指标码 | Hive ADS 实读 | MySQL `metric_value` | REST API | 页面 DOM | 论文公式 |
|---|---|---|---|---|---|
| `gmv` | 2042.00 | 2042.00 | 2042.00 | 待 R7-4 DOM 断言 | `sum(paid_amount)` |
| `paid_order_cnt` | 5 | 5 | 5 | 待 R7-4 DOM 断言 | `count(distinct order_id where final_paid_flag=1)` |
| `pv` | 7 | 7 | 7 | 待 R7-4 DOM 断言 | `count(view 行为事件)` |
| `refund_rate` | 0.6000 | 0.6000 | 0.6000 | 待 R7-4 DOM 断言 | 有已完成退款的订单数/支付订单数 |

> AI `EvidencePackage` 一列属 R8 交付，R7-4 阶段登记为待办；五处一致的完整断言在 R9 验收时闭环。
