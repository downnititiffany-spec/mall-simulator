# SPEC-CHANGE-DRAFT.md — P3-02 映射执行器 · 契约变更决策草案

> **性质**：**草案，待总控裁决**。本文件**不是**契约、**不是**裁决书，本身**不授权实现**。
> **触发原因**：按任务硬性要求 1，规格判定结论为 **②「规格未定义或有歧义」** ⇒ **立刻停止实现**，交回总控裁决，**不自行发明语义**。
> **HEAD 说明**：本卷开场 HEAD `fc53f62`，收工 HEAD `8983616`（**施工期间由他人推进**）。两处基线缺陷（§3bis）在新 HEAD 上**逐字复现**（`git show HEAD:` 复核），结论不受影响。
> **次序约束**：看板台账行 525（人裁决 ⑥）定「先修 IT 安全边界 → 配好隔离环境 → 验证 F-88 → **再继续 P3/P5**」⇒ **P3-02 被后置**（见待裁项 D-P3-02-14）。本草案**不主张例外**。
> **权威顺序**（D-091）：① 冻结契约 `contract-specs/**`、`docs/contracts/**` → ② 指导书 V2.4（sha256 `580AD7BE46B2F395400D9CD0812C58D4D3621794B8604B4E606EF44420C4535D`，本轮**已复算一致**）→ ③ 看板 V2.2 → ④ 专项设计 → ⑤ 历史文档。
> **纪律**：指导书是已编号文稿，按 §2.1.1 **不得就地改、不得文末追加**；本草案**不生成新版本**。冻结契约、指导书、看板本轮**均未改动**。
> **证据**：`INVENTORY.md`（只读盘点）＋ `raw/static-contrast-inventory.txt`、`raw/line-anchors-verify.txt`。

---

## 1. 判定结论

| 问题 | 结论 |
|---|---|
| 「把源事件转成 canonical event」这件事**是否已被规格授权**？ | **是**。流水线顺序、转换白名单、六类原因码名、dry-run 计数口径在计划书 `:196-199`/`:225` 与指导书 V2.4 `:346-350` 有逐字定义（见 §3）。 |
| 「字段映射**执行**的具体语法与判据」**是否已被规格定义**？ | **否，且有多处互相矛盾的既成实例**。共 **10 项**（A1–A10），其中 **6 项阻塞**。 |
| 因此本任务应如何处置？ | **走 ②**：**停止实现**，先裁决。**已完成**：只读盘点、规格判定、E1/E2 现状基线、本草案。**未做**：任何生产代码/测试代码。 |

> **不含糊的表述**：规格**有骨架、无执行语法**。缺口不在「要不要做」，而在「做出来的语义算不算合规」——A1–A10 每一项都会被验收反查，任一项由实施方自行拍定都会构成「发明语义」。

---

## 2. 歧义清单 A1–A10（逐条：现状 → 依据原文 → 影响 → 建议）

> 「阻塞」= 不定则**无法**写出合规实现（写出来必被某项裁定推翻或在验收时被反查为发明）。
> 「可延后」= 可在实施中按规格既有字面落地，但建议一并冻结以免二次返工。

---

### A1 —— canonical 骨架（规范字段名全集）**没有 owner**　【**阻塞**】

**现状**：三处规范字段清单**互不相等**，无一处声明自己是全集所有者：

| 处 | 位置 | 内容 |
|---|---|---|
| 冻结契约 | `contract-specs/schemas/canonical-event.v1.schema.json:8-55` ＋ `allOf` 12 类 `$defs` | 信封 8 属性；payload 按 `event_type` 路由到 12 个 `$defs` |
| 采集闸门 | `EventContractValidator.java:56-57`（信封 7）＋ `:62`（`payload`）＋ `:89-111`（payload 1–4 个） | **实查 37 个**（已入账：契约 62 vs 闸门 37） |
| 设计书 §4.4 | 设计书 `:163-177` | ODS 9 列，**不是** payload 骨架 |

`fieldMapping` 的语义依赖这个骨架：设计书 `:122` 原文 `// 源字段 → 规范字段（规范字段是骨架，见 §4.4）`；`:148` 原文 `` `"@keep"` 表示字段不映射到骨架 ``。**「骨架」在正文里从未被枚举**。

**影响**：无骨架 ⇒ ① 归一输出**没有合法的目标名字集合**；② 无法判定一条 `fieldMapping` 的 value 是「骨架字段」还是「契约外字段」（而这是 `@keep` 合法性的**唯一**判据——P3-01-a 的断言 I4 正是这么钉的：`IMPL-REPORT.md:179`「`@keep` 仅用于契约外字段（契约外 = `{event_time_utc}` **恰好**等于 `@keep` 集）」）；③ G1 缺陷（62 vs 37）无法收口，因为收口方向（收紧实现 or 放宽契约）取决于骨架归属。

**建议条款**（三选一，**须总控裁决**）：

| 方案 | 内容 | 代价 |
|---|---|---|
| **A1-α（建议）** | 冻结契约的 machine-readable schema **即为**骨架 owner；实施侧**派生**字段名集合，**禁止**在 Java 再写第二份字面量清单；G1 的 62 vs 37 缺口**另开任务**收敛 | 需接受「归一目标集＝schema 全集」；实施期必须写一条**防漂移守卫测试**（参照既有 `CanonicalEventSchemaParityTest` 的形态） |
| A1-β | 在 `platform-common` 新建 `CanonicalSkeleton` 常量类作为唯一 owner，schema 由它派生 | 与 D-064（退休 `EventContract.SOURCE_SYSTEM` 式的常量集中化）方向一致，但**新增第二个 owner 的风险最高**，且与「契约是唯一真相」的既有裁决倾向相反 |
| A1-γ | 骨架 owner 归 `EventNormalizer` 内部 | ❌ **不建议**：把契约知识放进执行器，违反本任务硬性要求 2（唯一所有者/反熵） |

---

### A2 —— `fieldMapping` 的 **value 语法**与**同目标冲突**　【**阻塞**】

**现状**：`fieldMapping` 的 value 只有两种**已知形态**：(a) 规范字段名字符串；(b) 哨兵 `"@keep"`。**没有任何一句规格说明**：value 是否允许对象（如 `{target, type, unit, scale}`）、是否允许 `null`、是否允许数组。

**同目标冲突是既成事实，且规格对它完全沉默**——`analytics-server/source-profiles/fixture-b.v1.json` 实测（`:44`/`:53`、`:48`/`:60`、`:51`/`:57`）：

| 规范目标 | 源侧候选 | 画像行 |
|---|---|---|
| `status` | `sale_state`、`order_state` | `:44`、`:53` |
| `quantity` | `qty`、`hold_qty` | `:48`、`:60` |
| `amount` | `line_amount_fen`、`paid_fen` | `:51`、`:57` |

`p5` 泳道已把这三组称为「**扁平 `fieldMapping` 的表达力缺口**的直接证据」，并实测其后果是**静默接受（后写覆盖）**（`mapping/MAPPING-NOTES.md:53`）。

**依据原文**：设计书 `:147` 只说「缺失的映射项 = "该源没有这个语义"（不是默认值）」，**未规定重复 value**。

**影响**：不定则实施方**必须**自选一种覆盖策略（首条胜／末条胜／按事件类型路由），三种都会改变 ACCEPT/QUARANTINE 计数 ⇒ 直接改变 P3-02 的 dry-run 覆盖率数字，且**换一份画像就换一个答案**。

**建议条款**：

1. **加性**：`fieldMapping` 的 value 允许两种形态 —— **字符串**（规范字段名或 `"@keep"`）与**「按事件类型分组」的对象**（解决跨域同目标：`sale_state` 只对 `product_*` 生效）。这份「按事件类型分组」在 `p5` 已作为建议载体提出（`IMPL-REPORT.md:73`），**尚未裁决**。
2. **默认严格**：**同一事件类型内**若两条映射落到同一规范目标 ⇒ **画像校验失败**（沿用 D-112 第 3 条的既有精神：「静默忽略等于"配置写了但没生效"，属禁止的假取证形态」；亦满足 D-139 §2.2 让 `/test` 从「浅检」升为语义检的方向）。
3. **禁止**在 `fieldMapping` 内表达金额 scale／unit（那属 A9）。

> ⚠️ 第 1 条是**新语义**，不是既有字面的澄清 ⇒ **必须裁决**，不得由实施方拍定。

---

### A3 —— `"@keep"` 的**归属字段名与作用位置**　【**阻塞**】

**现状**：设计书 `:148` 原文 `` `"@keep"` 表示字段不映射到骨架，仅保留在 `payload_json`。``

**未定义的三件事**（每件都有既成实例，两种读法都自洽）：

| # | 未定义项 | 实例 | 两种读法的差别 |
|---|---|---|---|
| a | 「保留」用的**键名** | `fixture-b.v1.json:69-70` `"ext_coupon_code": "@keep"`；设计书 `:127` `"coupon_code": "@keep"`；`mock-mall.v1.json:35` `"event_time_utc": "@keep"`（**源名＝规范名**，无差别） | 保留**源键名** `ext_coupon_code`，还是**去掉源前缀**（`ext_` 是 fixture-b 的命名习惯）？规格**未给任何 transform 语法**（白名单里的 `trim`/`case` 都不适用） |
| b | 作用**位置** | 设计书 `:122` 的 `fieldMapping` 是**扁平**的（无 `payload.` 前缀，也无信封字段的前缀区分），而 `fixture-b.v1.json:24-31` 明确把**信封字段**也放进同一张表（`msg_id→event_id` … `body→payload`） | `@keep` 字段保留在 **`payload` 内**（设计书 `:148` 字面）还是**顶层**？若源侧该字段在信封层（如 `mock-mall` 的 `event_time_utc`），两种读法结果不同 |
| c | 「不映射到骨架」是否**禁止**它参与任何下游语义 | 设计书 `:127` 注释原文「保留在 payload_json，**不参与标准指标**」 | 只是「不进标准指标」，还是「不得被 DWD 投影」？P2-04 已挂账依赖 P3-02 |

**影响**：`@keep` 是 **P3-01-a 断言 I4** 的判据核心（`docs/acceptance/p3-01a-mock-mall-profile-20260912/IMPL-REPORT.md:179`），也是设计书 `:213-216` **A4 断言**（「`coupon_code` 在 `payload_json` 可见」）的判据核心。键名一错，A4 直接失败。

**建议条款**：`@keep` ⇒ 该源字段的值**以源键名原文**作为 `payload` 的**新增键**写入，**不改名、不加前缀、不改大小写**；若该源字段原本位于信封层，则**移入** `payload`；`@keep` 字段**不参与**任何规范字段归一，且**必须**在规范事件中**可被逐一列举**（供 A4 断言与 DWD 投影排除）。冲突（`@keep` 键名与既有 payload 键撞名）⇒ **画像校验失败**。

---

### A4 —— `timePolicy.field` 的**命名空间**：源侧还是规范侧？　【**阻塞**】

**现状**：三份画像**互相矛盾**（实测）：

| 画像 | `timePolicy.field` | 该值是 | 其 `fieldMapping` 对应条目 |
|---|---|---|---|
| `p1-03-probe-1.v1.json:28` | `"created_at"` | **源侧** | `:15` `"created_at": "event_time"` |
| `fixture-b.v1.json:132` | `"occurred_ms"` | **源侧** | `:26` `"occurred_ms": "event_time"` |
| `mock-mall.v1.json:117` | `"event_time"` | **规范侧**（其 `fieldMapping` 为恒等，源名恰等于规范名） | `:31`… 恒等 |

**依据原文**：设计书 `:139` 的示例是 `"field": "created_at"`，而同一份设计书 `:126` 的 `fieldMapping` 示例是 `"created_at": "event_time"` ⇒ **设计书自身的示例即「源侧」读法**。但 `mock-mall` 的 `event_time` 在两种读法下**都成立**，无法作为判据。

**影响**：读法不同 ⇒ 时间解析读**完全不同的字段**。`mock-mall` 侥幸两读皆通（恒等），**异构源必然分叉**：按规范侧读 `fixture-b` 会去找规范字段 `event_time`（源里不存在）⇒ 全部时间解析失败 ⇒ 全量隔离。这正是 P5 实测 B1「60/60 全被隔离」的**同族**故障模式。

**建议条款**：`timePolicy.field` **一律是源侧字段名**；实现**先**按 `fieldMapping` 把它翻成规范目标（必须翻到 `event_time`，否则画像校验失败），**再**按 `formats` 顺序解析。同时冻结：`formats` 顺序即优先级（**D-112 第 1 条已裁**，见 §3）。

---

### A5 —— `quarantinePolicy` **缺 missing-field / null-value 旋钮**　【**阻塞**】

**现状**：旋钮全集实测只有 `{unknownEventType, unknownField}` 两个：

- `missingField` 全仓 **0 命中**；
- `nullValue`（画像语义）**0 命中**；
- `unknownField` **4 命中且全在画像 JSON 文本内，生产代码 0 读者**。

**两种「没有值」的语义分裂**（设计书 `:147` 只定义了**一种**）：

| 态 | 设计书定义 | 应否隔离 |
|---|---|---|
| **键不存在** | `:147` 「缺失的映射项 = "该源没有这个语义"（不是默认值），平台按"缺语义"处理而不是猜」 | **未定**——「按缺语义处理」是"跳过"还是"隔离"？ |
| **键存在但值为 JSON `null`** | **完全没有定义** | **未定** |
| **键存在但值为空串 `""`** | **完全没有定义** | **未定** |

**实测后果（已入账 F-92）**：B2 的 **16 行 null 被静默 ACCEPT**（无任何记录面）；另 **4 行 `cost:null` 被误报** `金额格式违规: cost 类型异常`——该措辞引用了**该源从未发送的规范字段名**。

**指导书 `:348` 只覆盖到「必填字段缺失 ⇒ quarantine」**，未区分上述三态，也未规定**可选字段**缺失怎么办。

**建议条款**：`quarantinePolicy` 增两个旋钮（**加性**）：

| 旋钮 | 建议取值闭集 | 语义 |
|---|---|---|
| `missingField` | `REQUIRED_QUARANTINE`（默认）／`OPTIONAL_KEEP_NULL`／`OPTIONAL_DROP` | 键**不存在**时：必填 ⇒ 隔离；可选 ⇒ 保留为显式 null 或删除 |
| `nullValue` | `KEEP_NULL`／`QUARANTINE`／`AS_MISSING` | 键存在但值为 `null`/空串时的处置；`AS_MISSING` 表示「与键不存在同判」 |

**必须同时冻结的一条**：无论取何值，**「观测到 null」必须在某个记录面上可见**（不得静默）——依据指导书 `:348`「必填字段缺失…全部进入 quarantine，并**保存** source instance、mapping version、reason code 和原始位置」的反面推论：**不可解释的静默接受**必被审计反查。

> 载体建议已在 `p5/mapping/MAPPING-NOTES.md:69-70` 与 `p5/IMPL-REPORT.md:120` 提出（**登记未实现**）。本草案**升级为待裁条款**。

---

### A6 —— **原因码对「字段级失败」的覆盖**　【**阻塞**】

**现状**：两处已冻结/已提出的原因码集合**互不相同**：

| 集合 | 出处 | 内容 | 状态 |
|---|---|---|---|
| 计划书六类 | 计划书 `:199` | `UNKNOWN_EVENT_TYPE`、`FIELD_REQUIRED`、`ENUM_UNKNOWN`、`TIME_PARSE`、`AMOUNT_PARSE`、`PROFILE_VERSION` | 计划（⑤ 历史文档层），**未升格为冻结契约** |
| D-116 冻结集 | `RULINGS-P2-02-BATCH2-20260912.md:56-58` | 既有 5：`EMPTY_FIELD`、`DUPLICATE_EVENT`、`BAD_ENUM`、`BAD_AMOUNT`、`FUTURE_TIME`；新增 2：`BAD_TIME_FORMAT`、`UNSUPPORTED_SCHEMA_VERSION` | **已裁决**，载体 `dwd_reject_record.reject_reason` ＋ `quarantine_record.reason` |
| P2-02 建议集 | `p2-02-spec-draft.md:204-211` | 7 个：`JSON_PARSE_ERROR`、`PAYLOAD_NOT_OBJECT`、`MISSING_REQUIRED_FIELD`、`MISSING_PAYLOAD_FIELD`、`UNKNOWN_EVENT_TYPE`、`UNSUPPORTED_SCHEMA_VERSION`、`BAD_BEHAVIOR_ENUM`、`BAD_AMOUNT_FORMAT` | 建议（待裁） |

**缺口**：规格里**没有**任何码能表达 P3-02 执行器必须产出的这几类判决：

| P3-02 需要的判决 | 现有码能表达吗 |
|---|---|
| 必填字段**缺失** | 计划书 `FIELD_REQUIRED`；P2-02 建议 `MISSING_REQUIRED_FIELD`／`MISSING_PAYLOAD_FIELD`（**两名并存，需择一**） |
| 字段**存在但值为 null** | ❌ **无码**（且 A5 未定 ⇒ 甚至不知该不该拒） |
| 字段**类型不符**（如 `price` 给了对象） | ❌ **无码** |
| 金额**单位换算后溢出/非法 scale** | 部分（`BAD_AMOUNT`／`AMOUNT_PARSE`，但未覆盖"单位"） |
| 时间**格式歧义**（同时命中两种 `formats`） | ❌ **无码**（`TIME_AMBIGUOUS_LOCAL` 仅覆盖 DST 重叠，且建议"先不定义"） |
| 时间**格式不在白名单** | P2-02 建议 `TIME_FORMAT_NOT_ALLOWED`（标注为**可合并项**） |
| 枚举值**在画像中登记为 `null`**（观测到但未裁定） | ❌ **无码**（且设计书附录 A 明确：**不进 quarantine**，只登记 DQ） |
| 画像**自身非法**（如 value 撞目标） | 计划书 `PROFILE_VERSION` 只覆盖版本，**不覆盖语义非法** |

**另有两处命名/数目不一致必须一并裁决**：
- 计划书说**六类**，看板 `:229` 验收口径也写**「六类原因码」**；但 D-116 冻结集是 **7 个**（5 既有 + 2 新增），P2-02 建议集是 **7–8 个**。**三处数目对不上**。
- 计划书用 `FIELD_REQUIRED`／`TIME_PARSE`，D-116 用 `BAD_TIME_FORMAT`／`EMPTY_FIELD`，P2-02 建议用 `MISSING_REQUIRED_FIELD` ⇒ **同一现象三个名**，违反 D-116 第 4 条「**禁止**自由拼装原因文本」的精神。

**建议条款**：以 D-116 的「封闭＋加性＋注释即注册表」为**形制**，一次性冻结 **P3-02 字段级判决所需的全集**，并**明确宣布**看板「六类」的所指（建议理解为「计划书六类＝P3-02 的**最小**必需集」，其余为加性扩展）。**禁止**实施方新增码（新增码必须与 `warehouse/ddl/01-dwd.sql:74` 注释同批提交，且 P2-02 未开工 ⇒ 该注释的 owner 归谁须一并裁）。

---

### A7 —— 扁平 `fieldMapping` 的**嵌套路径**表达力　【**可延后，但建议一并冻】**

**现状**：设计书 `:122` 的 `fieldMapping` 是**扁平** key。而 `fixture-b.v1.json:47` 有 `"order_lines": "items"`——`items` 是**数组**，数组内层字段（`items[].unit_price`）**无法用扁平 key 表达**。

**实测证据**（`p5/mapping/MAPPING-NOTES.md:73`）：`items[]` 内层金额**只做文本格式检查**（`EventContractValidator.java:125-134` 只遍历 `unit_price`/`discount`/`amount` 三个**硬编码**键名），**不做映射**。

**影响**：`order_created`/`order_paid` 的**金额口径**（`amount` vs `total_amount`）落不到行级 ⇒ ADS/GMV 口径可能失真。

**建议条款**：本轮**可**限定为「嵌套路径**不映射**，数组原样保留进 `payload`」并**显式登记为能力限制**（须有可见载体，见 A10）；若要求映射，则 `fieldMapping` 需引入路径语法（**新语义，须裁决**）。

---

### A8 —— **金额类型与单位**（分 vs 元、字符串 vs 数字 vs null）　【**阻塞**】

**现状**：规格**完全没有**金额单位的载体。`fieldMapping` 只有「源字段 → 规范字段」，**没有任何 scale/unit/type 的位置**。

**实测事实**：`fixture-b` 的金额字段**全部以「分」为单位**（`list_price_fen`/`cost_fen`/`unit_price_fen`/…，`MAPPING-NOTES.md:38`），而规范侧 `canonical-event.v1.schema.json` 的 `$defs/amount` ≠ 分。**从"分"到规范单位的换算规则没有任何规格**。

**四态实测行为**（`EventContractValidator.java:113-136`）：

| 输入态 | 现行为 | 规格说该怎样 |
|---|---|---|
| 文本且匹配 `^\d+(\.\d{1,2})?$` | 放行（`:117` 取反后不命中） | — |
| **JSON 数字** | **放行**（`:117` 只对 `isTextual()` 跑正则；`:120` 的 `!isNumber()` 使数字走不进报错分支） | **未定义**（已入账为缺陷 G3） |
| **JSON `null`** | **误报**「类型异常」（`:120` 条件对 `NullNode` 成立）⇒ 措辞还引用了**该源未发送的规范字段名** | **未定义**（已入账为缺陷 G4） |
| 文本但不匹配（如 `"12.345"`） | 隔离 `金额格式违规: key=值` | — |

**影响**：不定则 ① B3B-C 的 7 行「JSON 数字金额」继续**静默放行**（已入账）；② 「分」被当规范单位的风险直接污染 GMV/客单价口径；③ 「数字/null/文本」三态无法进入测试的**期望**（本任务要求写三态负例，但**期望从规格里推不出来**）。

**建议条款**：`fieldMapping` 的 value 增**对象形态**（加性）：`{ "target": "amount", "type": "DECIMAL", "unit": "FEN", "scale": 100 }`——即 `p5/IMPL-REPORT.md:71` 已登记的建议载体。**并冻结三态判据**：JSON 数字 ⇒ 按 `type` 收敛（不再是"放行"）；JSON `null` ⇒ 归 A5 的 `nullValue` 旋钮（不再是"类型异常"）；文本 ⇒ 按 `type`＋`scale` 解析。**同时冻结**：`AMOUNT_PATTERN`（`EventContract.java:18`，契约 `$defs/amount`）是**规范侧**约束，**不得**用来校验源侧单位。

---

### A9 —— 归一**输出形态**与**幂等键归属**　【**部分阻塞**】

**现状**：

- `LocalFileIngestor.java:138` 原文 `byte[] out = (text + "\n").getBytes(StandardCharsets.UTF_8);` ⇒ **accepted 目录里写的是源行原文**。归一之后，accepted 里写的是「归一后行」还是「源行 ＋ 归一产物另存」？**未定义**。
- 归一出错时，`QuarantineRecord`（`:149-155`）只 set `batchId`/`eventId`/`schemaVersion`/`reason`/`rawPath` ⇒ **无行号、无 mapping version、无原因码列**（与指导书 `:348` 要求的四要素「source instance、mapping version、reason code、原始位置」**实测 0 项完全满足**，已入账）。
- `IngestionService.java:300` 原文 `manifest.put("mappingVersion", null);` ⇒ 归一发生了但清单**说不出来**。
- 幂等键：设计书 §4.4 `:167-168` 说 `event_id` 是「契约事件 id（幂等键）」、「`source_system` 来自 `source_registry.source_code`」；`canonical-event.v1.schema.json:10` 与 `docs/contracts/event-contract.md:12`/`:165` 要求按 **`(source_instance_id, event_id)`** 去重（D-121 已据此裁 P2-04-a 可实施）。**归一后 `event_id` 由谁铸造**（源字段映射？内容哈希？）**未定义**。

**影响**：`event_id` 是**全链幂等键**——判错等于 DWD 去重失效（既有的 at-least-once 兜底依赖它）。

**建议条款**：① 归一后 `event_id` **只**来自 `fieldMapping` 映射的源字段，**不得**由内容哈希生成（否则同一事件重复投递会得到不同 id，破坏幂等）；② accepted 落盘内容 = **归一后行**（源行在 quarantine 的 `raw_path` 已保真留痕）；③ `mappingVersion` 的取值 = **画像的 `sourceCode` + `profileVersion` ＋ 内容 sha256 前缀**（**须裁决**，因为"版本"与"内容"是两件事）；④ 未通过归一的行走 quarantine，并**至少**补 `lineNo`（`Violation.lineNo` 已存在但 `:136` 写死 0）。

---

### A10 —— **能力限制与 dry-run 的可见载体**　【**可延后，但建议一并冻】**

**现状**：指导书 `:348` 要求「映射激活必须先用样本 dry-run，并输出接受/隔离/错误计数」；计划书 `:225` 要求 dry-run 输出 `input/accepted/quarantine/reason counts/field coverage/enum coverage`。**全仓无 dry-run 实现**（已入账 G-10）。

**未定义**：`field coverage` 与 `enum coverage` 的**分母**（画像声称的条目数？实际观测到的取值数？）、`error counts` 与 `quarantine` 是否同一维度、以及"能力限制"落在哪个响应字段。

**实测现状**：`GET /api/v1/ingestion/status` **没有任何**能力限制/画像路径/映射状态字段；批次清单 `mappingVersion: null` 且**全文 0 次出现源标识**。

**建议条款**：冻结 dry-run 输出契约（键名＋每键的分子/分母定义），并要求采集状态与批次清单增 **`profileCapabilities`**（`{applied, unsupported[]}`）——载体建议已由 `p5/IMPL-REPORT.md:74` 提出（**登记未实现**）。

---

## 3. 已在规格内、**不依赖**上述裁决即可实施的部分（供总控评估最小可交付）

以下各项**有逐字规格依据**，可**不新增任何词汇**地实现（**但本轮按硬性要求 1 未实施**）：

| # | 项 | 依据（原文位置） |
|---|---|---|
| S1 | 流水线**顺序固定**：解析信封→校验来源→事件类型映射→字段提取→枚举映射→时间/金额转换→canonical 校验→写 accepted/quarantine | 计划书 `:196` |
| S2 | 转换**白名单**：`trim`、`case`、`decimal scale`、`epoch/timezone`、受控 `enum map`、`JSONPath 读取`；**禁止**脚本、反射、任意 SQL、网络调用 | 计划书 `:197-198`；指导书 V2.4 `:346` |
| S3 | `eventTypeMapping` 的方向（源事件名 → 规范事件类型）与 `null` 语义（观测到但未裁定） | 设计书 `:116`；附录 A `:305-311` |
| S4 | `enumSemantics` 的方向与 `null` 第三态；`null` **不进 quarantine**、只可被 DQ 统计 | 设计书 `:129`；附录 A `:307-309` |
| S5 | `formats` 是**有序数组**，顺序即优先级（首个解析成功者）；元素只允许命名常量与 Java `DateTimeFormatter` 模式串；未知/非法 ⇒ **画像校验失败**；空/缺失 ⇒ 校验失败 | **D-112 第 1–4 条**（已裁决） |
| S6 | 原因码形制：封闭、加性、`UPPER_SNAKE_CASE`、大小写敏感、唯一 owner、注释即注册表 | D-116；P2-02 草案 R-1…R-8 |
| S7 | `identityPolicy.surrogate` 唯一允许值 = 契约算法名（`HASH64`） | `SurrogateKey.scala:54` 注释 ＋ D-089 |
| S8 | 缺失映射项 = 「该源没有这个语义」，**不得**按默认值补 | 设计书 `:147` |

**⇒ 建议的分期方案（供总控取舍）**：
- **P3-02-a（可立即开工，零新词汇）**：S1＋S2＋S3＋S4＋S5 的**事件类型映射 ＋ 枚举映射 ＋ 时间解析**，**只处理图片已定义的部分**，`fieldMapping` 仅支持「字符串 value ＋ `@keep`」且**碰撞即画像校验失败**（A2 第 2 条）。产出：归一器 ＋ 双画像/双夹具测试 ＋ dry-run 三计数。
- **P3-02-b（须先裁决 A1/A2/A3/A5/A6/A8）**：字段映射全量、`@keep` 完全语义、金额单位/类型、missing/null 旋钮、字段级原因码全集。

> **本草案不预设总控选哪一期**；但若要「一次做完」（任务书原意），则 A1/A2/A3/A4/A5/A6/A8 这 **7 项必须先裁**。

---

## 3bis. **既有基线缺陷**：本分支 HEAD 的 E2 为红（**与 P3-02 无关，但会挡住 E2 门禁**）

### 3bis.1 全反应堆实测（不提前中止的真实计数）

`raw/e1-e2-full-reactor-all-modules.txt`（`A_EXIT=0` 因 `maven.test.failure.ignore=true`，**非"全绿"**）：

| 模块 | Tests run | Failures | Errors | 判定 |
|---|---|---|---|---|
| `platform-common` | 49 | **1** | 0 | **红** ← F-1 |
| `connection-ingestion` | 156 | 0 | 0 | 绿 |
| `warehouse-pipeline` | 124 | 0 | 0 | 绿 |
| `metric-analysis` | 47 | 0 | 0 | 绿 |
| `ai-decision` | 91 | 0 | 0 | 绿 |
| `platform-app` | 100 | **1** | 0 | **红** ← F-2 |
| **合计** | **567** | **2** | 0 | **2 处既有基线缺陷** |

> ⚠️ **只跑 `-pl platform-app -am` 会误判**：该命令在 `platform-common` 即中止，得到「1 处失败」并让下游 5 个模块 SKIPPED —— F-2 会被**完全掩盖**。必须跑**全反应堆**才能看到 F-2。

### 3bis.2 F-1：`WarehouseNameLiteralGateTest`（**代码级**，违反行已入库）

失败测试：`WarehouseNameLiteralGateTest.noBareWarehouseNameLiterals`（`platform-common`），违反两行：

```
spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala:145 → * 建后即删，未碰 `dw_dwd.dwd_order_detail`）：
spark-jobs/src/main/scala/com/graduation/analytics/sql/SurrogateKey.scala:160 → *    table spark_catalog.dw_dwd.dwd_order_detail: Cannot safely cast user_key "STRING" to "BIGINT".`
```

| # | 判据 | 实测（`raw/baseline-gate-history.txt`） |
|---|---|---|
| 1 | 是否本泳道造成 | **否**——本泳道未改任何生产/测试代码 |
| 2 | 是否其它泳道的未提交改动造成 | **否**——`git status --porcelain` 未列这两文件；`git diff --stat -- spark-jobs/…` **为空** |
| 3 | 是否在 HEAD 版本中即存在 | **是**——`git show HEAD:<file>` 逐字命中两行 |
| 4 | 门禁何时生效 | 门禁测试最后改动 `cdd36db` **2026-09-12 14:41:59** |
| 5 | 违反行何时引入 | `959626e` **2026-09-12 21:46:16**（**晚 7h04m**）⇒ 在门禁生效**之后**引入 |
| 6 | 是否被禁用/条件跳过 | **否**——无 `@Disabled`、无标签过滤、无 `assumeTrue` |

⇒ 属**代码级既有缺陷**：`git status` / `git diff` 之外，`spark-jobs/**` 的**已提交内容**即为污染源。**必须改 `spark-jobs/**`（本任务明令禁改）或其门禁口径**才能转绿。

### 3bis.3 F-2：`IngestionManifestSourceSchemaTest`（**机器状态级**，非代码缺陷）

失败测试：`IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate`（实际位于 **`platform-app`**，非 `connection-ingestion`）。

断言原文（`IngestionManifestSourceSchemaTest.java:166-168`）：

```java
        assertThat(backfilled)
                .as("前提校验：历史清单一个都不许被回填/改写（D-037 裁决 6）——否则下面的集合对比没有意义")
                .isEmpty();
```

实测被回填的清单：`["40.json", "41.json", "42.json", "43.json"]`。逐文件键集合实测（`raw/baseline-manifest-failure.txt`）：

| 文件 | 键数 | 是否含 P1-05 四键 | mtime |
|---|---|---|---|
| `39.json` | **15** | 否（＝基线形态） | — |
| `40.json` | **19** | **是**（`sourceCode`/`sourceId`/`profileVersion`/`mappingVersion`） | 2026-09-12 09:23:15 |
| `41.json` | **19** | **是** | 2026-09-12 17:22:22 |
| `42.json` | **19** | **是** | 2026-09-12 17:34:54 |
| `43.json` | **19** | **是** | 2026-09-12 21:06:42 |

**归因**：`landing/` 被 `.gitignore:30` 排除 ⇒ 清单是**真实采集 run 在运行期写入的机器状态**，非仓库内固定件；四个文件的 mtime **全部晚于**测试引入提交 `f646604`（2026-09-12 09:09:19）⇒ 期间的新 run 按 P1-05 新格式落盘，被该回归断言如实捕获。

⇒ **该断言工作正常，红的是状态**。它的**隐含前提是「测试机上从此不再跑真实采集」** —— 只要跑一次真实 run，该门禁必红。

### 3bis.4 对本任务的影响（**请总控指派 owner**）

- P3-02 硬性要求 E2 必须绿，但 **F-1 与 F-2 都在 P3-02 之前就红**。
- 两者**均不在本泳道可修范围**：F-1 需改 `spark-jobs/**`（禁改）；F-2 需清理/重建 `landing/manifests/` 机器状态或调整门禁口径（**属规格**）。
- 本泳道**不自行裁定**修法，仅登记（见待裁项 **D-P3-02-13**）。

---

## 4. 待裁决清单（**总控逐条回填**）

| 编号 | 待裁问题 | 本草案建议 | 阻塞级别 |
|---|---|---|---|
| **D-P3-02-1** | canonical 骨架 owner 归谁？（A1-α/β/γ） | **A1-α**：冻结契约 schema 即 owner，实施侧派生 ＋ 防漂移守卫测试 | **阻塞** |
| **D-P3-02-2** | `fieldMapping` value 是否允许对象/按事件类型分组？同目标冲突如何处置？ | 允许分组；**同事件类型内碰撞 ⇒ 画像校验失败** | **阻塞** |
| **D-P3-02-3** | `@keep` 的键名、作用位置、与其他键撞名的处置 | 保留**源键名**原文写入 `payload`；信封层字段移入；撞名 ⇒ 校验失败 | **阻塞** |
| **D-P3-02-4** | `timePolicy.field` 是源侧还是规范侧？ | **源侧**；须能经 `fieldMapping` 翻到 `event_time`，否则校验失败 | **阻塞** |
| **D-P3-02-5** | `quarantinePolicy` 是否增 `missingField`／`nullValue`？取值闭集？ | 增；取值见 A5 表；「观测到 null」必须可见 | **阻塞** |
| **D-P3-02-6** | P3-02 字段级原因码的**冻结全集**；计划书「六类」与 D-116「7 个」如何统一？`BAD_TIME_FORMAT` 是否复用为 `TIME_PARSE`？ | 以 D-116 形制一次性冻结；「六类」＝最小必需集 | **阻塞** |
| **D-P3-02-7** | 金额的 `type`/`unit`/`scale` 载体 ＋ 数字/文本/null 三态判据 | `fieldMapping` value 对象形态；三态判据见 A8 | **阻塞** |
| **D-P3-02-8** | 归一输出形态（accepted 写归一后行？）＋ `event_id` 铸造 ＋ `mappingVersion` 取值 | 写归一后行；`event_id` 只来自源字段；`mappingVersion`＝`sourceCode@profileVersion+sha256`前缀 | **阻塞** |
| **D-P3-02-9** | 嵌套路径（`items[]`）是否映射？ | 本轮不映射 ＋ 显式登记能力限制 | 可延后 |
| **D-P3-02-10** | dry-run 输出契约（键名＋coverage 分子/分母）＋ `profileCapabilities` 载体 | 见 A10 | 可延后 |
| **D-P3-02-11** | 是否采用 §3 的**分期**（P3-02-a / P3-02-b）？ | 建议采用，先拿 E1/E2 | 决策项 |
| **D-P3-02-12** | 新增原因码须写进 `warehouse/ddl/01-dwd.sql:74` 注释（D-116 第 3 条）；但该文件属 `spark-jobs`/`warehouse` 侧，而 P2-02 未开工 ⇒ **该注释的写入 owner 是谁**？ | 须明确 owner，否则 D-116 第 3 条无法履行 | **阻塞（流程）** |
| **D-P3-02-13** | **既有基线缺陷 2 处**（§3bis）：F-1 代码级（`spark-jobs/**` 注释含裸库名，门禁红）／F-2 机器状态级（`landing/manifests/40-43.json` 被回填，回归断言红）。修不修？谁修？门禁口径是否排除注释行 / 是否重设清单基线？ | 本泳道**不裁定**（F-1 需禁改目录；F-2 属规格）。请总控指派 owner，否则 P3-02 的 E2 无法通过 | **阻塞（门禁）** |
| **D-P3-02-14** | **次序**：看板台账行 525（人裁决，**【以此为准】**）⑥ 定「**先修 IT 安全边界 → 配好隔离环境 → 验证 F-88 → 再继续 P3/P5 与集群闭环**」。P3-02 属 P3 ⇒ **被后置**。本泳道是否应按新次序暂停？还是先行裁决 §4 的 D-P3-02-1…13 备料（**不写码**）？ | 本泳道**不主张例外**；建议：**先按新次序执行前置三项**，同时用**只读裁决**清掉 §4 的阻塞项，使 P3-02 开工时无待裁 | **阻塞（次序）** |

---

## 5. 影响面

| 维度 | 影响 |
|---|---|
| **代码面** | `analytics-server/connection-ingestion`（新增 `SourceProfile` 模型＋加载器＋归一器）；`EventContractValidator` 的构造面需从「只吃 `ObjectMapper`」改为可接画像（`EventContractValidator.java:35`）；`LocalFileIngestor` 的 `:136` 调用点需插入归一（依赖注入面 `:60-63` 需增依赖） |
| **契约面** | 若采纳 A5/A7/A8 的对象形态 ⇒ 需 `contract-specs/specs/source-profile.v1.json`（**该文件当前不存在**，`p2-semantic-registry/RECON.md:806` 已登记 G-1：9 键**无机器可读契约**）；`contract-specs/VERSION` 按加法 `2.2.0 → 2.3.0` |
| **画像面** | `mock-mall.v1.json`／`fixture-b.v1.json` 需按裁决**复核**（尤其 A4 的 `timePolicy.field` 读法）；本轮**未改**任何画像 |
| **数据面** | A9 决定 accepted 落盘内容 ⇒ **改变 `landing/accepted/**` 的字节** ⇒ 会影响以 `acceptedBytes`/`checksum` 为基准的既有对账（`manifest` 的 `acceptedBytes`、`checksum`）。**须评估**既有冻结基线的可比性 |
| **下游面** | DWD 投影（P2-04-b 依赖 P3-01/P3-02）、`dwd_reject_record` 原因码、DQ 规则（P2-06）、`/ingestion/status` 与批次清单的 `profileCapabilities` |
| **风险面** | R6-13 类回归（清单/输入被静默替换）；「同一语义两个 owner」复发（A1-β 的风险） |

---

## 6. 可推翻性与回滚

| 项 | 可推翻性 | 回滚 |
|---|---|---|
| A1 骨架 owner | 高——纯归属问题，改归属不动数据 | 删除派生守卫测试即可 |
| A2/A3/A7/A8 的**加性** value 形态 | 高——旧形态（字符串）**继续合法** ⇒ 既有 4 份画像**全部仍然可解析**，无破坏性 | 撤回新增形态的解析分支；画像侧无迁移（本轮未改画像） |
| A5 旋钮 | 中——新增键若给了非默认值会改变 ACCEPT/隔离计数 | 旋钮缺失时按**现状**（不改行为）＝显式默认；建议冻结「缺键 ⇒ 保持今天的行为 ＋ 记 WARN」，使回滚为纯配置 |
| A6 原因码 | 中——D-116 明令「既有码不得改名/删除，新增即 additive、改语义即 breaking」 | 只允许**追加**；已写入的码**不可回填**（P2-02 R-3） |
| A9 输出形态 | **低**——改变 accepted 字节会影响既有对账基准 | 必须**双写过渡**（源行 ＋ 归一产物）或先只做 dry-run 不落盘；**建议**：P3-02-a **不落盘**，只产 dry-run 计数，从而回滚成本＝删代码 |
| A10 dry-run 契约 | 高——新接口，无存量消费者 | 直接撤接口 |

**本轮无破坏性动作**：未写库、未跑链路、未改契约/画像/生产代码、无 `git add/commit/push` ⇒ **回滚成本 ＝ 删除本轮新增的 `docs/acceptance/p3-02-mapping-executor-20260912/` 目录**。

---

## 7. 本轮**未做**（不得声称）

1. **未实施任何生产代码/测试代码**（按硬性要求 1 走 ② 分支，立即停止）。
2. **E3 真机链路 = 未做**（由总控安排）⇒ **不声称端到端已通**。
3. 未跑真实链路、未跑 Spark、未连集群、未写任何数据库；未启停任何服务（PID 61104 未触碰）。
4. 未改冻结契约／指导书／看板／`spark-jobs/**`；未生成指导书新版本。
5. `dry-run` 能力**仍不存在**（G-10 未关闭）；`EventNormalizer` **仍零代码命中**；F-89 **仍未关闭**。

### 7.1 本轮**做了**的验证（E1/E2 现状基线，非 P3-02 的实现证据）

| 门 | 命令 | 结果 |
|---|---|---|
| **E1 编译**（含测试编译） | `mvn -o -f analytics-server/pom.xml '-DskipTests=false' test-compile` | **绿**（`B_EXIT=0`，7/7 模块 BUILD SUCCESS） |
| **E2 模块自动化**（全反应堆） | `mvn -o -f analytics-server/pom.xml test '-Dmaven.test.failure.ignore=true' '-Dsurefire.skipAfterFailureCount=0'` | **红 2 例**：567 例 / Failures 2 / Errors 0 ⇒ **两处既有基线缺陷**（§3bis） |
| E2 子集（易误判，仅留档） | `mvn -o -f analytics-server/pom.xml -pl platform-app -am test` | 在 `platform-common` 即中止，下游 5 模块 SKIPPED ⇒ **只见 F-1，掩盖 F-2** |

> ⚠️ **口径纪律**：E1 绿 ＋ E2 红，且**红的 2 例均早于 P3-02 存在**、**均不在本泳道可修范围内**。
> ⚠️ 因此本任务「E1 编译 ＋ E2 模块自动化双绿」这一条**当前无法达成**，原因**不是** P3-02，而是**基线**（D-P3-02-13）。
> ⚠️ 本节 E1/E2 是**「P3-02 开工前的基线位形」**，**不是** P3-02 已实现的证据（P3-02 一行未写）。
> ⚠️ `-Dmetric.it=true` 未启用，`*IT` 从未运行（既有纪律：真实库 IT 曾引发破坏性数据事件）。
