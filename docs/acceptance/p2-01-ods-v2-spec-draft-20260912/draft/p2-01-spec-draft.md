# P2-01「ODS v2 公共列与 payload 原样保真」施工规格草案（DRAFT，待总控裁决）

- 性质：**只读调查产出的施工规格草案**，供总控裁决后派发实施泳道。**本文件不是代码、不是验收报告、不是结论书。**
- 生成时点：2026-09-12 11:39（本会话）。
- 生成者约束（已遵守）：未写任何生产代码、未改任何仓库文件（本文件写于 gitignore 的 `.verify/` 下）、未跑 Maven、未启停任何服务、未连接/修改任何数据库。三个在跑程序（8090/8092 及一直在跑的 8091）全程未触碰。
- 权威顺序（引用时按此判定）：① `contract-specs/**` ② `docs/项目完整实施指导书 V2.3.md` ③ `docs/项目实施进度与任务看板 V2.2.md`（唯一执行入口）④ 专项设计/实施书 ⑤ V2.1 及更早。
- 所有结论均带 `文件:行` 或 `命令 + 原始输出`；查不到的写「未查到」，不推测、不补全。
- 本草案**不替代**总控的任务派发；§7 列出必须先裁决的 8 个问题。

---

## 0. 任务卡与前置

### 0.1 看板原文（唯一执行入口）

`docs/项目实施进度与任务看板 V2.2.md`（396 行，sha256 `99831EEC7CD7E8291D99C2365549D8AF877FB376920E666A28435FF13FE75F87`）**L220** 逐字：

```
| P2-01 | ODS v2 公共列与 payload 原样保真 | `TODO` | B | P1-06 | DDL/显式 schema/额外字段无 DDL 变更 |
```

- 泳道 **B**（V2.2 L59：`B` = 数仓/ADS/质量/指标发布）。
- 依赖 **P1-06**。
- 「最小出口证据」列 = `DDL/显式 schema/额外字段无 DDL 变更`。
  **该列有三种读法，必须先钉口径**（见 §7 D-1）：①三件证据并列 = DDL 证据 + 显式 schema 证据 + 「额外字段不引起 DDL 变更」证据；②一条复合证据 = 「DDL / 显式 schema / 额外字段」三者都满足「无 DDL 变更」；③其他。本草案按读法①组织断言（最能覆盖三种读法），但**不代总控定读法**。

### 0.2 前置 P1-06 状态（已核实，不是转述）

- 看板 **L218** P1-06 = `` `DONE` ``，并在同行留下 2026-09-12 的补记，逐字含「**T2 已于 2026-09-12 09:23–09:31 完成**——run 41 SUCCESS（4 min 46 s，8/8 阶段），发布快照 `S20260901_41` v9 ACTIVE」。
- 证据目录在盘上：`docs/acceptance/p1-06-golden55-20260912/`（README + raw 原始文件）。
- 但同行的「**不得当通过的边界**」逐字给出三条，P2-01 继承：批 40 的 1,667 条 09-11 事件未入仓；T2 链实际跑了 2 次；`ads_data_quality_m` 每快照 4 行 vs `adsChecksPersisted=6` 未查清。
- 前置从「文档层面」成立；**不等于**P2-01 可以在真数仓上重跑（见 §5.6 R1/R2）。

### 0.3 与 P2 其他车道的关系（避免越界）

看板 V2.2 §3.4（L206-225）P2 行依次为 P2-01…P2-06。设计书 L200 的 P2 行把改动面写作：`warehouse/ddl/00-ods.sql`、`OdsLoadSql.scala`、`EventOdsLoadJob.scala`、`LocalSchemaInitJob`、`TradeDwdJob.scala`、`IdCodec.scala` → `SurrogateKeys.scala`。**其中 `TradeDwdJob.scala` / 代理键属 P2-04（DWD 由画像投影）**，本草案把 DWD 侧的改动**排除在 P2-01 之外**，并在 §5.3 A11 用「DWD/DIM 零改动」把边界钉住。

---

## 1. P2-01 的原始要求（逐字引用 + 行号）

### 1.1 计划书 §3.3 ODS v2 公共列

`docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md` **L77-81**：

> **ODS v2**：四主题 ODS 至少共享：`event_id`、`source_system`、`schema_version`、`raw_event_type`、`event_time`、`ingest_time`、`ingest_batch_id`、`landing_file`、`payload_json`、`payload_hash`、`dt`、`hour`。
> `payload_hash = SHA-256(UTF-8 原始 payload 字节)`；**不可对 JSON 重排后再算**，否则无法证明「原样保真」。
> 去重仍以 `(source_system, event_id)` 为业务键，hash 只用于冲突诊断（不代替业务键）。

⇒ **12 个公共列**（其中 `dt`/`hour` 实测为分区列）与**两条硬约束**：① hash 必须算在**原始字节**上；② 业务键是 `(source_system, event_id)`，hash 不得取代它。

### 1.2 计划书 §P2-01

同文件 **L147-151**：

> ### P2-01 ODS v2 DDL
> 为每个 source namespace 建 v2 表；不在原多源表上混写。
> `payload_json` 保存原始对象字符串，`raw_event_type` 保存源词汇，`source_system` 由平台注册表注入而非信任外部值。
> schema 不匹配时只生成「需要重建」的计划，必须经 `INIT_SCHEMA` 审计步骤执行。

⇒ 三条语义要求：`payload_json` = **原始对象字符串**；`raw_event_type` = **源词汇**（不是规范词汇）；`source_system` = **平台注册表注入**。外加一条流程要求：结构不匹配 → 只出「需要重建」计划 → 由 `INIT_SCHEMA` 审计步骤执行（**不得静默 DROP**）。

### 1.3 成功判据与后续任务对「额外字段」的原文

- 同文件 **L22**（成功判据 5）：「ODS 原样保留 payload；额外字段不需要 DDL 变更」。
- 同文件 **L182**（P2-06）：「多余字段只进入 `payload_json`，不需要 DDL」。

⇒ 「额外字段不进列、只进 `payload_json`」是**明确要求**，且必须做到**不需要 DDL 变更**。

### 1.4 设计书补充（G3 / 结构示例 / 断言 / 风险）

`docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md`：

- **L40 G3**：ODS 保真目标。
- **L127 / L148**：`"@keep"` 字段（例：`coupon_code`）**仅保留在 `payload_json`**。
- **L163-177**：ODS 保真结构示例，列 `event_id / source_system / schema_version / raw_event_type / event_time TIMESTAMP / ingest_batch_id / landing_file / payload_json / payload_hash`（**9 列**，无 `ingest_time`，`event_time` 标 `TIMESTAMP`）。
- **L179-183**：DWD 由画像投影，用 `get_json_object(payload_json, '$.<源字段>')`。
- **L187-191**：代理键算法（P2-04 范围）。
- **L200**：P2 行改动面（见 §0.3）。
- **L213-216**：A1–A4 断言；**A4 = `coupon_code` 在 `payload_json` 可见 + `git diff` 无 `ddl/**`**。
- **L226**：风险「P2 改 ODS 结构导致历史演示数据不可用 → 保留 v1 DDL 一个版本；重建前备份 `spark-warehouse`」。

### 1.5 指导书 V2.3 的裁决（重建纪律）

`docs/项目完整实施指导书 V2.3.md` **L666** 裁决 2 逐字：

> ODS v2 允许对演示数据重建。重建前必须备份对应 warehouse 目录、记录表数/行数/checksum，并由显式的 `INIT_SCHEMA` 审计步骤执行；不得静默 DROP，也不得影响其他 source namespace。

**取证边界（必须写明）**：全仓 grep（`payload_json|公共列|landing_file`）在 V2.3 中只命中 1 处，即上述 L666 附近；**V2.3 对 ODS v2 本身几乎没有规格**⇒ P2-01 的规格权威实际是**计划书 §3.3 + §P2-01 + 设计书 §4.4**，不是 V2.3。

### 1.6 原文之间的三处不一致（不得由实施车道自行拍定）

| # | 冲突 | 甲方原文 | 乙方原文 | 本草案默认 |
|---|------|---------|---------|-----------|
| C-1 | 公共列是 12 还是 9 | 计划书 L79：12 列（含 `ingest_time`、`dt`、`hour`） | 设计书 L163-177：9 列 | 取 **12 列**（计划书在上位，且 L79 写「至少共享」；`dt`/`hour` 为分区列） |
| C-2 | `event_time` 类型 | 设计书 L171：`TIMESTAMP` | 现状全链路 `STRING`（`OdsLoadSql.scala:31`；`DwdSql.scala:22` 直接 `UNIX_TIMESTAMP(rn.event_time)`） | 取 **STRING 不变**（改成 TIMESTAMP 是破坏性变更，与「加法」「无 DDL 变更」直接冲突）→ 见 §7 D-2 |
| C-3 | v1 旧列是否保留 | 设计书 L226：保留 v1 DDL 与旧列**一个版本** | 「不引入重复的事实所有者」纪律（`payload_json` 成为 payload 唯一所有者后，`payload_*` 列是派生投影） | 取 **P2-01 双写**：v1 列继续按现行为填充，v2 列新增 → 见 §7 D-3 |

---

## 2. 契约侧读法

### 2.1 `contract-specs` 现状

- `contract-specs/VERSION` = `contract-specs 1.2.0`（1 行）。
- `contract-specs/README.md` **L3**：`specs/warehouse-namespace.v1.json` 已 **frozen**（2026-09-11）；**其余四个制品 DRAFT**。
- 同 **L43**：各程序各自校验自己的产物、程序间无 Java 依赖；`EventContract.java` 的重复常量待消除。
- 同 **L45**：**不得静默放宽**契约。
- 同 **L51**：canonical-event 的 owner/consumers 清单。
- 同 **L136-144**：冻结指纹表；**L142-144** 明确「指纹是复核辅助，`VERSION` 是版本所有者」。

### 2.2 `canonical-event.v1.schema.json`（867 行）提供的事实

- 信封 8 字段；`event_type` 12 类枚举；`source_system` `const` = `mock-mall`；`schema_version` `const` = `1.0`；根与 payload 均 `additionalProperties: true`。
- **L5** 真实数据符合度：55 行中 **26 通过 / 29 不通过**（含 1 行不可解析）；与采集层「接受 51 / 隔离 4」**不一致**（`landing/manifests/30.json` 的 `acceptedRecords=51` / `quarantinedRecords=4`）。此差异 README 已记载，**P2-01 不得据此下结论，也不得顺手"修好"**。
- **契约要求 `items` 是数组**（L548-584，5 个子字段全部 required）；**实测真实数据里 `items` 是字符串**（见 §3.3）。
- 契约要求存在 `quantity`（L764）、`reserved_qty`（L774）、`available_qty`（L778）、`change_type`（L839）等字段（stock 三类事件）。
- 反向查询（grep `"available":` / `"reserved":`）**零命中**⇒ 契约中**不存在**裸键 `available` / `reserved`。

### 2.3 契约层的关键缺口

**没有任何 `contract-specs` 制品声明 ODS v2 的公共列集合。** 取证：全仓 grep `payload_json|payload_hash|raw_event_type`，**命中全部落在 `docs/**`**（计划书 L79/L81/L150/L182；设计书 L40/L74/L170/L174/L175/L200/L216），**`spark-jobs/src`、`analytics-server/*/src`、`warehouse/ddl`、`contract-specs` 零命中**。

⇒ 结论：**今天不存在任何"ODS v2 公共列"的机器可读事实所有者**。P2-01 若要落断言，必须先建一个所有者（§5.2），否则断言里的列名单会变成第 3 份手抄副本——正是项目纪律禁止的「重复的事实所有者」。

---

## 3. 现状事实（代码 / DDL，带 file:line 与片段）

### 3.1 12 个公共列的现状逐项对照

| # | 公共列 | 现状 | 证据 |
|---|--------|------|------|
| 1 | `event_id` | **有** | `warehouse/ddl/00-ods.sql` L12/34/61/83；`LocalSchemaInitJob.scala` L44/53/65/75 |
| 2 | `source_system` | **有列，但值来自外部文件**（违反 §1.2） | 列：`00-ods.sql` L16/38/65/87；**取值**：`OdsLoadSql.scala:96-97` 直接 select 行内 `source_system` |
| 3 | `schema_version` | **有**，取值来自行但被 `WHERE` 钉死为 `'1.0'` | `OdsLoadSql.scala:97` + `:107 / :135 / :157 / :185` |
| 4 | `raw_event_type` | **无**（全仓零命中） | grep 证据 §2.3 |
| 5 | `event_time` | **有**（STRING） | `00-ods.sql` L14/36/63/85；`OdsLoadSql.scala:31` |
| 6 | `ingest_time` | **有**（STRING） | `00-ods.sql` L15/37/64/86；`OdsLoadSql.scala:32` |
| 7 | `ingest_batch_id` | **有**（`BIGINT`，作业注入） | `00-ods.sql` L29；`OdsLoadSql.scala:20` 注释 + `:103` `$batchId AS ingest_batch_id` |
| 8 | `landing_file` | **无该列名**；最近似物是 `source_file`，且值是**常量字面量 `'landing'`** | `OdsLoadSql.scala:103 / :131 / :153 / :181` 均为 `'landing' AS source_file`；`SqlTemplateSpec.scala` L19-70 把该常量**断言成了正确行为** |
| 9 | `payload_json` | **无**（全仓零命中） | grep 证据 §2.3 |
| 10 | `payload_hash` | **无**（全仓零命中） | grep 证据 §2.3 |
| 11 | `dt` | **有**（分区列，由 `event_time` 派生） | `00-ods.sql` L30/57/79/106（`PARTITIONED BY (dt, hour)`） |
| 12 | `hour` | **有**（分区列，同上） | 同上 |

**缺口：12 列中缺 4 列**（`raw_event_type`、`landing_file`、`payload_json`、`payload_hash`），另有 2 列**语义不符**（`source_system` 信任外部；`landing_file` 位置被常量占位）。

### 3.2 payload 是「解析后重写」——这是保真缺口的机制根因

- 读取侧：`spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala:37`
  `spark.read.schema(EventLandingSchema.structType).json(landingDir)`
  ⇒ 用**闭合的显式 `StructType`** 读 JSON。Spark 的 JSON reader 在给定 schema 时**只保留 schema 中声明的键**，未声明的键**静默丢弃**。
- schema 本体：`spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala:28-73`，payload 是**闭合 STRUCT 31 字段**（L36-72）。
- 写入侧：`OdsLoadSql.scala:92-189` 四张表的模板一律 `payload.<f> AS payload_<f>` 投影到各自列，**没有任何一列保存原始对象字符串**。
- ⇒ **结构性结论**：现路径把 payload「拆成 31 个已知标量再拼列」，**原样保真在今天不可能成立**，与 §1.1 的「不可对 JSON 重排后再算」正面对立。

### 3.3 额外字段确实在丢（**实测**，非推测）+ 一条更严重的「契约内字段也在丢」

**取数方式（原始输出）**：对 `tests/golden-dataset/events/golden-20260901.jsonl`（55 行，第 53 行不可解析）逐行解析 `payload` 的**顶层键**并计数。结果（键 → 出现行数）：

```
age_group 5, amount 11, available_qty 9, behavior_type 17, brand_id 5, category_id 5,
change_type 1, channel 17, city_level 5, cost 5, items 6, member_level 5, order_id 26,
payment_id 5, price 5, product_id 31, product_name 5, quantity 9, reason 7, refund_id 6,
register_time 5, reserved_qty 8, session_id 17, status 5, total_amount 6, user_id 40
```

对 `landing/events/r9-m1-123006.jsonl`（55 行 / 1 行不可解析）复测，**键普查逐项相同**。

**与 `OdsLoadSql.landingSchema`（L36-72，31 键）逐键求差**：

| 方向 | 键 | 后果 |
|------|----|------|
| 数据有、schema **无** | `available_qty`(9)、`quantity`(9)、`reserved_qty`(8) | **静默丢弃**。而这三个键**正是契约要求的字段**（`canonical-event.v1.schema.json` L764 / L774 / L778 / L802 / L812 / L816 / L848 / L853）⇒ 这不只是「额外字段」问题，是**契约内字段一致性缺口** |
| schema 有、数据 **无** | `available`、`reserved`、`parent_category_id`、`parent_category_name`、`pay_amount`、`paid_at`、`completed_at` | 这 7 列**永远为 NULL**；其中 `available`/`reserved`（L54-55）**在契约与真实数据里都不存在**（§2.2 反向查询零命中）⇒ schema 里存在**与任何真实来源都不对应的字段名** |

**补充事实**：`items` 在 6 行 `order_created` 中**全部是字符串**（stringified JSON），而契约 L548-584 声明为**数组**。`EventContractValidator.java:124-134` 只在 `items.isArray()` 时校验子项金额 ⇒ 字符串形态**使该校验静默跳过**（校验真空）。同时 `OdsLoadSql.scala:205-212` 存在一个 `itemsArrayType`（ArrayType）声明，**在装载路径中未被使用** ⇒ 「同一事实两种表示」的隐患（见 §7 D-7）。

### 3.4 schema 是「显式」的（这一点现状已合规，别改坏）

- grep `inferSchema` 于 `spark-jobs/src/main/scala/com/graduation/analytics/**`：**零命中**。
- `OdsLoadSql.scala:27` 注释逐字：`Landing 事件显式 Schema（§10.2 步骤 2：不自动推断生产 Schema）`。
- ⇒ 看板出口证据里的「显式 schema」今天是**已满足项**；P2-01 的义务是**保持显式**且**不再用闭合 payload 结构**（§5.3 A9）。

### 3.5 列集有**两个所有者**（现存重复，P2-01 必须处理）

1. `warehouse/ddl/00-ods.sql`（106 行，sha256 `36EDBF7929D9AA6441F898A6D5CF3346C99E7BB09E13F0F2914907BFA1161693`，4517 B）——Hive/beeline 静态 DDL，4 表：`ods_user_event` L11-30 / `ods_product_event` L33-57 / `ods_behavior_event` L60-79 / `ods_trade_event` L82-106。
2. `spark-jobs/.../job/LocalSchemaInitJob.scala` 的 `statements(ns)`（L36-247）——本地/Derby 路径 DDL，其中 ODS 四表在 **L43-82**，列集与 ① **逐列相同**（我逐列比对过：名称、类型、顺序一致）。

**关键事实**：真实链路里跑的是 ②（`sci` = `INIT_SCHEMA` 阶段，建表 37 对象），① 只在 beeline/集群路径使用。⇒ 加列时**必须同时改两处**，否则「本地能跑、集群不能跑」或反之。

### 3.6 写入侧与下游耦合（决定 P2-01 的边界）

- `EventOdsLoadJob.scala:41-46`：校验 `schema_version === "1.0"`、`event_id`/`event_type`/`event_time` 非空、`event_type` 在映射表内；L49 建临时视图 `landing_valid`；L52-69 执行 4 条 `INSERT OVERWRITE` 模板；L72-74 `rejectedByVersion`；L79 消息 `accepted=… rejectedVersionKeys=… topics=4`；L87-89 `outputTables(ns)`。
- `IdCodec.scala:17` 纪律：**任何其它地方不得手写** `CAST(payload_*_id AS BIGINT)`（唯一所有者）。`IdCodecSpec.scala:52-79` 用正则门禁把这条钉住了（含 `OdsLoadSql.behaviorFromLanding`）。
- **ODS 今天没有任何去重**：grep `row_number|distinct|group by` 于 `OdsLoadSql.scala` **零命中**。去重在 DWD：`DwdSql.scala:29-38`（`ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time)`）与 `:45-56`（`GROUP BY event_id HAVING COUNT(*)>1`）。
- ⇒ **重要事实**：下游去重键今天是 **`event_id` 单键**，**不是**计划书 §1.1 要求的 `(source_system, event_id)`。这是 P2-01 的**事实定义**与 P2-04 的**消费**之间的跨车道耦合（§7 D-4）。
- `DwdSql.scala:49`：`dwd_reject_record.raw_payload` 恒为 `NULL`。⇒ 「拒绝行能看到原文」**今天不成立**，P2-01 不得把它当既有能力引用（它是 `payload_json` 落地后的自然受益项，属 P2-04）。
- `JobCommandBuilder.java:59 / :97 / :104-111`：平台已把 `--hiveDatabasePrefix=<prefix>` 与 `extra` 透传进 spark-submit；`PipelineService.java:417` 今天只放一个 `landingDir` extra。⇒ **若 `source_system` 要由平台注入，注入点已存在**（`extra` 通道），但需要新参数名（§7 D-5）。

### 3.7 缺口清单（G）

| ID | 缺口 | 证据 | 归属 |
|----|------|------|------|
| G-01 | 12 公共列缺 4：`raw_event_type` / `landing_file` / `payload_json` / `payload_hash` | §2.3 grep；§3.1 | **P2-01** |
| G-02 | payload 被解析成 31 标量后重写，原始对象字符串无处存 | `EventOdsLoadJob.scala:37`；`OdsLoadSql.scala:28-73, 92-189` | **P2-01** |
| G-03 | 闭合 schema 静默丢弃 `available_qty`/`quantity`/`reserved_qty`（契约要求的字段） | §3.3 键普查 | **P2-01** |
| G-04 | `source_system` 取值来自外部文件，未由平台注册表注入 | `OdsLoadSql.scala:96-97` vs 计划书 L150 | **P2-01** |
| G-05 | `source_file` 值是常量 `'landing'`，且被测试断言为正确 | `OdsLoadSql.scala:103…181`；`SqlTemplateSpec.scala:19-70` | **P2-01**（改名/换义） |
| G-06 | 列集有两个所有者（`00-ods.sql` 与 `LocalSchemaInitJob`） | §3.5 | **P2-01**（至少加对账门禁） |
| G-07 | schema 声明 `available`/`reserved` 两个**真实来源中不存在**的字段名 | `OdsLoadSql.scala:54-55` vs §2.2 反向查询 | **P2-01** |
| G-08 | `payload_hash` 计算方式在 Spark 内可能不可逐字节达成 | §5.6 R3（**未实测**） | **P2-01（先红后绿）** |
| G-09 | `raw_event_type` 对源 B **不可得**（未知事件类型在采集层被隔离，原文不入落地区） | `EventContractValidator.java:65-67`；`BEHAVIOR_TYPES` L28 | **需裁决**（§7 D-6），可能越界到泳道 A/P5 |
| G-10 | `event_time` 类型（STRING vs TIMESTAMP）与「无 DDL 变更」冲突 | §1.6 C-2 | **需裁决**（§7 D-2） |
| G-11 | 下游去重键是 `event_id` 单键，非 `(source_system,event_id)` | `DwdSql.scala:29-38, 45-56` | **P2-01 定义 / P2-04 消费** |
| G-12 | `mock-mall.v1.json` 不存在 ⇒ 任何依赖画像文件的实现都会阻塞 | `source-profiles/README.md:17, 21-26` | **P2-01 不得依赖** |

---

## 4. 现有测试覆盖 + 未覆盖真空

### 4.1 覆盖清单（路径 + 用例数 + 与 ODS/payload 的关系）

| 文件 | 用例数 | 与 P2-01 相关的部分 |
|------|--------|---------------------|
| `spark-jobs/src/test/scala/com/graduation/analytics/SqlTemplateSpec.scala`（245 行） | 22 个 `it should` | **L19-70 五条 ODS 断言**：派生 dt/hour + `'1.0'` + `landing_valid` + `partition (dt, hour)`；user 主题含 `payload_user_id/payload_age_group/payload_member_level/payload_register_time/'landing' as source_file`；product **L49** `cast(payload.price as decimal(18,2))`；trade **L59-60** `payload_items`/`payload_refund_id`；rejected **L64-70** `schema_version <> '1.0'` + `bad_version_or_key` |
| `spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala`（110 行） | 7 个 | **L52-79** 负向门禁「禁止手写 `CAST(payload_*_id AS BIGINT)`」（含 `OdsLoadSql.behaviorFromLanding` L78）；**L105-109** 「ODS 保留字符串原文」（断言含 `payload.user_id AS payload_user_id` 且 **不含** `as bigint`） |
| `spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala`（255 行） | 6 个 | 命名空间契约向量（P1-04），与 ODS 列无关 |
| `spark-jobs/src/test/scala/com/graduation/analytics/JobArgsRegistrySpec.scala`（45 行） | 3 个 | 作业参数登记（新参数须登记在此） |
| `analytics-server/platform-common/src/test/java/.../contracts/CanonicalEventSchemaParityTest.java`（164 行） | 5 个 `@Test` | L41-112：信封 8 字段 / 12 类枚举 / `schema_version`+`source_system` 常量 / `$defs` 路由 / 金额正则——**契约↔常量对账的范式**，可复用于「ODS 公共列↔DDL」对账 |
| `analytics-server/warehouse-pipeline/src/test/java/.../spark/SparkStageExecutorSmokeTest.java`（184 行） | 1 个 | 真实本地 Spark smoke：profile id 7、`spark-submit`（`D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd`）、warehouse `tests/r6-smoke-warehouse/warehouse`、夹具 `tests/golden-dataset/events`、作业 `sci`（L89）、`landingDir` 参数（L111）；**L28 注释**「golden 55 行（R6-8b 扩充：52 接受 / 3 rejected：坏 JSON、schema_version=2.0、缺 event_id）」；`partitionExists` L170-183 |
| `analytics-server/platform-common/src/test/java/.../warehouse/WarehouseNameLiteralGateTest.java`（216 行） | 3 个 `@Test` | **可复用的门禁范式**：扫 `spark-jobs/src/main/`、`warehouse/ddl/`、`scripts/`、`analytics-server/*/src/main/**`；`OWNER_FILES` 白名单 L40-42；`BARE_LITERAL` L45；`DYNAMIC_PREFIX` L47；`SKIP_DIRS` L59 |
| `analytics-server/warehouse-pipeline/src/test/java/.../spark/JobCommandBuilderTest.java`（155 行） | 9 个 | L125-134 `hiveDatabasePrefix` 来自 profile 或缺省 `dw` |
| `analytics-server/warehouse-pipeline/src/test/java/.../contracts/EventContractTest.java`（157 行） | 4 个 | 契约常量 |
| `analytics-server/connection-ingestion/.../EventContractValidator.java` | （主代码） | L22-28 白名单/版本/行为枚举；L56-67 信封必填 + payload 必须是对象 + **未知事件类型直接违规**；L113-136 金额校验（`items` 非数组时**跳过**） |

**ODS/payload 相关用例总数 ≈ 5（SqlTemplateSpec）+ 2（IdCodecSpec）+ 1（SmokeTest）= 8 条**，其余 30+ 条与本任务无关。

### 4.2 真空清单（V，全部「无任何测试」）

| ID | 真空 | 为什么是真空 |
|----|------|-------------|
| V-01 | **无任何测试断言 payload 的字节/字段保真** | 现有 8 条 ODS 用例全部只断言 **SQL 文本包含某些子串**（`include("payload.user_id AS payload_user_id")`），没有一条读真实数据比对 |
| V-02 | **无任何测试断言额外字段被保留** | 全仓无「额外字段/未知键」用例 |
| V-03 | **无任何测试断言 ODS 表列集 == 某份声明清单** | 列集只存在于两处 DDL 文本，无对账断言 ⇒ `00-ods.sql` 与 `LocalSchemaInitJob` 可任意漂移而全绿 |
| V-04 | **无任何测试对 `warehouse/ddl/*.sql` 做哈希/指纹钉定** | `WarehouseNameLiteralGateTest` 只查「库名是否变量化」与「恰好 5 个文件」，**不查列** |
| V-05 | **无任何测试断言「0 行丢失」非真空** | 没有任何正向对照用例（送 N 行进、断 N 行出且键集完整） |
| V-06 | **无任何测试覆盖 `payload_hash`** | 该列不存在 |
| V-07 | **无任何测试覆盖 `landing_file` 的真实来源** | 现状是常量 `'landing'`，且被 `SqlTemplateSpec` **断言为正确**（负向价值：改动必须先改这条断言） |
| V-08 | **无任何测试覆盖 `source_system` 的注入来源** | 现状信任外部值 |
| V-09 | **无 `INIT_SCHEMA`「需要重建」计划路径的测试** | `LocalSchemaInitJob.reconcile`（L275-299）只处理 `snapshot_id` 遗留列与 `R7_ADDED_COLUMNS`（L259-261），无「v2 需要重建」分支 |

### 4.3 已知不可跑 / 受限路径（排障前置，避免把环境问题误判为回归）

- `-DforkCount=0` 会破坏依赖 surefire cwd = 模块 basedir 的路径门禁测试（F-19 / DEF-16）⇒ 门禁类测试**不能**加 `-DforkCount=0`。
- 真实 Spark smoke 需要先有 `spark-jobs` 的 jar（现盘上 `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar`，234,038 B，mtime 2026-09-11 18:19:03），并需 `SPARK_DRIVER_MEMORY=512m`。
- `beeline --hivevar WAREHOUSE_PREFIX=…` 的变量替换**本机未实跑**（无 HiveServer2）——`docs/acceptance/p1-04-namespace-20260911/README.md` L79 已留痕。
- `.verify/p1-06-hive-counts.ps1` 是唯一验证过的 Hive 只读取数范式；其 L2 硬前置：**流水线的 Spark 必须已结束**（嵌入式 Derby 单进程占用）。

---

## 5. 施工规格草案

### 5.1 推荐口径（最小、加法、可回滚）

1. **只做加法**：v1 的 9 个业务列 + `trace_id` + v1 的 `payload_*` 列**全部保留、类型不变、名字不变**；新增 4 列（`raw_event_type STRING`、`landing_file STRING`、`payload_json STRING`、`payload_hash STRING`）。
2. **双写**：`payload_*` 列继续按现行为填充（保证 DWD/DIM/DWS/ADS 零改动仍可跑），同时新增 `payload_json`/`payload_hash`。理由：设计书 L226 要求「保留 v1 DDL 与旧列一个版本」，且 P2-04 才切 DWD 到 `get_json_object(payload_json, …)`。
3. **`payload_json` = 原始对象字符串**：取落地区 JSON 行中 `payload` 键对应对象的**原始文本**（原样字节），不做解析后重排、不做美化/压缩。
4. **`payload_hash` = SHA-256(UTF-8(payload_json 原始字节))**，十六进制小写。只用于冲突诊断，**不参与去重**（去重键仍为 `(source_system, event_id)`）。
5. **`source_system` 由平台注入**：不在 SQL 里直接信任行内值；注入值来自 `source_registry.source_code` 经平台参数通道（复用 `JobCommandBuilder` 的 `extra` 通道，L104-111）。**不得**依赖 `mock-mall.v1.json`（G-12）。
6. **`landing_file` 取真实来源文件标识**（候选实现 `input_file_name()`，**未实测**）；`source_file` 的常量 `'landing'` 必须消失或改为真实值（二者关系见 §7 D-8）。
7. **结构不匹配只出计划**：新增「ODS v2 需要重建」的**判定 + 计划输出**，执行路径必须是显式 `INIT_SCHEMA` 审计步骤；**禁止**静默 `DROP`，**禁止**碰其他 source namespace。
8. **唯一所有者**：新建一个 ODS 公共列的**唯一事实所有者**（Scala 常量或合同制品），`LocalSchemaInitJob` 从中派生；`warehouse/ddl/00-ods.sql` 保持静态 SQL 文本但被**对账门禁**钉住（不能生成，因为 beeline 要直接执行）。

### 5.2 改动面

**主代码（预计 4-6 个文件）**

| 文件 | 改动性质 |
|------|---------|
| `spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala` | ① `landingSchema` 的 `payload` 不再以闭合 STRUCT 承载（或标注为「仅 DWD 投影用」）；② 4 条模板新增 4 列 select；③ `'landing' AS source_file` 换真实值；④ `source_system` 改为注入常量 |
| `spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala` | 读取路径改为「原样保留 payload 文本」；接收并校验注入的 `sourceSystem` 参数 |
| `spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala` | ODS 四表（L43-82）加 4 列；新增「v2 需要重建」判定；重建动作必须可由审计步骤触发 |
| **新建** ODS 公共列唯一所有者（建议 `spark-jobs/.../sql/OdsCommonColumns.scala` 或 `warehouse/ods-common-columns.v1.json`） | 12 列的**唯一**名单 + 类型 + 分区列标注 |
| `spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala` | **仅在需要 per-source namespace 时**改（计划书 L148「为每个 source namespace 建 v2 表」）→ **需裁决**（§7 D-9） |
| `analytics-server/warehouse-pipeline/.../spark/JobCommandBuilder.java` | 新增注入参数（若裁决走平台注入通道） |
| `analytics-server/warehouse-pipeline/.../PipelineService.java` | L417 附近为 ODS 阶段补 `extra`（`sourceSystem` 等） |

**DDL / SQL**

| 文件 | 改动性质 |
|------|---------|
| `warehouse/ddl/00-ods.sql`（现 106 行 / sha256 `36EDBF79…`） | 四表各 **ADD** 4 列；`PARTITIONED BY (dt, hour)` 不变；`LOCATION` 不变 |
| `warehouse/ddl/` 新增「v2 迁移/重建」脚本（若走 DDL 脚本路径） | 必须是非破坏性 ADD COLUMNS 优先 |

**测试（新增/修改）**

| 文件 | 改动性质 |
|------|---------|
| `spark-jobs/src/test/scala/.../SqlTemplateSpec.scala` | **必须改**：`'landing' as source_file` 与「payload 投影」两类既有断言会与 v2 冲突（V-07） |
| `spark-jobs/src/test/scala/.../IdCodecSpec.scala` | 检查 L105-109 是否仍成立（`payload.user_id AS payload_user_id` 若保留双写则不变） |
| **新建** ODS 公共列对账门禁（Java 或 Scala，建议复用 `WarehouseNameLiteralGateTest` 的扫描范式 + `RepoRoot`） | 断言 `00-ods.sql` 与 `LocalSchemaInitJob` 的 ODS 列集 == 唯一所有者名单 |
| **新建** payload 保真用例（含独立 oracle 与正向对照夹具） | 见 A2/A3/A4 |
| **新建** 额外字段正向对照夹具（`tests/golden-dataset/events/` 下**追加**新文件，不改历史夹具） | 见 A3 |
| `SparkStageExecutorSmokeTest.java` | L28 注释里的「52 接受 / 3 rejected」口径需与 manifest 对齐（§4.1 / §7 D-10） |

**契约 / 文档**

| 文件 | 改动性质 |
|------|---------|
| `contract-specs/specs/` 新增 ODS 公共列制品 | **只有**在总控裁决「契约层应成为唯一所有者」时才做；`contract-specs/VERSION` 需随之升级（README L142-144：`VERSION` 是版本所有者） |
| `warehouse/README.md` | 追加 v2 列说明与重建步骤（只追加） |
| `docs/acceptance/p2-01-*/README.md` | 实施车道产出（**不在本草案范围**） |
| `docs/项目完整实施指导书 V2.3.md` / 看板 V2.2 | **本草案不动**；状态收敛由总控按 D-051 的方式做 |

### 5.3 验收断言（12 条，均为「输入 → 期望」）

> 通用要求：每条断言必须**可执行**且**带正向对照**；「计数为 0 / 字段为空 / 无改动」类断言**没有正向对照即视为无效**。
> A2/A3/A4/A8 需要**真实行数据**（E3 级）；A1/A5/A6/A7/A9/A10/A11/A12 可在 E1/E2 级用文本/单元断言完成。

**A1 公共列存在性（DDL 双所有者）**
- 输入：`warehouse/ddl/00-ods.sql` 与 `LocalSchemaInitJob.statements(ns)` 的 ODS 四表定义。
- 期望：四表的列集各自 **⊇ 12 公共列**名单（名单来自 §5.2 的唯一所有者，**测试内不得再抄一份字面量**）；`dt`/`hour` 出现在分区子句而非普通列。
- 正向对照：把名单中任意一列从 DDL 文本里删掉（内存字符串替换）后门禁**必须变红**。

**A2 `payload_json` 逐字节保真 + `payload_hash` 算法（独立 oracle）**
- 输入：夹具 `tests/golden-dataset/events/golden-20260901.jsonl` **第 1 行**。
- 期望：ODS 中该 `event_id` 行的 `payload_json`，与**用非 Spark 工具**（Python/PowerShell 原样截取 `"payload"` 对象的字节）得到的字符串 **SHA-256 相同**；且 `payload_hash` == 该 SHA-256 的十六进制小写。
- 正向对照（必须是负例）：把该行 payload 内做一处**语义等价但字节不同**的改动（例：`"1.50"`→`"1.5"`，或键间插入一个空格），用**同一实现**再跑，A2 **必须变红** ⇒ 证明断言测的是字节而不是语义。

**A3 额外字段不丢（正向对照夹具为必需前置）**
- 输入：**新建**夹具文件，某行 payload 含画像与契约里都不存在的键（建议用 `source-profiles/p1-03-probe-1.v1.json:16` 已定义的 `"coupon_code": "@keep"` 语义，键名 `coupon_code`），其余字段与既有夹具同构。
- 期望：该行被接受入 ODS；`payload_json` 中 **可见** `coupon_code` 及其原值；且 `warehouse/ddl/**` **无任何改动**（以 §5.5 的哈希对账为准，不以 `git status` 为空为准）。
- 负向对照（先红）：改动前用**同一输入**跑一次，`coupon_code` **必须不可见** ⇒ 证明该断言非真空。

**A4 契约内字段不丢（实测缺口的直接回归）**
- 输入：golden-55 中 `stock_reserved`/`stock_released` 行（payload 含 `reserved_qty`/`quantity`）与 `product_created`/`product_updated` 行（含 `available_qty`/`quantity`）。
- 期望：ODS 行的 `payload_json` 中 `available_qty`(9 行) / `quantity`(9 行) / `reserved_qty`(8 行) **可见且值与原文件逐字节一致**。
- 负向对照（已实测为真）：改动前这三键在 ODS 中 **100% 不可见**（§3.3 键普查）⇒ 断言的方向性明确。

**A5 `payload_hash` 与业务键的分工**
- 输入：两行 `event_id` 相同、`payload` 不同、`source_system` 相同的落地区事件。
- 期望：① `payload_hash` 两行**不同**；② 去重键仍是 `(source_system, event_id)`，`payload_hash` **不参与**去重（ODS 的现状行为——**不去重**——必须保持不变，去重仍在 DWD）；③ 存在一条可执行的查询按 `(source_system, event_id)` 聚合后列出 `COUNT(DISTINCT payload_hash) > 1` 的冲突行。
- 正向对照：`payload_hash` 相同的两行（同 payload 同 event_id）**不应**出现在冲突清单中。

**A6 `source_system` 由平台注入而非信任外部**
- 输入：落地区一行把 `source_system` 写成 `"evil-source"`（其余合法）；对照组一行写成正常值。
- 期望：ODS 中该 `event_id` 行的 `source_system` == 平台注入值（`source_registry.source_code`，实测为 `mock-mall`），**不等于** `evil-source`；且**不得**出现 `evil-source` 字面量（查询计数为 0 时必须配 A6 的对照组证明不是「整批全丢」）。
- 正向对照：对照组那行**必须入库**，且 `source_system` == 注入值。

**A7 `schema_version` 来自行而非字面量（静态断言，并标注数据级不可观测）**
- 输入：`OdsLoadSql` 生成的 4 条模板 SQL 文本。
- 期望：模板 SELECT 列表里 `schema_version` 是**列引用**（`schema_version`），不是字符串字面量 `'1.0'`；`'1.0'` 只允许出现在 `WHERE` 过滤中（现状即如此：`OdsLoadSql.scala:97` vs `:107/135/157/185`）。
- **必须写明的限制**：由于 `EventOdsLoadJob.scala:41-46` 与 `EventContractValidator.java:27`（`KNOWN_VERSIONS = {"1.0"}`）都把版本钉死为 `1.0`，**数据级「非 1.0 也被保真」不可观测**，任何该方向的断言都是真空 ⇒ **不得声称**。

**A8 `landing_file` 是真实来源而非常量（改动前必红）**
- 输入：落地区**两个不同文件**各取一行（例如 `landing/events/r9-m1-123006.jsonl` 与 golden 夹具各一行）。
- 期望：ODS 两行的 `landing_file` **互不相同**，且各自可对应回其来源文件。
- 负向对照（已实测为真）：现状是常量字面量 `'landing'`（`OdsLoadSql.scala:103/131/153/181`，且 `SqlTemplateSpec.scala:19-70` 把它断言成了正确行为）⇒ 两行相同 ⇒ 改动前该断言**必红**。

**A9 显式 schema 且 payload 不再以闭合 STRUCT 承载**
- 输入：`OdsLoadSql.scala` / `EventOdsLoadJob.scala` 源码。
- 期望：① 装载路径中不含 `inferSchema`（现状已满足，grep 零命中）；② payload 的键清单**不再**用于 ODS 装载的字段裁剪（即未知键不会被 schema 丢弃）；③ 若仍保留 payload 字段清单，必须以 `// 仅用于 DWD 投影` 之类注释显式标注用途。
- 正向对照：同一门禁在**改动前**的代码上**必须失败**（红 → 绿）。

**A10 两个列集所有者对账**
- 输入：`warehouse/ddl/00-ods.sql` 与 `LocalSchemaInitJob.statements(ns)`。
- 期望：ODS 四表的列集**逐列相同**（名称 + 类型 + 顺序）。
- 正向对照：人为在两者之一加一列（内存替换）后门禁**必须变红**。现状该门禁不存在（V-03）。

**A11 加法门 / P2-01 边界（v1 列不删、DWD 零改动）**
- 输入：改动前后 `warehouse/ddl/00-ods.sql` 与 `LocalSchemaInitJob` 的列集快照。
- 期望：① `v1 列集 ⊆ v2 列集`（`event_id/event_type/event_time/ingest_time/source_system/schema_version/trace_id/payload_*/source_file/ingest_batch_id` 全部保留）；② 无列**改名**、无类型**收窄**（`DECIMAL(18,2)` 保持）；③ `DwdSql.scala` / `DimSql.scala` / `DwsSql.scala` / `AdsSql.scala` **零改动**且仍能编译通过（P2-01 不碰 DWD）。
- 正向对照：`git diff --name-only` 中**不得**出现 `DwdSql.scala`/`TradeDwdJob.scala`（P2-04 范围）。

**A12 拒绝路径与口径（先钉口径再断言）**
- 输入：golden-55（含 1 行坏 JSON、1 行 `schema_version=2.0`、1 行缺 `event_id`）。
- 期望：ODS 四表合计行数 == 采集层实际接受行数；被拒行不入 ODS；`rejectedSelect()`（`OdsLoadSql.scala:192-199`）的 `BAD_VERSION_OR_KEY` 产出非空。
- **前置（必须先裁决）**：`SparkStageExecutorSmokeTest.java:28` 写「52 接受 / 3 rejected」，而 `landing/manifests/30.json` 是 `acceptedRecords=51 / quarantinedRecords=4`（§4.1 / §7 D-10）。口径未钉前 A12 **不得计为通过**。

### 5.4 正向对照设计（把 §0 纪律落到可执行）

| 断言 | 「0 / 空 / 常量 / 无改动」风险 | 必需的正向对照 |
|------|------------------------------|----------------|
| A2 | 「哈希相等」可能是两边都算错 | **语义等价但字节不同**的负例必须变红 |
| A3 | 「额外字段可见」可能因夹具为空而真空 | **改动前同输入必红**；且该行必须真被接受（行数非 0） |
| A4 | 「键可见」可能因没数据而真空 | 键计数必须 == 3.3 普查值（9/9/8） |
| A6 | 「无 evil-source」可能因整批全丢而真空 | 对照组行必须入库 |
| A8 | 「两行不同」可能因两行都 NULL 而真空 | `landing_file` 必须非 NULL 且可回溯到文件名 |
| A11 | 「零改动」可能因没跑而真空 | 必须给出**编译/测试实际执行**的证据，不能只给 `git status` |
| A1/A10 | 「无差异」可能因解析器没解析到任何列 | 门禁必须先在**改动前**代码上红（已知缺 4 列 ⇒ 天然非真空） |

### 5.5 最小验证命令（**文本，本轮未执行**）

> 纪律：下列命令**本轮一条都没有运行**。执行由实施车道在其自己的证据轮里做，并保留原始输出。

**(1) E1/E2 编译 + 受影响单测**

```powershell
$env:SPARK_DRIVER_MEMORY = '512m'
# spark-jobs（Scala 侧测试；含 SqlTemplateSpec / IdCodecSpec）
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o test -f spark-jobs\pom.xml
# 平台侧（含列集门禁 / 契约对账 / smoke）
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o test -f analytics-server\pom.xml -pl platform-app -am '-DforkCount=0'
```

注意：门禁类测试**不得**加 `-DforkCount=0`（§4.3，F-19/DEF-16）；`-pl platform-app -am` **不会**构建 `spark-jobs`（它是独立 Maven 工程），所以改了 Scala 代码后 smoke 用的 jar 需要单独重建——**而这会改变正在运行的 8091 下一次真实运行的行为**（§5.6 R1）。

**(2) DDL 哈希登记（改动前/后各一次，用于「无 DDL 变更」的可复现对账）**

```powershell
Get-ChildItem warehouse\ddl\*.sql | ForEach-Object { '{0}  {1}  {2}B' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash, $_.Name, $_.Length }
# 改动前基准（本草案实测）：00-ods.sql 36EDBF7929D9AA6441F898A6D5CF3346C99E7BB09E13F0F2914907BFA1161693  4517B
#                           01-dwd.sql 8ECF37D54D1944F61AE1573441126CEA76B8E1EFABFBCEC3445604543217652B  3509B
#                           02-dims.sql AD2D088113F3765116054EEE52A85EAC22054C040DD28ED7E32D60122C8EC1ED  2965B
#                           03-dws.sql D1BE5E71F112F70DE6B86E1CB975FDFD0BE76585489117EABF81AB9A97EFB4B1  4194B
#                           04-ads.sql 3057DE99C94372AF769A2E8E16B08264F88619FC34F35F81B6B0822DF2408A56  5207B
git status --porcelain -- warehouse/ddl
git diff --name-only -- warehouse/ddl
```

**读取时点与工作区状态（重要，避免误读）**：本草案所有引用读的是 **2026-09-12 11:39 的工作区**（当前分支 `remediation/r1-boundary`，HEAD `30d53c9`，2026-09-12 11:35:03）。当时工作区**本来就非干净**：`contract-specs/README.md`、`contract-specs/VERSION`、`contract-specs/openapi|schemas/*`、看板 V2.2、`synthetic-data-generator/**` 共 12 个已跟踪文件为 `M`，另有 `docs/acceptance/m1-5-contract-sync-20260912/` 等未跟踪项——**这些都不是本草案产生的**（本草案只新增 gitignore 下的 `.verify/p2/p2-01-spec-draft.md`，已用 `git check-ignore -v` 证实命中 `.gitignore:61:.verify/`）。
⇒ 因此**「`git status` 为空」在本仓库不是一个可用的判据**；「额外字段无 DDL 变更」必须用 §5.5(2) 的**逐文件哈希对账**来证明（本草案实测 `warehouse/ddl` 在 11:39 时点**无改动**，且 5 个 DDL 文件的哈希已登记在案）。

**(3) 独立 oracle：从夹具行原样截取 payload 并算 SHA-256（与 Spark 无关）**

```powershell
# 例：golden 夹具第 1 行；截取 "payload" 键对应对象的原始文本，逐字节算 SHA-256
$line = (Get-Content tests\golden-dataset\events\golden-20260901.jsonl -TotalCount 1)
# —— 需自行实现「JSON 对象原样切片」（按 { } 配对扫描，尊重字符串内转义），不得用 ConvertFrom-Json 再 ConvertTo-Json
# [BitConverter]::ToString((New-Object Security.Cryptography.SHA256Managed).ComputeHash([Text.Encoding]::UTF8.GetBytes($payloadText))) -replace '-',''
```

**(4) ODS 侧只读对账（**必须先在流水线 Spark 结束后**；严格复用 `.verify/p1-06-hive-counts.ps1` 的 conf/连接参数）**

```powershell
# 参考范式：.verify\p1-06-hive-counts.ps1（L7 spark-sql 路径；L40-52 warehouse/Derby URI；L54 调用）
# 只读语句（示例，未执行）：
#   DESCRIBE dw_ods.ods_trade_event;
#   SELECT event_id, landing_file, payload_hash, LENGTH(payload_json) FROM dw_ods.ods_trade_event WHERE dt='20260901';
#   SELECT source_system, event_id, COUNT(DISTINCT payload_hash) c FROM dw_ods.ods_trade_event WHERE dt='20260901' GROUP BY source_system, event_id HAVING c > 1;
```

**(5) 断言的负向对照（红）取证**

```powershell
# 门禁类：先在**改动前**的代码/文本上跑一次，必须失败并留下原始输出（红），再在改动后跑（绿）。
# 数据类：用「语义等价但字节不同」的输入跑同一实现，必须变红（A2）。
```

### 5.6 风险

| ID | 风险 | 证据 / 机制 | 处置建议 |
|----|------|------------|---------|
| **R1** | **重建 `spark-jobs` jar 会改变正在运行的 8091 的下一次真实运行行为** | 运行中 profile `id=1 local-dev`（ACTIVE）的 `sparkJobJarUri` = `D:/Develop_code/GraduationProject/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar`（`docs/acceptance/p1-baseline-r39-20260911/baseline.json:1265`、`raw/api-runtime-profile-active.json`）。该 jar 现盘上 mtime 2026-09-11 18:19:03。若新 jar 含 v2 列 SQL 而真库 `dw_ods` 仍是 v1 结构 ⇒ 下一次 `LOAD_ODS` 会因列不存在而失败；反之若 DDL 先改而 jar 未更新 ⇒ 旧 jar 写入的 v2 表 v1 列可为 NULL | P2-01 的 E1/E2 只用 `test`（不 `package`）；任何要重建 jar 的步骤必须与 DDL 执行**同一个审计轮**内完成，并在轮前记录 `dw_ods` 表数/行数/checksum |
| **R2** | **`INSERT OVERWRITE … PARTITION(dt, hour)` 空分区规格 = 整表分区替换（默认 STATIC）** | F-14（`docs/开发过程事实与决策记录.md` L1230-1234）：`spark.sql.sources.partitionOverwriteMode` 四处均未设置（Spark conf 目录无 `spark-defaults.conf`、`SparkSessionFactory.scala:12-23`、`SparkStageExecutorFactory.confsFor` 均未设；只有 `TradeDwdJob.scala:26` 为 DWD 设了 `dynamic`）；`OdsLoadSql.scala:19, 94, 115, 144, 165` 分区规格为空。**仅代码级取证，破坏性未验证** | ODS v2 重建**必须**先备份 `spark-warehouse`（指导书 L666 已要求）；F-14 破坏性验证**单独立轮**，**不得**并入 P2-01 |
| **R3** | **「逐字节保真」在 Spark 内可能不可达** | `get_json_object` / `to_json` / `from_json` 均经 Jackson 解析后重序列化，可能改变**空白、数字格式、键序**。**我未实测**这三种函数在 Spark 3.5.1 下的字节行为（未取证 U1） | 实施车道**第一步**就是写红测试实测（例：`{"a": 1,  "b": 1.50}` 经各候选函数后的字节），据实测选实现；**不得**先假设再实现 |
| **R4** | **`raw_event_type` 对源 B 不可得** | `EventContractValidator.java:65-67` 对未知 `event_type` 直接判违规 ⇒ 隔离；`KNOWN_TYPES` L22-26 只含 12 个规范名。源 B 的 `product_viewed` 等**原始名不会进入落地区** ⇒ 「raw ≠ canonical」在真实数据上**不可观测** | 见 §7 D-6：或裁决 P2-01 只保证「源 A 下 raw == canonical 且列存在」，并把「非平凡 raw」明确留给 P5；**不得**声称 raw_event_type 的映射语义已验证 |
| **R5** | **画像文件 `mock-mall.v1.json` 不存在** | `source-profiles/README.md:17`（属主 P3-01，当前不存在）、L21-26（种子源当前不可激活） | P2-01 的注入值只能取 `source_registry.source_code`（DB）；**不得**读画像文件；若某断言需要画像，标记为阻塞 |
| **R6** | `event_time` 类型冲突（STRING vs TIMESTAMP） | §1.6 C-2；`DwdSql.scala:22` 依赖字符串 `UNIX_TIMESTAMP(rn.event_time)` | §7 D-2 裁决前**不改类型** |
| **R7** | 列集双所有者：只改一处 ⇒ 本地/集群行为分叉 | §3.5 | A10 对账门禁 + 实施清单双列 |
| **R8** | `-DforkCount=0` 破坏路径门禁 | F-19 / DEF-16 | 门禁类测试单独跑 |
| **R9** | 本轮环境约束：三程序在跑（8090/8092 + 8091 一直在跑，pid 已知） | 会话约束；F-16（`scripts/start-all.ps1:96` 不返回）、F-17（不注入 `GENERATOR_TARGET_TOKEN`） | 实施轮**不得**启停三程序；需要 Spark 独占时先用只读方式确认流水线已结束 |
| **R10** | `items` 的两种表示（契约 = 数组，真实数据 = 字符串；`OdsLoadSql.scala:205-212` 的 `itemsArrayType` 未使用） | §2.2 / §3.3 | §7 D-7 |
| **R11** | 把 P2-01 做成「顺手把 ODS 推倒重建」 | 计划书 L148「不在原多源表上混写」可能被误读为「必须新建表」 | §7 D-9 裁决；在裁决前按**加法**做 |

### 5.7 未取证（本草案明确没有证实的事）

| ID | 未取证项 | 为什么没取 |
|----|---------|-----------|
| U1 | `get_json_object`/`to_json`/`from_json` 在 Spark 3.5.1 下是否逐字节保留 payload（空白/数字格式/键序） | 未跑 Spark（约束）；也**不应**用推理代替实测 |
| U2 | 真库 `dw_ods` 四表的实际 `DESCRIBE` 列清单 | 未连数据库（约束）。现有列集证据只有两份**文本所有者**（`00-ods.sql`、`LocalSchemaInitJob`） |
| U3 | golden-55 的「52 接受 / 3 拒绝」（`SparkStageExecutorSmokeTest.java:28`）与 `landing/manifests/30.json` 的 51/4 **为何不一致** | 未复跑采集/装载；属口径问题，需裁决（D-10） |
| U4 | `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar` 内容是否与当前源码一致 | 只看了 mtime（jar 18:19:03 / classes 18:03:57 / `OdsLoadSql.scala` 17:57:50，时间上自洽），**未做内容比对** |
| U5 | `source_registry` 当前实际行内容 | 未连数据库；只能引用 P1-06 证据里的「1 行 `mock-mall` ACTIVE 1.0」 |
| U6 | `input_file_name()` 在平台的 `spark-submit` 配置下是否稳定返回可用文件名 | 未实测 |
| U7 | 是否存在源 B（非 `mock-mall`）的**落地区夹具** | 全仓未见；A3 的正向对照夹具需**新建** |
| U8 | P2-04 切换 DWD 到 `payload_json` 后 DWD 的字节级等价性 | 属 P2-04，不在本草案 |
| U9 | `contract-specs` 的四个 DRAFT 制品中是否有隐含的 ODS 列要求 | 只读了 README/VERSION/schema/warehouse-namespace 四个文件，未逐读其余制品 |
| U10 | `spark-sql` 只读查询在「流水线未运行」时的可重复性 | 未执行（约束）；范式来自 `.verify/p1-06-hive-counts.ps1` |

### 5.8 不得声称清单（P2-01 完成后**仍然不能**声称的事）

1. **不得声称「ODS payload 原样保真已验收」** —— 除非 A2 在**真实行数据**上用**独立 oracle** 得到逐字节相等的 SHA-256，且语义等价负例变红。仅靠单元测试断言 SQL 文本**不构成**保真证据（现有 8 条 ODS 用例就是反例）。
2. **不得声称「额外字段无需 DDL 变更已被证明」** —— 除非 A3 的正向对照夹具跑通，且以**哈希对账**（§5.5(2)）证明 `warehouse/ddl/**` 未变。`git status` 为空**不等于**无改动（未跟踪/被忽略文件不会出现）。
3. **不得声称「DDL 未变」** —— 只说「与登记哈希逐字节相同」，并给出哈希值与时点。
4. **不得声称「schema 不匹配只生成重建计划已实现」** —— 除非真的走通了 `INIT_SCHEMA` 审计步骤并留下计划产物；计划书 L151 / 指导书 L666 的「不得静默 DROP」是**否定性**要求，没有发生 DROP **不等于**该路径已实现。
5. **不得声称 `raw_event_type` 的源词汇映射已验证** —— 见 R4 / D-6；源 A 下 `raw == canonical` 是**平凡情形**。
6. **不得声称「非 1.0 版本也被保真」** —— 见 A7 限制；采集层与 ODS 装载两层都把版本钉死为 `1.0`。
7. **不得声称 DWD/DIM/ADS 的保真或等价性** —— P2-01 边界内只保证「零改动 + 仍可编译/仍能跑」；DWD 的字节级等价属 P2-04。
8. **不得声称 F-14 / F-13 / F-15 已修** —— 三者与本任务同属 P2 但**不同车道**（F-14 单独立轮做破坏性验证）。
9. **不得声称 P2-01 完成后 T2 可重跑** —— 真数仓重建需备份 + 授权（R1/R2 + 指导书 L666）。
10. **不得声称仓库当前「列集只有一个所有者」** —— 现状是**两个**（§3.5）；P2-01 至多把重复收敛为「一个所有者 + 一个被门禁钉住的静态副本」，且该收敛是否彻底应由反熵专题裁决。

---

## 6. P1-06 前置证据（核实结论：**已满足**）

- 目录在盘：`docs/acceptance/p1-06-golden55-20260912/`（`README.md` 187 行 + `raw/` 原始文件）。
- README **L5**：前置按 **D-040** 完成。
- README **L55-59**（T2 表）：批 **#40**；run 40 FAILED `RUN_EMPTY_DATA`；**run 41 SUCCESS 8/8，09:26:27.592 → 09:31:13.403（4 min 46 s），快照 `S20260901_41` v9 ACTIVE**。
- README **L63**：ODS parquet `866 / 4,567,412` 逐字节不变。
- README **L74**：P6 未成立，但 `dt=20260901` ODS 行数 = 13+10+11+8 = **42**（首次真实测量，与 run 41 `QUALITY_CHECK businessDayEvents=42` 一致）。
- README **L75**：首次 Hive 物理计数 —— ODS 365/271/281/83（合计 1,000）、DWD 138/1/27、DIM 4/11、DWS 7 表、ADS 8 表 1/4/4/9/1/9/1/1（=30）；`source_registry` 1 行 `mock-mall` ACTIVE 1.0。
- README **L77 / L132-134**：F-14 风险已登记，**破坏性验证未做**。
- 决策记录 **D-043**（L1243-1251）：合并门通过、定级 `DONE`，F-13/F-14/F-15 三项另立。
- 看板 **L218**：`` `DONE` `` + 补记（F-27 对账，D-051 收敛）。

⇒ **前置成立；但「前置成立」只解除「依赖未完成」这一条，不解除 R1/R2 的重建授权问题。**

---

## 7. 需总控裁决的问题（实施车道不得自行拍定）

| ID | 问题 | 影响 | 本草案的临时默认 |
|----|------|------|-----------------|
| **D-1** | 看板 L220 出口证据列 `DDL/显式 schema/额外字段无 DDL 变更` 的**读法** | 决定验收断言的裁剪与「最小出口证据」的判定 | 按读法①（三件并列） |
| **D-2** | `event_time` 保持 `STRING` 还是改 `TIMESTAMP`（设计书 L171 vs 现状） | 改类型是**破坏性**变更（DWD `UNIX_TIMESTAMP` 依赖字符串），与「加法/无 DDL 变更」冲突 | 保持 `STRING` |
| **D-3** | v1 的 `payload_*` 列在 P2-01 是否**双写** | 决定 DWD 是否在 P2-01 期间零改动；决定「重复事实所有者」是否加剧 | 双写（P2-04 再切） |
| **D-4** | 业务键 `(source_system, event_id)` 的**定义所有者**归谁；DWD 现按 `event_id` 单键去重是否在本轮改 | 跨 P2-01/P2-04 边界 | P2-01 只定义列语义，不动 DWD |
| **D-5** | `source_system` 注入通道：`spark-submit --extra` 新参数 vs `runtime_profile.source_id` 反查 | 决定改动面是否含 `JobCommandBuilder`/`PipelineService` | 走 `extra` 通道（有 `hiveDatabasePrefix` 先例） |
| **D-6** | `raw_event_type` 的取值来源；源 B 的原始词汇在采集层已被丢弃，是否要改采集层（越界到泳道 A / P5） | 决定 R4 是「本轮解决」还是「明确留给 P5」 | 本轮只保证列存在 + 源 A 平凡值，**不声称映射** |
| **D-7** | `items` 的表示：契约（数组）vs 真实数据（字符串）；`OdsLoadSql.scala:205-212` 的 `itemsArrayType` 是留是删 | 影响 `payload_json` 落地后 DWD 投影能否照 `get_json_object` 走 | 保留现状（不删、不改），仅登记 |
| **D-8** | `landing_file` 与既有 `source_file` 的关系：改名 / 新增并存 / `source_file` 改为真实值 | 「加法」与「不删历史」张力 | 新增 `landing_file` 并**让 `source_file` 也填真实值**（否则留下一个语义错误的旧列） |
| **D-9** | 计划书 L148「为每个 source namespace 建 v2 表」是否要求本轮做 **per-source 表/库**，还是允许在现有单源库上**加法扩列** | 决定 R11 与改动面大小；涉及看板 L247 的「源级数仓前缀所有权」提醒（该提醒可能不属 P2-01） | 先**加法扩列**（单源 `mock-mall` 独占 `dw_ods`） |

---

## 8. 本草案实际读取的文件（路径 + 行范围）

**计划 / 设计 / 指导 / 看板 / 契约**

| 文件 | 读取范围 |
|------|---------|
| `docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md` | L22、L77-81、L147-151、L182（全 350 行已通读） |
| `docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md` | L40、L127、L148、L163-177、L179-183、L187-191、L200、L213-216、L226 |
| `docs/项目完整实施指导书 V2.3.md` | L666 及 grep 命中邻域 |
| `docs/项目实施进度与任务看板 V2.2.md` | L59、L206-225、L218、L220、L247、L373、L394（文件 396 行，sha256 `99831EEC…`） |
| `docs/开发过程事实与决策记录.md` | L1221-1250（F-13/F-14/F-15/D-043）、L1253-1262（F-16）、L1263-1278（D-044）、L1279-1282（F-17）、L833（D-031） |
| `contract-specs/VERSION` | 全部 1 行 |
| `contract-specs/README.md` | L3、L43、L45、L51、L136-144（144 行） |
| `contract-specs/schemas/canonical-event.v1.schema.json` | L5、L545-584、L764/L774/L778/L802/L812/L816/L839/L848/L853 + 反向 grep（867 行） |
| `contract-specs/specs/warehouse-namespace.v1.json` | 80 行（frozen 状态） |
| `docs/acceptance/p1-06-golden55-20260912/README.md` | L5、L55-59、L63、L74、L75、L77、L132-134（187 行） |
| `docs/acceptance/p1-baseline-r39-20260911/baseline.json` | L1265（`sparkJobJarUri`） |
| `docs/acceptance/p1-baseline-r39-20260911/raw/api-runtime-profile-active.json` | 全部 1 行 |
| `docs/acceptance/p1-04-namespace-20260911/README.md` | L23、L41、L70、L79、L87 |

**DDL / 主代码**

| 文件 | 读取范围 |
|------|---------|
| `warehouse/ddl/00-ods.sql` | L11-30、L33-57、L60-79、L82-106（106 行；sha256 `36EDBF79…`，4517 B） |
| `spark-jobs/src/main/scala/.../sql/OdsLoadSql.scala` | L17-20、L26-75、L76-89、L92-189、L180、L192-199、L203-212（215 行） |
| `spark-jobs/src/main/scala/.../job/EventOdsLoadJob.scala` | L37、L41-49、L52-74、L79、L87-89（90 行） |
| `spark-jobs/src/main/scala/.../job/LocalSchemaInitJob.scala` | L24、L36-82、L84-247、L249-261、L275-299（304 行） |
| `spark-jobs/src/main/scala/.../sql/IdCodec.scala` | L17、L26、L37-38、L44-48（49 行） |
| `spark-jobs/src/main/scala/.../sql/DwdSql.scala` | L13-42、L45-56（57 行，全读） |
| `spark-jobs/src/main/scala/.../warehouse/WarehouseNamespace.scala` | L88、L126-127（128 行） |
| `spark-jobs/src/main/scala/.../job/JobArgs.scala` | 全部 52 行 |
| `analytics-server/warehouse-pipeline/.../spark/JobCommandBuilder.java` | L59、L97、L104-111（133 行） |
| `analytics-server/warehouse-pipeline/.../PipelineService.java` | L417、L430-432、L889-940 |
| `analytics-server/connection-ingestion/.../ingestion/EventContractValidator.java` | 全部 146 行 |
| `analytics-server/platform-common/.../contracts/EventContract.java` | L16-17 |
| `analytics-server/source-profiles/p1-03-probe-1.v1.json` | 全部 32 行 |
| `analytics-server/source-profiles/README.md` | 全部 26 行 |

**测试**

| 文件 | 读取范围 |
|------|---------|
| `spark-jobs/src/test/scala/.../SqlTemplateSpec.scala` | L19-70（245 行 / 22 用例） |
| `spark-jobs/src/test/scala/.../IdCodecSpec.scala` | L52-79、L105-109（110 行 / 7 用例） |
| `analytics-server/platform-common/src/test/java/.../contracts/CanonicalEventSchemaParityTest.java` | 全部 164 行（5 `@Test`） |
| `analytics-server/warehouse-pipeline/src/test/java/.../spark/SparkStageExecutorSmokeTest.java` | L28、L65、L89、L111、L170-183（184 行 / 1 `@Test`） |
| `analytics-server/platform-common/src/test/java/.../warehouse/WarehouseNameLiteralGateTest.java` | L40-42、L45、L47、L59（216 行 / 3 `@Test`） |
| `analytics-server/warehouse-pipeline/src/test/java/.../spark/JobCommandBuilderTest.java` | L125-134（155 行 / 9 `@Test`） |
| `analytics-server/warehouse-pipeline/src/test/java/.../contracts/EventContractTest.java` | 用例数（157 行 / 4 `@Test`） |

**夹具 / 环境（只读）**

| 对象 | 读取方式 |
|------|---------|
| `tests/golden-dataset/events/golden-20260901.jsonl` | 55 行；逐行 payload 顶层键计数（原始输出见 §3.3）；第 53 行不可解析 |
| `landing/events/r9-m1-123006.jsonl` | 55 行 / 1 行不可解析；键普查与 golden 逐项相同 |
| `landing/manifests/30.json` | `acceptedRecords=51` / `quarantinedRecords=4` |
| `warehouse/ddl/*.sql` | 5 文件 SHA-256 + 字节数（§5.5(2) 基准表） |
| `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar` | 234,038 B / mtime 2026-09-11 18:19:03 |
| `spark-jobs/target/classes/**`（ODS 相关） | mtime 2026-09-11 18:03:57 |
| `spark-warehouse/` | 6 个库目录：`dw_ods.db`、`dw_dwd.db`、`dw_dim.db`、`dw_dws.db`、`dw_ads.db`、`probe_r613.db` |
| `.verify/p1-06-hive-counts.ps1` | 全部 56 行（只读取数范式） |
| `.gitignore` | L61 `.verify/` |
| `tests/` | 顶层目录：`ai-questions`、`golden-dataset`、`r6-smoke-warehouse`（无 Java/Scala 测试文件） |

**未读取**：`contract-specs` 的其余 DRAFT 制品（只读 README 列出的四者中两个）、`docs/contracts/event-contract.md` 全文、真库 `analytics_meta` 任何表、`landing/accepted/**` 内容（只引 manifest 计数）。

---

## 9. 一页速览（给总控）

- **现状一句话**：ODS 12 个公共列**缺 4 个**（`raw_event_type` / `landing_file` / `payload_json` / `payload_hash`）；payload **被解析成 31 个标量后重写**，原始对象字符串**无处存**；闭合 schema **静默丢弃契约要求的** `available_qty`(9 行) / `quantity`(9 行) / `reserved_qty`(8 行)；`source_system` **信任外部文件**；`source_file` 是**常量 `'landing'`** 且被测试断言为正确；**列集有两个所有者**（`00-ods.sql` 与 `LocalSchemaInitJob`）。
- **最关键的 3 条断言**：**A2**（`payload_json` 与独立 oracle 的 SHA-256 逐字节相等 + 语义等价负例必红）、**A3**（额外字段正向对照：改动前必红 / 改动后可见 / DDL 哈希不变）、**A4**（契约内字段 `available_qty`/`quantity`/`reserved_qty` 回归，计数必须等于 9/9/8）。
- **最大风险**：**R1** —— 运行中的 8091（profile id=1）的 `sparkJobJarUri` 就指向 `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar`，**重建这个 jar 会直接改变平台下一次真实运行的行为**；**R2** —— F-14（空分区规格 `INSERT OVERWRITE` = 整表替换）只做了代码级取证，ODS v2 重建必须先备份 `spark-warehouse`。
- **最硬的两个阻塞**：`raw_event_type` 对源 B **原理上不可得**（采集层隔离未知类型）；`mock-mall.v1.json` **不存在**（P3-01 交付物）⇒ P2-01 的实现与断言都**不得**依赖画像文件。
