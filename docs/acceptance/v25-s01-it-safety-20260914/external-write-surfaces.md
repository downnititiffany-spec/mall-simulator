# V25-S01 仓库级外部写入面扫描报告

- **扫描时间**：2026-09-14
- **HEAD**：`3fcf90e30962ae528b2079dc5c40070f1f6b7257`（`remediation/r1-boundary`）
- **扫描范围**：`analytics-server/**`（含各模块 `src/test`、`platform-app` 的 `*MySqlIT`/SmokeTests/Flyway 校验）、
  `mall-simulator/**`、`synthetic-data-generator/**`、`spark-jobs/**` 的测试触发入口、`scripts/**` 中连外部
  MySQL / Hive / HDFS 的脚本
- **扫描口径**：**不**以命名为判据——不以 `*Test` / `*IT` / 文件是否在 `src/test` 下推断安全性。
  凡出现外部连接（MySQL / Hive / HDFS / 子进程提交）且可能产生写入的入口一律登记。
- **本任务纪律**：全程零 DDL / 零 DML；正式库仅只读查询；未启停服务；未触碰集群路径。

## 判据说明

「是否隔离安全」只按**连接级事实**判定：目标**实例 + 端口**是否属隔离环境、
账号是否为该隔离库自己的受限账号、写入前是否做了范围（scope）校验。
**库名含 `test` 不构成安全证明**（用户 R2 裁决 / Q9 裁定）：把宿主正式实例上的库改名为 `xxx_test`，
实例、`datadir`、全局账号权限仍与正式库共享。

---

## 1. 写入面清单（一行一入口）

| # | 入口（`file:line`） | 外部写入？ | 当前默认 | 处置 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsMySqlIT.java:279`（URL）、`:109`（writer）、`:157,226,258,264,265`（写/删） | 是（改前写 `analytics_metric`） | **已整改**：`IsolationProfileCondition` 默认关闭；写前 `verifyBeforeWrite` | 库名来自 `context.metricDb()`；host/账号只来自 `v25.it.*`，无兜底；cleanup 先验范围再按 `snapshot_id` 删 | `raw/t0-guard-suite.log`、`raw/t1-negative-profile-host-3306.log` |
| 2 | `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/publish/MetricPublisherMySqlIT.java:432`（URL）、`:398,417,422,423`（写/删） | 是（改前写 `analytics_metric`） | **已整改**：同上 + 两条负向测试 | 同上；负向测试用 `RejectingDataSource` 证明零连接 | 同上 |
| 3 | `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/AnalysisGoldenMySqlIT.java:284`（URL）、`:80-81`（账号） | **否**（全类无 `INSERT/UPDATE/DELETE/TRUNCATE/DROP/ALTER/CREATE`，仅 SELECT） | `@EnabledIfSystemProperty(metric.it=true)`，`:65`；surefire 需显式指定 | **只报不改**（Q01 所有）。但账号面偏宽，见 §3 风险 R-2 | 本次扫描 grep 全类无变更语句 |
| 4 | `analytics-server/.../p1/.../SourceRegistryMigrationMySqlIT.java:363`（URL）、`:55`（`META_DB` 默认 `analytics_meta`） | **是**（Flyway `migrate()` 会写 schema 历史与 DDL） | `@EnabledIfSystemProperty(p1.it=true)`（`:44`）；**原扫描时** `META_DB` **默认即正式库** `analytics_meta` | **已收口**（V25-S03 R-3）：`META_DB` 默认值**已删除**，改 `requiredProperty("p1.it.metaDb")`（缺配置即 `MissingConfigurationException`，不再有正式库兜底）；`META_HOST/USER/PASSWORD` 同为正数必填、无明文；`@BeforeAll` 在**第一次 `Flyway.migrate()` 之前**先调 `verifyBeforeWrite` | `raw/r3-r5-source-scan.log`、`raw/r1b-datasource-url-accessor-probe.log`（失败关闭同型） |
| 5 | `synthetic-data-generator/.../GeneratorMetaStoreTest.java:36`（URL `127.0.0.1:3306/generator_meta`）、`:53-58` | **是**（真库真表，对 `generator_meta` 做写/删） | **原扫描时** `assumeTrue(false, ...)`（`:58`）——**不可达即跳过**（skip，非 fail-closed） | **已收口**（V25-S03 R-4）：**选"环境不具备时显式失败"**（不选 DEFERRED）。`assumeTrue` 已删除；改为 static 初始化即 `IsolationGuard.requireEnabled(...)`，配置缺失/非法一律抛 `GuardViolation`，**不再有 skip 冒充通过**；写前 `verifyBeforeWrite` | `raw/r4-generator-guard-probe.log`（exit 0，19 项全 PASS） |
| 6 | `mall-simulator/src/test/resources/application-test.yml:3-4`（URL `127.0.0.1:3306/mall_simulator_test?createDatabaseIfNotExist=true`，`username: ${MALL_DB_USER:root}`）＋ `:5-7`（Flyway `enabled: true`） | **是**（Flyway 建表 + 数据写入；`createDatabaseIfNotExist` 还会**建库**） | **原扫描时**默认配置即宿主 3306 + root + 自动建库 | **已收口**（V25-S03 R-1）：三处默认值全部移除——URL/账号/口令改为 `${MALL_ISOLATION_URL}`/`${MALL_ISOLATION_USER}`/`${MALL_ISOLATION_PASSWORD}`（**无默认值**）；`createDatabaseIfNotExist` 被门禁**无条件禁止**；Flyway 改 `enabled: ${MALL_ISOLATION_FLYWAY_ENABLED:false}`（默认不跑）；账号 root 属禁止清单 | `raw/r1-mall-guard-probe.log`（exit 0，11 项全 PASS） |
| 7 | `mall-simulator/src/test/java/.../MallTestSupport.java:81-89`（`delete(null)` 全表清空 + `DELETE FROM user_session`） | **是**（清表） | 依赖 #6 的默认数据源 | **已收口**（V25-S03 R-1）：`freshState()` 的**第一条语句**即 `MallIsolationGuard.verifyBeforeWrite(dataSource, ...)`，清表动作排在其后；`@Import(MallIsolationTestConfig.class)` 另挂了 DataSource URL 预检 + Flyway `BEFORE_MIGRATE` 回调 | `raw/r1-mall-guard-probe.log` |
| 8 | `analytics-server/warehouse-pipeline/src/test/java/.../SparkStageExecutorSmokeTest.java:32,39,45-56,65`（真实 `spark-submit` 写 `tests/r6-smoke-warehouse`，含递归建目录/删除） | **是**（本机文件系统 + 子进程） | **原扫描时无任何 gate 注解**（`:32` 注释只写「运行：mvn -pl warehouse-pipeline -Dtest=...」） | **已收口**（V25-S03 R-5）：新增 `SparkItGuard`——默认关闭（`requireEnabled` 抛出，**不提供 skip 形态**）；允许根白名单（拒仓库根/仓库一级子目录/URI/**盘根**）；runId 形状校验；**删除前先列出待删目标**，且只删 `target/v25-spark-it/<runId>/`，**不得**动 `graduation/**`。触库/触 HDFS 部分**等 W03** | `raw/r5-spark-guard-probe.log`（exit 0，14 项全 PASS） |
| 9 | `scripts/run-demo.ps1:79-80`（`mysql -uroot ... DELETE FROM mall_simulator.event_outbox`） | **是**（正式库删除） | **原扫描时**脚本直接以 root 删 `mall_simulator` 表数据 | **已收口**（V25-S03 R-6）：目标不明确即拒绝（新增 `-ConfirmCleanTarget`，缺失即不清场并打印目标）；库名白名单 `mall_simulator`（平台/生成器库拒绝）；root 拒绝；删除前先 `SELECT COUNT(*)` 预览，**预览不可得即不删除**；口令改走 `MYSQL_PWD` 不再出现在命令行 | `raw/r6e-run-demo-guard-rule-probe.log`、`raw/r6d-run-demo-reject-unclear-target.log` |
| 10 | `scripts/smoke-pipeline.ps1:39-42,50`（`mysql -uroot -p123456`） | **否**——**原扫描结论更正**：全脚本 SQL 只有 `SELECT COUNT/MAX/SUM`，无 DELETE/INSERT/UPDATE/CREATE/DROP/TRUNCATE（详见下方 R-6 说明） | **原扫描时**默认 `root` / `123456` | **已收口**（V25-S03 R-6）：口令硬编码默认值已删除（无口令即拒绝，退出码 5）；账号默认改只读 `metric_read`、root 拒绝；`-MetricDb` 加白名单（防止口径写错让"未发布"看起来像已发布）；口令经 `MYSQL_PWD` 传递 | `raw/r6a-*`、`raw/r6b-*`、`raw/r6c-*`（均退出码 5） |
| 11 | `scripts/accept-p1-baseline.ps1:26,63,74` | **否**（`:9` 声明「所有 MySQL 语句只允许 SELECT / SHOW / WITH，逐条登记并在结束时断言」） | 只读 | **无需整改**（脚本自带只读断言） | 本次只读核对 |
| 12 | `scripts/build-web-and-package.ps1:14`（`Remove-Item $StaticDir -Recurse -Force`） | 文件系统删除（**非 DB**） | 目标为构建产物目录 | **无需整改**：非外部数据写入面；已确认非仓库根 | 本次只读核对 |
| 13 | `docs/acceptance/m3-step8-parity-20260912/raw/post/export-import-rehearsal/build/run-rehearsal.ps1`＋`RehearsalRunner.java` | **是**（JDBC URL 来自 JVM 系统属性，无门禁） | 与整改前 S01 同类隐患 | **只报不改**：该目录属**已归档证据，禁止修改** | 本次只读核对 |

### 未能归类的 `jdbc:` 命中（已逐个核对，非外部写入面）

全仓 `src/test` 下 `jdbc:mysql` 命中共 15 处，除上表 #1/#2/#3/#4 外，其余全属
`TestIsolationGuard.java:530-532`（自有正则）与 `TestIsolationGuardTest.java:215-242`（**假 URL 字符串**，
仅用于断言拒绝逻辑，`urlDataSource` 的 `getConnection()` 恒抛 `SQLException`）。
即：**不存在漏网的第三个「测试直接连正式库」入口**。

---

## 2. 本任务已收口的入口（2 个）

| 入口 | 收口方式 |
| --- | --- |
| `MetricAdsMySqlIT` | 默认关闭（类级 `ExecutionCondition`）+ 写前连接级门禁 + 本次 run 自有档案 + 按 `snapshot_id` 精确清理 |
| `MetricPublisherMySqlIT` | 同上 + 两条「写入之前即失败」负向测试 |

---

## 3. 遗留风险（需其它泳道或后续任务处置）

> **状态说明（V25-S03 追加，不覆盖下面的原始扫描结论）**：R-1/R-3/R-4/R-5/R-6 已按同一门禁模式收口，
> 逐条状态与证据指针见本段每条末尾的「→ 状态」。原始扫描结论原文保留在「原扫描结论」中。

- **R-1（高危）** `mall-simulator/src/test/resources/application-test.yml:3-4`：宿主 3306 + root + `createDatabaseIfNotExist=true`。
  这是当前最直接违反「实例层面隔离」的配置。
  → **状态：已收口**。三处默认值移除；`createDatabaseIfNotExist` 门禁无条件禁；Flyway 默认 `enabled=false`；
  账号 root 入禁止清单。证据 `raw/r1-mall-guard-probe.log`（exit 0）。
  **附带发现（本轮探针跑出来的真实缺陷，已修）**：`MallIsolationGuard` 的键桥接曾把 `mysql.host`
  （`host:port` 形态）直接写成 `instancePorts`，并且桥接结果**黏住**不清理；导致端口白名单判据失效、
  且同一 JVM 内换配置后判据不更新。同时共享门禁的「3306 无条件拒绝」原先写在 `else` 分支里，
  只要配置把 3306 写进 `instancePorts` 就能静默放行正式端口。两处均已修正。
- **R-2（中）** `AnalysisGoldenMySqlIT.java:80-81`：`:81` 用 `meta_app` 连 `analytics_meta`，
  而 `meta_app` 实测在 `analytics_meta.*` 上持有 **ALL** 权限。当前 SQL 全为只读、**无实际写入风险**，
  但一旦该测试新增写语句即降级为 #4 同类隐患。另：该文件内**硬编码明文口令**，建议改为 `credentialsRef` 形态。
  → **状态：未收口（Q01 所有权）**。本轮未改动该文件；按纪律只报不改。
- **R-3（中）** `SourceRegistryMigrationMySqlIT.java:55`：`META_DB` 默认值是正式库 `analytics_meta`，
  加 `-Dp1.it=true` 即会在正式库上执行 Flyway 迁移。
  → **状态：已收口**。默认值删除、缺配置即拒跑、写前 `verifyBeforeWrite` 前置到首次 `Flyway.migrate()` 之前。
  证据 `raw/r3-r5-source-scan.log`。
- **R-4（中）** `GeneratorMetaStoreTest.java:58`：用 `assumeTrue(false, ...)` 表达不可达，
  是 §9.4 明确不接受的「skip 后 PASS」语义。
  → **状态：已收口**。**选择「环境不具备时显式失败」**（理由：本用例的判据就是"真库真表行为"，
  登记 DEFERRED 会让这条行为在用例集里彻底消失；显式失败能保证一旦有人放开开关就必须先配好隔离环境）。
  证据 `raw/r4-generator-guard-probe.log`（exit 0）。
  **附带发现 R-4b**：`GeneratorApiSmokeTest` + `MallApiGenerationSmokeTest` 用 `@SpringBootTest` +
  **默认 profile** 启动，会按 `application.yml` 连宿主 3306 / `generator_meta` / root 并经
  `GeneratorMetaStore` 写库。已 `@Import(GeneratorIsolationTestConfig.class)` 收口。
- **R-5（低）** `SparkStageExecutorSmokeTest`：无 gate，但当前不受 `-pl metric-analysis -am` 触发。
  → **状态：已收口**。`SparkItGuard` 默认关闭 + 允许根白名单 + runId 形状校验 + 删除前先列出待删目标 +
  只删本次 runId 目录。证据 `raw/r5-spark-guard-probe.log`（exit 0）。
  **附带发现（本轮探针跑出来的真实缺陷，已修）**：`runRoot()` 原先用 `requiredProperty("testRunId")`
  直接取原文——该方法只保证"配置项存在"、**不校验形状**，于是 `-Dv25.it.testRunId="../escape"`
  或含空格的值会被直接拼进目录名，"只清理本次 runId 拥有的目录"这条判据随即失去意义。
  已新增 `TestIsolationGuard.requireTestRunId()` 集中形状校验并改由它取值。
  另 `assertRootIsSafe` 对**盘根**（`D:\`，其 `getParent()` 为 null）判据失效，已显式挡住。
  **触库/触 HDFS 部分等 W03**，本轮只把门禁与"拒跑"做实。
- **R-6（低）** `scripts/run-demo.ps1:80` / `scripts/smoke-pipeline.ps1:39-42`：脚本侧写入面无门禁且硬编码 root 口令。
  → **状态：已收口，但需更正一处原始扫描事实**：
  `smoke-pipeline.ps1` **没有任何写语句**——全脚本 SQL 仅 `SELECT COUNT/MAX/SUM`
  （`:126,168,177,182` 等），不执行 DELETE/INSERT/UPDATE/CREATE/DROP/TRUNCATE。
  原扫描把「直删 `mall_simulator.event_outbox`」记在本脚本名下，实测与文件不符：那条直删在
  `run-demo.ps1`（已按 R-6 收口）。本脚本真实缺陷是 `root`/`123456` 硬编码默认值 + `-MetricDb` 无目标校验，
  三项均已整改（退出码 5 拒绝）。证据 `raw/r6a/r6b/r6c-*.log`、`raw/r6e-run-demo-guard-rule-probe.log`。

### 3.1 本轮顺带扫描到的同类明文口令（未整改·报险）

以下脚本仍有 `123456` 明文默认值，但**不在 R-6 授权范围内**（`start-all.ps1` 是启动正式部署服务，
R4 明确「不改正式部署目标」），故只报不改：

- `scripts/accept-p1-baseline.ps1:28`：`$DbPassword = '123456'`（该脚本自带只读断言，见 §1 #11）。
- `scripts/accept-three-programs.ps1:9`：`$MallDbPassword = '123456'`。
- `scripts/start-all.ps1:19`：`$env:MALL_DB_PASSWORD` 缺失时回落到 `'123456'`。
  **这条最值得后续处置**：它是启动正式服务的入口，回落明文口令会让"没配口令"静默变成"用 123456"。

### 3.2 未执行项（本轮明确不做，非遗漏）

- `scripts/it-prepare-isolation.ps1`：**已创建、但本轮不得执行**。执行门 = W03 交付 WSL 3307 实例 +
  parent 明确确认。脚本默认只打印"将创建的对象清单"就退出，须显式 `-Confirm` 才动作。
- 所有"真实隔离实例往返"（R-1/R-4 的实连判据、R-3 的 Flyway 迁移）：**等 W03 交付 3307 实例**。
  本轮取到的是"默认关闭证据 + 写前拒绝证据 + 退出码"，实连成功路径**未取证**。
- R-5 的 `spark-submit` 真实提交与 HDFS 清理：**等 W03**。
- 三个被删除的仓库根临时 classpath 文件**只在需要重跑时按命令重新生成**，当前仓库根无残留。

---

## 4. 与 V25-S02 的接口

上面 #1/#2 已改成「所有目标都从 `TestRunContext` 取、从不带默认值」。因此 S02 只需提供隔离档案
（`analytics-server/integration.local.properties`，模板见 `analytics-server/integration.local.properties.template`）
并让库侧落地到 **WSL 独立实例（端口 3307）**，两个 IT 即可在满足门禁后真正跑通。
库侧落地依赖 **L3 / V25-W02、W03** 交付，S02 中该部分标注「等 W03 交付」，**不用宿主库临时顶替**。

---

## 5. L5 复核（2026-09-14）—— 对外写入面的独立复核与新增发现

> 本节**追加**，未改动第 1–4 节任何一行。上游证据：`L5-verification-report.md`、`raw/l5-*.log`。

### 5.1 写入面「改动前 / 改动后」字面值对照（改动前取自 `git show HEAD:<path>`）

| 写入面 | 改动前（HEAD 原文） | 改动后（工作区） | 本泳道实测 |
| --- | --- | --- | --- |
| 测试库目标（mall） | `url: jdbc:mysql://127.0.0.1:3306/mall_simulator_test?createDatabaseIfNotExist=true…`；`username: ${MALL_DB_USER:root}`；`flyway.enabled: true` | `url: ${MALL_ISOLATION_URL}`（无默认）；`username: ${MALL_ISOLATION_USER}`；`flyway.enabled: ${MALL_ISOLATION_FLYWAY_ENABLED:false}` | 未提供即**在建 `DataSource` 之前**被拒（30 ERROR / 0 skipped） |
| `run-demo.ps1` 清场 | `& mysql -uroot -N -B -e "DELETE FROM mall_simulator.event_outbox; SELECT ROW_COUNT();"`（账号+库名**双写死**） | `& $MysqlExe "-u$MallDbUser" -N -B -e "DELETE FROM $MallDbName.event_outbox; …"` + 白名单 + `-ConfirmCleanTarget` + `SELECT COUNT(*)` 预览 | 真跑一次：**停在健康检查（exit 1）**，输出中 `DELETE FROM` 0 命中；**清场分支本身未取证** |
| `smoke-pipeline.ps1` 只读凭据 | `[string]$MysqlUser = 'root'` + `[string]$MysqlPassword = '123456'` | `'metric_read'` + `''`（无默认），口令走 `$env:MYSQL_PWD` 而非 `-p<口令>` | 三类拒绝**全部实测 exit 5**（库名不在白名单／无口令／账号 root） |
| `it-prepare-isolation.ps1` 隔离建库 | 新增文件 | 白名单 `@(3307)`、3306 显式拒绝、runId 形状校验、`-Confirm` 才动作、`credref:<id>` 口令引用 | **实测致命缺陷：脚本完全不可运行**（见 5.2 #1），故其建库写入面**从未被执行** |

### 5.2 本泳道新增/复确认的写入面风险（`file:line`）

| # | 风险 | 位置 | 状态 |
| --- | --- | --- | --- |
| 1 | **`it-prepare-isolation.ps1` 全线不可运行**：`[string]$Host` 与 PowerShell 只读自动变量 `$Host` 冲突，参数绑定即失败（`WriteError: 无法覆盖变量 Host…`）。4 种调用方式均失败；`-File` 下 exit 1，`-Command` 下 **exit 0 静默失败** | `scripts/it-prepare-isolation.ps1:28`（引用点 `:156`、`:157`、`:165`） | **新发现·阻塞级**：3307 隔离环境的唯一准备入口不可用 ⇒ R-1/R-4 完整验收路线被结构性阻塞；且静默失败会被 CI 误判为成功 |
| 2 | 建库/建账号阶段仍用 `-uroot`（3307 隔离实例上），与本文件 `:14-15`「绝不用 root / metric_pub / mall_app / meta_app」自述**矛盾** | `scripts/it-prepare-isolation.ps1:140` | **新发现**（待裁决：3307 上 root 是否可接受） |
| 3 | `DELETE FROM mall_simulator.event_outbox` 在 `-Clean -ConfirmCleanTarget -MallDbUser mall_app` + `$env:MALL_DB_PASSWORD` 齐备时**对正式库仍可达**（目标已显式化 + 有预览 + 有确认，但无「仅限隔离库」硬校验） | `scripts/run-demo.ps1:122` | **复确认仍存**（整改前该表实测 834,550 行） |
| 4 | 连不上库时 `Q()` 返回的 `ErrorRecord` 被强转 `[long]` ⇒ 脚本以类型转换异常**崩溃 exit 1**，而非 exit 5 的清晰拒绝 | `scripts/smoke-pipeline.ps1:137`、`:138`、`:149`、`:194` | **新发现**：连不上（含 `Access denied`）与「脚本自身 bug」现象不可区分；本泳道实测命中 |
| 5 | 生产配置未收口：`mall-simulator/src/main/resources/application.yml:8-10` 仍 `127.0.0.1:3306/mall_simulator` + `${MALL_DB_USER:root}` + `createDatabaseIfNotExist=true`（`synthetic-data-generator` 主配置同形，含 `${GENERATOR_DB_PASSWORD:123456}`） | 各模块 `src/main/resources/application.yml` | **复确认仍存**（属主配置，R-1/R-4 只加固了 test 面） |
| 6 | §3.1 列出的范围外明文残留**复确认仍在**：`accept-p1-baseline.ps1:28`、`accept-three-programs.ps1:9`、`start-all.ps1:19` | 同上 | **复核确认仍存**（本泳道只报不改） |

### 5.3 本泳道对「写前拒绝」的加强判据（新增）

即使调用方把正式端口 **3306** 显式写进配置（`-Dmall.it.mysql.host=127.0.0.1:3306`，守卫因此把 3306 读进 `allowedPorts` 并显示为 `[3306]`），**3306 仍被无条件拒绝** —— 配置**不能**把正式端口洗白（`mall-simulator/src/test/java/com/graduation/itguard/IsolationGuard.java:254-262` 的修复注释正记录此点）。

反证搜索（对全部 `raw/l5-*.log`，已排除自写注释）：`Communications link failure` / `Access denied` / `Connection refused` / `HikariPool-` / `Unknown database` 均 **0 命中** ⇒ 默认关闭与 3306 负向两轮里**没有任何连接被建立过**，「写前拒绝」不是措辞包装。

### 5.4 本泳道未取证（**不得**读作通过）

`it-prepare-isolation.ps1 -Confirm` 的真实建库/建账号/写 `credref`（脚本不可运行，且探测确认 `~\.graduation\credref-*.properties` **不存在**）；`run-demo.ps1` 的 `DELETE` 分支行为；R-1/R-4 的 `verifyBeforeWrite` 实连成功路径；`smoke-pipeline.ps1` 在凭据正确时的完整 7 步链路；`metric_read` 在正式库上的实际权限。逐项原因见 `L5-verification-report.md` §9。
