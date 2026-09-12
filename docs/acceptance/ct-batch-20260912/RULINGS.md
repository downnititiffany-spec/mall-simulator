# CT 批次裁决（CT-1 / CT-2 / CT-3 / CT-4 前置）— D-061…D-064

- 日期：2026-09-12　裁决人：总控
- 依据顺序：① 冻结契约（`warehouse-namespace.v1` FROZEN-2026-09-11；其余四制品 `DRAFT`）② 指导书 V2.2/V2.3 ③ 看板 V2.2 ④ `PLAN.md`（同目录）⑤ D-052…D-060（P2-01 目录 `RULINGS.md`）
- 本批次的**批次定义**来自 `docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md` §8：CT-1/CT-2/CT-3 与任何字段级改动**成批落地**，并**一次性**把 `contract-specs/VERSION` 由 `1.3.0` 升 `1.4.0`。
- 证据口径：以下每条裁决的"事实"栏均**实测**取得（命令与输出见 `PLAN.md` §5）；未实测者逐条标 **未取证**，不得据其下结论。
- **时点门禁（硬）**：CT 批次**不得**在 P2-01 车道（`spark-jobs/**`＋`analytics-server/**`）仍在飞时开工——CT-1 需改平台侧 `platform-common`，与本批次的 E2 会撞车。落地前置 = P2-01 交付并验收 + 其 jar 写入窗口关闭（M1-11 T2 55 条黄金链重跑**排在** CT 批次之后，避免同一 jar 被两次替换）。

---

## 1. D-061（CT-1）：`source_system` 由「锁定常量」改「形状约束 + 注册表取值」

**事实（实测）**

| # | 位置 | 逐字原文（节录） |
|---|---|---|
| 1 | `docs/contracts/event-contract.md:16`（语义权威） | `"source_system": "mock-mall",              // 固定值：mock-mall` |
| 2 | `contract-specs/schemas/canonical-event.v1.schema.json:38-41` | `"source_system": {` / `"const": "mock-mall",` / `"description": "固定值：mock-mall。来源：event-contract.md §1 L16『固定值：mock-mall』；与 EventContract.SOURCE_SYSTEM（EventContract.java:17）及 mall-simulator 侧 …` |
| 3 | `analytics-server/platform-common/src/main/java/com/graduation/analytics/contracts/EventContract.java:17` | `public static final String SOURCE_SYSTEM = "mock-mall";` |
| 4 | `synthetic-data-generator/src/main/java/com/graduation/generator/contract/ContractFormat.java:38-43` | javadoc「**未冻结项（B-06）**…若裁决要求文件模式使用独立 source_system，则契约须把 const 放宽为 enum，**本常量是唯一改动点**」＋ `public static final String SOURCE_SYSTEM = "mock-mall";` |
| 5 | `mall-simulator/src/main/java/com/graduation/mall/outbox/EventContract.java:10` | `public static final String SOURCE_SYSTEM = "mock-mall";` |
| 6 | `analytics-server/connection-ingestion/.../EventContractValidator.java:56-61` | 信封必填校验**只查非空**（`isBlank(text(node, required))`），**从不与常量比较** |
| 7 | 平台常量读者面 | `EventContract.SOURCE_SYSTEM` 的**唯一**读取点 = `CanonicalEventSchemaParityTest.java:71-73`（`assertEquals(EventContract.SOURCE_SYSTEM, root…path("source_system").path("const")…)`） |
| 8 | 运行期 schema 校验器 | main 侧 Java/Scala **不存在** JSON-Schema 运行期校验器（0 命中）；schema 的消费面 = ① 平台侧结构对账测试 ② `openapi/generator-api.v1.yaml:26` 对生成器文件模式的**要求**（"每行必须通过该校验"）③ M1-5 契约脚本的 `Draft202012Validator.check_schema`（只校验 schema 文档本身合法） |
| 9 | 反证（已被容忍的别的值） | `PipelineServiceTest.java:584` 用 `"source_system":"mall"`；`SourceRegistryMigrationScriptTest.java:90` 断言「种子源编码必须等于冻结契约里的 source_system 取值 mock-mall」 |

**裁决**：`canonical-event.v1` 的 `source_system` **取消 `const`**，改为**仅形状约束**：

```json
"source_system": { "type": "string", "minLength": 1 }
```

- **值域不由契约拥有**：取值 = 该事件所属源的 `source_registry.source_code`（D-056：由平台经 `spark-submit --extra` 注入），命名/合法集合由注册表侧校验（D-035 的 4 个错误码面）负责。
- **刻意不加 `pattern`/`enum`**：加 `enum` ⇒ 每接入一个源都要改冻结契约（与"换商城"目标直接冲突）；加 `pattern` ⇒ 契约成为**第二个命名规则所有者**，与注册表侧规则漂移。契约只说形状，值域单一所有者 = 注册表。**这是本裁决的 anti-entropy 要点**。
- `mock-mall` 仍是**合法取值**（首个源的 `source_code`），故历史 55 条黄金链与既有夹具的合规性**不变**（加性放宽、非破坏）。

**同批次必改点（缺一即批次不成立）**

1. `event-contract.md:16`：注释由「固定值：mock-mall」改为「取值 = 本事件所属源的 `source_code`（注册表唯一拥有，D-056）；`mock-mall` 为首个源的取值，非契约固定值」。
2. `canonical-event.v1.schema.json:38-41`：`const` → 形状约束；description 同步并**显式登记本裁决号**。
3. `EventContract.java:17` **退休该常量**（delete-first；它是 §5.1 L316 明令禁止的写死商城名——见 D-064，且唯一读者是对账测试）。
4. `CanonicalEventSchemaParityTest.java:65-73`：断言由「schema 常量 == Java 常量」改为**结构化守卫**「`source_system` **不得**带 `const`」＋**正向对照**（同一测试内断言 `schema_version` **仍然带 `const`**，证明该守卫非空转）。
5. `SourceRegistryMigrationScriptTest.java:90`：断言可保留（种子源编码确为 `mock-mall`），但"必须等于冻结契约里的取值"的**理由文字**须改为"首个源的 `source_code`"。
6. `contract-specs/VERSION` 与 `README.md`：随批次升 `1.4.0` ＋ 指纹重登记（含本文件 §8 的日期补记）。

**不做**：不改 `EventContractValidator`（它本就不锁值，第 6 条实测 ⇒ 源 B 事件在采集层**不会**因本字段被拒）；不改生成器 `ContractFormat.SOURCE_SYSTEM`（属 B-06 未决项，CT-1 只**解除契约障碍**，见 D-064 ③）；不改 `EventContractTest.java:154`／`PipelineServiceTest.java:584` 的样本串（`mock-mall`/`mall` 放宽后均合法）。

**未取证**：① 未实测"放宽后是否有任何既有测试因 `const` 消失而变成空转"（须在落地时以"删掉 `const` 后测试仍能失败"的变异验证补上）；② `docs/contracts/event-contract.md` 是否还有别处（本文件之外）复述"固定值"——本轮只扫了契约目录与 `docs/contracts`，未做全仓扫描。

---

## 2. D-062（CT-2）：去重语义文字化 = 「源命名空间内唯一」

**事实（实测）**

| # | 位置 | 逐字原文（节录） |
|---|---|---|
| 1 | `docs/contracts/event-contract.md:12` | `"event_id": "UUID",                        // 全局唯一，ODS/DWD 按此去重（允许 at-least-once 投递）` |
| 2 | `docs/contracts/event-contract.md:165` | `` * `event_id` 唯一；重复投递由 DWD 按 `event_id` 去重，因此允许 at-least-once。 `` |
| 3 | `contract-specs/schemas/canonical-event.v1.schema.json:10` | `"description": "全局唯一事件 ID，ODS/DWD 按此去重（允许 at-least-once 投递）。来源：event-contract.md §1 L12。…"` |
| 4 | 权威依据 | §5.1 L314「跨商城去重键为 `(source_instance_id, event_id)`，不能只按 `event_id` 去重」；D-055（3）命名空间内单键、（4）混源守卫 |

**裁决**：三处文字统一改为**源命名空间内唯一**，并显式写出命名空间键：

- 唯一性范围：`event_id` 在**同一源命名空间**（`source_instance_id`）内唯一，**跨源不保证唯一**。
- 去重键：ODS/DWD 按 `(source_instance_id, event_id)` 去重；`event_id` **不改名**（D-055 保持）。
- **混源守卫**：同一批次内混入不同源的事件时，须**硬失败**而非静默合并（D-055（4））；本裁决只做**文字化**，守卫的实现属 P2-04 及以后。
- 与 D-055（2）一致：`event_id` 的实现单一所有者仍是 `spark-jobs/.../DwdSql.scala:31`；CT-2 **不动代码**。

**同批次必改点**：`event-contract.md:12`、`event-contract.md:165`、`canonical-event.v1.schema.json:10` 三处文字 ＋ 批次升版与指纹重登记。

**未取证**：未做全仓"只按 `event_id` 去重"的表述扫描（`contract-specs` 目录内"去重"命中：`canonical-event.v1` 1 处、`ingestion-manifest.v1` 2 处 —— 后者的 2 处指 `schema_version` 集合去重，与事件去重语义无关，**已排除**）；ODS/DWD 侧 SQL 是否已按 `(source_instance_id, event_id)` 去重**未实测**（属 P2-04 交付面，本轮不声称）。

---

## 3. D-063（CT-3）：`items` 双形态 = 「规范数组 + 外部源兼容字符串」

**事实（实测）**

| # | 事实 | 数据 |
|---|---|---|
| 1 | `canonical-event.v1.schema.json:548-549` | `"items": {` / `"type": "array",` / `"description": "订单项数组。§2.4 L77；子表字段见 §2.4 L84-L90。"` |
| 2 | 黄金数据集真实形态（`tests/golden-dataset/events/golden-20260901.jsonl`，55 行） | 含 `items` 的 **6 行**全部为**字符串形态**、**0 行为数组**、49 行无 `items` |
| 3 | 实测样本 | `"items":"[{\"product_id\":\"2\",\"quantity\":2,\"unit_price\":\"399.00\",\"discount\":\"0.00\",\"amount\":\"798.00\"},{…}]"` |
| 4 | 契约已登记该冲突 | `schema:538` 描述内含「真实数据冲突（全部 6 行 order_created 均如此，L18/L20/L22/L39/L43/L47，**且都被采集层接受**）：(1) items 被写成 JSON 字符串…」 |

**裁决**：契约**加性**接受两种形态，并把"谁是规范形态"写清楚：

- **规范形态** = 数组（`array<object>`，5 字段必填不变）——自建/自产源（参考商城、生成器）**新产出**一律用数组。
- **兼容形态** = 承载 JSON 编码数组的**字符串**——外部源（不由我们控制）允许；**平台必须两种都能解析**（解析实现单一所有者放 DWD 侧，属 P2-04/P2-05 范围，**本批不实现**）。
- 契约表达：`oneOf`（数组形态 / 字符串形态），两种形态的子约束**保持不变**，仅新增分支 ⇒ 对既有 6 行与其余 49 行**均为加性**。
- **理由（业务）**：若严格拒绝字符串形态，则 6/6 真实 `order_created` 全被隔离 ⇒ 订单域分析**没有任何输入**；且"适配不同商城"要求契约不得比真实源的形态更窄。原始保真 + 下游归一正是 D-054 的既定方向。
- **禁止**：不得为了"让校验通过"而修改 6 行真实数据（证据数据不可改；数据面只有加性补充一条路径 D-041）。

**同批次必改点**：`canonical-event.v1.schema.json:538`（description 增加"本裁决后的处置"）与 `:548-549`（`oneOf` 化）＋ 批次升版与指纹重登记。

**未取证**：① 生成器/参考商城**当前产出**的 `items` 形态**未实测**（`CanonicalEventFactory` 路径未读）⇒ **不得**声称"生产者已改/已合规"；② 采集层对该字段的处置强度（`missingPayloadField` 是否覆盖 `items`）未实测；③ ODS/DWD 是否已有解析 `items` 的实现未实测。以上三项列入 `PLAN.md` §4 开工首测项。

---

## 4. D-064：§5.1 L316「不得写死商城名」的实测量化与处置（本批顺带）

**事实（实测，main 侧非测试 Java/Scala 326 个文件全扫）**：命中 `mock-mall|mock_mall|mall_simulator|mall-simulator` 共 **12** 处，其中**代码常量 3 处**（其余 9 处为注释/javadoc 中的正当叙述）：

| # | 常量位置 | 处置 |
|---|---|---|
| ① | `analytics-server/platform-common/.../EventContract.java:17` | **本批退休**（D-061 第 3 条；唯一读者是对账测试） |
| ② | `mall-simulator/.../outbox/EventContract.java:10` | **本批不动**，登记为待办：商城程序自身身份须**配置化**（否则同一程序无法以另一个 `source_code` 产出），时点 = P3/P5 前单独立项 |
| ③ | `synthetic-data-generator/.../contract/ContractFormat.java:43` | **本批不动**，属 **B-06 未决项**；CT-1 只解除契约障碍（放宽 `const`），使 B-06 可按"文件模式取配置/清单"收口 |

**对照**：test 侧命中 49 次（121 个 test 文件）——测试里出现商城名不构成 §5.1 L316 违规，本裁决不要求清理。

**不得声称**：本裁决落地后 §5.1 L316 仍未全合规（②③ 仍standing）——只能在 ②③ 完成后才可声称。

---

## 5. 与 CT-4 的边界（B-06 / Q6）

CT-4 = `canonical-event.v1` **冻结前置**，其两项未决：**B-06**（文件模式的自述身份：生成器文件模式产出行在信封上仍自称 `mock-mall`，而 §3.3 B 要求该模式"必须显示 synthetic=true"，该标记当前只落在**制品清单** `ArtifactManifest.synthetic`）与 **Q6**（"必填"在采集层的执行强度：`EventContractValidator.missingPayloadField` 只校验子类型子集）。

**本轮新增的量化起点（实测，仅供 CT-4 使用，不作为 CT-4 结论）**：`canonical-event.v1.schema.json` 已登记 **8** 处"真实数据冲突"（`:347` `age_group="45-54"` 且因 `schema_version="2.0"` 被正确隔离；`:490` 6 行 `behavior` 用 `channel="web"` **被接受**；`:513` `behavior_type="purchase"` 被正确隔离；`:538` 6 行 `order_created` 的 `items` 字符串形态**被接受**；`:614` 5 行 `order_paid` 缺 `paid_at` **被接受**；`:650` 1 行 `order_cancelled` 缺 `cancelled_at` **被接受**；`:681` 3 行 `refund_created` 缺 `created_at` **被接受**；`:722` 3 行 `refund_completed` 缺 `completed_at` **被接受**）⇒ **被接受但按契约属脏 = 24 行；被正确隔离 = 1 行；合计 25 行**，与既有"约 25 行"的估计一致但**首次给出了口径**。

**未取证（重要）**：上述行号指向真实夹具 `landing/events/r9-m1-123006.jsonl`，**该文件的行数与逐行内容本轮未实测**（CT-4 的前提是对该夹具逐行复核）⇒ **不得**据此声称 CT-4 已具备裁决条件。CT-4 时点不变：`canonical-event.v1` 冻结前，且**不阻塞** CT-1/CT-2/CT-3。

---

## 5bis. D-065（F-30 处置）：契约锚点风格统一 —— 一部分随本批，一部分单独立项

**事实（实测，全部来自 `docs/acceptance/m1-5-anchor-audit-20260912/` 的可复跑审计）**

| # | 事实 |
|---|---|
| 1 | `contract-specs/**` 现有 **258** 处裸锚点，按文件分布：`openapi/generator-api.v1.yaml` 71、`schemas/canonical-event.v1.schema.json` 125、`schemas/generation-artifact-manifest.v1.schema.json` 17、`schemas/ingestion-manifest.v1.schema.json` 1、`README.md` 44（`specs/warehouse-namespace.v1.json` 0） |
| 2 | 其中**只有 48 处**能机械定位目标（45 处 → `docs/contracts/event-contract.md`；3 处 → `docs/项目完整实施指导书 V2.3.md` §4.1.1.3），**210 处**无法机械确定目标文档（多文档同分 48／无 token 可核对 136／token 未命中 26） |
| 3 | F-29 声称的"统一偏移 +1／+12／+145"**不普适**；病根是**跨文档引用丢掉了文件名** |
| 4 | 审计明确**没有**核对"锚点是否指对了地方"（只做了机械定位）；非 markdown 载体（yaml/json/SQL/Java 注释）里的同款裸锚点**未普查** |

**裁决**

1. **拒绝批量改写**：不得按 F-29 的偏移批量改写，也不得给 210 处补一个**未经判定**的目标文件名——那等于把不可复核的引用写进契约，违反"未实测不写结论"。
2. **立规则（写入 `contract-specs/README.md`，随本批一次性升版）**：**新增**锚点必须带**文件名**（例：`docs/contracts/event-contract.md §2.5 L92-L100`）或在册版本前缀（`指导书 V2.x`／`设计文稿 V2.2`）；**裸锚点不得新增**。此规则与文件内既有写法一致（`canonical-event.v1.schema.json:611` 的 `x-source` 已经是带文件名的正确形态）。
3. **立机器守卫 ＋ 负债台账**（本批交付）：守卫脚本 `scripts/check-bare-anchors.ps1` ＋ 台账 `scripts/contract-bare-anchors.allowlist.txt`（258 行，逐行「文件｜锚点文本｜出现次数」）。判据 = **裸锚点集合 ⊆ 台账集合**；新增即**门禁失败**（响亮报错），台账**只减不增**，每次删减须在看板登记。守卫本身必须带**正向对照**（构造一条带文件名的锚点 ⇒ 不得计入裸锚点）。
4. **随本批顺带**：只补**与 CT-1/CT-2/CT-3 改动同段文字内**的裸锚点（含 `canonical-event.v1.schema.json:538` 的 `§2.4 L77；子表字段见 §2.4 L84-L90` 一处）——这些锚点本就要被本批改到，顺手带上文件名**零额外风险**。其余 48 处与 210 处**本批不动**。
5. **单独立项**：其余锚点的"补文件名（＋可选行号复核）"与**语义核对**立为专项 **M1-5-R**，时点排在 **P3-04（semantic/dimension registry，本就要动契约）同批或之前**；在此之前 **M1-5 保持 `REVIEW`**，且**不得**声称 R-M1-5-1 已闭环。
6. **本批不得声称**：不得声称"锚点已对齐（指对了地方）"（只补文件名、未核语义）；不得声称非 markdown 载体已普查。

---

## 6. 本批次的验收与证据面（摘要，明细见 `PLAN.md`）

- 契约面：`contract-specs/VERSION` `1.3.0 → 1.4.0`；四个 DRAFT 制品中**只有** `canonical-event.v1` 内容变化；每处改动须"**命中且仅命中 1 次**"断言 + 写前/写后 SHA-256 与逐项指纹重登记。
- 代码面：`EventContract.java`（退休常量）＋ `CanonicalEventSchemaParityTest.java`（守卫替换＋正向对照）＋ `SourceRegistryMigrationScriptTest.java`（文字）；E1 = `mvn -o -f analytics-server/pom.xml -pl platform-common,platform-app -am test -DforkCount=0`（离线、单模块，禁止与其他 Maven 并发）。
- 文档面：`docs/contracts/event-contract.md` 两处（CT-1 一处、CT-2 两处，其中 `:12` 与 CT-2 同一行见 §2 —— **施工顺序：CT-1 先改该行注释同一处，避免两次触碰同一行**）。
- 证据分级：本批只做 E1（编译＋模块单测）＋契约结构对账；**不产生** E3/E4/E5 证据；T2 黄金链重跑（M1-11）**必须**排在本批之后**重新**执行一次（jar/契约都已变）。
- 历史纪律：`README.md` §10 冻结指纹表的**历史行一律原文保留**，新值以**新增行**方式登记（CT-0 已确立的做法）。

---

## 6. 补记（2026-09-12 13:3x 总控）：VERSION 目标值勘误 —— CT 批次改为「按落盘时 minor 递增」

**背景**：P2-07（源级数仓命名空间）只读取证完成后裁决为**破坏性契约变更**（`docs/acceptance/p2-07-source-prefix-20260912/RULINGS-20260912.md` D-072）：`warehouse-namespace.v1.json` 的 `sourceOfTruth` 由 `runtime_profile.hive_database_prefix` 改为源级，按 `contract-specs/README.md:42`「不原地改语义」⇒ 新增 `warehouse-namespace.v2.json` 并把目录 `VERSION` 推到 **2.0.0**。

**勘误**：本批次原定 `VERSION 1.3.0 → 1.4.0`（见 D-061…D-065 与本目录 PLAN）。因两个批次共用同一份 `contract-specs/VERSION`，P2-07 先落 2.0.0 ⇒ **本批次的 VERSION 目标值改为「按落盘时的当前值 minor 递增」**：
- 若 P2-07（2.0.0）已落盘 ⇒ 本批次落 **2.1.0**；
- 若本批次先落 ⇒ 仍为 **1.4.0**，P2-07 后落则按 major 规则落 2.0.0。

**不变项**：CT-1/CT-2/CT-3 的裁决内容、施工单、验收面（E1/E2/T2 排序）、以及「锚点规则与守卫」全部不变；**变的只有 `VERSION` 这一行的目标数值**。落盘时必须在 `contract-specs/README.md` 的变更记录里写明本次是「加法变更(minor)」还是「破坏性变更(major)」并给日期。

---

## 7. 补记（2026-09-12，总控）：**D-061「未取证②」回填** —— 全仓扫描实测，CT-1 变更清单由此**完备化**

- **触发**：D-061 自述「未做全仓扫描」，即"除 `event-contract.md` 外是否还有别处复述 `source_system` 固定值"**未取证**。若另有复述而 CT-1 清单未列，CT-1 落地后会留下**自相矛盾的仓库**（契约已放宽、别处仍称固定值）。
- **扫描口径（可复现）**：`git ls-files -z` 取全量入库文件 ⇒ 按扩展名过滤（`.md/.json/.yaml/.yml/.java/.scala/.ps1/.sql/.py/.txt/.tsv/.proto/.xml/.properties` ＋ `contract-specs/VERSION`）⇒ **排除** `docs/acceptance/**`（历史报告，按定义不改）与 `target/**` ⇒ **扫描 589 个文件**；四组正则：**P1** `固定值`；**P2** `SOURCE_SYSTEM\s*=\s*"`；**P3** `"const"\s*:\s*"mock-mall"`；**P4** `(等于|==|必须是).{0,12}mock-mall|mock-mall.{0,12}(固定|常量|唯一)`。
- **正向对照（证明扫描非空转）**：P1 命中 `docs/contracts/event-contract.md`＝**是**；P2 命中 `platform-common/.../EventContract.java`＝**是**。⇒ 扫描确能发现该类文本，零命中不等于漏扫。
- **未覆盖边界（未取证）**：① **未入库文件**（`git ls-files` 不含，含在飞泳道新建文件）；② `docs/acceptance/**` 历史报告（按定义不改，但其中确有复述，勿据此判"仓库无复述"）；③ `target/` 构建产物与 `logs/`。

### 7.1 命中分类（15 个文件）

| 分类 | 文件（行） | 处置 |
|---|---|---|
| **CT-1 已列** | `docs/contracts/event-contract.md:16`；`contract-specs/schemas/canonical-event.v1.schema.json:39,40`；`analytics-server/platform-common/.../EventContract.java:17`；`contract-specs/README.md`（见下行，需细化） | 按 D-061 第 1–6 条执行 |
| **★ 新命中·必须在 CT-1 清单内细化** | **`contract-specs/README.md:43`**（§3 规则：「当前仓库里 … 两侧常量值经核对一致：`SCHEMA_VERSION="1.0"`、`SOURCE_SYSTEM="mock-mall"`」）与 **`:71`**（§5 建模决定：「**`source_system` 取 `const: "mock-mall"`**：…且 `EventContract.SOURCE_SYSTEM` 两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`」） | **CT-1 落地后这两句即变为假**：`:71` 的 `const` 与"parity 测试要求 const"双双反转；`:43` 的平台侧常量副本被退休。⇒ **CT-1 第 6 条"README 随批升版＋指纹重登记"须展开为"逐句更新 `:43`/`:71`"**，否则 README 与 schema 互相矛盾 |
| **新命中·**不改**（假阳性，防误改）** | `analytics-server/warehouse-pipeline/.../JobCommandBuilder.java:48` ＝ `ARG_SOURCE_SYSTEM = "sourceSystem"`（**参数名**，非商城名）；`analytics-server/platform-app/src/test/.../PlatformMallBoundarySourcePolicyTest.java:63`（**注释**说明小写串 `source_system="mock-mall"` 不受"商城命名遗留"守卫约束） | **不动**。二者与"固定值"无关；若被当作命中而改，属破坏 |
| **新命中·**不改**（行为不变）** | `synthetic-data-generator/src/test/.../FileModeGenerationEngineTest.java:525-527`（断言生成器产出 `source_system == "mock-mall"`） | CT-1 **明确不改生成器**（B-06 未决）⇒ 该断言**仍然正确**，不得随之改动 |
| **新命中·**不改**（商城侧副本，B-06 未决）** | `mall-simulator/src/main/java/.../outbox/EventContract.java:10`；`mall-simulator/src/test/.../GoldenDatasetTest.java:155`（用**商城自己**的常量） | CT-1 只退休**平台侧**常量（D-061 第 3 条）⇒ 商城侧常量与其测试**不受影响**；"两侧副本"的收敛仍属 **B-06** |
| **新命中·**不改**（历史/他级权威文本）** | `docs/开发过程事实与决策记录.md:427,875`；`docs/项目完整实施指导书 V2.0.md:1417`（**历史版本**）；`docs/superpowers/plans/2026-09-06-slice01-mall-outbox.md:72`；`docs/项目实施进度与任务看板 V2.2.md:431,445`（看板历史行，含本轮 CT/A12 行）；`docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md:23` | **一律不改**（append-only 历史件）。设计书 `:23` 是**审计问题陈述**（把 `source_system == "mock-mall"` 列为**待修硬耦合**），与 CT-1 **同向**，不构成冲突；如需标注"该问题由 CT-1 关闭"，走补记而非改原文 |

### 7.2 结论与效力

- **D-061「未取证②」就此关闭**：全仓（589 个入库文件）**无第三处"必须随 CT-1 改"的新文件**；唯一增量是 **`contract-specs/README.md:43` 与 `:71` 两句必须逐句更新**，已并入 CT-1 第 6 条，**不改变 CT-1 的裁决内容**（原裁决方向不变，仅使变更清单完备）。
- 本补记**不新立裁决号**（避免重复所有者）：它是对 **D-061** 的取证回填。
- **同时实测到的一处现状（只读，非裁决）**：平台侧 **A12 已在工作树落地** —— `JobCommandBuilder.java:124-126`（`// A12：源编码与库名并列下发` ＋ `cmd.add("--" + ARG_SOURCE_SYSTEM + "=" + source.sourceCode());`），Javadoc `:32–48` 同步写明"值 = `source_registry.source_code`"与 spark-jobs 侧 fail-closed 的呼应。**该文件属在飞泳道（P2-07）** ⇒ 总控**只读确认存在**，**E1/E2 未复核**，**不得**据此声称 A12 已通过验证。
- **未做**：未改任何代码/契约/历史文档；本轮无库写；未停启 809x。

---

## 8. 补记（2026-09-12 13:4x，总控）：**CT-4 §5 量化勘误（以此为准）+ §5「未取证」回填**

**取证方式（可复跑，脚本已入库）**：`docs/acceptance/ct4-fixture-replay-20260912/`（`REPLAY.md` ＋ `raw/per-line-verdict.tsv`、`raw/all-errors.tsv`、`raw/partition-map.tsv` ＋ `tools/*.py`）；本机 Python 3.14.5 ＋ `jsonschema`（Draft 2020-12，**未启用 `format`**）。夹具 `tests/golden-dataset/events/golden-20260901.jsonl` 18,430 B / **55 行** / sha256 前 16 `2351BCC35E04CCD2`，与 `landing/events/r9-m1-123006.jsonl` **字节相等**；契约 schema 867 行 / sha256 前 16 `0E2E4ED2B17D7DB7`。

### 8.1 关闭 §5「未取证」
「该文件的行数与逐行内容本轮未实测」**已不再成立**：行数 55、逐行判定与全错误枚举均已落盘。分区无损：`partition-map.tsv` 未匹配 0 行；`17,419 + 1,011 = 18,430 = 夹具字节数`。
**一处否定结果**：字节和相等但夹具**不是** accepted 与 quarantine 的字节拼接（`fixture_is_concat=False`），且隔离的 4 行**不在**夹具尾部（L38 在中间）⇒ 不得称"最后 4 行即隔离件"。

### 8.2 三口径对账（**RECON 的 26/25 经独立复算通过**）
采集层分区 **51 accepted / 4 quarantine**（与 manifest 51/4 一致）；契约复算 **26 VALID / 28 INVALID / 1 不可解析**。
`28 = 25`（在被接受的 51 行内）`+ 3`（在被隔离的 4 行内：L38、L54、L55）；另 **L53 为不可解析行**（字面量 `not-valid-json-line-with-no-braces-{{{`，**故意用例**）。
⇒ **被接受 51 行中：26 合规、25 违约**；**全量非合规 29 行**（25＋3＋1），完全合规 **26 行**。

### 8.3 §5 量化勘误（**以此为准**，原文「被接受但按契约属脏 = 24 行；被正确隔离 = 1 行；合计 25 行」三处需修正）

| # | 原文 | 修正为 | 依据 |
|---|---|---|---|
| 1 | 被接受但属脏 = **24** | **25** | 24 是"登记表内被接受的冲突行"数；**漏登第 25 行** = 夹具 **L52 `stock_changed.change_type="restock"`**（枚举外值且**被接受**）⇒ `canonical-event.v1.schema.json` 的"真实数据冲突"表**少一类** |
| 2 | 被正确隔离 = **1** | 登记表实为 **2**（L38、L54）；实际被隔离 **4**（L38、L53、L54、L55） | L347 与 L513 两条登记**均**描述被隔离行 |
| 3 | 合计 = **25** | 按其自身登记表应为 **26**；按实测非合规为 **29** | 24+1=25 与登记表 24+2=26 **自相矛盾** |
| 4 | L538 描述 6 行 `order_created` 冲突为「`items` 字符串形态」 | **不完整**：每行 3 处违规 = 缺必填 `status` ＋ 缺必填 `created_at` ＋ `items` 非数组 | `raw/all-errors.tsv`（L18/L20/L22/L39/L43/L47 同名缺字段） |

**应补登记 2 项冲突**：① `stock_changed.change_type` 枚举外值（L52，被接受）；② 信封级必填 `event_id` 缺失（L55，被隔离）。**并新立 1 档**：③ **不可解析行**（L53）——与字段级违约不同档，不得并入"必填/枚举"。

### 8.4 对 Q6 的证据（不替代 CT-4 裁决）
采集层 validator **接受 25 行违约行**（24 行登记 + 1 行未登记），与其"必填只校验子集"的判断同向；但它**确实隔离了 4 行**（含 1 行不可解析）⇒ 隔离通道真实工作。**不得**称"validator 不校验"。

### 8.5 §5 其余内容与前次补记的效力
§5 关于 CT-4 时点（`canonical-event.v1` 冻结前、**不阻塞** CT-1/CT-2/CT-3）与"B-06/Q6 未决"的部分**继续有效**；本补记只修正**量化数字与登记完整性**，并回填前提物。§7（D-061 未取证② 回填）继续有效。
