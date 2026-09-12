# 映射配置说明（P5 异构源人工配置接入验证）

> 本目录**只增不改**：本轮未修改任何既有画像。开工前后对照（实测）：
> `analytics-server/source-profiles/mock-mall.v1.json` = `0BB8A05C8D5E466864DCB90B8D7B97105DC65497DC3CCB463D104F27F021170B`（与 `raw/baseline-profile-fingerprints.txt` 登记值一致）。

## 1. 三个画像文件与角色

| 文件 | 字节 | sha256 | 角色 |
|---|---|---|---|
| `fixture-b.v1.json` | 3581 | `CE2E6DBC791F9FA65592C1CF37242CCFA86B3B293CA65BD713D62E7D7FEF13BD` | **归档副本**（B1 词汇：字段名/时间语义全异构） |
| `scenario-b2-canonical.v1.json` | 3125 | `57DE1116409BBB2E5B5F20233FAF6986A8A3C5F0C177F75C9B50BDFE49E3266E` | **B2 场景副本**：规范词汇 + 显式可选/未知字段策略 |
| `scenario-b3-ambiguous.v1.json` | 1596 | `1188B19E91336640A72BEC5595D79CB07D9455273A7DE3450D600F5265F1E36A` | **B3 场景副本（故意歧义）**：用于验证"配置校验能不能拦住歧义" |

三者都恰好含设计 §4.2 的 **9 个顶层必备键**（`profileVersion, sourceCode, canonical, eventTypeMapping, fieldMapping, enumSemantics, identityPolicy, timePolicy, quarantinePolicy`），且 `sourceCode` 均为 `fixture-b`（由 `fixtures/tools/verify-fixtures.mjs` §①/§④ 机器校验）。

### 1.1 单一权威副本规则

- **权威副本**：`analytics-server/source-profiles/fixture-b.v1.json` —— 它是源登记 `profile_path` 真正指向的文件（`source_registry.id=2`，实测）。
- **归档副本**：本目录 `fixture-b.v1.json` —— 与权威副本**逐字节相等**（`verify-fixtures.mjs` §④ 机器校验，输出 `ALL CHECKS PASSED`）。
- 二者若将来不一致，**以 `analytics-server/source-profiles/` 下的为准**；归档副本只用于交付留档。

> `SourceProfileValidator` 的 `profileRoot = ${platform.source.profile-root:.}` = 仓库根；`SourcePathPolicy` 接受任意仓库相对 POSIX 路径（不含 `..`）。约定俗成的形状 `analytics-server/source-profiles/<source>.v1.code.json` 只有 `SourceProfileFixtureTest` 对两个 `p1-03-probe-*` 探针做测试强制，**新增文件不会破坏该测试**（实测：本目录既有 4 个文件零改动）。

## 2. 语义方向与约定（读画像时必须记住）

- 方向一律 **源字段 → 规范字段**（`fieldMapping` 的 key 是**源侧**名字，value 是**规范侧**目标）。
- value = `"@keep"` ⇒ 该字段**只保留在 payload**，不参与规范字段归一。
- **缺少某个条目 = "该源没有这个语义"**（不是"用默认值补"）。
- `enumSemantics.<域>.<源取值> = null` ⇒ 该取值**观测到但未裁定**（D-140 §3：不得猜语义）。

## 3. `fixture-b.v1.json` 的映射要点（B1 异构词汇）

| 契约面 | 源侧键名（异构） | 规范目标 |
|---|---|---|
| 信封 | `msg_id` / `msg_kind` / `occurred_ms` / `received_ms` / `origin` / `contract_rev` / `corr_id` / `body` | `event_id` / `event_type` / `event_time` / `ingest_time` / `source_system` / `schema_version` / `trace_id` / `payload` |
| 事件类型 | `user_signup` / `item_new` / `item_view` / `order_new` / `order_pay` / `stock_hold` | `user_registered` / `product_created` / `behavior` / `order_created` / `order_paid` / `stock_reserved` |
| 身份/业务 | `buyer_uid` / `item_sku` / `visit_no` / `act_kind` / `terminal` | `user_id` / `product_id` / `session_id` / `behavior_type` / `channel` |
| 金额（**分**） | `list_price_fen` / `cost_fen` / `unit_price_fen` / `discount_fen` / `line_amount_fen` / `order_total_fen` / `paid_fen` | `price` / `cost` / `unit_price` / `discount` / `amount` / `total_amount` / `amount` |
| 订单/退款 | `order_ref` / `qty` / `sale_state` / `order_state` / `placed_ms` / `pay_ref` / `settled_ms` | `order_id` / `quantity` / `status` / `status` / `created_at` / `payment_id` / `paid_at` |
| 库存 | `hold_qty` / `held_qty` / `free_qty` | `quantity` / `reserved_qty` / `available_qty` |
| 维度 | `age_bucket` / `city_tier` / `member_tier` / `signed_ms` | `age_group` / `city_level` / `member_level` / `register_time` |
| 保留 | `ext_coupon_code` / `ext_device_id` | `@keep`（只留 payload） |
| 时间 | `timePolicy = { field: "occurred_ms", formats: ["EPOCH_MILLIS"] }` | 纪元毫秒 |

### 3.1 三个「同目标字段组」（本轮 B3B-A 歧义组的来源）

| 规范目标 | 源侧候选（≥2 个） | 语义差别 |
|---|---|---|
| `status` | `sale_state`（商品状态）、`order_state`（订单状态） | 同一目标字段被两个不同域的枚举驱动 |
| `quantity` | `qty`（下单量）、`hold_qty`（库存占用量） | 数量语义不同 |
| `amount` | `line_amount_fen`（行金额）、`paid_fen`（实付金额） | 金额口径不同 |

**这三组就是"扁平 `fieldMapping` 的表达力缺口"的直接证据**：key=源字段、value=规范字段的**扁平映射没有"事件类型"这一维**，无法表达"`sale_state` 只对 `product_*` 生效、`order_state` 只对 `order_*` 生效"。平台当前的处置是**后写覆盖/任选其一**（本轮实测：这类行被**静默接受**，见 `IMPL-REPORT.md` §4.3）。画像里保留这三组是**刻意**的：它证明"歧义在配置里可以被写出来，但配置校验不会报错"。

## 4. `scenario-b3-ambiguous.v1.json`（故意歧义，用于验证"校验能不能拦住"）

- `fieldMapping`：`amount` / `pay_money` / `settle_amount` **三个源字段同时映射到规范 `amount`**（无事件类型维度、无优先级）。
- `enumSemantics.behavior`：`purchase` / `add_cart` / `pay_later` / `cancel` **显式置 `null`**（观测到但未裁定）。
- `eventTypeMapping`：`coupon_redeemed` / `item_reviewed` 置 `null`（未裁定类型）。
- `timePolicy.formats`：**三种格式并列，且无优先级字段**：`ISO_OFFSET_DATE_TIME`、`dd/MM/yyyy HH:mm:ss`、`MM/dd/yyyy HH:mm:ss`（后两者对 `05/03/2026 14:00:00` 同时匹配）。
- `quarantinePolicy` 仍只有 `{unknownEventType: QUARANTINE, unknownField: KEEP_IN_PAYLOAD}`。

**实测结论（重要）**：把该画像登记为源 B 的 `profile_path` 后，`POST /api/v1/sources/2/test` 返回 **`ok: true`，7 项全过**，其中包含 `profile_required_top_level_keys: 设计 §4.2 的 9 个顶层必备键齐全`。即：**当前校验只看顶层键是否存在，歧义/未裁定/无优先级一律放行**，而同一个判定正是 `activate` 使用的判定 ⇒ "配置能拦住歧义"**不成立**（证据 `raw/source-b-check-and-test.txt`）。

## 5. 缺口与建议载体（**只登记，不实现**；无一处修改生产代码）

| # | 缺口 | 事实（file:line / 实测） | 建议载体 |
|---|---|---|---|
| 1 | `quarantinePolicy` **没有 missing-field 旋钮** | 全仓库 grep `missingField` = **0 命中**；`unknownField` 仅 4 处命中，且**全在画像 JSON 里，没有任何生产代码读它**（`raw/static-contrast-absent-knobs-and-wording.txt`） | 在画像 `quarantinePolicy` 下增设 `"missingField"`（取值形如 `REQUIRED_QUARANTINE` / `OPTIONAL_KEEP_NULL` / `OPTIONAL_DROP`），并允许按事件类型细化 |
| 2 | "观测到 null" 与 "字段不存在" **无法区分** | 实测：B2-2 的 16 行 null 被静默接受（无任何记录）；4 行 null 金额被误报 `金额格式违规: cost 类型异常`（引用了该源从未发送的规范字段名） | 同上旋钮增加 `"nullValue"` 取值；或在 `fieldMapping` 支持每字段 `{target, optional:true, onMissing:"..."}` |
| 3 | 金额**类型/单位**（分 vs 元）无载体 | 实测 B3B-C 7 行 JSON 数字金额被**静默接受**（`EventContractValidator.java:113-136` 只拒"既非文本又非数字"） | `fieldMapping` 的 value 支持 `{target:"amount", type:"DECIMAL", unit:"FEN", scale:100}` |
| 4 | 时间**格式优先级**无载体 | `timePolicy.formats` 是数组但**无 precedence 字段**；实测 B3B-D 7 行 `05/03/2026 14:00:00`（同时匹配两种候选格式）被**静默接受**；且 `event_time` **从不做格式校验**（见 `IMPL-REPORT.md` §4.4） | `timePolicy` 增 `"order": ["ISO_OFFSET_DATE_TIME", ...]` 与 `"ambiguousFormat": "QUARANTINE"`；或 formats 元素改为 `{pattern, priority}` |
| 5 | 扁平 `fieldMapping` 无法表达 **per-事件类型目标 / 嵌套路径** | 见 §3.1 三个同目标组；`items[].unit_price` 之类的嵌套路径在扁平 key 下无法表达（实测 `items[]` 内层金额只做文本格式检查，见 §4.4） | `fieldMapping` 允许按事件类型分组：`{ "order_created": { "order_lines[].unit_price_fen": "items[].unit_price" } }` |
| 6 | **能力限制无处展示** | `GET /api/v1/ingestion/status` 实测字段里**没有任何**能力限制/画像路径/映射状态字段；批次清单 `45.json` 里 `mappingVersion: null`，且全文 **0 次**出现 `fixture-b`（`raw/substitution-evidence-source-attribution.txt`） | 采集状态与批次清单增 `"profileCapabilities": {"applied": false, "unsupported": ["fieldMapping","enumSemantics","timePolicy"]}`（把"配置没被应用"这件事**显式说出来**） |

> 以上 6 条即父裁决要求从 B2 提取的"**缺失的旋钮名**与建议载体"。它们全部是**契约层**的扩展建议（不含任何商城名分支），符合"异构源扩展需求只能落在通用映射/适配契约上"的纪律。

## 6. 自检命令（只读）

```powershell
node docs\acceptance\p5-heterogeneous-source-20260912\fixtures\tools\verify-fixtures.mjs
# 校验项：① 3 个画像恰好 9 个顶层键 + sourceCode=fixture-b；② 逐画像 sha256 + 同目标字段组；
#         ③ 5 个 JSONL 的 sha256/行数/可解析性 vs fixture-manifest.json；④ 期望 TSV 表头/行数/直方图；
#         ⑤ 权威副本 vs 本目录归档副本逐字节相等 → ALL CHECKS PASSED
```
