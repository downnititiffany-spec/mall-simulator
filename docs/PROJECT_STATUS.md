# PROJECT_STATUS

指导书基线：V2.8（`docs/毕业设计指导书 V2.8.md`，300 行 / 30,565 B / sha256 `00CE7AA9EAEB57A46B709A7B4D93C35CD2D282353E85532E76C6940E3344AF6F`）
项目设计基线：V2.5（`docs/design/项目设计文档 V2.5.md`，1,041 行 / 100,726 B / sha256 `400131173C2059F26A93E5B5ED75113CABAC8330E52E6B5E8ACE8650DF6A09BB`）
当前代码 commit：`db77654`（＝ `db776547f6158a540430807b8262ef12e6498171`，`fix(it-guard): close DEV-001 DEV-002 isolation guard defects`，2026-09-15；上一代码提交 `8853730` F-88 写侧闭环；当前治理基线 `11919ed` ＝ `docs(治理): 发布指导书 V2.8 与项目设计文档 V2.5 —— 权威索引与冲突收口`，2026-09-14 19:28 +0800）
工作区状态：DEV-001／DEV-002 修复已由 `db77654` 落库（代码/测试/工具 10 路径）；本文件与泳道证据 `docs/acceptance/dev001-dev002-gate-fix-20260914/` 随本次 `docs(acceptance)` 提交入库（自指哈希 EVIDENCE_COMMIT 不写入本文件，沿用 `5180baf` 先例）。见「最近工作记录」09-15 落库条与 08:52／20:44／20:18／20:03 条、`REPORT.md` §11、§12
最后更新时间：2026-09-15 10:30 +0800（**证据复现口径补记（纯证据说明 commit，不改任何结论）**：`core.autocrlf=true` ⇒ 库内 blob 行尾归一为 LF，而泳道 **50/52** 份证据入库前工作区为 CRLF（blob 比工作区少 CRLF 字节，差异合计 5,548 B，**内容差异 0**）⇒ 原 `n4-evidence-manifest.txt` 的 SHA256 是 **Windows 工作区口径**、不是跨平台 blob 口径。已在 `HASH-REPRODUCIBILITY-NOTE.md` 写明两套口径与跨平台复核规则（A：`autocrlf=true` 检出复算原清单，50/52 逐份一致；B：对 `git cat-file blob` 内容算 SHA256 并注明 blob/LF 口径），并新增第二套清单 `raw/n4-evidence-gitblob-manifest.txt`（52 行，`work_*` 与 `blob_bytes`／`blob_sha1`／`blob_sha256` 并列，**不覆盖原清单**）；往返实测 5 样本还原后 SHA256 与原清单一致，2 份 LF 原样文件（`REPORT.md`、`raw/layer4-3307-guard-facts.txt`）登记为「blob 字节 = 工作区字节」且 checkout 会写成 CRLF 的例外已登记。DEV-001／DEV-002 结论不变）。**上一动作（10:13）＝ DEV-001／DEV-002 泳道正式落库，两个 commit 保持不变（不 amend、不 force push）**：commit 1 `db77654` ＝ analytics 3 ＋ runner ＋ mall/generator `IsolationGuard.java` ×2 ＋ `IsolationGuardFingerprintTest` ×2 ＋ 两个提示脚本（10 路径，`-A` 未使用、逐路径显式 add）；commit 2 ＝ 本文件 ＋ 泳道 52 文件（`REPORT.md` ＋ `raw/` 51，含证据清单）。落库前自查：lane 全量敏感词扫描无明文口令／token／secret；862 口径经总分项复算确认（＝852 ＋ 本轮 2×5 个不带 `@Tag` 的反例单测），852 标注为 N-3 收口前口径不回改）

> 阅读口径：本文件只登记**可回溯到证据的实测事实**与**尚未裁决的冲突**；不解释、不合并、不替总控裁决。「未取证」一律显式标注，不得当作通过。
> 本文件**不设 `Vx.x` / `Sxxx` 等版本号**，始终一份，历史由 Git 保存；不得创建 `PROJECT_STATUS_V2.md`、`final.md` 等副本。
> 自 `11919ed` 起，指导书 V2.8 与项目设计文档 V2.5 为**正式已发布版本，禁止再次修改**；二者对本 Agent 只读，本文件是代码 Agent 唯一允许持续维护的状态文件。

## 当前阶段

- 阶段定位：`V2.x` 收口阶段 —— 代码主链已具规模，当前重心是**测试与验收的证据闭合**，而不是新增业务功能。
- 治理状态：三文件治理已生效。指导书 V2.8＝项目决策权威；项目设计文档 V2.5＝正式设计权威；本文件＝实际开发状态报告。
- 四级分级现状（四条判据独立，不合并）：
  - 提交完成 ✅（治理基线 `11919ed` 已入库；本文件首版提交见「最近工作记录」；本轮 DEV-001／DEV-002 修复与 N-3、语义一致性两次收口**未提交**）
  - 测试通过 ⚠️ 默认档 **632 全绿**（exit 0，本轮 20:3x 复跑逐模块与上一轮相同）；隔离档本轮 runner `exit 7`：**mall 30/30 通过、generator 19 run 中 14 通过 + `GeneratorMetaStoreTest` 5 error**（**新 runId** `dev002sem_20260914_2035` 首跑复现，见「测试与验收状态」）——5 error 已归因 **DEV-003 子项 1**（隔离库 Flyway 建表与 `GeneratorMetaStoreTest` 无编排关系，Flyway 在该类之后才执行），**指纹假红 0**（三层守卫在新判据下全部放行）
  - 限定验收 ✅（E3 隔离真链；F-88 限定验收）
  - 完整验收 ❌（`v26-real-testcounts-20260914` 五条硬理由；F-88 另有 8 项未取证，并受 DEV-003／DEV-004 相关缺口约束）

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

- 隔离门禁修复：DEV-001／DEV-002 **已修复并实测**（2026-09-14 20:03）；DEV-002 的 N-3 残留**已于 20:10–20:18 收口**（analytics 侧唯一权威身份 `hostname:port`）；DEV-002 的**语义一致性残留已于 20:28–20:44 收口**（mall／generator 侧 `fingerprintMatches` 统一为同一判据、两处陈旧提示订正、8 条反例实测）；等总控复核后提交。
- D-5「启动前门禁」：E3 脚本的两处收紧已被总控认可为基线（启动前 GATE 自检；隔离准备不读 3306、改取仓库内工件真值＋sha256），但**门禁本身未落地**（登记为 V25-S04）。
- F-88 完整验收缺口的收敛（受 DEV-001～DEV-004 约束，见「测试与验收状态」）。

## 待实现

按**证据缺口**列出（不是设计愿望清单）：

- 迁移 IT 覆盖 V19/V20（DEV-004，**继续归入 F-93** 一并整改）。
- `spark-jobs`（111 个 ScalaTest）与 19 个 DB 用例的自动执行入口（DEV-003）。
- 隔离档的 **Flyway 编排**（DEV-003 子项）：隔离库必须在跑用例**之前**完成迁移，否则新 runId 上 `GeneratorMetaStoreTest` 的 5 个裸 JDBC 用例必红（`GeneratorMetaStoreTest` 无 Spring 上下文、不触发 Flyway；本轮新 runId 首跑已复现 5E，见「已知实现问题」DEV-003 子项 1）。
- `IsolationGuardMySqlIT` 的执行入口（DEV-003 子项；否则永远只能点名跑）。
- `AnalysisGoldenMySqlIT` 库名参数化（R-05；否则该类永远不可在隔离库执行）。
- 唯一权威配置键名表（R-06：`metric.publish.username|metric.read.username` vs `metric.read.user|meta.app.user`）。
- 3306 真库 V19/V20 迁移（当前**冻结**，条件见「当前阻塞」）。
- 隔离实例遗留库／账号清理（总控已裁：F-93 完成后走 `-AllowedCleanDbs` 白名单，禁止手写 `DROP`）。
- P-01 的 append-only 勘误证据（见「已知实现问题」）。

## 当前阻塞

- **3306（总控裁决冻结）**：DEV-001／DEV-002 已修复并实测、3307 隔离真跑已复跑成功，但 **D-5 门禁仍未落地**，因此**继续不进行新的 3306 迁移、切换或写入型验收**。本轮全程 3306 零写入：DEV-001／002 轮 15 项 pre/post 判据全一致，N-3 收口轮做了只读复取（16/16 行逐行一致），**语义一致性收口轮（20:4x）再做一次只读复取，10 个判据项（实例 uuid/port/host/version、两条 Flyway max_rank、三处计数、ACTIVE 快照/版本/行数、5 张表 CHECKSUM）与 post 快照差异 0**，证据 `docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n4-3306-readonly-recheck.txt`、`raw/n3-3306-readonly-recheck.txt`。
- DEV-001（阻断级）：**已修复并实测关闭**（2026-09-14 20:03 泳道；总控 20:1x 复核通过）。旧实现 `TestIsolationGuard` 用 `SELECT @@version_major`，在宿主 3306 与 WSL 3307（均 8.0.41）上实测 `ERROR 1193 Unknown system variable 'version_major'` ⇒ 端口／账号／权限／指纹四项判据一次都不执行。现改为 `SELECT VERSION()` 解析主版本号且解析不出即 fail-closed；真 3307 上四判据全部真实执行（`docs/acceptance/dev001-dev002-gate-fix-20260914/`）。同轮附带修掉同一调用链上两处被真实授权形态暴露的误判（`GRANT USAGE ON *.*` 被当成全局非只读权限；反引号库名导致本库写权限被算成越界写权限）——总控已裁定**纳入 DEV-001，不另立编号**。**代码提交：`db77654`**（commit 1，含 `TestIsolationGuard.java`／`TestIsolationGuardTest.java`／`IsolationGuardMySqlIT.java`／`run-isolated-tests.ps1`）；**总控 2026-09-15 复核裁决：已修复并实测关闭。**
- DEV-002（阻断级）：**已修复并实测关闭**（2026-09-14 20:03 修复 ＋ 20:10–20:18 N-3 收口 ＋ **20:28–20:44 语义一致性收口**；总控裁定「主体修复通过，残留修完才可关闭」）。原为隔离指纹／配置词汇表不对称（runner 注入 `server_uuid`，而 mall/generator 侧 itguard `fingerprintMatches` 不认 server_uuid）⇒ mall 30/30 假红。修法：runner 门禁6 探针在真实例上取 `@@hostname:@@port` 作为**唯一实例身份**（现场 `dahaishui:3307`）注入 `IT_GUARD_SERVERFINGERPRINT`；generator 模块补注入 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`（其 `@SpringBootTest` 原本走主 `application.yml` 的 3306 `createDatabaseIfNotExist=true`，属真实 3306 写入风险）。**N-3 收口（20:10–20:18）**：analytics 侧 `TestIsolationGuard.fingerprintMatches` 改为 `(expected, hostname, port)`，只有登记值与实连 `@@hostname:@@port` 规范化后完全相等才通过。**语义一致性收口（20:28–20:44）**：① mall／generator 侧两份 `com.graduation.itguard.IsolationGuard` 的 `fingerprintMatches`（`:420-425`）改为与 analytics **同一判据**，两文件仍逐字节相同（sha256 前 16 位 `35006DE37D34E8D4`，459 行），失败消息（`:382`）同步改为显式列出被拒形态；② 新增同包反例集 `IsolationGuardFingerprintTest`（两模块各一份、逐字节相同，5 `@Test`／25 断言、**不带 `@Tag` ⇒ 只进默认档**）；③ `scripts/it-prepare-isolation.ps1:199` 与 `scripts/it-isolation.env.template:31-34` 的陈旧提示统一为 canonical `IT_GUARD_SERVERFINGERPRINT = hostname:port`（例 `dahaishui:3307`），并写明 `@@server_uuid` 是独立漂移事实、不是 fingerprint 替代值。`@@server_uuid` 不再作为替代合法值（登记值填 uuid 一律拒绝），但仍是独立 fail-closed 漂移事实（取不到即拒绝，且计入 `LiveFacts.sha1()`）。端口白名单（analytics `ALLOWED_INSTANCE_PORTS={3307}`；mall/generator `assertPortAllowed`／`HOST_FORMAL_PORT=3306`）**未改动**，两层约束互不兜底。**关闭口径不含 runner 级「`-Module both` 全绿」**——该绿灯被 DEV-003 子项 1 挡着（见下条）。**代码提交：`db77654`**（commit 1，含 mall／generator 两份 `IsolationGuard.java` 与两份 `IsolationGuardFingerprintTest.java`、`it-prepare-isolation.ps1`、`it-isolation.env.template`）；**总控 2026-09-15 复核裁决：已修复并实测关闭。**
- 隔离档 runner `scripts/run-isolated-tests.ps1` **语义一致性收口轮退出 7**（新 runId `dev002sem_20260914_2035`，首个 run）：**mall 30 run / 0F / 0E / 0S 通过**；**generator 19 run / 0F / 5E / 0S**，5 个 error 全在 `GeneratorMetaStoreTest`（4 张表 `doesn't exist`），非 DEV-003 的 14 例（`GeneratorApiSmokeTest` 5、`MallApiGenerationSmokeTest` 9）全绿；runner 门禁 6 探针注入的 `dahaishui:3307` 被三层守卫**全部放行 ⇒ 指纹假红 0**。5 error 的真实机制是**执行顺序**而非「Flyway 从未执行」：同一进程内 `GeneratorMetaStoreTest`（无 Spring 上下文）先跑（首次 `doesn't exist` 在日志第 56 行），Flyway 直到 `@SpringBootTest` 类才执行（第 284 行起，`20:32:18.241 Successfully applied 1 migration`，5 张业务表 `CREATE_TIME` 全为 `20:32:18`）⇒ **新 runId 首跑必红、同库二次跑才绿**，属 DEV-003 子项 1，本文件不按「已修好」记。证据 `raw/n4-isolated-mall-newrunid.log`、`raw/n4-isolated-generator-newrunid.log`、`raw/n4-generator-dev003-flyway-order.txt`、`raw/n4-newrunid-instance-facts.txt`。
- **DEV-003 子项 1／2 收口轮（2026-09-15 10:34–10:45，代码 Agent）**：上条 `exit 7` 的现场已被修掉 —— **全新 runId `dev003_20260915_1110` 首跑** `-Module all -Confirm` 三档全绿（mall 30／generator 19／analytics `IsolationGuardMySqlIT` 6，F／E／S 全 0），runner **exit 0**。generator 那 5 个 error 的机制是「Flyway 执行得太晚」而非「从未执行」，修法为**在 `GeneratorMetaStoreTest` 执行前由测试侧扩展完成 门禁 → Flyway → 表存在性自检**（不预建表、不复用旧库、不 catch「表不存在」、不跳过、不写死执行顺序）；`IsolationGuardMySqlIT` 已由 `metric-analysis` 的 `isolated-tests` profile 自动收集，并在 runner 内加了「被点名隔离类必须真跑到」的零用例硬门禁。证据 `docs/acceptance/dev003-isolated-entry-20260915/`（`REPORT.md` ＋ `raw/`）。**DEV-003 主条目的另一半（`spark-jobs` 111 个 ScalaTest 自动入口）本轮仍未做。**
- D-5 门禁未落地 ⇒ 3306 真库迁移在当前冻结裁决下不可执行。
- `analytics-server` 六模块真实测试数曾因 platform-app 测试会触发 Flyway→3306 而受阻（`v26-f88-isolated-chain-20260914/CONTROLLER-VERIFICATION-20260914.md` §4-8）；该条已被 `v26-real-testcounts` 的默认档 624 部分覆盖（口径关系见「测试与验收状态」）。

## 已知实现问题

普通缺陷、测试失败与实现缺口登记在此，**不升级为总控议题**。

- DEV-003：`spark-jobs` 是独立 POM，既不在 `analytics-server` reactor 内，也不在 `run-isolated-tests.ps1`（只覆盖 mall/generator）内 ⇒ 111 个 ScalaTest 与 19 个 DB 用例都没有自动执行入口。本轮新增两个已知子项（总控 20:1x 裁定归入 DEV-003，**不新立 DEV-005**）：
  - **子项 1（generator 隔离库建表与 `GeneratorMetaStoreTest` 无编排）**：`_generator` 库的 schema 只由 generator Spring 上下文启动时的 Flyway 创建，而 `GeneratorMetaStoreTest` 走裸 JDBC 直查表（该类只有 `@Tag("it")` ＋ 静态门禁 ＋ 自建 `DriverManagerDataSource`，**无 Spring 上下文 ⇒ 从不触发 Flyway**），同一 Maven 进程内该类**先于**所有 `@SpringBootTest` 类执行 ⇒ **新 runId 首跑必现 5 个 error**。两次实测：2026-09-14 19:57（`dev12fix_20260914_1945_generator`）与 **20:32（新 runId `dev002sem_20260914_2035_generator`）**，错误均为 `Table '…_generator.<generation_run|generation_event_stat|generator_target|generation_plan>' doesn't exist`；同一次运行内 Flyway 随后才在 `20:32:18.241` 完成 `Successfully applied 1 migration … now at version v1`（首次 `doesn't exist` 在日志第 56 行，首次 Flyway 在第 284 行）。同库二次跑则全绿（态依赖），**不得记作 DEV-003 已修**。
    - **DEV-003a（＝子项 1）状态（2026-09-15 10:3x）：已修复并实测关闭**（**总控 2026-09-15 复核裁决：DEV-003a ＝ 已修复并实测关闭**）。修法＝新增测试侧扩展 `synthetic-data-generator/src/test/java/com/graduation/itguard/IsolatedSchemaInitializer.java`（7110 B／128 行，`implements BeforeAllCallback`：`requireEnabled`／`require(url,user,password)`／`assertUrlAllowed` → `IsolationGuard.verifyBeforeWrite(ds, expectedDb, …)`（**门禁在 DDL 之前**）→ `Flyway.migrate()`（`classpath:db/generator`）→ `information_schema` 复核 5 张表），在 `GeneratorMetaStoreTest` 上 `@ExtendWith(com.graduation.itguard.IsolatedSchemaInitializer.class)`（`:74`）；**未改任何 main 源码、未改 surefire 顺序、未加 `runOrder`**。**全新 runId `dev003_20260915_1110` 首跑即绿**：首跑前取数 `_generator` 0 表／0 条 `flyway_schema_history`、`_mall` 0 表／0 条；日志 `:38` `Successfully applied 1 migration to schema … now at version v1`、`:39` `[DEV-003a] GeneratorMetaStoreTest 前置编排完成 … flyway 本次执行迁移数=1 目标版本=1`、`:40` `flyway_schema_history: rank=1 version=1 description=generator meta installed_on=2026-09-15 10:42:04 success=true`、`:46` `GeneratorMetaStoreTest Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`、`:153` 模块汇总 `19/0/0/0`；首跑后 `_generator` 6 表。修前同一缺陷在**全新 runId `dev003a_20260915_1040`** 复现：`Tests run: 19, Failures: 0, Errors: 5`、runner `exit 7`、首个 `doesn't exist` 在日志 `:56`（`generation_event_stat`）／`:67`（`generation_run`）、Flyway 直到 `:284` 才执行。证据 `docs/acceptance/dev003-isolated-entry-20260915/raw/{00-prefix-RED-generator-firstrun-no-schema-init.log,01-runner-module-all-console-dev003_20260915_1110.txt,03-isolated-instance-facts.txt,11-isolated-generator.log}`。
  - **子项 2（`IsolationGuardMySqlIT` 无自动入口）**：analytics-server 无 surefire 配置，默认档 `*IT` 执行 0 个；该 IT 只能 `-Dtest=... -Dsurefire.failIfNoSpecifiedTests=false` 点名执行，否则只是「没人跑的绿」。
    - **DEV-003b（＝子项 2）状态（2026-09-15 10:3x）：已修复并实测关闭**（**总控 2026-09-15 复核裁决：DEV-003b ＝ 已修复并实测关闭**）。修法＝`analytics-server/metric-analysis/pom.xml` 新增 `<properties><v25.it.excluded.groups>it</v25.it.excluded.groups></properties>`（`:78`）＋ surefire `<excludedGroups>${v25.it.excluded.groups}</excludedGroups>`（`:90`）＋ profile `isolated-tests`（`:100`：清空该属性（`:103`）＋ `<groups>it</groups>`（`:113`）＋ **必须显式** `<includes><include>**/*IT.java</include></includes>`（`:117`）——类名 `IsolationGuardMySqlIT` 不在 surefire 默认包含式内，缺 `includes` 即 `Tests run: 0 / BUILD SUCCESS` 假绿）；`IsolationGuardMySqlIT.java` 加 `import org.junit.jupiter.api.Tag;`（`:6`）与 `@Tag("it")`（`:47`），类 javadoc 入口段（`:35`）由「必须 `-Dtest=` 点名」改写为「隔离档自动收集」；`scripts/run-isolated-tests.ps1` 增 `-Module analytics|all`（`ValidateSet` `:69`）与 analytics 目标（`:139-147`，**复用** `${RunId}_mall`／`${RunId}_mallapp`、**不新建 3307 对象**、命令 `-f analytics-server\\pom.xml -pl metric-analysis -am test` ＋ `-Pisolated-tests`），循环内按本 runId 注入该 IT 唯一读取的 `DEV001_IT_URL/USER/PASSWORD/RUNID/FINGERPRINT`（`:312`；非 analytics 目标清除，`:319`），并加**零用例硬门禁**（`:334-342`：日志内找不到 `-- in …IsolationGuardMySqlIT` 的 `Tests run:` 行即置模块退出码 7）。实测：标准入口 `-Module all -Confirm`（**全程无 `-Dtest=`**）自动执行该 IT **6 run / 0F / 0E / 0S**，runner **exit 0**；同 reactor 内 `platform-common` 89 亦绿。**默认档不受影响**：analytics-server `mvn test` 6 模块合计 **632**（89＋156＋134＋48＋91＋114），其中 `metric-analysis` **48** 与改动前一致 ⇒ 默认档未把真库 IT 带进来（三棵树默认档 `*IT` 选中数均为 0）。证据 `raw/{12-isolated-analytics.log,20-default-analytics-reactor.log}`。
  - **DEV-003 总体状态（总控 2026-09-15 复核，以此为准）：部分收口 —— a／b 已关闭；整体尚未关闭。** 内部子项编号（**DEV-003 内部编号，不新立主编号，不碰 DEV-004／DEV-005**）：
    - **DEV-003a**：generator fresh schema／Flyway 前置初始化 → **CLOSED**
    - **DEV-003b**：`IsolationGuardMySqlIT` 接入标准 `isolated-tests` 入口 → **CLOSED**
    - **DEV-003c**：`spark-jobs` **111 个 ScalaTest 缺少项目级统一自动执行入口** → **OPEN**
    - **DEV-003d**：**其余未打标 `*IT` 尚未纳入标准自动执行入口**（含结构性必败的 `SourceRegistryMigrationMySqlIT`，其双重必败本体仍归 DEV-004） → **OPEN**
    - 读法约束：**不得**由「DEV-003a／b 已关闭」推出「DEV-003 已结束」；c／d 关闭前 DEV-003 保持开启。
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
| `analytics-server` 默认档 | **624**，0F / 0E / 0S，exit 0（**本轮修复前口径**） | 全绿 | `docs/acceptance/v26-real-testcounts-20260914/REPORT.md`＋`raw/`（2026-09-14） |
| 默认档四棵树合计 | **844** = 624（analytics-server）＋ 8（mall-simulator）＋ 101（synthetic-data-generator）＋ 111（spark-jobs）（**本轮修复前口径**） | 全绿，退出码均 0 | 同上 |
| `analytics-server` 默认档（**DEV-001／002 修复轮口径**） | **628** = 85＋156＋134＋48＋91＋114，0F / 0E / 0S，exit 0 | 全绿 | `docs/acceptance/dev001-dev002-gate-fix-20260914/raw/default-tier-analytics-server.log`（2026-09-14 20:0x）；增量全部来自 `TestIsolationGuardTest` 20 → 24 |
| 默认档四棵树合计（DEV-001／002 修复轮口径） | **848** = 628（analytics-server）＋ 8（mall-simulator）＋ 101（synthetic-data-generator）＋ 111（spark-jobs） | 全绿 | 同上 |
| `analytics-server` 默认档（**N-3 收口轮口径**） | **632** = 89＋156＋134＋48＋91＋114，0F / 0E / 0S，exit 0 | 全绿 | `docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n3-default-tier-analytics-server.log`（2026-09-14 20:10–20:18）；增量**全部**来自 `TestIsolationGuardTest` 24 → 28（platform-common 85 → 89），其余五个模块逐模块不变 |
| 默认档四棵树合计（**N-3 收口前口径，已被下一条取代，不回改**） | **852** = 632（analytics-server）＋ 8（mall-simulator）＋ 101（synthetic-data-generator）＋ 111（spark-jobs） | 全绿 | 同上；**差额 10 ＝ 语义一致性收口轮新增的 2×5 个反例单测**（mall／generator `IsolationGuardFingerprintTest` 各 5 `@Test`，不带 `@Tag` ⇒ 只进默认档；逐类原文见下两条） |
| `analytics-server` 默认档（**语义一致性收口轮口径**） | **632** = 89＋156＋134＋48＋91＋114，0F / 0E / 0S，exit 0（与 N-3 轮**逐模块相同**，本轮未改 analytics 侧任何代码） | 全绿 | `docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n4-analytics-default-tier-maven.log`（2026-09-14 20:3x） |
| `mall-simulator` 默认档（**语义一致性收口轮**） | **13**，0F / 0E / 0S，exit 0（8 → 13，+5 ＝ 新增反例集 `IsolationGuardFingerprintTest`） | 全绿 | `raw/n4-mall-default-tier-maven.log`（同上）；逐类原文：`IsolationGuardFingerprintTest 5`（:30）＋ `OrderStateMachineTest 2`（:32）＋ `GoldenDatasetTest 6`（:34），汇总行（:38）13 |
| `synthetic-data-generator` 默认档（**语义一致性收口轮**） | **106**，0F / 0E / 0S，exit 0（101 → 106，+5 ＝ 同名反例集） | 全绿 | `raw/n4-generator-default-tier-maven.log`（同上）；17 个测试类，含 `IsolationGuardFingerprintTest 5`（:73），汇总行（:77）106 |
| 默认档四棵树合计（**语义一致性收口轮，以此为准**） | **862** = 632（analytics-server）＋ 13（mall-simulator）＋ 106（synthetic-data-generator）＋ 111（spark-jobs）；**862 − 852 = 10 ＝ 本轮新增 2×5 反例单测**（总控 2026-09-15 裁决：保留 862，852 为 N-3 收口前口径） | 全绿 | 同上 |
| `analytics-server` 默认档（**DEV-003 轮口径**） | **632** = 89＋156＋134＋48＋91＋114，0F / 0E / 0S，exit 0（与语义一致性收口轮**逐模块相同**；`metric-analysis` 48 未变 ⇒ 新增 surefire 配置未改默认档选中集合） | 全绿 | `docs/acceptance/dev003-isolated-entry-20260915/raw/20-default-analytics-reactor.log`（2026-09-15 10:4x） |
| 隔离档（**DEV-003 轮，全新 runId `dev003_20260915_1110`**） | mall **30** ／ generator **19（首跑即 19，无二次跑补绿）** ／ analytics `IsolationGuardMySqlIT` **6**；F / E / S 全 0；runner `exit 0`；`spark-jobs` 111 **本轮未复跑（沿用既有口径）** | 全绿 | 同 lane `raw/{10-isolated-mall.log,11-isolated-generator.log,12-isolated-analytics.log,01-runner-module-all-console-dev003_20260915_1110.txt}` |
| `spark-jobs` | **111**（ScalaTest） | 通过 | 同上；**口径**：只认 `TestSuite.txt` 的 `Total number of tests run:` 与逐套件 XML，任何用 `Tests run:` 汇总该模块的做法必得 0 或漏算（裁决 R-C2） |
| 历史 `local-readiness` 口径 | 后端 **566**：564 通过、2 失败、0 错误、0 跳过；另有 分析前端 74、商城前端 6（均 0 失败） | 2 失败 | `docs/acceptance/local-readiness-20260914.md:23`；同口径见 `docs/项目完整实施指导书 V2.5.md:22`（2026-09-14） |
| 完整验收 | ❌ | 五条硬理由 | `v26-real-testcounts-20260914/CONTROLLER-VERIFICATION-20260914.md:88` |

**862 默认档汇总口径（必须逐项拆开写；禁止只写 `862` 而不标 111 的旧口径；禁止把 spark-jobs 111 表述成本轮 fresh 实测）**：

```text
862
= 632  analytics-server（本轮实测）
+  13  mall（本轮实测）
+ 106  generator（本轮实测）
+ 111  spark-jobs（沿用既有证据口径，本轮未复跑）
```

### 证据口径说明（本轮总控裁决）

- `566 / 564 通过 + 2 失败` ＝ 历史 `local-readiness` 口径；`624 / 0F / 0E / 0S` ＝ 后续 `analytics-server` reactor 独立复算口径。**当前没有逐测试项映射**，因此**禁止合并、禁止相减后解释、禁止覆盖，也禁止声称其中一个推翻另一个**。
- 历史多个 HEAD 值（`8983616`、`6062434`、`2a10483`、`064b733`、`f26be26`、`198e0c5` 等）**属于不同取证时点**，**不回写历史证据**。当前状态**只认当前治理基线（`11919ed`）与当前代码基线（`8853730`）**。

### IT 是否真正执行（写**实际执行数量**，不以 `BUILD SUCCESS` 替代）

- 默认档：`*IT.java` **执行 0 个**（surefire 默认 include 不含 `*IT`）。`analytics-server/pom.xml` 内 surefire/failsafe 命中 **0 处**（本次实测）。`*IT.java` **6 个**、IT 用例 **25 个**（含 `IsolationGuardMySqlIT`，DEV-001 真链证据用），默认档仍执行 0 个。
- 点名执行（默认档 profile）：「4 个 IT ＝ 绿构建·零执行（被跳过，退出码 0）」＋「`SparkStageExecutorSmokeIT` ＝ `Tests run: 1, Errors: 1`，exit 1」。
- **DEV-003b 后的新增事实（2026-09-15 10:4x）**：`IsolationGuardMySqlIT` **不再需要人工点名** —— 项目唯一隔离入口 `scripts/run-isolated-tests.ps1 -RunId <runId> -Module analytics|all -Confirm` 会自动收集并执行它（实测 **6/0/0/0**，runner exit 0），且入口在跑完后**强制核对**「该类确实被执行到」（零用例／未被选中 ⇒ 模块退出码 7），不再依赖人工记忆类名。默认档仍执行 **0** 个 `*IT`（本轮三棵树默认档 `*IT` 选中数均为 0）。
  - 历史事实不回改：`-Dtest=IsolationGuardMySqlIT` 的两次点名执行（2026-09-14 20:1x／20:3x，均 6/0F/0E/0S）仍是 DEV-001 真链的原始证据。
  - 4 个 IT 的静默跳过是 `IsolationProfileCondition:35-38` 的**设计行为**（V2.5 §9.4），**不判为门禁缺陷**（裁决 R-09）。
  - `SparkStageExecutorSmokeIT` 走 `SparkItGuard`，无配置时 `MissingConfigurationException` **硬失败**。
  - `IsolationGuardMySqlIT` **必须点名**才跑（`-Dtest=IsolationGuardMySqlIT -Dsurefire.failIfNoSpecifiedTests=false`）：2026-09-14 20:1x（N-3 收口后）与 20:3x（**语义一致性收口轮**）两次在真 3307 上点名执行，均为 **6 run / 0F / 0E / 0S**，全绿；注入的登记指纹就是 runner 探针的 `dahaishui:3307`（证据 `raw/n4-realchain-3307-maven.log`、`raw/n4-realchain-3307-IsolationGuardMySqlIT-surefire.txt`）。
- 隔离档（唯一入口 `scripts/run-isolated-tests.ps1`）：**修复前** exit 7 —— `@Tag("it")` 共 49 个用例 **0 通过**（44 error / 5 failure），mall 30/30 假红（DEV-002）、generator 19 个未过、写入型 IT 被 `@@version_major` 卡死（DEV-001）⇒ 0 个真实业务断言被执行。**DEV-001／002 修复轮** 49 用例 44 通过 / 5 error（`mall 30/0F/0E/0S` exit 0；`generator 19/0F/5E/0S` exit 1）。**N-3 收口轮** 49 用例 49 通过（**但 generator 5 例属态依赖**：隔离库表由 19:57 上一轮 Flyway 建好，本轮日志为 `No migration necessary`）、runner `exit 0`。**语义一致性收口轮（新 runId `dev002sem_20260914_2035`）** 49 用例 **44 通过 / 5 error / 0 failure**：`mall 30/0F/0E/0S` exit 0；`generator 19/0F/5E/0S` exit 1（5 error 全在 `GeneratorMetaStoreTest`，非 DEV-003 的 14 例全绿）⇒ runner `exit 7`；**指纹假红 0**（`dahaishui:3307` 被三层守卫放行）。证据：`raw/n4-runner-both-newrunid.log`、`raw/n4-isolated-mall-newrunid.log`、`raw/n4-isolated-generator-newrunid.log`、`raw/n4-generator-dev003-flyway-order.txt`、`raw/n4-newrunid-instance-facts.txt`，以及 19:57 首跑日志 `raw/layer3-generator-maven.log`、20:12 复跑 `raw/n3-layer3-generator-maven.log`。
- 未入默认档的用例合计 **74 个**（`*IT` 25 ＋ mall `@Tag("it")` 30 ＋ generator `@Tag("it")` 19），**语义一致性收口轮 50 通过**（点名的 `IsolationGuardMySqlIT` 6 ＋ mall 30 ＋ generator 14），5 error（generator `GeneratorMetaStoreTest`，DEV-003 子项 1），其余 19 未过（未执行的 `*IT`）。
- 对外统一写法：「默认档 **632**（语义一致性收口轮）＋ 四棵树 **862** ＋ 未入默认档的隔离档 49 与点名 IT 用例」；**禁止**把 624／628／632 说成「全部测试」（裁决 R-C1），**禁止**「全量全绿」；引用 generator 隔离档数字时必须写明「新 runId 首跑 14 通过 / 5 error（DEV-003 子项 1）、同库二次跑才 19 全绿」。
- **口径更新（2026-09-15 10:4x，DEV-003 子项 1／2 收口轮，以此为准）**：① 「generator 隔离档 19 例须靠同库二次跑才全绿」的说法**作废** —— 全新 runId 首跑即 `19/0/0/0`（`GeneratorMetaStoreTest` 5/0/0/0），修法与证据见「已知实现问题」DEV-003 子项 1 状态条；② 默认档 **632**（analytics）＋ **13**（mall）＋ **106**（generator）＋ **111**（spark-jobs，沿用）＝ **862**，本轮三棵树默认档与基线**逐模块一致**；③ 原「未入默认档的用例合计 **74** ＝ `*IT` 25 ＋ mall `@Tag("it")` 30 ＋ generator `@Tag("it")` 19」**总数与分类不变**，变的是**能否自动执行**：其中 analytics `IsolationGuardMySqlIT` **6 例已进入隔离档自动执行**，其余未打标 `*IT` **19 例仍无任何自动入口**（含结构性必败的 `SourceRegistryMigrationMySqlIT`，属 DEV-004）；④ **F-88 仍为限定验收**，本轮不因 DEV-003 子项收口而升级；⑤ DEV-003 **未整条闭合**：`spark-jobs` 111 个 ScalaTest 与上述 19 个 `*IT` 的自动入口仍缺。⑥ **862 汇总必须逐项拆开写**：862 ＝ 632（analytics-server，**本轮实测**）＋ 13（mall，**本轮实测**）＋ 106（generator，**本轮实测**）＋ 111（`spark-jobs`，**沿用既有证据口径，本轮未复跑，不属于本轮 fresh 实测结果**）。⑦ DEV-003 内部子项最终状态（总控 2026-09-15 复核）：**a ＝ CLOSED**（generator fresh schema／Flyway 前置初始化）、**b ＝ CLOSED**（`IsolationGuardMySqlIT` 标准 `isolated-tests` 入口）、**c ＝ OPEN**（`spark-jobs` 111 缺项目级统一自动执行入口）、**d ＝ OPEN**（其余未打标 `*IT` 未纳入标准自动执行入口）⇒ **DEV-003 部分收口，整体仍开启**。

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
8. `analytics-server` 六模块真实测试数（曾阻塞于 D-5；现由默认档 **632** 部分覆盖，口径关系见上）。

**表述边界（总控裁决）**：正式状态保持 **限定验收**；**允许**写「写侧闭环已有实证。」；**禁止**任何「F-88 已通过完整验收」的同义表述，也禁止「规则版本化已闭合」。「写侧闭环有后续证据」≠「F-88 完整验收完成」；「链路按设计在质量闸门阻断、四列确实落库」≠「F-88 已闭合」。

## 关键证据

| 证据 | 证明什么 | 时点 |
|---|---|---|
| `docs/acceptance/dev001-dev002-gate-fix-20260914/`（`REPORT.md`＋`HASH-REPRODUCIBILITY-NOTE.md`＋`raw/` **52 文件**；含 N-3 轮 `n3-*` 11 文件、语义一致性轮 `n4-*` 18 文件〔内含证据清单 `n4-evidence-manifest.txt`，逐份 sha256，其中 14 份与原始日志**逐字节相同、漂移 0**〕、第二套口径清单 `n4-evidence-gitblob-manifest.txt`〔Git blob/LF 口径，52 行，不替代原清单〕） | DEV-001／DEV-002 修复实测 ＋ DEV-002 N-3 收口 ＋ **DEV-002 语义一致性收口**：三层守卫判据统一为 canonical `hostname:port`（mall/generator 两文件逐字节相同）＋真 3307 门禁真链 6 例全绿＋单测 24→**28** 全绿＋mall 30 假红清零＋8 条强制反例实测＋**1 条真跑反例（裸 hostname 在真实链上 fail-closed，30 例全 ERROR 无 DDL/DML）**＋默认档 632／13／106 无回归＋**3306 零写入**（本轮 10 判据项差异 0） | 2026-09-14 20:03／20:10–20:18／20:28–20:44 |
| `docs/acceptance/v26-real-testcounts-20260914/`（`REPORT.md`＋`CONTROLLER-VERIFICATION-20260914.md`＋`raw/`，含 `raw/g1b-03-*.log`、`raw/g3-03-run-isolated-tests.log`、`raw/g4-surefire/TestSuite.txt`） | 默认档 844 实测；IT 实际执行数量；隔离档 exit 7；完整验收五条硬理由；裁决 R-01～R-12、R-C1～R-C3（**隔离档数字为本轮修复前的历史口径**） | 2026-09-14 |
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

1. **F-88 完整验收的完成条件**：正式状态保持**限定验收**；完整验收何时、以 8 项未取证中的哪些为必需项判定，须在 DEV-003／DEV-004 相关缺口收敛后另行裁决。
2. **DEV-003 两个新子项的处理时序**：（a）隔离库 Flyway 编排（先迁移再跑用例）；（b）`IsolationGuardMySqlIT` 接入隔离档执行入口。两者是否与「`spark-jobs`／IT 聚合入口」同批，请总控排期。
3. **模块内两处陈旧提示是否随提交一并订正**（`mall-simulator/src/test/resources/application-test.yml:32`、**入库文件** `synthetic-data-generator/src/test/resources/it-guard.local.properties:26`，两者仍写 `serverFingerprint=<@@hostname 或 host:port>`）：**本轮授权边界只到 `scripts/` 两个文件，故未改**。按新语义这两处会误导（裸 hostname 一律被拒），方向 fail-closed，不影响运行。
4. **陈旧构建产物是否清理**（`mall-simulator/target/test-classes/mall-isolation.local.properties`，gitignore 覆盖、非源码，内容仍是 L5 负向验证留下的 `enabled=true` / `serverFingerprint=127.0.0.1:3306` / `testRunId=l5probe-20260914-x1`）：本轮 B 层隔离档绿证明运行期以环境变量为准，该文件不是生效来源，且在新判据下双重被拒（裸地址 ＋ 3306）；`target/` 不在本轮边界，未清理。

（已裁决、不再列为待裁决项：566/624 口径关系、历史多 HEAD 时点、P-01 MATRIX、F-88 表述边界、DEV-001/002 修复批准、**DEV-002 语义一致性收口批准（含 mall/generator 判据收紧与两处 `scripts/` 提示订正）**、DEV-004 归入 F-93、3306 冻结、生产 ACTIVE 暂不切换、治理基线提交时点。）

## 下一步建议

1. ~~修 DEV-001 / DEV-002~~**已完成并实测关闭**（2026-09-14 20:03 修复 ＋ 20:10–20:18 N-3 收口 ＋ 20:28–20:44 语义一致性收口）：`SELECT VERSION()` 解析 ＋ 唯一实例身份 `dahaishui:3307`（三处守卫同一判据 `fingerprintMatches(expected, hostname, port)`，uuid 不可替代；裸 hostname／裸端口／`127.0.0.1:port`／`localhost:port` 一律拒）＋ generator `SPRING_DATASOURCE_*` ＋ 8 条反例集。下一步：把隔离库 Flyway 迁移纳入隔离档编排（DEV-003 子项 1），使**新 runId** 上 generator 5 例不再依赖上一轮的残留 schema。
2. 建 `spark-jobs` ＋ IT 的聚合执行入口（DEV-003），使默认档之外的 74 个用例可一条命令执行 —— 否则它们永远只会出现在证据目录里；新增的真链 IT（`IsolationGuardMySqlIT`）也应在这一步接入 `-Pisolated-tests`。
3. 为 P-01 建 append-only 勘误证据（不改旧 MATRIX），说明同路径看板在 MATRIX 生成后发生过变化。
4. 补 V19/V20 的 IT 覆盖（DEV-004，随 F-93）＋ `AnalysisGoldenMySqlIT` 库名参数化（R-05）＋ 唯一权威键名表（R-06）；并按总控裁定决定是否订正模块内两处陈旧提示（见「待总控裁决」第 3 条）。
5. 对外一律使用「默认档 **632** ＋ 四棵树 **862** ＋ 未入默认档的隔离档 49 ／ 点名 IT 用例」的写法，禁止「624／628／632 即全部测试」或「全量全绿」；引用 generator 隔离档数字必须写明「新 runId 首跑 14 通过 / 5 error（DEV-003 子项 1），同库二次跑才 19 全绿」。

## 最近工作记录

（倒序；只记可回溯的事实与提交）

- 2026-09-15 10:30 ｜ 代码 Agent ｜ **证据复现口径补记（纯证据说明 commit，不改任何测试／验收结论）**：`core.autocrlf=true` 且仓库无 `.gitattributes` ⇒ 库内 blob 行尾归一为 **LF**；泳道 52 文件中 **50 份**入库前工作区为 **CRLF** ⇒ blob 字节比工作区少 CRLF 字节（差异合计 **5,548 B**，**内容差异 0**、行一一对应），**2 份**（`REPORT.md`、`raw/layer4-3307-guard-facts.txt`）blob 字节 = 工作区字节。因此原 `raw/n4-evidence-manifest.txt` 的 SHA256 是 **Windows 工作区（`autocrlf=true` 检出）口径**，**不是跨平台 Git blob 口径**。新增：① `HASH-REPRODUCIBILITY-NOTE.md`（写明两套口径、跨平台复核规则 A／B〔A：在 `autocrlf=true` 检出复算原清单，本泳道 50/52 逐份应一致；B：对 `git cat-file blob` 内容算 SHA256 或取 `git rev-parse` 对象 id 并注明「Git blob/LF 口径」，不得与原 worktree 哈希混用〕、以及不得仅凭哈希不等判定证据被篡改）；② `raw/n4-evidence-gitblob-manifest.txt`（第二套清单，52 行，`work_bytes`／`work_sha256` 与 `blob_bytes`／`blob_sha1`／`blob_sha256` 并列，基线 `ffbd996`，**不覆盖原清单**）。往返实测（删除工作区文件后 `git checkout -- <path>` 还原）5 个样本 SHA256 与原清单逐份一致：`n4-3306-readonly-recheck.txt` `F9363A01…C3EDBA`、`n4-mall-default-tier-maven.log` `EF0C31D8…4C7494`、`n4-isolated-mall-newrunid.log` `43263CA1…2035DDE`、`n4-isolated-generator-newrunid.log` `F8BBE025…EB0844`、`n4-3307-inventory-before.txt` `1267BDAA…9F87EC`。**实测发现的例外（已登记）**：LF 原样文件会被 `autocrlf=true` 的 checkout 写成 CRLF（`REPORT.md` 47,289 B／`B9C9C47D…`），故这 2 份须按 B 口径复核；该副作用**已还原**为 46,872 B／`6D53F5A5A51135615625BE1B56B580CAE15595C5ECC2AD697BF11E731579D29027`（blob id `ea1bf615…` 前后未变、`git diff` 为空、stat 刷新后状态干净），还原后 **52/52** 工作区字节与登记值复核一致。**未改动**：原 `REPORT.md`、原 `n4-evidence-manifest.txt`、其余 `raw/**`、任何代码、runner、指导书 V2.8、项目设计文档 V2.5。**DEV-001／DEV-002 结论不变。**
- 2026-09-15 10:34–10:45 ｜ 代码 Agent（**已按总控批准分两个 commit 落库**：CODE_COMMIT `deba29f`（5 个代码/配置路径）＋ EVIDENCE_COMMIT（本文件与泳道 16 文件）；`git status --porcelain` 落库后 0 行）｜ **DEV-003 子项 1／2 收口轮（本轮唯一动作，5 路径：4 改 1 新）**：① **DEV-003a（generator 首跑 schema 编排）** —— 修前先在全新 runId `dev003a_20260915_1040` 复现红案（`19 run / 5 error`、runner exit 7、首个 `doesn't exist` 日志 `:56`、Flyway 直到 `:284` 才跑 ⇒ 归因「执行得太晚」）；修法＝新增 `src/test/java/com/graduation/itguard/IsolatedSchemaInitializer.java`（`BeforeAllCallback`：门禁 → Flyway → 表存在性自检，**不预建表／不复用旧库／不 catch／不跳过／不写死顺序**）＋ `GeneratorMetaStoreTest` 上 `@ExtendWith`（`:74`）＋ DEV-003a 说明（`:57`）；**未改 main 源码**。② **DEV-003b（analytics 真库 IT 接入统一入口）** —— `metric-analysis/pom.xml` 加 `<properties>v25.it.excluded.groups=it</properties>`（`:78`）＋ surefire `excludedGroups`（`:90`）＋ profile `isolated-tests`（`:100`，`<groups>it</groups>`（`:113`）＋ `<includes>**/*IT.java</includes>`（`:117`））；`IsolationGuardMySqlIT.java` 加 `@Tag("it")`（`:47`）与入口 javadoc（`:35`）；`scripts/run-isolated-tests.ps1` 加 `-Module analytics|all`、analytics 目标（复用 `${RunId}_mall`／`${RunId}_mallapp`）、`DEV001_IT_*` 注入（`:312`）与**零用例硬门禁**（`:334-342`）。③ **实测（全新 runId `dev003_20260915_1110`，pre 取数两库均 0 表／0 历史）**：标准入口 `-Module all -Confirm`（**无 `-Dtest=`**）三档全绿 —— mall **30**、generator **19**（首跑即 19，`GeneratorMetaStoreTest` 5/0/0/0）、analytics `IsolationGuardMySqlIT` **6**，F／E／S 全 0，runner **exit 0**；首跑后 `_generator` 6 表、`flyway_schema_history` ＝ `1 | generator meta | 2026-09-15 10:42:04 | success 1`。④ **回归（默认档）**：analytics-server **632**（89＋156＋134＋48＋91＋114，逐模块与上一轮一致）、generator **106**、mall **13**，退出码均 0，三棵树默认档 `*IT` 选中数 0；`spark-jobs` **111 本轮未复跑（沿用既有口径）**。⑤ 3306 **全程只读、零写入**；未新建 `.github/workflows/**`、未接 CI；未触碰 `contract-specs/**`、已有 `docs/acceptance/**` 其它泳道、指导书 V2.8、设计 V2.5。⑥ 证据：`docs/acceptance/dev003-isolated-entry-20260915/`（`REPORT.md` ＋ `raw/` 12 文件，含 `evidence-manifest.txt` 与 `05-sensitive-scan.txt`：**明文口令 0 命中**）。⑦ 自查中当场修掉的自身缺陷 3 处（锚点手抄 0 匹配／多写一个 `}` 致 `Parser` 报错／`-Module all` 初版漏选 mall+generator），均已在正式取证前修掉并复检。**DEV-003 主条目（`spark-jobs` 111 自动入口）与 19 个未打标 `*IT` 仍未闭合；F-88 仍为限定验收。总控 2026-09-15 复核裁决：DEV-003a／b 已关闭，DEV-003c（`spark-jobs` 111 自动入口）／DEV-003d（其余未打标 `*IT`）登记为 OPEN，DEV-003 总体保持开启。**
- 2026-09-15 10:13 ｜ 代码 Agent ｜ **DEV-001／DEV-002 泳道正式落库（两个 commit，`git add -A` 未使用、逐路径显式 add）**：**commit 1 ＝ `db77654`**（`fix(it-guard): close DEV-001 DEV-002 isolation guard defects`，10 路径／＋1102 −50：analytics `TestIsolationGuard.java`＋`TestIsolationGuardTest.java`＋新增 `IsolationGuardMySqlIT.java`、`scripts/run-isolated-tests.ps1`、mall／generator 两份 `IsolationGuard.java`、mall／generator 两份新增 `IsolationGuardFingerprintTest.java`、`scripts/it-prepare-isolation.ps1`、`scripts/it-isolation.env.template`）；**commit 2 ＝ 本文件 ＋ `docs/acceptance/dev001-dev002-gate-fix-20260914/**`（52 文件）**。落库前自查：① lane **52 文件全量**敏感词扫描（`password`／`passwd`／`pwd=`／`MYSQL_PWD`／`Authorization`／`Bearer`／`token`／`secret`／`123456`／`apiKey`／`access_key`／私钥头 等）——**无明文口令／token／secret，无需脱敏、无需改标 [脱敏副本]**（命中仅掩码形态 `<3******…长度 24>`、引用名 `credref:…`、权限名 `APPLICATION_PASSWORD_ADMIN`、JVM 属性）；② `git check-ignore` lane 内**无一被忽略**；③ 「862」经总分项复算确认非笔误（＝852 ＋ 本轮 2×5 个不带 `@Tag` 的反例单测，逐类原文已登记入「测试与验收状态」），总控裁决保留 862、852 标为 N-3 收口前口径不回改；④ 两个 commit 后 `git status --porcelain` 为 **0 行**，随后 push 到 `origin/main`。**总控 2026-09-15 复核裁决：DEV-001 已修复并实测关闭；DEV-002 已修复并实测关闭；DEV-003 ＝ 下一轮主任务；F-88 继续限定验收。**
- 2026-09-15 08:52–08:55 ｜ 代码 Agent（**未提交、未 push**）｜ **证据完整性自查（只补证据、不改结论）**：① `raw/n4-*` **18 文件**中 14 份原样入库日志与 `%TEMP%\dev002n4-logs` 逐份 `Get-FileHash -Algorithm SHA256` 比对 —— **14/14 相同、漂移 0**；`n4-3306-readonly-recheck.txt` 恢复为机器输出原样（433 B，sha `F9363A01…C3EDBA`），09-14 追写的比对结论行**已撤回**，结论移入清单与 `REPORT.md` §12.10。② 新增 `raw/n4-evidence-manifest.txt`：逐份「文件名｜字节｜sha256｜入库方式（[原样]/[节选]/[生成]）」，并声明原始负例日志 871,033 B 未入库、入库的是节选。③ **报告数字 vs 原始证据逐条回查 25 项判据＝24 PASS / 1 FAIL**，FAIL 是**匹配式写错**而非结论错：多模块 `mvn test` **没有 reactor 级汇总行**，「632」只能由 6 条逐模块 `Results:` 汇总行相加得到 —— 已复算 `89＋156＋134＋48＋91＋114＝632`（F/E/S 全 0），且与 N-3 轮日志 `Compare-Object` **逐模块差异 0**。④ 未取证项**不升级**（8 条反例中 7 条仅单测级；跨库写权限未在真 3307 实测；`IsolationGuardMySqlIT` 无标准入口；`spark-jobs` 111 未复跑）。修改文件：`docs/PROJECT_STATUS.md`、`REPORT.md` §12.10、`raw/n4-evidence-manifest.txt`（新增）。
- 2026-09-14 20:28–20:44 ｜ 代码 Agent（**未提交**）｜ **DEV-002 语义一致性收口（本轮唯一动作，6 文件：4 改 2 新增）**：① mall／generator 两份 `com.graduation.itguard.IsolationGuard.fingerprintMatches`（`:420-425`）由「`hostname` 或 `hostname:port` 或裸 `port`／`127.0.0.1:port`／`localhost:port`」收紧为**与 analytics 侧同一判据**——登记值与实连 `@@hostname:@@port` 规范化（trim ＋ hostname 段忽略大小写）后必须完全相等，缺值／空值／`port<=0` 一律 fail-closed；两文件仍**逐字节相同**（sha256 前 16 位 `35006DE37D34E8D4`，459 行），失败消息（`:382`）与文档头（`:39`）同步订正；端口白名单未动（两层互不兜底）。② 新增同包反例集 `IsolationGuardFingerprintTest`（mall／generator 各一份、逐字节相同、5 `@Test`／25 断言、**不带 `@Tag` ⇒ 只进默认档，不污染隔离档 30／19 口径**）。③ `scripts/it-prepare-isolation.ps1:199` 与 `scripts/it-isolation.env.template:31-34` 陈旧提示统一为 canonical `IT_GUARD_SERVERFINGERPRINT = hostname:port`（例 `dahaishui:3307`），并写明 `@@server_uuid` 是门禁 6 的独立漂移事实、**不是** fingerprint 替代值；两文件 `Parser` 解析错误数 0。**实测**：`TestIsolationGuardTest` **28/0F/0E/0S**；新 runId `dev002sem_20260914_2035` 隔离档 mall **30/0F/0E/0S** exit 0、generator **19 run / 0F / 5E / 0S**（5 error 全在 `GeneratorMetaStoreTest`，非 DEV-003 的 14 例全绿）⇒ runner `exit 7`，**指纹假红 0**；真 3307 真链 `IsolationGuardMySqlIT` **6/0F/0E/0S**；默认档 analytics-server **632/0F/0E/0S**（逐模块与 N-3 轮相同）＋ mall **13**（8→13）＋ generator **106**（101→106）；8 条强制反例逐条实测通过 ＋ **真跑反例**（`IT_GUARD_SERVERFINGERPRINT=dahaishui` 裸 hostname 在真实链上 `[flyway-before-migrate]` 即 fail-closed，30 例全 ERROR、无一条进入 DDL/DML）；3306 只读复取 **10 判据项差异 0**。**DEV-003 归因修正**：新 runId 首跑 5E 的机制不是「Flyway 未执行」，而是「Flyway 执行得太晚」——`GeneratorMetaStoreTest`（无 Spring 上下文）先跑（首次 `doesn't exist` 日志第 56 行），Flyway 直到 `@SpringBootTest` 才跑（第 284 行起，`20:32:18.241` 完成迁移，5 张表 `CREATE_TIME` 全为 20:32:18）⇒ 首跑必红、同库二次跑才绿，归 **DEV-003 子项 1**。证据：`docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n4-*`（18 文件，含证据清单 `n4-evidence-manifest.txt`）＋ `REPORT.md` §12（含 §12.10 证据完整性自查，**09-15 08:5x 补记**）。**未提交、未 push。**
- 2026-09-14 20:10–20:18 ｜ 代码 Agent（**未提交**）｜ **DEV-002 N-3 收口（2 文件）**：`TestIsolationGuard.fingerprintMatches` 由 `(expected, serverUuid, hostname)` 改为 `(expected, hostname, port)` —— 唯一权威实例身份固定为 `@@hostname:@@port`，规范化（trim ＋ hostname 段忽略大小写）后必须完全相等；**`@@server_uuid` 不再是替代合法值**（登记值填 uuid、即便实际 uuid 逐字相同也拒绝），改由 `requireServerUuid()` 作独立 fail-closed 漂移事实（取不到即拒；仍计入 `LiveFacts.sha1()`，实测 3307 的 `fingerprintSha1=f228772c…` 与本轮前一致）。端口白名单未动。新增/改写单测：`TestIsolationGuardTest` 24 → **28**（含四条强制反例：同机 3306／异机 3307／uuid 冒充指纹／缺 uuid fail-closed，及「端口门禁与指纹门禁两层独立」用例）。复跑：单测 **28/0F/0E/0S**；mall 隔离档 **30/0F/0E/0S**；generator 隔离档 **19/0F/0E/0S**、runner **exit 0**（**态依赖**：隔离库表为 19:57 上一轮 Flyway 所建，本轮 `No migration necessary`，新 runId 仍会 5 error）；真 3307 真链 **6/0F/0E/0S**；默认档 **632/0F/0E/0S**（628＋4，全部来自 platform-common 85→89）；3306 只读复取 **16/16 行与 post 快照逐行一致**。证据：`docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n3-*`（11 文件）＋ `REPORT.md` §11。**未提交、未 push。**
- 2026-09-14 20:03 ｜ 代码 Agent（**未提交**）｜ **DEV-001／DEV-002 门禁修复泳道交付**：改 3 文件（`TestIsolationGuard.java`、`TestIsolationGuardTest.java`、`scripts/run-isolated-tests.ps1`）＋ 新增 1 文件（`analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/IsolationGuardMySqlIT.java`）。实测：单测 **24/0F/0E/0S**；mall 隔离档 **30/0F/0E/0S**（30 个假红清零，exit 0）；generator 隔离档 **19/0F/5E/0S**（14 转绿，5 个 `GeneratorMetaStoreTest` 因隔离库表不存在）；真 3307 门禁真链 **6/0F/0E/0S**（四类判据真执行；旧写法 1193 现场复现；3306 URL 与禁止库在建连前即拒）；默认档回归 **628/0F/0E/0S**（624＋4 个新单测）；**3306 零写入**（15 项 pre/post 判据全一致）。证据：`docs/acceptance/dev001-dev002-gate-fix-20260914/`（`REPORT.md`＋`raw/`）。未取证项与越界项已显式登记（真库跨库写权限反例未测；两侧指纹词表仍两套；DEV-001b/001c 属越界附带修复待裁）。（总控 20:1x 复核：DEV-001 通过可关闭；DEV-001b/001c 纳入 DEV-001；DEV-002 待 N-3 收口。）
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
