# P2-02 裁决书（第二批：D-112 … D-119）（2026-09-12 20:4x）

- 裁决人：总控（父会话）　适用任务：**P2-02 多格式时间**（看板 221 行）
- 权威顺序：①冻结契约 ②指导书 V2.4 ③看板 V2.2 ④专项设计/实施书 ⑤V2.1 及更早
- 第一批（**D-111 / D-120**）见 `RULINGS-P2-02-20260912.md`；号段分配见 `RULINGS-NUMBERING-20260912.md`
- 本批全部结论均基于**父侧自己读到的原文**，来源逐条标注；未取证的项写「未实测」，不写结论

---

## Aegis Visibility（本条裁决的可视范围）

| 事实 | 实测值 | 出处 |
| --- | --- | --- |
| 画像 §4.2 的 9 个顶层键（逐字） | `profileVersion` / `sourceCode` / `canonical` / `eventTypeMapping` / `fieldMapping` / `enumSemantics` / `identityPolicy` / `timePolicy` / `quarantinePolicy` | `docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md` §4.2（父侧读取） |
| `timePolicy` 的既有权形状 | `{ "field": "created_at", "formats": ["ISO_OFFSET_DATE_TIME", "EPOCH_MILLIS", "yyyy-MM-dd HH:mm:ss"] }` | 同上 ＋ 夹具 `analytics-server/source-profiles/p1-03-probe-1.v1.json`（两处逐字一致） |
| 拒绝通道真实形态 | `CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dwd.dwd_reject_record (reject_id, source_table, **reject_reason**, raw_payload, reject_time) PARTITIONED BY (dt)` | `warehouse/ddl/01-dwd.sql:71-81`（父侧读取） |
| 已声明的 `reject_reason` 枚举 | `EMPTY_FIELD/DUPLICATE_EVENT/BAD_ENUM/BAD_AMOUNT/FUTURE_TIME` | `warehouse/ddl/01-dwd.sql:74` 注释（**全仓唯一 1 处 `FUTURE_TIME`**） |
| 是否存在 `reason_code` 列 | **不存在** —— `reason_code` 在 `warehouse/**`＋`spark-jobs/src/**`＋`analytics-server/**` 全量 **0 命中** | 阳性对照：同范围 `mock-mall` = **94 命中** ⇒ 检索有效 |
| 死 SQL 的引用面 | `BAD_VERSION_OR_KEY` **2 命中**：生产 `spark-jobs/.../sql/OdsLoadSql.scala:272` ＋ **测试** `spark-jobs/src/test/scala/com/graduation/analytics/SqlTemplateSpec.scala:84` | 父侧检索（含阳性对照） |
| 静默丢弃点 | `OdsLoadSql.scala:181` 的 `WHERE schema_version = '1.0' AND $whereClause AND event_id IS NOT NULL AND event_time IS NOT NULL` | 父侧读取（行号以当前工作树为准） |

---

## D-112 —— `timePolicy.formats` 的语法与顺序（**冻结：沿用既有形状，不新增第三类语法**）

1. `formats` 是**有序字符串数组**；顺序即**优先级**（自上而下取**首个解析成功**者），实现**不得**自行重排、去重或改变匹配顺序。
2. 元素只允许两类：**(a)** 命名常量（既有实例：`ISO_OFFSET_DATE_TIME`、`EPOCH_MILLIS`）；**(b)** Java `DateTimeFormatter` 模式串（既有实例：`yyyy-MM-dd HH:mm:ss`）。**禁止**新增第三类语法（正则、`strptime` 式、布尔表达式等一律不许）。
3. 未知常量／非法模式 ⇒ **画像校验失败**（对外仍是 `409 SOURCE_PROFILE_INVALID` 语义），**禁止**静默忽略该元素后继续用其余元素 —— 静默忽略等于"配置写了但没生效"，属禁止的假取证形态。
4. `formats` **为空或缺失** ⇒ 画像校验失败（不是"全都能解析"）。
5. **证据纪律**：真源画像（P3-01-a 的 `mock-mall.v1.json`）的 `formats` **只能列出真实观测到的形态** —— 现有唯一实测形态是 `yyyy-MM-ddTHH:mm:ss+08:00`（2,622,616 个值 **100%**，其他形态 **0**）⇒ 真源画像中应**只列 `ISO_OFFSET_DATE_TIME`**；**不得**把"将来可能支持"的形态预先写进真源画像。多格式**能力**由算法与测试保证，**不等于**真源**声明**了多格式（此区分必须写进实施报告）。

## D-113 —— 分区列 `dt` / `hour` 的推导（**唯一算法 + 等价性验收**）

1. `dt` / `hour` 一律由 **D-111 的新派生列 `event_time_utc`（规范 UTC）** 换算到**业务时区**（`source_registry.timezone`）后取日期/小时；**禁止**继续用 `SUBSTR(event_time, 1, 10)` / `SUBSTR(event_time, 12, 2)` 这种**位置切片**（它把"源串恰好长得像 ISO"当成了协议）。
2. 原列 `event_time` 保持 `STRING`、**逐字节不变**（D-111 第 1 条），不作为换算依据。
3. **不重写历史分区**：现有 landing/ODS 数据形态单一（`+08:00`，100%），因此**禁止**为换算做 any 回填/重刷。
4. **等价性验收（可执行、必须做）**：对全量既有值（实测 **2,622,616** 个 `event_time`）验证「新表达式结果 == 旧 `SUBSTR` 结果」**逐值相等**，并把**不等数必须为 0** 的原始读数落盘。这是本行最硬的验收口径：一次查询即可同时证伪"换算写错"与"旧数据形态不单一"。

## D-114 —— `OdsLoadSql.scala:181` 的静默丢弃（**必须停止静默，但不得新建表**）

1. 当前 `WHERE … AND event_id IS NOT NULL AND event_time IS NOT NULL` 会把不合格行**静默丢进虚空**（无计数、无记录）⇒ **必须改**：不合格行一律写入**既有** `dwd_reject_record`（列见上表），`reject_reason` 取 D-116 的冻结码，`raw_payload` 存原始行，`dt` 取**业务时区日**（与 D-113 同源）。
2. 同理，`schema_version = '1.0'` 这个过滤条件**也是静默丢弃**：非 `1.0` 的行必须**计数**并按 D-116 的 `UNSUPPORTED_SCHEMA_VERSION` 落拒绝记录（"本契约束版本之外"是**可解释的拒绝**，不是"不存在"）。
3. **禁止**新建拒绝表、**禁止**新建 `reason_code` 列（实测该列不存在，见上表）；一律复用既有 `reject_reason`。
4. **禁止**把 Reject 记录写成"顺带一行日志"就当作取证：验收要求给出**拒绝写入的行数读数**（含阳性对照：故意注入 1 条空 `event_id` 行 ⇒ 拒绝数 +1）。

## D-115 —— 死 SQL `BAD_VERSION_OR_KEY`：**连测试一起删（delete-first）**

1. **退役判定**：`OdsLoadSql.scala:272` 处的该分支**无生产调用者**（第一批已实测），属"声明了但没接线的第二套语义"⇒ 保留它就是**第二个 owner**。
2. **动作**：删除该 SQL 分支 **＋** 同步删除/改写唯一测试引用 `SqlTemplateSpec.scala:84`；**禁止**留"兼容分支"或注释掉的代码。
3. **验证计划**：`BAD_VERSION_OR_KEY` 全仓命中数 **2 ⇒ 0**；阳性对照必须同时跑（`mock-mall` 命中数应保持 **94** ⇒ 证明检索本身没坏）。
4. 该删除**属于 P2-02 实施范围**（同文件、同语义域），不另开任务、不另开号。

## D-116 —— 原因码集合（**冻结：封闭、加性、注释即注册表**）

1. 载体：**既有文本列** —— DWD 侧 `dwd_reject_record.reject_reason`、采集侧 `quarantine_record.reason`（自由文本，**不改列**）。
2. 冻结集合（**现有 5 个不得改名/删除**，只允许追加）：
   - 既有：`EMPTY_FIELD`、`DUPLICATE_EVENT`、`BAD_ENUM`、`BAD_AMOUNT`、`FUTURE_TIME`
   - 本行**新增**（P2-02 需要）：`BAD_TIME_FORMAT`（所有格式均解析失败）、`UNSUPPORTED_SCHEMA_VERSION`（`schema_version ≠ 1.0`）
3. **新增码必须同时登记进 `warehouse/ddl/01-dwd.sql:74` 的注释枚举**（该注释是**唯一注册表**）：新增码的变更与使用它的代码**必须同一个提交**，禁止"代码先写、注册表后补"。
4. **禁止**自由拼装原因文本（如把变量插进字符串当原因）；未能归类的行用 `EMPTY_FIELD` 之外必须新增码，**不得**塞进语义不符的既有码。
5. 「缺时区」**不是**拒绝码：按 D-111 第 5 条，缺时区 ⇒ 显式回退 `Asia/Shanghai` ＋ WARN ＋ 审计留痕，**不拒绝数据**。

## D-117 —— `FUTURE_TIME` 的归属（**不在 P2-02，Aegis 要求显式挂账**）

1. 实测：`FUTURE_TIME` **只在** `warehouse/ddl/01-dwd.sql:74` 的注释里出现（全仓 1 处），**没有任何生产写入点** ⇒ 属"声明未实现"。
2. 裁定：该规则的阈值是 **DQ 规则参数**，**不属 P2-02**（时间格式/时区分辨率），**归属 P2-06**（看板 225 行 `TODO`）。
3. 本行**不动**该注释、**不实现**该规则；本行报告与看板**禁止**声称 `reject_reason` 枚举"已实现"（只有 5 个码里的前 4 个可能有写入点，`FUTURE_TIME` 仍为声明态）。

## D-118 —— DQ 规则表（`data_quality_result`）：**本行零改动**

1. 实测（第一批）：`data_quality_result` 445 行、有 `rule_code`、**0 条时间规则**。
2. 裁定：P2-02 **不得**新增/修改 `data_quality_result` 的规则、字段或历史行；时间类 DQ 规则整体归 **P2-06**。
3. 因此本行**不触碰**任何数据库 DDL/DML ⇒ 亦**无需**额外的 DDL 授权（这条解除了"DQ 规则需单独放行"这一前置对 P2-02 的阻塞）。
4. P2-02 的验收证据一律落在**文件**层面（SQL/测试/日志）与**拒绝记录**层面（D-114），不落在 DQ 表。

## D-119 —— 源时区的传递通道（**单 owner，不得新造并行通道**）

1. 源时区的**唯一 owner** ＝ `source_registry.timezone`（D-111 第 4 条；实测该列值 `Asia/Shanghai`，`runtime_profile.timezone` 是**副本**，实现**禁止**读它作为依据 —— `RuntimeProfileServiceImpl.java:72` 的硬编码 `Asia/Shanghai` 属待删项）。
2. 传递方式：经**与 `source_system` 完全相同的那一条既有通道**进入 `spark-jobs`（该通道的**逐字实现细节**由实施工作单核实后**引用原文**，**不得**凭印象新造 flag 名、也不得另开 `--extra` 之外的平行参数通道）。
3. 空值/缺失 ⇒ 显式回退 `Asia/Shanghai` ＋ WARN ＋ 审计留痕（D-111 第 5 条）；**禁止**静默回退、**禁止**在 spark-jobs 内散落第二份时区常量（现存 `SparkSessionFactory.scala:14`、`SparkStageExecutorFactory.java:93` 会话级时区**不属**本 owner，保持原样，不得拿它们当业务时区来源）。

---

## Anti-Entropy Declaration（本次裁决涉及的退役/去重）

| 项 | 重复 owner / 退役对象 | 处置 |
| --- | --- | --- |
| 时间解析 | 位置切片 `SUBSTR(...)` 与"按格式解析"两套语义并存 | **退役切片**（D-113），解析唯一化到 D-111 的派生列 |
| 版本语义 | `BAD_VERSION_OR_KEY` 死分支 vs 生产使用的拒绝语义 | **删除死分支＋其测试引用**（D-115） |
| 时区来源 | `source_registry.timezone`（owner） vs `runtime_profile.timezone`＋两处会话常量 | **保留 owner，副本降级为只读**（D-111/D-119） |
| 原因码 | DQ `rule_code` vs 拒绝文本 `reject_reason` vs 采集 `quarantine_record.reason` | **不合并、不新建列**；本行只用后两者并冻结码集（D-116/D-118） |

**Retirement Decision**：`delete-first`（内部代码/死 SQL：直接删，不留兼容分支）；**无**持久化状态删除 ⇒ 本轮**不需要** `confirmation-first` 级确认。
**Verification Plan**：① `BAD_VERSION_OR_KEY` 2 ⇒ 0；② `reason_code` 保持 0（不得新增该列）；③ 分区等价性不等数 = 0（D-113 第 4 条）；④ 拒绝写入计数含阳性对照（D-114 第 4 条）；⑤ 每步附原始日志与 `Tests run:` / `BUILD SUCCESS|FAILURE` 原文。

## Gap Closure（本批未关闭的缺口，显式挂账）

1. `FUTURE_TIME` 实现 —— 归 **P2-06**（D-117），**未关闭**。
2. 时间类 DQ 规则 —— 归 **P2-06**（D-118），**未关闭**。
3. 跨源/异种源时间格式 —— 真库仅 **1 个源**（`source_registry` 1 行），**当前不可取证**；仅可由构造的双源夹具在本机真链取证（集群读数一律不计）。
4. 集群侧形态一致性 —— **未实测**（M3 集群档 BLOCKED）。
5. P2-02 的依赖（**D-120**：`P2-01` ＋ `P3-01` 画像格式列表）中，P3-01-a 画像文件**尚未交付** ⇒ P2-02 **仍未开工**，本批裁决**不等于**授权开工。
