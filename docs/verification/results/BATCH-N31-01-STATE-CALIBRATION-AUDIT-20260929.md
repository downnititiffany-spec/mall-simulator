# N31-01 状态校准审计（2026-09-29）

> 状态：PARTIAL，待总控处理门控事项。本文件记录只读核验与工作树归属，不替代 G31-12 原结果，不构成产品验收，也不授权恢复数据库、发布指导书或推送代码。

## 1. 基线与范围

- 工作树：D:\Develop_code\GraduationProject-wt\v3-dev
- 分支：feature/v3-development
- HEAD：300c4c9b7bfc25b8a058c62ad89f349bb15121b8
- 本次未修改业务代码、未运行全量测试、未 stage / commit / push。
- 本次数据库核验只通过显式 127.0.0.1:3307 连接执行只读元数据查询；没有连接或执行 SQL 到 3306。

## 2. G31-12 归档与状态核验

### 2.1 历史归档

G31-12 归档实际位于当前仓库旁的共享归档目录 D:\Develop_code\GraduationProject-wt\v3-archive\g3112，不在当前仓库子目录内。对 MANIFEST-SHA256.txt 的 164 条记录逐文件重算 SHA-256，缺失与不匹配均为 0；git bundle verify 报告完整历史且退出码为 0。G31-12 结果表中 8 条判据均记录 PASS；D-052 已签收 G31-12 四项证据并解除 G31-11 暂缓签收，裁定范围仍限已记录的 WSL 单节点/隔离证据，不等于产品最终验收。

### 2.2 当前 WSL 3307 与归档状态不一致

只读查询识别到 MySQL 8.0.41、服务器主机名 dahaishui、端口 3307。当前 analytics_meta 表数为 0，因此没有 flyway_schema_history，不能现场读出 V33 checksum；analytics_metric 只有一张表 __v25_w03_probe。这与 2026-09-26 G31-12 归档中记录的正式 analytics_meta V33 checksum 535846146 不是同一时点状态。

本次没有执行 DDL/DML、导入 dump、修复 Flyway、删除或恢复任何数据库对象。G31-12 的历史证据仍按 D-052 的范围有效，但“当前 live 3307 已有正式 V33 状态”未能复证；不得把归档快照描述为当前数据库读数。原因尚未确定。

## 3. 当前工作树 192 项归属清点

以 git status --porcelain=v1 -uall 对全部条目分类，数量合计 192，无“未知”项：

| 数量 | 归属 | 处置边界 |
|---:|---|---|
| 30 | N31-03 普通员工可用性：后端权限/AI/来源选项、Vue 来源与快照选择、页面、测试、浏览器脚本和前端/平台打包脚本 | 保留；报告已追加变更归属勘误 |
| 52 | N31-02 腿③分类/城市等级供数：ADS SQL/DDL、V13 服务表迁移、导出/发布、服务/API、AI Evidence、Vue 销售页和回归测试 | 保留；腿③报告已追加变更归属勘误 |
| 12 | 批次登记材料：4 个已修改状态/计划/决策文档，及 4 个 N31-02 腿结果、N31-03 计划/结果、腿③契约和裁决原文共 8 个新增文件 | 保留，按批次管理 |
| 16 | N31-02 腿③ v3-archive/n3102/legc-20260929-7c11 证据文件 | 保留；manifest 已记录 |
| 78 | 带 .bak-* 后缀的编辑前备份 | 全部保留；不 stage、不提交 |
| 3 | .zcode/plans/...、.zcodeignore、仓库根游离 PROJECT_STATUS.md | 本地保留，不纳入项目提交 |
| 1 | scripts/g3111-formal-restart.ps1 | G31-11 历史专用 runner，按 D-051 永不提交清单保留 |
| 192 | 合计 | 未发现无法归属的路径 |

根目录忽略文件 credref-mysql3307-root.properties 不属于这 192 项；凭据值未输出、未复制进证据。所有备份原样留存。分支只读对比：本地 HEAD 相对 origin/feature/v3-development 为 31 ahead / 0 behind，相对 origin/main 为 451 ahead / 0 behind；不推送。

### 3.1 N31-03 文件清单（30）

下列路径在 N31-03 计划/结果生成前已有变更，语义与 N31-03 的员工页面、RBAC、来源向导、快照选择、AI→DRAFT 和发布包判据对应：

- analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/sql/AiScopeResolver.java
- analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/sql/SqlPolicy.java
- analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/TextToSqlService.java
- analytics-server/ai-decision/src/test/java/com/graduation/analytics/ai/AiSqlSecurityTest.java
- analytics-server/ai-decision/src/test/java/com/graduation/analytics/ai/TextToSqlAuditTest.java
- analytics-server/platform-app/src/main/java/com/graduation/analytics/controller/AiController.java
- analytics-server/platform-app/src/main/java/com/graduation/analytics/controller/AnalyticsSourceOptionController.java
- analytics-server/platform-app/src/main/java/com/graduation/analytics/controller/SpaFallbackController.java
- analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/AiControllerIdentityTest.java
- analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/AnalyticsSourceOptionControllerTest.java
- analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/ControllerPermissionCoverageTest.java
- analytics-server/platform-app/src/test/java/com/graduation/analytics/controller/SpaFallbackControllerTest.java
- scripts/build-web-and-package.ps1
- scripts/e2e/n3103_employee_browser.py
- web/src/api.js
- web/src/App.vue
- web/src/components/SnapshotSelector.vue
- web/src/composables/useAnalysis.js
- web/src/composables/useSnapshotSelection.js
- web/src/router.js
- web/src/utils/snapshotOptions.js
- web/src/views/AiAssistant.vue
- web/src/views/Behavior.vue
- web/src/views/Overview.vue
- web/src/views/Products.vue
- web/src/views/Rfm.vue
- web/tests/aiEvidenceWindow.test.js
- web/tests/boundary.test.js
- web/tests/snapshotOptions.test.js
- web/tests/useAnalysisFilters.test.js

### 3.2 N31-02 腿③文件清单（52）

下列路径在 D-058 批准后、腿③结果记录前修改或新增，与获批的 ADS→服务镜像→API/Vue/AI Evidence 数据链及其定向回归对应：

- analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidenceBuilder.java
- analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidencePackage.java
- analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidenceTemplates.java
- analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/MetricLineage.java
- analytics-server/ai-decision/src/test/java/com/graduation/analytics/ai/evidence/EvidenceBuilderTest.java
- analytics-server/ai-decision/src/test/java/com/graduation/analytics/ai/ExplanationEvidenceTest.java
- analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/AnalysisService.java
- analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/AnalysisViewModel.java
- analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsCatalog.java
- analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsWriter.java
- analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/publish/MetricExportPath.java
- analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/publish/MetricPublisher.java
- analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/publish/MetricPublishValidator.java
- analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/AnalysisGoldenMySqlIT.java
- analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/AnalysisServiceTest.java
- analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/AnalysisSourcePolicyTest.java
- analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsCatalogDdlConsistencyTest.java
- analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsDaoTest.java
- analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/publish/MetricExportManifestChecksumTest.java
- analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/publish/MetricPublisherBuildFailureCompensationTest.java
- analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/publish/MetricPublisherMySqlIT.java
- analytics-server/platform-app/src/main/resources/db/metric/V13__ads_category_region_sales.sql
- analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/QualityRuleCatalog.java
- analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/RuleSeverity.java
- analytics-server/platform-common/src/test/java/com/graduation/analytics/metric/RuleSeverityTest.java
- analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/PipelineService.java
- analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/PipelineServiceTest.java
- spark-jobs/src/main/scala/com/graduation/analytics/job/AdsPublishJob.scala
- spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala
- spark-jobs/src/main/scala/com/graduation/analytics/job/DimensionBuildJob.scala
- spark-jobs/src/main/scala/com/graduation/analytics/job/FunnelAdsJob.scala
- spark-jobs/src/main/scala/com/graduation/analytics/job/JobRegistry.scala
- spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala
- spark-jobs/src/main/scala/com/graduation/analytics/job/MetricExportJob.scala
- spark-jobs/src/main/scala/com/graduation/analytics/metric/MetricAdsSpec.scala
- spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala
- spark-jobs/src/main/scala/com/graduation/analytics/sql/DimSql.scala
- spark-jobs/src/test/scala/com/graduation/analytics/AdsCartRateSpec.scala
- spark-jobs/src/test/scala/com/graduation/analytics/AdsHotProductHeatRuleVersionSpec.scala
- spark-jobs/src/test/scala/com/graduation/analytics/AdsQualityRuleVersionSpec.scala
- spark-jobs/src/test/scala/com/graduation/analytics/AdsSaleTrendNetSaleSpec.scala
- spark-jobs/src/test/scala/com/graduation/analytics/AdsSchemaOwnerSpec.scala
- spark-jobs/src/test/scala/com/graduation/analytics/CategoryRegionAdsSpec.scala
- spark-jobs/src/test/scala/com/graduation/analytics/DwsAdsChainExecSpec.scala
- spark-jobs/src/test/scala/com/graduation/analytics/MetricAdsSpecTest.scala
- spark-jobs/src/test/scala/com/graduation/analytics/MetricExportZeroRowSpec.scala
- spark-jobs/src/test/scala/com/graduation/analytics/ProductDimensionAsOfSpec.scala
- spark-jobs/src/test/scala/com/graduation/analytics/SqlTemplateSpec.scala
- warehouse/ddl/04-ads.sql
- web/src/utils/chartOptions.js
- web/src/views/Sales.vue
- web/tests/chartOptions.test.js

## 4. 判据状态与剩余门控

| N31-01 条目 | 当前结论 |
|---|---|
| 1. 归档、8 项证据与 V33 | 历史归档 hash/bundle 核验通过；当前 3307 live schema 与归档状态不一致，V33 不能现场复核 |
| 2. G31-11 / G31-12 签收分离 | D-052 已签收 G31-12 证据并解除 G31-11 暂缓；明确不是产品最终验收 |
| 3. 脏工作树归属 | 192 项全部归类、未知 0；两份结果文档中的代码变更计数已追加勘误 |
| 4. 与冻结设计 V3.0 的确切差异 | 仍按 D-053 / V3.1 §2.3 门控，等待总控审阅 V3.1 草稿；本文件未自行发布或审定正式设计 |

因此 N31-01 尚未达到退出标准。接下来需要总控决定 V3.1 草稿审阅/采用范围；如要求重新建立正式库 live 证据，则需单独批准一个受控恢复/复验窗口。本轮不恢复或重建任何库。
