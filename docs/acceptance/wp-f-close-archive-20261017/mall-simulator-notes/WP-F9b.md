# WP-F9b 规约：渠道流量汇总表落地（F9-GAP-06，行为侧）

依据：WP-F9-INVENTORY-REPORT.md §F9-GAP-06（盘点已关账采信）＋总控本轮亲查实证与 G-04 部分裁定（见下）。执行方：agy Flash（仅文件读写；构建/测试由总控亲跑）。仓库：mall-simulator，分支 codex/cloud-v3-development。

## 总控裁定（G-04 部分解，口径钉死，不得自创、不得扩大）

总控亲查实证（2026-10-17 巡检）：
- `dwd_user_behavior_detail.channel` 为真实列（DwdSql L45 取 ODS `payload_channel`；源 B 行为事件 `via` 取值 APP/H5/PC 映射进入），行为侧渠道聚合是真下钻、非臆造。
- 交易链全程无 channel：`OrderTradeCompiler.TradeEvent` 无 channel 字段、`dwd_order_detail` 无 channel 列。故**任何按渠道的订单/支付归因都无数据依据**，正是设计 V3.0 §11.3 L443「未决不输出假的分类下钻」所禁。
- 据此裁定 G-04 部分解：**行为侧渠道粒度批准落地**（宽松用户口径，与全站漏斗同一语义所有者 `DwsSql.funnelDay`）；**交易侧渠道粒度维持不输出**，本包不得以任何方式（用户当日渠道归因、末次渠道归因等）生成 order/pay 渠道数字。设计 L443 自带注记同样适用：各渠道 distinct 用户之和不等于全站总数（多渠道用户在每个所用渠道各计一次），不得以此作对账失败判据。
- 盘点草案中 `order_uv`/`pay_uv` 两列**驳回**，不进本表。

## 范围（代码）：ads_channel_summary_m

口径（与 `DwsSql.funnelDay` 逐项同义，只是按 channel 分组）：
- 粒度 [snapshot_id, dt, channel]；channel 取 `dwd_user_behavior_detail.channel`，NULL 或空串归一为字面量 `'UNKNOWN'`（一行不丢）。
- `pv` = 当日该渠道 behavior_type='view' 的事件行数（COUNT(*)，不是去重）。
- `view_users` = 'view' 去重用户；`intent_users` = behavior_type IN ('favorite','cart_add') 去重用户；`cart_users` = 'cart_add' 去重用户。
- `intent_rate` = intent_users / view_users；`cart_rate` = cart_users / view_users；分母 0 → NULL（DECIMAL(8,4)，与 funnelDay 同精度同写法），绝不硬编码 0。
- ADS 表 `ads_channel_summary_m`，列：snapshot_id, dt, channel, pv, view_users, intent_users, cart_users, intent_rate, cart_rate（计数列不可空，比率列可空）。

落地链（照 WP-F9a 留存的既有模式，不得新造框架）：
1. Spark 计算层：`AdsSql.channelSummary` 直算 `dwd_user_behavior_detail`（与 `AdsSql.userRetention` 同层位理由：维度下钻属 ADS 专题，不动 DWS 全站宽表结构）；作业纳入既有编排序列，与其他 ADS 表同快照产出（留存挂在 FunnelAdsJob——本表同属漏斗域，优先同置；若结构不合，在报告中写明所选 Job 与理由）。
2. 发布层：`MetricAdsCatalog` 注册 `ads_channel_summary_m`（第 12 张 ADS 表）；发布通道/行数校验与既有表一致口径（行数 = 数据内 distinct (dt, channel归一后) 组合数）。
3. 存储层：metric 库新增 Flyway 迁移（版本号顺延当前头 V14 → V15）建 `ads_channel_summary_m` DDL；**同步更新 AnalyticsIsolationFlywayIT 的 metric 版本钉 "14"→"15"**（纪律：新迁移必同步该钉）。
4. 服务层：`AnalysisService` 漏斗查询加性扩展，`/api/v1/analysis/funnel` 的 ViewModel 加性扩展 `byChannel` 节点（不改既有字段）；docs/contracts/analysis-viewmodel-r7-4.md 按其自身加性演进惯例补记（留存为 v1.13，本项顺延）。
5. 测试：新增行为测试——多渠道夹具断言逐渠道 pv/去重用户/比率（含同一用户同日双渠道在两渠道各计一次、NULL channel 归 UNKNOWN、某渠道 view_users=0 时比率 NULL 三种情形）；发布/目录层按既有同类测试模式补测。**冻结计数同步纪律（WP-F9a 教训）**：凡因「ADS 表数 11→12、DDL 总数、暂存集、写入点清单」等冻结枚举连带变红的既有测试断言，随本包同步更新并在报告中逐项列明（只许改计数/清单类断言，不许改行为断言）；不新增字节冻结类巡检测试。
6. 文档：metric-dictionary.md 加性补渠道表口径节，写明：行为侧口径、交易侧无渠道（归因不输出）的裁定与理由、跨渠道 distinct 不可加和注记。

## 范围外（不得顺手做）
- 任何 order/pay 渠道列或归因逻辑（见裁定）；`DwsSql.funnelDay` 全站行与 `ads_behavior_funnel` 不动；category 粒度下钻不动（G-04 余部仍未决）。
- F9-GAP-09 异动下沉、GAP-10 dim_metric 同步：不在本包（后续排队）。
- F9-GAP-07/08（库存/成本）：外部数据源依赖，严禁造数。严禁改 AdsSql.categorySale（WP-F7d 裁决）。

## 交付与验收
- 交付报告 WP-F9b-REPORT.md：改动文件清单、所选 Job 层位与理由、迁移版本号、冻结计数连带更新逐项清单、新增测试清单、未做项声明（含交易侧渠道未输出声明）。
- 总控验收（执行方不自跑）：spark 套件、default 套件、AnalyticsIsolationScriptsContractTest、FlywayIT 版本钉核对；另以真实数据探针独立按 channel 重算 DWD 并与 ADS 产出对账。
- 完成标记：报告末行 `WPF9B-DONE`。
