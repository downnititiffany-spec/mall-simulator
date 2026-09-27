# BATCH-G31-09 · checkpoint 文件身份列宽修复（D-041 / V32）

- 日期：2026-09-25（批次窗口）
- 裁决依据：D-044④「两个短批次：G31-08 先修同日增量写入（已收口，D-045），再 G31-09 修 checkpoint 列宽与索引约束」；判据 = **同一 HDFS 文件重试不重读**。
- 缺陷登记：D-041 —— `file_checkpoint.file_identity VARCHAR(64)`（V8:17）装不下 HDFS 身份
  `hdfs:MD5-of-0MD5-of-512CRC32C:<64hex>`（≈95 字符，`HdfsLandingStorage.fileIdentity`）→
  checkpoint INSERT 抛 MysqlDataTruncation → 不落断点行 → 同一 HDFS 文件重扫时全量重读（幂等失效）。
  LOCAL 身份 = 创建时间戳毫秒（短串），不受影响。
- 前序：G31-08（D-045，同日增量写入合并语义）已收口。本批次只修存储宽度，**不改任何产品代码逻辑**。

## 1. 判据与证据边界
- 行为级「同一文件重试不重读」的判定逻辑在 `LocalFileIngestor.ingestFile`
  （identity 相同且 `startOffset >= size` → 返回 0 行；`upsertCheckpoint` 为 insert-or-update 同一行），
  已有单测钉住，本批次不触碰产品代码。
- 本批次补的是**存储层前提**：95 字符 HDFS 身份能原样落库/回读、唯一键 `uk_ckpt_source`
  语义不变、UPDATE 换身份（重放/新文件版本路径）可用。
- 证据边界：isolated 档 per-run 隔离库（3307，`${RunId}_analytics_meta`）≠ 正式 analytics_meta；
  冻结 3306 零接触；正式库的 V32 应用归 D-044⑤ 合并重跑窗口，本批次不执行。

## 2. 改动面（追加式 / 测试面，共 5 文件）
| 文件 | 动作 | 说明 |
|---|---|---|
| `db/meta/V32__file_checkpoint_identity_width.sql` | 新增 | 单条 `MODIFY COLUMN file_identity VARCHAR(255) NOT NULL DEFAULT ''`；头注释含缺陷、字节预算、回滚说明；不指定 ALGORITHM（V17 先例）；不 DROP/ADD 索引（MODIFY 自动维护所属键） |
| `platform-app/.../migration/FileCheckpointIdentityWidthMigrationScriptTest.java` | 新增 | 静态门禁：号位唯一且 V31 前置、单列加宽形态、禁删改清单、已发布 V8/V17 零改动（追加式纪律） |
| `platform-app/.../isolation/AnalyticsIsolationFlywayIT.java` | 加 1 个 @Test | 95 字符身份插入/回读/唯一键拒绝重复/UPDATE 换身份/own-row 清理；列宽以 information_schema 实测 |
| `platform-app/.../source/SourceRegistryMigrationMySqlIT.java` | 同步 | `EXPECTED_META_SCRIPTS` 补 V30/V31/V32（V30/V31 缺登记为**既有漂移**，S2-03.1/S3-08 先例）；末端断言 V21→V32，version 改由文件名推导（号位硬钉每批必红的根因移除） |
| `scripts/run-tests.ps1` | 基线 | analytics-server 1096→1096+N（N=静态门禁新增 @Test 数） |

## 3. 宽度取 255 的依据（写死在 V32 头注释）
`uk_ckpt_source (runtime_profile_id, source_id, file_path, file_identity)` 为 utf8mb4 唯一键，
InnoDB 单键上限 3072 字节：8 + 8 + (500×4) + (255×4) = **3036 ≤ 3072**；取 300 则 8+8+2000+1200=3216 > 3072，
键直接建不出来。身份串格式由 `HdfsLandingStorage` 代码所有（格式变更 = 新变更请求），95 字符 + 160 余量。

## 4. 测试计划
1. 窄测试：mvn 单类跑静态门禁（JDK17，`-Dtest=FileCheckpointIdentityWidthMigrationScriptTest`）。
2. default 档全量：`scripts/run-tests.ps1 -Suite default`，计数基线同步（1096→1096+N）。
3. isolated 档（3307 在监听、`V25_IT_*` 口令按既有合规通道进程环境注入）：
   `scripts/run-tests.ps1 -Suite isolated -Confirm`；`AnalyticsIsolationFlywayIT`（含新方法）为门，
   `SourceRegistryMigrationMySqlIT` 以副本库参数（per-run DB + metaapp 凭据映射到 `p1.it.*`）实跑取证。
4. 不重跑 spark 档（本批次不触 spark-jobs）。

## 5. 边界
- 不碰 3306；不碰运行中的 8091 平台；不改 V8/V17 已发布脚本；不 commit/push。
- 行为级端到端「HDFS 文件重试不重读」的闭环演示需要 HDFS/Flume 真链路，归 D-044⑤ 合并重跑；
  本批次以存储层 IT + 既有 ingestor 单测为证据。
- 登记三件套（result doc / CURRENT_BATCH / PROJECT_STATUS）改前先备份原件；DECISION_LOG 追加 D-046。
