# PROJECT_STATUS

指导书基线：V2.8（`docs/毕业设计指导书 V2.8.md`，300 行 / 30,565 B / sha256 `00CE7AA9EAEB57A46B709A7B4D93C35CD2D282353E85532E76C6940E3344AF6F`）
项目设计基线：V2.5（`docs/design/项目设计文档 V2.5.md`，1,041 行 / 100,726 B / sha256 `400131173C2059F26A93E5B5ED75113CABAC8330E52E6B5E8ACE8650DF6A09BB`）
当前代码 commit：`8853730`（当前治理基线 `11919ed` ＝ `docs(治理): 发布指导书 V2.8 与项目设计文档 V2.5 —— 权威索引与冲突收口`，2026-09-14 19:28 +0800）
最后更新时间：2026-09-14 19:29 +0800（首版终态）

> 阅读口径：本文件只登记**可回溯到证据的实测事实**与**尚未裁决的冲突**；不解释、不合并、不替总控裁决。「未取证」一律显式标注，不得当作通过。
> 本文件**不设 `Vx.x` / `Sxxx` 等版本号**，始终一份，历史由 Git 保存；不得创建 `PROJECT_STATUS_V2.md`、`final.md` 等副本。
> 自 `11919ed` 起，指导书 V2.8 与项目设计文档 V2.5 为**正式已发布版本，禁止再次修改**；二者对本 Agent 只读，本文件是代码 Agent 唯一允许持续维护的状态文件。

## 当前阶段

- 阶段定位：`V2.x` 收口阶段 —— 代码主链已具规模，当前重心是**测试与验收的证据闭合**，而不是新增业务功能。
- 治理状态：三文件治理已生效。指导书 V2.8＝项目决策权威；项目设计文档 V2.5＝正式设计权威；本文件＝实际开发状态报告。
- 四级分级现状（四条判据独立，不合并）：
  - 提交完成 ✅（治理基线 `11919ed` 已入库；本文件首版提交见「最近工作记录」）
  - 测试通过 ⚠️ **仅默认档通过**；隔离档不通过（`exit 7`）
  - 限定验收 ✅（E3 隔离真链；F-88 限定验收）
  - 完整验收 ❌（`v26-real-testcounts-20260914` 五条硬理由；F-88 另有 8 项未取证，并受 DEV-001～DEV-004 相关缺口约束）

## 当前可运行功能

取证时点 2026-09-14；来源见「关键证据」。

- 分析后端 `analytics-server`（Spring Boot，六子模块：`platform-common`、`connection-ingestion`、`warehouse-pipeline`、`metric-analysis`、`ai-decision`、`platform-app`），本地端口 8091；前端 `web/`。
- 商城模拟器 `mall-simulator`（端口 8090）＋ `mall-frontend`；合成数据生成器 `synthetic-data-generator`；Spark 作业树 `spark-jobs`（Scala，Spark 3.5.1 / Hadoop 3.3.4）。
- 已实测跑通的链路：
  - 隔离实例 `127.0.0.1:3307`：ingestion → pipeline → metric 全链（F-88 链，终态 `FAILED / PIPELINE_QUALITY_FAILED`，**按设计**被质量闸门阻断）。来源 `docs/acceptance/v26-f88-isolated-chain-20260914/`，2026-09-14 16:2x。
  - 隔离真链（E3，单机 `local[2]`，仅启动期 env 覆盖）：`docs/acceptance/v25-e3-isolated-chain-20260914/`，2026-09-14 14:22。
  - 正式库 3306 侧历史真实跑动：`docs/acceptance/r9-20260911-*/`（run 22/25/30）等，属历史时点。
- **未取证**：以上业务链路均未在当前代码基线 `8853730` 上重新跑过；本次整理不运行任何测试。

## 已完成

- 代码规模（2026-09-14 19:2x 实测，tracked 计数）：主源码 `*.java` 366 个；测试 `*.java` 148 个（`*Test.java` 131、`*IT.java` 5）。Maven 目标＝`analytics-server`（6 子模块）＋ `mall-simulator` / `synthetic-data-generator` / `spark-jobs` 三个独立 POM。**仓库无根 POM**。
- F-88 写侧闭环（提交 `8853730`，总控复核 `37561dc`）：`data_quality_result` 四列按「声明／生效」契约落库；`warehouse-pipeline` 实测 `Tests run: 134, F:0, E:0, S:0`，`connection-ingestion` 156、`platform-common` 81，`BUILD SUCCESS`。
- F-88 隔离真链（提交 `b26e92d`，总控复核 `064b733`）：3307 上 V19/V20 真实落地、四列真落库（含 1 行「声明 WARN／生效 BLOCKING」）、3306 前后指纹 27 项全等。
- 默认档测试全绿，且取得本项目第一份「默认档不碰正式库」的**位点级零写入 3306** 实测证明（`v26-real-testcounts-20260914` 裁决 R-01）。
- 治理：指导书 V2.8／项目设计文档 V2.5 于 `11919ed` 正式发布 —— 三文件治理、作者与写入权限、状态文档职责、冲突处理规则与权威索引收敛；`K-xx`（仅总控可在指导书／设计文档定义）与 `DEV-xxx`（本文件）编号分流建立（见「历史编号勘误」）。

## 正在进行

- 隔离门禁修复：**总控已批准下一轮立即修复**（DEV-001 对应 R-02、DEV-002 对应 R-03；修法已闭合，本轮未实施、未复跑）。
- D-5「启动前门禁」：E3 脚本的两处收紧已被总控认可为基线（启动前 GATE 自检；隔离准备不读 3306、改取仓库内工件真值＋sha256），但**门禁本身未落地**（登记为 V25-S04）。
- F-88 完整验收缺口的收敛（受 DEV-001～DEV-004 约束，见「测试与验收状态」）。

## 待实现

按**证据缺口**列出（不是设计愿望清单）：

- 迁移 IT 覆盖 V19/V20（DEV-004，**继续归入 F-93** 一并整改）。
- `spark-jobs`（111 个 ScalaTest）与 19 个 DB 用例的自动执行入口（DEV-003）。
- 隔离档跑绿：mall 30 转绿 / generator ≥14 转绿（DEV-001／DEV-002 修复后的复跑目标）。
- `AnalysisGoldenMySqlIT` 库名参数化（R-05；否则该类永远不可在隔离库执行）。
- 唯一权威配置键名表（R-06：`metric.publish.username|metric.read.username` vs `metric.read.user|meta.app.user`）。
- 3306 真库 V19/V20 迁移（当前**冻结**，条件见「当前阻塞」）。
- 隔离实例遗留库／账号清理（总控已裁：F-93 完成后走 `-AllowedCleanDbs` 白名单，禁止手写 `DROP`）。
- P-01 的 append-only 勘误证据（见「已知实现问题」）。

## 当前阻塞

- **3306（总控裁决冻结）**：在 DEV-001／DEV-002 修复、3307 隔离真跑和 D-5 门禁完成前，**不进行新的 3306 迁移、切换或写入型验收**。
- DEV-001（阻断级，**已批准下一轮立即修复**）：`TestIsolationGuard:511 SELECT @@version_major` 在 MySQL 8 必败 ⇒ 写入型 IT 在 3306 与 3307 上都到不了任何断言；`:521/:524/:531/:534` 这些真正的安全判据在真库上**一次都没执行过**。
- DEV-002（阻断级，**已批准下一轮立即修复**）：隔离指纹／配置词汇表不对称 —— runner `scripts/run-isolated-tests.ps1:50,:154,:222` 注入 `server_uuid`，而 mall `IsolationGuard.fingerprintMatches:396-402` 只接受 `hostname`/`hostname:port`/端口 ⇒ mall 30/30 假红。
- 隔离档 runner `scripts/run-isolated-tests.ps1` 退出 **7**；49 个 `@Tag("it")` 用例 **0 通过**（44E/5F）。
- D-5 门禁未落地 ⇒ 3306 真库迁移在当前冻结裁决下不可执行。
- `analytics-server` 六模块真实测试数曾因 platform-app 测试会触发 Flyway→3306 而受阻（`v26-f88-isolated-chain-20260914/CONTROLLER-VERIFICATION-20260914.md` §4-8）；该条已被 `v26-real-testcounts` 的默认档 624 部分覆盖（口径关系见「测试与验收状态」）。

## 已知实现问题

普通缺陷、测试失败与实现缺口登记在此，**不升级为总控议题**。

- DEV-003：`spark-jobs` 是独立 POM，既不在 `analytics-server` reactor 内，也不在 `run-isolated-tests.ps1`（只覆盖 mall/generator）内 ⇒ 111 个 ScalaTest 与 19 个 DB 用例都没有自动执行入口。
- DEV-004（**继续归入 F-93**）：`SourceRegistryMigrationMySqlIT` **双重必败** —— `context():131-132` 同库；`EXPECTED_META_SCRIPTS:148-165` 止于 V18，而树内已有 `V19__quality_rule_definition.sql` / `V20__data_quality_result_rule_version.sql`。直接牵连 F-88：V20 迁移在仓库内**没有任何 IT 覆盖**，D-5「一次误启动即迁移 3306」目前只有静态守卫、无测试守卫。
- **P-01 MATRIX 指纹不一致（已裁决为已知证据问题）**：`docs/acceptance/v25-r01-coverage-20260914/MATRIX.md:5` 记录看板 V2.5 ＝ 122 行 / 16,593 B / sha256 `9E9CC765…73AE`；实物为 167 行 / 80,547 B / sha256 `8DDE2255AE41394BC019DDC7D8EEC72514640F90BF5073E7A3FBF5D5319208B1`（末次提交 `ad7b338`）。**旧 MATRIX 不修改**；后续建立 **append-only 勘误证据**，说明同路径看板在 MATRIX 生成以后发生过变化。
- `AnalysisGoldenMySqlIT` 库名硬编码（R-05）：黄金值依赖只在 3306 存在的历史快照 ⇒ 不可重定向到隔离库。该类经核**全类无写语句**（纯只读）。
- 隔离安全风险面（`docs/acceptance/v25-s01-it-safety-20260914/`，只报不改，R-1～R-6；本条为看板 V2.5 摘录，本次整理未逐文件复核）：`mall-simulator/src/test/resources/application-test.yml:3-4` 指向宿主 3306＋`root`＋`createDatabaseIfNotExist=true`＋Flyway enabled；`SourceRegistryMigrationMySqlIT.java:55` 默认值即正式 `analytics_meta`；`GeneratorMetaStoreTest.java:58` 用 `assumeTrue(false, …)`（skip 后 PASS）；`SparkStageExecutorSmokeTest.java:32,39,65` 无门禁且真实 `spark-submit`＋递归删目录；`scripts/run-demo.ps1:80`、`scripts/smoke-pipeline.ps1:39-42` 硬编码 `root/123456`。
- 「`V19`/`V20` 存在 ≠ 版本化已闭合」：持久化载体虽补齐，跨 run 闭环与并发发布原子性**仍未取证**；`catalog=qrc-1` 无落库承载列，目录版本只能从应用日志回查。
- `15-seed-runtime-profile.ps1` 首跑缺陷（真实教训）：PowerShell `-replace` 是**正则**替换，`'\\'→'\\\\'` 落库成 4 个反斜杠；已改字面替换并复跑，最终值与历史值逐字节相同。**首跑错误值未独立归档**（总控已采认为证据残缺项，不要求重跑）。
- `platform-common` 曾在 2026-09-14 12:06–12:38 主代码不可编译（`QualityRuleDefinition.java:84` 找不到 `isKnownSeverity`，并阻塞另两条泳道）；其后默认档复算为 `BUILD SUCCESS`，说明已可编译，但**期间修复提交未逐一取证**。
- 判据口径局限（实测发现，非文档抄录）：单看 `effective_severity` 一列无法区分「未登记码按保守默认阻断」与「已登记且声明即阻断」；可靠判据＝`severity IS NULL AND rule_version IS NULL` 组合（已写入 `QualityChecker.java:248-254` 注释）。
- `GeneratorMetaStoreTest` 之类的「skip 后 PASS」模式使 `0 failure` 不能直接读成「已验证」。

## 测试与验收状态

### 事实（实测数字，含来源）

| 口径 | 数字 | 判定 | 来源（时点） |
|---|---|---|---|
| `analytics-server` 默认档 | **624**，0F / 0E / 0S，exit 0 | 全绿 | `docs/acceptance/v26-real-testcounts-20260914/REPORT.md`＋`raw/`（2026-09-14） |
| 默认档四棵树合计 | **844** = 624（analytics-server）＋ 8（mall-simulator）＋ 101（synthetic-data-generator）＋ 111（spark-jobs） | 全绿，退出码均 0 | 同上 |
| `spark-jobs` | **111**（ScalaTest） | 通过 | 同上；**口径**：只认 `TestSuite.txt` 的 `Total number of tests run:` 与逐套件 XML，任何用 `Tests run:` 汇总该模块的做法必得 0 或漏算（裁决 R-C2） |
| 历史 `local-readiness` 口径 | 后端 **566**：564 通过、2 失败、0 错误、0 跳过；另有 分析前端 74、商城前端 6（均 0 失败） | 2 失败 | `docs/acceptance/local-readiness-20260914.md:23`；同口径见 `docs/项目完整实施指导书 V2.5.md:22`（2026-09-14） |
| 完整验收 | ❌ | 五条硬理由 | `v26-real-testcounts-20260914/CONTROLLER-VERIFICATION-20260914.md:88` |

### 证据口径说明（本轮总控裁决）

- `566 / 564 通过 + 2 失败` ＝ 历史 `local-readiness` 口径；`624 / 0F / 0E / 0S` ＝ 后续 `analytics-server` reactor 独立复算口径。**当前没有逐测试项映射**，因此**禁止合并、禁止相减后解释、禁止覆盖，也禁止声称其中一个推翻另一个**。
- 历史多个 HEAD 值（`8983616`、`6062434`、`2a10483`、`064b733`、`f26be26`、`198e0c5` 等）**属于不同取证时点**，**不回写历史证据**。当前状态**只认当前治理基线（`11919ed`）与当前代码基线（`8853730`）**。

### IT 是否真正执行（写**实际执行数量**，不以 `BUILD SUCCESS` 替代）

- 默认档：5 个 `*IT.java` **执行 0 个**；19 个 IT 用例 **0 执行、0 通过**（不在 surefire 默认 include 内）。`analytics-server/pom.xml` 内 surefire/failsafe 命中 **0 处**（本次实测）。
- 点名执行（默认档 profile）：「4 个 IT ＝ 绿构建·零执行（被跳过，退出码 0）」＋「`SparkStageExecutorSmokeIT` ＝ `Tests run: 1, Errors: 1`，exit 1」。
  - 4 个 IT 的静默跳过是 `IsolationProfileCondition:35-38` 的**设计行为**（V2.5 §9.4），**不判为门禁缺陷**（裁决 R-09）。
  - `SparkStageExecutorSmokeIT` 走 `SparkItGuard`，无配置时 `MissingConfigurationException` **硬失败**。
- 隔离档（唯一入口 `scripts/run-isolated-tests.ps1`）：exit **7**；`@Tag("it")` 共 49 个用例 **0 通过**（44 error / 5 failure）；mall 30/30 假红（DEV-002）、generator 19 个未过、写入型 IT 被 `@@version_major` 卡死（DEV-001）⇒ **0 个真实业务断言被执行**。
- 未入默认档的用例合计 **68 个**（`*IT` 19 ＋ mall `@Tag("it")` 30 ＋ generator `@Tag("it")` 19），**0 通过**（`CONTROLLER-VERIFICATION-20260914.md:92`）。
- 对外统一写法：「默认档 624 ＋ 未入默认档的 19 个 DB 用例」；**禁止**把 624 说成「全部测试」（裁决 R-C1）。

### F-88 状态（**必须拆分，不得合并**）

**正式状态：限定验收。**

**已取得的真实写侧／隔离链证据**

1. 写侧代码闭环：`8853730`（总控复核 `37561dc`）—— 四列按「声明／生效」契约落库；`warehouse-pipeline` 实测 134 全绿，含反证用例 `DataQualityGateTest#gateIgnoresBothSeverityColumns:297-333`（两列同时说谎、门禁结论不变）与 `:283-284`（`getEffectiveSeverity()` 不被读侧回填）。语义迁移，非削弱断言。
2. 真库落库：3307 上 `data_quality_result` 四列 **4/4 行非空**，含 1 行实证「声明 WARN／生效 BLOCKING」；V19/V20 真落地（`flyway_schema_history` 19 条、版本 20、`installed_on 2026-09-14 16:26:31`）。
3. 不回填：probe 库四列 `IS_NULLABLE=YES`、`COLUMN_DEFAULT=NULL`、`rows_with_version_info=0`，历史 `severity='WARN'` 3 行仍在。
4. 3306 零写入：泳道前后指纹 27 项逐项全等；总控独立只读复取 `dqr_rows=467`、`v20_cols_on_3306=0`、`max_flyway=18`、`qrd_table_on_3306=0`（即 3306 无 V19/V20 痕迹）。
5. 判定依据：「3307 上 V19/V20 真落地」＋「四列真落库」＋「3306 前后指纹全等」三条，总控已独立复现。

**仍然存在的完整验收缺口（❌，共 8 项，均属未取证；并受 DEV-001～DEV-004 相关缺口约束）**

1. 未登记码分支的真库落库（本 run 0 行；仅有单测覆盖）。
2. ADS_STAGING / PUB / MXP / MP 约 31 条规则的落库（本 run 仅 LANDING 4 条）。
3. 成功／发布路径：链路终态为 `FAILED`，未进入 PUBLISH / METRIC_PUBLISH，`GET /api/v1/metrics/overview` 返回 `data=[]`。
4. 3306 真库上 V19/V20 的执行结果（当前 3306 已冻结，须冻结解除后另行裁决）。
5. V19 的 35 条定义 ↔ 写侧 `rule_version`/`checksum` 的全量映射（仅核到本次 4 行为 v1）。
6. `catalog=qrc-1` 无落库承载列 ⇒ 无法从库内回查目录版本（只在应用日志）。
7. 3306 binlog 位点级零写入证明（未取 before 位点；`performance_schema` 为空仅为旁证）。
8. `analytics-server` 六模块真实测试数（曾阻塞于 D-5；现由默认档 624 部分覆盖，口径关系见上）。

**表述边界（总控裁决）**：正式状态保持 **限定验收**；**允许**写「写侧闭环已有实证。」；**禁止**任何「F-88 已通过完整验收」的同义表述，也禁止「规则版本化已闭合」。「写侧闭环有后续证据」≠「F-88 完整验收完成」；「链路按设计在质量闸门阻断、四列确实落库」≠「F-88 已闭合」。

## 关键证据

| 证据 | 证明什么 | 时点 |
|---|---|---|
| `docs/acceptance/v26-real-testcounts-20260914/`（`REPORT.md`＋`CONTROLLER-VERIFICATION-20260914.md`＋`raw/`，含 `raw/g1b-03-*.log`、`raw/g3-03-run-isolated-tests.log`、`raw/g4-surefire/TestSuite.txt`） | 默认档 844 实测；IT 实际执行数量；隔离档 exit 7；完整验收五条硬理由；裁决 R-01～R-12、R-C1～R-C3 | 2026-09-14 |
| `docs/acceptance/v26-f88-isolated-chain-20260914/`（`REPORT.md` 33 KB＋`CONTROLLER-VERIFICATION-20260914.md`＋`raw/` 44 文件） | 3307 上 V19/V20 落地；四列落库；V20 不回填；3306 零写入；8 项未取证；jar sha256 `86B39A59…1473` | 2026-09-14 16:2x |
| `docs/acceptance/v26-f88-writeside-20260914/`（`REPORT.md`＋`CONTROLLER-VERIFICATION-20260914.md`） | 写侧闭环 `8853730`；81/156/134 实测全绿；语义迁移非削弱 | 2026-09-14 16:19 |
| `docs/acceptance/v25-e3-isolated-chain-20260914/`（53 文件 / 377 KB） | 隔离真链限定验收；工件 sha256 `7BE717A9…7561`；仅启动期 env 覆盖 | 2026-09-14 14:22 |
| `docs/acceptance/v25-s01-it-safety-20260914/` | IT 直连正式库隐患已消除（拒绝发生在建立连接**之前**）；R-1～R-6 风险面；3306 内容指纹 `452b7223a4a2b9dd0df7f3c883cdb74b` | 2026-09-14 12:30 |
| `docs/acceptance/v25-r01-coverage-20260914/`（`MATRIX.md`＋`README.md`＋`raw/01-identity-git.txt`） | 覆盖矩阵；P-01 指纹不一致（**已裁决：旧 MATRIX 不修改，另建 append-only 勘误证据**） | 2026-09-14 12:08–12:12 |
| `docs/acceptance/local-readiness-20260914.md` | 566 / 564+2 口径；前端 74 / 商城前端 6 | 2026-09-14 |
| `docs/acceptance/v26-authority-adoption-20260914/`、`docs/acceptance/v25-document-release-20260914.md` | 权威采纳对照；V2.5 文档发布逐项 SHA-256 校验 | 2026-09-14 |
| `docs/acceptance/e4-cluster-1000-20260912/`、`m1-5-contract-sync-20260912/`、`ct-batch-20260912/`、`r9-20260911-*` | 集群 1000 事件、契约同步、批处理、历史真实 run（历史时点证据） | 2026-09-11～09-12 |
| 正式库 3306 冻结态（**本次整理未重新连库复核**）：`analytics_metric.metric_snapshot` 12 行、`metric_value` 110 行、ACTIVE 快照 `S20260901_47` version 12、内容指纹 `452b7223a4a2b9dd0df7f3c883cdb74b`、`data_quality_result` 467 行 / 14 列、`flyway` 最高 V18。**总控裁决：暂不切换；后续必须重新申请。** | 生产数据未被迁移/污染；`dqr_rows` 与 V20 头注一致 | 2026-09-14 12:30 / 16:3x |

## 待总控裁决

只放**真正需要项目级决策**的问题：

1. **F-88 完整验收的完成条件**：正式状态保持**限定验收**；完整验收何时、以 8 项未取证中的哪些为必需项判定，须在 DEV-001～DEV-004 相关缺口收敛后另行裁决。
2. **本轮文档落库确认与代码修复开工时点**：DEV-001／DEV-002 已获批准立即修复，但按总控要求「等总控确认本轮文档正式落库后，再进入代码修复」，实际开工时点待总控确认。

（已裁决、不再列为待裁决项：566/624 口径关系、历史多 HEAD 时点、P-01 MATRIX、F-88 表述边界、DEV-001/002 修复批准、DEV-004 归入 F-93、3306 冻结、生产 ACTIVE 暂不切换、治理基线提交时点。）

## 下一步建议

1. 收到总控落库确认后，立即修 DEV-001 / DEV-002（R-02 改 `SELECT VERSION()` 解析；R-03 注入 `hostname:port` 并补 generator 的 `SPRING_DATASOURCE_*`），修完复跑 `scripts/run-isolated-tests.ps1`，取「mall 30 全绿 / generator ≥14 转绿」实测。
2. 建 `spark-jobs` ＋ IT 的聚合执行入口（DEV-003），使默认档之外的 68 个用例可一条命令执行 —— 否则它们永远只会出现在证据目录里。
3. 为 P-01 建 append-only 勘误证据（不改旧 MATRIX），说明同路径看板在 MATRIX 生成后发生过变化。
4. 补 V19/V20 的 IT 覆盖（DEV-004，随 F-93）＋ `AnalysisGoldenMySqlIT` 库名参数化（R-05）＋ 唯一权威键名表（R-06）。
5. 对外一律使用「默认档 624 ＋ 未入默认档的 19 个 DB 用例」的写法，禁止「624 即全部测试」或「全量全绿」。

## 最近工作记录

（倒序；只记可回溯的事实与提交）

- 2026-09-14 19:29 ｜ `docs(status): 建立 PROJECT_STATUS 当前项目状态单一入口` ｜ 本文件首版终态：按总控裁决落实 566/624 口径说明、多 HEAD 时点说明、P-01 移入已知证据问题、F-88 表述边界（限定验收）、DEV-001／DEV-002 批准立即修复、DEV-004 归入 F-93、3306 冻结、生产 ACTIVE 暂不切换。（提交哈希见 `git log`；沿用 `5180baf` 先例，不把自指哈希写入本文件。）
- 2026-09-14 19:28 ｜ `11919ed` ｜ `docs(治理): 发布指导书 V2.8 与项目设计文档 V2.5 —— 权威索引与冲突收口` —— 仅 4 个文件（`README.md`、`docs/README.md`、`docs/毕业设计指导书 V2.8.md`、`docs/design/项目设计文档 V2.5.md`）。**自此指导书 V2.8 与项目设计文档 V2.5 为正式已发布版本，禁止再次修改。**
- 2026-09-14 19:2x ｜ 代码 Agent ｜ 创建 `docs/PROJECT_STATUS.md` 首版；采集 HEAD／工作区、两份治理基线指纹、`*IT` 与 surefire 配置实况、566/624 冲突原文、F-88 8 项未取证、P-01 与多 HEAD 冲突。未跑测试、未改代码、未动 `docs/acceptance/**`。
- 2026-09-14 18:41 ｜ `198e0c5` ｜ 治理：指导书 V2.7 与设计文档 V2.4 —— 三文件治理与写入权限。
- 2026-09-14 17:12 ｜ `d991fea` ｜ 真实测试数泳道交付 ＋ 总控独立复核裁决（844 / 624 口径来源）。
- 2026-09-14 16:38 ｜ `064b733` ｜ 总控独立复核 F-88 隔离真链（直连复取四列 / 不回填复测 / 3306 只读 / jar 成分；裁决 7 项）。
- 2026-09-14 16:36 ｜ `b26e92d` ｜ F-88 隔离真链交付（3307 上 V19/V20 落地 ＋ 四列真落库 ＋ 3306 零写入）。
- 2026-09-14 16:22 ｜ `37561dc` ｜ 总控独立复核 F-88 写侧（复跑 81/156/134 全绿；8 项待裁）。
- 2026-09-14 16:19 ｜ `8853730` ｜ `fix(dq)`：F-88 写侧闭环 —— V20 四列按「声明/生效」契约落库。
- 2026-09-14 15:52 ｜ `00a38e8` ｜ `feat(v26-s02)`：隔离档模板化落库 ＋ 环境变量唯一真相源。
- 2026-09-14 14:22 / 15:2x ｜ E3 隔离真链（L4）交付 ＋ 总控独立核验（`v25-e3-isolated-chain-20260914/`）。
- 2026-09-14 12:08–12:38 ｜ V25-R01 覆盖矩阵 ＋ V25-S01 IT 安全取证（DEV-001 / DEV-002 的首次登记来源 K-10 / K-11）。

## 历史编号勘误

- 编号空间（自本文件首版生效）：`K-xx` ＝正式设计／治理问题编号，**仅总控**可在指导书／项目设计文档中定义；`DEV-xxx` ＝本文件（代码 Agent）使用。**历史 acceptance 中的旧编号原文不回改**，也不得据历史 `K-10…K-13` 认为指导书／设计文档的 `K-xx` 号段被占用。
- 映射（本轮首次建立，**以此为准**）：

| 历史临时编号 | 现编号 | 事项 | 首次登记来源 |
|---|---|---|---|
| K-10 | **DEV-001** | MySQL 8 隔离门禁预检查失败（`TestIsolationGuard:511 SELECT @@version_major` 必败 ⇒ 安全判据 `:521/:524/:531/:534` 在真库上从未执行） | `docs/acceptance/v26-real-testcounts-20260914/CONTROLLER-VERIFICATION-20260914.md` R-02 |
| K-11 | **DEV-002** | 隔离指纹／配置词汇表不对称（runner 注入 `server_uuid`，mall 门禁不认 ⇒ 30/30 假红） | 同上 R-03 |
| K-12 | **DEV-003** | IT／`spark-jobs` 自动执行入口缺失（111 个 ScalaTest 与 19 个 DB 用例无自动入口） | 同上 R-08 |
| K-13 | **DEV-004** | 迁移 IT 与 V19/V20 覆盖缺口（`SourceRegistryMigrationMySqlIT` 双重必败；V20 迁移无 IT 覆盖）→ **继续归入 F-93** | 同上 R-04 |

- 其他勘误与口径订正：
  - 「K-08 可关闭」（R-07）：`spark-jobs` 主树实测 111/15，但任何以 `Tests run:` 汇总该模块的做法必得 0 或漏算 ⇒ 口径并入本文件「测试与验收状态」。
  - 总控自我勘误（R-09 / `CONTROLLER-VERIFICATION` §4-1）：4 个 IT「绿构建·零执行」是 `IsolationProfileCondition` 的**设计行为**，**不是**门禁失效；真正的门禁失效是 DEV-001。
  - 证据目录日期 ≠ 跑动日期 ≠ 工作树（R-C3）：`GraduationProject-wt\m3-jdk8fix` 的 09-12 旧跑动（日志 `Finished at: 2026-09-12T16:12:17`）**不得引用为当前主树值**。
  - 「首跑错误值被复跑覆盖」（`15-seed-runtime-profile.ps1`）＝已采认的证据残缺项，不得据此推断脚本当前行为。
  - 本文件中所有「未取证」条目，在取得证据前**不得**改写为通过。
