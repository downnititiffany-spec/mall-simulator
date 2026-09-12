# P2-02 只读取数台账（RAW-READINGS）

- 泳道：P2-02「多格式时间与 quarantine 原因码」规格／施工单起草（只写本目录）
- 取数时点：**2026-09-12 20:04 ～ 20:13（+08:00）**，每条读数各自带 `Get-Date` 时钟
- 开工时 HEAD = `3dfe94e`；取数时 HEAD = **`ce36065c3db4572115ff09945ef6b041fcfdc5e2`**（总控期间提交 4 次，看板被改写）
- 本文件只记录**实际执行过的命令与原样输出**。凡未执行者一律写「未测」，不做推断。

## 0. 全局纪律声明（避坑）

| 项 | 本泳道做法 |
|----|-----------|
| 行数口径 | 逐字节计 LF 与 CRLF，净行数 = LF − CRLF。**不使用 `Measure-Object -Line`**（本项目陷阱 #57：看板实测 412 而真值 489） |
| 命中口径 | 一律 `Select-String -SimpleMatch` 逐文件扫描，**不使用 `git grep` + pathspec**（陷阱 #54：带 pathspec 的 git grep 曾产出假零） |
| 零命中的证伪 | 每条「0 命中」结论都必须附**阳性对照**（同命令在同范围扫描一个已知存在的 token）与**阴性对照**（一个已知不存在的 token 必须为 0） |
| 落盘方式 | 命令输出用 `Tee-Object` **原样落盘**（含退出码、含 `mysql` 的 `[Warning] Using a password…`）；文档类文件用文件工具写并回读 |
| 写权限 | 只在 `docs/acceptance/p2-02-multiformat-time-20260912/` 内新建文件；未修改任何既有文件；未做任何 git 写操作 |
| 在飞泳道 | `spark-jobs/**` 同时被 P2-03 改写 ⇒ 全部只读，且**记录读时 sha256**（见 `raw/source-fingerprints-20260912.txt`） |

### 0.1 一次「假零」的处置留档（总控 20:05:33 抽测为 0 字节）

总控 20:05:33 实测 `raw/scan-event-time-shapes-20260912.txt` 为 **0 字节**并发来假零预警。复核结论：**该 0 字节是「采样落在运行窗口内」，不是零命中**。

| 事件 | 实测时刻 |
|------|---------|
| 扫描脚本启动 | 20:05:32.784 |
| 输出文件被创建（此刻必然 0 字节，`Tee-Object` 结束前不落盘） | 20:05:33.166 |
| 写入完成 | 20:08:04.439 |
| 复核：25,018 B / 372 行 | 20:08:35.260 |
| 脚本退出码 | `EXIT=0` |

⇒ 结论：**该文件的 0 字节不构成读数**。本条留档的目的正是把它记成「采样窗口伪影」，避免后来者把它当「零命中」引用。总控的预警口径（0 命中必须附阳性对照）已被本台账全量采纳。

---

## 1. R1 真实语料 `event_time` 形态全量扫描

- 输出：`raw/scan-event-time-shapes-20260912.txt` — 25,018 B，sha256 `D52E716111E780ACF71E9406E46127C23FF420DB67AD2DE95FF7116A2432FDF5`
- 脚本：`raw/scan-event-time-shapes.ps1` — 4,678 B，sha256 `9B7DCC4B9C28840D85F2693D06CC18AD31D25586772F1454FDE3B89C474750A6`
- 时钟：20:05:32.784 → 20:08:04.475；退出码 `0`

命令原文（关键片段）：

```powershell
# 契约冻结正则逐字取自 contract-specs/schemas/canonical-event.v1.schema.json $defs.iso8601_time.pattern
$contractPattern = '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?([+-]\d{2}:\d{2}|Z)$'
$rxExtract = [regex]'"event_time"\s*:\s*"([^"]*)"'
$scanRoots = @("$root\landing", "$root\generator-output", "$root\tests\golden-dataset")
# 对每个 .jsonl 全量 ReadAllText + Matches + 形态分类
```

抽样范围（**全量，不是前 N 个**）：

| 项 | 值 |
|----|----|
| 扫描根 | `landing/`、`generator-output/`、`tests/golden-dataset/`，全部 `.jsonl`（递归） |
| 文件数 | **349**（输出文件首部 `SCAN_FILE_COUNT`，逐文件全量读取，无一跳过） |
| 总字节 | **1,215,989,873**（输出文件首部 `SCAN_TOTAL_BYTES`，约 1.216 GB） |
| 提取到的 `event_time` 取值数 | **2,622,616** |

结果（`=== GRAND TOTAL BY SHAPE ===` 段，逐字）：

| 形态 | 计数 |
|------|------|
| `CONTRACT_OK_OFFSET`（`yyyy-MM-ddTHH:mm:ss+08:00`，命中契约正则且非 Z） | 2,622,616 |
| `CONTRACT_OK_Z` | 0 |
| `NO_OFFSET` | 0 |
| `OFFSET_NO_COLON` | 0 |
| `SPACE_SEP` | 0 |
| `DATE_ONLY` | 0 |
| `EPOCH_DIGITS` | 0 |
| `SLASH_SEP` | 0 |
| `OTHER` | 0 |
| `MISSING_KEY` | 0 |
| `EMPTY_STRING` | 0 |

样本（每形态最多 4 个，`=== DISTINCT SAMPLES PER SHAPE ===` 段逐字）：

```
SHAPE CONTRACT_OK_OFFSET -> 2026-09-01T14:21:56+08:00 | 2026-09-01T02:11:59+08:00 | 2026-09-01T04:23:58+08:00 | 2026-09-01T20:00:56+08:00
```

阳性／阴性对照（`=== POSITIVE CONTROL ===` 段逐字）：

```
CONTROL_FILE	tests\golden-dataset\events\golden-20260901.jsonl
CONTROL_EXTRACT_COUNT	54
CONTROL_FIRST_VALUE	2026-09-01T09:00:00+08:00
CONTROL_FIRST_CLASS	CONTRACT_OK_OFFSET
NEGATIVE_CONTROL_ABSENT_KEY_COUNT	0
CONTROL_METHOD	Select-String 对照（不使用 git grep / pathspec）
CONTROL_SELECTSTRING_HITS	54
```

**结论（读数支撑）**：真实语料今天**只存在一种** `event_time` 形态 = `yyyy-MM-ddTHH:mm:ss+08:00`。所谓「多格式时间」在真实数据上 **零发生**。

**由此产生的硬约束**：P2-02 的出口判据**必须包含构造夹具**；且任何「已支持多格式」的说法都必须指明是**构造夹具**而非真实数据。

**反过来的一条守卫**：该 2,622,616 行可作为回归基线 —— 实现上线后 `TIME_PARSE_FAILED` 计数若 > 0，即证明实现引入误判（真实数据全部合法）。

---

## 2. R2 真库 `analytics_meta.quarantine_record` 结构与既有原因分布

### 2.1 结构

- 输出：`raw/db-quarantine-schema-20260912.txt` — 2,072 B，sha256 `6CA1D72D0C29457B57270DEF6E983A59648515E7788FDAEE1A2C032A107A793D`
- 时钟（库内 `NOW(3)`）：2026-09-12 20:04:41.628

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --host=127.0.0.1 --user=root --password=123456 `
  --default-character-set=utf8mb4 --batch --raw --table --execute="SHOW COLUMNS FROM ...; SHOW CREATE TABLE ..."
```

（`--host=127.0.0.1` 必须用**长选项**形式；本机短选项 `-h127.0.0.1` 会失败。）

结果要点：

| 列 | 类型 | 备注 |
|----|------|------|
| `id` | `bigint AUTO_INCREMENT` | `AUTO_INCREMENT=109` ⇒ 历史累计插入 108 行 |
| `batch_id` | `bigint NOT NULL` | 索引 `idx_quarantine_batch` |
| `event_id` | `varchar(64) NULL` | |
| `schema_version` | `varchar(16) NULL` | |
| **`reason`** | **`varchar(255) NOT NULL`** | **自由文本，非枚举** |
| `raw_path` | `varchar(500) NOT NULL` | |
| `created_at` | `datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)` | |

> **命名更正（针对父侧简报）**：父侧简报假设存在列 `reason_code`。**真库无此列**；实际列名是 `reason VARCHAR(255) NOT NULL`。同源证据：Java 实体 `QuarantineRecord.java` 字段名亦为 `reason`，`EventContractValidator.Violation` record 第 4 个分量亦为 `reason`（自由文本）。全库扫描 `reason_code` / `reasonCode` / `quarantine_reason` **均 0 命中**（见 R5，含阳性对照）。

### 2.2 既有原因分布

- 输出：`raw/db-reason-distribution-20260912.txt` — 2,601 B，sha256 `13D8FAF2BDA13CEDDA0D02E45B04673996EE02C92FED7016E20E072F73E82DFA`
- 时钟：2026-09-12 20:04:45.524

| 项 | 值 |
|----|----|
| `total_rows` | **108** |
| `distinct reason` | **9**（全部为自由文本） |
| 与**时间**相关的原因 | **0** |

9 个既有原因（原样，未改写）：

| 计数 | `reason` 原文（前缀一致，含变量值） |
|------|-----------------------------------|
| 23 | `非法 behavior_type: purchase` |
| 23 | `JSON 解析失败: Unrecognized token 'not'…` |
| 23 | `未支持 schema_version: 2.0` |
| 21 | `缺失必要字段: event_id` |
| 6 | `未支持 schema_version: 9.9` |
| 4 | `payload 缺失必要字段: session_id` |
| 4 | `JSON 解析失败: Unexpected character ('b' (code 98))…` |
| 2 | `非法 behavior_type: add_cart` |
| 2 | `未支持 schema_version: 1.1` |

`schema_version` 分布：`1.0` 50、`(NULL)` 27、`2.0` 23、`9.9` 6、`1.1` 2。
`created_at` 区间：2026-09-07 18:23:54.577 → 2026-09-12 17:22:22.362。

**结论（读数支撑）**：quarantine 通道今天承载 9 类**自由文本**原因，**没有任何原因码枚举**，且**零条时间相关**。

> **反向证据（重要）**：`缺失必要字段: event_id` 已出现 **21 次** ⇒ plan 要求的「缺 eventId 进 quarantine」**在采集侧已经存在且在工作**；P2-02 对它的增量可能只是「补原因码」，而不是「新建通道」。

---

## 3. R3 真库 `data_quality_result` 与拒绝通道边界

- 输出：`raw/db-dq-and-reject-20260912.txt` — 3,477 B，sha256 `AD97A32E873F0F8684F2A0F2C0EC88614288AF0F9DB0C720792F399273A50117`
- 时钟：2026-09-12 20:04:50.844
- **该次取证退出码 = 1**（尾部一条探针 SQL 失败，原文已落盘、未隐藏）：

```
ERROR 1054 (42S22) at line 1: Unknown column 'metric_code' in 'field list'
```

失败原因：父侧简报假设的列名 `metric_code` 不存在，实际列名是 **`rule_code`**。**该次取证的其余语句已成功并落盘**；这条失败被保留为「命名更正」的证据，不重跑掩盖。

结果要点：

| 项 | 值 |
|----|----|
| `data_quality_result` 列 | `id, run_id, rule_code, layer, severity, target_table, snapshot_id, check_count, error_count, error_rate, threshold, passed, detail, created_at` |
| `dq_total` | **445** |
| 与**时间**相关的 `rule_code` | **0** |

`rule_code` × `severity` 计数（实测，节选）：`MXP_EXPORT_ROWS` BLOCKING 104、`AMOUNT_RECONCILE` BLOCKING 25、`REQUIRED_FIELD_NULL_RATE` ERROR 25、`EVENT_ID_UNIQUE` ERROR 25、`ENUM_WHITELIST` ERROR 25、`ADS_STAGING_PRESENT` BLOCKING 22、`ADS_STAGING_KEY_NOT_NULL` BLOCKING 22、`PUB_DQ_BLOCKING_RULES` BLOCKING 22、`ADS_DWS_FUNNEL_RECONCILE` BLOCKING 22、`ADS_STAGING_SNAPSHOT_ISOLATION` ERROR 21、`PUB_DQ_EVENT_ID_UNIQUE` ERROR 19、`PUB_STAGING_READY` BLOCKING 17、`PUB_FORMAL_PARTITION_MATCH` BLOCKING 17、`MXP_SNAPSHOT_PINNED` BLOCKING 13、`MXP_EXPORT_COMPLETE` BLOCKING 13。

**结论（读数支撑）**：`data_quality_result` 是**批次级规则／严重度计数**通道，15+ 个 `rule_code`，**零条时间相关**。

---

## 4. R4 真库源／批次元数据（时间与格式载体）

### 4.1 「格式列表」列是否存在

- 输出：`raw/db-source-profile-tables-20260912.txt` — 2,708 B，sha256 `E1F2D0711A3A2EB4A07B9EFA2EE9B725FCEA74E385FF6656FFAB2C72DA7EA4D7`
- 时钟（库内 `NOW(3)`）：2026-09-12 20:09:17.472；退出码 `0`

`analytics_meta` 全部表（22 张）：`ai_call_log, ai_query_history, data_quality_result, decision_evaluation, decision_task, file_checkpoint, flyway_schema_history, ingestion_batch, ingestion_batch_file, metric_definition, metric_snapshot, metric_value, operation_audit_log, pipeline_run, pipeline_stage_run, quarantine_record, runtime_profile, source_registry, spark_job_run, sys_user, t_ckpt_test, user_session`。

名称含 `time` / `tz` / `zone` / `format` / `parse` 的列（全库实测）：

| 表 | 列 | 类型 |
|----|----|------|
| `source_registry` | `timezone` | `varchar(64) NOT NULL` |
| `runtime_profile` | `timezone` | `varchar(32) NOT NULL` |
| `ingestion_batch` | `start_time` / `end_time` | `datetime(3)` |
| `metric_snapshot` / `pipeline_run` | `business_time` | `datetime(3)` |
| `metric_definition` | `default_time_field` | `varchar(32) NOT NULL` |

**结论（读数支撑）**：
1. **源时区有两个载体**：`source_registry.timezone` 与 `runtime_profile.timezone`（两个 owner，见待裁决 D-103）。
2. **全库不存在任何「时间格式列表」列** ⇒ 「格式列表」只能来自源画像文件（`timePolicy.formats`），不能来自 DB。
3. 指导书 §5.2 提到的 `source_manifest` / `ingestion_batch_file.accepted/quarantined/parse_errors` / `schema_mapping` 表 **在真库不存在**（`schema_mapping` 不在 22 张表内）。

### 4.2 源登记与运行画像的实际取值

- 输出：`raw/db-source-registry-values-20260912.txt` — 1,016 B，sha256 `8DF1BB0BE7CC2CBA70019925EAB0C4AC6840258734B1DA68FC324FC3529CCF23`
- 时钟：2026-09-12 20:10:00.053

| 表 | 关键取值 |
|----|---------|
| `source_registry` (id=1) | `source_code=mock-mall`，`profile_path=analytics-server/source-profiles/mock-mall.v1.json`，`timezone=Asia/Shanghai`，`currency=CNY`，`status=ACTIVE`，`profile_version=1.0`，`warehouse_prefix=dw` |
| `runtime_profile` (id=1) | `profile_code=local-dev`，`timezone=Asia/Shanghai`，`hive_database_prefix=NULL`，`source_id=1`，`status=ACTIVE`，`version=3` |

**结论（读数支撑）**：登记的 `profile_path` 指向 `analytics-server/source-profiles/mock-mall.v1.json`，而该文件**不存在**（已用 `glob` 枚举该目录：只有 `p1-03-probe-1.v1.json`、`p1-03-probe-2.v1.json`、`README.md`）。`README.md` 自述此为**已知状态**：V16 种子源当前**不可激活**，`mock-mall.v1.json` 是 **P3-01 的交付物**。

⇒ **源 A 的真实时间格式列表今天无法取得**（见「未测」与待裁决 D-102）。

### 4.3 批次文件计数列

- 输出：`raw/db-batch-file-counters-20260912.txt` — 2,447 B，sha256 `AA7B20D9323814B9FA3B9044106CD889F678229FA759B0B7E39C1E05A5BBE170`
- 时钟：2026-09-12 20:11:46.228；退出码 `0`
- `ingestion_batch_file` 行数 = **118**

`ingestion_batch_file` 列：`id, batch_id, file_path, start_offset, end_offset, record_count, status`（**无** `quarantined_count` / `parse_error_count`）。
`ingestion_batch` 列：`id, batch_no, source, runtime_profile_id, source_id, status, record_count, error_count, quarantine_count, landing_dir, start_time, end_time`。

**结论（读数支撑）**：**逐文件的隔离／解析失败计数在 DB 侧不存在**（只有 `record_count` + `status`）。加法计数器若要落库需要新迁移（属 DDL 变更 ⇒ 停止条件，见施工单 A4）。

---

## 5. R5 时间语义 token 全库扫描（带双向对照）

- 输出：`raw/time-semantics-greps-20260912.txt` — 7,096 B，sha256 `E00B9BE94B3A9F19913AD53E7C7D55BE8B9B37980411A19506458D1F94A62B0D`
- 时钟：20:12:28.146；HEAD `ce36065`；退出码 `0`
- 扫描面：`spark-jobs/src`、`analytics-server`（排除 `target/`）、`warehouse`、`contract-specs`；扩展名 `scala/java/sql/json/yml/yaml`；**320 个文件**
- 方法：`Select-String -SimpleMatch` 逐文件

| 模式 | 命中 | 说明 |
|------|------|------|
| `FUTURE_TIME` | **1** | 仅 `warehouse/ddl/01-dwd.sql:90` 的注释（枚举声明） |
| `BAD_TIME` | **0** | — |
| `TIME_PARSE` | **0** | — |
| `UNPARSEABLE` | **0** | — |
| `BAD_FORMAT` | **0** | — |
| `INVALID_TIME` | **0** | — |
| `timePolicy` | **8** | 1 处在生产代码（`SourceProfileValidator.java:43` 的**键名清单**），其余 7 处在测试／夹具 JSON |
| `quarantinePolicy` | **9** | 同上，1 处键名清单 + 其余测试／夹具 |
| `normalizeTime` | **4** | 1 处定义（`Cleaners.scala:17`）+ **3 处测试断言**；**生产调用 0** |
| `reason_code` | **0** | — |
| `reasonCode` | **0** | — |
| `reject_reason` | **8** | 见下 |
| `quarantine_reason` | **0** | — |
| `parse_errors` | **0** | — |
| `min_event_time` | **6** | 全在**生成器侧**契约（`generator-api.v1.yaml`、`generation-artifact-manifest.v1.schema.json`），**不在** `ingestion-manifest.v1.schema.json` |

对照组（同一次扫描、同一范围）：

```
CTRL_FUTURE_TIME_HITS	1
CTRL_event_time_HITS	95
CTRL_ABSENT_TOKEN_HITS	0
```

⇒ 阳性对照 `FUTURE_TIME=1`（已知存在）与 `event_time=95`（广布）均命中，阴性对照 = 0 ⇒ **本次「0 命中」可信，非假零**。

`reject_reason` 的 8 处（逐字）：

```
warehouse/ddl/01-dwd.sql:90          reject_reason STRING COMMENT '枚举：EMPTY_FIELD/DUPLICATE_EVENT/BAD_ENUM/BAD_AMOUNT/FUTURE_TIME'
spark-jobs/.../sql/DwdSql.scala:64   'DUPLICATE_EVENT' AS reject_reason
spark-jobs/.../sql/OdsLoadSql.scala:272  'BAD_VERSION_OR_KEY' AS reject_reason
spark-jobs/.../sql/AdsSql.scala:258-261  三处 COUNT(*) WHERE reject_reason = 'DUPLICATE_EVENT'
spark-jobs/.../job/LocalSchemaInitJob.scala:59  reject_reason STRING
analytics-server/.../V4__platform_decisions.sql:21  reject_reason VARCHAR(255) NULL
```

**结论（读数支撑）**：
1. **时间类原因码在代码里零存在**（`BAD_TIME` 等 5 个候选全 0，对照有效）。
2. `FUTURE_TIME` 是**已声明未实现**的既有锚点：DDL 注释里有，代码里从不写。
3. `timePolicy.formats` 是**死配置**：只有键名存在性检查，**没有任何生产代码读取其内容**。
4. `Cleaners.normalizeTime`（唯一的「多格式」实现）**生产调用为 0** ⇒ 属未接线代码。

---

## 6. R6 时间语义链路锚点（契约 → 采集 → ODS → DWD）

- 输出：`raw/time-chain-anchors-20260912.txt` — 2,976 B，sha256 `ABF71831D712B210889976E3B51947F20F2C5BF8470ECA0BEE15DFB0FDA17A53`
- 时钟：20:12:44.766；HEAD `ce36065`；退出码 `0`
- 对照：`CTRL_A_iso8601_hits=9`、`CTRL_E_substr_hits=2`、`CTRL_ABSENT=0`

链路逐环（行号以 `raw/source-fingerprints-20260912.txt` 的 sha256 为准）：

**A. 契约**（`contract-specs/schemas/canonical-event.v1.schema.json`，sha256 `A70AF901…`）

| 位置 | 内容 |
|------|------|
| L30-33 | `"event_time": { "$ref": "#/$defs/iso8601_time" … }` |
| L34-37 | `"ingest_time": { "$ref": "#/$defs/iso8601_time" … }` |
| L323-327 | `"iso8601_time"`：`type: string`、`format: date-time`，带一个 pattern（**原文见下方代码块**，因含正则择一竖线，按本项目约定不放进表格单元格） |

L323-327 的 pattern 原文（逐字，取自 `canonical-event.v1.schema.json`）：

```
^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?([+-]\d{2}:\d{2}|Z)$
```

⇒ 该 pattern 是**词法**的：它要求**必须带偏移或 Z**，但它**接受任何合法偏移**（`+05:30` 也过），且**接受语义非法的日期**（如 `2026-02-30`——正则不校验日历）。这两点是规格 §2.4「词法命中 ≠ 解析成功」的依据。

`iso8601_time` 在全文件中出现 **9 行**：**1 行是定义**（L323），**8 行是 `$ref` 引用**（L31/35/371/605/642/674/713/750），覆盖 **8 个字段**（逐一实测归属）：

| 引用行 | 所属字段 |
|--------|---------|
| L31 | `event_time` |
| L35 | `ingest_time` |
| L371 | `register_time` |
| L605 | `created_at`（退款） |
| L642 | `paid_at` |
| L674 | `cancelled_at` |
| L713 | `created_at`（订单） |
| L750 | `completed_at` |

⇒ 除 `event_time`／`ingest_time` 外，**另有 6 个时间字段共用同一定义** ⇒ 本规格对时间语义的任何改动都会**同时影响 8 个字段**（这是 P2-02 影响面的真实大小）。

**B. 契约文档**（`docs/contracts/event-contract.md`，sha256 `7F4A6E45…`）

```
L25: * 时间一律 ISO-8601 带时区（业务统一 Asia/Shanghai，`+08:00`）；解析失败视为脏数据。
```

**C. 采集侧校验**（`EventContractValidator.java`，sha256 `B730041A…`）

```
L56-57: for (String required : new String[]{"event_id", "event_type", "event_time", "ingest_time",
                "source_system", "schema_version", "trace_id"}) {
L58-60:     if (isBlank(text(node, required))) { return new Violation(..., "缺失必要字段: " + required); }
```

⇒ **`event_time` 只做「非空」检查**。全文件**没有**任何 ISO-8601／偏移／时区／可解析性校验（R5 中 `BAD_TIME` 等 0 命中即其反面证据）。

**D. ODS 装载**（`OdsLoadSql.scala`，sha256 `5206DD69…`，291 净行）

```
L90:  "event_time" -> "event_time",                                  ← 透传，不改写
L168: "  REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt,\n" +
L169: "  SUBSTR(event_time, 12, 2) AS hour"
L174: INSERT OVERWRITE TABLE ${ns.ods}.$table PARTITION (dt, hour)
L181: AND event_id IS NOT NULL AND event_time IS NOT NULL            ← 静默丢弃
L208: StructField("event_time", StringType, nullable = false)        ← 显式 schema
L269-276: def rejectedSelect(): 只产 'BAD_VERSION_OR_KEY'，且**无生产调用**
```

⇒ **分区 `dt`/`hour` 由「位置切片」得出，不经过任何时间解析**。任何非 `yyyy-MM-ddTHH:mm:ss…` 形态都会**静默**产生错分区或不报错地扭曲业务日。

**E. DWD 侧**（`DwdSql.scala`，sha256 `A38EC916…`，71 净行；**该文件正被 P2-03 改写**）

```
L34: FROM_UTC_TIMESTAMP(FROM_UNIXTIME(UNIX_TIMESTAMP(rn.event_time)), 'Asia/Shanghai') AS event_time
L35: SUBSTR(rn.event_time, 1, 10) AS event_date
L36: CAST(SUBSTR(rn.event_time, 12, 2) AS INT) AS event_hour
```

⇒ **同一个 SELECT 内存在两套时间口径**：L34 走「解析式 + 固定 Asia/Shanghai」，L35/L36 走「原始字符串位置切片」。已用 `git show HEAD:` 核对：**L22/L23/L24 在 HEAD `b04ab09` 就已如此**（即该不一致**早于**本泳道，非新引入）。

**F. 其他时区 owner**

| owner | 位置 | 值 |
|-------|------|----|
| Spark session | `SparkSessionFactory.scala:14` | `.config("spark.sql.session.timeZone", "Asia/Shanghai")`（写死） |
| Java 默认 | `RuntimeProfileServiceImpl.java:72` | `p.setTimezone("Asia/Shanghai")`（为空时兜底） |
| DB | `source_registry.timezone` / `runtime_profile.timezone` | 均为 `Asia/Shanghai`（R4.2） |

⇒ **时区共有 4 个可写点**，其中 2 个是「写死/兜底」常量。这是「统一 UTC」诉求的真正难点（待裁决 D-103）。

**G. ODS DDL**（`warehouse/ddl/00-ods.sql`，sha256 `2BEBE55F…`）

```
L21: event_time      STRING  COMMENT '业务时间(ISO-8601带时区)',
L22: ingest_time     STRING  COMMENT '采集时间，链路延迟=ingest_time-event_time',
L48 / L80 / L107: event_time STRING          ← 另三张 ODS 表同型
```

⇒ `event_time` 保持 `STRING`（与 D-053 一致）。

---

## 7. R7 源画像格式列表的载体（设计书 §4.2）与真实画像缺失

`docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md`（sha256 `BDC62679…`，301 净行）：

```
L138-141:
  "timePolicy": {
    "field": "created_at",
    "formats": ["ISO_OFFSET_DATE_TIME", "EPOCH_MILLIS", "yyyy-MM-dd HH:mm:ss"]
  },
```

`analytics-server/source-profiles/README.md`（sha256 `7B385B8C…`）：

```
L9:  本目录中文件的键名契约见 docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md §4.2（9 个顶层必备键）。
L17: | mock-mall.v1.json | **真实源画像** | **P3-01 交付物，当前不存在** | ... profile_path 指向本文件 |
L21-23: V16 种子源（id=1）当前不可激活：其 profile_path 指向尚不存在的 mock-mall.v1.json ...
L25: ⇒ **P3-01 必须交付 mock-mall.v1.json** ... 在此之前，任何验收都不得依赖"激活种子源"这一步
```

`SourceProfileValidator.java`（sha256 `0C4CC657…`，140 净行）：

```
L20-22: 本类不是完整画像 Schema 校验器——每个键内部的子结构（eventTypeMapping 的枚举闭集、
        identityPolicy 的字段形状等）归 P3-01。本类只钉 P1-03 能钉的最小口径。
L35-44: REQUIRED_TOP_LEVEL_KEYS = 9 个键（含 "timePolicy"、"quarantinePolicy"）
L89-93: for (String key : REQUIRED_TOP_LEVEL_KEYS) { if (!root.has(key)) missing.add(key); }
```

⇒ **`timePolicy` 只被检查「键是否存在」，其内部（含 `formats`）完全未校验**；`formats` 的取值语法、是否闭集、是否允许 pattern 字符串，**今天没有任何权威定义**。

夹具实测取值（`p1-03-probe-1.v1.json:27-30`，sha256 `F525DF73…`）：

```json
"timePolicy": {
  "field": "created_at",
  "formats": ["ISO_OFFSET_DATE_TIME", "EPOCH_MILLIS", "yyyy-MM-dd HH:mm:ss"]
}
```

⇒ 三种取值**混用两种词汇体系**（两个 Java 风格 token + 一个 `DateTimeFormatter` pattern 字符串），且 **`formats` 是数组却没有任何顺序语义定义**（plan 说「依次解析」，但「依次」的判据未定义）。

---

## 8. R8 被引用文件指纹（防在飞漂移）

- 输出：`raw/source-fingerprints-20260912.txt` — 6,334 B，sha256 `CCAB715DD7A3CE7C43DFEFAE2C88176ACB05C03CC4D9C90BAE3520A2C8BB8567`，43 行，`MISSING_COUNT=0`
- 时钟：20:13:21.904；HEAD `ce36065`
- 口径：逐字节计 LF/CRLF，净行数 = LF − CRLF

其中**在飞泳道（P2-03）改写的文件**（本泳道只读，引用行号前必须重取）：

| 文件 | bytes | 净行 | sha256 | mtime |
|------|-------|------|--------|-------|
| `spark-jobs/.../sql/DwdSql.scala` | 3,854 | 71 | `A38EC916C547D5317F2A89041BBAA8EBB65EDFFA1CD66B7B38534031066BD9D3` | 2026-09-12 19:59:52 |
| `spark-jobs/.../job/LocalSchemaInitJob.scala` | — | — | 见文件 | — |
| `spark-jobs/.../job/TradeDwdJob.scala` | — | — | 见文件 | — |
| `spark-jobs/.../sql/DimSql.scala` | — | — | 见文件 | — |
| `warehouse/ddl/01-dwd.sql` | 6,199 | 96 | `F682FD468A9166D6E3CE9D3B1D3E19FE3331182707DE2C3079D7C6A8EE0ED088` | 2026-09-12 20:01:08 |
| `warehouse/ddl/02-dims.sql` | — | — | 见文件 | — |

**看板漂移留档**：

| 时点 | bytes | 净行 | sha256 |
|------|-------|------|--------|
| 开工（20:00 前） | 363,844 | 489 | `C99989B718903859A4B00FEF2AA2401C2A479506879B70F84D39B0E051FFD801` |
| 20:11:38 | 375,395 | 493 | `94D7C1CA8DCA7EC16EF4EFE211B9A5A9C7EE0AA5189A229043FC872398AA2D34` |
| 20:12:43 | 378,225 | 494 | `0EBC3D34E7EB64520EC43EB0C82FAACD161D4C507BB49B0BE9D01AD9B5643AA9` |

⇒ 看板**正被总控持续改写**，本泳道**未修改**它（依据：`git status` 中它不出现在已修改列表内）。

P2-02 行在改写后仍为（20:12:11 复读，L221，与开工时逐字相同）：

```
| P2-02 | 多格式时间与 quarantine 原因码 | `TODO` | A/B | P2-01 | 格式、未知版本、坏时间负例。**2026-09-12 19:54 只读核对**：无台账行／无验收目录／无实现提交 ⇒ `TODO` 与事实一致；**规格与施工单均未出**（不可据此开工）。 |
```

---

## 9. 未测 / 证据不足（明确登记，不得当作已测）

| 编号 | 未测项 | 为什么未测 | 影响 |
|------|--------|-----------|------|
| U1 | `UNIX_TIMESTAMP('2026-09-01T09:00:00+08:00')` 在 Spark 3 下的真实返回（NULL 还是正确秒数） | 需要起 SparkSession／跑 spark-sql，属重操作且会写 metastore／warehouse；本泳道权限仅止于只读文件与只读 SQL | 决定 `DwdSql.scala:34` 是否**已经**在产 NULL。**未测 ⇒ 不作断言** |
| U2 | 非 `+08:00` 形态进入 ODS 后 `dt`/`hour` 的实际错分区结果 | 同上：需要真实写入并用 `INSERT OVERWRITE` 建分区 | 本规格只能从**字符串位置切片**这一代码事实推断风险，**未实测** |
| U3 | 源 A 的真实 `timePolicy.formats` 取值 | `mock-mall.v1.json` **不存在**（P3-01 交付物） | 「支持哪些格式」**无法从真实源取得**，只能由裁决给出 |
| U4 | 多格式解析的**构造夹具**是否存在可复用基座 | 未在 `tests/fixtures/**` 内做穷举枚举（时间预算优先给了真实语料全量扫描 R1） | 施工单 A7 的夹具落点需先做一次 `tests/fixtures` 盘点（列为施工前置） |
| U5 | `FUTURE_TIME` 是否曾被写入 `dwd_reject_record` 的历史分区 | 未读取 `spark-warehouse/dw_dwd.db/dwd_reject_record` 的实盘 parquet（重读 + 需 Spark 或 parquet 读取器） | 只读证据仅到「源码里从不写」；历史分区内容 **未测** |
| U6 | 采集侧是否已有"时间格式"相关的运行期失败 | 只读 DB 未出现时间类 reason／rule_code；但日志目录未做全量扫描 | 「今天没有时间类故障」的证据仅覆盖 DB 两个表，**日志未测** |
| U7 | `spark.sql.session.timeZone` 对 ODS 装载的实际影响面 | 同上（需跑 Spark） | 规格中把它列为「候选 owner」而非「已生效 owner」 |
| U8 | 归一后 `event_time` 是否仍命中 `iso8601_time` pattern（端到端） | 无实现，无可测对象 | 列为出口判据 C5，**当前未测** |

**另一条必须声明的取证边界**：本泳道**未运行**任何测试、未构建、未提交。所有「实现现状」结论均来自**静态只读**（源码 + DDL + 契约 + 真库只读查询）。

---

## 10. 原始文件清单（含 sha256）

| 文件 | bytes | sha256 |
|------|-------|--------|
| `raw/scan-event-time-shapes.ps1` | 4,678 | `9B7DCC4B9C28840D85F2693D06CC18AD31D25586772F1454FDE3B89C474750A6` |
| `raw/scan-event-time-shapes-20260912.txt` | 25,018 | `D52E716111E780ACF71E9406E46127C23FF420DB67AD2DE95FF7116A2432FDF5` |
| `raw/db-quarantine-schema-20260912.txt` | 2,072 | `6CA1D72D0C29457B57270DEF6E983A59648515E7788FDAEE1A2C032A107A793D` |
| `raw/db-reason-distribution-20260912.txt` | 2,601 | `13D8FAF2BDA13CEDDA0D02E45B04673996EE02C92FED7016E20E072F73E82DFA` |
| `raw/db-dq-and-reject-20260912.txt` | 3,477 | `AD97A32E873F0F8684F2A0F2C0EC88614288AF0F9DB0C720792F399273A50117` |
| `raw/db-source-profile-tables-20260912.txt` | 2,708 | `E1F2D0711A3A2EB4A07B9EFA2EE9B725FCEA74E385FF6656FFAB2C72DA7EA4D7` |
| `raw/db-source-registry-values-20260912.txt` | 1,016 | `8DF1BB0BE7CC2CBA70019925EAB0C4AC6840258734B1DA68FC324FC3529CCF23` |
| `raw/db-batch-file-counters-20260912.txt` | 2,447 | `AA7B20D9323814B9FA3B9044106CD889F678229FA759B0B7E39C1E05A5BBE170` |
| `raw/time-semantics-greps-20260912.txt` | 7,096 | `E00B9BE94B3A9F19913AD53E7C7D55BE8B9B37980411A19506458D1F94A62B0D` |
| `raw/time-chain-anchors-20260912.txt` | 2,976 | `ABF71831D712B210889976E3B51947F20F2C5BF8470ECA0BEE15DFB0FDA17A53` |
| `raw/source-fingerprints-20260912.txt` | 6,334 | `CCAB715DD7A3CE7C43DFEFAE2C88176ACB05C03CC4D9C90BAE3520A2C8BB8567` |
