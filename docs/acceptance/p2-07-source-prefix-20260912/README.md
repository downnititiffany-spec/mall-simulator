# P2-07 源级数仓命名空间 —— 证据目录说明（raw/ 索引 ＋ 残留/未闭环清单）

> 权威文本：本目录 `RULINGS-20260912.md`（裁决 D-070…D-082 ＋ 施工单 A0–A12）、`RECON.md`（只读侦察）。
> `WORKORDER-P2-07-b.md` 是**另一条任务**的施工单（快照血缘按源解析，D-077），本轮**不做**，只在本文件 §2 登记残留。
> 本文件只做**索引与登记**，不复述、不加工结论 —— 结论一律以 `raw/` 内日志原文为准（append-only，日志不改写）。
> 落盘时 `git rev-parse --short HEAD` = **`b900aeb`**（分支 `remediation/r1-boundary`）；开工时总控给定 HEAD = `287b82c`（经 `git merge-base --is-ancestor 287b82c HEAD` 复核为祖先，exit=0），
> 期间总控推进过若干次提交（实测链 `143a0d3` F-42 → `4124d00` → `68d0fb2` → `c9b2d24` → `df9fbf1` → `b900aeb`），故"开工 HEAD"与"落盘 HEAD"不同属客观事实，非本泳道 git 操作（本泳道**无任何 git 写操作**）。

## 1 raw/ 索引

| 文件 | 是什么 | 命令（本泳道实际执行） | 退出码／关键读数 |
|---|---|---|---|
| `e0-backup.ps1` | A0 备份脚本（把全部将改文件复制到 `%TEMP%` 并逐字节校验） | `pwsh -File raw/e0-backup.ps1` | `backupVerified(byte-equal): 29/29` |
| `e0-backup-20260912-132331.log` | 上述运行的原始输出 | —— | 备份根 `C:\Users\ASUS\AppData\Local\Temp\p2-07-backup-20260912-132331`（29 文件） |
| `e1-changed-files-sha256.txt` | 改动文件 before/after SHA256 全表（37 个受控文件：改动 36、未改动 1） | `Get-FileHash -LiteralPath <path> -Algorithm SHA256` | 未改动者 = `EvidenceBuilder.java`（D-077"本轮不改"的机器证据） |
| `e1-compile.log` | E1：7 模块编译 | `mvn.cmd -o -f analytics-server\pom.xml -pl platform-app -am test-compile -Dmaven.repo.local=D:\maven_repository` | **EXIT=0**，`BUILD SUCCESS`，`Total time: 3.402 s`（增量构建，全为 `Nothing to compile - all classes are up to date`）；真实重编译行见 `e2-mvn-test-nosmoke-*.log`：`Compiling 20 source files` / `Compiling 17 source files` |
| `e2-pipeline-fast.log` | E2：4 模块（analytics-server 根＋platform-common＋connection-ingestion＋warehouse-pipeline）单测 | `mvn.cmd -o -f analytics-server\pom.xml -pl connection-ingestion,warehouse-pipeline -am test -Dmaven.repo.local=D:\maven_repository` —— **本日志未回显命令，该文本是按日志内 reactor 形态复述，不是落盘原文**；后台作业记录里同形态命令为 `-pl platform-common,connection-ingestion,warehouse-pipeline -am test`，但其 Tee 目标是 `e2-mvn-test-20260912.log`，故**不能断定**本文件源自该次运行（见 §3 第 1 条） | 模块计数 `41 / 156 / 110`，`Failures: 0, Errors: 0`，`BUILD SUCCESS`（12.561 s）；**本文件未落盘退出码（取证缺口，见 §3）** |
| `e2-mvn-test-nosmoke-20260912.log` | E2：全 7 模块单测（排除 `*SmokeTest`） | `mvn.cmd -o -f analytics-server\pom.xml test -Dtest=!*SmokeTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.repo.local=D:\maven_repository` | **EXIT=1**；`platform-app: Tests run: 107, Failures: 1, Errors: 0, Skipped: 7`，唯一失败在**跨泳道**用例（§2 第 7 条） |
| `e2-mvn-test-full-smoke-20260912-1339.log` | E2：含 smoke 的运行（证伪用） | 同上但**不排除** `*SmokeTest` | **EXIT=1**；`warehouse-pipeline` `SparkStageExecutorSmokeTest.realSparkOdlLoadsGoldenDataset:101` `[PARSE_SYNTAX_ERROR] Syntax error at or near 'USING'.(line 22, pos 8)` |
| `e2-foreign-smoke-failure-cause.md` | 上述失败的归因（跨泳道、带指纹） | —— | 归因物证：`spark-jobs/.../LocalSchemaInitJob.scala:220-229`；jar `463AF1D3…`（283971 B，builtAt 2026-09-12 13:34:35） |
| `e3-01-db-state.txt` | E3：采集期真库只读读数 | `mysql.exe -uroot -p*** -t < 只读 SELECT` | 真库 `analytics_meta`：history 16 行、`max_version=17`、`v18_rows=0`、`warehouse_prefix` 列数 0 |
| `e3-01b-db-state-after.txt` | E3：**全部证据采集完成后**的收尾复测（真库＋副本库对照） | 同上 | 真库仍 `max_version=17 / v18_rows=0 / 列数 0`（并附 `ERROR 1054 Unknown column 'warehouse_prefix'` 原文＝列不存在的第二重证据）；副本库 `analytics_meta_p207` `max_version=18 / v18_rows=1 / blank_prefix_rows=0`，列型 `varchar(24) NO NULL`，行 `mock-mall/ACTIVE/dw` |
| `e3-migration-it-on-replica.log` | E3：V18 在**只读转储副本库**上执行（`SourceRegistryMigrationMySqlIT`） | `mvn.cmd -o -f analytics-server\pom.xml -pl platform-app -am test -Dtest=SourceRegistryMigrationMySqlIT -Dsurefire.failIfNoSpecifiedTests=false -Dp1.it=true -Dp1.it.metaDb=analytics_meta_p207 -Dp1.it.metaUser=p207_meta -Dp1.it.metaPassword=p207_meta_pw_2026 -Dmaven.repo.local=D:\maven_repository`（**命令原文取自 DSH 后台作业记录**：该作业的 Tee 目标即本文件，来源唯一确定；副本库账号是临时只读转储副本账号） | **EXIT=0**（会话内读 `$LASTEXITCODE`；盘上证据只有 `BUILD SUCCESS` 与 `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`，见 §3 第 1 条）；日志原文 `Migrating schema 'analytics_meta_p207' to version "18 - source warehouse prefix"` / `Successfully applied 1 migration` |
| `e3-20-probe-source-prefix.ps1` | E3：强化后的 P1-04 隔离探针（P0 观测 + P1 缺参 + P2 带源 + P3 四类非法前缀 + P4 两源并存）；每个场景独立数仓目录，全部落在 `.gitignore` 忽略的 `analytics-server\warehouse-pipeline\tests\r6-smoke-warehouse\p2-07-source-probe\` | `pwsh -NoProfile -File raw/e3-20-probe-source-prefix.ps1 *> raw/e3-21-probe-console.log` | **PROBE_EXIT=0**，`=== 结果: 21/21 PASS ===`，`=== 观测（不断言）: 5 条 ===` |
| `e3-21-probe-console.log` | 上述运行的完整控制台原文 | —— | 含 P1 实测 `exit=2`（脚本初版断言 64 已按实测改正）、P3 四码各一次 `exit=64` 且 `0 个库`、P4 十个库目录并存 |
| `e3-22-probe-summary.json` | 探针机器可读汇总 | —— | `passed=21 total=21 jobs=9 observations=5`；`jarSha256Before == jarSha256After == 463AF1D3…`（探针**未重建** jar，`spark-jobs/**` 零改动的旁证） |
| `e2-mvn-test-20260912.log` | **说明**：本文件是总控在 `143a0d3` 提交的 4 模块日志（51669 B），非本泳道产出 | 本泳道 13:40 的 7 模块运行曾覆盖它，事后已按 HEAD 逐字节还原（`git diff --exit-code` = 0，`git hash-object --path` == `HEAD:` blob `8ed7900…`），新运行另存为上表 `e2-mvn-test-full-smoke-20260912-1339.log` | `git status` 该行虽显示 ` M`，但内容是 HEAD 原样（`core.autocrlf=true` 的换行差异，非内容改动） |

## 2 残留／未闭环清单（**不得**声称已闭环）

1. **D-073 过渡例外**：`runtime_profile.hive_database_prefix` 列**物理保留**，本轮只断读（`RuntimeProfileServiceImpl.create/update` 对非空值一律拒绝）。⇒ 不得声称"已删列"；删列（破坏性 DDL）另立任务，由总控在用户在场时分配号位。
2. **D-077 → P2-07-b（未做）**：`ai-decision/.../EvidenceBuilder.java:316` 血缘库名仍取 `namespaceProvider.current()`（身份取自快照、库名取自当前档案）。切换数据源后**历史快照血缘会显示新源的库名**，属可复现的静默错误展示。本轮**不修**，`EvidenceBuilder.java` 指纹未变即为证据；施工要求见本目录 `WORKORDER-P2-07-b.md`。
3. **F-39（在册风险，未机器强制）**：`warehouse/ddl/*.sql` ＋ `beeline --hivevar WAREHOUSE_PREFIX=<prefix>` 是与 Java/Scala 取值链**独立**的第二条注入通道，不校验形状/保留字/层后缀；`docs/deployment.md:155` 已就地标注"未机器强制"。
4. **F-41（跨组件口径分叉，本轮只登记不修）**：`spark-jobs/.../EventContract.java:17 SOURCE_SYSTEM = "mock-mall"` 为硬编码，且 `CanonicalEventSchemaParityTest:65-73` 把它钉住。A12 起平台会按**源**下发 `--sourceSystem`（本轮实测第二源传 `mall-b`），一旦真跑第二源的 odl，行内 `source_system` 与平台参数通道将分叉 ⇒ 已登记为本轮风险项，修复属 P2-01/契约侧。
5. **D-076 旧断言退役**：`docs/acceptance/p1-03-*/e3-verify.ps1:96` 的历史文本**保持原样**（append-only）；复跑该脚本时 **S12b 预期 FAIL**，新语义证据以本目录 `raw/` 为准。`JobCommandBuilderTest` 中写死"前缀来自 profile／回落 dw"的用例已**删除并改名**（delete-first，不留双真相）。
6. **D-075 块④（未批准，本轮不做）**：真链路（重建在产 jar／重启 8091／前移 ACTIVE 快照／触碰真实 `spark-warehouse/`）全部超出只读泳道权限，与 `D-032` 冲突 ⇒ 与 P2-05/P2-06 T2 合并安排。本轮出口证据只有**隔离口径**。
7. **跨泳道阻塞（本泳道外，未修）**：`spark-jobs` 工作树存在**他人未提交**改动（`LocalSchemaInitJob.scala:220-229` 的 `odsCreateTable` 把 `COMMENT` 写在 `USING` 前），已烘进共享 jar（`463AF1D3…`）⇒ 一切 `sci`（本地建表）在 Spark 3.5 报 `PARSE_SYNTAX_ERROR`。**后果**：P4"两源并存"只能取到**库目录级**证据（`dw_*` 与 `dw_b_*` 各 5 个库目录并存），**表级（`dw_b_ods` 内有表/数据）未取证**。修复不属本泳道（不得改 `spark-jobs/**`）。
8. **跨泳道测试前提破裂（本泳道外，未修）**：`landing/manifests/40.json`（1051 B，mtime 2026-09-12 09:23:15，`landing/` 被 `.gitignore:30` 忽略）使 P1-05 的 `IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate:168` 前提校验失败（`Expecting empty but was: ["40.json"]`）。本泳道 diff 内**没有任何产生清单的代码**，该文件早于本泳道 4 小时生成。

## 3 本目录已知的取证缺口（如实登记，不用推断补）

1. `e2-pipeline-fast.log` 与 `e3-migration-it-on-replica.log` 的**退出码**当时未随日志落盘（会话内读到 `EXIT=0`，但盘上只有 `BUILD SUCCESS` / 测试计数）。二者的**命令**状态不同：IT 那条的原文已由 DSH 后台作业记录补回（该作业 Tee 目标即该文件，来源唯一确定，已填进 §1 表格）；`e2-pipeline-fast.log` **既无命令回显也无作业记录可唯一对应**，表格里的命令只是按 reactor 形态复述，**不得当作原文引用**。可复核的部分（模块数、离线模式、`Tests run:` 计数、Flyway 目标 schema）均在日志内可查。E1 与探针两条命令的原文与退出码已落盘（`EXIT=0` / `PROBE_EXIT=0`）。
2. E2 全量运行中 `platform-app` 的 1 个失败**不是**本泳道引入（§2 第 8 条），但因此 **7 模块全绿未被一次性取到**：平台侧只取到"107 run / 1 foreign failure / 7 skipped"，本泳道新增的 `SourceWarehousePrefixMigrationScriptTest`（6）全绿、`SourceRegistryMigrationMySqlIT`（7）在 `-Dp1.it=true` 下独立跑绿。
3. 真链路（8091 → spark-submit → 真 `spark-warehouse/`）**未跑**：故"源级命名空间已生效"无证据（见 §4）。

## 4 复现命令（只读／隔离，可原样重跑）

```powershell
# ① 编译（E1）：期望 BUILD SUCCESS / EXIT=0
cmd /c "chcp 65001 >nul && set MAVEN_OPTS=-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1&& D:\apache-maven-3.9.14\bin\mvn.cmd -o -f analytics-server\pom.xml -pl platform-app -am test-compile -Dmaven.repo.local=D:\maven_repository"
# ② 隔离探针（E3，全部写在 gitignored 目录，不碰真数仓）：期望 PROBE_EXIT=0、21/21 PASS
pwsh -NoProfile -ExecutionPolicy Bypass -File docs\acceptance\p2-07-source-prefix-20260912\raw\e3-20-probe-source-prefix.ps1
# ③ 真库未被改动（只读）：期望 max_version=17 / v18_rows=0 / 列数=0
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -uroot -p*** -t -e "SELECT MAX(CAST(version AS UNSIGNED)) FROM analytics_meta.flyway_schema_history;"
```

## 5 本轮**禁止声称**（D-082 ＋ 本目录追加）

1. 不得声称「源级命名空间已生效／每源命名空间已闭环」；2. 不得声称 `runtime_profile.hive_database_prefix` 已删除；
3. 不得声称 `EvidenceBuilder` 已按快照解析；4. 不得声称 `beeline --hivevar` 通道已受约束；
5. 不得声称集群／百万行档已验证；6. 不得声称 V18 已在真库执行（本轮**未执行**，见 `e3-01b-db-state-after.txt`）；
7. **追加**：不得声称"两源并存的表级行为已验证"（仅库目录级已证，表级被跨泳道 DDL 回归阻塞）。
