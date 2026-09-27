# BATCH-G31-13 — 工作树 git 历史欠账收口（分组本地提交）

- **日期**：2026-09-27（Asia/Shanghai）
- **触发**： standing directive「持续工作直到指导书全部完成」+ D-048「应按批次保存完整源码，并将必要的脱敏证据归档到持久位置」+ G31-07 结果 §9 预留指令「原样保留；**提交时按改动线分组单独 commit，逐文件 diff 复核**」。
- **决策**：**D-051**（本批立项即登记，先决策后执行）。

## 0. 背景

b25b47f（2026-09-24 bulk checkpoint）之后、G31-04（2026-09-25）起，按「暂不 commit」指令窗口，G31-04~G31-09 各批次的代码与文档改动**有意保留在工作树未提交**：

- G31-04 计划 L9：「含 16 处既有未提交改动，原样保留」；
- G31-04/G31-05 结果：「产品源码零改动；工作树既有未提交改动原样保留；未 commit、未 push」（演练 jar 从携带树打包）；
- G31-11 结果 §167：「不触碰（G31-10/D-048 在途文件，保持未提交原状）：ExplanationService、IngestionService、LocalFileIngestor、…、MetricPublishValidator、MetricPublisher 及其测试」；
- G31-07 结果 §9（权威处置指令）：在途产品改动四条线（connection-ingestion 摄取/landing 存储线、metric-analysis 发布/导出线、warehouse-pipeline、ai-decision）「原样保留；提交时按改动线分组单独 commit，逐文件 diff 复核」。

当前工作树（vs HEAD `b1c02b6`）：**35 个已跟踪修改 + 67 个未跟踪路径**，diffstat ≈ +1362/−211。本批将这批欠账按归属分组、逐文件 diff 复核后**分组本地提交**收口。

## 1. 审计结论（逐文件定性，2026-09-26~27 完成）

### 1.1 六条代码改动线（35 M 中 30 个 + 未跟踪代码 14 个）

| 组 | 归属改动线 | 文件 | 依据 |
|---|---|---|---|
| ① connection-ingestion 摄取/landing 存储线 | item5/HDFS 链支撑（LandingStorage 存储缝：local/hdfs 双实现 + Resolver 按 profile.landing_uri 分派；LocalFileIngestor 存储无关化；SparkSubmitter 日志捕获完成语义 + 进程树取消） | 11 M（IngestionService、LocalFileIngestor、LandingInputScanner、LandingLayout、Hdfs/Local LandingStorage、LandingStorage 接口、LocalProcessSparkSubmitter + 3 测试）+ 9 新（LandingStorageResolver + HdfsFlumeRawIngestionIT、HdfsIngestionServiceIT、IngestionStoragePipelineTest、LandingStorageScannerTest、HdfsLandingStorageIT、HdfsLandingStorageScannerTest、LandingStorageContractTest、SeekableLandingInputTest）+ platform-app/pom.xml（hadoop-client 3.3.4 runtime，注释点名 HDFS profile） | G31-07 §9「13 M + 9 新文件」；G31-05 §05.3 平台 HDFS 摄取腿实测 |
| ② metric-analysis 发布/导出线 | D-042 file:/// URI 产品修复（MetricExportPath.localPath：file URI/盘符路径/其余 scheme 显式拒绝；Publisher/Validator 全部经它解析；CRC32 对 regular local file） | 5 M（MetricPublishValidator、MetricPublisher + 3 测试）+ 1 新（MetricExportPath） | G31-07 §9「6 M + 1 新（MetricExportPath）」；G31-05 §4 D-042 |
| ③ warehouse-pipeline Spark 本地档加固 | LOCAL/SINGLE_NODE 清空 `spark.hadoop.hive.metastore.uris`（隔离宿主 hive-site.xml）+ remote 键前缀修正 + Windows `spark.file.transferTo=false`（Spark 3.5.1 NIO jar 拷贝挂起规避） | 2 M（SparkStageExecutorFactory + 测试） | G31-07 §9；Spark 回环挂起坑（memory） |
| ④ ai-decision 日期令牌守卫 | ExplanationService 证据日期按整 token 校验，禁止用允许数字片段拼造日期 | 2 M（ExplanationService + ExplanationEvidenceTest） | G31-07 §9「2 M（ExplanationService 及其测试）」 |
| ⑤ web 每图空态线 | `chartStateForRows(requestStatus, rows)` 每图按自身行集派生状态 + 5 视图接线 + aiButtonStyle 回归 | 6 M（chartState.js、chartState.test.js、Overview/Behavior/Products/Rfm/AiAssistant.vue）+ 1 新（tests/aiButtonStyle.test.js） | 未单列于 G31-07 §9 表（UI 线），逐文件复核确认纯前端状态派生 |
| ⑥ fixtures/source-a-e3 精度注记 | ORACLE.md 发布精度注记（客单价按 DWS DECIMAL(18,2) quantize：E3=84.17/E4=92.31；92.3077 系历史口径残留）+ MANIFEST 计数段/哈希同步 | 2 M（ORACLE.md、MANIFEST-SHA256.txt） | sha256sum -c **5/5 OK**（2026-09-27 复核；7 行格式警告 = 计数段落非 hash 行，良性） |

### 1.2 按批次归属（G31-08/G31-09 代码 + 各批文档）

- **G31-08**（D-044④ 第一短批，F-G4-1/D-040 修复）：EventOdsLoadJob.scala、OdsLoadSql.scala（2 M）+ OdsMergeIncrementalSpec.scala（新）——diff 复核 = 分区作用域「读-合并-去重-覆写」+ dynamic 模式 try/finally 限域，与结果文档归属清单逐字吻合。
- **G31-09**（D-044④ 第二短批，D-041 修复）：V32__file_checkpoint_identity_width.sql + FileCheckpointIdentityWidthMigrationScriptTest（新）+ AnalyticsIsolationFlywayIT、SourceRegistryMigrationMySqlIT（2 M）。
- **批次文档**（10 份）：G31-04/05/07/08/09 各 plan+result；G31-07 组连带 `docs/handover/deployment-freeze-20260925.md`；`docs/guidance/项目完整实施指导书 V3.1.md` 单独一组。
- **scripts/run-tests.ps1**（共享门值，跨批累积）：G31-08 `$BaselineSpark` 322→329、G31-09 `$BaselineDefault['analytics-server']` 1096→1150、G31-11 1150→1165→1169；**G31-13 补 G31-12 同步行 1169→1170**（+1 重算旁路负例，D-050②；G31-12 实际基线 1170 未回同步，本批补齐并留痕）。

### 1.3 永不提交清单（本批边界）

`*.bak-*` 全部（30 件，含 20260925/26 历史备份与 20260927-g3113 本批备份）、`.zcode/`、`.zcodeignore`、仓库根游离 `PROJECT_STATUS.md`（正本在 docs/）、`scripts/g3111-formal-restart.ps1`（MIXED 脚本不入库，G31-11 惯例）、`credref-*.properties`（如有）。

## 2. 提交分组决策（D-051 核心）

**每组一个独立 commit，显式路径逐文件列全，绝不 `git add -A`/bulk-tree；仅本地提交，push 授权已用尽。** 顺序（注册先行，收口殿后）：

1. **R 注册组**：本计划 + DECISION_LOG（D-051）+ CURRENT_BATCH + PROJECT_STATUS（编辑前备份 `*.bak-20260927-g3113`）。
2. 组① connection-ingestion 存储缝线（21 文件）。
3. 组② metric-analysis D-042 线（6 文件）。
4. 组③ warehouse-pipeline 加固线（2 文件）。
5. 组④ ai-decision 守卫线（2 文件）。
6. 组⑤ web 空态线（7 文件）。
7. 组⑥ fixtures 精度注记（2 文件）。
8. G31-04 文档组（plan+result）。
9. G31-05 文档组（plan+result）。
10. G31-07 文档组（plan+result+freeze）。
11. 指导书 V3.1 文档组。
12. G31-08 代码组（3 文件）。
13. G31-08 文档组（plan+result）。
14. G31-09 代码组（4 文件）。
15. G31-09 文档组（plan+result）。
16. run-tests.ps1 门值组（先补 1169→1170 同步行再提交，message 记录 G31-08/09/11 累积链 + G31-13 补同步）。
17. **C 收口组**：CURRENT_BATCH + PROJECT_STATUS 结果登记。

## 3. 验证依据（不重跑 Java 全量基线的论证）

- **analytics-server 基线 1170（F=0 E=0 S=2）携带**：G31-12 收口（2026-09-26）在**当前字节状态的工作树**上全 reactor 跑出；本批只做 git 提交，不改任何文件字节 → 基线结论对提交后树同样成立。
- **web 套件 2026-09-27 复跑 375/375 PASS, 0 fail**（G31-02 时 329 → 现 375，含组⑤新增用例；node --test，约 1.7s）。
- **夹具 sha256sum -c 5/5 OK**（2026-09-27 复核）。
- **危险扫描双清零**（2026-09-27）：全部待提交 M diff + 14 个未跟踪代码文件 grep `jdbc:mysql:3306|password="..."|MYSQL3307_ROOT_PASSWORD|PRIVATE KEY` 零命中；V32 SQL 内 3306 表述仅为红线声明文字。
- **逐文件 diff 复核**（G31-07 §9 指令）已于 2026-09-26~27 对全部 35 M + 14 新代码文件完成，各组内容与上表归属一一对应，未发现越界内容。

## 4. 边界

- 本批**零产品源码/测试字节改动**（run-tests.ps1 门值数字与注释行除外——它本身就是待提交欠账的一部分）。
- 不改写历史提交（D-048）；不 push；3306 永久冻结零接触；`*.bak-*` 永不提交。
- 本批不产生新的验收判据：G31-04~G31-09 各批结论以各自结果文档为准，本批仅收口其 git 呈现形态。
- 提交信息按批次归属如实注来源（「暂不 commit」窗口 + 验证链），不伪装成单一批次产物。
