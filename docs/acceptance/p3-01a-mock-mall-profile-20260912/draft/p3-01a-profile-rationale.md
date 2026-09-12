# P3-01-a 源画像 `mock-mall.v1.json` 取值依据（逐键举证）

- 任务：P3-01-a 泳道（总控派单）
- 交付物：`analytics-server/source-profiles/mock-mall.v1.json`（真实源画像，非夹具）
- 取证脚本：`raw/04-stats-script.py`、`raw/07-time-shape-probe.py`、`raw/08-enum-identity-probe.py`、
  `raw/09-skeleton-crosscheck.py`、`raw/11-profile-conformance.py`
- 总控对拍读数：`landing/` = 345 jsonl / 1,215,042,454 B（本泳道独立复算一致，见 `raw/04b-scan-inventory.txt`）

> 本文件只回答一个问题：**画像里每一个取值，凭什么这么写。**
> 取不到证据的一律写 `null` 并登记为「待裁定」，绝不臆造。

---

## 0. 一条贯穿全文的事实：本画像的"源字段名 = 规范字段名"

实测 `landing/**` 346 个文件、2,620,776 行可解析记录后，`payload` 里出现的**全部 31 个字段名**
与冻结契约 `canonical-event.v1.schema.json` 的 12 个 `$defs` 属性名并集（30 个）做交叉表
（`raw/09` 第 P 节）：

- 30 个字段名**逐一恒等命中**契约骨架（12 个事件类型里，「契约有但本事件未实测」全部为 0 —— 即源侧没有改名、没有缺骨架字段）；
- 只有 **1 个**字段 `event_time_utc` 不在任何骨架里；
- **0 个**契约骨架字段在源侧找不到对应。

⇒ 因此 `fieldMapping` 是 31 条：30 条恒等 + 1 条 `@keep`。这不是"照抄契约"，是**实测出来的恒等**。
若源侧改过名（例如 `buyer_id→user_id`），本表的形态会立刻不同。

---

## 1. `profileVersion` = `"1.0"`、`sourceCode` = `"mock-mall"`

| 出处 | 内容 |
|------|------|
| `raw/03-db-source-registry.txt` | `analytics_meta.source_registry` 全表唯一一行：`source_code=mock-mall`、`profile_version=1.0`、`profile_path=analytics-server/source-profiles/mock-mall.v1.json` |
| 阳性对照 | `COUNT(*)` = 1 |
| 阴性对照 | `WHERE source_code='__NO_SUCH_SOURCE__'` 的 `COUNT(*)` = 0 |
| 代码 | `SourceProfileValidator.check(path, expectedSourceCode, expectedProfileVersion)` 要求二者与登记**逐字相等**，否则 `sourceCodeMatches/profileVersionMatches=false` |

⇒ 两个字面量必须与 DB 行**逐字**一致，否则 `POST /api/v1/sources/1/test` 的前置项 `profile_source_code_matches`
与 `profile_profile_version_matches` 直接失败。**不允许**写成 `MOCK-MALL` 或 `1.0.0`。

---

## 2. `canonical.schemaVersion` = `"1.0"`

| 证据 | 数字 |
|------|------|
| `raw/04` 第 C 节 | `schema_version=1.0` → 2,620,729 行；`2.0` → 35；`9.9` → 9；`1.1` → 3 |
| `landing/manifests/30.json` | `"schemaVersions": [ "1.0" ]`（黄金批，`acceptedRecords=51 / quarantinedRecords=4`） |
| `landing/manifests/42.json` | `"schemaVersions": [ "1.0" ]`（最新批，1,000 行） |
| 冻结契约 | `contract-specs/schemas/canonical-event.v1.schema.json` 即 `1.0` 版本族 |

非 1.0 的 3 种取值**已被采集层处置**（证明"1.0 是本源被接受的契约版本"）：
`raw/09` 第 S 节显示 `schema_version=1.1` 的那 1 个事件（`event_id=bbbbbbbb-...-999`）同时出现在
`landing/events/2026090700.jsonl`（源输入）、`landing/quarantine/29/`、`landing/quarantine/7/`（隔离产物）；
`2.0` 的那行（`age_group="45-54"`）也在隔离区（`raw/08` 第 M 节样本）。

> 注意：全树聚合计数 = **出现位置数**，不是去重事件数（同一事件在 `events/` 与 `accepted|quarantine/<id>/`
> 各出现一次）。`2.0` 的 35 与 `age_group="45-54"` 的 35 是**同一批 35 行**，不是两批。

---

## 3. `eventTypeMapping`：12 条**恒等**映射（全覆盖，0 未映射）

`raw/04` 第 B 节：实测 `event_type` **恰好 12 个 distinct**，合计 = 2,620,776 = 可解析总行数。

```
[positive-control] event_type 计数总和 = 2620776 ; 可解析行数 = 2620776 ; 相等 = True
[negative-control] event_type == '__NO_SUCH_VALUE__' 计数 = 0
```

12 个取值与冻结契约的 12 个 `$defs` 名称**逐字相同**（`raw/09` 第 O 节）：
`behavior / order_cancelled / order_created / order_paid / product_created / product_updated /
refund_completed / refund_created / stock_changed / stock_released / stock_reserved / user_registered`。

⇒ 本源**直接产出规范事件名**，映射是恒等。**没有**"源事件名"这一层（`product_viewed`/`add_to_cart` 之类在
本源数据里出现次数为 **0**）——所以画像**不写**这些猜测性条目：§4.2 规定"缺失的映射项 = 该源没有这个语义"，
写进去就等于宣称源会发这些事件，那是臆造。

---

## 4. `fieldMapping`：30 条恒等 + `event_time_utc: "@keep"`

恒等条目（30）＝ 契约骨架字段并集，见第 0 节；`raw/09` 第 P 节逐事件类型给出交叉表，且
`raw/11` 断言 I4 复核：实测字段 31、未登记 0、契约外字段 `{event_time_utc}` == `@keep` 集合。

### 4.1 唯一 `@keep`：`event_time_utc`

- 出处：`raw/04` 第 E 节 `[behavior] event_time_utc 2587497`（259 万行，**真实存在且量大**）。
- 判定依据：它**不在**契约任何 `$defs` 的属性里（`raw/09` 第 P 节：behavior 的"需 @keep 的源侧额外字段" =
  `['amount', 'event_time_utc', 'product_name']`）。
- §4.2 原文：`"@keep"` 表示字段不映射到骨架，仅保留在 `payload_json`，不参与标准指标。

### 4.2 已登记的结构性缺陷（**不是**本画像能解决的）

`fieldMapping` 是**扁平命名空间**，而契约骨架是**按事件类型**定义的。同一个源字段名在不同事件里
既可能是骨架字段、也可能是额外字段 —— 实测有 3 例：

| 字段 | 作为骨架字段（应恒等） | 同时作为额外字段 |
|------|----------------------|----------------|
| `amount` | `order_paid`/`refund_created`/`refund_completed` | `behavior.payload.amount` 15 行 |
| `product_name` | `product_created`/`product_updated` | `behavior.payload.product_name` 2,427 行 |
| `reason` | `order_cancelled`/`refund_created` | `refund_completed.payload.reason` 105 行 |

本画像只能取"恒等"（若改成 `@keep`，会连带把 `order_paid.amount` 这类**骨架字段**判成额外字段，
错得更重）。⇒ 登记为**待裁定**：`fieldMapping` 是否需要按事件类型分域（会改 §4.2 的键结构，属破坏性变更）。

---

## 5. `enumSemantics`：**契约外一律 `null`**，8 个域

### 5.1 域名的出处

| 域名 | 出处 | 依据强度 |
|------|------|---------|
| `behavior` | 设计 §4.3 `domain ∈ {behavior, order_status, refund_type, channel, ...}` | 冻结词表内 |
| `channel` | 同上 | 冻结词表内 |
| `orderStatus` | 设计 §4.2 L131 示例逐字使用了 `orderStatus` | §4.2 原文 |
| `productStatus`/`changeType`/`ageGroup`/`cityLevel`/`memberLevel` | §4.3 的域词表**未覆盖**，按 §4.2 `orderStatus` 的驼峰式命名补出 | **待裁定**（见 §7） |

### 5.2 取值规则（本画像的核心裁决）

**规则：取值 ∈ 契约 enum ⇒ 恒等标签；取值 ∉ 契约 enum ⇒ `null`。**

为什么不是"把明显同义的拼写归一过去"（例如 `add_cart→cart_add`、`age18_24→18-24`）：
`contract-specs/README.md` **Q4「枚举漂移」明确未决**，原文要求
「需决定：扩枚举（升 1.1）还是修数据/收紧校验器」。**没有属主做过这个决定**，
所以"哪个拼写算合法值"不归本泳道裁。把 `age18_24` 归一成 `18-24`、却把同一漂移族的 `45-54` 判 `null`，
是在**同一漂移类内部**做任意切分 —— 那才是"擅自决定"。

`null` 的语义在此画像中显式定义为：**"该取值在源里确实存在（计数见下），但平台语义标签未经裁决"**。
它与"键缺失（= 该源没有这个语义）"是**两件事**，这正是不省略键、而写 `null` 的原因。

### 5.3 逐域数字（`raw/09` 第 Q 节，全部为实测 distinct 与计数）

| 域 | 契约 enum（出处：`canonical-event.v1.schema.json`） | 命中（恒等） | 出界（→ `null`） |
|----|-----------------------------------------------|------------|----------------|
| `behavior` | `view/favorite/cart_add/cart_remove/search` | 2,591,945：`view`=2,280,400、`cart_add`=128,768、`favorite`=104,933、`search`=39,447、`cart_remove`=38,397 | 47：`purchase`=35、`click`=6、`add_cart`=6 |
| `channel` | `app/pc/h5` | 2,589,340：`app`=2,588,704、`pc`=428、`h5`=208 | 210：`web`=210 |
| `changeType` | `inbound/outbound/adjust` | 64：`inbound`=64 | 35：`restock`=35 |
| `ageGroup` | `under18/18-24/25-34/35-44/45+` | 1,325：`25-34`=459、`35-44`=339、`18-24`=289、`under18`=124、`45+`=114 | 1,451：`20-29`=900、`age18_24`=132、`age45_plus`=132、`age25_34`=132、`age35_44`=120、`45-54`=35 |
| `cityLevel` | `tier1/tier2/tier3/other` | 1,799：`tier1`=641、`tier2`=439、`tier3`=409、`other`=310 | 69：`2`=63、`1`=6 |
| `memberLevel` | `normal/silver/gold/platinum` | 2,776：`gold`=1,295、`normal`=891、`silver`=433、`platinum`=157 | **0** |
| `orderStatus` | 契约对 `order_created.status` **无 enum** | 6,434：`CREATED`（恒等；契约未给词表 ⇒ 不存在"出界"概念） | 0 |
| `productStatus` | `on_sale/off_sale/pending` | 375：`on_sale`=375 | 0 |

`raw/11` 断言 I5/I6 复核：9 个 `(event_type, 字段)→域` 组合**全部覆盖**、无漏登记、
`null` 与"契约外"**互为充要**（既不漏判也不冤枉），且"契约本无 enum 却被判 null" = 0。

### 5.4 为什么 `reason` **没有**进 `enumSemantics`

`reason` 这一个源字段名承载了**两个不同域**（实测，`raw/08` 第 J 节）：

- `order_cancelled.reason`（4 值）：`out_of_stock`=646、`user_cancel`=631、`change_of_mind`=522、`payment_timeout`=512
- `refund_created.reason`（6 值）+ `refund_completed.reason`（3 值）：`quality_issue`、`buyer_returns_all`、
  `late_partial_return`、`wrong_item`、`late_delivery`、`user_regret`

扁平域命名（§4.2 的 `enumSemantics.<domain>.<rawValue>`）**无法表达"同一键名两个域"**：
若只写一个 `refundType` 域，`out_of_stock` 会被误当退款原因；若写两个域，`reason` 这个扁平键会指向哪一个？
⇒ 登记为**待裁定**，画像中**不写** `reason` 域，并在本节留下全部实测取值。
（`contract-specs/README.md` Q4 只登记了 `channel/behavior_type/age_group/change_type` 四项漂移，
**未登记** `reason` 的域重载与 `city_level` 的数字漂移，本泳道补登。）

---

## 6. `identityPolicy`：5 个实体，`shape` 全为 `ANY`

### 6.1 实体名的出处

| 实体 | `rawField` | 出处 | 依据强度 |
|------|-----------|------|---------|
| `user` | `user_id` | `surrogate-key.v1.json` `entityEnum` | 词表内 + 实测 |
| `product` | `product_id` | 同上 | 词表内 + 实测 |
| `category` | `category_id` | 同上 | 词表内 + 实测 |
| `brand` | `brand_id` | 同上 | 词表内 + 实测 |
| `order` | `order_id` | 设计 §4.2 L136 示例含 `order` | **待裁定**：`entityEnum` 无 `order` |

`coupon` 在 `entityEnum` 里但**实测不存在**（`raw/09` 第 P 节：任何事件类型都没有 `coupon*` 字段）
⇒ 按"缺失 = 该源没有这个语义"**不写**。

`refund_id`/`payment_id`/`session_id` **实测存在**（`raw/08` 第 K 节）但两个词表都没有对应实体
⇒ 登记为**待裁定**，画像不写。

### 6.2 `shape` = `ANY`：实测就是混合形态

`raw/09` 第 R 节（形态 = 对 `payload.<字段>` 的字符串判定）：

| 字段 | 实测形态分布 | 非纯数字样本 |
|------|-------------|-------------|
| `user_id` | `NUMERIC`=2,602,348、`PREFIX_NUMERIC`=8,664 | `u999`、`ur41`、`ux10` |
| `product_id` | `NUMERIC`=2,596,470、`PREFIX_NUMERIC`=5,286 | `pr41`、`px10` |
| `category_id` | `PREFIX_NUMERIC`=500、`NUMERIC`=175 | `c1` |
| `brand_id` | `PREFIX_NUMERIC`=200、`NUMERIC`=175 | `B001` |
| `order_id` | `NUMERIC`=20,820、`PREFIX_NUMERIC`=4,414 | `or41`、`o2` |

5 个实体的 id **全部**是"纯数字 + 前缀数字"混合。`shape` 词表 `{UUID, PREFIX_NUMERIC, ANY, NUMERIC}`
（出处：`p1-03-probe-1.v1.json` + `SourceProfileValidatorTest`）里**没有**能表达"混合、但以纯数字为主"
的取值 ⇒ 5 个全取 `ANY`。取 `PREFIX_NUMERIC` 会漏掉 260 万行纯数字 id；取 `NUMERIC` 会漏掉 8,664 行
前缀 id —— 两者都是可被实测直接否证的错。

> **登记词表缺口**：`shape` 无法表达"混合"这一**实测占多数**的事实 ⇒ 待裁定（扩充取值，或允许数组）。

### 6.3 `surrogate` = `"HASH64"`

- 出处（画像侧唯一在用词）：`SourceProfileValidatorTest.java:51` 与 `SourceRegistryServiceTest.java:92`
  的画像文本均写 `"surrogate": "HASH64"`；`p1-03-probe-1/2.v1.json` 同。
- 语义一致性：`surrogate-key.v1.json` 规定 `SHA-256` **取前 8 字节** = **64 位**、清符号位、`BIGINT`
  ⇒ `HASH64` 与之一致（8 字节 = 64 bit）。
- **注意**：该规格状态为 `DRAFT` 且"**尚无任何实现读取**"⇒ 本画像的 `surrogate` 目前**没有消费者**，
  取值只是与既有画像夹具保持一致，不代表实现已就位。

---

## 7. `timePolicy`：`field="event_time"`，`formats=["ISO_OFFSET_DATE_TIME"]`（**只有一种**）

### 7.1 全量实测（`raw/07`，2,620,776 个 `event_time` 观测值，100% 分类完毕）

| 形态 | 计数 |
|------|------|
| `ISO_OFFSET_PLUS08_NO_FRACTION`（`yyyy-MM-ddTHH:mm:ss+08:00`） | 2,611,924 |
| `ISO_OFFSET_PLUS08_WITH_FRACTION(7位)` | 7,965 |
| `ISO_OFFSET_PLUS08_WITH_FRACTION(6位)` | 817 |
| `ISO_OFFSET_PLUS08_WITH_FRACTION(5位)` | 58 |
| `ISO_OFFSET_PLUS08_WITH_FRACTION(4位)` | 8 |
| `ISO_OFFSET_PLUS08_WITH_FRACTION(3位)` | 4 |
| 其它偏移（含 `Z`） | **0** |
| 无偏移 / `EPOCH_MILLIS` / 空格分隔 | **0** |
| 合计 | 2,620,776 |

`raw/11` 断言 I8 复核：**100%** 被契约 `iso8601_time` 形态解析，非 `+08:00` 的 = **0**。

### 7.2 `formats` 只写一种，且**足够**覆盖 100%

冻结契约 `contract-specs/schemas/canonical-event.v1.schema.json` 的 `$defs.iso8601_time.pattern` 原文：

```
^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?([+-]\d{2}:\d{2}|Z)$
```

该 pattern 的语义 = 秒必填、**小数秒可选 0–9 位**、偏移必填 = 正是 `ISO_OFFSET_DATE_TIME` 的接受集。
故**单个** `ISO_OFFSET_DATE_TIME` 覆盖上表全部 6 种形态；
而"只写 `yyyy-MM-ddTHH:mm:ss+08:00` 字面量"会**拒绝**那 8,852 行带小数秒的真实数据。

⇒ 结论：`formats` 只有一项**不是**"能力不足"，是"实测只有这一个形态族"。

> **纠偏（引自父会话实测，本泳道复核后不成立）**：父会话给出"2,622,616 个 `event_time` 全部为
> `yyyy-MM-ddTHH:mm:ss+08:00`，其它任何形态 **0**"。本泳道全树复算得到 **8,852** 行带小数秒（3–7 位），
> **非 0**；总行数也不等（本泳道 2,620,776）。"没有别的**时区**"这一半成立，"没有别的**形态**"这一半不成立。
> 差异的可能来源：父会话的样本集与 346 文件全树不同（本泳道读数已与总控对拍的 345 文件 /
> 1,215,042,454 B 一致）。⇒ 按"实测优先"，`formats` 取覆盖 100% 的 `ISO_OFFSET_DATE_TIME`。

### 7.3 `field` 为什么是 `event_time`

§4.2 示例写的是"源字段 `created_at` → 规范字段 `event_time`"（那是**改名源**的写法）。
本源的 `event_time` 是**信封字段本身**，实测 2,620,776 行全部由源侧直接给出（`raw/04` 第 A 节：
`event_time` 缺失 = 0 行）。源侧另有一组**按事件类型**的业务时间字段（`created_at`/`paid_at`/
`cancelled_at`/`completed_at`/`register_time`），它们**同时**是契约骨架字段，已在 `fieldMapping` 里恒等映射
（第 4 节），因此不重复塞进 `timePolicy`。

### 7.4 已登记的规格缺口

- `timePolicy` **只有一个 `field` 槽位**，但信封里 `event_time` 与 `ingest_time` **都是源侧提供**的；
  实测 `ingest_time` 的形态族与 `event_time` 不同（`raw/07`：7 位小数 = 2,336,220 行，
  而无小数仅 24,496 行）⇒ `ingest_time` 的格式策略**无处声明**。待裁定。
- `formats` 的**取值词表**没有冻结件：既有夹具混用 Java `DateTimeFormatter` 名
  （`ISO_OFFSET_DATE_TIME`）与字面 pattern（`yyyy-MM-dd HH:mm:ss`）。本画像取前者（与既有用法一致）⇒ 待裁定。

---

## 8. `quarantinePolicy` = `{"unknownEventType": "QUARANTINE", "unknownField": "KEEP_IN_PAYLOAD"}`

- 出处：设计 §4.2 **L142 逐字**原文，且 `p1-03-probe-1/2.v1.json` 同值（既有唯一在用的取值）。
- 实测旁证：隔离区**确实在产出**（142 个 jsonl，`raw/04b`），且能读到真实隔离记录（`raw/08` 第 M 节）。

⇒ 两个键/值都照 §4.2 写，**未**新增键。

> **已登记的规格缺口（本泳道实测，重要）**：这两个键**无法表达实际发生的隔离原因**。
> `raw/08` 第 M 节 + 第 S 节的真实隔离记录显示原因是：
> ① `schema_version` 不受支持（`9.9`、`2.0`、`1.1`）；② 非法 `behavior_type`（`purchase`）；
> ③ 缺 `event_id`；④ JSON 不可解析（41 行，`raw/04`）。
> 而**实测 `event_type` 未知 = 0**（12 个取值全在契约内）⇒ 真正在用的 `unknownEventType` 分支
> **从未被触发**。这与 `contract-specs/README.md` 的 **Q6**（采集层"必填"执行强度弱于契约）同源。
> 待裁定：`quarantinePolicy` 是否需要"版本不受支持/枚举非法/缺字段"等键。

---

## 9. 明确**没有**写进画像的东西（以及为什么）

| 没写 | 理由 |
|------|------|
| `timezone` / `currency` | 二者的唯一 owner 是 `source_registry.timezone` / `.currency`（DB 实测 `Asia/Shanghai` / `CNY`，`raw/03`）。写进画像 = 造**第二个 owner**。§4.2 的 9 个键里**本来就没有**这两个槽位。`raw/11` 断言 I9 强制校验画像文本不含 `timezone/currency/Asia/Shanghai/CNY`。 |
| `age_group` 的归一映射（`age18_24→18-24` 等） | Q4 未决，不擅自归一（§5.2）。 |
| `reason` 域 | 同一键名两个域，扁平结构无法表达（§5.4）。 |
| `coupon` 实体 | 实测 0 处出现。 |
| `refund`/`payment`/`session` 实体 | 两个词表都无此实体名，待裁定（§6.1）。 |
| 任何"源事件名"（`product_viewed` 等） | 实测出现 0 次；写了就等于宣称源会发这些事件。 |
| `EPOCH_MILLIS` 等额外时间格式 | 实测 0 次；写了会掩盖"源侧格式单一"这一事实。 |

---

## 10. 本泳道自查出的错误（留痕，不删除）

1. **范围错误 ①：漏 `landing/accepted`**，只扫 `landing/events`（61 文件），报成"全量"。
2. **范围错误 ②：漏 `landing/quarantine`**（142 文件里 137 个是 0 字节，但**恰好是负例证据所在**）。
   根因：**按已知子目录列举**而不是**先枚举再筛选**。
   正确做法（已落盘 `raw/04b-scan-inventory.txt`）：
   `Get-ChildItem landing -Recurse -File -Filter *.jsonl` 拿**权威清单**，再按目录分组计数，
   并打印 `目录 => 文件数 => 字节数` 汇总行；本泳道全树读数 345 / 1,215,042,454 B 与总控对拍**一致**。
3. **空捕获假证据**：`raw/03b-db-source-registry-full.txt` 曾只含 `### exit=0`（12 B）。
   根因：PowerShell 里 `"h1"; "h2"; $out; "### exit=$?" | Tee-Object -FilePath $f` 的管道
   **只作用于最后一条语句**。已改为逐条 `Out-File -Append` 并**回读校验字节数**；该假文件已**删除**。
4. **阳性对照自身写错（两次）**：
   ① `raw/07` 用 `'"+08:00"' in line` 判命中 —— JSON 里引号在**右边**（`...+08:00"`），
   该对照**恒为 0**，于是把"工具坏了"显示成"数据里没有"。`raw/08` 改用 `'+08:00"'` 后命中 2,620,776。
   ② `raw/10` 用 `Where-Object Length -lt 200MB` 后 `Select-String` 全仓递归 → **超时**（无输出）。
   教训：**对照表达式本身也要有对照**（若阳性对照返回 0，先怀疑对照写错，而不是先宣布"数据里没有"）。
5. **`raw/10` 第 [4] 节 `bytes` 列为空**：`Measure-Object` 用法写错（已用 `[4b]` 更正，并注明以 `raw/04b` 为权威）。
6. **`raw/09` 首跑崩溃**：enum 计数的字典键用了 Python `repr`（单引号），却又用 `json.loads` 还原 ⇒
   `JSONDecodeError`。已改为 `json.dumps`。
7. **画像自身缺陷（由断言 I6 抓出，已修）**：首版把 `add_cart→cart_add`、`age18_24→18-24`、
   `age25_34→25-34`、`age35_44→35-44`、`age45_plus→45+` 归一成了规范值。I6 判 FAIL：
   这等于对**契约 enum 之外**的取值擅自给出平台语义标签，而 Q4 明确未决。
   已改为**契约外一律 `null`**（见 §5.2），复跑 `verdict=ALL_PASS`。
   ⇒ 这条说明一件事：**没有 I6 这条断言，我会带着一个"擅自归一"的画像交付。**

---

## 11. 待裁定清单（汇总，供总控裁决）

| # | 事项 | 证据位置 |
|---|------|---------|
| R1 | Q4 枚举漂移：扩枚举（升 1.1）还是修数据/收紧校验器？影响 `enumSemantics` 里 8 类 `null` | §5.3、`contract-specs/README.md` Q4 |
| R2 | `reason` 一个键名两个域，扁平 `enumSemantics` 无法表达 | §5.4、`raw/08` 第 J 节 |
| R3 | `fieldMapping` 扁平命名空间 vs 契约骨架按事件类型（`amount`/`product_name`/`reason` 三例冲突） | §4.2 |
| R4 | `identityPolicy` 的 `order` 不在 `surrogate-key.entityEnum`；`refund`/`payment`/`session` 两词表皆无 | §6.1 |
| R5 | `shape` 词表无法表达"混合形态"（5 个实体实测全是混合） | §6.2 |
| R6 | `timePolicy` 只有单 `field` 槽位，`ingest_time` 格式无处声明 | §7.4 |
| R7 | `formats` 的取值词表未冻结（Java 格式器名 vs 字面 pattern） | §7.4 |
| R8 | `quarantinePolicy` 两键无法表达实际隔离原因（版本/枚举/缺字段）；`unknownEventType` 分支实测从未触发 | §8 |
| R9 | §4.3 的 `semantic_registry` / `dimension_registry` 在真实 DB **不存在**（`raw/06`：`ERROR 1146`） ⇒ 域名与语义标签**无实现属主** | `raw/06` |
| R10 | §4.2 示例域写 `orderStatus`，§4.3 词表写 `order_status`（spec-vs-spec 不一致） | §5.1 |
| R11 | `surrogate-key.v1.json` 向量 V01 标注 `entity=user, rawInput=89a6db2c-…, why="真实 landing 取值"`，但该 UUID 在真实行里是 **`event_id`**，`user_id` 实为 `2098607948334395394`（即 V02）；且该规格自称"事件粒度键 `event_id` 不在本规格范围" | `raw/10` 第 [1][2] 节 |
| R12 | `status` 一个键名两个域（订单状态 vs 商品状态），契约只对商品状态给了 enum | §5.3 |
