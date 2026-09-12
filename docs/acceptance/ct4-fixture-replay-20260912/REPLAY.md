# CT-4 前提物对账（55 条黄金夹具 × canonical-event.v1）：**CT-4 §5 量化勘误 + 未取证回填**

- **任务**：`ct4-fixture-replay-20260912`（总控直接执行，非泳道交付）
- **时间**：2026-09-12 13:4x（机器时间；git `%ci` 为权威时间轴）
- **状态**：`REVIEW`（对账面证据；**不**宣称 CT-4 已具备裁决条件之外的其他结论）
- **证据级别**：**契约合规复算**（脚本可复跑）—— 不是 E1（未编译）、不是 E3（未重跑链路）、不是 E4/E5
- **数据来源（全部现状实测）**
  - 契约 `contract-specs/schemas/canonical-event.v1.schema.json`：867 行 / sha256 前 16 = `0E2E4ED2B17D7DB7`
  - 夹具 `tests/golden-dataset/events/golden-20260901.jsonl`：18,430 B / 55 行 / sha256 前 16 = `2351BCC35E04CCD2` / mtime 2026-09-10 16:52:06
  - 与 `landing/events/r9-m1-123006.jsonl` **字节相等 = 真**
  - 既有真实运行产物：`landing/accepted/30/r9-m1-123006.jsonl`（17,419 B）、`landing/quarantine/30/r9-m1-123006.jsonl`（1,011 B），mtime 09-11 12:30
- **复跑方式（脚本随本目录入库）**
  - `tools/ct4-fixture-replay.py`（逐行契约判定）→ `raw/per-line-verdict.tsv`
  - `tools/ct4-fixture-split.py`（字节/行数切分核验 + 全错误枚举）→ `raw/all-errors.tsv`
  - `tools/ct4-partition-map.py`（行↔分区归属映射）→ `raw/partition-map.tsv`
  - 依赖：本机 Python 3.14.5 + `jsonschema`（**未启用 `format` 断言**，与契约自身口径一致）；打印一律 ASCII（控制台 GBK）

---

## 1. 关闭 CT-4 §5 的「未取证」

CT-4 原文：「上述行号指向真实夹具 `landing/events/r9-m1-123006.jsonl`，**该文件的行数与逐行内容本轮未实测**」。

**现已实测**：

| 项 | 实测值 |
|---|---|
| 行数 | **55**（非空行；`55 = 51 accepted + 4 quarantine`） |
| 逐行内容 | 见 `raw/per-line-verdict.tsv`（55 行逐行判定）与 `raw/all-errors.tsv`（逐行**全部**错误） |
| 夹具↔落地件 | **字节相等**（18,430 B，sha256 `2351BCC3…`）= 真 |
| 分区无损 | `raw/partition-map.tsv`：51 accepted + 4 quarantine，**未匹配 0 行** |
| 字节账 | 17,419 + 1,011 = **18,430 = 夹具字节数**（相等为真） |

**一处必须记下的否定结果**：字节**和**相等，但夹具**不是** accepted 与 quarantine 的字节拼接（实测 `fixture_is_concat=False`、非前缀、非后缀）⇒ 两个产物**各自重写行序/序列化**，`accepted` 中不含不可解析行（`accepted_has_UNPARSEABLE=False`）。**不得**据此宣称"夹具=两件直接拼接"。

---

## 2. 三口径对账（本报告的核心结论）

| 口径 | 结果 | 出处 |
|---|---|---|
| 采集层 validator 分区 | **51 accepted / 4 quarantine** | 既有运行产物（09-11 12:30）；与 RECON 报告所述 manifest 51/4 **一致** |
| 契约（JSON Schema）合规 | **26 VALID / 28 INVALID / 1 不可解析** | 本轮复算 `raw/per-line-verdict.tsv` |
| RECON 报告所述 | 「仅 26 合规、25 违约」 | `p2-semantic-registry-20260912/RECON.md` |

**对账成立**：`28 INVALID` = **25**（落在被接受的 51 行内）+ **3**（落在被隔离的 4 行内：L38、L54、L55）；另 L53 为不可解析行。故
**被接受的 51 行中：26 行合规、25 行违约** —— RECON 的 26/25 **经独立复算通过**，且首次给出口径与逐行依据。
**全量非合规 = 29 行**（25 接受违约 + 3 隔离违约 + 1 隔离不可解析）；**完全合规 = 26 行**。

隔离的 4 行身份（`raw/partition-map.tsv`）：`golden-evt-038`（L38 behavior `behavior_type="purchase"`）、**L53 不可解析行**、`golden-evt-054`（L54 `age_group="45-54"`）、**L55 信封缺 `event_id`**。⇒ **隔离行不在夹具尾部**（L38 在中间），"最后 4 行=隔离件"的说法为**假**。

---

## 3. **CT-4 §5 量化勘误（以此为准）**

CT-4 §5 原文：「**被接受但按契约属脏 = 24 行；被正确隔离 = 1 行；合计 25 行**，与既有"约 25 行"的估计一致但首次给出了口径。」

| # | CT-4 §5 原文 | 实测/按其自身登记表 | 说明 |
|---|---|---|---|
| 1 | 被接受但属脏 = **24** | **25** | 登记表登记的"被接受冲突行"确为 24；**漏登 1 行** = 夹具 **L52 `stock_changed.change_type="restock"`**（枚举 `['inbound','outbound','adjust']` 之外，**被接受**）⇒ `canonical-event.v1.schema.json` 的"真实数据冲突"表**少一类** |
| 2 | 被正确隔离 = **1** | 登记表实为 **2**（L38、L54）；实际被隔离 **4**（L38、L53、L54、L55） | L347 与 L513 两条登记**都**描述了被隔离行 |
| 3 | 合计 = **25** | 按其自身登记表应为 **26**；按实测非合规为 **29** | 24+1=25 与登记表 24+2=26 **自相矛盾** |
| 4 | L538 对 6 行 `order_created` 的冲突描述 = 「`items` 字符串形态」 | 每行实为 **3 处**违规：缺必填 `status`、缺必填 `created_at`、`items` 非数组 | 登记描述**不完整**（实测 6 行同名缺字段：L18/L20/L22/L39/L43/L47） |

**另新增 2 项应登记冲突（登记表未收录）**：
① `stock_changed.change_type` 枚举外值（L52，**被接受**）；
② 信封级必填 `event_id` 缺失（L55，**被隔离**）。
**并新增 1 档而非并入必填/枚举**：③ **不可解析行**（夹具 L53 = 字面量 `not-valid-json-line-with-no-braces-{{{`）——它是解析失败通道的**故意用例**，与"字段级违约"不是同一档。

---

## 4. 对 CT-4 的 Q6（"必填"在采集层的执行强度）的直接证据

- 采集层 validator **接受了 25 行契约违约行**（其中 **24 行**属登记冲突、**1 行**属未登记的 `stock_changed` 枚举外值）⇒ 与 CT-4 对 `EventContractValidator.missingPayloadField` 只校验子集、Q6 待决的判断**同向**，且本轮给出了**行数与行号**。
- **不得**据此推断"validator 全不校验"：它确实隔离了 4 行（含 1 行不可解析），隔离通道**真实工作**。

---

## 5. 本报告**不**证明（未取证）

1. **未重跑 Java 侧 `EventContractValidator`**：51/4 来自 09-11 12:30 那次运行留下的两个文件，本轮只读它们；**不得**称"本轮跑通采集链"（不构成 E3）。
2. **未核行序**：只核了行数、集合归属与字节和；`accepted`/`quarantine` 的行序与夹具是否一致**未取证**。
3. **未启用 `format` 断言**：时间/金额的 `format` 类约束未参与判定（契约 schema 以正则为主）。
4. **未核 `payload` 之外的运行期跨字段约束**（如 `price ≥ cost`、`total_amount = Σ item.amount`）——它们在契约中以 `description` 声明、非本地关键字。
5. **不构成 CT-4 裁决**：CT-4 仍须自行决定 B-06/Q6；本报告只回填其**前提物**与量化。

## 6. 本轮动作边界

未改任何代码/契约/历史文档；无库写；未停启 809x 三个常驻进程。新增文件仅限本目录（`REPLAY.md`、`raw/*.tsv`、`tools/*.py`）。
