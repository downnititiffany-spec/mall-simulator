# P2-07 源级数仓命名空间 —— 实施报告（施工单 A0–A12）

落盘 HEAD **`b900aeb`**（分支 `remediation/r1-boundary`，`git rev-parse --short HEAD` 实测；`git log --oneline -6` 实测链：`143a0d3`→`4124d00`→`68d0fb2`→`c9b2d24`→`df9fbf1`→`b900aeb`）；开工时总控给定 HEAD `287b82c`，经 `git merge-base --is-ancestor 287b82c HEAD` 复核为当前 HEAD 的祖先（exit=0）。本泳道**无任何 git 写操作**；A10 的三处文档勘误已被总控提交入库（`git status` 对这三个路径为空）。施工单＝`RULINGS-20260912.md` §二 A0–A11 ＋ 总控追加 A12；证据索引与残留清单见同目录 `README.md`。

## 1 交付清单

- **A0** 备份 29 文件 → `%TEMP%\p2-07-backup-20260912-132331`，`backupVerified(byte-equal): 29/29`（`raw/e0-backup-20260912-132331.log`）。
- **A1** 新增 `db/meta/V18__source_warehouse_prefix.sql`（49 行）：`ADD COLUMN warehouse_prefix VARCHAR(24) NULL` → 回填 `'dw'`（显式 `updated_at=updated_at`）→ `MODIFY … NOT NULL`；**不设 DEFAULT**，只动 `source_registry`。
- **A2** 增列：`SourceRegistry` ＋ 3 个 DTO（`SourceRegistryCreateReq`/`UpdateReq`/`View`）＋ `SourceRegistryMapper.lockById` ＋ `SourceRegistryController.digestOf`（幂等摘要纳入新列）。
- **A3** 三处校验挂点：`SourceRegistryServiceImpl` 的 `create`/`update`/`activate` 均过 `WarehouseNamespace.validationError`，复用冻结四码，未新写规则。
- **A4** 解析链唯一化：`WarehouseNamespaceProvider.forSource/requireSourcePrefix/requireSourceCode` ＋ 新增 `RunSourceIdentity`（源身份值对象）＋ `ActiveProfileWarehouseNamespaceProvider.current()` 经 ACTIVE 档案 `source_id` 解析。
- **A5** 提交路径：`JobCommandBuilder`（两个重载改收 `RunSourceIdentity`）、`SparkStageExecutor`、`SparkStageExecutorFactory.create(profile)`（唯一装配点）、`PlatformBeans`。**`--hiveDatabasePrefix` 参数名与语义不变 ⇒ `spark-jobs/**` 零改动**（探针采集前后 jar 指纹同为 `463AF1D3…`）。
- **A6** D-073 断读：`RuntimeProfileServiceImpl.rejectProfileHivePrefix` —— create/update 对非空旧列 `hive_database_prefix` 一律拒绝，文案指向源级字段。
- **A7** 测试：新增 `SourceWarehousePrefixGateTest`(14)、`ActiveProfileWarehouseNamespaceProviderTest`(8)、`SparkStageExecutorFactoryTest`(4)、`SourceWarehousePrefixMigrationScriptTest`(6)；`WarehouseNamespaceContractTest` 加载点改指 v2；D-076 下"前缀来自 profile/回落 dw"用例**删除并改名**（delete-first）。
- **A8** 契约 v2（总控冻结，本泳道**只读**）：`contract-specs/VERSION` = `contract-specs 2.1.0`，`contract-specs/README.md:56` 列 `warehouse-namespace.v2.json` 为 `FROZEN-2026-09-12`；v1 文件与 §10 指纹行未改。
- **A9/A11** 证据与报告：本目录 `README.md`（逐文件索引见其 §1）＋ `raw/` **15** 个文件 ＋ 本报告。
- **A10** 文档勘误：`docs/deployment.md:154-155` **就地**改为源级描述＋F-39"未机器强制"注记；`docs/thesis-materials/thesis-outline.md:31`、`docs/开发过程事实与决策记录.md:929` 追加补记（历史文本不改）。
- **A12**（总控追加）：同一 `JobCommandBuilder` 入口在 `--hiveDatabasePrefix` 旁追加 `--sourceSystem=<source_registry.source_code>`，复用既有 `sourceId` 链、**无字面量回落**、解析不出即拒绝提交。

## 2 改动文件与指纹

受控文件 **37**：改动 **36**（含新增 6）、未改动 **1**。未改动者＝`ai-decision/.../EvidenceBuilder.java`（D-077"本轮不改"的机器证据）。全表（逐文件 before/after SHA256 ＋ before 来源 ＋ 复算命令）＝`raw/e1-changed-files-sha256.txt`。抽样（8 位前缀，before → after）：
`JobCommandBuilder.java 68FF7E49→2A54834B`｜`SourceRegistryServiceImpl.java 30B84E80→D7DFCB50`｜`WarehouseNamespaceProvider.java D3131FCD→4774A997`｜`ActiveProfileWarehouseNamespaceProvider.java C9236270→30C2259D`｜`RuntimeProfileServiceImpl.java 4EDA7444→73E85641`｜`JobCommandBuilderTest.java 816BA945→507E0863`。新增 6 个（after 前缀）：`V18__source_warehouse_prefix.sql BACA5DF0`、`RunSourceIdentity.java 64CCFF49`、`SourceWarehousePrefixGateTest.java ADD10AE3`、`ActiveProfileWarehouseNamespaceProviderTest.java 951867F4`、`SourceWarehousePrefixMigrationScriptTest.java 30AF6BBD`、`SparkStageExecutorFactoryTest.java 618B8222`。

## 3 命令与真实退出码

1. **E1 编译**：`mvn.cmd -o -f analytics-server\pom.xml -pl platform-app -am test-compile -Dmaven.repo.local=D:\maven_repository` → **EXIT=0**，`[INFO] BUILD SUCCESS`，`Total time: 3.402 s`（增量；日志全为 `Nothing to compile - all classes are up to date`，真实重编译行见 §3 第 3 条日志内：`Compiling 20 source files`／`Compiling 17 source files`）。日志 `raw/e1-compile.log`。
2. **E2 4 模块**：命令形态＝`mvn.cmd -o -f analytics-server\pom.xml -pl connection-ingestion,warehouse-pipeline -am test …`（**该日志未回显命令，此文本是按 reactor 形态复述，非落盘原文**）→ `BUILD SUCCESS`，模块计数见 §4 第 2 条；**退出码未落盘**（取证缺口，`README.md` §3 第 1 条 已登记）。日志 `raw/e2-pipeline-fast.log`。
3. **E2 全 7 模块**（排除 `*SmokeTest`）：`mvn.cmd -o -f analytics-server\pom.xml test -Dtest=!*SmokeTest -Dsurefire.failIfNoSpecifiedTests=false …` → **EXIT=1**，唯一失败为**跨泳道**用例（§4 第 2 条）。日志 `raw/e2-mvn-test-nosmoke-20260912.log`。
4. **E2 含 smoke**（证伪）：同上不排除 `*SmokeTest` → **EXIT=1**，`[PARSE_SYNTAX_ERROR] Syntax error at or near 'USING'.(line 22, pos 8)`（跨泳道 DDL 回归，见 §5 第 2 条）。日志 `raw/e2-mvn-test-full-smoke-20260912-1339.log`。
5. **E3 副本库迁移**：`mvn.cmd -o -f analytics-server\pom.xml -pl platform-app -am test -Dtest=SourceRegistryMigrationMySqlIT -Dsurefire.failIfNoSpecifiedTests=false -Dp1.it=true -Dp1.it.metaDb=analytics_meta_p207 -Dp1.it.metaUser=p207_meta -Dp1.it.metaPassword=<已脱敏> -Dmaven.repo.local=D:\maven_repository`（**命令原文由 DSH 后台作业记录补回**，该作业 Tee 目标即本日志；口令字面量于 2026-09-12 13:58 由总控脱敏，原字面量曾随 `a81fde6` 进入过 `origin/main`，**历史未重写**）→ **EXIT=0**（会话内 `$LASTEXITCODE` 读数；盘上证据＝`BUILD SUCCESS`），`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`；日志原文 `Migrating schema 'analytics_meta_p207' to version "18 - source warehouse prefix"`。日志 `raw/e3-migration-it-on-replica.log`。
6. **E3 隔离探针**：`pwsh -NoProfile -ExecutionPolicy Bypass -File raw/e3-20-probe-source-prefix.ps1` → **PROBE_EXIT=0**，`=== 结果: 21/21 PASS ===`、`=== 观测（不断言）: 5 条 ===`。日志 `raw/e3-21-probe-console.log` ＋ `raw/e3-22-probe-summary.json`。
7. **E3 真库只读复核**：`mysql.exe -uroot -p*** -t`（只读 SELECT）→ 真库 `analytics_meta` `history_rows=16 / max_version=17 / v18_rows=0 / warehouse_prefix 列数=0`；副本库 `analytics_meta_p207` `max_version=18 / v18_rows=1 / blank_prefix_rows=0`，列型 `varchar(24) NO NULL`，行 `mock-mall/ACTIVE/dw`。读数 `raw/e3-01b-db-state-after.txt`。

## 4 E1/E2/E3 结论

- **E1 通过**：7 模块 `BUILD SUCCESS`，退出码 0（§3-1）。
- **E2 本泳道新增/受影响用例全绿**，原文：`[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0`（platform-common，含契约 24 ＋ 门禁 3）｜`[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0`（connection-ingestion，含 `SourceWarehousePrefixGateTest` 14 ＋ `ActiveProfileWarehouseNamespaceProviderTest` 8 ＋ `SourceRegistryServiceTest` 32）｜`[INFO] Tests run: 110, Failures: 0, Errors: 0, Skipped: 0`（warehouse-pipeline，含 `JobCommandBuilderTest` 13 ＋ `SparkStageExecutorFactoryTest` 4）｜`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in …SourceWarehousePrefixMigrationScriptTest`｜`Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 -- in …SourceRegistryControllerAuditTest`。D-081 的四项要求逐条对应：①契约 22 向量随 v2 全绿；②四类错误码在 create/update/activate **三条路径各有真实拒绝**用例（断言具体码）；③零迁移等价性（源级 `'dw'` ⇒ 五库名逐字相同）＋ 第二源 `dw_b` 不撞名；④`forSource` 含"源不存在/未绑定/前缀非法"负例。**唯一非绿**：`platform-app` `[ERROR] Tests run: 107, Failures: 1, Errors: 0, Skipped: 7`，失败＝`IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate:168 Expecting empty but was: ["40.json"]` —— 系**跨泳道**残留清单文件所致（见 §5 第 2 条与 `README.md` §2 第 7/8 条），本泳道 diff 内无任何产生清单的代码。
- **E3 通过（隔离口径）**：V18 在**只读转储副本库** `analytics_meta_p207` 上执行成功（17 行历史、`Successfully applied 1 migration`、回填 0 空值、`varchar(24) NOT NULL` 无默认值）；探针 21/21 PASS：缺 `--sourceSystem` 时 `exit=2` 且**未启动 Spark／0 个库**（fail-closed 真实链）、带源 `dw_b` 时打印 `数仓库名空间: dw_b_ods, dw_b_dwd, dw_b_dim, dw_b_dws, dw_b_ads`、四类非法前缀（`dw__b`/`default`/`dw_ods`/`DW`）各 `exit=64` ＋ 对应 `WAREHOUSE_PREFIX_*` 码 ＋ **0 个库**；P4 两源并存取到**库目录级**证据（`dw_*` 与 `dw_b_*` 各 5 个目录同根并存）。**真库 `analytics_meta` 全程只读、未执行 V18**（§3-7；另附 `ERROR 1054 Unknown column 'warehouse_prefix'` 原文）。

## 5 未取证清单（不得用推断补齐）

1. **块④真链路未跑**（D-075 未批准）：未重建在产 jar、未重启 8091、未前移 ACTIVE 快照、未触碰真实 `spark-warehouse/` ⇒ "源级命名空间已生效"**无证据**（另：总控已实测登记 **8091 自 09:31:13 起不存在**，见 `b900aeb` 补记与 `docs/开发过程事实与决策记录.md:1352`；本泳道未启停任何进程）。
2. **两源并存的表级未取证**：被跨泳道 ODS DDL 回归阻塞（`sci` 建表 `PARSE_SYNTAX_ERROR`）⇒ 只有库目录级。
3. **V18 未在真库执行**（前向效应：下次平台启动时执行）；**`beeline --hivevar` 通道未实测、无机器强制**（F-39）。
4. **集群档／百万行档未测**；本机无 HiveServer2（沿 `contract-specs/README.md` 登记的冻结边界，本轮未复测）。
5. **`metric_snapshot` 血缘按源解析未做**（D-077 → P2-07-b；`EvidenceBuilder.java` 指纹未变）。
6. **Scala 侧规格引用仍指 v1**（`WarehouseNamespaceSpec.scala:177`，属 P2-07-c）——本轮未跑 Scala 侧（`spark-jobs/**` 只读、jar 不重建）。
7. **F-41 未修**：`analytics-server/platform-common/.../EventContract.java:17 public static final String SOURCE_SYSTEM = "mock-mall";`（`mall-simulator/.../EventContract.java:10` 同值硬编码）与平台按源下发 `--sourceSystem` 的口径分叉 —— 第二源真跑 odl 时会暴露；`CanonicalEventSchemaParityTest.java:65-73` 把 `source_system` 钉死在该常量上（实测原文 `"source_system 必须锁定为 EventContract.SOURCE_SYSTEM"`），本轮只登记不改。
8. **两条 Maven 命令的退出码均未落盘**（§3 第 2/5 条；`README.md` §3 第 1 条）；其中 E2 4 模块那条**连命令原文也未留存**（表格里的文本是按 reactor 形态复述），IT 那条的命令原文已由 DSH 后台作业记录补回。

## 6 命中过的停止条件（按施工单口径）

- **命中④（需重启进程／真库 DDL／触碰真实 `spark-warehouse/`）⇒ 已遵守未越界**：探针全部落 `.gitignore` 内的隔离目录，真库只读，未启停任何进程 ⇒ 由此产生 §5 第 1/3 条的"未取证"。
- **命中②（必需改动落在 `spark-jobs/**`）⇒ 未改，改为登记＋降级取证**：`sci` 建表失败源于他人未提交的 `spark-jobs` 改动（非本泳道），本泳道 A5 硬约束未破，代价＝§5 第 2 条。
- **未命中①**（运行源身份复用 `source_registry`，未加列/表）、**未命中③**（契约 v2 已由总控冻结，本泳道只读）、**未命中⑤**（复核 `git status`：非本泳道条目仍在 —— `spark-jobs/` 14 条、`synthetic-data-generator/` 8 条、`warehouse/ddl/` 1 条、P2-01 证据 8 条均未消失）。

## 7 本轮未做／不做（与 D-082 一致）

未删 `runtime_profile.hive_database_prefix` 列、未改 `EvidenceBuilder`、未改 `contract-specs/**`、未改 `spark-jobs/**`、未做任何 git 写操作、未启停 8090/8091/8092、未对真库做 DDL、未删除任何文件、未回滚其它泳道改动。**不声称**：源级命名空间已生效/已闭环、集群或百万行档已验证、V18 已在真库执行、两源并存表级已验证、`beeline` 通道已受约束。
