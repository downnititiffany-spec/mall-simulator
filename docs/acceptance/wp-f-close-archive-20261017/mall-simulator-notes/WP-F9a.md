# WP-F9a 规约：用户留存率落地（F9-GAP-03）＋ product_heat 口径收敛（F9-GAP-01 方案 B）

依据：WP-F9-INVENTORY-REPORT.md（总控已亲验采信，2026-10-17）。执行方：agy Flash（仅文件读写；构建/测试由总控亲跑）。仓库：mall-simulator，分支 codex/cloud-v3-development。

## 范围 A（代码）：用户留存率 D+1 / D+7 / D+30

口径由总控钉死，不得自创：

- 队列定义：对 dwd_user_behavior_detail 中出现的每个 dt，cohort(dt) = 当日有任一有效行为的去重用户集。
- 留存定义：retained_n(dt) = cohort(dt) 中在 dt+n 日仍有任一有效行为的去重用户数，n ∈ {1, 7, 30}；retention_n = retained_n / |cohort(dt)|。
- 可观测性：当 dt+n 超出数据内最大 dt 时，该 n 的 retained/retention 必须为 NULL（未可观测），不得记 0；|cohort(dt)| = 0 的 dt 不出行。
- ADS 表 `ads_user_retention_m`，粒度 [snapshot_id, dt]，列：snapshot_id, dt, cohort_user_count, retained_d1, retention_d1, retained_d7, retention_d7, retained_d30, retention_d30（比率列可空，计数列不可空）。

落地链（照既有同类模式，不得新造框架）：

1. Spark 计算层：在与现有 ADS 计算一致的层位实现（优先 DwsSql 日级聚合 + AdsSql 投影，或 AdsSql 直算——在报告中写明所选层位与理由）；作业须纳入既有 JobRegistry/发布链的既有编排方式，与其他 ADS 表同快照产出。
2. 发布层：MetricAdsCatalog 注册 `ads_user_retention_m`；MetricPublisher 增加该表导出通道；MetricPublishValidator 的行数/必填校验与既有表一致口径（cohort 行数 = 数据内 distinct dt 数）。
3. 存储层：metric 库新增 Flyway 迁移（版本号顺延当前头 V13 → V14）建 `ads_user_retention_m` DDL；**同步更新 AnalyticsIsolationFlywayIT 的 metric 版本钉 "13"→"14"**（纪律：新迁移必同步该钉）。
4. 服务层：AnalysisService 增加留存查询，`/api/v1/analysis/users` 的 ViewModel 加性扩展 `retention` 节点（不改既有字段）；docs/contracts/analysis-viewmodel-r7-4.md 按其自身加性演进惯例补记。
5. 测试：新增行为测试——多日夹具含已知队列（含跨日回访、仅首日用户、horizon 外 NULL 三种情形）断言留存计数与比率；发布/目录层按既有同类测试模式补测。不新增字节冻结类巡检测试。

## 范围 B（仅文档）：metric-dictionary.md 口径收敛

在 docs/contracts/metric-dictionary.md 以加性澄清节写明（不改既有 15 项的公式与 definition_version）：

- `product_heat` 为实体级（商品粒度）指标，物理归宿固定为 `ads_hot_product_m.heat_score`，不入 `metric_value` 全站标量表；
- `user_value_level` 的业务诉求由 `ads_user_profile_m.rfm_segment` 与 RfmService 分组承接，不单列标量字段。

## 范围外（不得顺手做）

- F9-GAP-06 渠道表（转 WP-F9b）、GAP-09 异动下沉、GAP-10 dim_metric 同步：不在本包。
- F9-GAP-07/08（库存/成本）：外部数据源依赖，严禁造数。
- F9-GAP-02/04/05/11/12：按盘点 D 类裁定关闭/维持，尤其严禁改 AdsSql.categorySale（WP-F7d 裁决）。

## 交付与验收

- 交付报告 WP-F9a-REPORT.md：改动文件清单、所选计算层位与理由、迁移版本号、新增测试清单、未做项声明。
- 总控验收（执行方不自跑）：spark 套件、default 套件、AnalyticsIsolationScriptsContractTest、FlywayIT 版本钉核对；另以真实数据探针独立重算留存并与 ADS 产出对账（口径以本规约钉死定义为准）。
- 完成标记：报告末行 `WPF9A-DONE`。
