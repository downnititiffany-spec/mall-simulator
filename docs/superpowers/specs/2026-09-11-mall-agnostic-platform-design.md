# 设计：分析平台的"商城无关化"改造（源画像 + 语义注册表 + 每源仓库命名空间）

- 日期：2026-09-11
- 状态：**已评审通过，进入 P1–P5 实施计划**（2026-09-11；当前实现基线 `076f5d2`）
- 范围：`analytics-server/**`（采集/数仓/指标/看板）、`synthetic-data-generator/**`（后续 W2）、`mall-simulator/**` + `mall-frontend/**`（后续 W1）
- 相关条款：实施指导书 V2.1 §3.4-4/5/6（程序独立性与"非硬编码单一商城"）、§2.1 权限序、§2.2 状态机与 E1–E5 证据
- 相关决策：D-002（唯一数据来源口径）、D-021（跨程序身份语义：契约字符串 id / 数仓 BIGINT）、D-022（数据源停机时的平台表现）
- 用户裁决（本轮，原文）：
  1. 「那商城这块还是自建吧,后续再慢慢把界面做的好看一些,商品种类多一些」
  2. 「分析系统不能和商城写死,必须是解耦的,因为这是一个工具系统,不可能只多一个模拟平台服务」
  3. 「我当前系统在现在,是以这个模拟的商城服务的,但是我不能只为这个商城界面服务,也就是说,后面我可能会让我的分析系统去服务其他的商城系统,为其他商城系统进行分析」
  4. 三档选择：**档 B**（配置驱动映射 + 原始 payload 保真 + 代理主键）、**甲**（固定分析骨架 + 语义/指标/维度注册表）、**甲**（每源一套仓库命名空间 + 指标层带 `source_system`）、实现路径 **方案一**（按 owner 分家 + P1–P5 分阶段）

---

## 1. 问题：今天把平台接到"另一个商城"会发生什么

平台的采集、数仓、指标三层都假设了"源就是 mock-mall 这一个词汇表"。审计（2026-09-11）确认了三类硬耦合：

| # | 层 | 硬耦合事实 | 位置（示例） | 接新商城的后果 |
|---|---|---|---|---|
| 1 | 采集 | 12 个事件类型白名单 + 5 个行为枚举白名单写死在 Java | `connection-ingestion/**/EventContractValidator.java` | 事件名对不上 → **整批进 quarantine，ODS 0 行** |
| 2 | 采集/ODS | `schema_version == "1.0"`、`source_system == "mock-mall"` 为常量 | `EventOdsLoadJob.scala`、`OdsLoadSql.scala` | 常量不匹配 → 记录被静默丢弃 |
| 3 | ODS 物理模型 | `payload_*` 逐字段物理列（4 张表、27 列）在 3 处独立定义 | `warehouse/ddl/00-ods.sql`、`OdsLoadSql.scala`、`LocalSchemaInitJob` | 新字段**静默丢弃**；缺字段 → 下游 join 全空 |
| 4 | DWD/DWS | 行为语义为 SQL 字面量（`'view'`、`'favorite'`…） | `TradeDwdJob.scala` 等 | 行为词不匹配 → **指标恒 0** |
| 5 | 身份 | `IdCodec` 只认"字母前缀+数字"，全部 id 列为 BIGINT | `IdCodec.scala`、各层 DDL | UUID/纯数字雪花主键 → **id 全 NULL，维度 join 全空** |
| 6 | 指标/发布 | `OVERVIEW_REQUIRED = {pv,uv,dau,order_count,sale_amount,net_sale_amount}` 为常量非空闸门 | `MetricPublishValidator.java` | 没有"浏览"概念的商城 → **发布直接失败** |
| 7 | 数仓命名 | `dw_ods/dw_dwd/dw_dim/dw_dws/dw_ads` 写死在 22 个文件、244 行；`RuntimeProfile.hiveDatabasePrefix` **存在但从未被消费** | `warehouse-pipeline/src/main/resources/**` | 换源不能换库 → 多源数据必然互污 |
| 8 | 词汇副本 | 同一份事件/行为词汇表在仓库里有 4–6 份独立副本（平台 Java、采集 Java、Scala、生成器、JSON Schema、商城 Java） | 多处 | 改一处要同步六处；任一处漏改即静默失真 |

结论：**"接一个新商城"今天等于改 6 处代码 + 4 份 DDL + 一堆 SQL 字面量**，与用户要求的"工具系统"定位冲突。

---

## 2. 目标与非目标

### 目标
- **G1 零代码接入**：接入一个词汇/字段/枚举/身份形状都不同的商城，只需"一份源画像 JSON + 一条源登记"，不改 Java/Scala 源码。
- **G2 多源并存且隔离**：平台可同时登记多个源；每源一套仓库命名空间（物理隔离）；指标层带 `source_system`，看板可按源切换与对比。
- **G3 ODS 保真**：原始 payload 原样落库（`payload_json`），新增字段**不需要任何 DDL 变更**；缺字段不再让 join 静默全空（改为显式 NULL 语义 + 可观测）。
- **G4 身份策略可配置**：契约层保持字符串 id（D-021），数仓层由"身份策略"决定代理键生成（支持 UUID/雪花/纯数字/字母+数字），代理键确定性、幂等。
- **G5 分析语义注册表化**：事件类型、行为/状态语义、指标定义、维度定义、发布闸门由注册表数据驱动；SQL 模板中**不出现**任何商城业务字面量（只出现占位符）。

### 非目标（明确不做，避免范围蔓延）
- **N1 不做"元数据驱动自动生成 SQL"的分析引擎**（丙方案）。模板结构仍是代码，只有"表名/字段名/语义名/表达式"来自配置。
- **N2 本期不实现 JDBC/HTTP 直连适配器**（档 C）。只保留接入方式抽象与占位（`ingest_mode=FILE` 一个实现）。
- **N3 不改商城业务逻辑与埋点语义**，不要求商城提供新的抽取接口（D-002 唯一来源口径保持）。
- **N4 不做跨源口径自动对齐**：两个商城同名字段不自动合并，跨源对比只在指标层按 `source_system` 并列。
- **N5 不做商城 UI 美化**（另立 W1，见 §10）。
- **N6 不把任何开源商城源码合并进分析平台**。后续接入开源商城时，它仍是独立外部数据源，只新增 Adapter/Profile/映射与验收，不改变平台核心。

---

## 3. 架构总览

### 3.1 三类"真相"的 owner（方案一：按 owner 分家）

| 真相 | 内容 | 唯一 owner（载体） | 读取方 |
|---|---|---|---|
| 源运行时状态 | 哪个源激活、landing 位置、仓库库名前缀、接入方式 | **DB**：`analytics_meta.source_registry` + `runtime_profile.source_id` | 平台 API、看板、流水线编排 |
| 源词汇映射 | 事件类型映射、字段映射、枚举语义、身份策略、时间解析策略 | **文件**：`analytics-server/source-profiles/<source>.v1.json` | Java 采集、Scala 作业（读同一份） |
| 分析语义 | 行为/状态语义标签、指标定义与口径、维度定义、发布闸门 | **DB**：`semantic_registry`、`dimension_registry`、`metric_definition`（增源列） | Spark SQL 模板、指标发布、看板、AI 问数 |

规则：**一份真相只有一个 owner**；不做"文件 + DB 双写"（避免漂移）。

### 3.2 数据流（改造后）

```
源 A（模拟商城 8090）──Outbox──▶ landing/<source>/events/*.jsonl
源 B（异构 fixture / 将来的真实商城）──▶ landing/<source>/events/*.jsonl
                                        │
                    IngestionService（读 source_registry + source-profiles/<source>.v1.json）
                    ① 按画像做契约校验（未知事件类型 → quarantine + 原因）
                    ② payload 原样落 ODS：<prefix>_ods.ods_*（payload_json + 少量接入元数据列）
                    ③ 记录 checkpoint（按源）
                                        │
   Spark：<prefix>_ods ─▶ <prefix>_dwd（按画像投影字段 + 代理键）─▶ <prefix>_dim ─▶ <prefix>_dws ─▶ <prefix>_ads
                                        │
              MetricPublishValidator（闸门按源）─▶ analytics_metric.metric_value（带 source_system）
                                        │
                     看板 /metrics/*?source=<code>（按源切换与对比）
```

关键不变量：
- **I1** 采集与 SQL 模板中不含任何商城业务字面量（由 P3 的自动化检查断言：`grep` 白名单）。
- **I2** 每个源一套库前缀；跨源只在指标层并列，不在仓库层混合。
- **I3** 契约层 id 是字符串、数仓层是代理键 BIGINT（延续 D-021），映射可配。
- **I4** 接入新源 = profile JSON + 源登记，**不产生 Java/Scala 改动**（由 A1 断言）。

---

## 4. 契约与配置格式

### 4.1 源登记（DB 新表：`analytics_meta.source_registry`）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK | 代理键 |
| `source_code` | VARCHAR(64) UNIQUE | 源业务键（写入 ODS 的 `source_system`），如 `mock-mall`、`fixture-b` |
| `display_name` | VARCHAR(128) | 看板展示名 |
| `ingest_mode` | VARCHAR(16) | `FILE`（本期唯一实现）/ 预留 `JDBC`、`HTTP` |
| `profile_path` | VARCHAR(255) | 源画像文件相对路径（§4.2） |
| `status` | VARCHAR(16) | `ACTIVE` / `PAUSED` |
| `created_at` / `updated_at` | DATETIME(3) | 审计 |

`runtime_profile` 增列 `source_id BIGINT`（FK，可空以兼容存量行，迁移时回填为 `mock-mall`）。分工：`source_registry` 管"这个源是谁、词汇表在哪"；`runtime_profile` 继续管"这次运行用什么参数"（landingUri、`hiveDatabasePrefix`、激活状态）。
> 备选（未采纳）：把 `source_registry` 折进 `runtime_profile`。放弃原因是"源身份"与"运行参数"变更频率不同、生命周期不同（一个源可有多次运行参数调整），且看板需要一张稳定的源清单表。

### 4.2 源画像（版本化文件：`analytics-server/source-profiles/<source>.v1.json`）

```jsonc
{
  "profileVersion": "1.0",
  "sourceCode": "fixture-b",
  "canonical": { "schemaVersion": "1.0" },     // 该源产出/被接受的契约版本
  "eventTypeMapping": {                         // 源事件名 → 规范事件类型
    "product_viewed":   "view",
    "add_to_cart":      "cart_add",
    "payment_success":  "order_paid",
    "contract_signed":  "order_created"
  },
  "fieldMapping": {                             // 源字段 → 规范字段（规范字段是骨架，见 §4.4）
    "buyer_id":     "user_id",
    "item_id":      "product_id",
    "pay_money":    "amount",
    "created_at":   "event_time",
    "coupon_code":  "@keep"                     // 规范骨架没有的字段：保留在 payload_json，不参与标准指标
  },
  "enumSemantics": {                            // 源枚举取值 → 平台语义标签（§4.3）
    "behavior": { "wishlist": "favorite", "browse": "view", "purchased": "purchase" },
    "orderStatus": { "SIGNED": "PAID", "CANCELLED": "CANCELLED" }
  },
  "identityPolicy": {                           // 身份与主键策略（§4.5）
    "user":    { "rawField": "buyer_id",  "shape": "UUID",           "surrogate": "HASH64" },
    "product": { "rawField": "item_id",   "shape": "PREFIX_NUMERIC", "surrogate": "HASH64" },
    "order":   { "rawField": "contract_no","shape": "ANY",           "surrogate": "HASH64" }
  },
  "timePolicy": {
    "field": "created_at",
    "formats": ["ISO_OFFSET_DATE_TIME", "EPOCH_MILLIS", "yyyy-MM-dd HH:mm:ss"]
  },
  "quarantinePolicy": { "unknownEventType": "QUARANTINE", "unknownField": "KEEP_IN_PAYLOAD" }
}
```

约束：
- 缺失的映射项 = "该源没有这个语义"（不是默认值），平台按"缺语义"处理而不是猜。
- `"@keep"` 表示字段不映射到骨架，仅保留在 `payload_json`。
- 画像文件受**版本化与评审**（进 git）；接入新商城的那次提交 = 新增一个 JSON，`git diff` 即接入证据。

### 4.3 语义/维度/指标注册表（DB）

- `semantic_registry(domain, raw_code, canonical_code, label, funnel_stage, weight)`
  - `domain ∈ {behavior, order_status, refund_type, channel, ...}`
  - `funnel_stage ∈ {visit, intent, cart, purchase, retention, none}` —— 漏斗由数据驱动
- `dimension_registry(dimension_code, source_code, resolver, label, enabled)`
  - `resolver` 只允许**受控取值**（如 `payload:category_name`、`dim_product.brand`、`constant`），不允许自由 SQL（守 N1）
- `metric_definition` 增列 `source_code`（NULL = 通用）、`required_for_overview BOOLEAN`
  - `MetricPublishValidator` 的非空闸门改为按源查注册表；某源没定义 `uv` 就不要求 `uv`

### 4.4 ODS 保真结构（P2）

ODS 表由"逐字段物理列"改为"接入元数据列 + 原样 payload"：

```
<prefix>_ods.ods_behavior_event(
  event_id        STRING,     -- 契约事件 id（幂等键）
  source_system   STRING,     -- 来自 source_registry.source_code（不再写死常量）
  schema_version  STRING,     -- 来自画像 canonical.schemaVersion（不再写死 '1.0'）
  raw_event_type  STRING,     -- 源原始事件名（保真，便于排错与回归）
  event_time      TIMESTAMP,
  ingest_batch_id BIGINT,
  landing_file    STRING,
  payload_json    STRING,     -- ★ 原样保留，新字段无需 DDL
  payload_hash    STRING      -- 幂等/去重
)
```

DWD 由画像投影：`get_json_object(payload_json, '$.<源字段>')` 的表达式**由映射生成**（模板占位符替换），因此：
- 新字段 → 只改画像（`fieldMapping`），DWD 重新跑即出现；
- 缺字段 → 显式 NULL + DQ 检查项"必填字段缺失率"，不再静默污染 join。

历史数据处置：ODS 结构 v2 与 v1 不兼容 → `INIT_SCHEMA` 提供"结构不匹配则重建该源 ODS"的显式路径（演示数据可重建；重建动作在流水线里可见、可审计）。

### 4.5 代理键策略（P2）

- 算法：`surrogate = 正数化(SHA-256( source_code || '|' || entity || '|' || UPPER(TRIM(raw_id)) )[0..8])`
  - Java（采集/维度初始化）与 Scala（Spark）**同一算法、同一输入规范**，避免两侧算出不同键；
  - 选 SHA-256 而非 xxhash 是为了两侧都无第三方依赖、实现可逐位复现。
- 保留 `raw_*_key` 列（原始字符串）用于排错与对账；`dim_*` 主键为代理键。
- 幂等性：同一 (source, entity, raw_id) 永远得到同一代理键 → 重跑不产生重复维度行。

---

## 5. 阶段计划 P1–P5（每阶段独立可验收、独立提交）

| 阶段 | 范围 | 主要改动面 | 验收证据 | 回滚 |
|---|---|---|---|---|
| **P1 库名收口 + 源登记** | 激活 `hiveDatabasePrefix`；`dw_*` 从 22 文件收敛到单点；新增 `source_registry` + `runtime_profile.source_id`；`/runtime-profiles` 与看板能列出源 | `warehouse-pipeline`（新增 `WarehouseNames.scala` 单点 + 全量替换）、`db/migration/V*__source_registry.sql`、`IngestionService`（读源）、`platform-app` 控制器 | E1 编译；E2 单测（库名解析、迁移幂等）；E3 现有单源链路端到端跑通且库名与前缀一致 | 迁移脚本新增列可空；库名替换失败可回退常量表 |
| **P2 payload 保真 + 代理键** | ODS 结构 v2（`payload_json`）；DWD 按画像投影；代理键替换 `IdCodec` 单规则 | `warehouse/ddl/00-ods.sql`、`OdsLoadSql.scala`、`EventOdsLoadJob.scala`、`LocalSchemaInitJob`、`TradeDwdJob.scala`、`IdCodec.scala` → `SurrogateKeys.scala` | E2 单测（同一 raw_id 稳定；UUID/雪花/纯数字三类形状）；E3 mock-mall 端到端指标与改造前**逐值一致**（回归基线：快照 `S20260901_39`，gmv 88.16 / pv 5 / uv 5 / dau 10 / buy_rate 0.2） | 保留 v1 DDL 与旧列一个版本，`INIT_SCHEMA` 可切回 |
| **P3 词汇/语义注册表** | 采集白名单来自画像；`schema_version`/`source_system` 去常量；DWD/DWS 去业务字面量、改 join `semantic_registry`；6 份词汇副本收敛为"契约（冻结）+ 各源画像" | `EventContractValidator.java`、`OdsLoadSql.scala`、`dws_*`/`ads_*` SQL 模板、`LocalSchemaInitJob` | E2 单测（未知事件类型 → quarantine 且带原因；语义标签驱动漏斗）；E3 源 A 指标不变；**I1 自动化检查**（模板中无业务字面量白名单外命中） | 注册表为空时回落常量（一次性，迁移后删除该回落，禁长期共存） |
| **P4 指标/维度/闸门按源** | `metric_definition.source_code`/`required_for_overview`；闸门按源；`/metrics/*?source=`；看板源切换器 | `metric-analysis`、`MetricPublishValidator.java`、`analytics_meta` 迁移、`analytics-web`（源切换 + 标签化） | E2 单测（A 源要求 uv、B 源不要求）；E3 两源指标并列可查、A 源数值不变；E5 看板可切源 | 源列可空，NULL 视为通用（先兼容再收敛） |
| **P5 异构 fixture 端到端验收** | 造"第二个商城 B"的事件文件（事件名 `product_viewed/add_to_cart/payment_success`、字段 `buyer_id/pay_money`、**UUID 主键**、行为枚举 `browse/wishlist/purchased`、多一个 `coupon_code` 字段）+ 画像 JSON + 源登记；端到端跑出源 B 指标 | 仅新增：`source-profiles/fixture-b.v1.json`、`landing/fixture-b/**`（脚本生成）、`scripts/accept-source-b.ps1`、验收记录 | E3 全链（采集→ODS→DWD→DIM→DWS→ADS→指标→看板）；A1–A4 断言（§6） | 删除源登记与目录即可（源 B 与 A 物理隔离，不影响 A） |

排序理由：P1 是所有后续阶段的地基（多源必须有库名隔离）；P2 与 P3 有依赖（投影需要映射），但 P2 可以先只做"原样落 + 代理键"；P4 依赖 P3 的注册表；P5 是 V2.1 §3.4-6 的正面证据。

---

## 6. 验收标准（可执行断言）

| 编号 | 断言 | 证据形式 |
|---|---|---|
| **A1** | 接入源 B 的那次提交，`git diff --name-only` **不含** `src/main/**/*.java|scala`（只含画像 JSON、迁移数据、验收脚本、文档） | `scripts/accept-source-b.ps1` 打印文件清单 + 断言；验收记录留 `git diff` 输出 |
| **A2** | 接入源 B 前后，源 A 的 `metrics/overview` 数值**逐项不变** | 脚本取 A 源指标两次快照并 diff |
| **A3** | 源 B 用 UUID 主键：`dim_user` 代理键非空、join 成功率 100%、行为-订单关联完整 | Spark SQL 计数（`count(*)` vs `count(user_key)`） |
| **A4** | 源 B 多出的 `coupon_code` 在 `payload_json` 可见，且**全流程无 DDL 变更** | 脚本断言 `git diff` 无 `ddl/**`；SQL 查询示例输出 |
| **A5** | 数据源停机语义（B-08/D-022）：停机后 `POST /ingestion/runs` 返回 `noNewData=true`；`GET /ingestion/status` 的 `newFileCount=0`、`lastArrivalAt` 有值 | 已实现，`scripts/accept-three-programs.ps1` §5 门禁（本轮实测） |
| **A6** | 三程序独立启停（M1-8 §3.4-1/5） | `scripts/accept-three-programs.ps1` 报告 |

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| P2 改 ODS 结构导致历史演示数据不可用 | 看板空窗 | 保留 v1 DDL 一个版本；先跑一次全链回归取新基线；重建前备份 `spark-warehouse` 目标库 |
| 代理键算法两侧不一致 | join 全空（比今天更隐蔽） | 单一实现来源（Java 与 Scala 各一份但**由同一测试向量表驱动**：固定 10 组输入/期望输出，两侧测试都读它） |
| 语义注册表与 SQL 模板双 owner 漂移 | 口径不明 | 模板只允许"占位符 + 注册表 join"两种用法；I1 自动化检查拦截字面量 |
| 多源并存后指标口径混乱 | 看板误导 | 指标一律带 `source_system`；`/metrics/overview` 无 `source` 参数时按"当前激活源"返回并显式标注源 |
| 改造期长（P1–P5）挤占论文时间 | 交付风险 | 每阶段独立提交、可停可交付；P5 之后才动商城 W1/W2 |
| 6 份词汇副本收敛被误解为"放宽契约" | 违反冻结契约 | 冻结契约（JSON Schema + OpenAPI）不动；收敛的是**平台内部副本**，商城/生成器各自保留自己的枚举并各自映射 |

---

## 8. 改动面清单（文件级，供实现计划展开）

| 阶段 | 模块 | 文件/位置 | 动作 |
|---|---|---|---|
| P1 | warehouse-pipeline | 新增 `WarehouseNames.scala`（库名/表名单点） | 新增 |
| P1 | warehouse-pipeline | 22 文件/244 行 `dw_*` 引用 | 替换为单点取值 |
| P1 | platform-app / DB | `db/migration/V*__source_registry.sql` | 新增表 + 加列 + 回填 |
| P1 | connection-ingestion | `IngestionService` / `RuntimeProfileService` | 读 `source_registry`，checkpoint 带 `source_id` |
| P2 | warehouse | `warehouse/ddl/00-ods.sql`、`OdsLoadSql.scala`、`EventOdsLoadJob.scala`、`LocalSchemaInitJob` | ODS v2 |
| P2 | warehouse | `IdCodec.scala` → `SurrogateKeys.scala`；`TradeDwdJob.scala` | 代理键 + 投影 |
| P3 | connection-ingestion | `EventContractValidator.java` | 白名单来自画像 |
| P3 | warehouse | `OdsLoadSql.scala`、`dws_*`/`ads_*` 模板、`semantic_registry` 迁移 | 去字面量 |
| P4 | metric-analysis / web | `MetricPublishValidator.java`、`MetricQueryService`、`analytics-web` 视图 | 按源闸门 + 源切换 |
| P5 | 新增 | `source-profiles/fixture-b.v1.json`、`scripts/accept-source-b.ps1` | 新增 |

---

## 9. 与既有决策的关系

- **D-002（唯一数据来源口径）不变**：平台仍只从 landing 文件读数据，不主动探活生产者。
- **D-021 延续**：契约层字符串 id、数仓层 BIGINT；本期把"BIGINT 怎么来"从单一 `IdCodec` 规则升级为可配身份策略 + 确定性代理键。
- **D-022 已实施**（B-08，本轮实测）：`noNewData` + `newFileCount` + `lastArrivalAt` 即"最小明示"，本设计不扩大该语义（不新增探活）。
- 本设计已经通过；实施裁决登记为事实记录 D-025～D-030。D-024 已被 M1-12 的 checkpoint 键所有权使用，后续不得复用该编号。

---

## 10. 后续工作流（不在本设计实现范围内，另行立设计/计划）

### W1 商城自建与美化（用户裁决：自建、逐步美化、商品种类变多）
- 前端：`mall-frontend` 引入 Element Plus；页面补齐为 商品网格（本地图片）→ 详情 → 购物车 → 结算 → 订单列表；`public/img/products/*.jpg` 本地化（`tests/boundary.test.js` 禁止外链）。
- 后端：新增 `V2__product_image.sql`（加 `image_url`）、`GET /api/v1/mall/categories`、购物车 VO + PUT/DELETE、`GET /orders/{id}`、关键字搜索、`POST /orders/{id}/complete`；修 `api.js requestRefund` 参数缺失；种子商品扩容。
- 排序：**P5 之后**（分析平台是主体）。

### W2 随机下单程序（模拟"真实店铺经营"）
- 生成器侧新增 `MallTargetAdapter` 抽象 + `ReferenceMallHttpAdapter`（驱动 8090 的 登录→浏览→搜索→加购→下单→支付→退款 随机行为，带思考时间与随机放弃率）；现有 `GenerationRunService` 的 `MALL_API` 模式由 `UnsupportedModeException` 改为真实调用。
- 第二个 adapter（异构 fixture 或真实开源商城，档 C）作为 §3.4-6 "非唯一硬编码"证据的第二形态。
- 排序：P5 后。它不依赖商城 UI 美化；Adapter 调用稳定公开 API，页面是否好看不影响生成器。真实开源商城 Adapter 需等候选系统确定。

---

## 11. 已裁决的实施默认值（2026-09-11）

1. **源 B 先用纯文件异构 fixture。** P5 必须在没有第二个商城服务的情况下先证明零核心代码接入；真实开源商城列为 P5 后加分项。
2. **旧 ODS 演示数据允许弃用重建。** 但执行前必须备份目标 source namespace、记录表数/行数/checksum，由可审计的 `INIT_SCHEMA` 步骤执行；禁止静默删除和跨源删除。
3. **采用独立 `source_registry`。** 不把源身份折叠进 `runtime_profile`。
4. **P4 首版只做单源切换。** 跨源同屏对比推迟到 P5 之后，避免在口径尚未稳定时制造误导。
5. **后端可先 API，P4 出口必须有最小数据源管理页。** 页面至少提供源列表、状态、测试、启停、当前源选择和画像校验结果；拖拽字段映射后续实现。

补充裁决：

- `canonical-event.v1.schema.json` 保持严格目标契约；当前 51/4 的旧校验行为只作为 `mock-mall-legacy-v1` 兼容输入，不宣称完全满足严格契约。P2/P3 用独立生成器重建严格 golden-55 后再去除兼容。
- `synthetic=true` 属于生成运行、制品清单和验收元数据，不进入 8 字段事件信封。
- `newFileCount` 的真实含义接受为“当前可采集完整记录的文件数”；文档/页面使用“可采集文件数”，API 后续增加 `consumableFileCount` 并废弃旧命名。

## 12. 开源商城的正确位置

当前自建商城继续作为参考源，作用是稳定地产生已知业务行为和便于演示。后续寻找开源商城时，选择标准不是“商城本身功能最多”，而是：许可证清晰、能本地部署、订单/退款/商品数据可合法导出、接口稳定、数据模型可理解、与当前 Java/MySQL 环境兼容。

接入流程固定为：

1. 将开源商城作为独立仓库、独立数据库、独立进程部署；不复制其业务代码到本仓库核心模块。
2. 只读梳理订单、用户、商品、行为、支付、退款字段和状态机。
3. 新建 `source_registry` 记录和 `<source>.v1.json` 源画像；需要主动调用时新增独立 Adapter。
4. 先导出 20–100 条小样本做 mapping dry-run，检查接受、隔离、缺字段和枚举覆盖。
5. 再跑 P5 同款验收：源码零改动断言、代理键 join、payload 保真、源 A 指标不变、源 B 指标可查。

开源商城的接入属于“证明平台可复用”的加分证据，不成为分析平台运行的强依赖，也不要求分析平台承接商城建设工作。

---

## 附录 A（2026-09-12 追加，append-only）：`enumSemantics` 的 `null` 定义（D-140 §3）

> `enumSemantics` 中形如 `"<源取值>": null` 的映射项，语义为**「该取值已被实测观测到，但平台尚未裁定其规范语义（待裁定）」**；
> 它与 §4.2 L147 的**省略键**（＝「该源没有这个语义」）是**两种不同陈述**，实现方**不得**把二者归一；
> 出现 `null` 的漂移族一律：(a) 不参与 ADS 口径的规范化，(b) 须登记为开放裁定项（本源即 Q4），(c) 在数据质量侧可被统计但不进 quarantine。

**由来**：`null` 并非本文档原有词汇，而是 2026-09-12 指令引入的扩展；本源（`mock-mall`）**实测确实存在**契约 enum 之外的取值，若按 L147 省略键则等于陈述一句被实测否证的假话，故须有此第三态。**Q4（枚举漂移：扩枚举升 1.1 还是修数据）保持未决，本附录不预设结论。** `semantic_registry` / `dimension_registry` 表**实测尚不存在**，本定义暂以本文本为属主，待表落地后迁移并留痕。裁决全文：`docs/acceptance/p3-01a-mock-mall-profile-20260912/RULINGS-P3-01A-FINAL-20260912.md`。