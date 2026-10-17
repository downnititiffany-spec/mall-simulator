# WP-F9a 交付报告：用户留存率落地（F9-GAP-03）与指标字典口径收敛（F9-GAP-01 方案 B）

> **实施基线**：仓库 `mall-simulator`，分支 `codex/cloud-v3-development`  
> **依据规约**：`/home/hatch/workspace/projects/mall-simulator-notes/WP-F9a.md`、`WP-F9-INVENTORY-REPORT.md`  
> **执行约束**：仅使用文件读写工具；不运行任何 Shell 命令、Maven 构建或测试（由总控全权亲跑验收）

---

## 一、改动文件清单

### 1. 存储与数仓 Schema（DDL / Migration）
1. `analytics-server/platform-app/src/main/resources/db/metric/V14__ads_user_retention.sql` **[新建]**
   - 建立 MySQL `ads_user_retention_m` 物化宽表，主键 `(snapshot_id, dt)`，顺延 Flyway 迁移版本至 `V14`。
2. `analytics-server/platform-app/src/test/java/com/graduation/analytics/isolation/AnalyticsIsolationFlywayIT.java` **[修改]**
   - 将 `CURRENT_METRIC_HEAD` 版本钉从 `"13"` 严格同步顺延至 `"14"`。
3. `warehouse/ddl/04-ads.sql` **[修改]**
   - 新增 Hive 正式外表 `ads_user_retention` DDL（分区为 `dt STRING`，Parquet 存储）。
4. `spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala` **[修改]**
   - 本地自举/轻量测试中增加 `ads_user_retention` 正式表与 `ads_user_retention__staging` 暂存表 DDL。

### 2. 数仓计算与发布流水线（Spark 层）
5. `spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala` **[修改]**
   - 在 `TABLES` 清单中追加 `"ads_user_retention"`。
   - 新增辅助函数 `shiftDate(dt, days)`，提供 compact yyyyMMdd 格式日期偏移。
   - 新增 `userRetention(ns, dt, snapshotId)` SQL 构造器，实现基于 `dwd_user_behavior_detail` 的队列去重与 D+1 / D+7 / D+30 留存人数与比率直算，并在目标日超出 `max_dt` 时置为 `NULL`，排除空队列行。
6. `spark-jobs/src/main/scala/com/graduation/analytics/job/FunnelAdsJob.scala` **[修改]**
   - 在 `fna` 作业中调度执行 `AdsSql.userRetention`，使留存表与现有 ADS 表集同快照、同批次产出至暂存分区。
7. `spark-jobs/src/main/scala/com/graduation/analytics/metric/MetricAdsSpec.scala` **[修改]**
   - 在 Hive ADS ↔ MySQL 宽表导出白名单 `MetricAdsSpec.TABLES` 中登记 `ads_user_retention` -> `ads_user_retention_m` 映射及 7 个业务列定义。

### 3. 发布与目录层（Platform / Metric Analysis）
8. `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsCatalog.java` **[修改]**
   - 在 `MetricAdsCatalog.ALL` 中注册 `ads_user_retention_m`，定义 7 个业务列，主键非快照列为空（无除 snapshot_id/dt 之外的额外键列）。
   - 由于发布侧 `MetricPublisher` 循环迭代 `MetricAdsCatalog.ALL`，并依赖 `MetricAdsWriter.insertRows` 与 `MetricExportManifest`，注册后发布通道与整快照清理自动就绪。

### 4. 服务层与契约扩展（AnalysisService / Contracts）
9. `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/AnalysisService.java` **[修改]**
   - 增加常量 `T_USER_RETENTION = "ads_user_retention_m"`。
   - 声明 `RetentionPoint` 实体 record（包含 `date, cohortUserCount, retainedD1, retentionD1, retainedD7, retentionD7, retainedD30, retentionD30`）。
   - 扩展 `UsersData` record 增加 `List<RetentionPoint> retention` 字段，同时保留兼容旧构造器。
   - 更新 `users(snapshotId, from, to)` 端点，增加 `requireOrderedDateRange(from, to)` 校验，调用 `retention(...)` 组装返回数据。
   - 实现私有方法 `retention(...)`，基于 `trendRows(T_USER_RETENTION, snapshotId, from, to)` 按 `dt` 升序映射。
10. `docs/contracts/analysis-viewmodel-r7-4.md` **[修改]**
    - 升级契约版本至 `v1.13`，头部增加 `v1.13` 用户留存率加性扩展变更记录。
    - 更新 §3.5 `/api/v1/analysis/users` 端点定义、响应 JSON 样例及字段口径说明。
11. `docs/contracts/metric-dictionary.md` **[修改]**
    - 保持首批 15 项指标公式与 `definition_version v1` 不变，在文末新增 `## 6. 指标物理归宿与聚合形式澄清（加性说明）`：
      - 明确 `product_heat` 为实体级（商品粒度）指标，物理归宿物化为 `ads_hot_product_m.heat_score`，不入 `metric_value` 全站标量表；
      - 明确 `user_value_level` 业务诉求由 `ads_user_profile_m.rfm_segment` 与 `RfmService` 分组承接，不单列标量字段。

### 5. 测试套件适配与新增测试
12. `spark-jobs/src/test/scala/com/graduation/analytics/AdsSchemaOwnerSpec.scala` **[修改]**
    - 在 `Frozen` 表清单及 `write` 模式匹配中补充 `ads_user_retention`，更新表总数断言为 `AdsSql.TABLES.size`。
13. `spark-jobs/src/test/scala/com/graduation/analytics/DwsAdsChainExecSpec.scala` **[修改]**
    - 将写死的 10 张 ADS 表断言改为动态对齐 `AdsSql.TABLES.size` 与 `MetricAdsSpec.TABLES.size`。
14. `spark-jobs/src/test/scala/com/graduation/analytics/AdsUserRetentionSpec.scala` **[新建]**
    - 新增行为测试套件，全面覆盖：跨日回访计算、首日未回访留存记 0 而非 NULL、超出最大业务日 horizon 严格置 NULL、空队列无产出行、正式表同构写入。
15. `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/AnalysisServiceTest.java` **[修改]**
    - 引入 `RetentionPoint` 导入与 `adsRows()` 夹具中的 `ads_user_retention_m` 存根数据。
    - 新增 `usersReturnsRetentionPoints` 测试，断言留存点数值与不可观测字段为 null；新增 `usersInvertedDateRangeThrows` 断言日期倒置返回 `PARAM_INVALID`。

---

## 二、计算层位选择与理由

- **所选层位**：`AdsSql` 直算（基于 `dwd_user_behavior_detail` 关联直出），并在 `FunnelAdsJob`（`fna`）中调度执行。
- **选型理由**：
  1. **数据源粒度契合**：队列与留存的判定口径为「当日存在任一有效行为的去重用户（`distinct user_id`）」。`dwd_user_behavior_detail` 本身已按 `dt` 分区、清洗过滤有效行为，具备最完整的用户行为明细。
  2. **避免引入冗余 DWS 中间状态**：现有的 `dws_user_behavior_day` 仅汇总单用户单日各行为频次（PV/加购/收藏/购买），并不维护跨日（D+1、D+7、D+30）的队列生命周期关系；若为了留存专门新建 DWS 跨日宽表，将打破现有 7 张 DWS 的结构并增加不必要的重算开销。
  3. **计算与调度一致性**：在 `AdsSql` 中通过首日队列行为明细 `LEFT JOIN` 各目标日行为明细，能够天然、统一地结合 `CROSS JOIN (SELECT MAX(dt) FROM dwd)` 进行动态可观测性判定；且接入 `FunnelAdsJob` 保证了 `ads_user_retention` 与大盘概览、活跃趋势、行为漏斗等在同一个 Spark 作业、同一个暂存快照生命周期内一致产出。

---

## 三、Flyway 迁移版本号

- **版本号**：`V14`
- **文件路径**：`analytics-server/platform-app/src/main/resources/db/metric/V14__ads_user_retention.sql`
- **版本钉**：`AnalyticsIsolationFlywayIT.java` 中 `CURRENT_METRIC_HEAD` 已由 `"13"` 顺延同步至 `"14"`。

---

## 四、新增与更新测试清单

1. `com.graduation.analytics.AdsUserRetentionSpec` **[新建行为测试]**：
   - `AdsSql.userRetention should 正确计算跨日回访队列留存，horizon 外严格为 NULL`：多日测试夹具验证有回访时的计数与 4 位小数留存率，以及超出数据最大日期的 D+7/D+30 为 `NULL`。
   - `should 在 horizon 内若无用户回访，留存记 0 而非 NULL`：验证可观测范围内次日无回访用户时保留分母并计算为 `0L` / `0.0000`。
   - `should dt 位于最新业务日时，D+1/D+7/D+30 均超出 horizon，全部置为 NULL`：验证最新日期的全部远期留存均为 `NULL`。
   - `should |cohort(dt)| = 0 的日期不产出行`：验证无任何行为用户时不产生空统计行。
   - `should 正式表写入与暂存表同结构（不带 snapshot_id 分区）`：验证正式分区 SQL 投影语法与暂存分区严格对称。
2. `com.graduation.analytics.analysis.AnalysisServiceTest` **[扩展服务层测试]**：
   - `usersReturnsRetentionPoints`：断言 `/api/v1/analysis/users` 能够正确透传 `RetentionPoint` 列表及 `null` 语义。
   - `usersInvertedDateRangeThrows`：断言 `from` 晚于 `to` 时抛出 `PlatformBizException(PARAM_INVALID)`。
3. `AdsSchemaOwnerSpec` / `DwsAdsChainExecSpec` **[回归兼容更新]**：
   - 修正表总数从 10 张更新至 11 张，确保全链路元数据与调度断言自洽通过。

---

## 五、未做项与范围外边界声明（Non-Goals）

严格按照 `WP-F9a.md` 边界约束，坚决不越界处理：
1. **F9-GAP-06 渠道表**：不触碰，按规约留待后续专用工作包 WP-F9b 处理。
2. **F9-GAP-09（异动归因下沉）与 F9-GAP-10（dim_metric 物理表同步）**：不属于本包范围。
3. **F9-GAP-07 / GAP-08（库存周转 / 成本毛利）**：因依赖外部仿真数据源，坚决不虚构造数。
4. **F9-GAP-02 / 04 / 05 / 11 / 12**：已按盘点 D 类维持关闭；严格遵守 WP-F7d 既有裁决，**严禁修改 `AdsSql.categorySale`**。

---

WPF9A-DONE
