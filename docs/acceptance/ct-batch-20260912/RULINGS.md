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

## 6. 本批次的验收与证据面（摘要，明细见 `PLAN.md`）

- 契约面：`contract-specs/VERSION` `1.3.0 → 1.4.0`；四个 DRAFT 制品中**只有** `canonical-event.v1` 内容变化；每处改动须"**命中且仅命中 1 次**"断言 + 写前/写后 SHA-256 与逐项指纹重登记。
- 代码面：`EventContract.java`（退休常量）＋ `CanonicalEventSchemaParityTest.java`（守卫替换＋正向对照）＋ `SourceRegistryMigrationScriptTest.java`（文字）；E1 = `mvn -o -f analytics-server/pom.xml -pl platform-common,platform-app -am test -DforkCount=0`（离线、单模块，禁止与其他 Maven 并发）。
- 文档面：`docs/contracts/event-contract.md` 两处（CT-1 一处、CT-2 两处，其中 `:12` 与 CT-2 同一行见 §2 —— **施工顺序：CT-1 先改该行注释同一处，避免两次触碰同一行**）。
- 证据分级：本批只做 E1（编译＋模块单测）＋契约结构对账；**不产生** E3/E4/E5 证据；T2 黄金链重跑（M1-11）**必须**排在本批之后**重新**执行一次（jar/契约都已变）。
- 历史纪律：`README.md` §10 冻结指纹表的**历史行一律原文保留**，新值以**新增行**方式登记（CT-0 已确立的做法）。
