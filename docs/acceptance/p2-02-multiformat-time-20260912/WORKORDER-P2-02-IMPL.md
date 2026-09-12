# P2-02 施工单（IMPL）：多格式时间与 quarantine 原因码

- 文档性质：**施工单草案（DRAFT）**。**未开工。** 开工前置条件见 §2.1（含一条硬阻塞）。
- 上游：`draft/p2-02-spec-draft.md`（规格草案，**含 9 条待裁决**）。规格未裁决前，本单**只有 A1／A2 可以被讨论，不可被实施**。
- 基线：HEAD `ce36065c3db4572115ff09945ef6b041fcfdc5e2`（取数时点 2026-09-12 20:13）。
- 预期产出：`IMPL-REPORT.md` + `raw/` + `evidence/`，落在 `docs/acceptance/p2-02-multiformat-time-20260912/`。

---

## §0 现状实测（施工前必须知道，避免重新发现）

全部为只读实测；行号对应 `raw/source-fingerprints-20260912.txt` 的 **sha256 快照**。

| 位置 | 事实 | 与 P2-02 的关系 |
|------|------|----------------|
| `OdsLoadSql.scala:90` | `"event_time" -> "event_time"` 直通，不改写 | A3 的接线点 |
| `OdsLoadSql.scala:168` | `REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt` | **位置切片**，无解析 |
| `OdsLoadSql.scala:169` | `SUBSTR(event_time, 12, 2) AS hour` | **位置切片**，无解析 |
| `OdsLoadSql.scala:174` | `INSERT OVERWRITE TABLE … PARTITION (dt, hour)` | 分区由此定型 |
| `OdsLoadSql.scala:181` | `AND event_id IS NOT NULL AND event_time IS NOT NULL` | **静默丢弃**坏行 |
| `OdsLoadSql.scala:208` | `StructField("event_time", StringType, nullable = false)` | 显式 schema **已存在**（plan L155 前半已满足） |
| `OdsLoadSql.scala:269-276` | `rejectedSelect()` 只产字面量 `'BAD_VERSION_OR_KEY'` | **无生产调用**（仅 `SqlTemplateSpec.scala:80`）＝ 死 SQL |
| `EventContractValidator.java:56-61` | `event_time` 仅**非空**检查 | 采集侧无格式校验 |
| `EventContractValidator.java:30` | `Violation(lineNo, eventId, schemaVersion, reason)` — **有 `lineNo`** | 「原位置」的行号字段已存在 |
| `LocalFileIngestor.java:136-157` 一带 | `validator.check(text, 0)`，**行号写死 0** | 行号被丢弃 ⇒ A6 的接入点 |
| `QuarantineRecord.java` | 字段 `id, batchId, eventId, schemaVersion, reason, rawPath, createdAt` | **无码字段** |
| `V1__platform_ingestion.sql:45-55` | `reason VARCHAR(255) NOT NULL`（自由文本） | A4 的落点 |
| `Cleaners.scala:16-31` | `normalizeTime` 唯一「多格式」实现，**丢偏移**、输出无偏移串 | **生产调用 0**；其输出会违反 `iso8601_time` pattern ⇒ **不得直接复用** |
| `DwdSql.scala:34` vs `:35-36` | 同 SELECT 两套口径（解析式 vs 位置切片） | A5（**该文件在飞，见 §2.1**） |
| `DwdSql.scala:64` | `'DUPLICATE_EVENT' AS reject_reason` | 拒绝通道现状（本单不动） |
| `01-dwd.sql:90` | `reject_reason` 注释含 `FUTURE_TIME` | 已声明未实现；**本单不落地**（D-107） |
| `SourceProfileValidator.java:35-44` | 9 个顶层键**存在性**检查 | A1／A2 的接入点；**该类属 P1-03 泳道** |
| `SparkSessionFactory.scala:14` | 写死 `spark.sql.session.timeZone = Asia/Shanghai` | 不得作为业务时区来源 |
| `docs/contracts/event-contract.md:25` | 「解析失败视为脏数据」 | 与 §3.2 的码枚举对应 |

**基线读数**：真实语料 **349 文件 / 1,215,989,873 B / 2,622,616 个 `event_time` 取值，100% 合法**（`raw/scan-event-time-shapes-20260912.txt`）。这是 A8 的守卫基线。

---

## §1 施工项

每项：**改动面** · **验收命令** · **证据文件名** · **停止条件**。
凡标注 `[需裁决]` 者，**裁决未下不得动工**。

### A1 — 冻结 `timePolicy.formats` 的取值语法与校验位置 `[需裁决 D-102]`

- **改动面**：`analytics-server/connection-ingestion/.../source/SourceProfileValidator.java`（**属 P1-03 泳道**，改动需该泳道/总控同意）**或**新建平行校验类。二选一必须裁决。
- **内容**：把 §2.2 建议的受控 token 闭集写进校验；`timePolicy` 由「只查键存在」升级为「查 `field` 非空 + `formats` 非空有序数组 + 每个元素 ∈ 闭集」。
- **验收命令**：`mvn -pl analytics-server/connection-ingestion test -Dtest=SourceProfileValidatorTest`（**草案，实际模块坐标以仓库为准**）。
- **证据文件名**：`evidence/A1-formats-validation.md`、`raw/A1-validator-tests.txt`。
- **停止条件**：若裁决要求改**契约**（`contract-specs/**`）⇒ **停**，改由总控走 CT 批次 + 版本 bump。

### A2 — 实现唯一的时间解析模块（含三态结果）

- **改动面**：**新建单一文件**（建议 `spark-jobs/src/main/scala/com/graduation/analytics/algorithm/TimeNormalizer.scala`，**或**按裁员裁决落在采集侧 Java）。**只此一处**实现格式尝试与归一。
- **内容**：输入 `(原始串, formats 有序列表, 源时区)` → 输出三态：`Ok(归一值, 命中下标)` / `ParseFailed` / `ZoneUndetermined`。严格模式（词法命中 ≠ 解析成功，见规格 §2.4 第 2 条）。
- **验收命令**：单测 + C9 向量（总控独立复算）。
- **证据文件名**：`evidence/A2-normalizer.md`、`raw/A2-vectors.txt`、`evidence/C9-time-vectors.md`。
- **停止条件**：若发现必须**改 `Cleaners.normalizeTime` 的既有语义** ⇒ **停并报告**（它有既有测试断言钉住：「丢偏移」是**已被测试固化**的行为，改它就是 breaking）。

### A3 — 把解析结果接到 ODS 装载（`event_time` 与分区派生）

- **改动面**：`spark-jobs/.../sql/OdsLoadSql.scala`（`envelopeSelect` / `odsSelect` / 分区表达式 L168-169）。**⚠ 见 §2.1 并行约束。**
- **内容**：① 坏行**不再静默丢弃**（配合 A4 的通道）；② `dt`/`hour` 改为**解析式**派生（口径按 D-103 裁决：建议保持源时区业务日）；③ 若 D-101 选 P-B，追加派生列（**末尾**，D-059）。
- **验收命令**：`golden-55` 本地链 + `gen-s3b-1000` 本地链各一次；比对 P2-01 冻结读数。
- **证据文件名**：`evidence/A3-ods-wiring.md`、`raw/A3-run-golden55.txt`、`raw/A3-run-gen1000.txt`。
- **停止条件**：**改动会导致既有分区值变化**（即 `dt`/`hour` 对既有语料算出不同值）⇒ **立刻停并报告**（那意味着 C7 基线必破，属重大变更而非加法）。

### A4 — 原因码落库与写入者 `[需裁决 D-104 / D-105 / D-106 / D-108]`

- **改动面**：新 Flyway 迁移（`analytics_server/platform-app/.../db/meta/V??__*.sql`，加 `reason_code VARCHAR(64) NULL`）+ `QuarantineRecord.java` + `LocalFileIngestor.java` 写入点。
- **⚠ D-092 停止条件**：**DB DDL 属停止条件** ⇒ 本项**必须总控放行**后才能动。
- **验收命令**：迁移 + 一条端到端负例（隔离 1 行，断言 `reason_code` 命中）。
- **证据文件名**：`evidence/A4-reasoncode.md`、`raw/A4-migration.txt`、`raw/A4-quarantine-row.txt`。
- **停止条件**：若裁决要求**改写既有 108 行** ⇒ **停**（违反 R-3「不可回填」与 A 类硬约束「不得发明证据」）。

### A5 — 消除 DWD 内两套时间口径

- **改动面**：`spark-jobs/.../sql/DwdSql.scala:35-36`（`event_date`/`event_hour` 由位置切片改解析式）。
- **⚠ 该文件正被 P2-03 改写**（读时 sha256 `A38EC916…`）；**必须等 P2-03 收口**并按其 11 文件白名单判断本项是否越界。
- **验收命令**：DWD 单测 + `golden-55` 链上断言 `event_date`/`event_hour` 与 `event_time` 一致。
- **证据文件名**：`evidence/A5-dwd-single-source-of-truth.md`。
- **停止条件**：若本项需要改 **D-090 认定的 `event_id` 属主语义** ⇒ **停**。

### A6 — 保留「原位置」（源文件 + 行号）

- **改动面**：`LocalFileIngestor.java`（把写死的 `check(text, 0)` 改为真实行号）+ `QuarantineRecord`／`raw_path` 的取法。
- **内容**：源文件路径用 **`landing_file`**（D-057 已真实化）；行号用 `Violation.lineNo`（**已存在**）。
- **⚠ D-060 实测**：`input_file_name()` 返回**空字符串** ⇒ **不得**依赖它；用 `landing_file` 或 `_metadata.file_path`。
- **验收命令**：负例夹具，断言隔离行的「文件 + 行号」指向夹具中真实位置。
- **证据文件名**：`evidence/A6-origin-location.md`。
- **停止条件**：若需改 `landing_file` 的既有语义（D-057）⇒ **停**。

### A7 — 负例夹具（**多格式 + 坏时间 + 未知版本 + 缺 id**）

- **改动面**：新增夹具文件（**落点需裁决**：`tests/fixtures/**` 还是 `landing/**` 的独立批次目录）。**禁止**污染既有批次目录（既有 `landing/` 下 349 个 `.jsonl` 已被本泳道全量扫描并作为 C5 基线，写进去会破坏基线可复算性）。
- **内容**：每个受控 token 各 1 行正常样本；每个新增原因码各 ≥1 行负例；期望值**逐行标注**在夹具配套表里（不得来自实现输出）。
- **验收命令**：夹具自检脚本（键齐全、JSON 合法、期望表与行数一致）。
- **证据文件名**：`evidence/A7-fixtures.md`、`raw/A7-fixture-inventory.txt`。
- **停止条件**：**待定** —— 开工前须先做 `tests/fixtures/**` 盘点（规格 U4），盘不到合适基座就**停并报告**，不要自造目录结构。

### A8 — 零误伤回归守卫（C5）

- **改动面**：**无源码改动**（纯验证）。
- **内容**：对真实语料全量跑一次解析，断言 `TIME_PARSE_FAILED = 0` 且 `TIME_ZONE_UNDETERMINED = 0`。
- **验收命令**：复用 `raw/scan-event-time-shapes.ps1` 的扫描面（349 文件 / 1,215,989,873 B），把分类器换成 `A2` 的解析器。
- **证据文件名**：`evidence/C5-no-regression.md`、`raw/C5-scan.txt`（**必须含阳性对照**）。
- **停止条件**：**任一计数 > 0 ⇒ 停并报告**（真实数据 100% 合法，>0 即实现有误判）。

### A9 — 一致性守卫（列集与 DDL 不漂移）

- **改动面**：测试（`OdsV2SchemaOwnerSpec` 一带，**该文件正被 P2-03 修改**，需避让）。
- **内容**：断言 `warehouse/ddl/00-ods.sql` 的列集与 `OdsV2Columns.scala` 的列集一致（今天靠手工镜像，且两者当前**确实一致**）。
- **验收命令**：单测。
- **证据文件名**：`evidence/A9-schema-owner.md`。
- **停止条件**：与 P2-03 的同名测试冲突 ⇒ **停并报告**。

---

## §2 硬约束

### 2.1 并行约束（**最高优先级，先读这条**）

| 约束 | 内容 |
|------|------|
| **不得与 P2-03 并发写 `spark-jobs/**`** | 依据 **D-091**。P2-03 于 2026-09-12 20:0x 状态为 `IN_PROGRESS`，正在改写 `DwdSql.scala`、`DimSql.scala`、`TradeDwdJob.scala`、`LocalSchemaInitJob.scala`、`OdsV2SchemaOwnerSpec.scala`、`IdCodecSpec.scala`、`WarehouseNamespaceSpec.scala`、`warehouse/ddl/01-dwd.sql`、`warehouse/ddl/02-dims.sql` |
| **开工前置（硬阻塞）** | **必须等 P2-03 收口（提交 + 状态转 `DONE_LIMITED`）或总控明确裁决"可以并行、且范围白名单已给"**。在此之前 **A3／A5／A9 一律不得动工** |
| **可先行项（不碰 `spark-jobs`）** | 仅 **A7**（夹具，落点仍待裁决）与 **A8** 的准备（复用只读脚本）可先行；**A1／A2 需先有裁决** |
| **实施前必做** | 按 **D-091** 重新取一次 HEAD 与全部被改文件的 **sha256 + 行号**（本单行号对应 `raw/source-fingerprints-20260912.txt`，**必然已漂移**） |
| 范围白名单 | 实施时以 P2-03 已登记的 11 文件白名单为参照，**本单不得越界**；越界即停 |
| 基线 | 取一次"开工基线提交"，全程可回滚到它 |

### 2.2 模块单一写者

| 模块 | 单一写者 | 说明 |
|------|---------|------|
| ODS 列集合 | `OdsV2Columns.scala` | DDL 是手工镜像；A9 负责守卫一致 |
| `event_time` 写入表达式 | `OdsLoadSql.envelopeSelect` | 不得第二处 |
| 新增时间派生表达式 | A2 的单一模块 | **禁止**内联到多处 SQL |
| `dt`/`hour` 派生 | `OdsLoadSql.scala:168-169` | 唯一 |
| 原因码枚举 | **一处**（A4 裁决落点） | **禁止** Java 与 Scala 各一份字面量 |
| 源时区 | 建议 `source_registry.timezone` | 见 D-103；未裁决前不得新增第 5 个可写点 |

### 2.3 不得做（违反即失败）

- 不改 `contract-specs/**`（含 `VERSION`、schema、`README.md`）。
- 不做任何 **git 写操作**（`add/commit/push/checkout/reset/stash/worktree`）。
- 不改 `docs/项目实施进度与任务看板 V2.2.md`。
- 不做 **DB DDL/DML**（除 A4 经放行的迁移）；不重启 8090／8091／8092。
- 不做破坏性操作（DROP／DELETE／TRUNCATE／删文件）。
- 不改写既有 108 行 quarantine 历史文本、不回填原因码。
- 不改 `event_id` 语义／去重键属主（D-055／D-062／D-090）。
- 不改既有 CLI 入参名（D-092）。
- 不把 `Cleaners.normalizeTime` 的输出**直接**写进 ODS（它丢偏移、输出无偏移串，会违反 `iso8601_time` pattern）。
- 不依赖 `input_file_name()`（D-060 实测为空串）。
- 不用 Spark 自动推断 schema（plan L155）。
- **不发明数据**：不在时间缺失/无法判定时补默认值；不猜时区。

### 2.4 停止并报告清单

1. 需要改冻结契约 `rule` 主体 ⇒ 停。
2. 需要改既有 CLI 入参名 ⇒ 停。
3. 需要重启 8090／8091／8092，或做计划外 DB DDL/DML ⇒ 停。
4. 泳道外文件"消失"或签名突变 ⇒ 停。
5. A3 导致既有 `dt`/`hour` 取值变化 ⇒ 停。
6. A8 出现任一 > 0 ⇒ 停。
7. 需要改 `Cleaners.normalizeTime` 既有语义 ⇒ 停。
8. 需要改 `landing_file`（D-057）语义 ⇒ 停。
9. P2-03 未收口而本单被要求动 `spark-jobs/**` ⇒ 停并请求裁决。
10. 夹具落点盘不到合适基座 ⇒ 停（不自造目录）。

---

## §3 证据计划 E1–E5

| 编号 | 层次 | 内容 | 产出 |
|------|------|------|------|
| **E1** | 单元／向量 | A2 的三态判定逐例；C9 向量表（交总控复算） | `evidence/E1-unit-vectors.md`、`raw/E1-test-output.txt` |
| **E2** | 本地真链（单源） | `golden-55` 本地链：ODS→…→指标，与 P2-01 冻结读数逐值比对 | `evidence/E2-golden55.md`、`raw/E2-*.txt` |
| **E3** | 本地真链（大批量） | `gen-s3b-1000`（1,000 行）本地链；含 C5 零误伤守卫 | `evidence/E3-gen1000.md`、`raw/E3-*.txt` |
| **E4** | 负例专档 | A7 夹具逐条：多格式接受、坏时间、未知版本、缺 id | `evidence/E4-negatives.md`、`raw/E4-*.txt` |
| **E5** | 复算与守卫 | C6 契约一致（pattern 逐行）、C7 基线不变（`spark-warehouse` 文件数与整体 sha256 口径）、C8 原因码复算、A9 列集守卫 | `evidence/E5-guards.md`、`raw/E5-*.txt` |

E1–E5 全部要求：**命令原文 + 原样输出 + 退出码 + 读数时钟 + 阳性对照**（本项目的假零陷阱）。

---

## §4 回滚方法

| 层次 | 方法 |
|------|------|
| 代码 | 全部改动落在**一次提交**内；回滚 = 回到 §2.1 记录的「开工基线提交」。**本单不授权 git 写**，回滚由总控执行 |
| DDL（A4） | 迁移必须**可回退**：新增列 `reason_code` 为 `NULL` 可空、**无默认值改写**、**不删列**；回退 = 停用写入者 + 忽略该列（历史 `NULL` 语义 = "未知/历史"，与 R-3 一致） |
| 数据 | A3 使用 `INSERT OVERWRITE`，**重跑即幂等**；不需要也不允许删除既有分区文件。若必须丢弃本次结果：按 D-091 建议的**备份清单**（表/分区/行数/路径/checksum）恢复 |
| 夹具 | 夹具为**新增文件**；回滚 = 删除新增夹具（**由总控执行**，本单不授权删文件） |
| 分区口径 | 若 A3 后必须回到位置切片：**回滚代码 + 重跑 `INSERT OVERWRITE`**；因为 `INSERT OVERWRITE` 幂等，这是可逆的；但**必须先有 §2.1 的开工基线**才能证明"回到原值" |

**回滚验证**：回滚后必须重跑 **A8（C5 零误伤）** 与 **C7（基线不变）**，二者通过才算回滚成功（不能只看"编译过了"）。

---

## §5 不得声称清单

实施者与报告者**一律不得**声称下列内容（除非有对应的、可复算的证据文件）：

1. 不得声称「**已支持多格式时间**」——真实语料里多格式**零发生**（2,622,616 个取值 100% 单形态）；只能声称「对**构造夹具**中的 N 种格式各 ≥1 行通过」。
2. 不得声称「**真实数据已验证**」任何多格式能力。
3. 不得声称「**已统一 UTC**」——除非 D-101 已裁决且派生列已落地并有逐行证据。
4. 不得声称「**quarantine 原因码已具备**」——除非 A4 的迁移已放行且**真库**有实测行。
5. 不得声称「**已实现 `FUTURE_TIME`**」——本单不落地它（D-107）。
6. 不得声称「**`dt`/`hour` 已正确**」——除非 A3 通过且 C7 基线比对逐值一致；**位置切片的风险仍是未测项（U2）**。
7. 不得声称「`UNIX_TIMESTAMP` 对 `+08:00` 串的行为」——U1 **未测**。
8. 不得声称「**解析失败已全部隔离**」——`OdsLoadSql.scala:181` 的静默丢弃是否已消除，需 A3 证据。
9. 不得声称「**历史 108 行已带原因码**」——R-3 明确不回填；历史行为 `NULL`。
10. 不得声称「**P2-03 与本单互不影响**」——两者都碰 `spark-jobs/**`，见 §2.1。
11. 不得把 `Cleaners.normalizeTime` 的既有测试当作「多格式已实现」的证据（它生产调用为 0，且语义与 plan L156 冲突）。
12. 不得声称「**Mock／构造夹具 = 真实**」。
13. 不得声称「**看板状态已更新**」——本单不改看板。
14. 不得声称「**契约已同步**」——本单不改 `contract-specs/**`；如需同步，走 CT 批次由总控执行。
15. 不得在**未附阳性对照**的情况下声称任何「0 命中」。

---

## §6 开工检查表（打印出来逐项打勾）

- [ ] 规格草案的 9 条待裁决（D-101…D-109）**全部已下**
- [ ] P2-03 已收口，或总控已给出并行范围白名单
- [ ] 已重取 HEAD 与全部被改文件的 sha256 + 行号（本单行号**必然已漂移**）
- [ ] 已记录「开工基线提交」
- [ ] A4 的 Flyway 迁移已获总控放行（D-092）
- [ ] A7 的夹具落点已裁决，且**不污染** `landing/` 既有 349 文件基线
- [ ] 已确认 `landing_file`（D-057）与 `Violation.lineNo` 的可用性
- [ ] 已确认不使用 `input_file_name()`（D-060）
- [ ] 回滚方案与回滚验证命令已写明
- [ ] §5 不得声称清单已随单下发
