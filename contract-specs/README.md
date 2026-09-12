# contract-specs — 三程序共享的版本化机器可读契约

状态：**部分冻结**（`specs/surrogate-key.v1.json` 为 `DRAFT`，**尚无任何实现读取**；见 §4 目录表与 §10 指纹表）——`specs/warehouse-namespace.v2.json` 已由总控冻结（2026-09-12，依据 P1-04 的本地 E3 实测，见 §4；v1 为历史冻结件，其 `sourceOfTruth` 已被 v2 取代）；其余四个制品仍为 `DRAFT`（`canonical-event.v1` 受 B-06/Q6 未决阻塞） ｜ 版本：`2.0.0`（见 [`VERSION`](VERSION)；`1.0.0 → 1.1.0` 对应 `specs/warehouse-namespace.v1.json` 的加法新增，`1.1.0 → 1.2.0` 对应 `ingestion-manifest.v1` 的 P1-05 加法扩展，`1.2.0 → 1.3.0` 对应 M1-5 收口同步（见 §11），`1.3.0 → 2.0.0` 对应 `specs/warehouse-namespace.v2.json` 的**破坏性变更**（取值来源由 `runtime_profile.hive_database_prefix` 迁至源级 `source_registry.warehouse_prefix`；规则本体与 22 向量与 v1 逐字段等价，v1 保留为历史冻结件），均按 §3 的目录级版本规则） ｜ 建立任务：M1-5（`specs/` 部分为 P1-04 新增，`ingestion-manifest.v1` 的来源字段为 P1-05 新增）

## 1. 目的与边界

本目录是**中立目录**：按 `docs/项目完整实施指导书 V2.1.md` §3.1 L64

> 另外建立中立的 `contract-specs`，保存版本化 JSON Schema、OpenAPI 和清单 Schema。三个程序可以分别根据契约生成或校验数据，但不得通过共享 Java 实体、Mapper 或 Maven 业务依赖耦合。

它服务于三个独立程序（§3.1 L60-L62）：

| 程序 | 端口 | 与本目录的关系 |
|---|---|---|
| `analytics-platform`（分析平台） | 8091 | 采集/标准化侧按 `ingestion-manifest.v1` 写出并校验清单；按 `canonical-event.v1` 校验入湖事件 |
| `reference-mall`（参考商城） | 8090 | Outbox 事件按 `canonical-event.v1` 校验后再落滚动日志 |
| `synthetic-data-generator`（模拟数据生成器） | 8092 或 CLI | 按 `canonical-event.v1` 生成事件、按 `generation-artifact-manifest.v1` 写制品清单；对外 API 按 `generator-api.v1` 实现 |

本目录**不承载实现代码**，也不是第四个可运行程序；不含 DDL、不含 Java 类、不含前端契约实现。

## 2. 权威顺序（冲突如何处理）

1. **人读语义权威**：`docs/contracts/*.md`（首要是 [`docs/contracts/event-contract.md`](../docs/contracts/event-contract.md)，schema_version 1.0）以及 `docs/项目完整实施指导书 V2.1.md` §3/§4。
2. **本目录是它们的机器可读投影**，不是新的语义来源。**两者不一致时以 markdown 文档为准**；本目录文件必须改，或 markdown 必须升版。
3. 任何跨程序字段变化，先由总控登记契约任务，其他 Agent 等待契约冻结（V2.1 §9.1 L358：统一事件契约属热点文件）：

   > `PipelineService`、迁移版本号、统一事件契约、前端 router、主配置属于热点文件，同一时间只允许一个 Owner 修改。任何跨泳道字段变化先由总控登记契约任务，其他 Agent 等待契约冻结。

4. 上一轮读取的来源文档版本（作者会话内实测 SHA-256，便于判断本目录是否滞后）：

| 来源 | SHA-256（前 16 位） |
|---|---|
| `docs/contracts/event-contract.md` | `FB991C17B9037B1B` |
| `docs/项目完整实施指导书 V2.1.md` | `845D8C08297F7E1C` |
| `landing/manifests/30.json`（真实清单） | `A68566DB7C38FFA0` |
| `landing/events/r9-m1-123006.jsonl`（真实夹具） | `2351BCC35E04CCD2` |

**权威文档已换代（本轮实测，2026-09-11）**：当前权威是 `docs/项目完整实施指导书 V2.2.md`（实测 SHA-256 前 16 位 `B366969C12A1A2C7`）；本目录建立时依据的 V2.1 现测前 16 位为 `F7E13DAF244295BF`，与上表记录的 `845D8C08297F7E1C` **不同** ⇒ 该来源文档在本目录建立之后发生过变化（原因未取证：可能为内容更新或行尾/编码重写）。**本目录尚未按 V2.1/V2.2 复读**，属待办的契约任务；在上表补上 V2.2 指纹之前，任何"契约与指导书一致"的说法只覆盖建立时那一版。

## 3. 规则

- **版本号**：沿用 `event-contract.md` L4 的自身规则——新增字段 → `schema_version` 升 `1.1` 起；破坏性变更 → 新主版本并增加转换器。文件名中的 `v1` 表示主版本，仅在破坏性变更时新增文件（如 `canonical-event.v2.schema.json`），**不原地改语义**。目录级版本见 [`VERSION`](VERSION)：**加法变更 → minor 递增**（`1.0.0 → 1.1.0 → 1.2.0 …`）；破坏性变更 → major 递增（`2.0.0`）并新建 `v2` 文件。（原句写作"加法变更 → `1.1.0`"，那是目录还在 `1.0.0` 时的写法；`D-037` 裁决 7 明确为语义化递增，避免第二次加法无号可升。）
- **禁止跨程序 Java 依赖**：三个程序不得互相 import Java 实体、Mapper、Service 或 Maven 业务依赖（§3.1 L64、§3.4 L96-L97）。本目录只交换 JSON Schema / OpenAPI / 清单 Schema 这类语言无关产物。当前仓库里 `analytics-server` 与 `mall-simulator` 各有一份 `EventContract.java` 常量副本，就是本目录要消除的耦合形态（两侧常量值经核对一致：`SCHEMA_VERSION="1.0"`、`SOURCE_SYSTEM="mock-mall"`、金额正则相同）。
- **每个程序校验自己的输出**，不依赖别的程序替它校验：生成器校验自己写的 JSONL 与清单；商城校验自己写的 outbox 事件；平台校验自己读入的事件与自己写出的采集清单。校验器实现由各程序自带，本目录只提供 schema。
- **不得静默放宽**：本目录 schema 中任何未获来源明确授权的地方都以 `"x-unspecified": true`、`x-*` 注解或 `description: "指导书未确定，待契约任务冻结"` 显式标注；不得用「反正宽松点也能过」的方式偷偷放宽枚举、必填或正则。

## 4. 制品清单

| 文件 | 归属程序（Owner） | 谁校验 | 状态 |
|---|---|---|---|
| [`schemas/canonical-event.v1.schema.json`](schemas/canonical-event.v1.schema.json) | 三方共享：`reference-mall`（写 outbox 事件）、`synthetic-data-generator`（文件模式写 JSONL）、`analytics-platform`（入湖校验） | 三方各自校验自己的输出；平台侧另有结构对账测试 `CanonicalEventSchemaParityTest` | `DRAFT` |
| [`schemas/ingestion-manifest.v1.schema.json`](schemas/ingestion-manifest.v1.schema.json) | `analytics-platform`（`connection-ingestion` 写出 `landing/manifests/{batchId}.json`） | 平台采集侧自校验；生成器/商城只读不写 | `DRAFT`（2026-09-11 按 `D-037` 加法扩展 4 个源身份字段：`sourceCode`/`sourceId`/`profileVersion`/`mappingVersion`，**均不入 `required`** 以兼容已落盘清单；取值者 = `source_registry` 的 `source_code`/`id`/`profile_version`，`mappingVersion` 在 P2 前恒为 `null`） |
| [`schemas/generation-artifact-manifest.v1.schema.json`](schemas/generation-artifact-manifest.v1.schema.json) | `synthetic-data-generator`（`CANONICAL_EVENT_FILE` 模式制品清单） | 生成器自校验；平台采集侧在读取生成器目录时按此校验 | `DRAFT` |
| `openapi/generator-api.v1.yaml` | `synthetic-data-generator`（服务端）；`MALL_API` 场景下的调用方与页面为消费方 | 生成器按契约实现并自测；调用方按契约生成客户端 | `DRAFT` |
| [`specs/warehouse-namespace.v1.json`](specs/warehouse-namespace.v1.json) | 两个消费方共享：`spark-jobs`（Scala）、`analytics-server`（Java）；两侧各自持有**薄适配器**，规则本体只在本规格 | Java：`WarehouseNamespaceContractTest`（逐向量 + `errorCodes` 数量/取值）+ `WarehouseNameLiteralGateTest`（源码门禁：除唯一 owner 外无裸库名字面量）；Scala：`WarehouseNamespaceSpec`（62 用例） | **已被 v2 取代（历史冻结件，不改）** |
| [`specs/warehouse-namespace.v2.json`](specs/warehouse-namespace.v2.json) | 同上（`supersedes: warehouse-namespace.v1.json`） | 同上 | **`FROZEN-2026-09-12`** |
| [`specs/surrogate-key.v1.json`](specs/surrogate-key.v1.json) | `spark-jobs`（Scala，派生侧）＋ `analytics-server`（Java，源画像声明校验与展示）；两侧薄适配**待建** | 待建（Java/Scala 逐向量对账，模式沿用 warehouse-namespace） | **`DRAFT-2026-09-12`**（无实现读取；不得声称已验证） |
| [`VERSION`](VERSION) | 总控 | — | `2.1.0` |

**为什么其余四个制品仍是 `DRAFT`（诚实说明）**：这些文件是 M1-5 新建立的投影，尚未经过两条冻结门槛——(1) 结构对账测试 `analytics-server/platform-common/src/test/java/com/graduation/analytics/contracts/CanonicalEventSchemaParityTest.java` 转绿；(2) 总控（热点 Owner）审阅并处置第 7 节的待决策项。**只有两者都完成，才允许把状态改为 `FROZEN`。** 当前 `canonical-event.v1` 的字段集/枚举/常量/金额正则已按该测试的断言逐条对齐（本案建立时用 PowerShell 逐条模拟断言核对，见第 8 节；M1-5 范围内不允许运行 Maven，故未执行该测试本身）；且 Q6（采集层接受的 51 行里约 25 行按契约属脏数据）未决 ⇒ `canonical-event.v1` **不得**冻结。

**`specs/warehouse-namespace.v1.json` 为何已冻结（冻结依据与边界，2026-09-11 总控复核）**：P1-04 交付了机器可读规格 + Java/Scala 两侧薄适配器 + 门禁与逐向量测试，并用**隔离临时数仓真跑 `spark-submit`** 取证（`docs/acceptance/p1-04-namespace-20260911/`，探针 18/18 PASS）：前缀 `dw_b` → 实建 `dw_b_ods…dw_b_ads` 且**不建** `dw_ods`；不传前缀 → `dw_ods…dw_ads` 与前者的表/分区/文件**逐项一致**（⇒ 零数据迁移）；非法前缀（`dw_ods`、`DW`）→ Spark 启动**前** `exit 64`、stdout 无 JobResult、**0 个库**（fail-closed）。E2 侧 `WarehouseNameLiteralGateTest` 证明"除唯一 owner 外无裸库名字面量"。**冻结的边界（未取证，不得当作已证）**：① 集群档与 1,000,000 行档；② `beeline --hivevar` 的实际变量替换（本机无 HiveServer2 ⇒ 留 M3/集群 T4）。这两项不改变规则本体，但**任何"集群上已验证"的说法都不成立**。冻结后按 §3 处置：加法变更 → 目录级升 `1.1.0` 并复核向量；语义变更 → 新建 `warehouse-namespace.v2.json`，不原地改。

**2026-09-12 更新（P2-07 / `D-072`）：取值来源迁到源级，规则本体逐字段未变。** P2-07 只读取证实测：源级前缀此前在代码/库/契约三层**皆无载体**（`source` 包内 `WarehouseNamespace` 零命中；全库仅 `runtime_profile.hive_database_prefix` 一列；`source_registry` 11 列无前缀列），故按本节规则**出新 v2 而非原地改 v1**：`sourceOfTruth` 改指 `source_registry.warehouse_prefix`（NOT NULL、无 DEFAULT，V18 迁移建立），解析链唯一化为 `source_registry.warehouse_prefix → WarehouseNamespace`（经 `WarehouseNamespaceProvider.forSource(sourceId)`，`current()` = `forSource(ACTIVE 档案的 source_id)`）。**机械等价性证明**：v2 的 `rule` 除 `sourceOfTruth` 外与 v1 逐字段相等，22 条向量的 `input`/`expect`/`names` 三元组逐条相等（`json` 深比较，`contract-specs/VERSION` 同步 `1.3.0 → 2.0.0`）⇒ 上文 P1-04 的隔离真跑结论（零数据迁移、非法前缀 fail-closed）**继续有效**，因其只依赖规则本体。**引用点排期**：Java 侧（`WarehouseNamespaceContractTest.java:40` 等 4 处）随 P2-07 实施改指 v2；Scala 侧（`WarehouseNamespaceSpec.scala:177` 等 2 处）改指 v2 排在 P2-01 实施泳道让出 `spark-jobs/**` 之后（登记为 P2-07-c）——**在该项完成前，两侧读的是 v1/v2 两个文件名，但规则本体逐字段相同，不构成行为分叉**。另：`runtime_profile.hive_database_prefix` **只断读不删列**（`D-073`；删列属破坏性 DDL，待用户确认后另立任务），`EvidenceBuilder` 的第二读取端口（快照身份 vs 当前 profile）未在本轮修复，另立 P2-07-b。

## 5. `canonical-event.v1` 的建模决定（须连同 markdown 一起读）

- **根对象 = 恰好 8 个信封字段**（§1 L11-L20）：`event_id`、`event_type`、`event_time`、`ingest_time`、`source_system`、`schema_version`、`trace_id`、`payload`；`required` 为同样 8 个（§1 L28「缺失必需字段视为脏数据」）。信封上不新增任何字段——包括生成器的 `synthetic` 标记（信封放不下，见第 7 节 Q3）。
- **信封允许多出未知字段**（`additionalProperties: true`，标 `x-unspecified`）：依据 `event-contract.md` §1 L28「多出的未知字段不阻止消费（向后兼容）」的兼容性原则与 `EventEnvelope.java:18` 的 `@JsonIgnoreProperties(ignoreUnknown = true)`；该句字面只覆盖 payload，故显式登记为待冻结项（Q1）。
- **`payload` 允许多出未知字段**：§1 L28 明确允许。
- **`source_system` 取 `const: "mock-mall"`**：§1 L16「固定值：mock-mall」，且 `EventContract.SOURCE_SYSTEM` 两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`。§3.3 B 的 `synthetic=true` 与它的关系未定（Q3）。
- **`schema_version` 取 `const: "1.0"`**：§1 L17 + `EventContract.SCHEMA_VERSION`。
- **`event_type` 枚举 12 类**：§3 L152-L160。
- **金额类字段**统一 `$ref` 到 `$defs.amount`（`type: string` + 正则 `^\d+(\.\d{1,2})?$`）：§1 L26「金额一律十进制字符串（保持精度，禁止 double 序列化）」+ §4 L168 的字段清单与正则原文。
- **时间类字段**统一 `$ref` 到 `$defs.iso8601_time`（必须带时区偏移）：§1 L25「时间一律 ISO-8601 带时区（业务统一 Asia/Shanghai，`+08:00`）；解析失败视为脏数据」。
- **payload 路由**用 `allOf` + `if/then`（`if event_type const X` → `then payload $ref X`），12 条分支一一对应；该形态是 `CanonicalEventSchemaParityTest` 逐条断言的形状，且不需要引入校验器依赖即可人工核对。
- **`price ≥ cost`、`total_amount = Σ item.amount`、`order_paid.amount = order_created.total_amount`、`refund_completed.amount ≤ 已付金额`** 属跨字段/跨事件约束，draft 2020-12 的本地关键字无法表达，写在 `description` 里作为运行期校验要求（§2.2 L57、§2.4 L78、§4 L166-L167）。
- **`stock_reserved`/`stock_released` 的 `order_id`** 按 §2.9 L138「条件（预留时必填，释放时如有）」处理为**不列入 `required`**，条件写在 `description` 与 `x-conditional` 中（未擅自改成 `if/then` 强制）。

## 6. 采集清单与生成器制品清单的建模依据

- `ingestion-manifest.v1` **不是从指导书表格推导**，而是从真实产物 + 写出代码反推：`landing/manifests/30.json`（15 个顶层键全部进 `required`）+ `IngestionService.buildManifest`（L167-L192）与 `runOne`（L68-L165）。`files[]` 的 5 个键与真实观测一致，且**不约束键顺序**（`Map.of` 迭代顺序不稳定）。`checksum` 用 `^[0-9a-f]{1,8}$`，因为它是 `Long.toHexString(CRC32)`，不补前导零（真实观测既有 `"0"` 也有 `"71bbde63"`）。
- `generation-artifact-manifest.v1` **严格限定**在 §4.2 L135 的 8 个字段（`min/max_event_time` 展开为 `min_event_time`/`max_event_time`）+ §3.3 B L90 要求的 `synthetic: true`。字段可选性、`run_id` 类型、`uri`/`checksum` 格式均未冻结，已逐项标注；`additionalProperties: false` 让 §4.3 L149 的「期望隔离数」在契约冻结前被**显式拒绝**而不是静默接受（Q9）。
- `ingestion-manifest.v1` 的 **P1-05 加法扩展**（2026-09-11，`D-037`）：新增 `sourceCode`/`sourceId`/`profileVersion`/`mappingVersion` 四个**可选**字段。**为什么不进 `required`**：已落盘的 `landing/manifests/1-5.json`、`30.json` 里没有这些键，一旦列入 required，历史清单会瞬间全部非法——契约的加法变更必须让既有产物继续合法。**为什么 `source` 不复用**：实测真库 `ingestion_batch.source` 39 行全部是 `local-file`，它指的是**连接器类型**（采集方式），不是源编码；源身份必须另立字段，三个所有者（连接器类型 / 源身份 / 源实例连接器配置）各占各的键名，改名或复用都会同时破坏 15 个 required 键与已落盘清单。**`mappingVersion` 为什么可以是 `null`**：词汇映射在 P2 才落地，此阶段"未应用映射"是**事实**而不是缺值；写 `"0"`/`""`/`"v1"` 之类的占位值等于把"没做"伪装成"做了"。类型上写 `["string","null"]`，让"没有映射"这件事在 schema 层就是合法的、可判别的。

## 7. 待冻结 / 待决策（按 V2.1 §9.1 交由总控登记契约任务）

1. **Q1 信封是否允许多余字段**：来源未规定（§1 L28 只覆盖 payload）。本目录取「允许」（`additionalProperties: true` + `x-unspecified`）。需决定：保持宽松，还是收紧为 `false` 并在 §1 补一句信封字段封闭。
2. **Q2 ID 形态**：§1 L27 要求 ID 为数值字符串、`event_id`/`trace_id` 为 UUID 字符串；真实夹具 `landing/events/r9-m1-123006.jsonl` 用的是 `golden-evt-001`/`golden-trace-001`，且 `refund_id=R-1003-A`、`payment_id=P-1001` 非数值字符串，却都被采集层接受。本目录对所有 ID 只约束 `string`。需决定：补 UUID/数值正则并修夹具，还是修订 §1 L27。
3. **Q3 `source_system` 与 `synthetic`**：§1 L16 固定 `mock-mall`（对账测试也要求 `const`），而 §3.3 B L90 要求文件模式「必须显示 `synthetic=true`」；信封只有 8 个字段，放不下该标记，本目录把它放在生成器制品清单上。需决定：(a) 确认该落点，文件模式事件仍写 `source_system=mock-mall`；或 (b) 允许生成器使用另一个 `source_system` 值（等于改契约，需升版本）。**补记（2026-09-12，M1-5 收口同步）**：选项 (a) 的「落点」部分已被 V2.3 L675 追认（`synthetic=true` 放 generation run / artifact manifest / 验收元数据，不进 8 字段信封）；**仍未决**的是 `source_system` 的取值（文件模式事件是否仍写 `mock-mall`）⇒ 本条降级为「仅 `source_system` 取值待决」。
4. **Q4 枚举漂移（真实数据 vs §2 表格）**：
   - `channel = "web"`：夹具 6 行（L12/L13/L14/L34/L35/L36）使用 `web`，不在 §2.3 L69 的 `app/pc/h5` 内；采集层只校验 `behavior_type`（`EventContractValidator` L75-L80），因此这 6 行**被判为干净并被接受**。
   - `behavior_type = "purchase"`（L38）与 `age_group = "45-54"`（L54，该行同时 `schema_version="2.0"`）：均**已被正确隔离**，属隔离区正常表现，不构成漏洞，但说明生成器的场景取值超出了枚举，需统一。
   - `stock_changed.change_type = "restock"`（L52）：不在 §2.10 L146 的 `inbound/outbound/adjust` 内，但 `stock_changed` 只校验 `product_id`（L100-L101），因此该行**被判为干净并被接受**。
   需决定：扩枚举（升 1.1）还是修数据/收紧校验器。
5. **Q5 `order_created.items` 的实际类型**：§2.4 L77 为数组，但 6 行 `order_created` 全部把 `items` 写成**转义后的 JSON 字符串**（如 L18 `"[{\"product_id\":\"2\",\"quantity\":2,...}]"`）。校验器只判 `items` 是否存在且非空白（L106），字符串**照样通过**（`findBadAmount` 只遍历数组型 `items`，L124-L134）。需决定：修数据侧（商城/生成器写数组）还是契约承认字符串形态。
6. **Q6 「必填」在采集层的执行强度（本目录最重要的一条差距）**：`EventContractValidator.missingPayloadField`（L89-L111）只校验各类型的**子集**（例如 `order_cancelled` 只要 `order_id/user_id/reason`；`stock_*` 只要 `product_id`），而 §1 L28 说「缺失必需字段视为脏数据」、§2 各表把时间/status 字段标为必填。建立时实测：**按 `EventContractValidator` 的全部规则逐条复刻**（Python 复现，未运行 Java/Maven）回放夹具 55 行，得到**接受 51 / 隔离 4**，与 `landing/manifests/30.json` 的 `acceptedRecords=51 / quarantinedRecords=4` 完全一致（隔离的 4 行是 L38 非法 `behavior_type`、L53 JSON 不可解析、L54 未支持 `schema_version="2.0"`、L55 缺 `event_id`）；而用本目录的 `canonical-event.v1` 校验同一文件只有 **26 行通过 / 29 行不通过**。即：**采集层接受的 51 行里约 25 行按契约是脏数据**（缺 `status`/`created_at`/`paid_at`/`cancelled_at`/`completed_at`、`items` 字符串、`channel=web`）。需决定：把校验器补齐到契约（会让这批 golden 数据大批转隔离，影响既有指标口径），还是把契约的必填降级（需说明哪些字段可空）。**在此决定之前，不得以任一侧为唯一真值。**
7. **Q7 平台清单 `batchId` 类型漂移**：`landing/manifests/1.json`–`5.json` 为字符串 `"1"`…`"5"`，`6.json`–`30.json` 为数字。本 schema 以任务指定的 `30.json` 为准取 `integer`。需决定：是否重生成/迁移历史清单，或让 schema 接受两种类型。
8. **Q8 采集清单是否允许多余键**：来源未规定，本目录取「允许」并标 `x-unspecified`。需决定：是否收紧为封闭集合（会影响未来连接器写扩展字段）。
9. **Q9 「期望隔离数」字段缺失**：V2.3 §4.3 L295 要求「在 manifest 中记录期望隔离数」，但 V2.3 §4.2 L281 的 `generation_artifact` 字段清单里没有它。本目录不擅自加字段（`additionalProperties: false`）。需决定：字段名与所属对象（制品清单或运行报告），然后升契约版本。
10. **Q10 制品轮转与运行级清单**：V2.3 §4.1 L133 `Optional<Artifact> rotateIfNeeded()` 意味着一次运行可能有多个制品文件，而 V2.3 §4.2 L281 只有单制品字段。本目录描述「单文件一份清单」。需决定：是否需要运行级清单（含制品数组）及其字段。
11. **Q11 生成器命名与类型未冻结**：V2.3 §4.2 L280 写作 `started/finished`、L279 写作 `start/end`，均未给出完整键名（本目录按字面取 `started`/`finished`/`start`/`end`）；`run_id`（V2.3 §4.2 L280）与 `runId`（V2.3 §4.4 L299）命名不一致；`plan_id`/`run_id`/`version` 的序列化类型未规定。需决定：统一命名与类型并冻结。
12. **Q12 生成器 API 的非字段约定**：HTTP 成功状态码（本契约按 200 记录并标 `x-unspecified`）、错误响应体形状、`PUT /api/v1/targets` 是否应为 `PUT /api/v1/targets/{id}`、`/targets/{id}/test` 是否带 `/api/v1` 前缀、`GET /generation-runs/{id}` 的「进度」字段名与口径。需决定后补齐 `generator-api.v1`。
13. **Q13 生成器制品 `checksum` 与 `uri` 形态**：算法/编码/URI 基准目录未规定；是否与采集清单的 CRC32 十六进制一致也未规定（本契约明确「不得默认相同」）。
14. **Q14 采集校验器接受数字型金额**：`EventContractValidator.findBadAmount`（L120-L122）允许金额字段是 JSON number，而 §1 L26 要求「一律十进制字符串」。本 schema 只接受字符串。需决定：收紧校验器还是修订 §1 L26。
15. **Q15 生成器 5 张表的 DDL**：M1-5 范围只含 JSON Schema / OpenAPI / 清单 Schema，故本目录**不含** `generator_target`/`generation_plan`/`generation_run`/`generation_artifact`/`generation_event_stat` 的 DDL。若要求把表结构也纳入中立契约，需要新的契约任务（并明确 Flyway 版本号 Owner）。

## 8. 本目录的校验方式（每个程序自查 + 建立时的实测证据）

- **结构对账（平台侧，已有）**：`analytics-server/platform-common/src/test/java/.../CanonicalEventSchemaParityTest.java` 读取 `contract-specs/schemas/canonical-event.v1.schema.json`，断言：信封 `properties` 与 `required` 都等于 `EventEnvelope` 的 8 个 `@JsonProperty`；`event_type.enum` 等于 `EventContract.EVENT_TYPES`；`schema_version.const`/`source_system.const` 等于对应常量；`$defs` 覆盖 12 类且 `allOf` 路由完整；文件中出现 `EventContract.AMOUNT_PATTERN`。**M1-5 禁止运行 Maven，故该测试本次未执行**；作者改用等价的 PowerShell 断言逐条模拟，9 项断言全部为 True：`properties`/`required` 等于 8 字段集、`event_type.enum` 等于 12 类、`schema_version.const="1.0"`、`source_system.const="mock-mall"`、`$defs` 覆盖 12 类、`allOf` 恰好路由 12 类且每条 `$ref` 指向同名 `$defs`、解析后的 `pattern` 值等于 `EventContract.AMOUNT_PATTERN`。（对账测试用**解析后的** JSON 文本值比较，而非原始文件字符串，故文件中写 `"^\\d+(\\.\\d{1,2})?$"` 是正确的。）
- **建立时实测（本会话，仅用 PowerShell + 本地 Python 工具，未改任何其他文件、未启动服务）**：
  - 3 个 JSON 文件用 `Get-Content -Raw | ConvertFrom-Json` 解析通过；
  - 3 个 JSON 文件用 `jsonschema`（本地 4.26.0）做 draft 2020-12 `check_schema` 通过；
  - `landing/manifests/30.json` 按 `ingestion-manifest.v1` 校验通过；
  - `landing/events/r9-m1-123006.jsonl` 按 `canonical-event.v1` 逐行校验，差异行已作为 Q2/Q4/Q5/Q6 的实证；
  - `openapi/generator-api.v1.yaml` 用 PyYAML（本地 6.0.3）解析通过（未做 OpenAPI 语义校验器校验，仓库无该依赖）。
  - 上述 Python 工具仅用于建立时的本地校验，**未引入为项目依赖**（构建保持离线可用，见对账测试注释 L33-L34）。
- **消费方最小自校验建议**：商城与生成器在写事件前用本 schema 校验每个信封（尤其是 `required` 与 `event_type` 枚举）；平台在写采集清单前用 `ingestion-manifest.v1` 校验；生成器在写制品清单前用 `generation-artifact-manifest.v1` 校验。

## 9. 有意不放在本目录的内容（范围声明）

- **V2.1 §5.2 元数据/映射相关 schema**（L172-L182 的 `source_instance`、`source_connector`、`schema_mapping`、`source_manifest`、`ingestion_batch_file`、`file_checkpoint` 的表与映射规格）：属 M2 连接器插件体系工作，尚未冻结字段语义，放进来必然靠猜。
- **源登记 `analytics_meta.source_registry` 与源画像 `analytics-server/source-profiles/<source>.v1.json`**：**明确不进入本目录**（总控裁决 `D-036`，2026-09-11，并在 G0 契约门复核）。理由：本目录的边界是"三个程序**之间**交换的语言无关产物"（§1/§3 逐字），而这两者是 **`analytics-platform` 自身的元数据**——自己的库表、自己读的配置文件，**没有任何第二个程序读它们** ⇒ 放进来只会制造"看似跨程序契约、实为单程序内部结构"的假耦合（正是本目录要消除的形态）。因此：源登记的契约由迁移 `V16__source_registry.sql` + `D-034`/`D-035` 承担；源画像的机器可读 JSON Schema 由 **P3-01 作为平台自有制品**落位（`analytics-server/source-profiles/source-profile.v1.schema.json`），同样不进本目录。**触发迁入的条件**：出现真正的第二个消费者（例：独立的数据源管理服务、第三方商城自检工具，或生成器的 `MALL_API` 需要读画像做映射）时，按 §3 走"总控登记契约任务 → 冻结"的流程迁入，不提前放。
- **平台入库清单 `source_manifest` 的 JSON 契约**：与采集侧 `ingestion-manifest.v1` 的关系（是同一份文件还是入库后的另一份）未冻结，故只登记存在性（Q 未列，属 M2 范围）。
- **ADS / 分析视图模型契约**：归 `docs/contracts/analysis-viewmodel-r7-4.md`，R7-4 工作项范围，本目录不复制。
- **指标字典与血缘**：归 `docs/contracts/metric-dictionary.md`、`docs/contracts/metric-lineage.md`。
- **证据与安全决策契约**：归 `docs/contracts/r8-evidence-security-decision.md`。
- **事件类型 → ODS 主题表路由**：已由 `EventContract.ODS_TABLE_BY_TYPE`（Java）与 scala 侧 `OdsLoadSql.eventTypeToTable` 双向锁定，本目录不重复登记（重复登记反而会与实现漂移）。
- **商城 outbox 表 DDL 与商城业务表 DDL**：属 `reference-mall` 内部实现，按 §3.1 L64 不进入中立契约；仅 `event_outbox`（`mall-simulator/src/main/resources/db/migration/V1__init_mall.sql` L116-L129）作为事件来源的事实记录在此。
- **生成器 5 张表的 DDL**（Q15）与**第二个 `MallTargetAdapter` 的字段夹具**（M1-9）。
- **滚动日志 / Landing 目录布局契约**：`landing/events`、`landing/accepted/{batchId}`、`landing/quarantine/{batchId}`、`landing/manifests/{batchId}.json` 目前只在 `IngestionService` 与 `RuntimeProfile.landingUri` 中体现，尚无独立契约文件；是否纳入本目录待总控决定。

## 11. 补记：M1-5 收口同步（2026-09-12）

**为什么有本节**：指导书 V2.3 §4.1.1 之 11【新增要求】原文——「`contract-specs/openapi/generator-api.v1.yaml`
现在把这一区域标为 `x-unspecified`，待 M1-5 收口时按本节同步（含 `TargetCheckResult` 的空对象声明与
`adapter_type` 取值集合）」。本节记录该同步做了什么、依据是什么、还有什么没做。

**同步清单（只增不删；原文若被废止，均以「以此为准作废」+ 日期标注）**：

| # | 对象 | 同步前（`VERSION` 1.2.0） | 同步后（`VERSION` 1.3.0） | 依据 |
|---|---|---|---|---|
| 1 | `TargetCheckResult`（`generator-api.v1`） | 空对象 + `x-unspecified`，明写"指导书没有给出它的任何字段，不发明检查项字段" | 按 DTO 最小字段表声明 `targetId`/`reachable`/`detail`/`capabilities` 四个 properties；**不声明 `required`**（来源未给必填性，不发明约束） | V2.3 §4.1.1.3 L240；实现侧 `adapter/TargetCheckResult.java`（同一 schema 提交） |
| 2 | `TargetCapabilities` | **不存在**（`generator_target.capabilities` 只写"结构未冻结"） | 新增 schema：`verdicts`（能力名→三态）+ `declared` | V2.3 §4.1.1.3 L241、§4.1.1.2 之 5（三态、缺键⇒`UNDETERMINED`） |
| 3 | `adapter_type` 取值集合 | "取值集合未冻结（§4.1 L126 只说明第一版实现…）" | "**按契约不冻结**（V2.3 §4.1.1.1 L158 明示）"，第一版实现锚点改 V2.3 §4.1 L139 | 同上；语义由"来源漏规定"改成"契约明确留开"，两者不是一回事 |
| 4 | `generator_target.capabilities` 列 | "能力集合（对应 §4.1 L108 `capabilities()`）。结构未冻结" | "能力**台账列**，**不参与任何能力判定**"，判定取自 `capabilities(config)` 声明与 `test(config)` 实测 | V2.3 §4.1.1.1 L216-218【追认】 |
| 5 | `credential_ref` | "凭据只存引用；引用形态未冻结" | 追加 V2.3 §4.1.1.2 之 4 的凭据纪律（日志/流水/异常里只允许出现引用名） | V2.3 §4.1.1.2 之 4 |
| 6 | `generation-artifact-manifest.v1` 的 `x-synthetic-marker` | "未规定该标记落在制品清单、运行报告还是响应体；需契约任务确认" | **落点已被追认**（制品清单是三个载体之一），并要求三处载体都写；是否已满足留给生成器侧取证 | V2.3 L675 |
| 7 | 全部 `§x.y Lzzz` 锚点 | 指向 V2.1/V2.2 行号 | 加 `V2.3 ` 前缀并改为 V2.3 行号（本文件 §4.1 区域 + 逐条复核过的 §4.2/§4.3/§4.4 锚点） | 指导书已迭代到 V2.3，裸行号会指向错误段落 |

**勘误（2026-09-12 实测：拿 V2.1 原文逐条比对，不是推断）**：本目录与相邻契约文件里的 **V2.1 锚点全部比实际行号少一行**。

| 旧锚点（原文写法） | V2.1 该行**实际**内容 | V2.1 正确行号 | V2.3 行号 |
|---|---|---|---|
| §4.1 L107 `test(TargetConfig)` | `interface MallTargetAdapter {` | L108 | L120 |
| §4.1 L108 `capabilities()` | `TargetCheckResult test(TargetConfig config);` | L109 | L121 |
| §4.1 L126 「第一版实现 `ReferenceMallHttpAdapter`」 | 空行（该句在 L127） | L127 | L139 |
| §4.1 L118-L123（EventSink 四方法） | EventSink 块实际在 L119-L124 | L119-L124 | L131-L136 |
| §4.2 L132 `generator_target` | 表格分隔行 `\|---|---|---\|` | L133 | L278 |
| §4.2 L134 `generation_run` 字段 | `generation_plan` 行 | L135 | L280 |
| §4.2 L135 `generation_artifact` 字段 | `generation_run` 行 | L136 | L281 |
| §4.2 L138 `generation_event_stat` 字段 | 空行（该行在 L137） | L137 | L282 |
| §4.3 L149 异常样本 / 期望隔离数 | 「可复现」条目 | L150 | L295 |
| §4.4 L153 响应写作 `runId` | 空行（该句在 L154） | L154 | L299 |
| §4.4 L158 连通性检查 | `GET /api/v1/scenarios` | L159 | L304 |

**规律（残留项机械重算的依据）**：V2.1 锚点 = 实际行号 **−1**；V2.3 相对 V2.1 的偏移为 §4.1 **+12**、§4.2/§4.3/§4.4 **+145**（后三者同偏移，因为 V2.3 在 §4.1 之后插入了 §4.1.1）。据此可对残留裸锚点机械重算，但**本轮未做** ⇒ 不得声称全部锚点已校正。事实记录：**F-29**。

**未做 / 未取证（不得当作已完成）**：

- **未逐条重核全部锚点（已量化）**：机械扫描（脚本第 7 步，判据＝未带 `V2.3 `/`V2.1 ` 前缀的 `§x.y Lzzz`）在 6 个文件中命中 **258 处**裸锚点（同步后基线 247 处；另 11 处是本勘误表刻意保留的旧锚点原文，用于对照）；本轮只核了 `generator-api.v1` 的 §4.1 区域与 §4.2/§4.3/§4.4 已读到段落、README §7 的 Q9/Q10/Q11、制品清单 schema 的 `x-evidence`（另有 11 处已在上面勘误表中给出正确行号）⇒ 残留项 **R-M1-5-1**（看板 M1-5 行）；重算规则见上（+1 与 +12/+145）。
- **Q12 未被本节解决**：§4.1.1 覆盖的是 SPI/DTO，不覆盖 REST 约定（成功状态码、错误体形状、`/targets` 的动词与前缀、进度字段名）⇒ Q12 保持待决。
- **新增 Q16**：V2.3 §4.1.1.2 之 6【新增要求】要求运行流水/报告的路由取自 `operationRoutes(config)`，但**生成器对外 API 与页面尚未规定如何暴露该路由信息**（`GET /generation-runs/{id}` 无对应字段）⇒ 需决定：是否在运行详情/产物中增加路由字段（会改本 OpenAPI）。
- **平台侧 `IngestionManifestSourceSchemaTest` 类注释里的"目录级 `1.2.0`"未改**：那是 P1-05 加法扩展发生时的版本，属历史陈述；本轮升到 1.3.0 不改动测试文件（避免跨模块无谓改动），如需同步由平台侧泳道带出。
- **`VERSION` 升版未同步 `analytics-server` 侧任何断言**：已实测全仓唯一命中是上述注释一处（`contract-specs 1.2.0`/`"1.2.0"` 检索），故升版不影响 E2。
## 10. 冻结指纹（文件级，2026-09-11 总控复核时实测）

目的：让"证据对应的是哪一版契约"可被**独立复核**——引用本目录任何结论前先核对指纹；指纹不符 ⇒ 该结论应按新版本重取。

| 文件 | SHA-256 | 说明 |
|---|---|---|
| `specs/warehouse-namespace.v1.json` | `463D9DC3503D563D8DD8EB844C1AB5EDE9AAEE073251F07957D410C13911AE5A` | **已冻结**版（`status=FROZEN-2026-09-11`）。冻结前哈希 `A3E712B2…` 出自 P1-04 轮实测记录，本轮**无法再复核**（该内容已不存在）；冻结改动经 diff 确认为**仅 `status` 一个字符串**，故 P1-04 的 22 向量实测结论按"仅 status 变化"继承。**2026-09-12 起由 v2 取代（`D-072`），本行哈希仍有效（v1 未被改动）** |
| `specs/warehouse-namespace.v2.json` | `CD79BBA1688E333B8E378D079AEC2959C5B803A5D27F38A9916392F426C17B28`（6,156 B / 84 行） | **现行权威版**（`status=FROZEN-2026-09-12`，`supersedes: warehouse-namespace.v1.json`）。与 v1 的差异经逐行 diff 限定为：`version`/`status`/`owner` 三行改写、`createdAt` 后新增 `supersedes`/`revisedAt`/`revisionReason` 三行、`sourceOfTruth` 与 `compatibility.note` 两行改写、1 处向量 `why` 散文改写；`rule`（除 `sourceOfTruth`）与 22 向量三元组经 json 深比较**逐字段等价** |
| `schemas/ingestion-manifest.v1.schema.json` | `0993E1474228E2EE895E5804D20212DBF1662EF74C22F7EB2A4FAF2E2EF93603` | P1-05 加法扩展后（19 属性 / `required` 仍 15） |
| `schemas/canonical-event.v1.schema.json` | 未冻结，见 §7 | B-06/Q6 未决，指纹待冻结时同表登记 |
| `schemas/generation-artifact-manifest.v1.schema.json` | 未冻结，见 §7 | 同上 |
| `README.md` | **不登记自身**（自指：把本文件的哈希写进本文件，写入动作本身就会让该哈希失效） | 需核对时现算：`Get-FileHash contract-specs/README.md -Algorithm SHA256`，并与该次提交比对 |
| `VERSION` | `C9E89F9DC5A13DD44A5F75BE0F69F7239723875F4685B11E93AAB09B6DDBC4A0` | 内容 `contract-specs 1.2.0`（本表登记的是 2026-09-11 20:12 写入、提交 `332b52b` 20:14:08 时的值） |
| `VERSION`（**当前值**，2026-09-12 CT-0 重登记） | `B6BAB8E0547C6BC0EB7005128E177B891E32534FA3522D54B079E7AEC384EC89` | 内容 `contract-specs 1.3.0`（21 B）。上一行 `C9E89F9D…`／内容 `1.2.0` 是 2026-09-11 20:12 的**历史登记，原文保留不改**；变化发生在 M1-5 收口同步升版（§11，提交 `fee9dbc`）。引用**当前态**结论用本行，引用**历史轮次**结论用上一行 |
| `VERSION`（**当前值**，2026-09-12 P2-07 重登记） | `BA01EE34366FAC52AD8EE7709EF44F25117E0662F3D7E3D0CE1FF9E3B3407A71` | 内容 `contract-specs 2.0.0`（21 B）。上一行 `B6BAB8E0…`／内容 `1.3.0` 是 CT-0 时点的**历史登记，原文保留不改**；本次变化是 P2-07 的**破坏性契约变更**（`D-072`，见 §13）。引用**当前态**结论用本行 |
| `specs/surrogate-key.v1.json` | `14385528205886CB3D90E89C4207054234F332D8946662F90E8C1EE8ECF75320` | `DRAFT`（2026-09-12 落盘）：23 向量由总控**独立复算**（Python）并与泳道 PowerShell 读数逐条对账；本文件内**同时**给出 `digestPrefixHex16` 与 `keyHex16`/`keyBigint`，消除“摘要前 8 字节”与“清符号位后的键值”的列语义歧义 |
| `VERSION`（**当前值**，2026-09-12 surrogate-key 落盘后重登记） | `9784177F34E4B4A248F7D3840D73E294C35EE46ADCB76F429CAA87D4D8E0E30D` | 内容 `contract-specs 2.1.0`（21 B）。加法变更（新增一个 DRAFT 制品）→ minor 递增；上一行 `BA01EE34…`／`2.0.0` 是 P2-07 契约变更时点的历史登记，原文保留不改 |

**口径**：指纹是**复核辅助**而非契约的一部分——`VERSION` 才是契约的版本所有者（改契约必须同时升 `VERSION`，指纹随内容自然变化，不单独维护"指纹版本"）。

**本表自检（2026-09-11 20:22，总控现场复核）**：对表中三个已冻结指纹逐个重算 `Get-FileHash -Algorithm SHA256` 并与登记值比对 ⇒ `specs/warehouse-namespace.v1.json` `463D9DC3…` ✓、`schemas/ingestion-manifest.v1.schema.json` `0993E147…` ✓、`VERSION` `C9E89F9D…`（内容 `contract-specs 1.2.0`，21 B）✓，**三项全部相符、未漂移**。⇒ 冻结后没有任何人改动过这些制品；引用其结论时按登记版次有效。未冻结的四个制品（`canonical-event.v1` 等）不在本表登记，其状态见 §7。

## 12. CT-0 / F-31 勘误落地：当前态版本串 `1.2.0` → `1.3.0`（2026-09-12）

**为什么有本节**：本文件 §1 的状态行与 §3 的目录表把**当前态**版本写作 `1.2.0`，而 `VERSION` 早已是 `1.3.0`（M1-5 收口同步，见 §11）。该滞后登记为事实 **F-31**、契约任务 **CT-0**（`docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md` §8）。本节记录本次勘误**改了什么、刻意保留了什么、按什么口径复核**。

**写前实测（2026-09-12，总控现场）**：

- `VERSION` 内容 `contract-specs 1.3.0`，21 B，sha256 `B6BAB8E0547C6BC0EB7005128E177B891E32534FA3522D54B079E7AEC384EC89`；
- 本文件写前 sha256 `07D2F02E31CEFB28665B32C00B7D7E554621D4B4991EB64C617601B7455E33BB`，34,496 B，188 行、CR=0——与既有登记值相符 ⇒ 写前不存在未登记的改动；
- 滞后点两处：§1 状态行与 §3 目录表的 `VERSION` 行，均写 `1.2.0`。

**改了什么（两处正文，均"命中且仅命中 1 次"）**：

| # | 位置 | 改前 | 改后 |
|---|---|---|---|
| 1 | §1 状态行（第 3 行） | `版本：`1.2.0`（… `1.1.0 → 1.2.0` 对应 `ingestion-manifest.v1` 的 P1-05 加法扩展，均按 §3 的目录级版本规则）` | `版本：`1.3.0`（… 前两段不变 …，`1.2.0 → 1.3.0` 对应 M1-5 收口同步（见 §11），均按 §3 的目录级版本规则）` |
| 2 | §3 目录表 `VERSION` 行（第 56 行） | `` `1.2.0` `` | `` `1.3.0` `` |

**新增一处登记（不改历史行）**：§10 冻结指纹表中 `VERSION` 的 `C9E89F9D…`／内容 `1.2.0` 行**原文保留**，其后**新增**「当前值」行（`B6BAB8E0…`／内容 `1.3.0`／21 B），并注明历史行是 2026-09-11 20:12 时点的读数。

**刻意保留不改（历史陈述，按"历史证据文本不改写"纪律原文留存）**：

- §10 表头「同步前（`VERSION` 1.2.0）｜同步后（`VERSION` 1.3.0）」——那是 M1-5 同步动作的前后对照；
- §11 中「平台侧 `IngestionManifestSourceSchemaTest` 类注释里的『目录级 `1.2.0`』未改」与「`VERSION` 升版未同步 `analytics-server` 侧任何断言」——那是当轮实测与决定；
- §10「本表自检（2026-09-11 20:22）」中 `VERSION C9E89F9D…` 的 ✓——那是该时点的读数，不是当前态声明。

**未改（本次不动）**：四个 `DRAFT` 制品（`canonical-event.v1`、`generation-artifact-manifest.v1` 等）的状态与内容；`VERSION` 本身（**勘误级，不升版**）；任何 OpenAPI／schema 制品；`analytics-server` 侧任何文件。

**写入窗口纪律（F-32 残因下的做法）**：本次采用「暴露窗口最小化」——写前复核本文件指纹与登记值相符；四处改动逐条断言"命中且仅命中 1 次"；写后立即复核新指纹；并在同一次操作内提交。期间若检测到外部改写即中止。**残因如实登记**：看板 **F-32** 中本文件于 2026-09-12 11:53 被改写一事**仍未归因**；F-37 的"外部并发写者"说法对 33 个文件被删一事**已证伪**（系总控自身复核脚本的 helper 撞 PowerShell 别名 `rd`），两者不是同一件事。

**本节自检口径**：本文件哈希按 §10 的口径**不登记自身**（自指会让哈希失效）；复核命令 `Get-FileHash contract-specs/README.md -Algorithm SHA256` 并与本次提交比对。本节不改变任何制品的冻结状态：`specs/warehouse-namespace.v1.json` 仍 `FROZEN-2026-09-11`，其余四个仍 `DRAFT`。


## 13. P2-07 / `D-072` 落地：当前态版本串 `1.3.0` → `2.0.0`（2026-09-12）

**为什么有本节**：§12 记录的是 CT-0 勘误（`1.2.0 → 1.3.0`）。本轮 P2-07 按 §3 的目录级版本规则把一个**语义变更**落到契约上：数仓库名前缀的取值来源由 `runtime_profile.hive_database_prefix` 迁到源级 `source_registry.warehouse_prefix`，故**新建 `specs/warehouse-namespace.v2.json`**（`supersedes: warehouse-namespace.v1.json`）并升 `VERSION` 到 `2.0.0`。本节记录**改了什么、刻意保留了什么、按什么口径复核**。

### 13.1 改了什么（逐条可复核）

| # | 位置 | 改动 |
|---|---|---|
| 1 | 新增 `specs/warehouse-namespace.v2.json` | 由 v1 行级外科改写而来：`version` `v1→v2`、`status` `FROZEN-2026-09-11→FROZEN-2026-09-12`、`owner` 改指 P2-07、`createdAt` 后新增 `supersedes`/`revisedAt`/`revisionReason`、`sourceOfTruth` 与 `compatibility.note` 两行改写、1 处向量 `why` 散文改写 |
| 2 | `VERSION` | `contract-specs 1.3.0` → `contract-specs 2.0.0`（21 B，sha256 `BA01EE34366FAC52AD8EE7709EF44F25117E0662F3D7E3D0CE1FF9E3B3407A71`） |
| 3 | §1 状态行（第 3 行） | 冻结状态改指 v2（v1 标为历史冻结件）；版本述沿革追加 `1.3.0 → 2.0.0` 一段（**破坏性变更**） |
| 4 | §4 目录表 | `VERSION` 行 `1.3.0 → 2.0.0`；原 v1 规格行状态改为「已被 v2 取代（历史冻结件，不改）」，**新增 v2 行**（`FROZEN-2026-09-12`） |
| 5 | §4 冻结依据段之后 | **追加**「2026-09-12 更新」段：说明三层无载体的实测、机械等价性证明、两个消费方的引用点排期、`D-073` 只断读不删列、P2-07-b |
| 6 | §10 指纹表 | 新增 v2 行（`CD79BBA1688E333B8E378D079AEC2959C5B803A5D27F38A9916392F426C17B28`，6,156 B / 84 行）与 `VERSION` **当前值**行；v1 行说明追加「2026-09-12 起由 v2 取代，本行哈希仍有效」 |

### 13.2 机械等价性证明（本轮实测，非声明）

- `rule` 对象：v1 与 v2 **除 `sourceOfTruth` 外逐字段相等**（Python `json` 深比较，断言通过）；
- `vectors`：22 条，`input`/`expect`/`names` **三元组逐条相等**；
- `compatibility.observedDatabases` 相等；向量条数 22 = 22；
- v1 文件本体**未被改动**：本轮现算 sha256 `463D9DC3503D563D8DD8EB844C1AB5EDE9AAEE073251F07957D410C13911AE5A`，与 §10 登记值 `463D9DC3…` 相符。

⇒ §4 中 P1-04 的隔离真跑结论（`dw_b` 建 `dw_b_*` 且不建 `dw_*`；不传前缀与前者的表/分区/文件逐项一致 ⇒ 零数据迁移；非法前缀 `exit 64` 且 0 个库）**继续有效**，因其只依赖规则本体，而规则本体未变。

### 13.3 刻意保留（不改）

- `specs/warehouse-namespace.v1.json` **本体与其 §10 指纹行**（历史冻结件；其 `sourceOfTruth` 已被 v2 取代，但原文不改）；
- §11 的 `1.2.0 → 1.3.0` 对照表、§12 的 CT-0 勘误全节（那两节是**历史动作记录**，其中出现的 `1.3.0` 是当时读数）；
- 四个 `DRAFT` 制品的状态与内容；任何 OpenAPI／schema 制品。

### 13.4 引用点排期（本轮**未**改实施侧）

`contract-specs/specs/warehouse-namespace.v1.json` 在实施侧仍有 6 处引用（Java 4 处含**加载点** `WarehouseNamespaceContractTest.java:40`；Scala 2 处含加载点 `WarehouseNamespaceSpec.scala:177`）。Java 侧随 P2-07 实施改指 v2；Scala 侧改指 v2 **排在 P2-01 实施泳道让出 `spark-jobs/**` 之后**（登记为 P2-07-c）。⇒ 在此之前两侧读的是 v1/v2 两个文件名，但**规则本体逐字段相同，不构成行为分叉**；本节不声称任何一侧已按 v2 运行。

### 13.5 本节自检与未取证

- 自检：`VERSION` 与 v2 指纹均为**本轮现算值**；本文件哈希按 §10 口径**不登记自身**（`Get-FileHash contract-specs/README.md -Algorithm SHA256`，与该次提交比对）；v1 指纹现算相符（13.2 末条）。
- **未取证**：本轮**未运行**任何 Maven/测试（Java/Scala 侧仍加载 v1 ⇒ 运行它们不能证明 v2 侧行为）；v2 的 `rule` 未被任何实现读取过；`source_registry.warehouse_prefix` 列、V18 迁移、源级校验与 `forSource` 解析**均尚未实现**（属 P2-07 实施泳道，见 `docs/acceptance/p2-07-source-prefix-20260912/RULINGS-20260912.md`）。**不得**据本节声称"源级命名空间已生效"。
