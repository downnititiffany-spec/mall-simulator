# WP-F9a-FIX 修复规约：连带冻结断言同步（总控亲跑 WP-F9a 首轮验收判不合格后派发）

执行方：agy Flash（仅文件读写；构建/测试由总控亲跑）。仓库：mall-simulator，分支 codex/cloud-v3-development。

## 总控亲跑证据（RunId wpf9agate20261017，不采信自述的复验结果）

- spark 档：380 用例（基线 375 + 新增 AdsUserRetentionSpec 5）中 **19 失败**；**AdsUserRetentionSpec 本体 5/5 全过**（留存生产逻辑与口径无误，不得改动）。
- default 档 analytics：MetricAdsDaoTest 2 失败（见 E 项）；其余已跑模块全绿（platform-common 123 / connection-ingestion 415 / warehouse-pipeline 216 / metric-analysis 其余 164）。default 档中 generator 的失败是总控调用环境缺 JAVA_TOOL_OPTIONS 所致，与本包无关，**不要动 generator 任何文件**。
- 19+2 个失败全部是「ADS 表集 10→11 / DDL 43→45 / 夹具清单」类冻结枚举未同步，或新测试种子写法不符合 FixtureWriteShapeSpec 既有范式。逐项按下表修，**只许改测试文件**。

## 修复清单（仅 spark-jobs/src/test/** 与 analytics-server/metric-analysis/src/test/**）

### A. spark 表数断言 10→11
1. `MetricAdsSpecTest.scala` L44、L70 两处 ADS 表数断言 10→11。
2. `AdsHotProductHeatRuleVersionSpec.scala` L160：10→11。
3. `AdsQualityRuleVersionSpec.scala` L194：10→11。
4. `AdsCartRateSpec.scala` L249：10→11。
5. `DwsAdsChainExecSpec.scala` L150：fna outputPartitions 期望数 10→11（留存暂存分区确已产出，其余断言不动）。
6. `AdsSaleTrendNetSaleSpec.scala`：暂存表集合断言补入本命名空间的 `ads_user_retention__staging`（定位失败断言原文后按其既有写法加入集合）。

### B. DDL 总数断言 43→45（LocalSchemaInitJob 新增正式+暂存两张留存表）
7. `WarehouseNamespaceSpec.scala` L159：43→45。
8. `OdsV2SchemaOwnerSpec.scala` L181：43→45。

### C. FixtureWriteShapeSpec 与新测试种子范式
9. `FixtureWriteShapeSpec.scala` 的 FrozenWriteCounts 增补一项：`"AdsUserRetentionSpec.scala" -> 4`（该文件手写 INSERT 写入点数；若第 10 项改写后 A1 打印的 [S3-15] 实际计数与 4 不符，以打印值为准并在报告中注明）。
10. `AdsUserRetentionSpec.scala` 的 seedData 四条字面 INSERT…SELECT 改写为既有夹具范式（参照 AdsUvPvInvariantSpec L121-126）：每条形如
    `INSERT OVERWRITE TABLE ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '<dt>') SELECT behavior_id, user_id, product_id, category_id, behavior_type, event_time, event_date, event_hour, city_level, channel, session_id, source_batch_id, user_key, product_key, category_key FROM ( SELECT <原字面行> UNION ALL SELECT <原字面行> ) v`
    ——种子行与值逐字不变、5 个测试及其全部断言逐字不变，只改种子语句的投影形状。

### D. MetricExportZeroRowSpec（7 失败）
11. 该 spec 的零行导出夹具按其既有逐表构造范式补入 `ads_user_retention__staging`（零行），spec 内写死的表清单/计数由 10 改 11；零行语义不变（全表 0 行、pub/mxp 应达 SUCCESS、manifest rowCount=0）。失败点定位：L74 pub 状态、L92 MXP_SNAPSHOT_PINNED、L106 计数及其后 _export.json 连锁异常，根因同为夹具暂存集缺留存表。

### E. Java 目录断言
12. `MetricAdsDaoTest.java`：
    - `catalogContainsAllCurrentAdsTables`：hasSize(10)→hasSize(11)；containsExactly 列表末尾追加 `"ads_user_retention_m"`；DisplayName「10 张」→「11 张」。
    - `deleteSnapshotCoversAllTables`：times(10)→times(11)；deleted 期望 20→22；DisplayName「10 张」→「11 张」。

## 红线
- 不改任何生产代码（AdsSql.userRetention、目录、迁移、服务层均已亲验，冻结）。
- 不改 AdsUserRetentionSpec 的测试断言与种子数据值；不改 run-tests.ps1 基线（总控关账时再登记）。
- 不动 synthetic-data-generator、web、scripts 任何文件；清单外文件一律不碰。清单内某行号若与实际不符，按失败信息定位同一断言修改并在报告中注明实际行号。

## 交付
- 报告 `WP-F9a-FIX-REPORT.md`：逐项列改动文件、位置、改前→改后；末行 `WPF9AFIX-DONE`。
