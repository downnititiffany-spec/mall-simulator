# CT 批次施工单（CT-1 / CT-2 / CT-3 ＋ 一次性升版）

- 日期：2026-09-12　编制：总控　状态：**已登记，未开工**（等 P2-01 车道交付并验收）
- 裁决依据：同目录 `RULINGS.md`（D-061…D-064）；批次定义见 `docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md` §8
- 纪律：本批**只做**契约文本/形状、平台侧一处常量退休、两处测试同步；**不含** E3/E4/E5 证据，**不含**数据面改动

---

## 1. 开工门禁（三条全满足才可开工）

| # | 门禁 | 判据 |
|---|---|---|
| G1 | P2-01 车道交付并**总控验收** | 该车道 README/证据入库、复核完成、状态非 `IN_PROGRESS` |
| G2 | jar 写入窗口关闭 | 近 30 分钟内无 `spark-jobs/target/*.jar` 写入；`spark_job_jar_uri` 指向的 jar 指纹已登记 |
| G3 | 无并发 Maven 写者 | 同一模块同时只有一个 Maven 进程（本批只跑 `analytics-server`，**不得**与任何 `spark-jobs` Maven 并发） |

**批次内顺序**：契约文档 → schema → 代码（常量退休＋测试）→ 升版与指纹重登记 → E1 → 提交推送 → 看板/日志。**任一步失败即停**，不越过失败点继续写（避免半批落地）。

## 2. 逐点改动清单（机械可执行；每点都必须"命中且仅命中 1 次"）

> 断言口径：先以**计数**断言（`[regex]::Matches(全文, 旧串).Count -eq 1`）再替换；替换后以**新串计数 = 1 ∧ 旧串计数 = 0** 复核。**凡"零命中"判据必须带正向对照**（F-38 ①）。

### 2.1 CT-1

| # | 文件 | 定位锚（旧串，逐字） | 期望新串 |
|---|---|---|---|
| 1 | `docs/contracts/event-contract.md` | `// 固定值：mock-mall` | `// 取值 = 本事件所属源的 source_code（注册表唯一拥有，D-056）；mock-mall 为首个源的取值，非契约固定值` |
| 2 | `contract-specs/schemas/canonical-event.v1.schema.json` | `"const": "mock-mall",` | `"type": "string",` ＋ `"minLength": 1,` |
| 3 | 同上（description 内） | `"固定值：mock-mall。来源：event-contract.md §1 L16『固定值：mock-mall』；与 EventContract.SOURCE_SYSTEM（EventContract.java:17）及 mall-simulator 侧 ` | 改为"形状约束；取值 = 该源 source_registry.source_code（D-056）；值域非契约所有（D-061）；`mock-mall` 为首个源取值" |
| 4 | `analytics-server/platform-common/src/main/java/com/graduation/analytics/contracts/EventContract.java` | `public static final String SOURCE_SYSTEM = "mock-mall";`（含其 javadoc 行，若引用它） | **整行删除**（退休；读者只有对账测试） |
| 5 | `analytics-server/platform-common/src/test/java/com/graduation/analytics/contracts/CanonicalEventSchemaParityTest.java` | `:65-73` 的 `@DisplayName("schema_version / source_system 常量与 EventContract 一致")` ＋ `assertEquals(EventContract.SOURCE_SYSTEM, root.path("properties").path("source_system").path("const").asText(), "source_system 必须锁定为 EventContract.SOURCE_SYSTEM");` | 改为**结构化守卫**：断言 `source_system` 节点**不含** `const` 字段；**正向对照**：同测试断言 `schema_version` 节点**含** `const`（证明守卫非空转） |
| 6 | `analytics-server/platform-app/src/test/java/com/graduation/analytics/source/SourceRegistryMigrationScriptTest.java` | `种子源编码必须等于冻结契约里的 source_system 取值 mock-mall` | `种子源编码必须等于首个源的 source_code（契约不再固定该值，D-061）`（**断言本身不变**） |

### 2.2 CT-2（纯文字；不动代码）

| # | 文件 | 定位锚（旧串，逐字） | 期望新串 |
|---|---|---|---|
| 1 | `docs/contracts/event-contract.md:12` | `// 全局唯一，ODS/DWD 按此去重（允许 at-least-once 投递）` | `// 源命名空间内唯一（同 source_instance_id）；ODS/DWD 按 (source_instance_id, event_id) 去重（允许 at-least-once 投递）` |
| 2 | `docs/contracts/event-contract.md:165` | `` `event_id` 唯一；重复投递由 DWD 按 `event_id` 去重 `` | `` `event_id` 在源命名空间内唯一；重复投递由 DWD 按 `(source_instance_id, event_id)` 去重 `` |
| 3 | `contract-specs/schemas/canonical-event.v1.schema.json:10` | `全局唯一事件 ID，ODS/DWD 按此去重（允许 at-least-once 投递）。` | `源命名空间内唯一的事件 ID，ODS/DWD 按 (source_instance_id, event_id) 去重（允许 at-least-once 投递）。` |

> **与 CT-1 同文件同行提示**：`event-contract.md:12` 与 `:16` 是**不同行**，但都在 §1 的 JSON 示例块内 ⇒ 本批这两处**一次改完**，避免两次触碰同一代码块。

### 2.3 CT-3

| # | 文件 | 定位锚 | 期望改法 |
|---|---|---|---|
| 1 | `canonical-event.v1.schema.json:548-549` | `"items": {` / `"type": "array",`（**注意**：文件中另有 `"items"` 作为 JSON-Schema 关键字出现在多处 ⇒ **不得**用裸 `"items"` 计数，须用带上下文的锚：`§2.4 L77；子表字段见 §2.4 L84-L90`） | 改为 `oneOf`: 分支甲 = 现有数组定义（**原文不动**）；分支乙 = `{"type":"string","description":"承载 JSON 编码数组的字符串（外部源兼容形态，D-063）…"}` |
| 2 | 同上 `:538`（`order_created` 描述） | `真实数据冲突（全部 6 行 order_created 均如此，L18/L20/L22/L39/L43/L47，且都被采集层接受）：(1) items 被写成 JSON 字符串` | 在该条**之后**追加处置句：`处置（D-063）：契约加性接受字符串形态；规范形态仍为数组；解析归一属 DWD（P2-04/P2-05）`——**原文保留**（历史陈述不改写） |

### 2.4 升版与指纹重登记

| # | 对象 | 动作 |
|---|---|---|
| 1 | `contract-specs/VERSION` | `contract-specs 1.3.0` → `contract-specs 1.4.0`（21 B → 21 B，天然 LF，写后 `CR=0`） |
| 2 | `contract-specs/README.md` | §1 状态行版本串、§3 目录表 `VERSION` 行同步 `1.4.0`；§10 指纹表**新增一行**登记 `canonical-event.v1` 与 `VERSION` 的**新**sha256（历史行原文保留）；文末新增 §13 批次补记（逐项命中数、写前/写后指纹、未改面） |
| 3 | 其余三个 DRAFT 制品 | **不动**（若实测有变动 ⇒ 立即停并报告） |

### 2.5 锚点规则与守卫（D-065；随本批一次性升版）

| # | 对象 | 动作 |
|---|---|---|
| 1 | `contract-specs/README.md` | 新增"**锚点必须带文件名/版本前缀**"规则行（与 §3 升版规则同处），并登记 258 处裸锚点为**在册负债**（指向台账文件） |
| 2 | `scripts/check-bare-anchors.ps1`（新建） | 扫描 `contract-specs/**` 的裸锚点，判据 = 集合 ⊆ 台账；**带正向对照**（一条带文件名的锚点必须**不**计入） |
| 3 | `scripts/contract-bare-anchors.allowlist.txt`（新建） | 258 行台账（文件｜锚点文本｜出现次数），**只减不增** |
| 4 | `canonical-event.v1.schema.json:538` | 与 CT-3 同段改动**顺手**补文件名（`§2.4 L77` → `docs/contracts/event-contract.md §2.4 L77`，行号**不改**） |
| 5 | 其余 48 处 / 210 处 | **本批不动**（专项 M1-5-R；时点 = P3-04 同批或之前） |

> **本批不得声称**：锚点"指对了地方"（只补文件名、未核语义）；非 markdown 载体的同款裸锚点已普查。

## 3. 出口证据（本批的证据面，全部可复现）

| 级别 | 命令/动作 | 通过判据 |
|---|---|---|
| E1-a | `mvn -o -f analytics-server/pom.xml -pl platform-common,platform-app -am test '-DforkCount=0'`（`JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'`） | `Tests run: N, Failures: 0, Errors: 0` ＋ `BUILD SUCCESS`；**基线 N 必须先测**（改动前跑一次），改动后不得低于基线 |
| E1-b | Python：`Draft202012Validator.check_schema()` 载入改后 schema | 无异常（schema 文档本身合法） |
| E1-c | **真实数据对照（本批关键，反假绿灯）**：以改前/改后 schema 分别校验 `tests/golden-dataset/events/golden-20260901.jsonl` 55 行，输出**失败行号集合的差集** | 改后 = 改前 **减去** `items` 相关失败行；**其余失败行必须逐行不变**（Q4/Q6 类冲突仍在 ⇒ 若"改后 0 失败"反而不合格，说明判据被写宽了） |
| E1-d | 变异验证（守卫非空转） | 临时把 `"const"` 塞回 `source_system` ⇒ 对账测试**必须失败**；恢复后必须通过（临时改动不入库） |
| 契约面 | 逐制品 `Get-FileHash -Algorithm SHA256` | `warehouse-namespace.v1` 保持 `463D9DC3…` **不变**；`canonical-event.v1`/`VERSION` 新值与 §2.4 登记值逐字符相符 |

**明确不产生**：E2 之外无模块级自动化；**无** E3/E4/E5；**无** T2 结论（T2 重跑 = M1-11，排在本批之后，用改后的契约与 jar **重新**跑 55 条黄金链）。

## 4. 开工首测项（施工前必须补齐的实测，防"未实测就动手"）

1. 生成器/参考商城**当前产出**的 `items` 形态（读 `CanonicalEventFactory` 与参考商城 Outbox 写出路径）——决定 CT-3 的"规范形态"是否与自产源一致。
2. `EventContractValidator.missingPayloadField` 是否覆盖 `items`（决定字符串形态在采集层的现状）。
3. `landing/events/r9-m1-123006.jsonl` 的**行数与逐行内容**（CT-4 前提；本批只做登记，不做裁决）。
4. 全仓其余"固定值：mock-mall"/"只按 event_id 去重"表述扫描（本轮只扫了 `contract-specs` 与 `docs/contracts`）。
5. `EventContract.java` 中 `SOURCE_SYSTEM` 的**完整**引用面（含 `javadoc`/注释/资源文件，不限于 `.java`）。

## 5. 本轮已完成的口径与命令（供复核复现）

- 契约内 `mock-mall` 分布：`README.md` 7、`canonical-event.v1` 3、`ingestion-manifest.v1` 1。
- `mock-mall` 在 main 侧非测试 Java/Scala（326 文件）：命中 12，其中**代码常量 3**（D-064 表）；test 侧 121 文件命中 49。
- 黄金数据集 55 行 `items` 形态：数组 0 / 字符串 6 / 无 49。
- `EventContractValidator` 信封校验：仅非空（`EventContractValidator.java:56-61`），无值比较。
- 平台侧运行期 JSON-Schema 校验器：0 命中（`JsonSchema|SchemaValidator|json-schema|networknt|everit`）。
- schema 中"真实数据冲突"登记 8 处（行号见 `RULINGS.md` §5）。

## 6. 禁改面（本批的红线）

- 不改 `spark-jobs/**`（P2-01 车道面；CT-2 的 DWD 去重键实现属 P2-04）。
- 不改 `tests/golden-dataset/**` 与任何**证据数据**（6 行字符串 `items` 保持原样）。
- 不改 `mall-simulator/**`（D-064 ② 单独立项）。
- 不改生成器 `ContractFormat.SOURCE_SYSTEM`（B-06 未决，D-064 ③）。
- 不引入新的 JSON-Schema `pattern`/`enum` 作为源编码值域所有者（D-061 理由）。
- 不改 `warehouse-namespace.v1.json`（已冻结，指纹必须保持 `463D9DC3…`）。

## 7. 回滚

- 所有改动**一次提交**，回滚 = `git revert` 该提交（契约与代码同批，不留半批状态）。
- 契约文件写前各留离仓冷备份：`D:\Develop_code\graduation-lane-backup\ct-batch\*.before`（含 sha256 清单）。

## 8. 勘误（2026-09-14 补记；上文不改写，以本节为准）

- 上文 §7 L112 记录的"离仓冷备份 `D:\Develop_code\graduation-lane-backup\ct-batch\*.before`"是**当时的实际做法**，不是笔误；
  但**2026-09-14 用户硬约束**规定：**产物一律不得在仓库之外创建**（允许的落点只有仓库内目录与 `%TEMP%`）⇒
  该仓外目录**就此冻结、不得新增任何文件**，仅作为历史证据保留。
- 后续批次的"写前备份"改为落在**仓库内** `backups/`（`.gitignore` 已忽略）或 `%TEMP%`；
  隔离性由 gitignore/命名空间/端口/runId 提供，**不再靠把文件搬出仓库**。
- 该仓外目录**未删除、未迁移**（按用户裁决：冻结新增 ＋ 分类归并，删除须逐项批准）。

### 8.1 第二条勘误（2026-09-14 15:0x 补记；上文与 §8 均不改写，以本节为准）

- 用户已**逐项批准**处置：`D:\Develop_code\graduation-lane-backup` **已迁入仓库** ⇒ **冷备份新位置＝
  `docs/acceptance/graduation-lane-backup-20260912/`**（182 文件 / 3.04 MB，迁入后逐字节校验 `diff=0`），
  **仓外原件已删除**。§7 L112 所写的离仓路径**不再是证据位置**，查阅请改按新路径。
- 因此 §8 中「仅冻结、未迁移」的表述**已被本节取代**（§8 写就时该目录确实未迁移，属当时事实，不改写）。
- **规则（永久）**：隔离靠 **gitignore／命名空间／端口／runId**，**不靠把文件搬出仓库**；
  后续任何「写前备份」一律落 **仓内** `backups/`（gitignored）或 `docs/acceptance/<通道>-<日期>/`（需被引用时）。
- 依据与过程证据：`docs/acceptance/v25-stray-consolidation-20260914/EXECUTION-20260914.md`
  （批准范围／删除前复验／逐字节校验／命令／结果／未取证）。
