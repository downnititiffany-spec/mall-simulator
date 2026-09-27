# 批次 G31-09 结果 — D-041 checkpoint 列宽与索引约束修复（V32 加宽 file_identity 64→255）+ 三档回归

- **批次**：G31-09（D-044④ 两个短批次之第二批）
- **日期**：2026-09-25
- **对应计划**：`docs/verification/batches/BATCH-G31-09-CHECKPOINT-IDENTITY-PLAN.md`
- **状态**：✅ 完成（V32 迁移落盘 + 静态门禁 5/5 + isolated 档全绿 + default 档 1150/1275 MATCH exit=0）
- **提交纪律**：不 commit、不 push（等用户安排）；本批触碰文件见 §6 归属清单

## 1. 缺陷与修复

**缺陷（D-041，G31-05 发现、D-044④ 列为验收拦截项）**：`file_checkpoint.file_identity` 由 V8 定义为 `VARCHAR(64)`，取值域按「LOCAL 创建时间戳毫秒」设计。HDFS 档接入后，`HdfsLandingStorage` 写入的身份是

```
"hdfs:" + "MD5-of-0MD5-of-512CRC32C" + ":" + <64 位 hex>  =  94 字符
```

超出 64 列宽 → checkpoint INSERT 抛 `MysqlDataTruncation` → 断点行落不下来 → 同一 HDFS 文件重试时被当作新文件全量重读（幂等失效，重复摄取）。LOCAL 档不受影响。

**身份长度的字节账（94，双证钉死）**：

- 构成：`hdfs:`（5）+ 算法名 `MD5-of-0MD5-of-512CRC32C`（24）+ `:`（1）+ hex（64）= **94**；
- hex 为何是 64：`FileChecksum.getBytes()` 经 `WritableUtils.toByteArray(new Writable[]{this})` 实现，返回 `DataOutputBuffer.getData()` 的**原始缓冲**（不按写入量裁剪）——`write()` 实写 28 字节（writeInt bytesPerCRC 4 + writeLong crcPerBlock 8 + MD5Hash 16），但 `ByteArrayOutputStream` 初容量 32、28 ≤ 32 不扩容 → 返回 `byte[32]`（含 4 字节零余量）→ `HexFormat` 编码出 64 个 hex 字符；
- 证据双源：hadoop-common 3.3.4 `javap -c` 字节码链（`getData` 直接 `areturn`、无 copyOfRange/裁剪）与平台侧运行时实测（attempt-3 失败信息 `expected: 86 but was: 94` 即实测 94）；二者一致。
- **长度推导修正轨迹**：批次计划文档写「≈95」（估算）→ attempt-2/3 运行时实测 94 → 中间一度按「toByteArray 会裁剪」的错误假设推出 86，被字节码推翻。**结论 94，以本登记为准；计划文档 ≈95 保留原文不改写。**

**修复（一个生产 schema 文件，追加式迁移）**：`analytics-server/platform-app/src/main/resources/db/meta/V32__file_checkpoint_identity_width.sql`

- 单条语句：`ALTER TABLE file_checkpoint MODIFY COLUMN file_identity VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文件身份（LOCAL=创建时间戳毫秒；HDFS=hdfs:算法:校验和hex；G31-09/D-041 由 64 加宽至 255）'`；
- **为什么是 255**：`uk_ckpt_source (runtime_profile_id BIGINT, source_id BIGINT, file_path VARCHAR(500), file_identity)` 为 utf8mb4 唯一键，InnoDB 单键字节上限 3072 —— `8 + 8 + (500×4) + (255×4) = 3036 ≤ 3072`；若取 300 则 `8+8+2000+1200 = 3216 > 3072`，迁移直接建键失败。94 字符 + 161 余量；
- **为什么只 MODIFY**：MODIFY 自动维护该列所属唯一键 `uk_ckpt_source` 与索引 `idx_file_checkpoint_source`、外键 `fk_file_checkpoint_source`，无 DROP/ADD INDEX；不指定 ALGORITHM/LOCK（沿用 V17 先例）；存量 LOCAL 行语义不变（仍 NOT NULL DEFAULT ''）；
- **追加式纪律**：V8/V17 已发布内容零改动（静态门禁钉住）；执行范围 = 隔离验证库（3307 per-run DB，本批已执行）+ 正式 analytics_meta（按 D-044⑤ 合并重跑窗口执行，**不在 3306 上运行**）。

## 2. 测试证据

### 2.1 静态门禁 `FileCheckpointIdentityWidthMigrationScriptTest`（5/5，default 档计入）

只读静态检查、不连库：① V32 存在、版本号唯一、V31 前置；② 整脚本仅一条 `ALTER TABLE file_checkpoint` 且只 `MODIFY COLUMN file_identity varchar(255)`，不得夹带加列/删列/删表/TRUNCATE/DELETE/INSERT/UPDATE；③ 不得 DROP/ADD 索引或约束（MODIFY 自动维护）；④ 列语义保持 `NOT NULL DEFAULT ''` 且注释说明两种身份来源；⑤ 已发布迁移零改动（V8 仍 VARCHAR(64)、V17 仍持 uk_ckpt_source 四列定义）。attempt-4 实测 **Tests run: 5, Failures: 0**。

### 2.2 isolated 档 schema 门 `AnalyticsIsolationFlywayIT`（3307 per-run 库，真 Flyway）

新增 HDFS 身份 IT（2026-09-25 attempt-4 全绿，**Tests run: 2, Failures: 0, Errors: 0**）：

- information_schema 断言：`file_identity` 列宽 `varchar(255)` / `CHARACTER_MAXIMUM_LENGTH=255` / `IS_NULLABLE=NO` / 默认值空串；
- 用**真实形态** 94 字符身份 `hdfs:MD5-of-0MD5-of-512CRC32C:<64hex>`（代码内按真实字节账拼装，断言 `startsWith("hdfs:MD5-of-0MD5-of-512CRC32C:")` 且 `hasSize(94)`，注释注明「94 > 64：V8 宽度下此 INSERT 必然 MysqlDataTruncation（D-041 的复现形态）」）执行 INSERT → 原样回读逐字符相等 → 同键重复 INSERT 抛 `DuplicateKeyException`（**uk_ckpt_source 语义不变**，94 字符身份照样查重）→ UPDATE 到下一身份 → 原样回读 → finally 清理后 `COUNT==0`。

### 2.3 isolated 档 p1.it `SourceRegistryMigrationMySqlIT`（真迁移链 V1→V32）

attempt-4 **Tests run: 7, Failures: 0, Errors: 0，BUILD SUCCESS**：`EXPECTED_META_SCRIPTS` 显式清单扩至 **31 条（V1…V32）**，两次「应用启动」真实 Flyway migrate（[P2-07] 打印全清单、[P1-02] 确认真迁移执行），V32 加宽在真库上生效。本 IT 同时承载本批修复的两个潜伏缺陷（见 §3）。

### 2.4 isolated 档编排（attempt-4 权威记录）

编排器单进程四步（V25_IT_* 口令仅进程内，零落盘零 argv）：RunId **`g3109iso_20260925_180727`** → it-prepare-isolation → credref bridge → `run-isolated-tests.ps1 -Module all -IncludeAnalyticsWriteIts` → p1.it（mysql 探针钉指纹 `dahaishui:3307`）。结果：**隔离 62/62 MATCH**（mall 30 + generator 19 + analytics 13）+ schema 门 2/2 + p1.it 7/7，`=== SUMMARY RunId=g3109iso_20260925_180727 isolated=0 p1it=0 ===` / `G31-09 ISOLATED FACE: ALL GREEN`。日志：`C:\Users\ASUS\AppData\Local\Temp\g3109-iso-run4.log`；明细 `C:\Users\ASUS\AppData\Local\Temp\v25tests-g3109iso_20260925_180727\isolated-mvn\`。

### 2.5 default 档全量回归（计数门同步 + MATCH 复跑）

- 首跑：analytics-server 实测 **1150（F=0 E=0 S=2）** 对照旧门 1096 → `DRIFT ⇒ FAIL`（唯一失败项=基线比对，非测试失败；且本工作树状态下历史环境性红 manifest patrol 未触发，S=2）；
- 同步：`$BaselineDefault['analytics-server']` 1096→1150，按惯例注释归因：**+5 = G31-09 静态门**，其余 **+49 = 09-23 后各批次已在自有档验证、从未回同步进本计数门的存量**（归因清单供提交前 checklist，D-044⑤）；同时**修正基线图中 1101 的既有笔误**（+5 已加、+49 漏加的半截同步）；
- MATCH 复跑：**exit=0**，analytics-server 1150（F=0 E=0 S=2）MATCH + mall 14 + generator 111，三树合计 **1275 = 基线 1275**（日志 `v25tests-dev003c_20260925_181516_4c125f`，2026-09-25 18:15 轮）。

## 3. 顺手修复的被暴露潜伏缺陷（诚实归因）

1. **`SourceRegistryMigrationMySqlIT.context()` 自 31d5ed5 后从未可绿**：31d5ed5 收紧 `TestRunContext` 构造即校验（metricDb 非空且 ≠ metaDb、hiveNamespace 前缀、hdfsRoot/manifestRoot 绝对路径含 runId）后，本 IT 的 context() 仍传占位式登记值，**构造器直接拒绝**——即无论迁移状态如何，该 p1.it 用例自收紧提交起不可能通过。修复：hiveNamespace/hdfsRoot/manifestRoot 改为 `requiredProperty` 真实 scope 登记（编排器注入本 RunId 域值），METRIC_DB 与 META_DB 分库。attempt-3/4 实测通过即证明修复有效。
2. **V22 冻结列漂移**：`FROZEN_RUNTIME_PROFILE_COLUMNS` 缺 `landing_layout`（V22 以 `AFTER landing_uri` 追加）；本 IT 自 V22 落地后未再实跑，旧清单只列到 V7 的 24 列。修复：补齐为冻结 25 列（landing_layout 在 landing_uri 后、source_id 由 V16 追加在末尾），断言「迁移前 25 列原样保留，新增列只能是 source_id」。
3. **run-tests.ps1 基线笔误**：基线图 1101 与其自身注释记载的 1150 矛盾（+5 已同步、+49 漏加的半截同步），若不修将使下次 default 档 DRIFT 误报。已随本次同步一并修正。

三者均为测试基建/登记层缺陷，产品代码唯一改动 = V32 迁移脚本。

## 4. 边界与诚实登记（必须随批报告）

1. **per-run 3307 库 ≠ 正式 analytics_meta**：V32 本批只在隔离验证库执行；正式库（WSL analytics_meta）的 V32 应用归 **D-044⑤ 合并重跑窗口**；3306 全程零接触（V32 永不在 3306 运行）。
2. **行为级判据未闭环**：D-044④ 判据「**同一 HDFS 文件重试不重读**」需要真实 HDFS/Flume/平台链（Ingestor 把 94 字符身份写进加宽列 → 重试命中断点行跳过）才成立。本批证明的是**schema 层前提**（加宽后 94 字符身份可原样落库、唯一键语义不变、迁移在真库生效），行为级闭环归 D-044⑤ 合并重跑 + G31-07 终验重跑。**在 D-044⑤ 闭环前，HDFS 同文件重扫幂等继续不得作为验收口径**（D-044④ 边界延续）。
3. **身份串格式所有权**：格式由 `HdfsLandingStorage` 代码所有（`HdfsLandingStorage.java:145`），格式变更=新变更请求；255 宽度对本格式余量 161。
4. **计划文档长度勘误不改写**：计划文档「≈95」保留原文，修正轨迹见 §1，以本结果文档 94 为准。
5. 3306 零接触；不改已发布迁移（V8/V17 由静态门禁钉住）；冻结 V3.0 指导书/设计文档零触碰；未 commit 未 push。

## 5. D-044④ 判据对账

| 判据（G31-09） | 本批证据 | 状态 |
|---|---|---|
| checkpoint 能存下 HDFS 身份（schema 前提） | V32 静态门禁 5/5 + AnalyticsIsolationFlywayIT 94 字符原样回读（2/2）+ p1.it 真迁移 V1→V32（7/7） | ✅ 本批闭合 |
| 唯一键/约束语义不变 | uk_ckpt_source 对 94 字符身份仍拒重复（DuplicateKeyException 实测）；无 DROP/ADD 索引 | ✅ 本批闭合 |
| **同一 HDFS 文件重试不重读（行为级）** | — | ⏳ **D-044⑤**（真实 HDFS/Flume/平台链重跑 + G31-07 终验） |

## 6. 归属清单（本批触碰文件，供提交分组）

| 文件 | 变更 |
|---|---|
| `analytics-server/platform-app/src/main/resources/db/meta/V32__file_checkpoint_identity_width.sql` | **新增**：追加式加宽迁移（唯一产品 schema 改动） |
| `analytics-server/platform-app/src/test/java/com/graduation/analytics/migration/FileCheckpointIdentityWidthMigrationScriptTest.java` | **新增**：5 条静态门禁 |
| `analytics-server/platform-app/src/test/java/com/graduation/analytics/isolation/AnalyticsIsolationFlywayIT.java` | 修改：+HDFS 身份 94 字符 IT + 86→94 勘误注释 |
| `analytics-server/platform-app/src/test/java/com/graduation/analytics/source/SourceRegistryMigrationMySqlIT.java` | 修改：清单 +V30/V31/V32、context() 真实 scope 修复、V22 冻结列补齐 |
| `scripts/run-tests.ps1` | 修改：default 基线 1096→1150（+5/+49 归因注释，修正 1101 笔误） |
| `docs/verification/batches/BATCH-G31-09-CHECKPOINT-IDENTITY-PLAN.md` | 新增：批次计划（≈95 估算保留原文，勘误见本文件 §1） |
| `docs/verification/results/BATCH-G31-09-CHECKPOINT-IDENTITY-RESULT.md` | 新增：本文件 |
| `docs/decisions/DECISION_LOG.md` | 追加：D-046（备份 `*.bak-20260925-g3109`） |
| `docs/verification/CURRENT_BATCH.md` / `docs/PROJECT_STATUS.md` | 更新（备份 `*.bak-20260925-g3109`） |

（其他工作树在途改动属先前批次（质量规则 v2 组、G31-08 组等），与 G31-09 无关，提交时不得混入——D-044⑤；归属分组另见 G31-07 验收报告 §9 与 G31-08 结果 §5。）

## 7. 下一步

- **D-044⑤ 合并重跑**：受影响链路（LOCAL/HDFS 平台摄取 + LOAD_ODS 以 G31-08 合并语义入链）+ 正式库 V32 应用 + G31-07 终验重跑 → 替换「限定通过候选」中的两个拦截项（F-G4-1、D-041）→ 报总控。
- 提交按归属分组执行（G31-07 §9 / G31-08 §5 / 本文件 §6），push 等用户另行明确安排。
