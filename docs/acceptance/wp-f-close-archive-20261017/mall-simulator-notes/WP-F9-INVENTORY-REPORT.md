# WP-F9 规约交付物：全量指标扩展评估——盘点与三层对账报告

> **报告版本**：V1.0  
> **盘点日期**：2026-10-17  
> **执行环境**：Linux / 无数据库连接 / 纯只读源码与文档盘点  
> **依据规约**：`mall-simulator-notes/WP-F9-INVENTORY.md`  
> **基线分支**：`codex/cloud-v3-development`  
> **边界声明**：本报告为纯只读评估制品。不运行任何命令、不连接数据库（不碰 3306/Docker/DeepSeek）、不修改代码仓库内任何文件；不作「项目已全量完成」结论，不承诺指标必定落地。既有裁决（如 WP-F7d 品类退款分摊属上游责任）严格维持，不在 ADS 层重复建议修改。

---

## 一、已发布侧全量枚举（Published Baseline）

已发布基线以发布门（WP-F7c 验收报告）、代码白名单（`MetricAdsCatalog.java`）、发布器（`MetricPublisher.java`）、校验器（`MetricPublishValidator.java`）及 HTTP API 契约（`WP-N2B-CONTRACTS.md`、`analysis-viewmodel-r7-4.md`）为权威事实依据。

### 1.1 10 张已发布 ADS 表枚举

代码锚点：
- 目录定义：`analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsCatalog.java:23-63`
- 发布门校验：`analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/publish/MetricPublishValidator.java:16-17`
- 导出与行数登记：`mall-simulator-notes/WP-N2B-CONTRACTS.md:14-25`、`mall-simulator-notes/WP-F7c-REPORT.md:38-52`

| 序号 | 表名 | 统计粒度（主键/业务主键） | 黄金验证快照行数 | 核心业务字段及口径出处 | 代码锚点 |
|---|---|---|---|---|---|
| 1 | `ads_operation_overview_m` | `[snapshot_id, dt]`（日级商城整体） | 1 行 | `pv`, `uv`, `dau`, `order_count`, `sale_amount`, `net_sale_amount`, `avg_order_value`, `refund_rate`, `full_refund_rate`, `repeat_rate`, `fav_count`, `cart_add_count`, `pay_user_count`, `refund_amount` | `MetricAdsCatalog.java:24-27`<br>`AdsSql.scala:11-37` |
| 2 | `ads_sale_trend_m` | `[snapshot_id, dt]`（日级时序趋势） | 30 行（30天连续滑动窗口） | `order_count`, `sale_amount`, `net_sale_amount`, `paid_user_count`, `refund_amount` | `MetricAdsCatalog.java:28-31`<br>`AdsSql.scala:40-54` |
| 3 | `ads_behavior_funnel_m` | `[snapshot_id, dt, stage]`（全站日级4阶段漏斗） | 4 行（view/intent/order/pay） | `stage`, `user_count`, `step_conversion_rate`, `overall_buy_rate`, `overall_cart_rate` | `MetricAdsCatalog.java:32-35`<br>`AdsSql.scala:57-79`<br>`FunnelAdsJob.scala:67-115` |
| 4 | `ads_active_trend_m` | `[snapshot_id, dt]`（日级活跃用户时序） | 30 行（30天连续滑动窗口） | `dau`, `login_user_count` | `MetricAdsCatalog.java:36-39`<br>`AdsSql.scala:82-96` |
| 5 | `ads_hot_product_m` | `[snapshot_id, dt, rank_no]`（商品Top排行） | 20 行（Top 20商品） | `rank_no`, `product_id`, `product_name`, `category_id`, `category_name`, `sale_count`, `sale_amount`, `heat_score`, `conversion_rate` | `MetricAdsCatalog.java:40-43`<br>`AdsSql.scala:99-130` |
| 6 | `ads_product_conversion_m` | `[snapshot_id, dt, product_id]`（单品转化率） | 100 行（全量在售商品） | `product_id`, `product_name`, `detail_uv`, `order_uv`, `pay_uv`, `conversion_rate` | `MetricAdsCatalog.java:44-47`<br>`AdsSql.scala:133-157` |
| 7 | `ads_user_profile_m` | `[snapshot_id, dt, user_id]`（用户标签与RFM） | 500 行（全量活跃/画像用户） | `user_id`, `gender`, `age_group`, `city_level`, `recency_days`, `frequency_cnt`, `monetary_amount`, `r_score`, `f_score`, `m_score`, `rfm_type`, `rfm_segment`, `lifecycle_stage`, `first_category_preference`, `favorite_period`, `active_level`, `loss_risk` | `MetricAdsCatalog.java:48-51`<br>`AdsSql.scala:160-238` |
| 8 | `ads_data_quality_m` | `[snapshot_id, dt, rule_code]`（数仓质量规则核查） | 20 行（20项固定规则） | `rule_code`, `rule_name`, `target_table`, `check_status`, `error_count`, `actual_value`, `threshold_value`, `check_detail` | `MetricAdsCatalog.java:52-55`<br>`AdsQualityJob.scala:21-120` |
| 9 | `ads_category_sale_m` | `[snapshot_id, dt, category_id]`（品类销售汇总） | 6 行（6大主品类） | `category_id`, `category_name`, `parent_category_id`, `order_count`, `sale_amount`, `net_sale_amount`, `sale_share` | `MetricAdsCatalog.java:56-59`<br>`AdsSql.scala:241-267` |
| 10 | `ads_region_sale_m` | `[snapshot_id, dt, region]`（地区销售汇总） | 6 行（6大地理区域） | `region`, `order_count`, `sale_amount`, `net_sale_amount`, `pay_user_count` | `MetricAdsCatalog.java:60-63`<br>`AdsSql.scala:270-295` |

### 1.2 14 个 `metric_value` 标量指标枚举

代码与契约锚点：
- 指标提取与入库：`analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/publish/MetricPublisher.java:45-62, 282-304`
- 发布必填校验：`analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/publish/MetricPublishValidator.java:18-20, 30-38`
- 存储与查询实现：`analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MySqlMetricStore.java:80-136`
- 黄金测试对账验证：`mall-simulator-notes/WP-F7c-REPORT.md:46`（14/14 完全匹配）

| 序号 | metric_code | 指标中文名 | 所属源 ADS 表 | 所属源列 | 计算公式与计算层出处 | 发布与校验锚点 |
|---|---|---|---|---|---|---|
| 1 | `pv` | 浏览量 | `ads_operation_overview_m` | `pv` | `count(1) where behavior_type='view'`<br>`AdsSql.scala:14` | `MetricPublisher.java:47`<br>`MetricPublishValidator.java:18` |
| 2 | `uv` | 浏览用户数 | `ads_operation_overview_m` | `uv` | `count(distinct user_id) where behavior_type='view'`<br>`AdsSql.scala:15` | `MetricPublisher.java:48`<br>`MetricPublishValidator.java:18` |
| 3 | `dau` | 日活跃用户数 | `ads_operation_overview_m` | `dau` | `count(distinct user_id) where event_time=dt`<br>`AdsSql.scala:16` | `MetricPublisher.java:49`<br>`MetricPublishValidator.java:18` |
| 4 | `paid_order_cnt` | 支付订单数 | `ads_operation_overview_m` | `order_count` | `count(distinct order_id) where final_paid_flag=1`<br>`AdsSql.scala:17` | `MetricPublisher.java:50`<br>`MetricPublishValidator.java:18` |
| 5 | `gmv` | 成交总额(GMV) | `ads_operation_overview_m` | `sale_amount` | `sum(paid_amount) where final_paid_flag=1`<br>`AdsSql.scala:18` | `MetricPublisher.java:51`<br>`MetricPublishValidator.java:18` |
| 6 | `net_sale` | 净销售额 | `ads_operation_overview_m` | `net_sale_amount` | `sum(paid_amount) - sum(refund_amount)`<br>`AdsSql.scala:19` | `MetricPublisher.java:52`<br>`MetricPublishValidator.java:18` |
| 7 | `avg_order_value` | 客单价 | `ads_operation_overview_m` | `avg_order_value` | `sale_amount / order_count`<br>`AdsSql.scala:20` | `MetricPublisher.java:53`<br>`MetricPublishValidator.java:18` |
| 8 | `refund_rate` | 订单退款率 | `ads_operation_overview_m` | `refund_rate` | `refund_order_count / order_count`<br>`AdsSql.scala:21` | `MetricPublisher.java:54`<br>`MetricPublishValidator.java:18` |
| 9 | `full_refund_rate` | 全额退款率 | `ads_operation_overview_m` | `full_refund_rate` | `full_refund_order_count / order_count`<br>`AdsSql.scala:22` | `MetricPublisher.java:55`<br>`MetricPublishValidator.java:18` |
| 10 | `repeat_rate` | 有效复购率 | `ads_operation_overview_m` | `repeat_rate` | `count(users with paid>=2 and net>0) / count(users with paid>=1)`<br>`AdsSql.scala:23` | `MetricPublisher.java:56`<br>`MetricPublishValidator.java:18` |
| 11 | `fav_cnt` | 收藏次数 | `ads_operation_overview_m` | `fav_count` | `count(1) where behavior_type='favorite'`<br>`AdsSql.scala:24` | `MetricPublisher.java:57`<br>`MetricPublishValidator.java:18` |
| 12 | `cart_add_cnt` | 加购次数 | `ads_operation_overview_m` | `cart_add_count` | `count(1) where behavior_type='cart_add'`<br>`AdsSql.scala:25` | `MetricPublisher.java:58`<br>`MetricPublishValidator.java:18` |
| 13 | `buy_rate` | 全站购买转化率 | `ads_behavior_funnel_m` | `overall_buy_rate` | `pay_uv / view_uv` (全站整体)<br>`AdsSql.scala:72`<br>`FunnelAdsJob.scala:88` | `MetricPublisher.java:60`<br>`MetricPublishValidator.java:35` |
| 14 | `cart_rate` | 全站加购转化率 | `ads_behavior_funnel_m` | `overall_cart_rate` | `cart_uv / view_uv` (全站整体)<br>`AdsSql.scala:71`<br>`FunnelAdsJob.scala:87` | `MetricPublisher.java:61`<br>`MetricPublishValidator.java:36` |

### 1.3 读取与 API / ViewModel 侧已暴露分析视图映射

代码与契约锚点：
- 接口控制器：`analytics-server/platform-app/src/main/java/com/graduation/analytics/controller/AnalysisController.java:23-74`
- 业务编排服务：`analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/AnalysisService.java:31-155`
- RFM 计算服务：`analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/RfmService.java:20-95`
- 数据质量服务：`analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsReader.java:110-145`
- 视图模型契约：`docs/contracts/analysis-viewmodel-r7-4.md:1-120`（当前版本 v1.12）

| HTTP 端点 | ViewModel 顶层节点 | 映射已发布 ADS 表 / `metric_value` | 字段映射细节与行为契约 |
|---|---|---|---|
| `GET /api/v1/dashboards/overview` | `metrics` | `metric_value`（14项） | 聚合 14 项核心标量指标，包含单位、值、定义版本及快照 ID |
| | `salesTrend` | `ads_sale_trend_m` | 30日时序数组：`dt`, `orderCount`, `saleAmount`, `netSaleAmount` (v1.4加性), `paidUserCount` |
| | `activeTrend` | `ads_active_trend_m` | 30日时序数组：`dt`, `dau`, `loginUserCount` |
| | `quality` | `ads_data_quality_m` | 规则校验汇总对象：`summary`, `ruleVersions` (v1.6加性), `checks` 数组 |
| | `metricDictionary` | `metric_definition` | 随快照固定的系统指标元数据定义映射字典 |
| `GET /api/v1/analysis/sales` | `gmv`, `netSale`, `refundRate`, `fullRefundRate` | `metric_value` | 快照级汇总标量数值原样透传（不因日期筛选缩放） |
| | `trend` | `ads_sale_trend_m` | 日期筛选过滤区间内的数据点列表，含 `netSaleAmount` |
| | `byCategory` | `ads_category_sale_m` | 6大品类销售分布（`categoryName`, `saleAmount`, `netSaleAmount`, `saleShare`）(v1.12) |
| | `byRegion` | `ads_region_sale_m` | 6大地理区销售分布（`region`, `orderCount`, `saleAmount`, `netSaleAmount`）(v1.12) |
| `GET /api/v1/analysis/products` | `hot` | `ads_hot_product_m` | 真分页（`page`, `size`, `total`, `hasMore`）与稳定排序（`sort`）(v1.3/v1.5) Top商品列表 |
| | `conversion` | `ads_product_conversion_m` | 100个商品转化明细：`detailUv`, `orderUv`, `payUv`, `conversionRate` |
| `GET /api/v1/analysis/funnel` | `stages` | `ads_behavior_funnel_m` | 4个标准阶段漏斗：`view`, `intent`, `order`, `pay`，含步长转化率 |
| | `overallBuyRate` | `ads_behavior_funnel_m` | 全站总转化率（`overall_buy_rate`） |
| `GET /api/v1/analysis/users` | `rfmSegments` | `ads_user_profile_m` | 8类人群分布统计（人数、占比、均值），含 RFM 原值与窗口 (v1.1) |
| | `lifecycle` | `ads_user_profile_m` | 生命周期阶段占比分布（新客、成长期、成熟期、衰退期、流失期） |
| | `preference` | `ads_user_profile_m` | 用户第一品类偏好分布 |
| | `period` | `ads_user_profile_m` | 用户偏好活跃时段分布 |
| `GET /api/v1/analysis/rfm` | `rfmSegments`, `rfmMatrix` | `ads_user_profile_m` | RFM 三维评分矩阵分布与客户群聚类画像 |
| `GET /api/v1/metrics/overview` | 根数据数组 | `metric_value` | 14项标量指标原始列表 |
| `GET /api/v1/metrics/quality` | 根数据数组 | `ads_data_quality_m` | 20项数据质量规则执行明细（`ruleCode`, `checkStatus`, `errorCount` 等） |
| `POST /api/v1/reports` | 报告实例制品 | 10 张 ADS 表 + `metric_value` | 生成 6 种标准业务报告实例 JSON（D-060 契约） |

---

## 二、设计侧全量枚举（Design Specification）

### 2.1 实际通读文件清单

本盘点针对 `docs/design/`、`docs/guidance/` 及 `docs/contracts/` 下所有与指标、ADS、数仓和报表相关的文档进行了全量通读与核实：
1. `docs/design/项目设计文档 V3.0.md`（权威冻结文档：§9数仓架构、§10计算架构、§11指标体系、§12质量与发布门、§13AI问数与证据链、§14决策闭环与业务报告、§15前端可视化、§16接口规范、§17一致性与状态机）
2. `docs/guidance/项目完整实施指导书 V3.0.md`（权威冻结指导书：§5总体链路与数仓分层、§6-§8各阶段任务与完成标准、§10测试与验收、§11开发Backlog与待决项G-01~G-07、§12文档变更规则）
3. `docs/contracts/metric-dictionary.md`（唯一指标口径来源：definition_version v1，首批15项指标清单、对比/异常/效果评价公式）
4. `docs/contracts/analysis-viewmodel-r7-4.md`（分析接口ViewModel契约：v1.12，各端点字段映射、加性演进历史、日期过滤边界）
5. `docs/contracts/business-report-instance-api-draft.md`（业务报告实例API契约草案：D-060批准，6类模板定义、数据边界与持久化模型）
6. `docs/PROJECT_STATUS.md`（项目运行与治理状态：D-060、D-061、D-062等裁决，WP-N2A/B/C、WP-F1~WP-F8b登记记录）

### 2.2 设计要求的指标体系全量提取

#### A. 指标字典（`metric-dictionary.md` v1）15 项核心指标

出处：`docs/contracts/metric-dictionary.md:16-33`

| 编码 | 名称 | 字典设计口径 / 公式 | 粒度 | 来源分层要求 |
|---|---|---|---|---|
| `pv` | 浏览量 | `count(view 行为事件)` | 日/小时 | `dwd_user_behavior_detail` |
| `uv` | 浏览用户数 | `count(distinct user_id where behavior_type='view')` | 日 | `dwd_user_behavior_detail` |
| `dau` | 日活跃用户数 | `count(distinct user_id where 当日存在任一有效行为)` | 日 | `dwd_user_behavior_detail` |
| `fav_cnt` | 收藏次数 | `count(favorite 事件)` | 日 | `dwd_user_behavior_detail` |
| `cart_add_cnt` | 加购次数 | `count(cart_add 事件)` | 日 | `dwd_user_behavior_detail` |
| `cart_rate` | 加购率 | `加购用户数 ÷ 浏览用户数`（用户去重口径） | 日 | `dws_behavior_funnel_day` |
| `buy_rate` | 购买转化率 | `支付用户数 ÷ 浏览用户数`（用户去重口径） | 日 | `dws_behavior_funnel_day` |
| `paid_order_cnt` | 支付订单数 | `count(distinct order_id where final_paid_flag=1)` | 日 | `dws_trade_day` |
| `gmv` | 销售额(GMV) | `sum(paid_amount)`（有效支付订单） | 日/周/月 | `dws_trade_day` |
| `net_sale` | 净销售额 | `sum(paid_amount) − sum(refund_completed.amount)` | 日/周/月 | `dws_trade_day + refund` |
| `avg_order_value` | 客单价 | `GMV ÷ 支付订单数` | 日/周/月 | `dws_trade_day` |
| `refund_rate` | 退款率 | `退款订单数 ÷ 支付订单数`（按订单去重） | 日/周/月 | `dws_trade_day / 退款表` |
| `repeat_rate` | 复购率（有效） | `观察期内有效支付订单数≥2 的用户数 ÷ 观察期内支付用户数`（剔除全额退款） | 观察期（默认30天） | `dws_user_trade_period` |
| `product_heat` | 商品热度 | `1.0×ln(1+PV) + 2.0×ln(1+收藏) + 3.0×ln(1+加购) + 5.0×ln(1+支付件数)` | 日 | `dws_product_behavior_day` |
| `user_value_level` | 用户价值等级 | RFM分位数分层：R反向五分位/F、M正向五分位，对比中位数分为8类 | 用户×观察期 | `ads_user_profile` |

#### B. 设计文档 V3.0 业务专题扩展指标与算法要求

出处：`docs/design/项目设计文档 V3.0.md` §11–§14

1. **流量与转化专题**（§11.3 L442-446）：
   - 全站松散日级漏斗：`view -> intent -> order -> pay`（已实现）。
   - 渠道流量转化归因：按行为日志 `channel` 维度下钻转化率与跳出率（设计提及，未建 ADS）。
   - 会话级严格漏斗（Session Cohort Funnel）：按单次访问会话时间序严格匹配（指导书 L264 G-04 明文裁定延期）。
2. **用户与留存专题**（§11.4 L447-453）：
   - RFM 8类精细分群（`rfm_segment`，已实现）。
   - 用户留存率（`retention_rate`）：次日（D+1）、7日（D+7）、30日（D+30）活跃队列留存（设计概念明确，数仓未算）。
   - 复购率变体：支付复购率（`paid_repeat_rate`，含退款） vs 有效复购率（`repeat_rate`，剔除退款）（设计提及，Backlog F-36 跟踪）。
   - 流失风险预测（`loss_risk`）：基于未访问天数分级（已在用户画像表实现）。
3. **商品与供应链专题**（§11.5 L454-459）：
   - 商品热度榜（Top N 综合评分，已实现）。
   - 单品转化漏斗（商详UV -> 下单UV -> 支付UV，已实现）。
   - 库存周转天数（`stock_coverage`）与 ABC 分类：§11.5 L457 明确强调「缺库存流就显示缺少库存数据，不可从订单数虚构库存；无 cost 时不可计算毛利」。
   - 毛利与毛利率（`gross_profit`, `gross_margin`）：依赖采购成本价（设计约束同上）。
4. **统计异动与决策效果专题**（§11.6 L460-466、`metric-dictionary.md:38-42`）：
   - 统计异动检测：Z-Score（`|z| >= 2.5`）与 IQR 算法检测 GMV、退款率等异常波动（AI 服务层内存计算，未持久化 ADS）。
   - 决策前后效果评价：`improvementRate = (actual - baseline) / |baseline|`（AI 决策模块已实现）。
5. **数仓与发布模型**（§9.3 L327-338、§12.2 L476）：
   - 10 张 ADS 表与 20 条数据质量校验规则（已全量实现并发布）。
   - 维度表体系：`dim_user`, `dim_product`, `dim_date`, `dim_region`, `dim_metric`。
6. **业务报告实例体系**（`docs/contracts/business-report-instance-api-draft.md:12-21`，D-060 批准）：
   - 6 类业务报告实例模板：`OPS_DAILY`, `OPS_WEEKLY`, `ORDER_ANALYSIS`, `PRODUCT_ANOMALY`, `USER_OPERATIONS`, `DECISION_EFFECT`（后端已实现 JSON 实例生成，PDF/HTML 格式导出列为延期 Backlog）。

---

## 三、三层对账与缺口分析

### 3.1 三层对账方法与定义

- **第一层：设计要求层（Design）**：`docs/design/`、`docs/guidance/` 及 `docs/contracts/` 规定的指标、表与功能要求。
- **第二层：数仓计算层（Compute）**：Spark 离线批处理作业（`AdsSql.scala`、`DwsSql.scala`、`DwdSql.scala`、`FunnelAdsJob.scala`、`AdsQualityJob.scala` 等）是否已产生该数据。
- **第三层：发布与暴露层（Publish & Expose）**：是否已通过发布门写入 MySQL `ads_*_m` / `metric_value`，且是否通过 Spring Boot API（`AnalysisController.java` 等）和 ViewModel 暴露给前端或报告。

### 3.2 全量缺口对照表（F9-GAP-01 ~ F9-GAP-21）

| 缺口编号 | 事项名称 | 设计要求出处 | 数仓计算层出处 | 发布与暴露层出处 | 三层对账状态 | 缺口所属层 | 口径差异要点说明 |
|---|---|---|---|---|---|---|---|
| `F9-GAP-01` | `product_heat` 标量指标发布 | `metric-dictionary.md:31` | `AdsSql.scala:118`<br>(`heat_score`) | 未入 `metric_value`<br>暴露于单品分析 | 已算未入标量库 | 发布映射层 (粒度差异) | 字典列为15项基础指标之一；Spark 在 `ads_hot_product_m` 逐商品计算；`MetricPublisher` 仅发布全站日级标量，未汇总全站热度值 |
| `F9-GAP-02` | `user_value_level` 独立标量字段 | `metric-dictionary.md:32`<br>设计 V3.0 §11.4 | `AdsSql.scala:195-236`<br>(`rfm_segment`) | 已暴露为 RFM 分群<br>无独立字段列 | 功能已满足但形式不同 | 形式差异 (设计已覆盖) | 字典作为用户级分层；代码中由 `rfm_segment`（8大群）及 `RfmService` 高中低分组替代，业务价值已完全闭环 |
| `F9-GAP-03` | 用户留存率 (`retention_rate`) (次日/7日/30日) | 设计 V3.0 §11.4 L449 | 无 Spark 计算<br>(`DwsSql`/`AdsSql` 缺) | 未发布<br>未暴露 | 未算未发布 | 数仓计算层 (缺聚合作业) | 设计要求统计 D+1/D+7/D+30 队列留存；DWD 行为明细已具备历史时间戳，但数仓未开发 `dws_user_retention_day` 及 ADS 表 |
| `F9-GAP-04` | 支付复购率变体 (`paid_repeat_rate`) | 设计 V3.0 §11.4 L449<br>Backlog F-36 | `AdsSql.scala:23`<br>(仅算有效复购率) | `metric_value.repeat_rate`<br>(仅有效复购) | 已算基线，变体未算 | 变体暂缓 (已有默认口径) | 设计区分含退款的“支付复购率”与剔除全额退款的“有效复购率”；目前已发布“有效复购率”为基线，支付复购率作为 F-36 暂缓 |
| `F9-GAP-05` | 会话级严格转化漏斗与多维下钻 | 设计 V3.0 §11.3 L442<br>指导书 G-04 | `FunnelAdsJob.scala:67`<br>(仅算全站松散漏斗) | `ads_behavior_funnel_m`<br>(日级4阶段) | 已算松散，严格延期 | 设计裁定延期 (G-04) | 设计提出 Session 级严格时间序和渠道下钻；指导书 G-04 裁定首版仅支持日级松散转化漏斗，严格会话漏斗延期 |
| `F9-GAP-06` | 渠道流量与转化汇总表 | 设计 V3.0 §11.3 L444 | `dwd_user_behavior_detail`<br>(有 `channel` 列未汇总) | 未发布渠道 ADS 表<br>未暴露端点 | 源数据在 DWD，ADS 未算 | 数仓计算与发布层 | DWD 明细包含 `channel` 来源渠道，但未建立 `ads_channel_summary_m` 渠道聚合主题表，前端无法按渠道切片 |
| `F9-GAP-07` | 库存周转天数与 ABC 库存分类 | 设计 V3.0 §11.5 L457<br>指导书 L151 | 无此数据流<br>(ODS 缺库存流) | 未发布<br>未暴露 | 缺数据源未算 | 外部数据源依赖 (ODS) | 设计明文要求「缺库存流就显示缺少数据，不可从订单数虚构库存」；模拟器与生成器未产出库存进销存及盘点日志 |
| `F9-GAP-08` | 商品采购成本、毛利与毛利率 | 设计 V3.0 §11.5 L457<br>指导书 L151 | `dim_product` 缺 `cost_price`<br>无采购流水 | 未发布<br>未暴露 | 缺数据源未算 | 外部数据源依赖 (ODS) | 设计明文要求「无 cost 时不可计算毛利」；商品维表仅含销售定价，缺乏进货成本与供应链扣点数据 |
| `F9-GAP-09` | 统计异动检测结果 ADS 持久化 | 设计 V3.0 §11.6 L460<br>`metric-dictionary.md:38` | AI 模块 `EvidenceBuilder`<br>(内存动态计算) | 内存注入解释与报告<br>无独立 ADS 历史表 | 已在服务层实现，未入ADS | 数仓存储层 (可选优化) | 设计要求 Z-Score/IQR 异常检测；当前在 AI 服务层根据已发布快照动态计算，未在数仓落库为 `ads_metric_anomaly_m` 历史表 |
| `F9-GAP-10` | 指标维度表 `dim_metric` 双向同步 | 设计 V3.0 §9.2<br>PROJECT_STATUS L3260 | Spark 缺同步 Job | MySQL 有 `metric_definition`<br>Hive 缺失 | 元数据未双向同步 | 数仓元数据同步层 | MySQL 由 Flyway/代码维护指标元数据，但未通过 Spark 双向同步至 Hive `dim_metric`，数仓内部元数据未闭环 |
| `F9-GAP-11` | 品类销售表跨品类退款分摊 | 设计 V3.0 §9.3<br>WP-F7d 裁决记录 | `AdsSql.scala:241-267`<br>(按整单或未拆分扣减) | `ads_category_sale_m`<br>(已发布已暴露) | 既有裁决：ADS维持现状 | 上游责任 (DWD/Compiler) | WP-F7d 明确裁决：退款未拆分 SKU 属 DWD/编译器责任，ADS 层严禁作二次启发式分摊；维持现状，不予修改 |
| `F9-GAP-12` | 业务报告实例 HTML/PDF 格式导出 | 设计 V3.0 §14.4<br>D-060 契约草案 | `business-report-instance-api-draft.md` | 已发布 JSON 实例接口<br>未做二进制渲染 | JSON已就绪，文件导出暂缓 | 格式导出层 (Backlog) | D-060 批准了标准报告实例 JSON 结构，并明确将 HTML/PDF 物理渲染与下载列入后续 Backlog |
| `F9-GAP-13` | 品类与地区销售分析 | 设计 V3.0 §9.3<br>指导书 L150 | `AdsSql.scala:241-295`<br>(已计算2张表) | `ads_category_sale_m`<br>`ads_region_sale_m` (已暴露) | 已发布已暴露 | 无缺口 (A类) | WP-F7c/WP-N2B 验证通过，ViewModel v1.12 正式在 `/analysis/sales` 暴露 `byCategory` 与 `byRegion` |
| `F9-GAP-14` | 数据质量 20 项规则审计 | 设计 V3.0 §12.2 | `AdsQualityJob.scala:21-120`<br>(20项规则) | `ads_data_quality_m`<br>(已暴露于看板与接口) | 已发布已暴露 | 无缺口 (A类) | 覆盖 ODS/DWD/DWS/ADS，通过 `/metrics/quality` 及概览 quality 节点全量暴露 |
| `F9-GAP-15` | 用户画像与 RFM 聚合分群 | 设计 V3.0 §11.4 | `AdsSql.scala:160-238` | `ads_user_profile_m`<br>(已暴露于用户/RFM页) | 已发布已暴露 | 无缺口 (A类) | 涵盖 RFM 得分、8大群、生命周期、偏好类目与时段，ViewModel v1.1 已补充原值与窗口 |
| `F9-GAP-16` | 商品漏斗转化明细 | 设计 V3.0 §11.5 | `AdsSql.scala:133-157` | `ads_product_conversion_m`<br>(已暴露于商品页) | 已发布已暴露 | 无缺口 (A类) | 100个商品详情UV、下单UV、支付UV及单品转化率全量暴露 |
| `F9-GAP-17` | 热门商品 Top 排行榜 | 设计 V3.0 §11.5 | `AdsSql.scala:99-130` | `ads_hot_product_m`<br>(已暴露于商品页) | 已发布已暴露 | 无缺口 (A类) | Top 20 热度排行，支持真分页与多维度排序 (v1.3/v1.5) |
| `F9-GAP-18` | 销售与活跃趋势时序分析 | 设计 V3.0 §9.3 | `AdsSql.scala:40-54, 82-96` | `ads_sale_trend_m`<br>`ads_active_trend_m` (已暴露) | 已发布已暴露 | 无缺口 (A类) | 30日滑动趋势，支持日期窗口筛选，ViewModel v1.4 透传 `netSaleAmount` |
| `F9-GAP-19` | 全站行为转化漏斗 | 设计 V3.0 §11.3 | `FunnelAdsJob.scala:67-115`<br>`AdsSql.scala:57-79` | `ads_behavior_funnel_m`<br>(已暴露于漏斗页) | 已发布已暴露 | 无缺口 (A类) | 4阶段全站转化漏斗，暴露步长转化率与整体加购率、购买率 |
| `F9-GAP-20` | 核心经营概览与 14 项标量指标 | 设计 V3.0 §11.2<br>`metric-dictionary.md` | `AdsSql.scala:11-37` | `ads_operation_overview_m`<br>`metric_value` (14/14 全暴露) | 已发布已暴露 | 无缺口 (A类) | 14项标量与概览行全量发布，通过发布门金标准校验，全端点正常消费 |
| `F9-GAP-21` | 6 大业务报告实例 API | D-060 契约草案<br>设计 V3.0 §14.4 | 服务端确定性生成引擎 | `/api/v1/reports/*`<br>(已暴露实例创建/查询) | 已发布已暴露 | 无缺口 (A类) | 支持 `OPS_DAILY`、`OPS_WEEKLY` 等6种模板实例生成与查询，满足 D-060 规范 |

---

## 四、分类与价值排序

依据规约要求，对上述 21 项对账事项进行严格分类（A/B/C/D 每项必落一类）与价值排序。

### 4.1 分类标准与分布

- **A 类（已发布已暴露）**：9 项。功能在数仓、发布门与 API 侧完全闭环，无技术缺口，登记为基线能力。
- **B 类（本地可落地）**：5 项。上游数据源已在现有 DWD/DWS 或 MySQL 中存在，不依赖外部新增系统，可制定具体落地步骤供总控立项。
- **C 类（需新数据源或外部依赖）**：2 项。因底层模拟器/生成器缺失相应业务日志（库存流、采购成本流），严禁无数据造数，属于系统外部边界依赖。
- **D 类（设计过时/重复/与既有裁决冲突）**：5 项。已被既有裁决收敛、或被更优实现替代、或已正式列为暂缓 Backlog，建议维持现状或关闭。

---

### 4.2 A 类（已发布已暴露）登记清单（共 9 项）

1. `F9-GAP-13`：品类与地区销售汇总分析（`ads_category_sale_m`, `ads_region_sale_m`）
2. `F9-GAP-14`：全链路数据质量 20 项规则审计（`ads_data_quality_m`）
3. `F9-GAP-15`：用户画像与 RFM 8类精细分群（`ads_user_profile_m`）
4. `F9-GAP-16`：单品漏斗转化率分析（`ads_product_conversion_m`）
5. `F9-GAP-17`：商品热度榜稳定排序与分页（`ads_hot_product_m`）
6. `F9-GAP-18`：销售与活跃 30 日时序趋势（`ads_sale_trend_m`, `ads_active_trend_m`）
7. `F9-GAP-19`：日级 4 阶段全站转化漏斗（`ads_behavior_funnel_m`）
8. `F9-GAP-20`：核心经营概览与 14 项标量指标库（`ads_operation_overview_m`, `metric_value`）
9. `F9-GAP-21`：6 大标准业务报告实例生成引擎（`POST /api/v1/reports`）

---

### 4.3 B 类（本地可落地）实施步骤草案（共 5 项）

#### 1. `F9-GAP-03`：用户留存率（次日、7日、30日活跃留存）
- **数据就绪度**：`dwd_user_behavior_detail` 已具备全量历史行为事件（包含 `user_id`, `event_time`）。
- **具体落地步骤草案**：
  1. *数仓计算层（Spark）*：在 `DwsSql.scala` 中新增 `dws_user_retention_day` 聚合作业，按新客首日活跃分组，自关联计算后第 1、7、30 日再次活跃的用户数及留存比例；在 `AdsSql.scala` 中新增 `ads_user_retention_m` 主题表。
  2. *目录与发布层*：在 `MetricAdsCatalog.java` 中注册 `ads_user_retention_m` 表元数据；在 MySQL 迁移脚本中加性新增该表 DDL；在 `MetricPublisher.java` 中增加导出至 MySQL 的通道。
  3. *服务与API层*：在 `AnalysisService.java` 中增加 `userRetention()` 查询，在 `/api/v1/analysis/users` ViewModel 中加性扩展 `retention` 字段。
- **验收判据**：留存率介于 0%~100% 之间，同快照下重算结果严格确定性复现，通过新快照发布门测试。

#### 2. `F9-GAP-01`：`product_heat` 标量指标归宿对齐
- **数据就绪度**：`AdsSql.scala` 已计算每件商品的 `heat_score`。
- **具体落地步骤草案**：
  1. *发布映射层*：由于 `metric_value` 当前为全站日级标量，对齐方案二选一（总控决策）：
     - 方案 A（全站标量化）：在 `MetricPublisher.java` 中取 Top 1 商品热度值或全站商品热度均值，作为 `product_heat` 标量写入 `metric_value`，使得字典15项与 `metric_value` 达到 15/15 完全一致。
     - 方案 B（元数据明确）：在 `metric-dictionary.md` 明确声明 `product_heat` 属于实体级指标（Entity-grain Metric），物理归宿固定为 `ads_hot_product_m.heat_score`，不在 `metric_value` 标量表中冗余存储。
- **验收判据**：指标字典文档与 `MetricPublishValidator` 校验规则逻辑闭环，无歧义。

#### 3. `F9-GAP-06`：渠道流量与转化汇总表（`ads_channel_summary_m`）
- **数据就绪度**：`dwd_user_behavior_detail.channel` 列已有真实渠道值（如 APP、H5、Web、微信小程序等）。
- **具体落地步骤草案**：
  1. *数仓计算层（Spark）*：在 `AdsSql.scala` 中新增 `channelSummary` 计算，以 `[snapshot_id, dt, channel]` 为粒度聚合 `pv`, `uv`, `cart_uv`, `order_uv`, `pay_uv` 及各环节转化率。
  2. *发布与元数据*：在 MySQL 增加 `ads_channel_summary_m` 表 DDL，在 `MetricAdsCatalog.java` 注册新表并纳入发布检查。
  3. *服务与API层*：在 `/api/v1/analysis/funnel` 或 `/api/v1/analysis/sales` 中加性扩展 `byChannel` 节点。
- **验收判据**：各渠道 UV 之和合理大于或等于全站去重 UV，发布门行数校验通过。

#### 4. `F9-GAP-09`：统计异动检测结果 ADS 表下沉（`ads_metric_anomaly_m`）
- **数据就绪度**：`EvidenceBuilder.java` 与 `metric-dictionary.md:39` 中 Z-Score/IQR 算法已在后端稳定运行。
- **具体落地步骤草案**：
  1. *数仓计算层（Spark）*：新建 `AdsAnomalyJob.scala`，读取 `ads_sale_trend_m` 与 `ads_operation_overview_m` 近 14 天滑动窗口，利用 Spark SQL 窗口函数计算 14 天滚动均值与标准差，计算各核心指标 Z-Score 并输出候选异常点。
  2. *发布与存储*：建立 `ads_metric_anomaly_m` 表，包含 `metric_code`, `actual_value`, `rolling_mean`, `z_score`, `is_anomaly` 等字段。
  3. *服务改造*：AI 模块及业务报告直接查询该 ADS 表，减少 Java 内存滑动窗口重算负担。
- **验收判据**：Z-Score 阈值判断与 AI 现有检测逻辑输出一致，通过发布门校验。

#### 5. `F9-GAP-10`：指标维度表 `dim_metric` 双向同步
- **数据就绪度**：MySQL 中已有 `metric_definition` 表（Flyway 迁移维护），指标定义权威版本完备。
- **具体落地步骤草案**：
  1. *同步作业开发*：在 `spark-jobs` 中编写 `DimMetricSyncJob`（或集成至 `LocalSchemaInitJob`），将 MySQL `metric_definition` 表反向同步至 Hive 数仓的 `dim_metric` 表。
  2. *对账校验*：增加元数据校验用例，确保 Hive 中 `dim_metric` 行数与 MySQL `metric_definition` 严格一致。
- **验收判据**：`dim_metric` 在 Hive 与 MySQL 两侧行数、字段与版本哈希完全一致，消除 PROJECT_STATUS L3260 登记的元数据缺口。

---

### 4.4 C 类（需新数据源或外部依赖）依赖说明（共 2 项）

#### 1. `F9-GAP-07`：库存周转天数与 ABC 库存分类
- **缺失数据源**：商城交易系统或数据生成器目前仅生成前端浏览事件与订单/支付/退款流水，**缺失仓库物理库存快照、入库单、出库单及盘点日志（ODS 缺库存主题）**。
- **设计约束依据**：`docs/design/项目设计文档 V3.0.md` §11.5 L457 与指导书 V3.0 L151 明文立规：“缺库存流就显示缺少库存数据，不可从订单数虚构库存”。
- **结论与处置**：本期不伪造虚假库存数据。若总控未来规划供应链仿真，须在 `generator` 模块新增仓储库存仿真引擎并接入 Flume，方可启动数仓模型建设。

#### 2. `F9-GAP-08`：商品采购成本、毛利与毛利率
- **缺失数据源**：`dim_product` 当前仅有零售标价（`price`），**缺失商品进货成本价（`cost_price`）、促销折让分摊及供应链采购履约成本**。
- **设计约束依据**：`docs/design/项目设计文档 V3.0.md` §11.5 L457 明文立规：“无 cost 时不可计算毛利”。
- **结论与处置**：严禁按固定比例拍脑袋假定毛利率。保持当前净销售额（`net_sale`）作为最终财务收入指标，毛利指标待商品档案增加采购成本源字段后再行接入。

---

### 4.5 D 类（设计过时/重复/既有裁决冲突）处置依据（共 5 项）

#### 1. `F9-GAP-02`：`user_value_level` 独立标量字段
- **依据说明**：设计字典中提出的价值等级概念，在当前工程中已被 `ads_user_profile_m.rfm_segment`（8大精细人群）与 `RfmService` 的高中低价值分组完全涵盖且更为严谨。
- **处置建议**：建议关闭将 `user_value_level` 作为单列输出的冗余需求，正式裁定由 RFM 分群模型承接该业务诉求。

#### 2. `F9-GAP-04`：支付复购率变体（`paid_repeat_rate`）
- **依据说明**：指导书 V3.0 §11.4 与 Backlog F-36 将其列为可选变体。当前已发布的“有效复购率”（剔除退款）在电商学术界与业务实操中是更科学的口径，已完全满足毕业设计论文与答辩要求。
- **处置建议**：维持现状，保留在 Backlog F-36 中，首版不重复实现变体。

#### 3. `F9-GAP-05`：会话级严格时序漏斗与多维下钻
- **依据说明**：指导书 V3.0 §11 L264 明文裁决待决项 G-04：“严格/分类漏斗粒度...不阻塞无关开发”。目前全站日级松散转化漏斗（4阶段）已在发布门与页面上完整呈现。
- **处置建议**：维持 G-04 延期裁决，不在本期引入会话切分与严格时序状态机。

#### 4. `F9-GAP-11`：`AdsSql.categorySale` 跨品类退款分摊
- **依据说明**：`mall-simulator-notes/WP-F7d-REPORT.md` 与既有总控裁决明确界定：退款缺少子单 SKU 级拆分属于上游 DWD/编译器责任，ADS 层严禁利用启发式规则二次分摊，以免破坏数据一致性校验。
- **处置建议**：严格遵守 WP-F7d 既有裁决，严禁在 ADS 层作任何代码修改，维持现状。

#### 5. `F9-GAP-12`：业务报告实例 HTML / PDF 二进制导出
- **依据说明**：D-060 批准的 `docs/contracts/business-report-instance-api-draft.md` §4/§6 明确指出：首版核心是输出结构化、不可变且带校验和的 JSON 报告实例制品，HTML/PDF 渲染与文件下载明确列为延期 Backlog。
- **处置建议**：维持 D-060 范围边界，本期以 JSON API 作为验收终点，文件导出保持在 Backlog 中。

---

### 4.6 综合价值排序（全量 21 项）

排序权重口径严格依据：**答辩可见性 > 数据就绪度 > 实施成本低**。每个事项均附一句定性评估理由。

| 优先级次序 | 事项编号 | 事项名称 | 分类 | 排序依据与定性理由 |
|---|---|---|---|---|
| **1** | `F9-GAP-20` | 经营概览与 14 项指标 | **A** | 答辩核心门面，展示电商大盘运营全局健康度，基线已完备。 |
| **2** | `F9-GAP-18` | 销售与活跃趋势分析 | **A** | 评委走查第一入口，直观体现 30 日时序演进与快照一致性。 |
| **3** | `F9-GAP-19` | 4 阶段全站转化漏斗 | **A** | 电商数仓代表性业务模型，经典展示转化漏损率。 |
| **4** | `F9-GAP-15` | 用户画像与 RFM 分群 | **A** | 毕业设计高级数仓亮点，提供精细化人群运营数据支撑。 |
| **5** | `F9-GAP-14` | 全链路数据质量 20 项规则 | **A** | 工程规范与学术严谨性最强佐证，答辩防御数据造假利器。 |
| **6** | `F9-GAP-17` | 热门商品 Top 排行榜 | **A** | 答辩高频交互项，支持稳定排序与真分页，体现工程落地深度。 |
| **7** | `F9-GAP-13` | 品类与地区销售汇总 | **A** | 展现多维下钻分析能力，覆盖宏观品类与地域空间分布。 |
| **8** | `F9-GAP-16` | 单品转化漏斗分析 | **A** | 微观商品运营必备视图，直观对比商品转化效能。 |
| **9** | `F9-GAP-21` | 6 大业务报告实例 API | **A** | 贯通数仓与管理层决策，D-060 契约赋能确定性业务研判。 |
| **10** | `F9-GAP-03` | 用户留存率 (D+1/D+7/D+30) | **B** | **答辩极具分量的标准指标，DWD 数据已完备，补充开发产出比极高。** |
| **11** | `F9-GAP-01` | `product_heat` 标量发布对齐 | **B** | **消除字典15项与发布14项字面差异，防评委现场逐字对账追问，实施成本极低。** |
| **12** | `F9-GAP-06` | 渠道流量与转化汇总表 | **B** | 经典流量来源看板，DWD 已有 channel 字段，可显著增强营销分析完整性。 |
| **13** | `F9-GAP-10` | `dim_metric` 双向同步 | **B** | 消除 PROJECT_STATUS 登记的历史元数据缺口，健全数仓架构规范性。 |
| **14** | `F9-GAP-09` | 统计异动检测 ADS 表下沉 | **B** | AI 模块已有内存算子，下沉数仓可增强离线历史审计，但答辩已有功能可用。 |
| **15** | `F9-GAP-02` | `user_value_level` 独立字段 | **D** | 业务已被更先进的 RFM 8群完整替代，无需冗余字段，建议正式关闭。 |
| **16** | `F9-GAP-04` | 支付复购率变体 | **D** | 默认“有效复购率”已在基线完美发布，变体对答辩无增量价值，维持 F-36 暂缓。 |
| **17** | `F9-GAP-05` | 会话级严格时序漏斗 | **D** | G-04 既有裁决延期，松散漏斗已满足要求，引入状态机会极大推高复杂度。 |
| **18** | `F9-GAP-11` | 品类退款跨单分摊修改 | **D** | WP-F7d 严令维持现状，属上游责任，ADS 层强修会导致测试门破损。 |
| **19** | `F9-GAP-12` | 报告 HTML/PDF 格式导出 | **D** | D-060 已将 JSON 实例列为终点，二进制渲染为前端/导出附庸，维持暂缓。 |
| **20** | `F9-GAP-07` | 库存周转与 ABC 分类 | **C** | 模拟器无库存日志，设计明文禁止臆造造数，必须等待源系统扩展。 |
| **21** | `F9-GAP-08` | 采购成本、毛利与毛利率 | **C** | 缺失成本底数据，无 cost 禁止算毛利为铁律，不可违规计算。 |

---

## 五、汇总计数与勾稽核对

为确保盘点成果严谨、自洽，对本报告所涉所有清单行数与分类进行最终的交叉勾稽核对。

### 5.1 分类与清单勾稽表

| 类别分类 | 类别定义 | 包含缺口编号 | 数量统计 | 勾稽验证结果 |
|---|---|---|---|---|
| **A 类** | 已发布已暴露 | `F9-GAP-13` ~ `F9-GAP-21` | **9 项** | 与第一部分暴露视图及 10 表完全对应 |
| **B 类** | 本地可落地（待立项实施） | `F9-GAP-01`, `F9-GAP-03`, `F9-GAP-06`, `F9-GAP-09`, `F9-GAP-10` | **5 项** | 数据源均已验证在库，步骤草案完备 |
| **C 类** | 需新数据源 / 外部依赖 | `F9-GAP-07`, `F9-GAP-08` | **2 项** | 缺失数据源均来自 ODS 仿真器限制 |
| **D 类** | 设计过时 / 重复 / 裁决冲突 | `F9-GAP-02`, `F9-GAP-04`, `F9-GAP-05`, `F9-GAP-11`, `F9-GAP-12` | **5 项** | 均具备既有文档/裁决硬约束凭据 |
| **总计** | **三层对账缺口全量事项** | `F9-GAP-01` ~ `F9-GAP-21` | **21 项** | **9 + 5 + 2 + 5 = 21（严格自洽）** |

### 5.2 盘点核心结论与总控建议

1. **核心基本盘稳固**：已发布的 10 张 ADS 表、14 个 `metric_value` 核心指标、20 项数据质量审计规则以及 6 类报告实例 API 构成了极其扎实且自洽的系统基线，通过了发布门双重校验与 ViewModel v1.12 契约对齐。
2. **高价值推进项明确**：
   - 若总控在后续阶段仍有余力扩充系统指标，首选实施 **`F9-GAP-03` 用户留存率（D+1/D+7/D+30）** 与 **`F9-GAP-01` `product_heat` 标量收敛**。前者为答辩提供重量级亮点，后者彻底消除字典 15 项与发布库 14 项的字面差异，且两者均可在纯本地环境下依靠既有 DWD 行为明细和 Spark 批处理作业无风险完成。
3. **红线明确坚守**：坚决不触碰库存与成本毛利虚构造数（C 类），严格遵循 WP-F7d 裁决不对 `AdsSql.categorySale` 做危险修补（D 类），维持 G-04 及 D-060 的延期范围约束。
