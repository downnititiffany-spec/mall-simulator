# IMPL-REPORT.md — P5 异构源人工配置接入验证 · 实测实施报告

> 分支 `remediation/r1-boundary`。**开工时 HEAD `dba4381`；测量完成时工作树已被母泳道的台账提交推进到 `29b7016`（`04f9aa7`/`29b7016` 均为 `docs(board)` 提交，未触及源码）。本轮无 `git add` / `commit` / `push`；本泳道未写任何生产代码。**
>
> ⚠️ **测量环境披露（重要）**：实测由**运行中的进程 PID 61104**产生，其 jar 构建于 **17:21:07**、进程启动于 **19:17:49**；同一工作树在 **22:01–22:14** 被**其他泳道未提交地**修改了 13 个 `.java`/`.scala` 文件（不含本泳道；本泳道只新增 `analytics-server/source-profiles/fixture-b.v1.json`）。经实测：① 19:00 之后的所有提交**均未触及**我引用的闸门/采集/登记类（`git log --name-only` 过滤命中 **0**）；② `PipelineService.java` 的并发改动共 50 行，hunk 行号最大 778，**不落在**我引用的三段（`:377-379`、`:938-956`、`:978-1013`）之内。⇒ 结论不受影响；但**本报告 file:line 一律指工作树当前版本**，且**运行中 jar 的字节码未逐指令反编译核验（🟥 未证，见 §8）**。证据：`raw/measurement-integrity-and-corroboration.txt`。
> 权威：`docs/acceptance/m3-step8-parity-20260912/RULINGS-D142-20260912.md` §3 ＋ 父裁决 **D-143**（含"解除污染"三段式升级要求）。
> 口径纪律：**期望**（跑前冻结，见 `EXPECTED.md`）／**预测**（跑前预登记，见 `EXPECTED.md` §4）／**实测**（本文件）三者严格区分；`EXPECTED.md` 冻结后 sha256 复算一致（`D09CD579…141F`）⇒ **测量后未改动**。

---

## 0. 结论（先说三句）

1. **本平台无法仅靠"登记配置"接入一个字段名/枚举/时间语义全异构的源。** 实测 180 行：**92 行结果与冻结期望不符**（期望 ACCEPT 120 / QUARANTINE 60；实测 **ACCEPT 84 / QUARANTINE 96**）。根因是架构级的：**画像 6 个语义键在生产代码的读取面上为零**，"配置驱动接入"目前**只有校验、没有执行**（登记为架构发现 **F-89**）。
2. **B1（词汇全异构、信息完整）60/60 全被隔离**——归一从未发生；且其中 30 行的隔离记录**连 `event_id` 都是 NULL**（源里叫 `msg_id`），异构源在隔离面上连追溯标识都给不出。
3. **B3（应拒绝的歧义/缺必需字段）60 行里有 28 行被静默 ACCEPT**（无隔离、无提示），构成真实业务信息静默失真风险；另 12 行"结局正确但理由错误"（**偶然正确**），必须与"真命中"分开记账。

**顶部标红（未证清单，不得当作已验证）**

> 🟥 **未证 A：库级「不串源」与「指标对账」= 未测**（需**激活源 B** 并跑链路；D-143 未批准激活）。本轮给出的替代证据**只能**证明"入库产物仍归属源 A"，**不能**证明库级隔离，详见 §7.3。
> 🟥 **未证 B：链路各阶段对本批次的处理 = 未测**（本轮**未跑任何 Spark 链路**；父裁决冻结 M3/E5 链路运行）。
> ✅ 唯一被本报告证明为"已解除"的是**清单污染**（三段式证据齐全，见 §8），**不是**上面两项。

---

## 1. 边界与实施纪律

| 项 | 事实 |
|---|---|
| 范围 | 异构源**人工配置接入**验证（能否只靠配置接进来），**不是**"真实第三方商城接入验证" |
| 生产代码 | **零改动**（本轮没有对 `analytics-server/` 下任何 `.java`、`contract-specs/`、既有画像做修改；见 §10 哈希复核） |
| 未实现 | P3-02 / `EventNormalizer` / mapping 执行器（父裁决明确不实现） |
| 源 B | **未激活**（D-143 已批准不激活）；`source_registry.id=2` 停留 **DRAFT**、`current:false`；源 A（id 1）保持 ACTIVE/current |
| 商城名分支 | **零新增**（全部分析脚本只读夹具与产物，无任何商城名分支） |
| 链路 | **未跑任何链路**；`pipeline_run` 全库 `RUNNING` = 0（实测） |
| DB 写入 | 仅经平台 API 产生（源登记 1 次 POST + 3 次 PUT；采集 2 次 POST）；**所有核查 SQL 均为 `SELECT`**；**未修改任何 `ingestion_batch` 状态行**（父裁决明令） |
| 若需改源码 | 未发生；本轮全程无需改码即完成"登记 + 量化缺口" |

---

## 2. 实施流水（实测时间线）

| 时刻 | 动作 | 产物/证据 |
|---|---|---|
| 21:55:51 | `landing/events` 61 文件基线清点（`name\tbytes\tsha256`） | `raw/pre-landing-events-inventory.txt` |
| 21:5x | 只读 DB 快照（pipeline_run / source_registry / runtime_profile / ingestion_batch / manifests） | `raw/pre-db-snapshot.txt` |
| ~22:00 | 生成夹具（固定种子 `mulberry32(20260912)`） | 5 个 JSONL + 3 个期望 TSV + manifest + ground truth；`raw/gen-fixtures-output.txt` |
| ~22:02 | 自查：9 键/哈希/归档副本逐字节相等 | `raw/verify-fixtures-output.txt` = **ALL CHECKS PASSED** |
| 22:07:13 | `POST /api/v1/sources` 登记源 B（`fixture-b`，FILE，Asia/Shanghai，CNY，DRAFT，prefix `het`） | 审计 `operation_audit_log` id **93** `SOURCE_CREATE` SUCCESS |
| 22:07:18 / 22:07:26 | 3 次 `PUT /api/v1/sources/2`（其中一次把 `profile_path` 指向**故意歧义**画像做对照） | 审计 id **94/95/96** `SOURCE_UPDATE` SUCCESS |
| 22:07:2x | 2 次 `POST /api/v1/sources/2/test`：歧义画像与正式画像**各 7/7 通过**（`ok:true`） | `raw/source-b-check-and-test.txt`；**审计无留痕**（最近 10 条只有 93-96） |
| 22:05:39 | **`EXPECTED.md` 冻结落盘**（此后不得反向修改） | sha256 `D09CD579…141F` |
| 22:08:05 | `POST /api/v1/ingestion/runs`（**实测运行 #1**，0.16 s） | 批次 **45**；`raw/ingest-run-1.txt` |
| 22:0x | 只读 SQL 取 96 条隔离理由 | `raw/quarantine-reasons-batch45.tsv`、`raw/ingest-run-1-quarantine-reasons.txt` |
| 22:1x | 逐行对账（期望 × 实测 × 理由） | `raw/actual-outcomes-batch45.tsv`（180 行）、`raw/reconcile-batch45-output.txt` |
| 22:11:07 | `POST /api/v1/ingestion/runs`（**幂等复跑 #2**，文件逐字节未动） | 批次 46：`noNewData:true`、0/0/0；`raw/ingest-run-2-idempotency.txt` |
| 22:11:40 | **清单停车**（45.json ＋ 46.json → `raw/manifest-parked/`）＋ 判据复刻前后对比 | `raw/manifest-pollution-and-release.txt` |
| 22:12:00 | 夹具撤出、62 个基线文件**原路还原**并逐文件 sha256 对拍 | `raw/ingest-step2-restore-baseline.txt`（不符 **0**） |
| 22:12–22:18 | 替代证据、缺失旋钮 grep、契约真子集量化、三段式污染证据、钉住批次权威测量 | `raw/substitution-evidence-source-attribution.txt`、`raw/static-contrast-absent-knobs-and-wording.txt`、`raw/contract-vs-gate-subset.txt`、`raw/manifest-pollution-and-release-three-part.txt`、`raw/pinned-batch-authoritative.txt`、`raw/pollution-release-three-part-conclusion.txt` |

**源登记最终态（实测）**：`source_registry` id 2 `fixture-b` / 异构源 B（P5 人工配置接入验证）/ FILE / `analytics-server/source-profiles/fixture-b.v1.json` / Asia/Shanghai / CNY / **DRAFT** / profileVersion 1.0 / warehousePrefix `het`；id 1 `mock-mall` 仍 **ACTIVE**（`runtime_profile.source_id = 1`，`current` 未变）。
**新增 `file_checkpoint` 5 行**（id 114-118，仅我的 5 个夹具路径；全表 118 = 原有 113 ＋ 我 5）。

---

## 3. 逐行对账总表（实测 vs 期望）

**批次 45**：`batchNo ing-20260912220805-04beeb43`、status **QUARANTINED**、`recordCount 84`、`quarantineCount 96`、`errorCount 0`、`fileCount 5`、`acceptedBytes 28349`；清单 `landing/manifests/45.json`：`status READY`、`sourceCode mock-mall`、`sourceId 1`、`profileVersion 1.0`、**`mappingVersion null`**、`checksum 80281eba`、`schemaVersions ["1.0"]`。

| 族 | 期望 A/Q | 实测 A/Q | 结果不符 | 判定 |
|---|---|---|---|---|
| B1A-信封异构 | 30/0 | **0/30** | **30** | 完全相反 |
| B1B-payload 异构 | 30/0 | **0/30** | **30** | 完全相反 |
| B2-1-缺契约必需(闸门未查) | 20/0 | 20/0 | 0 | 结论一致，但"显式规则+能力限制展示"**无载体** |
| B2-2-字段为 null | 20/0 | 16/4 | **4** | 4 行误报（理由与真实原因无关） |
| B2-3-缺源声明可选字段 | 20/0 | 20/0 | 0 | 同上"无载体" |
| B3A-1-缺闸门必需字段 | 0/12 | 0/12 | 0 | **真命中** |
| B3A-2-缺契约必需(闸门未查) | 0/10 | **10/0** | **10** | 静默接受 |
| B3A-3-缺信封必需字段 | 0/8 | 0/8 | 0 | **真命中** |
| B3B-A1-映射歧义(无规范字段) | 0/4 | 0/4 | 0 | **偶然正确** |
| B3B-A2-映射歧义(有规范字段) | 0/4 | **4/0** | **4** | 静默接受 |
| B3B-B-未裁定枚举 | 0/8 | 0/8 | 0 | **偶然正确** |
| B3B-C-金额类型歧义 | 0/7 | **7/0** | **7** | 静默接受 |
| B3B-D-时间格式歧义 | 0/7 | **7/0** | **7** | 静默接受 |
| **合计** | **120/60** | **84/96** | **92** | 结果相符 88 |

**对账可信度自证**（`reconcile-actual.mjs` 输出）：① 期望表行序与夹具行序不一致数 = **0**；② 隔离记录 `event_id ↔ 行 id` 连接不一致数 = **0**。

**与预登记预测的偏差**：预测 ACCEPT 88 / QUARANTINE 92，实测 **84/96** ⇒ 相差**恰好 4 行**，全部是 B2-2 中 `product_created` 的 `cost: null` 行（`b2-2-evt-0025/0030/0035/0040`）。根因：`EventContractValidator.java:113-136` `findBadAmount` 对 **JSON `null`** 判 `!isTextual() && !isNumber()` ⇒ 报 `金额格式违规: cost 类型异常`。**我在预登记时漏了这一条**（预测写的是"null 落在闸门不查的字段上 ⇒ 全 ACCEPT"），实测推翻了它——这正是"预测可被实测推翻"的设计意图，且**未反向修改期望**。

---

## 4. 父裁决要求的三项专项证据（＋1 项平台缺陷取证）

### 4.1 ① B1「全隔离」的逐行证据（每行违规原因 ＋ 计数）

**逐行明细**：`raw/actual-outcomes-batch45.tsv` 中 `family ∈ {B1A-信封异构, B1B-payload异构}` 的 60 行，每行都有 `actual_outcome=QUARANTINE` 与 `actual_reason`。**每行恰好 1 条隔离记录**（闸门命中即返回，不累积）。

| 族 | 行数 | 每行违规原因（逐行一致） | 计数 |
|---|---|---|---|
| B1A-信封异构 | 30 | `缺失必要字段: event_id` | 30 |
| B1B-payload异构（behavior 12 ＋ user_registered 6） | 18 | `payload 缺失必要字段: user_id` | 18 |
| B1B-payload异构（order_created 6 ＋ order_paid 6） | 12 | `payload 缺失必要字段: order_id` | 12 |

**性质**：违规全部是"**按规范键名找不到键**"，不是"信息不全"——夹具里 `msg_id`/`buyer_uid`/`order_ref` 对应的信息**全部存在**。逐行结果是 `ACCEPT 0 / QUARANTINE 60`，**H1 未被证伪**（见 §5）。

**附加实测（可追溯性缺口，本轮新发现）**：`quarantine_record` 中 batch 45 共 96 条，其中 **`event_id` 为 NULL 的 31 条**、**`schema_version` 为空的 31 条**；按文件分布：`b1a-envelope-vocab.jsonl` **30/30 全 NULL**、`b3a-missing-required.jsonl` 1 条 NULL（该行信封 `event_id` 被删）、其余文件 0 条。⇒ 异构源的隔离记录**丢失事件标识与契约版本**（源里叫 `msg_id`/`contract_rev`），运营侧无法按事件追溯。证据：`raw/b1-quarantine-traceability.txt`。

### 4.2 ② B2「能力限制无处安放」：缺失的旋钮名 ＋ 建议载体

**缺失的旋钮名（实测 grep，全仓库）**：`missingField` = **0 命中**；`nullField` = 2 命中但均为 Spark JSON 序列化配置（与画像无关）；`unknownField` = 4 命中且**全部在画像 JSON 文本里，没有任何生产代码读它**。现有 `quarantinePolicy` 只有 `{unknownEventType, unknownField}` 两个旋钮 ⇒ **"缺字段/空值该不该隔离"这个决策在画像里根本没有位置**。证据：`raw/static-contrast-absent-knobs-and-wording.txt`。

**两处实测症状（同一根因）**：

| 症状 | 实测 | 为什么是"能力限制无处安放" |
|---|---|---|
| 静默放过 | 20 行 null（`city_level`/`register_time`/`brand_id`/`status`/`channel`/`created_at`/`paid_at` 等）中 **16 行被 ACCEPT**，产物与报告里**没有任何地方**记录"这些字段是空值/缺失" | 没有旋钮 ⇒ 没有规则 ⇒ 没有输出面可以声明"本源的这些语义不可得" |
| 误报 | 4 行 `cost: null` 被报 **`金额格式违规: cost 类型异常`** —— 理由引用了**该源从未发送过的规范字段名**，且把"空值"说成"类型异常" | 判定发生在硬编码的规范键名集合上（`EventContractValidator.java:114-115` 的 8 个键），与源声明无关 |

**建议载体（只登记，不实现）**：
1. 画像 `quarantinePolicy` 增 `"missingField"`（如 `REQUIRED_QUARANTINE` / `OPTIONAL_KEEP_NULL` / `OPTIONAL_DROP`）与 `"nullValue"`（区分"观测到空值"与"字段不存在"）；
2. `fieldMapping` 的 value 支持 `{target, optional:true, onMissing:"…"}`，把"可选"从注释变成**机器可读**；
3. **能力限制的展示面**：`GET /api/v1/ingestion/status`（实测字段：`profileState/runtimeProfileId/sourceId/landingUri/landingError/eventsDir/pendingFiles/pendingBytes/checkpointFiles/newFileCount/lastArrivalAt/latestBatch`）与批次清单（实测字段含 `mappingVersion:null`）**都没有**"配置未被应用/不支持哪些能力"的位置 ⇒ 建议增 `"profileCapabilities": {"applied": false, "unsupported": [...]}`。

### 4.3 ③ B3：必须区分「因异构词汇被隔离」与「识别出缺必需字段/歧义而拒绝」

**三分账（实测，逐行）**：

| 类别 | 行数 | 明细 | 证据 |
|---|---|---|---|
| **真命中**（平台确实识别出缺必需字段 / 载荷非对象） | **19** | B3A-1 12 行（逐行各缺 1 个闸门必查字段：`payment_id, amount, session_id, behavior_type, price, category_id, total_amount, items, member_level, age_group, reason, refund_id`）＋ B3A-3 7 行（`trace_id, source_system, schema_version, event_type, ingest_time, event_time` 各 1）＋ B3A-3 1 行 `payload 缺失或非对象` | `raw/actual-outcomes-batch45.tsv` |
| **偶然正确**（结局是 QUARANTINE，但理由**不是**"识别歧义/未裁定"） | **12** | B3B-A1 4 行：理由 `payload 缺失必要字段: amount`（真相是"该源没有名为 amount 的字段"，**恰因闸门按规范键名找键而落网**）＋ B3B-B 8 行：理由 `非法 behavior_type: purchase / add_cart / pay_later / cancel`（各 2 行；真相是"画像把这些取值标为**未裁定**"，平台命中的是**硬编码白名单** `BEHAVIOR_TYPES`） | 同上 |
| **静默接受**（应拒绝却无隔离、无提示） | **28** | B3A-2 10（缺契约必需但闸门未查）＋ B3B-A2 4（`amount` 与 `pay_money` 取值不等，**任选其一无人过问**）＋ B3B-C 7（JSON 数字金额，类型/单位歧义）＋ B3B-D 7（`05/03/2026 14:00:00` 同时匹配两种候选格式） | 同上 |

**措辞证据（可复核的字符串级事实）**：96 条隔离理由里

- `歧义` 出现 **0** 次、`未裁定` **0** 次、`映射` **0** 次、`能力` **0** 次、`不支持` **0** 次；
- 生产代码里 `ambiguous` / `AMBIGUOUS` / `未裁定` 命中 **0**；`歧义` 的 4 处命中全在 MyBatis"多数据源 mapper 扫描歧义"注释里，与业务语义无关。

⇒ **平台当前不存在"识别映射歧义/未裁定语义"的能力**；B3 的 12 行正确结局是**偶然正确**，不能记作"平台拒绝了歧义"。证据：`raw/static-contrast-absent-knobs-and-wording.txt`。

### 4.4 ④ 闸门缺陷逐条取证（file:line ＋ 影响）

| # | 缺陷 | file:line | 影响（本轮实测） |
|---|---|---|---|
| 1 | **闸门必需字段集是契约必需集的真子集** | 契约 `contract-specs/schemas/canonical-event.v1.schema.json` `$defs.<type>.required`（**62** 个 payload 字段） vs 闸门 `EventContractValidator.java:90-103` switch（**37** 个）⇒ 量化：`raw/contract-vs-gate-subset.txt`（逐类型列出"契约有而闸门不查"的字段；另有 `order_created.items` 嵌套 required 5 个字段**完全不查**） | **B3A-2 10 行 + B1B 部分键位被当可选**：缺 `paid_at/channel/brand_id/status/city_level/register_time/created_at/completed_at/available_qty` 的行**静默入库** |
| 2 | **`event_time` 从不做格式校验** | `EventContractValidator.java:56-61` 只判 `isBlank(text(node, required))`；全类无时间解析（`timePolicy` 在读取面上为零） | **B3B-D 7 行**（`05/03/2026 14:00:00`，无法确定 `dd/MM` 还是 `MM/dd`）被 **ACCEPT** |
| 3 | **金额 JSON 数字静默放过** | `EventContractValidator.java:113-123` `findBadAmount` 只拒"既非文本又非数字"（`v.isNumber()` 直接过）；无单位/标度概念 | **B3B-C 7 行**（`amount: 12900`，分还是元？）被 **ACCEPT** |
| 4 | **JSON `null` 金额被误报为"类型异常"**（本轮新发现） | 同上 `:120-122`：`null` 满足 `!isTextual() && !isNumber()` | **B2-2 4 行**被隔离，理由 `金额格式违规: cost 类型异常` —— 把"该源此字段为空"说成"类型错"，且引用了源从未发送的规范字段名 |
| 5 | **信封 `source_system` 与绑定源不一致时无人校验**（本轮新发现） | 采集侧只校验信封键**存在**（`:56-61`），不校验取值；批次归属取 `runtime_profile.source_id`（`IngestionService.java:297-300`） | 夹具每行 `source_system: fixture-b`，批次与清单却记 `sourceCode mock-mall`/`sourceId 1` ⇒ 见 §7.3 |
| 6 | **幂等身份 = 文件创建时间(ms)，不是内容哈希**（静态阅读） | `LocalFileIngestor.java:289-298` `fileIdentity = creationTime().toMillis()`；键 = `runtime_profile_id+source_id+path+file_identity`（`entity/FileCheckpoint.java:18`） | 幂等复跑实测成立（§7.2）；但"同路径同创建时间而内容改写"**不会被识别为新数据**——**静态推断，未实测** |

---

## 5. 预登记假设的验证结果（H1 / H2 / H3）

| 假设 | 内容 | 实测 | 结论 |
|---|---|---|---|
| **H1** | B1 的 60 行全部被隔离，原因是"闸门不认识的键名"而非"信息不全" | B1A 0/30、B1B 0/30；理由全部为 `缺失必要字段: event_id`(30) / `payload 缺失必要字段: user_id`(18) / `order_id`(12) | **未被证伪**（成立） |
| **H2** | B3 存在 **28 行被静默接收**（B3A-2 10 ＋ B3B-A2 4 ＋ B3B-C 7 ＋ B3B-D 7） | 逐族实测 **10/4/7/7**，合计 **28** | **未被证伪（精确命中）** |
| **H3** | B3B-A1 与 B3B-B 共 12 行结局正确但理由串里**不会**出现"歧义/未裁定"，而是 `amount` 与 `非法 behavior_type` | 12 行理由逐行实测 = `payload 缺失必要字段: amount`×4、`非法 behavior_type: {purchase,add_cart,pay_later,cancel}`×8；全文 `歧义/未裁定/映射` = 0 次 | **未被证伪（成立）** |

**预测偏差**：预登记预测 ACCEPT 88 / QUARANTINE 92，实测 84/96（偏差 4 行，根因见 §3 末）。这是**预测**被推翻，**期望**（120/60）保持冻结未动。

---

## 6. 主结论与能力缺口清单

### 6.1 主结论（架构发现 F-89，建议登记）

> **「配置驱动接入」目前只有校验、没有执行。** 画像 9 个顶层键里，**6 个语义键（`canonical`/`eventTypeMapping`/`fieldMapping`/`enumSemantics`/`identityPolicy`/`timePolicy`）在生产代码的读取面上为零**；`SourceProfileValidator` 只判"键是否存在 ＋ `sourceCode`/`profileVersion` 是否匹配"（`SourceProfileValidator.java:35-44,137`），采集链路在 `LocalFileIngestor.java:136` 把**行原文**直送 `EventContractValidator`（其构造函数只吃 `ObjectMapper`，结构上读不到画像）。
> ⇒ 只有 `source_registry` 的绑定与 `warehouse_prefix`（源隔离/库前缀）是**真的被执行**的；**字段级归一、枚举归一、时间解析、身份策略一条都没执行**。

**"能力限制"三处无处安放**：画像（无 `missingField`/`nullValue` 旋钮）／采集状态接口（无 capability 字段）／批次清单（`mappingVersion: null`，且全文 **0 次**出现 `fixture-b`）。

### 6.2 缺口清单（按处置建议排序）

| 优先级 | 缺口 | 建议 |
|---|---|---|
| P0 | 归一/映射执行器不存在（F-89） | 这就是 P3-02 的落点；本泳道**只量化不实现**（父裁决） |
| P0 | 闸门必需字段集 ⊊ 契约必需集（62 vs 37） | 让闸门从**契约 schema** 生成必需集，而不是硬编码 switch |
| P1 | 无歧义/未裁定识别能力（B3 的 28 行静默接受） | 配置校验阶段就应拒绝：同目标多来源、未裁定枚举、多格式无优先级 |
| P1 | `quarantinePolicy` 缺 `missingField`/`nullValue` 旋钮 | 见 §4.2 建议载体 |
| P1 | 金额单位/类型、时间格式优先级无载体 | `fieldMapping`/`timePolicy` 扩展（`unit`/`scale`/`order`） |
| P2 | 隔离记录缺事件标识（31 条 `event_id` NULL） | 隔离时保留**源侧**标识（`msg_id`）与原始行片段 |
| P2 | park 清单会击穿 R6-13「钉住」语义 | park 前自查钉住批次（SQL 见 `README.md` §6.2.2） |
| P2 | 幂等身份用创建时间而非内容哈希 | 身份改内容哈希（或哈希＋时间双因子） |

---

## 7. 不串源 / 幂等 / 替代证据

### 7.1 可测部分：不串源（实测 PASS）

- 我方 `landing/accepted/45` 逐文件逐行统计：`b2` 56 行、`b3a` 10 行、`b3b` 18 行 = **84 行**，其中 `source_system = "fixture-b"` 的行 = **84**，`source_system = "mock-mall"` 的行 = **0**（`b1a`/`b1b` 因全被隔离，accepted 文件为 0 行）。
- 我方批次产物内**不含**任何源 A 历史行；我方的业务日 2026-09-20 与源 A 的 2026-09-01 在物理目录上不重叠。
- 幂等复跑（运行 #2）`fileCount 0` ⇒ 未重复吞入任何文件。

### 7.2 幂等（实测 PASS）

运行 #2（文件与运行 #1 逐字节相同）：`batchId 46`、`status SUCCESS`、`recordCount 0`、`quarantineCount 0`、`errorCount 0`、`fileCount 0`、`acceptedBytes 0`、**`noNewData: true`**；`GET /api/v1/ingestion/batches?limit=4` 显示 46 为空批次、45 仍为 84/96。⇒ **重复导入幂等成立**（判据 = 绝对路径 ＋ 文件创建时间(ms)，`LocalFileIngestor.java:289-298`）。

### 7.3 替代证据（因**未激活源 B** 而采用）＋ **它不能证明什么**

**替代方案（经父裁决 D-143 追认）**：不激活源 B，让夹具在**源 A 绑定**下被采集，从而测"同一台机器上，一个自称 `fixture-b` 的源的数据被采进来时会发生什么"。

**它证明了什么（实测）**：
1. 批次 45 与清单 45.json 归属 = `sourceId 1` / `sourceCode mock-mall`（**无论原始行自称什么**）；
2. manifest 全文出现 `fixture-b` **0 次**，`mappingVersion` = `null` ⇒ 异构源的配置与身份**在产物里无痕**；
3. `runtime_profile.source_id` 仍为 1，源 A 的 `current:true` 未被扰动。

**🟥 它不能证明什么（必须与上面等量阅读）**：
1. **不能证明库级「不串源」**——撞号 id（user 1/2/3、product 1-4、order 1001-1010、session s-1..3）是否在 ODS/DWD/DWS/ADS 层被正确按源隔离，**未测**；本替代路径下所有数据都被归到源 A，**恰恰绕开了**跨源隔离的检验条件。
2. **不能证明「指标对账」**——本轮**未跑任何链路**，独立推算基准（pv 14 / uv 3 / order_count 11 / orders_created 11 / sale_amount 3652.00 元）**没有任何平台侧数字与之对照**。
3. **不能证明"源 B 作为独立源可接入"**——登记只到 DRAFT/`current:false`；激活会**全局切换** `runtime_profile.source_id`（`SourceRegistryServiceImpl.activate`），可能干扰其他泳道，故未执行。
4. **不能证明"异构词汇经配置可归一"**——恰恰相反，实测 B1 60/60 被隔离。
5. **不能替代"真实第三方商城接入验证"**——两者不是同一件事；本泳道**只**覆盖"人工配置接入"这一层。

---

## 8. 污染停车与还原（三段式，摘要）

**完整命令原文与证据见 `README.md` §6.1/§6.2**，此处只登记结论：

| 面 | 事实 |
|---|---|
| ① 判据来源 | **磁盘 `landing/manifests/*.json`**（`PipelineService.java:978-1013`，`:959-971`；DB 只在 `:938-956` 提供"钉住"的 batchId 且仍须读磁盘同名文件）；输入目录由清单对象带出（`:377-379`）⇒ **park 文件有效** |
| ② 当前 max accepted 批次（实测） | **文件面 = 43**（accepted 51 / quarantined 4、mock-mall、业务日 2026-09-01）；**DB 面 = 45**（我的）；**排除我的 45/46 后 DB 面 = 44**（E5 的，accepted 1，业务日 2026-09-12，其实测自 `accepted/44` 产物） |
| ③ park 后是否仍指向夹具批次 | **否**：`43.json` 的 `files[].file` 与 5 个夹具名零交集、`sourceCode mock-mall`；夹具唯一入口 `accepted/45` 已不可达；**DB 里我的批次行保留未改**（元数据事实，不构成链路输入） |
| 结论 | ✅ **污染已解除**（三段式齐全）；**我未修改任何 `ingestion_batch` 状态行**（DB 写入仅经平台 API） |
| 附带发现 | park 会击穿 R6-13「钉住」语义；**本轮实测无风险**（无任何 run 钉住 44/45，最高钉住 43 且 43.json 在盘） |

**独立复核（母泳道 M3 只读钉死，与我的结论一致）**：HEAD 提交 `29b7016`（台账行 518）原文记载 —— 「选批判据来源**钉死为磁盘清单（file:line）**＋ **文件面 max accepted batchId=43（黄金批）**＋ **新缺陷 F-91（park 击穿 WAIT_LANDING 钉住语义）**＋ **陷阱 #59（batchId 提取须锚定正则）**＋ 撤销 M3 重复探测、**DB 批次行保留不动**」。⇒ 上表 ①②③ 三行结论与 M3 独立复核一致；F-91 与陷阱 #59 皆源自本泳道（后者来自我中途回报的 `LIKE` 误命中更正）。

**🟥 本节的唯一「未证」**：运行中 jar（构建 17:21:07）里 `findReadyManifest` 的**字节码未逐指令反编译核验** ⇒ 「**平台运行中实现真的读磁盘清单**」这一点是由**源码 file:line ＋ M3 独立复核**共同支持的，**不是**由运行中进程直接实测的（本轮未跑链路，故平台侧没有第二个观测面）。旁证三条：jar 构建 **17:21:07** 早于一切源码改动；19:00 后提交未触及该类；`PipelineService.java` 的并发改动 hunk 最大行号 778 < 930。若需彻底闭合，建议由有链路权限的泳道跑一次最小 run 观察其选批（本轮**未获批准**）。证据：`raw/measurement-integrity-and-corroboration.txt`。

**`landing/events` 基线还原**：62 个文件（406,496,908 B = 61 个开工前文件 ＋ 别道 21:57 的 `2026091221.jsonl`）逐文件 sha256/长度对拍**不符 0**；`GET /api/v1/ingestion/status` 复测 `pendingFiles 62 / checkpointFiles 62 / newFileCount 0`。

---

## 9. 未测清单（与顶部标红一致）

| # | 未测项 | 原因 |
|---|---|---|
| 1 | 库级不串源（ODS/DWD/DWS/ADS 的跨源隔离） | 需激活源 B ＋ 跑链路；未获批准 |
| 2 | 指标对账（pv/uv/order_count/sale_amount） | 同上；本轮未跑链路 |
| 3 | 链路各阶段对批次 45 的处理（LOAD_ODS 起） | 未跑链路（父裁决冻结） |
| 4 | 「同路径同创建时间、内容改写」是否被识别 | 静态阅读推断为"否"，**未实测** |
| 5 | `activate` 对全局 `runtime_profile.source_id` 的影响面 | 未激活（会造成全局切换） |
| 6 | B2 "显式规则"若要落地，建议载体是否与既有契约冲突 | 建议未评审（本轮不实现） |
| 7 | 运行中 jar（17:21:07）`findReadyManifest` 的字节码级同源性 | 未反编译核验；由源码 file:line ＋ M3 独立复核支持（见 §8） |

---

## 10. 交付物哈希与冻结一致性（末尾复核）

- **冻结成立（双重证据）**：① `EXPECTED.md` 复算 sha256 = `D09CD579FB74B0E08C4DBAC54DC1E3AEBAAD04A63931480DB5B58027E949141F`，与冻结登记值一致；② **更强的独立证据 = mtime 序**：14 个冻结件（`EXPECTED.md` ＋ 5 夹具 JSONL ＋ 5 期望产物 ＋ 3 映射画像）最后写入时间**全部早于运行#1（22:08:05）**，其中 `EXPECTED.md` = **22:06:00** ⇒ 期望与夹具在实测之前定稿、实测之后零改动（该证明不依赖我登记的任何哈希）。唯一晚于运行#1 的文件是 `mapping/MAPPING-NOTES.md`（22:18:53）——**说明文档**，非期望/夹具。
- **对拍脚本自身的错误已于 22:25 修正并留痕**：`raw/final-deliverable-hashes.txt` 初版把契约 schema 的"登记"哈希写成中段臆造的假值（把缩写 `A70AF901…ADE5` 当成可补全），造成一次**假「不符」**。真实值 `A70AF90110FBB5…ADE5` 有**三处独立记录**（本泳道 `raw/canonical-schema-required.txt:3`、跨泳道 `p2-02-multiformat-time-20260912/raw/source-fingerprints-20260912.txt`、跨泳道 `ct-batch-20260912/lane/refresh-fingerprints.log`），与实测完全一致；契约 schema 未被改动（`git status` 干净、最后写入 17:08:58、HEAD 提交 `5690ffb`）。⇒ **陷阱登记：缩写哈希 `XXXX…YYYY` 不得用于全量对拍，登记值必须整串留档。** 见 `raw/measurement-integrity-and-corroboration.txt` §[7]。
- 未改动复核：`analytics-server/source-profiles/mock-mall.v1.json` = `0BB8A05C…170B`、`contract-specs/schemas/canonical-event.v1.schema.json` = `A70AF901…ADE5`、`docs/acceptance/m3-step8-parity-20260912/MAPPING-20260912.md` = `E80B357C…F52F` —— 三者与开工登记值**逐字节一致**。
- 本轮新增文件逐文件 sha256：`raw/final-deliverable-hashes.txt`（含 README/IMPL-REPORT 之外的证据与交付文件）。
- **本泳道未做**：`git add` / `commit` / `push`；未新增任何测试；未改动 `pom.xml` / 契约 / 既有画像。
- **本泳道在本轮唯一的生产树改动 = 新增 1 个文件**：`analytics-server/source-profiles/fixture-b.v1.json`（工单要求的"登记绑定画像"，源 B 的 `profile_path` 指向它；**只新增，不改既有**）。
- **并发披露（不属于本泳道）**：工作树内有其他泳道 **13 个 `.java`/`.scala` 未提交改动**（`MetricQualityGate`/`DataQualityGate`/`QualityChecker`/`RuleSeverity`(新)/`JobResultParser`/`PipelineService`/`TradeDwdJob`/4 个测试类等，时间 22:01–22:14）。本文任何"未改动源码"的表述**只针对本泳道**；这些改动与本文结论的关系见卷首"测量环境披露"与 §8 末段。

---

## 11. 给总控/其他泳道的三条落地建议

1. **把 F-89 作为架构结论登记**：在 P3-02（`EventNormalizer`/映射执行器）落地前，任何"异构源接入"验收都不成立；在此之前，采集侧对应结论只能是"**能登记、不能接入**"。
2. **闸门必需集改由契约 schema 派生**（消除 62 vs 37 的真子集缺口）＋ **`event_time` 增格式校验**＋**金额增类型/单位判定**：这三条是**不依赖** P3-02 也能独立修的高收益项。
3. **park 清单前先查"钉住"**（SQL 见 `README.md` §6.2.2）——否则会静默换掉某 run 的输入（R6-13 回归）。
