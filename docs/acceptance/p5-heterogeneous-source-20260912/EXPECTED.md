# EXPECTED.md — P5 异构源人工配置接入验证 · **跑前冻结的期望**

> **冻结时刻**：2026-09-12 22:05:39 +08:00（分支 `remediation/r1-boundary`，HEAD `dba4381`）
> **冻结时的运行状态（实测，非推断）**：
> - 本轮**尚未调用任何**采集 / 链路接口。本文件落盘前的全部工作只有两类：① 生成夹具/画像等**新增文件**；② **只读**阅读生产代码与**只读** SQL 快照。
> - `analytics_meta.pipeline_run`：0 行 `RUNNING`（最近一次 = id 47 SUCCESS）。
> - `landing/events`：61 个历史文件（406,496,411 B）原样未动，逐文件 sha256 已存 `raw/pre-landing-events-inventory.txt`。
> - **本文件一经冻结，任何实测结果都不得反向修改本文件。**若确需补充，只能追加 `EXPECTED-AMENDMENTS.md` 并写明补充理由与时点。

## 0. 三种口径必须严格区分（本文件与 IMPL-REPORT 的核心纪律）

| 口径 | 含义 | 来源 | 可否被结果影响 |
|---|---|---|---|
| **期望（应然）** | 裁决 D-142 §3 要求平台**应该**做到什么 | 人工裁定文本 | **不可**。写死在 `fixtures/expected/*-expected-outcomes.tsv` |
| **预测（实然预登记）** | 我在**跑之前**读代码推断平台**会**怎样做 | 静态阅读，逐条给 file:line | **不可**。写死在本文件 §4 |
| **实测（实然）** | 平台**实际**给出的批次/逐行结果 | 运行输出 | 只进 `IMPL-REPORT.md` |

**期望 ≠ 预测**。B1 的期望是"归一后入库、产出可对账指标"，预测是"100% 被隔离" —— 二者矛盾**本身就是本轮要交付的结论**，不是失败。

## 1. 三组夹具设计（固定种子 = 20260912）

生成器：`fixtures/tools/gen-fixtures.mjs`（`mulberry32(20260912)`，**无** `Date.now()`/随机源；重复运行 sha256 逐字节一致，已实测两次一致）。
业务日 **2026-09-20**（与其它泳道的 2026-09-01 **刻意隔离**，杜绝任何分区分流混淆）；时区 `+08:00`；源内自称 `source_system = fixture-b`。

### 1.1 与源 A 的**有意撞号**（用于验证「不串源」）

| 维度 | 源 A（mock-mall 金样例）已有取值 | 本夹具复用取值 |
|---|---|---|
| user id | `1` `2` `3` | 同 |
| product id | `1` `2` `3` `4` | 同 |
| order id | `1001`… | `1001`…`1010` |
| payment id | `P-1001`… | 同规则 `P-<order_id>` |
| session id | `s-1` `s-2` `s-3` | 同 |

理由：若平台存在**跨源主键串用**（surrogate key 未按源隔离），撞号是唯一能把它暴露出来的设计。反过来说，**撞号而指标不串**才算证据；不撞号的"干净"数据证明不了任何事。

### 1.2 五个夹具文件

| 文件 | 组 | 行数 | 词汇 | 设计要点 |
|---|---|---|---|---|
| `b1a-envelope-vocab.jsonl` | **B1** | 30 | **信封 + payload 全异构** | 信封键名全换（`msg_id/msg_kind/occurred_ms/received_ms/origin/contract_rev/corr_id/body`）；业务种类 6×5：`user_signup/item_new/item_view/order_new/order_pay/stock_hold` |
| `b1b-payload-vocab.jsonl` | **B1** | 30 | 信封**规范**、payload **异构** | `buyer_uid/item_sku/visit_no/act_kind/terminal`、`order_lines[].qty/unit_price_fen/line_amount_fen`、`order_total_fen`、`paid_fen` 等；事件 12×behavior + 6×order_created + 6×order_paid + 6×user_registered |
| `b2-missing-optional.jsonl` | **B2** | 60 | **规范**（隔离"缺可选字段"这一个变量） | 3 族×20：B2-1 缺「契约必需但闸门未查」字段；B2-2 字段显式置 `null`；B2-3 缺源声明为附加/可选的字段 |
| `b3a-missing-required.jsonl` | **B3** | 30 | 规范 | 3 族：B3A-1 缺闸门必查 payload 字段（12）；B3A-2 缺「契约必需但闸门未查」字段（10）；B3A-3 缺信封必需字段/payload（8） |
| `b3b-ambiguous-mapping.jsonl` | **B3** | 30 | 部分异构 | 4 族：B3B-A1 同事件同层两个候选金额字段且**无** `amount`（4）；B3B-A2 有 `amount` 但另有取值不等的候选（4）；B3B-B 画像中 `enumSemantics = null` 的未裁定取值（8）；B3B-C 金额为 JSON 数字（类型/单位双重歧义，7）；B3B-D `n/d/yyyy` 时间同时匹配两种候选格式（7） |

> B2/B3a 刻意使用**规范词汇**：这两组要检验的是"缺字段/歧义**本身**有没有被识别"，若同时换成异构词汇，就会被 B1 的"词汇不认识"掩盖成一个笼统的隔离计数，**测不出**目标。

## 2. 逐族期望（应然，源自 D-142 §3）

| 族 | 行数 | 期望结论 | 期望理由类别 |
|---|---|---|---|
| B1A-信封异构 | 30 | **ACCEPT** | 经画像 `eventTypeMapping`/`fieldMapping` 归一后入库，产出**可对账**指标（B1 必需信息完整） |
| B1B-payload异构 | 30 | **ACCEPT** | 同上 |
| B2-1-缺契约必需(闸门未查) | 20 | **ACCEPT** | 缺**可选**字段必须由**显式规则**处理，并在采集/质量结果中**展示能力限制**；不得静默补造值 |
| B2-2-字段为null | 20 | **ACCEPT** | 同上（且须区分"观测到空值"与"未观测"） |
| B2-3-缺源声明可选字段 | 20 | **ACCEPT** | 同上（可选字段缺失不得影响主线指标） |
| B3A-1-缺闸门必需字段 | 12 | **QUARANTINE** | `payload 缺失必要字段: <字段>` |
| B3A-2-缺契约必需(闸门未查) | 10 | **QUARANTINE** | 拒绝或隔离并指明缺失必需字段（**不得**静默按可选处理） |
| B3A-3-缺信封必需字段 | 8 | **QUARANTINE** | `缺失必要字段: <字段>` |
| B3B-A1-字段映射歧义(无规范字段) | 4 | **QUARANTINE** | 拒绝或隔离并指明「字段映射歧义」，**不得**任选其一 |
| B3B-A2-字段映射歧义(有规范字段) | 4 | **QUARANTINE** | 存在未裁定权威来源 ⇒ 不得静默采信 `amount` 而丢弃 `pay_money` |
| B3B-B-未裁定枚举 | 8 | **QUARANTINE** | 拒绝或隔离并指明未裁定取值（D-140 §3：观测到但未裁定的取值不得猜语义） |
| B3B-C-金额类型歧义 | 7 | **QUARANTINE** | 拒绝或隔离并指明金额类型/单位歧义 |
| B3B-D-时间格式歧义 | 7 | **QUARANTINE** | 拒绝或隔离并指明时间格式歧义 |

合计期望：**ACCEPT 120 行 / QUARANTINE 60 行**（B1 60 + B2 60 = ACCEPT；B3 60 = QUARANTINE）。

逐行期望见 `fixtures/expected/{B1,B2,B3}-expected-outcomes.tsv`（`row_id / family / expected_outcome / expected_reason_class / note`，共 180 行）。

## 3. 独立推算的对账基准（只依据夹具原始行 + 画像声明的映射，不读平台任何输出）

推算规则（`gen-fixtures.mjs` §独立对账基准）：

- `pv` = `behavior` 事件中 `behavior_type` 归一后语义为 `view` 的行数（b1b 12 行行为全为 view；b1a 仅 `view_action = BROWSE` 的 2 行算 view）
- `uv` = 业务日 2026-09-20 内出现过的**去重** `user_id` 数
- `order_count` / `orders_created` = `order_created` 语义事件数（b1a `order_new` 5 + b1b `order_created` 6）
- `sale_amount` = `order_paid` 语义事件的 `amount` 之和（b1a `order_pay` 5 + b1b `order_paid` 6）

| 指标 | 基准值 |
|---|---|
| pv | **14** |
| uv | **3** |
| order_count | **11** |
| orders_created | **11** |
| sale_amount | **3652.00 元**（365200 分） |

> **口径声明**：以上是**本方**依据规范语义独立推算的口径；平台侧口径由 `spark-jobs` 的 ADS SQL 定义，二者是否逐字一致**本轮未验证**（指标级对账需要激活源 B 并跑链路，**未获批准**）。因此：**指标对账 = 未测**；本基准的用途是"若平台真的产出了指标，它必须等于这些值才叫可对账"。

## 4. 【预登记】静态预测（跑前写死；依据 = 只读代码阅读）

预测依据的关键事实（本轮亲自 grep，原文见 `raw/static-contrast-profile-keys-consumed.txt`、`raw/static-contrast-mapping-executor-absent.txt`）：

- `LocalFileIngestor.java:136` `validator.check(text, 0)` —— 行文本直送闸门，**无**归一/映射步骤。
- `EventContractValidator.java:35` 构造函数只吃 `ObjectMapper`；`:22-28` 词汇表硬编码（12 个规范类型 / `1.0` / 5 个 behavior 白名单）。
- 画像 6 个语义键的**取值**在生产代码中读取面 = **零**（仅 `SourceProfileValidator.java:35-44` 出现键名）。
- `SourceProfileValidator.java:137` 判定 = `exists && jsonObject && sourceCodeMatches && profileVersionMatches && missingKeys.isEmpty()` ⇒ 只有键存在性。
- 闸门检查顺序（`:42-85`）：JSON 解析 → 对象 → 信封 7 键（`event_id,event_type,event_time,ingest_time,source_system,schema_version,trace_id`，`isBlank(text(...))` 判定，**JSON null 亦算缺失**）→ `payload` 存在且为对象 → 类型闭集 → 版本闭集 → payload 必要字段（`:89-111`，`v == null || v.isNull() || 文本空白` 算缺失）→ behavior 白名单 → 金额形态（`:113-136`）。
- `event_time` **完全不校验格式**；金额只在"既非文本也非数字"时报错。

| 族 | 行数 | **预测实际结论** | **预测的实际违规原文** | 与期望是否一致 |
|---|---|---|---|---|
| B1A-信封异构 | 30 | QUARANTINE 30 | `缺失必要字段: event_id` | ✗ **完全相反**（期望 ACCEPT） |
| B1B-payload异构 | 30 | QUARANTINE 30 | `payload 缺失必要字段: user_id`（behavior 12 + user_registered 6）／`payload 缺失必要字段: order_id`（order_created 6 + order_paid 6） | ✗ **完全相反** |
| B2-1 | 20 | ACCEPT 20 | —（闸门不查这些字段） | 结论一致，但「显式规则 + 能力限制展示」**无载体** |
| B2-2 | 20 | ACCEPT 20 | —（null 落在闸门不查的字段上） | 同上 |
| B2-3 | 20 | ACCEPT 20 | — | 同上 |
| B3A-1 | 12 | QUARANTINE 12 | `payload 缺失必要字段: <被删字段>`（逐行与期望同串） | ✓ 一致 |
| B3A-2 | 10 | **ACCEPT 10** | —（闸门不查 `paid_at/channel/brand_id/status/city_level/register_time/created_at/completed_at/available_qty`） | ✗ **相反** |
| B3A-3 | 8 | QUARANTINE 8 | 7 行 `缺失必要字段: <字段>`；1 行 `payload 缺失或非对象` | ✓ 一致 |
| B3B-A1 | 4 | QUARANTINE 4 | `payload 缺失必要字段: amount` | 结论一致、**理由不一致**（不是识别出"歧义"，而是"根本没有名为 amount 的字段"）⇒ **偶然正确** |
| B3B-A2 | 4 | **ACCEPT 4** | —（`amount` 存在即过闸；`pay_money` 取值不等**无人过问**） | ✗ **相反** |
| B3B-B | 8 | QUARANTINE 8 | `非法 behavior_type: purchase / add_cart / pay_later / cancel` | 结论一致、**理由不一致**（命中 `BEHAVIOR_TYPES` 硬编码白名单，**不是**识别出"画像未裁定"）⇒ **偶然正确** |
| B3B-C | 7 | **ACCEPT 7** | —（`findBadAmount` 对 `isNumber()` 放行） | ✗ **相反** |
| B3B-D | 7 | **ACCEPT 7** | —（`event_time` 无格式校验） | ✗ **相反** |

预测合计：**ACCEPT 88 / QUARANTINE 92**（对比期望 ACCEPT 120 / QUARANTINE 60）。
预测的**结论级**不符 = B1A 30 + B1B 30 + B3A-2 10 + B3B-A2 4 + B3B-C 7 + B3B-D 7 = **88 行**；另有 **12 行结论正确但理由错误**（B3B-A1 4 + B3B-B 8）。

### 4.1 由预测导出的三条可证伪假设（实测要打的就是这三条）

- **H1**：B1 的 60 行**全部**被隔离，且违规原因是"网关不认识的键名"而非"信息不全"（可证伪：只要出现 1 行 ACCEPT 即 H1 假）。
- **H2**：B3 存在 **28 行被静默接收**（B3A-2 10 + B3B-A2 4 + B3B-C 7 + B3B-D 7），即"缺契约必需字段 / 权威来源未裁定 / 金额类型歧义 / 时间格式歧义"**都不足以让平台拒收**。
- **H3**：B3B-A1 与 B3B-B 的 12 行虽然结局是正确的 QUARANTINE，但**理由串里不会出现"歧义""未裁定""缺失必需"等措辞**，而是出现 `amount`（缺字段）与 `非法 behavior_type`（白名单）⇒ 判定为"偶然正确"。

## 5. 不串源 与 幂等 的期望

| 检查项 | 期望 |
|---|---|
| 不串源（可测部分） | 我方 accepted 文件里**每一行**的 `source_system` 字段 = `fixture-b`；我方批次**不含**任何源 A 的历史行；我方业务日 2026-09-20 与源 A 的 2026-09-01 在物理目录/分区上不重叠 |
| 不串源（**未测项**） | 库级（ODS/DWD/DWS/ADS）不串源、`source_id` 归因正确性、surrogate key 跨源隔离 —— **需激活源 B 并跑链路，未获批准 ⇒ 未测** |
| 幂等（可测部分） | 同一批文件**重跑采集**：因 `file_checkpoint`（键 = runtime_profile_id + source_id + 绝对路径 + file identity）已推进，第二次运行 accepted 行数 = 0、quarantine 行数 = 0，`ingestion_batch` 不新增业务行 |
| 幂等（**未测项**） | 库级"同一批数据跑两次链路不产生重复业务行" —— 同上，未测 |

## 6. 冻结清单（sha256，本文件落盘时实测）

| 文件 | 字节 | sha256 |
|---|---|---|
| `fixtures/b1a-envelope-vocab.jsonl` | 8950 | `8986C3D08B17B29538797903CC3BBF0B5FA648F639BD61374474C48707AFD594` |
| `fixtures/b1b-payload-vocab.jsonl` | 10406 | `CB5B1FC3ADD2773DB10C56ED1F245B8F171C145DF39A12839ABD16B52EA7FAD1` |
| `fixtures/b2-missing-optional.jsonl` | 20507 | `B07E505E21CB272B1000F79A4777E097CA7FC57D2C1E983D8356F32121208054` |
| `fixtures/b3a-missing-required.jsonl` | 9537 | `70BDEBE126E224D1BF4FA730FDB1B8AD0432C4A6C32DD2FD929FBEA74CD6329F` |
| `fixtures/b3b-ambiguous-mapping.jsonl` | 9894 | `DD5030F17D592930E0739E3D9A9D6F136C9FAB7F27D2FA388DF86450FEEE407C` |
| `mapping/fixture-b.v1.json` | 3581 | `CE2E6DBC791F9FA65592C1CF37242CCFA86B3B293CA65BD713D62E7D7FEF13BD` |
| `mapping/scenario-b2-canonical.v1.json` | 3125 | `57DE1116409BBB2E5B5F20233FAF6986A8A3C5F0C177F75C9B50BDFE49E3266E` |
| `mapping/scenario-b3-ambiguous.v1.json` | 1596 | `1188B19E91336640A72BEC5595D79CB07D9455273A7DE3450D600F5265F1E36A` |
| `fixtures/expected/B1-expected-outcomes.tsv` | 12277 | `FE87B0DF7F0C03B3C9A1DE91710C4494A51C0F566073C7EBA952639FADE27EAB` |
| `fixtures/expected/B2-expected-outcomes.tsv` | 17590 | `7F050B79DD9A9821A8603DF43CD083A7465528D9DE890C923987A5877C2B1C0A` |
| `fixtures/expected/B3-expected-outcomes.tsv` | 12100 | `BDF301170E0D6912618977CCF24CAF26537DD25DBA8D1CB2C7B57EE9EC2D0852` |
| `fixtures/expected/fixture-manifest.json` | 1983 | `A5212467AD1E7F6C793B9BF89FD149D73677600B68E8824AC04BAAEC81F8ABEF` |
| `fixtures/expected/ground-truth-metrics.json` | 904 | `D97FFEF3853E22A23B6B94C5E883CE92452354051DCFD854A60BC9E5BAC86C39` |

**未修改的既有文件**（复核对拍，`raw/baseline-profile-fingerprints.txt`）：源 A 画像 `analytics-server/source-profiles/mock-mall.v1.json` = `0BB8A05C8D5E466864DCB90B8D7B97105DC65497DC3CCB463D104F27F021170B`；`MAPPING-20260912.md` = `E80B357CD8FCE13CD69D6DC0919FEBDB40127EB1974FF5F1A2F50CDED642F52F`。本轮对 `mapping/` 只**新增**三个文件，未改动 `analytics-server/source-profiles/` 下任何文件。
