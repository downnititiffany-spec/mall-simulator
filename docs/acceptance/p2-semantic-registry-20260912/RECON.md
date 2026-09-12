# P2 语义注册表与字段映射层 —— 只读取证报告（RECON）

| 项 | 值 |
|---|---|
| 泳道 | P2 语义注册表与字段映射层（只读取证，**未改任何生产/测试代码**） |
| 上位依据 | `docs/项目完整实施指导书 V2.4.md` L693（P1–P3 硬前置含"语义注册表"）＋ L324（`schema_mapping`）＋ L344–L350（§5.4 标准化与映射） |
| 工作目录 | `D:\Develop_code\GraduationProject` |
| 开工时 HEAD | `de253afca7d14486d94a86999341550bf6ca2f3b`（任务书给定；**开工实测即此值**） |
| 收工时 HEAD | `54418f0767be612194128ce1d63124aa58e2e3c6`（**窗口内前进 3 个 commit**，见 §2 勘误 E-06） |
| 报告成文时刻 | 2026-09-12 13:28–13:35（本机时钟） |
| 证据级别 | **E1 为主**（读源码/读契约/读真库 `information_schema` 与 `SELECT`/读真实落盘产物）＋ 一次**独立复算**（§7.4，Python 重放）；**未运行任何 Maven/测试，未触碰 8090/8091/8092 进程，未做任何库写操作** |
| 原始读数 | 同目录 `raw/`（8 个文件，见 §12.3） |

---

## §0 一句话结论

指导书与设计文档**已经完整写好了**这个层的规格（表、画像键、原因码、流水线顺序），但**实现侧一行都没有**：真库无 `schema_mapping`、无 `semantic_registry`、无 `dimension_registry`，`analytics_meta`/`analytics_metric` 两个 schema 里**没有任何一列**名字含 `mapping`/`enum`/`semantic`/`contract`；`EventNormalizer` 在**全部受管文件里零代码命中**（只存在于 3 份指导书、1 份看板、1 份计划文档的正文中）；唯一像映射的东西是 `analytics-server/source-profiles/*.v1.json` 文件，而它在生产代码里**只被校验「9 个顶层键名是否存在」，键值从未被任何主代码读取用于转换**（`mappingVersion` 在 `IngestionService.java:300` 硬编码为 `null`）。因此"M2 商城无关化"的**配置驱动映射这一环当前是"有规格、有容器承诺、无执行者"**。

---

## §1 任务与范围

### 1.1 上位依据逐字（不改一字，勿转述）

**V2.4 L324**（§5.2 元数据结构表内一行）：

> \| `schema_mapping` \| id、source_schema_version、target_contract_version、event_type_map、field_mapping_json、enum_mapping_json、timezone、amount_unit、transform_policy、status、version \| 把源字段映射为平台契约 \|

**V2.4 L344–L350**（§5.4 标准化与映射，全节逐字）：

> ### 5.4 标准化与映射
>
> 采集只负责可靠移动和断点；`EventNormalizer` 负责源事件到 canonical event 的转换：事件类型映射、JSONPath 字段提取、枚举映射、时区转换、金额单位转换和允许的纯函数变换。禁止在映射表达式中执行任意脚本或 SQL。
>
> 未知源版本、没有 ACTIVE mapping、必填字段缺失和不允许的转换全部进入 quarantine，并保存 source instance、mapping version、reason code 和原始位置。映射激活必须先用样本 dry-run，并输出接受/隔离/错误计数。
>
> 映射的最终真相仍是版本化配置和审核记录，不是大模型对话。P1–P5 先证明人工/配置驱动接入异构源；其后 AI 可以读取字段元数据和脱敏样本，生成 `SourceProfileDraft`、字段映射建议、置信度和待确认问题。低置信度、金额、退款、用户身份、时区等业务语义必须由人确认，AI 草案不能直接变为 ACTIVE mapping。

**V2.4 L693**（§13.2 与商城无关化主线的关系）：

> - P1–P3 是硬前置：必须先有 source registry、每源 namespace、ODS 保真、代理键和语义注册表，AI 才有稳定目标契约。

### 1.2 铁律遵从声明

- **只读**：全部库访问为 `information_schema` 查询与 `SELECT`；无 `INSERT/UPDATE/DELETE/DDL`。
- **未改生产/测试代码**：本泳道只新建 `docs/acceptance/p2-semantic-registry-20260912/**`（`RECON.md` ＋ `raw/`），未写仓库内其它任何路径，未改 `docs/项目实施进度与任务看板 V2.2.md`。
- **未做 git 写操作**：仅用 `git rev-parse` / `git status` / `git ls-files` / `git ls-tree` / `git grep` / `git log` / `git reflog` / `git diff --stat` 等只读命令。
- **未启停进程**：全程未触碰 8090/8091/8092。
- **未删除任何文件**；仅删/建自己 `%TEMP%` 下的脚本（`%TEMP%\p2sr-schema-probe.ps1`、`p2sr-replay.py`、`p2sr-exact-counts.ps1`）。
- **零命中均带正向对照**：见 §4.1、§6.1、§6.3、§8.2 逐处。

---

## §2 方法与勘误（**必读：含一处已构成假红风险的方法缺陷**）

### 2.1 勘误 E-01（**最高优先级**）：`information_schema.TABLES.TABLE_ROWS` 对 InnoDB 是近似值

**缺陷**：`raw/q1-tables.tsv` 的第三列读的是 `information_schema.TABLES.TABLE_ROWS`。该列对 InnoDB 是**统计估算**，不是精确计数。

**总控独立复核指出并由本轮实测确认**（同一时刻 approx vs exact）：

| 表（schema 限定） | `TABLE_ROWS`（近似） | `COUNT(*)`（精确） | 后果 |
|---|---|---|---|
| `analytics_meta.source_registry` | **0** | **1** | **若据此表 → 假红**："源注册表为空 ⇒ V16/V17 源实例迁移未生效"，与事实相反 |
| `analytics_meta.runtime_profile` | 2 | 1 | 假异常："存在 2 条 runtime_profile" |
| `analytics_meta.pipeline_run` | 38 | 40 | 少计 |
| `analytics_meta.data_quality_result` | 379 | 401 | 少计 |
| `analytics_meta.file_checkpoint` | 102 | 105 | 少计 |
| `analytics_meta.metric_snapshot` | 9 | 9 | 恰好相同 |
| `analytics_meta.quarantine_record` | 104 | 104 | 恰好相同 |

> **关键教训**：后两行"近似＝精确"说明**不能靠"读数看起来合理"发现该缺陷**。本泳道开工时确实在 `flyway_schema_history` 上撞到过同一现象（`TABLE_ROWS` 报 14，而 `SELECT` 实返 16 行），当时用查询结果覆盖了估算值；但 `raw/q1-tables.tsv` 那一列仍是近似值，未加标注 —— 这是本泳道的判据缺陷。

**处置**（不改历史文件）：

1. `raw/q1-tables.tsv` **原样保留不动**；
2. 新增 **`raw/q1-tables-exact.tsv`**：对**同一批表**（`analytics_meta` ＋ `analytics_metric` 全部 33 张 BASE TABLE，**schema 限定**）用 `SELECT COUNT(*)` 精确计数，列名为 **`exact_count`**；
3. **本报告所有"表为空 / 表未使用 / 表刚建 / 行数"的判断一律只引用 `q1-tables-exact.tsv`**（§4.2 起）。

**可复跑命令**（读只读；`%TEMP%` 下脚本已留）：

```powershell
# 枚举 33 张基础表 → 逐表 SELECT COUNT(*) → 写 raw/q1-tables-exact.tsv
pwsh -NoProfile -File "$env:TEMP\p2sr-exact-counts.ps1"
```

实测读数：`TABLES_ENUMERATED=33`，33 行全部有值（无表查询失败）。

### 2.2 勘误 E-02：本泳道第一条 `git grep` 因 PowerShell 引号缺陷**执行失败**，已重做

原命令在双引号串内用了 `\"`，PowerShell 将其解析为命令分隔，stderr 报 `术语 'view' 不会被识别为 cmdlet…`。**该次结果作废**；改用逐 token 的 `git grep -n -I '<literal>'` 重做，并在同一批次内附正向对照 `'order_created'`（命中 `OdsLoadSql.scala:195`）。重做后的读数见 §6.3。

### 2.3 勘误 E-03：一次探针查询使用了不存在的列

我为验证"quarantine 是否保存 source instance"构造过 `SELECT SUM(LENGTH(source_instance_json)>0)`，MySQL 返回 `ERROR 1054 (42S22): Unknown column 'source_instance_json' in 'field list'`，`EXIT=1`。

- 该错误**本身**是"`quarantine_record` 无此列"的一条证据，但它是**我构造的探针失败**，不能算系统性取证；
- 系统性证据是 §4.2 的 `information_schema.COLUMNS` 直读；
- 此失败**已如实记录**，未从报告中隐去。

### 2.4 勘误 E-04：PowerShell 行数统计与真实行数不符

`(Get-Content -LiteralPath 'docs/项目完整实施指导书 V2.4.md' -Encoding UTF8 | Measure-Object -Line).Lines` 报 **519**；而 `Get-Content` 数组实长 **720**，`read` 工具亦报 total 720 lines，两端一致。

⇒ 本报告一律以"`$c.Count` / `$c[692]` 式索引"或 `read` 工具行号为准；`Measure-Object -Line` 的读数在本报告中**不出现**（避免行号错位引用）。

### 2.5 本轮**复现**的既有陷阱（非勘误，是给后续泳道的护栏）

**T-01 字符类过滤器静默零命中（本项目已知 5 例同类，本轮第 6 次复现）**：

```powershell
(Get-ChildItem -LiteralPath 'analytics-server/platform-app/src/main/resources/db/meta' -Filter 'V1[5-8]__*.sql').Count
# 实测 → 0   （静默、无错、看起来像"这些迁移不存在"）
(Get-ChildItem -LiteralPath 'analytics-server/platform-app/src/main/resources/db/meta' -File |
  Where-Object { $_.Name -match '^V1[5-8]__' }).Name
# 实测 → V15__stage_evidence_mediumtext.sql,V16__source_registry.sql,
#         V17__source_dimension_for_checkpoint_and_batch.sql,V18__source_warehouse_prefix.sql  （4 命中）
```

⇒ `-Filter` 的 `[5-8]` 被当作字面字符而非字符类。**凡列举迁移文件必须用 `Where-Object { $_.Name -match ... }`。**

**T-02 `git ls-files`/`git grep` 不加 `-z` 时非 ASCII 路径被 C 引号转义**：本轮输出中可直接看到 `"docs/\351\241\271\347\233\256..."` 形态（V2.4 等中文文件名）。⇒ 本报告凡列举中文名文件，均改用 `git ls-files -z` 拆分或 `Get-ChildItem -LiteralPath`，**不据转义串断言"不存在"**。

**T-03 同名表跨 10 个 schema，且真库内也有同名陈旧表**（总控附告，本轮已独立复核）：

```
information_schema.TABLES WHERE TABLE_NAME IN ('metric_snapshot','metric_value')
→ metric_snapshot 命中 10 个 schema；metric_value 命中 10 个 schema：
  analytics_meta, analytics_meta_p103, analytics_meta_p105, analytics_meta_p105it,
  analytics_meta_v17probe, analytics_metric, analytics_metric_p103, analytics_metric_p105,
  mall_simulator, mall_simulator_test
```

其中 `analytics_meta.metric_snapshot`（精确 9 行）是**真库内的同名陈旧表**；平台实际读写的是 `analytics_metric.metric_snapshot`（精确 9 行，两者精确读数恰好相同 —— 又一个**不能靠"数字看着对"发现**的陷阱）。

⇒ **本报告全部查询均已 schema 限定**（形如 `analytics_meta.xxx` 或 `WHERE TABLE_SCHEMA='analytics_meta'`）；§4.1 的普查**只覆盖 `analytics_meta` 与 `analytics_metric` 两个 schema**，未混入任何 `_p103`/`_p105`/`_p105it`/`_v17probe`/`mall_simulator_test` 副本。**本泳道此前产出的 `q1-tables.tsv` 亦只含这两个 schema，无副本混入，此项无需勘误。**

**T-04 HEAD 在取证窗口内前进**（见 E-06）。

**T-05 控制台为 GBK**：本报告所有中间脚本的 stdout **只用 ASCII**（Python 脚本内不含 `⇒`/`→`）；中文只写入 UTF-8 文件。

### 2.6 勘误 E-06：HEAD 在窗口内前进 3 个 commit（**影响所有"HEAD 态"引用**）

| 观测点 | `git rev-parse HEAD` |
|---|---|
| 开工（本泳道第一条命令） | `de253afca7d14486d94a86999341550bf6ca2f3b` |
| 收工 | `54418f0767be612194128ce1d63124aa58e2e3c6` |

`git rev-list --count de253af..HEAD` = **3**。`git reflog` 显示三笔：

- `54418f0` 13:28:07 `P2-07-b 施工单 §1.1：A0 认库现场答毕…＋ 实测 V18 未在真库执行（flyway max=17）`
- `f530017` 13:27:10 `P2-07-b 施工单成文…`
- `8ae0148` 13:25:44 `证据保全：P1-05 目录 8091-stdout.log 的运行时追加段落入库…`

**重要**：`git log de253af..HEAD -- 'analytics-server/platform-app/src/main/resources/db/meta/'` **为空** ⇒ 窗口内**无任何 commit 触碰迁移目录**，故 §5（Q2）的迁移清单结论不受 HEAD 前进影响。另：`54418f0` 的提交信息**独立复述了**本泳道 §5 的核心读数（"实测 V18 未在真库执行（flyway max=17）"）—— 两条独立路径同值。

工作树另有 **34 文件 / +1380 −400** 未提交改动（`git diff --stat`），主要在 P1-05/P2-01/P2-07 在飞模块；本报告凡引用具体行号处均附 §12.2 的 sha256，**行号有效性以该 sha256 为准**。

---

## §3 方法、证据级别与判据口径

| 级别 | 含义 | 本报告用在哪 |
|---|---|---|
| **E1** | 直接读仓库文件 / 直接读真库只读查询 / 直接读落盘产物 | Q1–Q6 的**全部**事实 |
| **E2** | 独立复算（重放规则、重新计数） | Q5 的 51/26 复算（§7.4） |
| **E3** | 端到端运行（起进程、跑接口、跑 Maven） | **本泳道未做**（见 §11） |

**判据口径（防假绿）**：

- 凡"不存在 / 零命中"结论，**必须**同批附"同形状查询对确定存在对象有非零命中"的正向对照；
- 凡"表为空 / 未使用"，**只**用 `SELECT COUNT(*)` 精确值（E-01）；
- 凡引用契约字段/枚举，**必须**给文件名＋行号，并优先用机器可读解析（§7.4 用 JSON 解析而非目视）；
- 凡"代码里没有 X"，**必须**给正向对照 token（§6.1）。

---

## §4 Q1 —— 表/载体现状：真库有没有 `schema_mapping` 及同类表？

### 4.1 回答：**没有**。两个 schema 里不存在任何映射表/枚举表/语义注册表。

**命令**（只读，schema 限定）：

```powershell
$mysql='C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
& $mysql -uroot -p123456 -N -B -e @"
SELECT TABLE_SCHEMA,TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric')
  AND (TABLE_NAME LIKE '%mapping%' OR TABLE_NAME LIKE '%enum%' OR TABLE_NAME LIKE '%semantic%'
       OR TABLE_NAME LIKE '%registry%' OR TABLE_NAME LIKE '%normaliz%' OR TABLE_NAME LIKE '%canonical%')
ORDER BY 1,2;"@
```

**实测输出（`EXIT=0`，原样 1 行）**：

```
analytics_meta	source_registry
```

`raw/q1-absence-probe-tables.tsv`（114 B）逐字即上述 1 行。

**正向对照（同一通道内）**：该 LIKE 通道**能**命中 —— `%registry%` 命中了确定存在的 `analytics_meta.source_registry`；`%mapping%`/`%enum%`/`%semantic%`/`%normaliz%`/`%canonical%` 各自 0 命中是**真阴性**，不是通道坏。

**再加一层全库探针**（不加 schema 限制，最大范围）：

```sql
SELECT TABLE_SCHEMA,TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_NAME LIKE '%schema_mapping%' OR TABLE_NAME LIKE '%enum_mapping%' OR TABLE_NAME LIKE '%semantic_registry%';
```

**实测输出：0 行**（`EXIT=0`）。⇒ **整个 MySQL 实例里没有任何 schema 存在 `schema_mapping` / `enum_mapping` / `semantic_registry`。**

### 4.2 列级探针（比表名探针更强）：**没有任何一列**名字含 `mapping`/`enum`/`semantic`/`contract`

**命令**：

```sql
SELECT 'CTRL_source',COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND COLUMN_NAME LIKE '%source%'
UNION ALL SELECT 'CTRL_version',COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND COLUMN_NAME LIKE '%version%'
UNION ALL SELECT 'mapping',COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND COLUMN_NAME LIKE '%mapping%'
UNION ALL SELECT 'enum',COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND COLUMN_NAME LIKE '%enum%'
UNION ALL SELECT 'semantic',COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND COLUMN_NAME LIKE '%semantic%'
UNION ALL SELECT 'contract',COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND COLUMN_NAME LIKE '%contract%';
```

**实测输出（`EXIT=0`；`raw/q1-column-probe-with-controls.tsv`，158 B）**：

| 探针 | 命中列数 | 判读 |
|---|---|---|
| `CTRL_source` | **11** | ✅ **正向对照成立**（通道有效） |
| `CTRL_version` | **22** | ✅ **正向对照成立**（通道有效） |
| `mapping` | **0** | 真阴性 |
| `enum` | **0** | 真阴性 |
| `semantic` | **0** | 真阴性 |
| `contract` | **0** | 真阴性（`EventContract` 只是 Java 类名，未落库为列名） |

同一形状的扩展探针（含 `%canonical%`/`%normaliz%`/`%jsonpath%`/`%transform%`）**返回 0 行**（`EXIT=0`）。

### 4.3 设计文档点名的三张表逐张点名探针

设计文档 `docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md` §4.3（L151–L159）**点名**了 `semantic_registry`、`dimension_registry`，并要求 `metric_definition` 增列：

```sql
SELECT 'semantic_registry' k, COUNT(*) n FROM information_schema.TABLES WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='semantic_registry'
UNION ALL SELECT 'dimension_registry', COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='dimension_registry'
UNION ALL SELECT 'CTRL_source_registry', COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='source_registry'
UNION ALL SELECT 'CTRL_metric_definition', COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='metric_definition';
```

**实测输出（`EXIT=0`）**：

| 探针 | n | 判读 |
|---|---|---|
| `semantic_registry` | **0** | **不存在** |
| `dimension_registry` | **0** | **不存在** |
| `CTRL_source_registry` | **1** | ✅ 正向对照成立 |
| `CTRL_metric_definition` | **1** | ✅ 正向对照成立 |

`metric_definition` 实际列（`information_schema.COLUMNS` 直读，7 列）：
`metric_code, metric_name, formula, grain, default_time_field, unit, definition_version`

⇒ 设计 §4.3 L158 要求的 `source_code`（NULL=通用）与 `required_for_overview BOOLEAN` **两列都不存在**（该结论亦有 §4.2 的 `CTRL_source` 通道为背景：`%source%` 在库里 11 列有命中，但都不在 `metric_definition`）。

### 4.4 已知未完成项的独立复核：`metric_snapshot` 与 `pipeline_run` 均无 `source_id`

```sql
SELECT 'metric_snapshot.source_id' k, COUNT(*) n FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='metric_snapshot' AND COLUMN_NAME='source_id'
UNION ALL SELECT 'pipeline_run.source_id', COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='pipeline_run' AND COLUMN_NAME='source_id'
UNION ALL SELECT 'CTRL_pipeline_run.id', COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='pipeline_run' AND COLUMN_NAME='id';
```

**实测（`EXIT=0`）**：`metric_snapshot.source_id=0`、`pipeline_run.source_id=0`、`CTRL_pipeline_run.id=1` ⇒ **原判据成立，且通道有正向对照。**

### 4.5 现存"最接近的同类载体"：`quarantine_record`（它是**唯一的**语义级表）

**列清单**（`information_schema.COLUMNS`，`analytics_meta.quarantine_record`）：

| 列 | 类型 | 空 | 键 | 默认 |
|---|---|---|---|---|
| `id` | bigint | NO | PRI | auto_increment |
| `batch_id` | bigint | NO | MUL | — |
| `event_id` | varchar(64) | YES | | — |
| `schema_version` | varchar(16) | YES | | — |
| `reason` | varchar(255) | NO | | — |
| `raw_path` | varchar(500) | NO | | — |
| `created_at` | datetime(3) | NO | | CURRENT_TIMESTAMP(3) |

**精确行数**：**104**（`raw/q1-tables-exact.tsv`；近似值亦 104，两者相同）。

**约束与外键**：

- 约束：仅 `PRIMARY KEY(id)`；**无 UNIQUE、无 CHECK、无 FK**（`information_schema.TABLE_CONSTRAINTS` 直读，该表只返回 1 行 `PRIMARY`）。
- 索引：`KEY idx_quarantine_batch (batch_id)`（DDL `V1__platform_ingestion.sql:54`）。
- **对比**：同库其它表**有** FK —— `fk_ingestion_batch_source`（`ingestion_batch.source_id → source_registry.id`）、`fk_file_checkpoint_source`（`file_checkpoint.source_id → source_registry.id`）、`fk_runtime_profile_source`（`runtime_profile.source_id → source_registry.id`）、`fk_session_user`（`user_session.user_id → sys_user.id`）。⇒ **`quarantine_record` 是全库唯一"带着来源语义却不挂 FK"的证据表**，`batch_id` 是裸 bigint。

**精确行数普查**（`raw/q1-tables-exact.tsv`，33 张 BASE TABLE，**只含两个目标 schema**）：

- `analytics_meta` **22 张**：`ai_call_log`(0)、`ai_query_history`(54)、`data_quality_result`(401)、`decision_evaluation`(4)、`decision_task`(8)、`file_checkpoint`(105)、`flyway_schema_history`(16)、`ingestion_batch`(40)、`ingestion_batch_file`(112)、`metric_definition`(16)、`metric_snapshot`(9)、`metric_value`(132)、`operation_audit_log`(92)、`pipeline_run`(40)、`pipeline_stage_run`(252)、`quarantine_record`(104)、`runtime_profile`(1)、`source_registry`(**1**)、`spark_job_run`(216)、`sys_user`(3)、`t_ckpt_test`(0)、`user_session`(168)
- `analytics_metric` **11 张**：`ads_active_trend_m`(8)、`ads_behavior_funnel_m`(32)、`ads_data_quality_m`(32)、`ads_hot_product_m`(42)、`ads_operation_overview_m`(8)、`ads_product_conversion_m`(42)、`ads_sale_trend_m`(8)、`ads_user_profile_m`(20)、`flyway_schema_history`(3)、`metric_snapshot`(9)、`metric_value`(80)
- 合计 **33** = `TABLES_ENUMERATED=33` ✔

### 4.6 `source_registry` 的**精确内容**（Q7/Q8 的正向基线）

```sql
SELECT id,source_code,display_name,ingest_mode,profile_path,timezone,currency,status,profile_version
FROM analytics_meta.source_registry;
```

**实测（`EXIT=0`，1 行）**：

```
1	mock-mall	参考商城（源 A）	FILE	analytics-server/source-profiles/mock-mall.v1.json	Asia/Shanghai	CNY	ACTIVE	1.0
```

来源：`V16__source_registry.sql:41-44`（`INSERT … SELECT 'mock-mall', … 'ACTIVE', '1.0' WHERE NOT EXISTS (…)`）。

⇒ **现状基线（后续 Q7/Q8 均以此为起点）**：**源实例已登记（1 行、`status=ACTIVE`、11 列）**，但 `profile_path` 指向的 `analytics-server/source-profiles/mock-mall.v1.json` **在仓内不存在**（§6.2）。

### 4.7 Q1 结论

| 问 | 答 |
|---|---|
| 真库有 `schema_mapping` 吗？ | **没有**（表名、列名两层探针皆 0，全实例探针亦 0；通道有 2 个正向对照） |
| 有同类表（映射/枚举/语义注册表）吗？ | **没有**。`semantic_registry`=0、`dimension_registry`=0；两 schema 内**无任何列**含 `mapping`/`enum`/`semantic`/`contract` |
| 唯一的"语义级"现存表 | `analytics_meta.quarantine_record`（104 行，7 列，**无 FK**） |
| 源注册表现状 | `source_registry` **精确 1 行**（id=1 `mock-mall`，`status=ACTIVE`） |
| 指导书 L324 的 11 列落地了吗？ | **0/11 落地**（表本身不存在） |

---

## §5 Q2 —— 迁移号位：仓内文件 vs 真库 `flyway_schema_history`

### 5.1 仓内迁移文件（**必须用 `Where-Object -match`，见 T-01**）

```powershell
Get-ChildItem -LiteralPath 'analytics-server/platform-app/src/main/resources/db/meta' -File |
  Where-Object { $_.Name -match '^V\d+__' } | Select-Object -ExpandProperty Name | Sort-Object
```

**实测（17 个文件，`EXIT=0`）**：

```
V1__platform_ingestion.sql                              V10__spark_job_run_output_partitions.sql
V2__platform_pipeline_quality.sql                       V11__data_quality_layers.sql
V3__platform_ai_audit.sql                               V12__quality_detail_width.sql
V4__platform_decisions.sql                              V13__metric_definition_r7.sql
V5__platform_users.sql                                  V14__r8_identity_decision.sql
V7__platform_runtime_profile.sql                        V15__stage_evidence_mediumtext.sql
V8__platform_ingestion_r3.sql                           V16__source_registry.sql
V9__platform_stage_evidence_widen.sql                   V17__source_dimension_for_checkpoint_and_batch.sql
                                                        V18__source_warehouse_prefix.sql
```

- **`REPO_VERSIONS = 1,2,3,4,5,7,8,9,10,11,12,13,14,15,16,17,18`；`REPO_MAX = 18`；`REPO_COUNT = 17`。**
- **无 V6**（与背景给定一致，本轮实测确认）。

### 5.2 真库 `flyway_schema_history`

```sql
SELECT installed_rank,version,description,script,success,installed_on
FROM analytics_meta.flyway_schema_history ORDER BY installed_rank;
```

**实测（`EXIT=0`，16 行，`raw/q2-flyway-history-real-db.tsv`）** —— 全部 `success=1`：

| rank | version | script | installed_on |
|---|---|---|---|
| 1–5 | 1,2,3,4,5 | V1/V2/V3/V4/V5 | 2026-09-07 |
| 6 | 7 | V7__platform_runtime_profile.sql | 2026-09-07 17:15:57 |
| 7 | 8 | V8__platform_ingestion_r3.sql | 2026-09-07 18:13:41 |
| 8 | 9 | V9__platform_stage_evidence_widen.sql | 2026-09-10 17:06:34 |
| 9 | 10 | V10__spark_job_run_output_partitions.sql | 2026-09-10 18:02:13 |
| 10 | 11 | V11__data_quality_layers.sql | 2026-09-10 18:38:59 |
| 11 | 12 | V12__quality_detail_width.sql | 2026-09-10 18:45:27 |
| 12 | 13 | V13__metric_definition_r7.sql | 2026-09-10 20:05:31 |
| 13 | 14 | V14__r8_identity_decision.sql | 2026-09-11 09:22:21 |
| 14 | 15 | V15__stage_evidence_mediumtext.sql | 2026-09-11 09:41:19 |
| 15 | 16 | V16__source_registry.sql | 2026-09-11 19:56:28 |
| 16 | 17 | V17__source_dimension_for_checkpoint_and_batch.sql | 2026-09-12 09:12:11 |

- **`DB_VERSIONS = 1,2,3,4,5,7,…,17`；`DB_MAX = 17`；行数 16（精确值；近似值误报 14，见 E-01）。**
- **无 V6**；**无 V18**。

### 5.3 差异逐条

```
IN_REPO_NOT_DB : 18
IN_DB_NOT_REPO : （空）
```

**唯一差异 = V18**。补充两条**必须同时说清**的事实：

1. **`V18__source_warehouse_prefix.sql` 在 HEAD 里不是受管文件**：

   ```powershell
   git status --porcelain -- '…/db/meta/V18__source_warehouse_prefix.sql'   # → ?? (untracked)
   git ls-files -- '…/db/meta/V18__source_warehouse_prefix.sql'             # → 空（NOT TRACKED）
   git ls-tree --name-only HEAD -- '…/db/meta/'                             # → V1…V17，无 V18
   ```

   ⇒ "下号 V18 已被 P2-07 占用"应精确表述为：**V18 只在工作树被"占位"（未提交，3697 B，sha256 `BACA5DF0…4E20F9`）**；**HEAD 态迁移最高号仍是 V17**。

2. **窗口内无 commit 触碰 `db/meta/`**（`git log de253af..HEAD -- '…/db/meta/'` 为空）⇒ 5.1/5.2 不受 HEAD 前进影响。

### 5.4 迁移执行机制（解释"为什么仓内有而库里没有"）

`analytics-server/platform-app/src/main/resources/application.yml`：

- `:41` 注释：`# 迁移由 MetaFlywayInitializer 显式执行（classpath:db/meta）；默认 flyway 关闭`
- `:43-44`：`flyway:` / `enabled: false`

⇒ 迁移由 Spring 侧 `MetaFlywayInitializer` 在启动时扫 `classpath:db/meta`。**8091 当前进程若未重启或运行旧 jar，就不会看到 V18**。本泳道**未验证** 8091 实际加载的 jar 内容（见 §11 U-06）。

### 5.5 Q2 结论

| 问 | 答 |
|---|---|
| 仓内有哪些迁移文件？ | 17 个（V1–V5、V7–V18），**无 V6** |
| 最高号？ | 工作树 **18**；**HEAD 受管态 17** |
| 与真库一致吗？ | **受管态完全一致**（都 1–5、7–17，都无 V6，max 都是 17）；**唯一差异是工作树未提交的 V18** |
| 我的新迁移该取哪个号？ | 见 §10 待裁问题 3（V19 已是 P2-07-b 的预期号，见 `git reflog` 该笔提交信息"V19 预期"） |

---

## §6 Q3 —— 契约载体：`contract-specs/**` 里有没有映射/枚举/语义注册表契约？

### 6.1 `contract-specs/` 全量清单（9 个受管文件）

```powershell
$z = git ls-files -z -- contract-specs
($z -split "`0" | Where-Object { $_ -ne '' })
```

**实测（`COUNT=9`）**：

```
contract-specs/README.md
contract-specs/VERSION
contract-specs/openapi/generator-api.v1.yaml
contract-specs/schemas/canonical-event.v1.schema.json
contract-specs/schemas/generation-artifact-manifest.v1.schema.json
contract-specs/schemas/ingestion-manifest.v1.schema.json
contract-specs/specs/surrogate-key.v1.json
contract-specs/specs/warehouse-namespace.v1.json
contract-specs/specs/warehouse-namespace.v2.json
```

### 6.2 状态字段逐文件实测

```powershell
git grep -n -E '"(status|state)"' -- 'contract-specs/*.json' 'contract-specs/**/*.json'
```

| 制品 | 状态 | 出处（行号） |
|---|---|---|
| `specs/surrogate-key.v1.json` | `DRAFT-2026-09-12` | **`:4`** |
| `specs/warehouse-namespace.v1.json` | `FROZEN-2026-09-11` | **`:4`** |
| `specs/warehouse-namespace.v2.json` | `FROZEN-2026-09-12` | **`:4`** |
| `schemas/canonical-event.v1.schema.json` | `x-contract-status: "DRAFT"`（**不是** `status` 键，故上条 grep 未命中它） | **`:67`** |
| `schemas/ingestion-manifest.v1.schema.json` | `DRAFT` | `README.md` §4 目录表 **`:52`** |
| `schemas/generation-artifact-manifest.v1.schema.json` | `DRAFT` | `README.md` §4（同表） |
| `openapi/generator-api.v1.yaml` | `DRAFT` | `README.md` §4（同表） |

`contract-specs/README.md:3` 逐字自述：

> 状态：**部分冻结**（`specs/surrogate-key.v1.json` 为 `DRAFT`，**尚无任何实现读取**…）… 其余四个制品仍为 `DRAFT`（`canonical-event.v1` 受 B-06/Q6 未决阻塞） ｜ 版本：`2.0.0`

✅ 交叉验证：README §2（`:33`）登记 `docs/contracts/event-contract.md` 的指纹 `FB991C17B9037B1B`；本泳道现算 `FB991C17B9037B1B10183B46BDCAB1B8240641E8929DE6A1298787CEDA68EE5D`，**前 16 位逐字相符** ⇒ 该来源文档自登记以来未变（这是本报告唯一复算的指纹项，见 §11 U-08）。

### 6.3 回答：**没有**任何描述映射/枚举/语义注册表的现行契约。

- 9 个制品中**没有一个**是映射契约或语义注册表契约；`specs/` 下只有 `surrogate-key.v1.json` 与 `warehouse-namespace.v1/v2.json`（对应 P2-03 代理键与 P2-07 命名空间，**都不是本层**）。
- `contract-specs/README.md:122` **明文把本层排除在契约目录之外**（逐字）：

  > - **V2.1 §5.2 元数据/映射相关 schema**（L172-L182 的 `source_instance`、`source_connector`、`schema_mapping`、`source_manifest`、`ingestion_batch_file`、`file_checkpoint` 的表与映射规格）：属 M2 连接器插件体系工作，尚未冻结字段语义，放进来必然靠猜。

- `contract-specs/README.md:123` 同节还把 source registry / source profiles 一并排除（D-036）。

### 6.4 现行**最接近**的三个载体（文件名 + 行号）

| # | 载体 | 精确位置 | 它提供了什么 / 缺什么 |
|---|---|---|---|
| **1** | `contract-specs/schemas/ingestion-manifest.v1.schema.json` 的 **`mappingVersion`** | **`:28-34`**（`"type": ["string","null"]`，`maxLength: 64`；`:34` description 逐字："本批次应用的词汇映射版本；P1-05 阶段**恒为 null**（映射在 P2 落地），语义 = 未应用任何映射。禁止写占位值（D-037 裁决 6）。"） | **全仓唯一为"映射版本"预留的机器可读契约槽位**。它只声明"有一个版本号"，**不描述映射内容** |
| **2** | `contract-specs/schemas/canonical-event.v1.schema.json` | 8 信封字段（`:8-43`）、`event_type` 12 类枚举（`:14`）、`source_system` const `"mock-mall"`（**`:39`**）、`schema_version` const `"1.0"`（**`:43`**）、`x-contract-status: DRAFT`（**`:67`**）、逐类型 `required`（`:94/:113/:132/:151/:170/:189/:208/:227/:246/:265/:284/:303`）、`amount` 正则（`$defs`）、`behavior.behavior_type` 枚举（**`:504-513`**）、`behavior.channel` 枚举与 required（**`:515-517` / `:525-530`**） | **映射的"目标词表"**（canonical 侧），状态 `DRAFT` 且受 B-06/Q6 未决阻塞 |
| **3** | `docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md` §4.2 + §4.3 | §4.2 **`:109-149`**（源画像 9 键的**散文规格**，含 `@keep` 哨兵语义 `:148`）；§4.3 **`:151-159`**（`semantic_registry` / `dimension_registry` / `metric_definition` 增列的**表规格**）；§4.3 **`:213`**（归属边界原则） | **本层真正的规格来源**，但**不是** `contract-specs/` 制品、**不是**机器可读 schema、**不在** `contract-specs/VERSION` 的版本管辖内 |

**⇒ Q3 结论**：契约目录里**没有**本层的现行契约；`mappingVersion` 是唯一的机器可读占位（且当前恒 `null`）；真正的规格在 `docs/superpowers/**` 的**设计文档与计划文档**里，属"散文规格"，未升格为 `contract-specs/` 制品。

---

## §7 Q4 —— 代码侧现状：谁读/写"映射或枚举或语义"？

### 7.1 **`EventNormalizer` 是否存在？—— 不存在（零代码命中，附正向对照）**

```powershell
git grep -n -I 'EventNormalizer'
```

**实测（`EXIT=0`，7 个文件命中，全部是文档）**：

| 文件 | 行 | 性质 |
|---|---|---|
| `docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md` | `:194` | 计划（`### P3-02 EventNormalizer`） |
| `docs/项目完整实施指导书 V2.1.md` | `:201` | 历史指导书 |
| `docs/项目完整实施指导书 V2.2.md` | `:213` | 历史指导书 |
| `docs/项目完整实施指导书 V2.3.md` | `:346` | 历史指导书 |
| `docs/项目完整实施指导书 V2.4.md` | `:346` | **现行权威** |
| `docs/项目实施进度与任务看板 V2.2.md` | `:228` | 看板（`P3-02 … \`TODO\``） |
| `docs/项目实施进度与任务看板.md` | `:196` | 旧看板 |

**⇒ 零个 `.java` / `.scala` / `.sql` / 配置命中。`EventNormalizer` 今天在任何模块里都不存在。**

**正向对照（证明 grep 通道本身有效）**：

```powershell
git grep -l -I 'EventContractValidator'
# → 7 个源文件命中，含真实主源码：
#   analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/ingestion/EventContractValidator.java
#   analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/ingestion/LocalFileIngestor.java
#   …
```

同通道对确定存在的标识符有非零命中 ⇒ `EventNormalizer` 的零命中是**真阴性**。

### 7.2 那"今天谁读映射键的值"？—— **没有人。**

对 6 个画像键逐一在**主源码**（`analytics-server/**/src/main/**`、`spark-jobs/src/main/**`、`mall-simulator/src/main/**`）内检索：

| 键 | 主源码命中 | 命中点性质 |
|---|---|---|
| `eventTypeMapping` | `SourceProfileValidator.java:21, :39` | `:21` 注释、`:39` 必备键**名字符串** |
| `fieldMapping` | `SourceProfileValidator.java:40` | 必备键**名字符串** |
| `enumSemantics` | `SourceProfileValidator.java:41` | 必备键**名字符串** |
| `identityPolicy` | `SourceProfileValidator.java:21, :42` | 注释 ＋ 必备键**名字符串** |
| `timePolicy` | `SourceProfileValidator.java:43` | 必备键**名字符串** |
| `quarantinePolicy` | `SourceProfileValidator.java:44` | 必备键**名字符串** |

**唯一命中点是 `SourceProfileValidator.REQUIRED_TOP_LEVEL_KEYS`（`:35-44`）这一个 List**，它只做**键存在性**检查：

```java
// SourceProfileValidator.java:88-93
List<String> missing = new ArrayList<>();
for (String key : REQUIRED_TOP_LEVEL_KEYS) {
    if (!root.has(key)) { missing.add(key); }
}
```

且 `:20-22` **自述边界**（逐字）：

> 不做什么（如实登记的边界）：本类**不是**完整画像 Schema 校验器——每个键内部的子结构（`eventTypeMapping` 的枚举闭集、`identityPolicy` 的字段形状等）归 P3-01。

**⇒ 结论：`eventTypeMapping`/`fieldMapping`/`enumSemantics` 的**取值**在整个生产代码里从未被读取用于任何转换。** 它们今天只是"必须存在的键名"。

### 7.3 那"今天谁读画像文件"？—— 只有"存在性/一致性校验"这一条路

`SourceProfileValidator` 的唯一调用方是 `SourceRegistryServiceImpl`（`:199`、`:269`），服务于 registry 的 `/test` 与 `/activate`：

- `SourceRegistryServiceImpl.java:199`：`SourceProfileValidator.ProfileCheck check = …`
- `SourceRegistryServiceImpl.java:195-197`：`profile_path_policy` 检查项
- `SourcePathPolicy.java`：仓库相对路径策略（`:88`、`:93`）

**画像文件实际存在 3 个中的 2 个**（`git ls-files -z -- analytics-server/source-profiles`）：

```
analytics-server/source-profiles/README.md
analytics-server/source-profiles/p1-03-probe-1.v1.json     ← 验收夹具
analytics-server/source-profiles/p1-03-probe-2.v1.json     ← 验收夹具
```

**`analytics-server/source-profiles/mock-mall.v1.json` 不存在**（`source-profiles/README.md:17` 逐字承认："`mock-mall.v1.json` | **真实源画像** | **P3-01 交付物，当前不存在**"；`:21-25` 说明种子源因此"当前不可激活"）。

真实的映射数据长这样（`p1-03-probe-1.v1.json`，**夹具**，32 行）——它是**今天唯一存在的映射载体形态**：

```json
"eventTypeMapping": { "product_viewed": "view", "add_to_cart": "cart_add",
                      "payment_success": "order_paid", "contract_signed": "order_created" },
"fieldMapping":     { "buyer_id": "user_id", "item_id": "product_id", "pay_money": "amount",
                      "created_at": "event_time", "coupon_code": "@keep" },
"enumSemantics":    { "behavior": { "wishlist": "favorite", "browse": "view", "purchased": "purchase" },
                      "orderStatus": { "SIGNED": "PAID", "CANCELLED": "CANCELLED" } },
"identityPolicy":   { "user": { "rawField": "buyer_id", "shape": "UUID", "surrogate": "HASH64" }, … },
"timePolicy":       { "field": "created_at",
                      "formats": ["ISO_OFFSET_DATE_TIME","EPOCH_MILLIS","yyyy-MM-dd HH:mm:ss"] },
"quarantinePolicy": { "unknownEventType": "QUARANTINE", "unknownField": "KEEP_IN_PAYLOAD" }
```

### 7.4 采集链今天到底怎么走（真正的"事实上的 normalizer"）

```
读取行 → LocalFileIngestor.java:136  validator.check(text, 0)
       → EventContractValidator.check(...)   纯白名单校验，**零映射**
       → v == null ? 写 accepted/ : 写 quarantine/ + insert quarantine_record
       → IngestionService.buildManifest(...) → manifest.put("mappingVersion", null)   ← :300，硬编码
```

- `LocalFileIngestor.java:136`：`EventContractValidator.Violation v = validator.check(text, 0);`
- `LocalFileIngestor.java:146-155`：违规 → 写 quarantine 文件 ＋ `quarantineRecordMapper.insert(record)`
- `IngestionService.java:267` 注释逐字：`{@code mappingVersion} ← {@code null}：语义映射尚未实施，恒为 null 直到 P2。`
- `IngestionService.java:300`：`manifest.put("mappingVersion", null);`

**真实产物佐证**：`landing/manifests/` 共 **40** 个清单，**只有 1 个**含 `mappingVersion` 键（`40.json`，`mappingVersion = null`）；历史清单（如 `30.json`，15 个顶层键）**连键都没有**。`30.json` 的键集实测：

```
batchId, batchNo, runtimeProfileId, source, status, startedAt, finishedAt, files,
acceptedRecords, quarantinedRecords, acceptedBytes, schemaVersions, acceptedUri,
quarantineUri, checksum          （has_mappingVersion = False）
```

### 7.5 `spark-jobs/**` 侧：**没有任何**映射/枚举/语义读取方

- 事件类型路由是**硬编码 Map**：`EventContract.ODS_TABLE_BY_TYPE`（`:53-65`）↔ Scala 侧 `OdsLoadSql.eventTypeToTable`（跨语言锁由测试钉住）。
- `EventContract.isKnownType` / `odsTable`（`:71`、`:76`）**只被自身定义，主源码内无调用点**（在 `analytics-server/**/src/main/**` 内检索 `isKnownType|odsTable\(` 仅命中定义处）。
- SQL 模板里**硬编码**业务语义字面量（这正是计划 P3-05 要清的目标）：

| 文件:行 | 硬编码内容 |
|---|---|
| `spark-jobs/.../sql/AdsSql.scala:54,55` | `behavior_type = 'view'`（pv / uv） |
| `spark-jobs/.../sql/AdsSql.scala:81` | `SELECT 'view' AS stage` |
| `spark-jobs/.../sql/AdsSql.scala:267,268,269` | `behavior_type NOT IN ('view','favorite','cart_add','cart_remove','search')`（DQ 错误率） |
| `spark-jobs/.../sql/DwdSql.scala:41` | `payload_behavior_type IN ('view','favorite','cart_add','cart_remove','search')` |
| `spark-jobs/.../sql/DwsSql.scala:19-22` | `= 'view'` / `= 'favorite'` / `= 'cart_add'` / `= 'search'` 四路 CASE |
| `spark-jobs/.../sql/DwsSql.scala:59-60` | `= 'view'`；`IN ('favorite','cart_add')` |
| `spark-jobs/.../sql/DwsSql.scala:86-89` | 同上（第二处重复块） |

- Java 侧同样硬编码：`AnalysisService.java:59`（`FUNNEL_ORDER = List.of("view","intent","order","pay")`）、`:61`（中英对照）、`QualityChecker.java:25`（5 值集合）、`EventContractValidator.java:28`（5 值集合）。

**正向对照**：同通道 `'order_created'` 命中 `OdsLoadSql.scala:195` ✔（grep 通道有效）。

### 7.6 Q4 结论汇总

| 问 | 答 |
|---|---|
| `EventNormalizer` 存在吗？ | **不存在**（0 个源码文件；7 处命中全为文档；正向对照 `EventContractValidator` 有 7 文件命中） |
| 它"今天"从哪取映射？ | **不适用**（无此物）。**事实上的替代者是 `EventContractValidator`，它取 0 份映射**：硬编码 12 类型白名单 ＋ `"1.0"` 版本白名单 ＋ 5 值行为枚举 ＋ 金额正则 |
| 谁读映射键的值？ | **没有人**。6 个键只作为 `REQUIRED_TOP_LEVEL_KEYS` 的字符串字面量被做"存在性"检查（`SourceProfileValidator.java:35-44`） |
| 谁写映射版本？ | `IngestionService.java:300` **硬编码 `null`**（40 个真实清单中 39 个连键都没有） |
| 映射数据在哪？ | 只在**文件**里：`analytics-server/source-profiles/*.v1.json`（2 个夹具；真实画像 `mock-mall.v1.json` **缺失**） |
| 库里有映射吗？ | **没有**（§4） |
| spark-jobs 侧？ | **零映射读取方**；事件路由与行为语义**硬编码**（14 处行号见 7.5） |

---

## §8 Q5 —— `canonical-event.v1` 契约 vs 今天的行为：一致吗？

### 8.1 口径说明（**先纠正问题前提**）

问题问的是"契约 vs **`EventNormalizer`** 行为"。**`EventNormalizer` 不存在**（§7.1），故不存在"它的行为"可比。本节的对照对象改为**今天唯一实际承担"canonical 语义执行者"角色的 `EventContractValidator`**，并额外用**独立复算**给出量级（§8.4）。

**契约侧**：`contract-specs/schemas/canonical-event.v1.schema.json`（32516 B，sha256 `0E2E4ED2…37302B2`，`x-contract-status: DRAFT` @ `:67`）。
**行为侧**：`analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/ingestion/EventContractValidator.java`（6844 B，sha256 `B730041A…600671B`）＋ `platform-common/.../contracts/EventContract.java`（4027 B，sha256 `18ABEECA…06F4BA68`）。
**方法**：`ConvertFrom-Json` 机器解析 schema（`%TEMP%\p2sr-schema-probe.ps1`），不靠目视。

### 8.2 信封层（8 字段）—— **一致**

| 项 | 契约 | 行为 | 判定 |
|---|---|---|---|
| 字段集 | `event_id, event_type, event_time, ingest_time, source_system, schema_version, trace_id, payload`（`properties` 8 个） | 逐字同名 7 个 required 循环（`:56-61`）＋ `payload` 单独判 `isObject()`（`:62-64`） | ✅ **等价** |
| `required` | 同 8 个全 required | 同上 | ✅ 等价 |
| `additionalProperties` | `true`（信封层） | 未校验未知键 | ✅ 等价（都是宽松） |

### 8.3 字段/枚举逐条差异（**不一致处全部列出**）

| # | 项 | 契约侧（文件:行） | 行为侧（文件:行） | 判定 |
|---|---|---|---|---|
| D-1 | `event_type` 枚举 | 12 类：`user_registered, product_created, product_updated, behavior, order_created, order_paid, order_cancelled, refund_created, refund_completed, stock_reserved, stock_released, stock_changed`（`:14`） | `KNOWN_TYPES` 同 12 类（`EventContractValidator.java:22-26`，取自 `EventContract.java:22-33`） | ✅ **一致** |
| D-2 | `schema_version` | `"const": "1.0"`（`:43`） | `KNOWN_VERSIONS = Set.of("1.0")`（`:27`） | ✅ **一致** |
| D-3 | **`source_system`** | `"const": "mock-mall"`（**`:39`**） | **只判非空**（`:56-61` 的字段循环），**不比对取值** | ❌ **不一致**：行为侧接受任意非空 `source_system`（"换商城"会静默写出违约值，而校验器放行） |
| D-4 | `amount` 形态 | 正则 `^\d+(\.\d{1,2})?$` 的 **string** | 同正则，但 `:120` 额外放行 `v.isNumber()`（JSON 数字） | ❌ **不一致**：数字型金额被行为侧接受、被契约侧拒绝 |
| D-5 | `behavior.behavior_type` | enum `view,favorite,cart_add,cart_remove,search`（`:504-513`） | `BEHAVIOR_TYPES` 同 5 值（`:28`） | ✅ **一致** |
| D-6 | **`behavior.channel`** | enum `app,pc,h5`（**`:515-517`**） | **完全不校验**（无任何 `channel` 引用） | ❌ **不一致**（枚举缺失查验） |
| D-7 | **`behavior.channel` 必填** | `required: [user_id, product_id, session_id, behavior_type, channel]`（**`:525-530`**） | 只要求前 4 个（`:92`） | ❌ **不一致**（必填项少 1） |

**逐类型 payload 必填差异**（契约 `required` vs 行为 `missingPayloadField`，`EventContractValidator.java:89-111`）—— **行为侧对每一种类型都是契约的真子集**：

| 事件类型 | 契约 required（schema 行号） | 行为侧 required（java:行） | 行为侧**漏检**的必填字段 |
|---|---|---|---|
| `user_registered` | user_id, age_group, **city_level**, member_level, **register_time**（`:94`） | user_id, age_group, member_level（`:91`） | `city_level`、`register_time` |
| `product_created` | product_id, product_name, category_id, **brand_id**, price, **cost**, **status**（`:113`） | product_id, product_name, category_id, price（`:93`） | `brand_id`、`cost`、`status`（＋ `status` 枚举 `on_sale/off_sale/pending` 未校验） |
| `product_updated` | 同 `product_created`（`:132`） | 同上（`:93`） | 同上 |
| `behavior` | user_id, product_id, session_id, behavior_type, **channel**（`:151`） | 前 4（`:92`） | `channel` |
| `order_created` | order_id, user_id, items, total_amount, **status**, **created_at**（`:170`） | 前 4（`:95`） | `status`、`created_at` |
| `order_paid` | order_id, user_id, payment_id, amount, **paid_at**（`:189`） | 前 4（`:96`） | `paid_at` |
| `order_cancelled` | order_id, user_id, reason, **cancelled_at**（`:208`） | 前 3（`:97`） | `cancelled_at` |
| `refund_created` | refund_id, order_id, user_id, amount, **reason**, **created_at**（`:227`） | 前 4（`:98`） | `reason`、`created_at` |
| `refund_completed` | refund_id, order_id, user_id, amount, **completed_at**（`:246`） | 前 4（`:98`） | `completed_at` |
| `stock_reserved` | product_id, **quantity**, **reserved_qty**, **available_qty**（`:265`） | 仅 product_id（`:100`） | `quantity`、`reserved_qty`、`available_qty` |
| `stock_released` | 同 `stock_reserved`（`:284`） | 仅 product_id（`:100`） | 同上 |
| `stock_changed` | product_id, **change_type**, **quantity**, **available_qty**（`:303`） | 仅 product_id（`:100`） | `change_type`、`quantity`、`available_qty`（＋ `change_type` 枚举 `inbound/outbound/adjust` 未校验） |

### 8.4 独立复算（E2）：51 接受 / 26 合规 / 25 违规

**方法**：Python 3.14.5（`%TEMP%\p2sr-replay.py`）独立重放 (a) `EventContractValidator` 的全部规则、(b) `canonical-event.v1` 的 `required`/`enum`/`const`/`amount` 约束，对真实夹具 `landing/events/r9-m1-123006.jsonl` 逐行判定。**这不是引用 README，是重新算。**

**实测输出**：

```
FIXTURE_LINES=55
VALIDATOR_ACCEPTED=51 VALIDATOR_QUARANTINED=4
OF_ACCEPTED_CONTRACT_OK=26 CONTRACT_BAD=25
ENVELOPE_PROPS=8 REQUIRED=8 ENUM_EVENT_TYPE=12
--- contract violation histogram among validator-accepted rows ---
  PAYLOAD_REQ:created_at           9
  ENUM:channel=web                 6
  PAYLOAD_REQ:status               6
  PAYLOAD_REQ:paid_at              5
  PAYLOAD_REQ:completed_at         3
  PAYLOAD_REQ:cancelled_at         1
  ENUM:change_type=restock         1
MANIFEST30 acceptedRecords=51 quarantinedRecords=4
```

**三重自洽**：

1. 复算的 `51/4` **与真实清单 `landing/manifests/30.json` 的 `acceptedRecords=51`、`quarantinedRecords=4` 逐字相同**；
2. 复算的 `26 合规 / 25 违规` **独立复现了 `contract-specs/README.md` §7 Q6 的既有读数（"约 25 行按契约是脏数据"）**；
3. 违规直方图与 §8.3 的静态差异表**互为印证**（`channel` 与 `change_type` 两项正是 D-6/`stock_changed` 枚举漏检；`created_at`/`status`/`paid_at`/`completed_at`/`cancelled_at` 正是必填漏检）。

### 8.5 与"映射层"的关系（**本节最重要的判断**）

上述差异**不是映射层的缺陷**，而是"**校验器比契约宽**"的既有缺口（`contract-specs/README.md` §7 Q6 记为 B-06/Q6 未决，且 `canonical-event.v1` 因此仍为 `DRAFT`）。但它对 P2 是**直接阻塞性**的：

> L348 要求"允许的纯函数变换"由映射层执行，且 **canonical 校验**是流水线的最后一关（计划 `:196`：`…→canonical 校验→写 accepted/quarantine`）。若 P2 的映射层按**今天的校验器**取齐，它会把 25/51 的违约数据判为"映射成功"；若按**契约**取齐，则它必须比今天的采集层更严 —— **同一批真实数据会在两个闸门得到不同结论**。

### 8.6 Q5 结论

| 问 | 答 |
|---|---|
| `EventNormalizer` 与契约一致吗？ | **不可比**（`EventNormalizer` 不存在） |
| 今天的执行者（`EventContractValidator`）与契约一致吗？ | **不完全一致**：字段集/事件类型/schema_version/behavior_type/amount 正则 **5 项一致**；**`source_system`、`channel` 枚举、`channel` 必填、amount 允许数字** 4 项不一致；**12 个事件类型的 payload 必填项全部是契约的真子集**（共漏检 20 个必填字段与 3 组枚举） |
| 量级 | 真实夹具 55 行：校验器接受 51，其中**仅 26 行**满足契约，**25 行违约**（违规 31 处） |

---

## §9 Q6 —— quarantine 通道：L348 的四要素今天存在吗？

### 9.1 通道**存在**（库 ＋ 文件 ＋ 真实行）

| 维度 | 实测 | 出处 |
|---|---|---|
| 库表 | `analytics_meta.quarantine_record`，**精确 104 行** | `raw/q1-tables-exact.tsv` |
| DDL | `V1__platform_ingestion.sql:45-55`（`CREATE TABLE quarantine_record`） | 迁移 V1，真库 `flyway_schema_history` rank=1，`success=1` |
| 写入方 | `LocalFileIngestor.java:146-157` | `quarantineRecordMapper.insert(record)` @ `:155` |
| 实体/Mapper | `entity/QuarantineRecord.java:14`（`@TableName("quarantine_record")`）、`mapper/QuarantineRecordMapper.java:6` | — |
| 物理文件 | `landing/quarantine/` 下 **134 个文件**；`raw_path` **29 个不同值**，**29/29 全部真实存在** | 正向对照：伪路径 `…\quarantine\999\does-not-exist.jsonl` → `Test-Path = False` ✔ |
| 真实内容样例 | `landing/quarantine/30/r9-m1-123006.jsonl`（4 行，含 `not-valid-json-line-with-no-braces-{{{`） | 与 §8.4 复算的 4 条隔离一致 |

**真实原因分布**（`raw/q6-quarantine-reason-histogram.tsv`）：

```
非法 behavior_type: purchase                                      22
JSON 解析失败: Unrecognized token 'not': was expecting …          22
未支持 schema_version: 2.0                                        22
缺失必要字段: event_id                                            20
未支持 schema_version: 9.9                                         6
payload 缺失必要字段: session_id                                   4
JSON 解析失败: Unexpected character ('b' (code 98)) …              4
非法 behavior_type: add_cart                                       2
未支持 schema_version: 1.1                                         2
```

**真实行样例**（`raw/q6-quarantine-tail.tsv`，倒序 10 行）：

```
104  30  (null)         1.0  缺失必要字段: event_id      D:\…\landing\quarantine\30\r9-m1-123006.jsonl  2026-09-11 12:30:06.314
103  30  golden-evt-054 2.0  未支持 schema_version: 2.0  D:\…\landing\quarantine\30\r9-m1-123006.jsonl  2026-09-11 12:30:06.312
102  30  (null)         (null) JSON 解析失败: …          D:\…\landing\quarantine\30\r9-m1-123006.jsonl  2026-09-11 12:30:06.310
101  30  golden-evt-038 1.0  非法 behavior_type: purchase D:\…\landing\quarantine\30\r9-m1-123006.jsonl 2026-09-11 12:30:06.306
```

### 9.2 vs L348 的四要素：**0 项完全满足，2 项部分，2 项缺失**

L348 原文要求"保存 **source instance**、**mapping version**、**reason code** 和**原始位置**"：

| # | L348 要求 | 现状 | 判定 |
|---|---|---|---|
| 1 | **source instance** | 行内**没有** `source_id` 列；只有 `batch_id`（bigint，**无 FK**）。**间接可推断**：104/104 行 join `ingestion_batch` 后 `source_id IS NOT NULL`（`batch_with_source=104`、`batch_without_source=0`、`orphan_batch=0`） | ⚠️ **部分**：可 join 得到，**未持久化**；且 qu arantine 主键路径上**不挂 FK**，与同库另 3 张表的做法不一致 |
| 2 | **mapping version** | 行内**无此列**；`schema_version` 列存的是**源事件的 schema 版本**（1.0/2.0/9.9/1.1），**不是映射版本**；manifest 的 `mappingVersion` 在 `IngestionService.java:300` **硬编码 `null`** | ❌ **缺失** |
| 3 | **reason code** | `reason VARCHAR(255)` 存的是**自由文本中文消息**（见 9.1 分布），非稳定码。计划 `:199` 定义的 6 类码实测：`FIELD_REQUIRED`/`ENUM_UNKNOWN`/`TIME_PARSE`/`AMOUNT_PARSE`/`PROFILE_VERSION` **全仓 0 命中**；`UNKNOWN_EVENT_TYPE` 仅作为常量存在于 `EventContract.java:50`，**唯一引用是测试断言** `EventContractTest.java:122`，**从未写入 quarantine**（正向对照：`SOURCE_PROFILE_INVALID` 命中 9 个文件 ✔） | ❌ **缺失**（退化为自由文本） |
| 4 | **原始位置** | `raw_path` 存的是**隔离输出文件路径**（不是源文件），**无行号、无字节偏移**。`EventContractValidator.Violation` **有** `lineNo` 字段（`:30`），但调用点 `LocalFileIngestor.java:136` **传字面量 `0`**，且 `:149-155` 从未把它写库 | ❌ **不足**（无行号/偏移；`lineNo` 是"声明了但没用"的死字段） |

### 9.3 Q6 结论

**quarantine 通道今天存在且是真实的**（104 行库记录 ＋ 134 个物理文件 ＋ 29 个 `raw_path` 全部可解析），**但它不是 L348 要求的那个 quarantine**：L348 的四要素里，**mapping version 与 reason code 完全缺失**，**source instance 只能靠 join 事后推断**，**原始位置只有输出文件路径、没有行号/偏移**。此外它**不区分"未知源版本 / 无 ACTIVE mapping / 必填字段缺失 / 不允许的转换"四类语义**（L348 的四类触发条件），因为今天**根本不存在"mapping"这个对象**——所有隔离都来自无映射的纯契约校验。

---

## §10 Q7 —— "换商城不改代码"的缺口清单（V2.4 P2 要求的最小交付物）

### 10.1 正向基线（**先说已有什么**，避免把缺口读成"从零开始"）

| 已有 | 实测 |
|---|---|
| 源实例已登记 | `analytics_meta.source_registry` **精确 1 行**：id=1、`mock-mall`、`FILE`、`status=ACTIVE`、`profile_version=1.0`（§4.6） |
| 源画像**机制**已有 | 9 键契约（`SourceProfileValidator.java:35-44`）＋ 路径安全策略（`SourcePathPolicy`）＋ `/test` `/activate` 接线 |
| 源画像**形态**已有真实样本 | `p1-03-probe-1.v1.json`（含 6 个映射键的完整形状，§7.3） |
| 隔离通道已有 | `quarantine_record` 104 行 ＋ 物理文件（§9） |
| 映射版本**契约槽位**已有 | `ingestion-manifest.v1.schema.json:28-34` 的 `mappingVersion` |
| 目标词表已有 | `canonical-event.v1.schema.json`（12 类型 ＋ 全部枚举与必填） |
| 缺口**规格**已写好 | 设计 §4.2/§4.3（`:109-159`）＋ 计划 P3-01…P3-06（`:186-224`）＋ `README.md:122` 的排除声明 |

### 10.2 缺口逐条（**缺什么 → 最小交付物**）

| # | 缺口 | 实测证据 | 最小交付物 |
|---|---|---|---|
| **G-1** | **缺契约**：没有映射/语义注册表的机器可读契约 | `contract-specs/` 9 文件中无一个（§6.1）；`README.md:122` 明文把它排除在目录之外 | 新增 `contract-specs/specs/source-profile.v1.json`（或 `semantic-mapping.v1.json`）：把设计 §4.2 的 9 键**升格**为 JSON Schema，含 `eventTypeMapping` 枚举闭集、`@keep` 哨兵、`identityPolicy.shape/surrogate` 闭集、`timePolicy.formats` 闭集、`quarantinePolicy` 取值闭集；`VERSION` 按加法 `2.0.0 → 2.1.0` |
| **G-2** | **缺表**：指导书 L324 的 `schema_mapping` 11 列 **0/11 落地** | 表名/列名/全实例三层探针皆 0（§4.1–4.2） | 二选一（待裁 TQ-1）：① 建 `analytics_meta.schema_mapping`（L324 十一列）；② 建设计 §4.3 的 `semantic_registry` ＋ `dimension_registry` ＋ 给 `metric_definition` 增 `source_code`/`required_for_overview` |
| **G-3** | **缺迁移号** | 真库 `flyway_schema_history` max=**17**；仓内 max=18 但 V18 **未提交**（§5.3） | 取 **V19**（P2-07-b 已声明"V19 预期"，见 `git reflog` 提交信息），并**先确认 V18 的归属已落定**（TQ-3） |
| **G-4** | **缺执行者**：无 `EventNormalizer`，无任何"源事件 → canonical"的转换代码 | 源码 0 命中（§7.1） | 实现 `EventNormalizer`（`connection-ingestion` 模块内），顺序固定为计划 `:196`：解析信封 → 校验来源 → 事件类型映射 → 字段提取 → 枚举映射 → 时间/金额转换 → canonical 校验 → 写 accepted/quarantine；变换白名单 = 计划 `:197`（trim/case/decimal scale/epoch-timezone/受控 enum map/JSONPath），**禁脚本/反射/任意 SQL/网络**（L346） |
| **G-5** | **缺 loader**：画像只被"查键存在性"，从未被加载为可用对象 | `SourceProfileValidator` 只做 `root.has(key)`（`:88-93`），取值从不读取（§7.2） | `SourceProfile` 不可变加载器 ＋ 按 `sourceCode`+`profileVersion` 缓存 ＋ run 级 profile checksum/version 记录（计划 `:190-192`） |
| **G-6** | **缺真实画像**：`mock-mall.v1.json` 不存在 | `git ls-files -z -- analytics-server/source-profiles` 只有 2 个夹具 ＋ README（§7.3）；`source-profiles/README.md:17` 自述"当前不存在" | P3-01 交付 `analytics-server/source-profiles/mock-mall.v1.json`（9 键齐全、`sourceCode=mock-mall`、`profileVersion=1.0` 与登记行一致）⇒ 否则 id=1 种子源永远不可激活 |
| **G-7** | **缺映射版本产出**：`mappingVersion` 恒 `null` | `IngestionService.java:300` 硬编码；40 个真实清单仅 1 个含该键（§7.4） | manifest 的 `mappingVersion` 改由**实际生效的 profile/mapping 版本**填充；禁止占位值（schema `:34` 已明文） |
| **G-8** | **缺 quarantine 四要素** | §9.2：mapping version ❌、reason code ❌、source instance ⚠️、原始位置 ❌ | 扩 `quarantine_record`：`source_id`、`mapping_version`、`reason_code`（闭集）、`line_no`/`byte_offset`；`reason` 自由文本降级为 `detail`（TQ-8） |
| **G-9** | **缺原因码闭集** | 6 类计划码中 5 类 **0 命中**；`UNKNOWN_EVENT_TYPE` 只有常量无写入路径（§9.2 #3） | 冻结 `UNKNOWN_EVENT_TYPE/FIELD_REQUIRED/ENUM_UNKNOWN/TIME_PARSE/AMOUNT_PARSE/PROFILE_VERSION` 为契约枚举，并让写入路径真实使用 |
| **G-10** | **缺 dry-run** | L348 要求"映射激活必须先用样本 dry-run，并输出接受/隔离/错误计数"；全仓无 dry-run 实现 | 激活前置校验接口：输入样本 → 输出 accepted/quarantined/error 三计数；**未通过不得置 ACTIVE** |
| **G-11** | **缺去字面量**（P3-05） | SQL/Scala/Java 硬编码行为语义 **14 处**（§7.5 表） | 漏斗/状态/指标语义改由注册表 join 或受控参数取得；加自动扫描门禁：`'view'/'favorite'/'cart_add'` 只可出现在 seed/fixture/profile |
| **G-12** | **缺测试** | 无 dry-run 覆盖率测试、无"新映射零 Java/Scala 修改"测试（看板 `:232` P3-06 验收项） | 计划 `:223`"新增映射无需改 Java/Scala" ＋ `:224`"源 A 指标不变"的两条验收测试 |

### 10.3 "换商城不改代码"今天的**真实**完成度

| 环节 | 今天是否已"配置驱动" | 证据 |
|---|---|---|
| 源身份登记 | ✅ **已是配置/数据驱动** | `source_registry` 表 ＋ API（`SourceRegistryController`） |
| 连接方式 | ✅ 部分 | `ingest_mode` 列（`FILE`） |
| 事件类型映射 | ❌ **数据在文件里，但无人消费** | `eventTypeMapping` 仅被查键存在性 |
| 字段映射 | ❌ 同上 | `fieldMapping` |
| 枚举映射 | ❌ 同上 | `enumSemantics`；且下游 SQL 硬编码 canonical 值（14 处） |
| 时间/金额转换 | ❌ 无 | 无 `timePolicy`/`amount_unit` 读取方；`amount` 只做正则校验 |
| 语义注册表（canonical → 分析含义） | ❌ **表都不存在** | `semantic_registry`=0、`dimension_registry`=0 |
| 隔离语义 | ⚠️ 通道有、语义无 | §9.2 |

**⇒ 一句话**：今天"换商城"能改的只有**源名/时区/币种/连接模式**（4 个标量列）；**换词汇表本身仍需改代码** —— 因为词汇映射的容器（文件）虽在，**读取它的执行者不存在**。

---

## §11 Q8 —— 待裁问题清单（无法由本泳道单独判断，需总控裁决）

> 格式对齐 `docs/acceptance/p2-03-surrogate-key-20260912/README.md` §11（每条给两个以上选项与各自代价）。

**TQ-1（映射载体二选一，最高优先级）**：L324 要 `schema_mapping` 表，设计 §4.3 要 `semantic_registry`+`dimension_registry`，设计 §4.2 又要文件画像。
 ①**只建文件画像**（成本最低，但 DB 无审核/生效区间/版本查询能力，L348"版本化配置和审核记录"只有前半）；
 ②**只建 DB 表**（可审核可查询，但"接入新商城 = git 新增一个 JSON"这条**接入证据**（设计 `:149`）会消失）；
 ③**两者并存**（设计 `:213` 已给边界原则"profile 管源词汇→canonical；DB 管 canonical→分析含义"，但引入双所有者风险）。
 **代价要点**：③ 与 §10.2 G-1 的契约工作量最大；① 会让 P4"按源指标"缺可查依据。

**TQ-2（谁是"映射真相"的权威）**：L350 说"最终真相是版本化配置和审核记录"。该"审核记录"落在哪张表？`operation_audit_log`（92 行）复用，还是新表？若复用，如何表达"mapping version X 由 reviewer Y 在 T 时刻批准"？

**TQ-3（迁移号与 V18 归属）**：真库 max=17，仓内 V18 **未提交**（§5.3）。P2 本层取 **V19** 还是先等 P2-07-b 落 V18？
 ①**直接取 V19**（会与 P2-07-b 声明的"V19 预期"**撞号**）；
 ②**等 V18 入库后取 V19**（安全，但阻塞本层）；
 ③**与 P2-07-b 协商，本层取 V20**（需总控协调）。
 （`git reflog` 该笔 commit 信息已写"V19 预期"，故 **① 有明确撞号风险**。）

**TQ-4（契约制品命名与状态）**：新增契约叫 `source-profile.v1.json`（对齐文件名）还是 `semantic-mapping.v1.json`（对齐 L324 表名）？`status` 初值是 `DRAFT` 还是直接 `FROZEN`？（参照 P2-03 待裁问题 7 的既有口径；另注意 `docs/acceptance/m1-5-anchor-audit-20260912/` 的裸锚点规则 D-065 会约束新制品的锚点风格 —— **本泳道未读该目录**。）

**TQ-5（映射的"目标"是契约还是今天的校验器）**：`canonical-event.v1` 是 `DRAFT` 且 §8.4 显示校验器与契约对同一批真实数据给出**不同结论（51 接受 vs 26 合规）**。
 ① P2 映射层**按契约**取齐（更严 ⇒ 会把今天"成功"的 25/51 判为违约，**改变现有验收基线**，需总控确认是否可接受）；
 ② **按今天的校验器**取齐（不破坏现状，但把 25 行违约数据固化为"映射成功"，与 L346"canonical event 转换"语义相悖）；
 ③ **先裁 B-06/Q6 冻结契约，再实施本层**（最干净，但本层被阻塞）。

**TQ-6（收紧校验器的顺序）**：§8.3 的 D-3/D-4/D-6/D-7 ＋ 20 个漏检必填字段，是**本层一并收紧**（映射层一次到位）还是**另立任务**（先冻结契约再收紧采集层）？若一并收紧，**存量 25 行 accepted 数据要如何处理**（重跑？标记？）

**TQ-7（`source_system` const 与多源矛盾）**：契约把 `source_system` 钉成 `const: "mock-mall"`（`:39`）。**"换商城"必然要改这个 const** ⇒ 这是本层与 P2 目标**直接冲突**的一处。选项：① 改成 enum/pattern 并升 minor；② 改由源画像/注册表提供（破坏性变更 → `canonical-event.v2`）；③ 维持 const、靠"每源一个 canonical 变体"。**本泳道不预设答案。**

**TQ-8（quarantine 四要素的落地方式）**：① `ALTER TABLE quarantine_record` 加 4 列（改动存量表，104 行需回填/留空）；② 新表 `quarantine_record_v2` 双写（与 G-3 的迁移号联动）；③ 只加 `mapping_version`+`reason_code`（最小）。**另需裁**：`reason` 自由文本是否保留为 `detail`（保留 = 不破坏既有 104 行可读性）。

**TQ-9（`reason_code` 闭集是否就用计划的 6 类）**：计划 `:199` 给的是 `UNKNOWN_EVENT_TYPE/FIELD_REQUIRED/ENUM_UNKNOWN/TIME_PARSE/AMOUNT_PARSE/PROFILE_VERSION`。但 L348 给的四类触发条件是"未知源版本 / 无 ACTIVE mapping / 必填字段缺失 / 不允许的转换" —— **两套分类不一致**（计划有 TIME_PARSE/AMOUNT_PARSE/ENUM_UNKNOWN 而 L348 没有；L348 有"无 ACTIVE mapping"而计划用 PROFILE_VERSION 近似）。以哪套为准？

**TQ-10（mapping 的"激活"动词落在哪张表）**：L324 的 `status` 列（DRAFT/ACTIVE？）与 L348 的"没有 ACTIVE mapping ⇒ quarantine"要求一个**可查询的 ACTIVE 状态**。若选 TQ-1① 纯文件方案，"ACTIVE"如何表达与查询？

**TQ-11（源级 vs 全局映射的唯一性）**：设计 §4.2 约束"缺失的映射项 = 该源没有这个语义（不是默认值）"（`:147`）。那么**两个源映射到同一 canonical 字段**时的冲突口径是什么？谁拥有 canonical 骨架的最终定义（`canonical-event.v1` 的 `$defs`？还是画像的 `canonical` 键）？

**TQ-12（`mock-mall.v1.json` 由谁交付、内容是什么）**：它是"真实源画像"（README `:17`），但 `mock-mall` 恰好是参考商城、**其词汇与 canonical 同名**（看板 `:407` 记录 F-25："参考商城状态词与规范 `enum` 恰好同名，掩盖了「缺少映射」，第二适配器会立刻暴露"）。
 ① 照实写"同名映射"（诚实，但**测不出映射能力**，与 F-25 同病）；
 ② 写成"故意错位"的词汇表以证明映射真的生效（但就不是"真实源画像"了）。
 **本泳道认为这是 P3-01 能否构成有效证据的关键裁决点。**

**TQ-13（每源 namespace 与映射层的接口）**：`source_registry` 是否增 `warehouse_prefix`（V18 已占位但**未入库**）？映射层产出的 canonical 事件落到**哪个 namespace**？本层与 P2-07/P2-07-b 的边界需总控划清（否则 `raw_path`、"源级 ODS"、命名空间三处会互相踩）。

**TQ-14（权威文档换代后的复读义务）**：`contract-specs/README.md:38` 自述"本目录尚未按 V2.1/V2.2 复读"，而权威现已到 **V2.4**（`README.md:3` 仍称依据 V2.1 §3.1）。请裁：本层新增契约是否**必须**先完成 `contract-specs` 对 V2.4 的复读（含 §5.2/§5.4 引用点）？若是，该复读任务归本泳道还是 M1-5？

---

## §12 Q9 —— 未取证清单（**本报告未证明的东西**）

| # | 未取证项 | 卡在哪 |
|---|---|---|
| **U-01** | **未运行任何 Maven / 单元测试 / 集成测试** | 任务书禁止跑 Maven；故"代码里没有 X"是**静态 grep** 结论，**不含编译期/运行期**验证（例如 `EventNormalizer` 可能以反射/SPI 形式存在 —— 本泳道未做反射面检索） |
| **U-02** | **未触碰 8090/8091/8092 进程**，未做任何 HTTP 调用 | 任务书硬约束。故 `source-profiles/README.md:22` 所述"`POST /api/v1/sources/1/test` 返回 `ok=false`、`/activate` 返回 409 `SOURCE_PROFILE_INVALID`"是**引用，不是本轮实测**；本报告只用它来**指向**已知状态，未作为判据 |
| **U-03** | **未做任何库写操作**，故未验证 V18/V19 在真库执行后的结果 | 只读铁律。所有"迁移未生效"结论均来自 `flyway_schema_history` 与 `information_schema` 的**只读**读数 |
| **U-04** | **未验证 8091 当前进程加载的 jar 是否含 V18** | 需读进程/文件句柄或重启，均越界 |
| **U-05** | **未复算 `contract-specs/README.md` §10 的指纹表全量** | 只抽验了 `docs/contracts/event-contract.md` 一项（前 16 位相符 ✔）；其余 55+ 项未复算 |
| **U-06** | **未读 `docs/acceptance/m1-5-anchor-audit-20260912/`、`scripts/check-bare-anchors.ps1`、`scripts/contract-bare-anchors.allowlist.txt`** | 与 TQ-4 相关，但本泳道未读，**不预设形态**（与 P2-03 待裁问题 16 同一处理） |
| **U-07** | **未遍历 `docs/acceptance/**` 全部历史报告** | 仅读了 P2-03 的 §11（对齐格式）与 `contract-specs/README.md`。可能存在本泳道未见的既有裁决 |
| **U-08** | **未做集群 / HDFS / Hive / 百万行 / 并发**验证 | 需集群，本机无 |
| **U-09** | **未验证 `landing/quarantine/` 134 个文件中"空文件"的成因** | 可见多枚 0 字节文件（如 `quarantine/2/*.jsonl`），可能对应"该批无隔离行"，但**本泳道未读写入侧对该情形的分支**，不作结论 |
| **U-10** | **未验证 `analytics_meta.metric_snapshot`（陈旧同名表，9 行）是否仍被任何代码读写** | T-03 只证明了"两个 schema 同名表都存在、读数恰好相同"；**哪一个是活表**来自总控附告与本报告未复算的推断，**本轮未取证** |
| **U-11** | **未做 `spark-jobs/**` 的行为验证**（未提交 Spark 作业） | 故 §7.5 的"硬编码语义"是**源码字面量**事实，**未证明**它在运行时真的决定漏斗/指标取值 |
| **U-12** | **未验证报告成文后仓库是否又变化** | HEAD 在窗口内已前进 3 次（E-06）；本报告所有行号以 §13.2 的 sha256 为准 |

---

## §13 读取窗口与指纹

### 13.1 窗口定义

- **开工**：本泳道第一条命令（`git rev-parse HEAD` → `de253af…`）
- **收工**：最后一条只读命令
- **窗口内 HEAD 前进 3 次**（E-06），但**无 commit 触碰 `db/meta/`**

### 13.2 引用文件指纹（SHA-256，全量见 `raw/cited-file-fingerprints.tsv`；24 项）

| SHA-256（前 16） | bytes | 文件 |
|---|---|---|
| `580AD7BE46B2F395` | 57442 | `docs/项目完整实施指导书 V2.4.md` |
| `02BA3305CA4DA879` | 220784 | `docs/项目实施进度与任务看板 V2.2.md` |
| `C8354C26655450F0` | 47442 | `contract-specs/README.md` |
| `9784177F34E4B4A2` | 21 | `contract-specs/VERSION` |
| `0E2E4ED2B17D7DB7` | 32516 | `contract-specs/schemas/canonical-event.v1.schema.json` |
| `0993E1474228E2EE` | 11945 | `contract-specs/schemas/ingestion-manifest.v1.schema.json` |
| `14385528205886CB` | 16924 | `contract-specs/specs/surrogate-key.v1.json` |
| `CD79BBA1688E333B` | 6156 | `contract-specs/specs/warehouse-namespace.v2.json` |
| `E241F0265123992A` | 3275 | `…/db/meta/V1__platform_ingestion.sql` |
| `D3E1C98D4AFC5B48` | 4512 | `…/db/meta/V16__source_registry.sql` |
| `BACA5DF047833C52` | 3697 | `…/db/meta/V18__source_warehouse_prefix.sql`（**未提交**） |
| `B730041AFBF55A35` | 6844 | `…/ingestion/EventContractValidator.java` |
| `4050302F1B577802` | 19610 | `…/ingestion/LocalFileIngestor.java` |
| `9159C06D60AB1115` | 25028 | `…/ingestion/IngestionService.java` |
| `0C4CC657C74E4229` | 7041 | `…/source/SourceProfileValidator.java` |
| `18ABEECA3097F545` | 4027 | `…/contracts/EventContract.java`（platform-common） |
| `D7E1697AAF09656E` | 1728 | `mall-simulator/…/outbox/EventContract.java` |
| `7B385B8C4EF5DF04` | 2122 | `analytics-server/source-profiles/README.md` |
| `F525DF73181E9300` | 1123 | `analytics-server/source-profiles/p1-03-probe-1.v1.json` |
| `BDC6267947821AEB` | 25299 | `docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md` |
| `528E648E97E4F714` | 18114 | `docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md` |
| `FB991C17B9037B1B` | 7231 | `docs/contracts/event-contract.md`（**与 README §2 登记值前 16 位相符** ✔） |

> 注：`analytics-server/mall-simulator/.../EventContract.java` 一项 `MISSING`（该路径不存在；真实第二份副本在 `mall-simulator/src/main/...`）—— 这是本报告的一次错误路径试探，**如实登记**，不计入"文件缺失"结论。

### 13.3 `raw/` 清单（8 个文件，本报告全部原始读数）

| 文件 | 内容 |
|---|---|
| `q1-tables.tsv` | **原始文件，保留不动**。第三列为 InnoDB **近似值** —— 见 E-01 |
| `q1-tables-exact.tsv` | **取代上者行数口径**。33 张 BASE TABLE 的 `SELECT COUNT(*)`，列名 `exact_count` |
| `q1-absence-probe-tables.tsv` | 表名探针（表名含 mapping/enum/semantic/registry/normaliz/canonical） |
| `q1-column-probe-with-controls.tsv` | 列名探针 ＋ 2 个正向对照（source=11、version=22） |
| `q2-flyway-history-real-db.tsv` | 真库 `flyway_schema_history` 16 行 |
| `q6-quarantine-reason-histogram.tsv` | 104 行 quarantine 的 reason 直方图 |
| `q6-quarantine-tail.tsv` | quarantine 最新 10 行 |
| `cited-file-fingerprints.tsv` | 24 项引用文件 SHA-256 |

---

## §14 反熵声明与"不得声称"

### 14.1 反熵声明

- 本泳道**未**创建第二所有者：报告只描述现状与缺口，**未**定义任何新契约、新表、新枚举（规格性内容一律指向既有 `docs/superpowers/**` 与 `contract-specs/**`，并附行号）。
- 本泳道**未**修改任何既有文件；`raw/q1-tables.tsv` 的缺陷以**新增 exact 文件 ＋ 本报告勘误**处理，不覆盖历史。
- 本泳道**未**改动 `docs/项目实施进度与任务看板 V2.2.md`（其 P3-01…P3-06 仍为 `TODO`，本报告不代其改写状态词）。
- 发现的一处"声明了但没实现"的重复所有者风险已登记为 TQ-1/TQ-2，**留给总控裁决**，本泳道不自裁。

### 14.2 不得声称（**任何后续报告不得把下列推断写成实测**）

1. **不得**声称"`schema_mapping` 不存在**因为**迁移没写" —— 本报告只证明了"表不存在"，未证明原因。
2. **不得**声称"V16/V17 源迁移未生效" —— **E-01 的假红**。`source_registry` **精确 1 行**、`runtime_profile` **精确 1 行**，V16/V17 已生效（`flyway_schema_history` rank 15/16，`success=1`）。
3. **不得**声称"`EventNormalizer` 是未实现的**类**" —— 准确表述是"**全仓无该标识符的任何代码命中**"（未做反射面检索，见 U-01）。
4. **不得**声称"映射层完全空白" —— 画像**文件形态**与**校验机制**已存在（§10.1），缺的是**契约升格、加载器、执行者、注册表表、dry-run**。
5. **不得**声称"quarantine 不存在" —— 它存在且有 104 行真实记录；缺的是 L348 要求的四要素与四类触发语义。
6. **不得**声称"8091 的 `/test` 返回 409"是实测 —— 那是引用（U-02）。
7. **不得**声称"校验器与契约不一致 ⇒ 采集链有 bug" —— 该差异是 `contract-specs` 已知的 **B-06/Q6 未决**项，且契约本身是 `DRAFT`；本层如何取齐需总控裁决（TQ-5）。
8. **不得**声称本报告的行号在成文后仍然有效 —— 以 §13.2 的 SHA-256 为准（HEAD 在窗口内已前进 3 次）。
