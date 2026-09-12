# P5 异构源人工配置接入验证（2026-09-12）

> 权威依据：`docs/acceptance/m3-step8-parity-20260912/RULINGS-D142-20260912.md` §3（人类裁定）+ 父裁决 **D-143**。
> 分支 `remediation/r1-boundary`，HEAD `dba4381`。**本轮无 `git add` / `commit` / `push`。**
> 工作语言：中文。**只记实测事实**；凡未测者一律显式标注「未测」，凡静态推断者标注「静态阅读」。

---

## 0. 一句话结论（实测）

**本平台无法仅靠"登记配置"接入一个字段名 / 枚举 / 时间语义全异构的源。**
5 个夹具共 **180 行**，逐行对账结果：**92 行结果与冻结期望不符，88 行相符**（期望 ACCEPT 120 / QUARANTINE 60；实测 **ACCEPT 84 / QUARANTINE 96**）。

- **B1**（词汇全异构、必需信息完整、期望"经配置映射后入库"）：**60/60 全被隔离**，ACCEPT 0 ⇒ 归一没有发生。
- **B3**（必须拒绝/隔离的歧义与缺必需字段）：**60 行里 28 行被静默 ACCEPT**（无隔离、无提示）。
- **B2**（缺可选字段、期望"显式规则 ＋ 展示能力限制"）：60 行里 **56 ACCEPT / 4 QUARANTINE**，且 4 行的隔离理由与真实原因无关（详见 `IMPL-REPORT.md` §4.2）。

根因（静态阅读 + 实测共同支持，已登记为架构发现 **F-89**）：画像的 9 个顶层键里，**6 个语义键（`canonical`/`eventTypeMapping`/`fieldMapping`/`enumSemantics`/`identityPolicy`/`timePolicy`）在生产代码的读取面上为零**——"配置驱动接入"目前**只有校验，没有执行**。

---

## 1. 本轮范围与边界

| 项 | 内容 |
|---|---|
| **做** | ① 用公开 API **人工登记**一个新源（源 B `fixture-b`）；② 生成三组固定种子夹具 ＋ **跑前冻结**期望；③ 实测采集；④ **逐行**对账（结果 ＋ 理由）；⑤ 污染停车与还原 |
| **不做** | 不写任何生产代码；不实现 P3-02 / `EventNormalizer` / mapping 执行器；**不激活源 B**（D-143 已批准）；不跑任何 Spark 链路；不改动任何既有 profile / 契约 / 映射文档 |
| **明确不是** | 本轮**不是**"真实第三方商城接入验证"，而是"异构源**人工配置接入**验证"（能否只靠配置接进来） |
| **失败处理** | 若"仅靠配置接入"需要改源码才可能成立 ⇒ **停下来如实报告**，不用改码把结果做成"通过" |

> 铁律：**禁止**在分析代码里加任何"商城名分支"。异构源的扩展需求只能落在**通用映射/适配契约**上（本轮记录为能力缺口，不实现）。

---

## 2. 目录与交付物

```
docs/acceptance/p5-heterogeneous-source-20260912/
├─ README.md                     ← 本文件（索引 + 复现指引 + 结论摘要）
├─ EXPECTED.md                   ← 跑前冻结的期望（14843 B，sha256 D09CD579…141F，冻结后未改动）
├─ IMPL-REPORT.md                ← 实测实施报告（含父裁决要求的 4 项专项证据）
├─ fixtures/
│  ├─ b1a-envelope-vocab.jsonl   ← 30 行（B1：信封 + payload 全异构）
│  ├─ b1b-payload-vocab.jsonl    ← 30 行（B1：信封规范、payload 异构）
│  ├─ b2-missing-optional.jsonl  ← 60 行（B2：缺可选字段 / 置 null）
│  ├─ b3a-missing-required.jsonl ← 30 行（B3：缺必需字段）
│  ├─ b3b-ambiguous-mapping.jsonl← 30 行（B3：映射/枚举/金额/时间歧义）
│  ├─ expected/                  ← 逐行期望 TSV（180 行）＋ fixture-manifest.json ＋ ground-truth-metrics.json
│  └─ tools/                     ← 生成器与校验/对账脚本（全部只读或只写本目录）
├─ mapping/                      ← 3 个画像（**只增不改**；既有画像零改动）
└─ raw/                          ← 全部原始证据（运行响应、SQL 快照、逐行对账 TSV、停车清单）
```

**关键文件 sha256（完整清单逐文件见 `raw/final-deliverable-hashes.txt`）**

| 文件 | 字节 | sha256 |
|---|---|---|
| `EXPECTED.md` | 14843 | `D09CD579FB74B0E08C4DBAC54DC1E3AEBAAD04A63931480DB5B58027E949141F` |
| `fixtures/b1a-envelope-vocab.jsonl` | 8950 | `8986C3D08B17B29538797903CC3BBF0B5FA648F639BD61374474C48707AFD594` |
| `fixtures/b1b-payload-vocab.jsonl` | 10406 | `CB5B1FC3ADD2773DB10C56ED1F245B8F171C145DF39A12839ABD16B52EA7FAD1` |
| `fixtures/b2-missing-optional.jsonl` | 20507 | `B07E505E21CB272B1000F79A4777E097CA7FC57D2C1E983D8356F32121208054` |
| `fixtures/b3a-missing-required.jsonl` | 9537 | `70BDEBE126E224D1BF4FA730FDB1B8AD0432C4A6C32DD2FD929FBEA74CD6329F` |
| `fixtures/b3b-ambiguous-mapping.jsonl` | 9894 | `DD5030F17D592930E0739E3D9A9D6F136C9FAB7F27D2FA388DF86450FEEE407C` |
| `fixtures/expected/B1-expected-outcomes.tsv` | 12277 | `FE87B0DF7F0C03B3C9A1DE91710C4494A51C0F566073C7EBA952639FADE27EAB` |
| `fixtures/expected/B2-expected-outcomes.tsv` | 17590 | `7F050B79DD9A9821A8603DF43CD083A7465528D9DE890C923987A5877C2B1C0A` |
| `fixtures/expected/B3-expected-outcomes.tsv` | 12100 | `BDF301170E0D6912618977CCF24CAF26537DD25DBA8D1CB2C7B57EE9EC2D0852` |
| `fixtures/expected/fixture-manifest.json` | 1983 | `A5212467AD1E7F6C793B9BF89FD149D73677600B68E8824AC04BAAEC81F8ABEF` |
| `fixtures/expected/ground-truth-metrics.json` | 904 | `D97FFEF3853E22A23B6B94C5E883CE92452354051DCFD854A60BC9E5BAC86C39` |
| `mapping/fixture-b.v1.json`（归档副本） | 3581 | `CE2E6DBC791F9FA65592C1CF37242CCFA86B3B293CA65BD713D62E7D7FEF13BD` |
| `mapping/scenario-b2-canonical.v1.json` | 3125 | `57DE1116409BBB2E5B5F20233FAF6986A8A3C5F0C177F75C9B50BDFE49E3266E` |
| `mapping/scenario-b3-ambiguous.v1.json` | 1596 | `1188B19E91336640A72BEC5595D79CB07D9455273A7DE3450D600F5265F1E36A` |
| `raw/actual-outcomes-batch45.tsv`（逐行对账） | 40609 | `B797DB94F8BD67CFC4A9E31D689AD8735EAD87F9D961A3EB00C420D0F56DD531` |
| `raw/manifest-parked/45.json`（停车的污染清单） | 1388 | `BAE2B69623326E46B2AA9683CDE7C719055AD8C0119DFBCC28B06C1420D63958` |
| `raw/manifest-parked/46.json`（停车的空批次清单） | 571 | `B0A915BA0135BC669AB0645315C213948AFAB035164EAD771F1022ABD9D0D8D0` |

**登记绑定副本（生产配置目录，权威）**：`analytics-server/source-profiles/fixture-b.v1.json`，3581 B，sha256 `CE2E6DBC…13BD` —— 与 `mapping/` 下归档副本**逐字节相等**（由 `fixtures/tools/verify-fixtures.mjs` §④ 机器校验，输出 `ALL CHECKS PASSED`）。该目录**原有文件零改动**：`mock-mall.v1.json` `0BB8A05C…170B`、`README.md` `61C74155…`、`p1-03-probe-1/2.v1.json` 均与本轮开工前一致。

---

## 3. 夹具设计（固定种子 ＋ 三组语义）

- **生成器**：`fixtures/tools/gen-fixtures.mjs`，随机源 = `mulberry32(20260912)`（**固定种子**，无 `Date.now()`、无系统随机）。同种子重复运行**逐字节一致**（实测两次一致，见 `raw/gen-fixtures-output.txt`）。
- **业务日** `2026-09-20`（与其它泳道的 `2026-09-01` **刻意隔离**）；时区 `+08:00`；源内自称 `source_system = "fixture-b"`。
- **行数**：B1 60（30+30）、B2 60、B3 60（30+30）= **180 行**。

### 3.1 与源 A 的**有意撞号**（"不串源"唯一的证伪设计）

| 维度 | 源 A（`mock-mall` 金样例）取值 | 本夹具复用 |
|---|---|---|
| user id | `1` `2` `3` | 同 |
| product id | `1` `2` `3` `4` | 同 |
| order id | `1001`… | `1001`…`1010` |
| payment id | `P-<order_id>` | 同规则 |
| session id | `s-1` `s-2` `s-3` | 同 |

撞号是唯一能暴露"跨源主键串用（surrogate key 未按源隔离）"的设计；**不撞号的干净数据证明不了任何事**。

### 3.2 三组（逐族期望见 `EXPECTED.md` §2）

| 组 | 夹具 | 期望结论 | 设计要点 |
|---|---|---|---|
| **B1** | `b1a` 30 ＋ `b1b` 30 | **ACCEPT 60** | 字段名/类型/枚举**全异构**、必需信息完整 ⇒ 经配置映射后应产出**可对账**指标 |
| **B2** | `b2` 60（3 族×20） | **ACCEPT 60** | 缺**可选**字段 ⇒ 显式规则 ＋ **展示能力限制**；不得静默补造值 |
| **B3** | `b3a` 30 ＋ `b3b` 30 | **QUARANTINE 60** | 缺**必需**字段 / 映射歧义 ⇒ 明确拒绝或隔离，**不得**静默编造业务信息 |

> B2/B3a 刻意使用**规范词汇**（只留一个变量）；否则会被 B1 的"词汇不认识"掩盖成一个笼统的隔离计数，测不出目标。

### 3.3 逐行期望（跑前冻结，**结果不得反向修改**）

`fixtures/expected/{B1,B2,B3}-expected-outcomes.tsv`，表头 `row_id / family / expected_outcome / expected_reason_class / note`，共 180 行；`fixture-manifest.json` 记录 5 个夹具的字节数/行数/sha256，`ground-truth-metrics.json` 记录本方**独立推算**的对账基准（pv 14 / uv 3 / order_count 11 / orders_created 11 / sale_amount 3652.00 元）。

**冻结完整性（实测）**：`EXPECTED.md` 最后写入 `22:05:39`，采集实测时刻 `22:08:05`；本轮结束时复算 sha256 仍 = `D09CD579…141F` ⇒ **测量后未改动**（证据 `raw/final-deliverable-hashes.txt`）。

---

## 4. 复现步骤（命令原文，全部可重跑）

```powershell
# 0) 前置：应用已在 8091 运行；不要重启
$p5  = "docs\acceptance\p5-heterogeneous-source-20260912"
$raw = "$p5\raw"

# 1) 生成夹具（固定种子，逐字节可复现；写入 fixtures/ 与 fixtures/expected/）
node "$p5\fixtures\tools\gen-fixtures.mjs"                  # → raw/gen-fixtures-output.txt

# 2) 自检：3 个画像 9 键齐全 + 5 个 JSONL 的 sha256/行数/可解析 + 绑定副本与归档副本逐字节相等
node "$p5\fixtures\tools\verify-fixtures.mjs"                # → raw/verify-fixtures-output.txt（ALL CHECKS PASSED）

# 3) 登记源 B（幂等：重复执行会得到 400 源编码已存在）——命令原文见 raw/register-source-b.txt
$H = @{ Authorization = "Bearer " + (Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/v1/auth/login `
      -ContentType application/json -Body '{"username":"admin","password":"admin123"}').data.token }
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/v1/sources -Headers $H -ContentType application/json `
  -Body '{"sourceCode":"fixture-b","displayName":"异构源 B（P5 人工配置接入验证）","ingestMode":"FILE", ... }'

# 4) 采集（先把夹具放进 landing\events，再把基线文件停车 —— 命令原文见 raw/ingest-step1-park-baseline.txt）
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8091/api/v1/ingestion/runs -Headers $H -ContentType application/json -Body '{}'

# 5) 取隔离理由（只读 SQL）→ raw/quarantine-reasons-batch45.tsv
# 6) 逐行对账（期望 vs 实测 vs 理由）→ raw/actual-outcomes-batch45.tsv
node "$p5\fixtures\tools\reconcile-actual.mjs" 45 "$raw\quarantine-reasons-batch45.tsv"

# 7) 复刻 findReadyManifest 判据（只读，用于证明污染已解除）
node "$p5\fixtures\tools\check-ready-manifest.mjs"
```

> `reconcile-actual.mjs` 自带两项**自证**：① 期望表行序与夹具行序不一致数 = **0**；② 隔离记录 `event_id ↔ 行 id` 连接不一致数 = **0**。二者为 0 时，"逐行"对账才成立。

---

## 5. 实测结果（一张主表）

**批次 45**（`POST /api/v1/ingestion/runs`，`2026-09-12 22:08:05`，耗时 0.16 s）：`batchId 45`、`status QUARANTINED`、`recordCount 84`、`quarantineCount 96`、`errorCount 0`、`fileCount 5`、`acceptedBytes 28349`；清单 `landing/manifests/45.json`（READY，`sourceCode mock-mall`、`sourceId 1`、`profileVersion 1.0`、**`mappingVersion null`**）。

| 族 | 期望 A/Q | 实测 A/Q | 结果不符 |
|---|---|---|---|
| B1A-信封异构 | 30/0 | **0/30** | **30** |
| B1B-payload 异构 | 30/0 | **0/30** | **30** |
| B2-1-缺契约必需(闸门未查) | 20/0 | 20/0 | 0 |
| B2-2-字段为 null | 20/0 | 16/4 | **4** |
| B2-3-缺源声明可选字段 | 20/0 | 20/0 | 0 |
| B3A-1-缺闸门必需字段 | 0/12 | 0/12 | 0 |
| B3A-2-缺契约必需(闸门未查) | 0/10 | **10/0** | **10** |
| B3A-3-缺信封必需字段 | 0/8 | 0/8 | 0 |
| B3B-A1-映射歧义(无规范字段) | 0/4 | 0/4 | 0 |
| B3B-A2-映射歧义(有规范字段) | 0/4 | **4/0** | **4** |
| B3B-B-未裁定枚举 | 0/8 | 0/8 | 0 |
| B3B-C-金额类型歧义 | 0/7 | **7/0** | **7** |
| B3B-D-时间格式歧义 | 0/7 | **7/0** | **7** |
| **合计** | **120/60** | **84/96** | **92**（相符 88） |

逐行明细：`raw/actual-outcomes-batch45.tsv`（180 行，表头 `file,line,row_id,family,expected_outcome,expected_reason_class,actual_outcome,actual_reason,verdict,join`）。摘要：`raw/reconcile-batch45-output.txt`。

**三点结论**：
1. **B1 归一没有发生**：异构信封/载荷 60 行**一行没进**，隔离理由全部是"规范字段缺失"（`event_id` 30、`user_id` 18、`order_id` 12）——**按键名判缺**，而非按源声明映射后判缺。
2. **B3 有 28 行静默失真**：B3A-2 10、B3B-A2 4、B3B-C 7、B3B-D 7 被 **ACCEPT**，无隔离、无提示 ⇒ 业务信息静默失真风险（缺必需字段被当可选、歧义金额/时间被直接采信）。
3. **B2 的"能力限制"无处安放**：16 行 null 被静默接受（无任何记录），4 行 null 金额被误判为 `金额格式违规: cost 类型异常`（引用了一个源从未发送的规范字段名）。

**幂等性（实测）**：夹具原样不动再跑一次 → 批次 46：`noNewData: true`、`recordCount 0`、`quarantineCount 0`、`fileCount 0`、`status SUCCESS`（`raw/ingest-run-2-idempotency.txt`）⇒ **重复导入幂等成立**（判据 = 绝对路径 ＋ 文件创建时间(ms)，见 `IMPL-REPORT.md` §7.3）。

---

## 6. 污染停车与还原（命令原文 ＋ 实测证据）

### 6.1 `landing/events` 基线停车 / 还原
`landing/events` 原有 **62** 个 `.jsonl`（406,496,908 B = 开工前 61 个文件 406,496,411 B ＋ 别道 21:57 新增的 `2026091221.jsonl` 497 B）。为让采集只看到夹具，先整体停车到 `landing/parked-p5-baseline/`，测量后**原路还原**（命令原文见 `raw/ingest-step1-park-baseline.txt` / `raw/ingest-step2-restore-baseline.txt`）：

```powershell
# 停车（测量前）
New-Item -ItemType Directory -Force -Path landing\parked-p5-baseline | Out-Null
Move-Item landing\events\*.jsonl landing\parked-p5-baseline\      # 62 个文件整体移出
# 放夹具
Copy-Item "$p5\fixtures\*.jsonl" landing\events\                  # 只放 5 个夹具
# 还原（测量后）
Get-ChildItem landing\events -File -Filter *.jsonl | Remove-Item  # 撤出夹具
Move-Item landing\parked-p5-baseline\*.jsonl landing\events\      # 62 个文件原路还原
Remove-Item landing\parked-p5-baseline -Force -Recurse
```

**实测还原结果**：`landing/events` 回到 **62 个文件 / 406,496,908 B**，逐文件 sha256 与字节数对拍**不符 0 个**（`raw/ingest-step2-restore-baseline.txt`）。

### 6.2 清单停车（本泳道唯一的跨泳道污染面）
批次 45 的清单 `landing/manifests/45.json` 一旦留下，就会成为 `findReadyManifest` 的**最新有数据 READY 批次**，被任何泳道的下一次链路运行当作 ODS 输入（业务日 `2026-09-01` vs 夹具 `2026-09-20` ⇒ `RUN_EMPTY_DATA` 失败）。**测量一结束立即停车**，并给出**实测的"已解除污染"证据**：

```powershell
# 停车（含批次 46 的空清单，一并移出）
New-Item -ItemType Directory -Force -Path "$p5\raw\manifest-parked" | Out-Null
Move-Item landing\manifests\45.json "$p5\raw\manifest-parked\45.json"
Move-Item landing\manifests\46.json "$p5\raw\manifest-parked\46.json"
# 还原（若需回放本轮实测，把两个文件移回即可）
# Move-Item "$p5\raw\manifest-parked\45.json" landing\manifests\45.json
# Move-Item "$p5\raw\manifest-parked\46.json" landing\manifests\46.json
```

#### 6.2.1 三段式「已解除污染」实测证据（父裁决升级要求）

**① 判据来源 = 磁盘 manifest 文件（不是 DB 状态）**

| file:line | 事实 |
|---|---|
| `PipelineService.java:978-1013` | `findReadyManifest`：`Files.list(landingRoot.resolve("manifests"))` **逐个文件** `readManifest` → `status=="READY"` → `accepted+quarantined>0` → 取 `max(batchId)` |
| `PipelineService.java:959-971` | `readManifest`：`Files.readString(landing/manifests/<N>.json)`；文件不存在返回 `null` |
| `PipelineService.java:938-956` | `manifestForRun`：DB 只用来取"钉住"的 batchId（`pipeline_stage_run.evidence`），**且仍要读磁盘同名文件**；读不到 → 回落 `findReadyManifest`（仍是磁盘） |
| `PipelineService.java:377-379` | LOAD_ODS 输入目录 `= landingRoot.resolve(manifest.get("acceptedUri"))` —— 由**清单对象**带出 |

⇒ **判据来自磁盘 ⇒ park 清单文件有效**（若判据来自 DB 状态则 park 无效——实测是前者）。

**② 当前 max accepted batchId 的实测值**

| 面 | 值 | accepted / quarantined | 业务日（实测自产物） | 归属 |
|---|---|---|---|---|
| **文件面**（平台真正读取的面，`findReadyManifest`） | **43** | 51 / 4 | `2026-09-01`（读 `accepted/43` 首行 `event_time`） | `mock-mall`，文件 `m3s8-golden55-20260912210313.jsonl` ⇒ **非夹具批次** |
| **DB 面**（`ingestion_batch` 中 `record_count+quarantine_count>0` 的最大 id） | **45** | 84 / 96 | —（夹具业务日 `2026-09-20`） | 我的批次（元数据残留，未清理） |
| **DB 面，排除我的 45/46** | **44** | 1 / 0 | `2026-09-12`（读 `accepted/44/2026091221.jsonl`：`behavior`/`cart_add`/`mock-mall`/`21:57:19+08:00`） | 别道（E5）批次 `ing-20260912215733-85b570e5` |

> `ingestion_batch` **没有业务日列**（`SHOW COLUMNS` 实测）⇒ 业务日只能从产物或 `pipeline_run.business_time` 取。批次 44 的清单亦已被别道 park 到 `docs/acceptance/e5-preaccept-20260912/raw/manifest-parked/44.json`（实测路径）。

**判据复刻的前后对比**（`fixtures/tools/check-ready-manifest.mjs`）：

| 时点 | `landing/manifests` 文件数 | findReadyManifest 结果 |
|---|---|---|
| 停车前 | 45 | **batchId=45（我的批次）← 污染成立** |
| 停车后 | 43 | **batchId=43 ← 回到开工前状态（污染已解除）** |

**③ park 之后该值是否真的不再指向任何夹具批次 = 是（实测）**

- 文件面命中 `43.json`，全文存证（`raw/manifest-pollution-and-release-three-part.txt`）：`sourceCode mock-mall`、`sourceId 1`、`files[].file = m3s8-golden55-20260912210313.jsonl`，与我的 5 个夹具名（`b1a-*`/`b1b-*`/`b2-*`/`b3a-*`/`b3b-*`）**零交集**。
- 夹具数据的唯一入口 `accepted/45` 只能由清单对象带出（`:377-379`）⇒ 清单不在目录里 ⇒ **夹具数据对链路不可达**。
- DB 面仍留有我的批次行 45/46 —— **元数据事实，不构成链路输入**；**我未改任何状态行**（本轮我对 DB 的写入只有经平台 API 的两类：源登记、采集批次；其余全部 `SELECT`）。

> **污染判定：已解除（实测三段式齐全）**。批次 45/46 的 DB 记录、`landing/accepted/45`、`landing/quarantine/45`、`quarantine_record` **保留不删**（它们是本轮证据）。原文：`raw/manifest-pollution-and-release.txt`、`raw/manifest-pollution-and-release-three-part.txt`、`raw/pollution-release-three-part-conclusion.txt`。

#### 6.2.2 顺带发现：park 清单会击穿 R6-13 的"钉住"语义（跨泳道治理项）

若某 run 已被 `WAIT_LANDING` 钉住批次 N，而 `manifests/N.json` 被 park，则 `readManifest` 返回 `null` → **静默回落 `findReadyManifest`**，输入被换成别的批次（正是 R6-13 修掉的那类缺陷）。

- **本轮实测无实际风险**：以 `REGEXP_SUBSTR(evidence,'"batchId":[0-9]+')` 权威提取全部 `WAIT_LANDING` 行的钉住值 —— **没有任何 run 钉住 44 或 45**；最高钉住值 = **43**（run 45/46/47），而 `43.json` 在磁盘上存在 ⇒ 不会触发回落（证据 `raw/pinned-batch-authoritative.txt`）。
- **更正**：初测用无锚定 `LIKE '%batchId%44%'` 得到的"3 行"是**误命中**（命中的是 evidence 内的 `[RECOVERY] … at=2026-09-10T19:44:48` 时间戳）；锚定 + REGEXP 重测为 **0 行**。
- **建议**（不实现，仅登记）：park 前先只读自查 `SELECT id,run_id,REGEXP_SUBSTR(evidence,'"batchId":[0-9]+') FROM analytics_meta.pipeline_stage_run WHERE stage_code='WAIT_LANDING' AND evidence LIKE CONCAT('{%"batchId":',<N>,',%');` —— 非空即**不可 park**（或先确认该 run 已终态且不再 retry）。

---

## 7. 未测清单（诚实标注，不得当作已验）

| # | 事项 | 原因 |
|---|---|---|
| 1 | **库级「不串源」**（撞号 id 在 ODS/DWD/ADS 是否按源隔离） | 需**激活源 B** 并跑链路；D-143 未批准激活。替代证据（`raw/substitution-evidence-source-attribution.txt`）**只能**证明"入库产物仍归属源 A"，**不能**证明库级隔离，详见 `IMPL-REPORT.md` §7 |
| 2 | **指标对账**（pv 14 / uv 3 / order_count 11 / sale_amount 3652.00 元） | 同上；且本轮**未跑任何链路** |
| 3 | 采集后的归一对账（B1 是否真能产出指标） | 归一能力不存在（F-89），已实测为零 |
| 4 | `findReadyManifest` 之后各链路阶段对本批次的处理 | 未跑链路（父裁决冻结 M3/E5 链路运行） |
| 5 | 「同路径同创建时间但内容被改写」是否被识别为新数据 | 静态阅读 `LocalFileIngestor.java:289-298`（身份 = `creationTime().toMillis()`）推断为"不识别"，**未实测** |

---

## 8. 相关记录

- 期望与预测（跑前冻结）：`EXPECTED.md`
- 实测实施报告（含父裁决要求的 4 项专项证据、能力缺口 file:line、主结论）：`IMPL-REPORT.md`
- 画像与映射说明（含"缺失旋钮"与建议载体）：`mapping/MAPPING-NOTES.md`
- 全部原始证据：`raw/`（运行响应、SQL 快照、逐行 TSV、停车清单）
