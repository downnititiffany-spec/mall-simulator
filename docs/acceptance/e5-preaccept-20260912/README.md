# E5 小范围预验收（1 页走查）— 2026-09-12

> **性质声明（先读这一段）**
>
> 1. 本轮**不是**完整 E5 验收，而是人工裁决 **D-142 §2** 授权的「启动三程序做一页走查 + **小范围预验收**」。
>    **不得声称完整 E5 通过**（D-142 §6）；本文件与 `IMPL-REPORT.md` 均以此为前提写作。
> 2. 四项判据中，**只有第 2 项（商城行为形成真实采集输入）与边界测试取得完整正证**；
>    第 1、3、4 项各存在「未满足」或「未测」的部分，逐条如实标注，**未做任何生产代码修改**（只登记缺陷）。
> 3. 所有数字均为本轮实测（命令/接口/页面原文已存 `raw/`）；**未做**的项目一律标「未测」，不用估计值代替测量值。
>
> 裁决依据：`docs/acceptance/m3-step8-parity-20260912/RULINGS-D142-20260912.md`
> 走查时间窗：**2026-09-12 21:52:55 → 22:04:12**（本机时间，UTC+8）

---

## 0. 结论速览

| 项 | 判据 | 实测结论 | 关键证据 |
|---|---|---|---|
| 1 | 三程序可独立访问 | **满足** | 8090/8091 各返回 200 HTML；8092 返回 200 JSON API（`/` 为 404，无页面） |
| 1 | 页面明确自身职责 | **部分满足**：8090 满足；8091 仅隐含；**8092 无页面 ⇒ 不满足** | `raw/page-8090-mall.text.txt:6`；`raw/item1-responsibility-probe.txt`；`raw/item1-http-status.txt:5` |
| 2 | 商城少量行为形成真实采集输入 | **满足**（覆盖 1 条 `cart_add`） | `raw/item2-mall-behavior.log`、`raw/item2-ingestion-run.txt`、`raw/item2-manifest-44.txt`、`raw/manifest-parked/README.txt` |
| 3 | 页面展示 任务状态 / 失败原因 / 结果数据源·日期·快照 | **部分满足**：任务状态 ✅、日期+快照 ✅；**失败原因 ✘、数据源 ✘** | `raw/item34-probe-results.txt`、`raw/item34-failed-runs.txt`、`raw/dom-8091-*.html` |
| 4 | 新运行失败时页面显示失败且明确「旧结果仍可用」，不得把旧结果包装成本次成功 | **失败显示 ✅ / 未包装旧结果 ✅（历史态取证）/ 「旧结果仍可用」显式声明 ✘ / 实时失败演练 未测** | `raw/item34-failed-runs.txt`、`raw/db-readonly-pre-stop.txt`、`raw/page-8091-ops.text.txt` |
| 边界 | 关停 8090/8092 后 8091 仍可查既有结果 | **成立** | `raw/boundary-test-conclusion.txt`、`raw/boundary-stop-step.txt`、`raw/page-8091-after-stop-*.text.txt` |

缺陷 4 条（D-a…D-d）+ 观察 4 条（O-a…O-d），见 §7。

### 0.1 总控裁决登记（收讫后回填，2026-09-12）

总控采信本报告实测结论，**四项记为**：第 1 项 **部分满足**（8092 无页面）、第 2 项 **✅**、第 3 项 **部分满足**（失败原因与数据源不可见）、第 4 项 **历史态满足但「旧结果仍可用」无显式声明**；**边界测试 ✅ 成立**。逐条登记（**本轮不动生产代码**）：

| 裁决编号 | 本文编号 | 登记内容 | 处置 |
|---|---|---|---|
| **E5-a** | D-a | 8092 无页面（`resources` 仅 `application.yml` + `V1__generator_meta.sql`，无 `static/`、`templates/`；仅 9 条 REST 路由）⇒ 与看板 §3.5 ④ / **F-23** 同源 | 登记，**保持未修** |
| **E5-b** | D-b | 失败原因页面不可见（API `errorMessage` 有全文；`/pipeline`、`/ops` 对 `errorMessage/失败原因/Cannot safely cast` **0 命中**；`/pipeline-runs/46` 顶层亦不含）⇒ **D-142 §2 第 3 项硬缺口** | 登记，挂「平台页面」泳道，**不在 E5 本轮修** |
| **E5-c** | D-c | 页面不显示结果所属**数据源**（DB/API 有 `source=spark-ads`，三页 0 命中）⇒ 第 3 项缺口 | 登记，同上 |
| **E5-d** | D-d | 失败态页面**没有**「旧结果仍可用」显式文案（关键词三页 0 命中）⇒ D-142 §1.1 / §2 第 4 项未达成项 | 登记，同上 |
| （缺陷） | O-a | `/overview`「数据更新」用了**业务时间**（`dataUpdatedAt:"2026-09-01T00:00:00"`）而 run 47 实际完成于 **21:29:27** ⇒ 口径混淆，应为「运行完成时间」、业务时间另列 | 由观察**升为缺陷**，登记，**不修** |
| （并入） | O-b | 同一快照 `/overview` 质量=PASS 而 `/ops`=未通过，且同响应 `data.quality.failedRules=["EVENT_ID_UNIQUE"]` ⇒ 与 **F-88**（严重度/阻断口径不一致）同根因 | **并入 F-88，不单独立项**（交叉引用见 §7 O-b） |
| **陷阱 #58** | §3 坑 2 | 跨进程响应比对直接比整文件 SHA256 会因随机 `traceId` 产生 **10/10 假阳性** ⇒ 必须归一化后再比（本轮已按归一化执行） | **采信入陷阱台账 #58** |

**保留不动（总控指令，一律不删）**：`landing/events/2026091221.jsonl`（497 B）、`landing/accepted/44/`、`landing/quarantine/44/`、`analytics_meta.ingestion_batch id=44`、`mall_simulator.cart_item id=37`、`mall_simulator.event_outbox id=2949240` —— **历史与证据留痕优先**。
**landing 基线正式变更**：`landing/events` **61 → 62**（+497 B），已由总控登记（见 §9）。
**未测清单照原样保留**（§8）⇒ 因此**不得**声称完整 E5 通过。

---

## 1. 现场与授权（本轮做了什么、没做什么）

**发现时的现场（21:52:55，未启停任何东西）** — `raw/timeline.txt`
- 监听端口只有 `:::8091`（`owningPid=61104`，`java.exe -jar analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar`，workdir = 仓库根）。
  ⇒ **8091 本轮全程未重启、未 kill**（首尾 PID 均为 61104）。
- `landing/events` = **61** 个文件；`landing/manifests` = **43** 个文件。这两个基线数在 §9 复核。
- 8090 / 8092 **当时均未运行**（无监听、无进程）⇒ 由本轮按注册命令启动，见下。

**启动（唯一偏离：`java` → `javaw`，理由 F-47）** — `raw/startup-commands.txt`
- 命令模板取自 `scripts/start-all.ps1` 的 `Start-Jar`（L39–L51），**workdir = 仓库根**，日志重定向到 `raw/8090-mall-stdout.log`、`raw/8092-generator-stdout.log`：
  - `[8090] javaw -Dfile.encoding=UTF-8 -jar mall-simulator\target\mall-simulator-0.1.0-SNAPSHOT.jar` → **PID 54644**，21:53:07 起
  - `[8092] javaw -Dfile.encoding=UTF-8 -jar synthetic-data-generator\target\synthetic-data-generator-0.1.0-SNAPSHOT.jar` → **PID 13696**，21:53:07 起
  - jar 指纹（sha256 前 16 位）：mall `EF1991A03D68B394`、generator `11EE6F7F8C1D7FD6`
- 三者**各自独立进程、独立端口、独立库、独立日志**（8090 `mall_simulator`、8092 `generator_meta`、8091 `analytics_meta`+`analytics_metric`）。

**本轮未做**：未改任何生产代码；未写任何业务数据（SQL 全为 `SELECT`）；未跑 Spark 链；**未调用 `POST /api/v1/pipeline-runs`（一次都没有）**；未 `git add/commit/push`；未移动或删除 `landing/events` 里既有 61 个文件。

---

## 2. 第 1 项：三程序可独立访问 + 页面明确自身职责

### 2.1 访问性（`raw/item1-http-status.txt`，21:53:30）

| 程序 | 请求 | HTTP | content-type | 大小 | 页面 title |
|---|---|---|---|---|---|
| 分析平台 8091 | `GET http://127.0.0.1:8091/` | **200** | text/html | 608 B | `电商用户行为分析系统` |
| 分析平台 8091 | `GET /api/v1/health` | **200** | application/json | 59 B | `{"app":"analytics-server","code":"OK","stage":"R1-skleton"}` |
| 模拟商城 8090 | `GET http://127.0.0.1:8090/` | **200** | text/html | 407 B | `模拟商城（mall-simulator）` |
| 模拟商城 8090 | `GET /api/v1/mall/products?page=1&size=1` | 401 | — | — | 需登录（预期，非缺陷） |
| 生成器 8092 | `GET http://127.0.0.1:8092/` | **404** | — | — | **无页面** |
| 生成器 8092 | `GET http://127.0.0.1:8092/api/v1/scenarios` | **200** | application/json | 1017 B | 11 个场景（JSON） |

独立性正证：8091 已在运行时，8090 与 8092 各自独立启停成功（21:53:07 起，各自日志出现 `Tomcat started on port 8090/8092`），三者互不依赖启动顺序。

### 2.2 职责陈述

- **8090 满足（全文引用，`raw/page-8090-mall.text.txt` 第 6 行）**：
  > 本页属模拟商城（事件来源系统，仅提供可重复测试数据）； 分析平台看板在 http://127.0.0.1:8091，两者数据库、账号与前端完全独立。

  首屏另有标题 `模拟商城（mall-simulator）`、导航 `商城演示 / 商品后台`。
- **8091 仅隐含（未显式陈述）**：未登录首屏全文 90 字符 = `电商用户行为分析系统 / 请登录后进入运营看板 / 用户名 / 密码 / 登 录`；
  对 `职责/定位/本页/本系统/本平台/事件来源/上游/下游/数据来源系统/模拟商城/分析平台/生成器` 共 11 个关键词，
  `/login` 与 `/overview` 渲染文本**命中数全为 0**（`raw/item1-responsibility-probe.txt`）。
  其职责只能由标题、登录引导句与导航（运营大盘/用户行为分析/商品分析/用户分层/销售分析/数据流水线/运维中心/决策中心/智能分析助手）**间接**判定。
- **8092 页面维度不成立**：`GET /` = 404；源码实测 `synthetic-data-generator/src/main/resources` 下**只有** `application.yml` 与 `db/generator/V1__generator_meta.sql`（**无 `static/`、无 `templates/`**）；
  对外仅 3 个 `@RestController`（`/api/v1/generation-runs` 4 条、`/api/v1/scenarios` 1 条、`/api/v1/targets` 4 条 = 9 条路由）+ 模块内 CLI `GeneratorCli`（`ApplicationRunner`，子命令 `plan-append` / `run-start`）。
  既有登记可对照：看板 §3.5 ④ / F-23 记载 §4.5 判据为「页面**或** CLI」，CLI 分支早已交付、**页面分支零资产**。
  ⇒ 本轮只如实记录：**E5 第 1 项的措辞是「页面」，故 8092 在该维度不满足**（判归裁决，本轮不改代码）。

---

## 3. 第 2 项：商城少量行为 → 真实采集输入（端到端一条链）

只做 1 次页面动作（**未注册新用户、未下单、未支付**），全链证据如下（时间均为 2026-09-12）：

| 环节 | 实测证据 | 来源 |
|---|---|---|
| ① 页面行为 | 8090 `/mall`，以既有用户 `2098607950599319554` 身份点 1 次 `+ 加购`（商品 1001 磁吸手机支架），页面提示 `已加入购物车：磁吸手机支架`；购物车区显示 `磁吸手机支架 × 1 ¥29.90` | `raw/page-8090-mall-after-addcart.text.txt` / `.png` |
| ② 商城库落库 | `mall_simulator.cart_item` id=**37**，user_id=2098607950599319554，product_id=1001，quantity=1，updated_at=**21:57:19.689** | `raw/db-readonly-after-item2.txt`（只读 SQL） |
| ③ outbox | `mall_simulator.event_outbox` id=**2949240**，event_id=**eb22c6ca-58be-47c4-87d2-1467cc79bbe2**，event_type=behavior，created_at=21:57:19.691，published_at=**21:57:22.365**；发布前 outbox pending = **0** ⇒ 该条就是本次页面行为 | 同上 |
| ④ 发布到 landing | 8090 日志：`2026-09-12T21:57:22.376 INFO 54644 --- [scheduling-1] c.g.mall.outbox.OutboxPublisher : outbox publish: ok=1 failed=0` | `raw/8090-mall-stdout.log` |
| ⑤ landing 文件 | **新建** `landing/events/2026091221.jsonl`：497 B、**1 行**、sha256 `81518b0c25274b58d86911fcc8062333cf0201883a1a4c0b5a991bf83de93180`；行内容 `event_type=behavior`、`behavior_type=cart_add`、`user_id=2098607950599319554`、`product_id=1001`、`source_system=mock-mall`、`event_time=2026-09-12T21:57:19.6892274+08:00` | `raw/item2-mall-behavior.log`、`raw/landing-newfile-2026091221.jsonl.txt` |
| ⑥ 采集 | `POST /api/v1/ingestion/runs` → **HTTP 200**，`batchId=44`、`batchNo=ing-20260912215733-85b570e5`、`status=SUCCESS`、`recordCount=1`、`fileCount=1`、`quarantineCount=0`、`acceptedBytes=496`、`noNewData=false`、`acceptedDir=landing\accepted\44`、`manifestPath=landing\manifests\44.json` | `raw/item2-ingestion-run.txt` |
| ⑦ 批次清单 | `landing/manifests/44.json`：`files[0] = {file: 2026091221.jsonl, startOffset: 0, endOffset: 497, acceptedRecords: 1}`；`sourceCode=mock-mall`、`sourceId=1`、`checksum=d3bdd2bf`、`acceptedRecords=1` | `raw/item2-manifest-44.txt` |
| ⑧ 采集前后状态 | pendingFiles 62 / checkpointFiles 61 / newFileCount 1 → 62 / 62 / 0 | `raw/item2-ingestion-run.txt` |

**既有 61 个文件未动**：`landing/events` 文件数 61 → **62**（只新增我这一小时的 1 个文件，无改名、无删除）。
**本轮创建的采集批次 = `id 44`；manifest 文件名 = `44.json`；已按总控硬约束 park（见 §6.4）。**

---

## 4. 第 3 项：任务状态 / 失败原因 / 结果数据源·日期·快照

探针口径：Playwright 渲染后的 `document.body.innerText` **与** 完整 DOM 全文双查（`raw/item34-probe-results.txt`、`raw/dom-8091-*.html`）。

### 4.1 任务状态 ✅ 满足
- `/pipeline`（可见文本 706 字符）：表格列为 `ID / 流水线 / 业务时间 / 状态 / 尝试 / 操作`，含 `47 SUCCESS`、`46 FAILED`、`45 FAILED`、`44 FAILED`、`40 FAILED`、`38 FAILED`。
- `/ops`（可见文本 5,808 字符）「流水线实例（幂等键 / 尝试次数 / 目标快照，溯源链路）」表列出 `实例/流水线/业务时间/目标快照/尝试次数/状态/当前阶段/错误码/创建时间`，
  其中 44/45/46 = `FAILED / FAILED / RUN_JOB_FAILED`，40 = `RUN_EMPTY_DATA`，38 = `RUN_METRIC_PUBLISH_FAILED`，47 = `SUCCESS`。

### 4.2 失败原因 ✘ **不满足 → 缺陷 D-b**
- API **有**：`GET /api/v1/pipeline-runs`（列表）中 run 44/45/46 的 `errorMessage` =
  `BUILD_DWD 作业失败: tdw: 进程退出码非0(exit=1): [INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_SAFELY_CAST] Cannot write incompatible data for the table \`spark_catalog\`.\`dw_dwd\`.\`dw_order_detail\`: Cannot safely cast \`user_key\` "STRING" to "BIGINT".`
- 详情接口 `GET /api/v1/pipeline-runs/46` 顶层字段为
  `runId, pipelineCode, status, currentStage, errorCode, targetSnapshotId, attemptNo, idempotencyKey, createdAt, finishedAt, stages…` —— **不含 `errorMessage`**（`raw/item34-failed-runs.txt` 段 B）。
- 页面 **无**：`/pipeline`（DOM 4,788 字符）与 `/ops`（DOM 35,005 字符）全文对 `errorMessage`、`失败原因`、`Cannot safely cast`、`BUILD_DWD 作业失败` 命中数 **全为 0**；唯一展示的是错误码 `RUN_JOB_FAILED`（仅 `/ops` 一列）。行内 `[title]` 悬浮提示 = 空数组（无隐藏展示位）。

### 4.3 结果所属数据源 ✘ **不满足 → 缺陷 D-c**
- API/库 **有**：`GET /api/v1/metrics/snapshots` 每行含 `"source":"spark-ads"`；只读 SQL `analytics_metric.metric_snapshot` 12 行 `source` **全为 `spark-ads`**（`raw/db-readonly-pre-stop.txt`）。
- 页面 **无**：`/overview`、`/pipeline`、`/ops` 三页全文对 `spark-ads`、`数据源`、`sourceCode`、`source_id` 命中数 **全为 0**。

### 4.4 结果的日期与快照 ✅ 满足（但「数据更新」有问题，见 O-a）
- `/overview` 渲染：`快照 S20260901_47` / `业务时间 2026-09-01 00:00:00` / `数据更新 2026-09-01 00:00:00` / `口径版本 v2` / `质量 PASS(质量通过)`；
  指标卡 `销售额(GMV) 2,042.00元`、`净销售额 1,493.00元`、`支付订单数 5单`、`浏览量(PV) 7次`、`浏览用户(UV) 3人`、`活跃用户(DAU) 3人`、`客单价 408.40元`、`退款率 60.00%`（`raw/page-8091-overview.text.txt`）。
- `/ops` 渲染「快照版本」列表（`S20260901_47 ACTIVE v12` / `S20260901_43 ARCHIVED v11` / … / `S20260901_38 FAILED v7`）与「快照 S20260901_47 指标」10 行（`gmv 2,042.00 元`…）。
- 旧（归档）结果仍可读：点 `S20260901_43` 行「查看指标」后加载出 10 行（`avg_order_value 88.16`、`gmv 88.16`、`paid_order_cnt 1.00`…），页面明示「按该行的快照号加载指标行」（`raw/page-8091-ops-after-view-43.text.txt`）。

---

## 5. 第 4 项：新运行失败 ⇒ 显示失败 + 明确「旧结果仍可用」；不得把旧结果包装成本次成功

### 5.1 失败可见 ✅ 满足
`/pipeline` 与 `/ops` 均列出 44/45/46 三条 `FAILED`（含 `RUN_JOB_FAILED` 错误码、目标快照 `S20260901_44/45/46`、创建时间 21:03:40 / 21:06:48 / 21:17:06）。

### 5.2 「不得把旧结果包装成本次成功」✅ **历史态取证成立**（`raw/db-readonly-pre-stop.txt`）
- 失败运行**没有发布任何快照**：`analytics_metric.metric_snapshot` 全表 12 行中**不存在** `S20260901_44/45/46`（12 行为 47 ACTIVE、43/42/41/39/30/29/25/22/24/23 ARCHIVED、38 FAILED）。
- **ACTIVE 唯一**：`SELECT COUNT(*) … WHERE status='ACTIVE'` = **1**，且该行 `pipeline_run_id=47`（= SUCCESS 的那次运行）。
- ⇒ 页面上的 `快照 S20260901_47 / 质量 PASS / GMV 2,042.00` 是 **run 47 的成功结果**，没有被记到 44/45/46 名下（`pipeline_run.target_snapshot_id` 分别为 S20260901_44/45/46，页面「目标快照」列如实显示）。
- 归档快照 `S20260901_43`（v11, ARCHIVED）与其 10 行指标值仍然可读，未被失败运行覆盖（`metric_value` 中 43 与 47 各 10 行）。

### 5.3 「明确告诉用户旧结果仍可用」✘ **不满足 → 缺陷 D-d**
三页（`/overview`、`/pipeline`、`/ops`）全文对 `旧结果`、`仍可用`、`上一份`、`不影响`、`未发布`、`回退`、`保留`、`上次成功` 命中数 **全为 0**。
`/ops` 的告警句只讲规则口径，未涉及「新运行失败与既有结果的关系」：
> 数据告警：该接口未返回统一信封（无 snapshotId/口径版本/质量状态），上下文以响应内可见字段拼装，另有缺失项已逐项标注。；数据质量规则存在未通过项（金额对账失败会阻断指标发布），请在下方的质量规则结果中核对。

⇒ 页面**能显示失败**，但**没有任何一句**告诉用户「这次失败不影响既有结果、旧结果仍可用」。按指令：如实登记为缺陷，**不改代码**。

### 5.4 未测
**真实触发一次新运行失败并观察页面** —— **未测**（会跑 Spark 链，且总控 22:03 追加硬约束禁止触发任何 pipeline run）。
本轮第 4 项为**历史失败态取证**（44/45/46 的历史状态 + 现役 ACTIVE 47），**不是**实时故障演练。

---

## 6. 边界测试：关掉 8090/8092，8091 仍可查既有结果

### 6.1 动作（`raw/boundary-stop-step.txt`）
- 22:01:29 核对：`8091 PID=61104`、`8090 PID=54644`（javaw，21:53:07 起）、`8092 PID=13696`（javaw，21:53:07 起）。
- 22:01:32 执行 `Stop-Process -Id 54644 -Force` 与 `Stop-Process -Id 13696 -Force`（**只停 8090/8092**）。
- 22:01:38 复核：`8091 → LISTENING pid=61104`；`8090 / 8092 → 无监听（端口已关闭）`、残留进程 0；
  `GET http://127.0.0.1:8090/` 与 `GET http://127.0.0.1:8092/api/v1/scenarios` 均 **连接被拒**（`由于目标计算机积极拒绝`）。

### 6.2 同快照再查（停之前 21:59:47 vs 停之后 22:01:48，`raw/boundary-test-conclusion.txt`）

| 接口 | HTTP | 字节数 前/后 | traceId 归一化后内容 |
|---|---|---|---|
| `health` | 200/200 | 59/59 | **完全一致 ✔** |
| `dashboards/overview` | 200/200 | 3784/3784 | **完全一致 ✔** |
| `metrics/overview` | 200/200 | 1396/1396 | **完全一致 ✔** |
| `metrics/snapshots` | 200/200 | 4646/4646 | **完全一致 ✔** |
| `metrics/quality` | 200/200 | 9228/9228 | **完全一致 ✔** |
| `pipeline-runs` | 200/200 | 13867/13867 | **完全一致 ✔** |
| `pipeline-runs/46`·`/45` | 200/200 | 9085/9088 | **完全一致 ✔** |
| `ingestion/status`·`/batches` | 200/200 | 529/1703 | **完全一致 ✔** |

> 口径说明：每份响应都带随机 `traceId`，**直接比整文件 SHA256 必然不同**（10/10「不一致」，差异 100% 来自 traceId 与文件头时间戳）；上表为**归一化后**的业务体比对。

页面：`/overview` 渲染文本停前停后**逐字节一致**（452 字符，SHA256 前 16 位 `EB98ED637E01F781`）；
`/ops` 仍可读 `S20260901_47` 指标 10 行与历史快照 `S20260901_43`；页面文本中 `不可用/离线/连接失败/商城/生成器/采集` 命中数 **0**（无任何「依赖商城在线」的报错）。

### 6.3 结论
**关掉模拟商城与生成器后，分析平台仍可查询既有结果**（同一快照 `S20260901_47` 的指标、流水线历史、质量规则结果、归档快照全部照常返回，内容与关停前逐字节一致）。
即：**真实数据入口需要它们产数，历史分析不依赖它们在线**——边界成立。

### 6.4 我的采集批次已 park（总控 22:03 硬约束）
- **创建的 ingestion batch id = 44；manifest 文件名 = `landing/manifests/44.json`；现已 park**：
  **是「移动」不是「复制」**（`Move-Item -LiteralPath landing\manifests\44.json -Destination …\raw\manifest-parked\44.json -Force`）⇒ 源路径 `landing/manifests/44.json` **已不存在**（`Test-Path` = **False**），730 B，sha256 `B74A5A63138539F6936A4AC1C9CB8CFECFBC1A76007AAA8D92F33556CEF82921`（移动前后哈希一致）。
- **`landing/manifests` 现在确实是 43 个文件**：park 前实测 44 个（原有 43 + 我的 44.json），park 后实测 **43** 个（`Get-ChildItem -File | Measure` 两次独立复核：22:04:12 与终态复核各一次，均 43）；`manifest-parked/` 内为 `44.json` + `README.txt`。
- **22:17:25 独立复核**（`raw/ruling-followup.txt`，同时 `Test-Path 'landing\manifests\44.json'` = **False**、`landing/manifests` = **43** 个且**最大批次号 = 43**（磁盘上已无 44）、`analytics_meta.pipeline_run` `max(id)=47` 且 `SUM(id>47)=0`、8091 `LISTENING pid=61104`、8090/8092 无监听、`landing/events` = 62）。
- 还原命令（写在 `raw/manifest-parked/README.txt`）：
  ```powershell
  Copy-Item -LiteralPath 'D:\Develop_code\GraduationProject\docs\acceptance\e5-preaccept-20260912\raw\manifest-parked\44.json' `
            -Destination 'D:\Develop_code\GraduationProject\landing\manifests\44.json' -Force
  ```
- 影响面复核：park 前只读查 `analytics_meta.pipeline_run`，`id>47` 的运行数 = **0**（最大运行仍是 47）⇒ 本批次从未被任何流水线消费。
- **批次风险（总控已交接 M3 泳道只读钉死 `findReadyManifest` 判据来源）**：DB 侧 `ingestion_batch 44`（accepted=1、业务日 2026-09-12）与 `landing/accepted/44/` 仍在原地，故若某道跑链且其选批判据只读 DB、不看磁盘 manifest，仍可能选中 44；本轮按指令**不补测**，只确认：**磁盘 `landing/manifests` 里已无 44.json**。
- 保留不动的其它产物（总控指令：**一律保留、不要删**）：`landing/events/2026091221.jsonl`、`landing/accepted/44/`、`landing/quarantine/44/`、`analytics_meta.ingestion_batch id=44`、`mall_simulator.cart_item id=37`、`mall_simulator.event_outbox id=2949240`。

---

## 7. 缺陷与观察清单（本轮只登记，不改代码）

| 编号（裁决登记） | 类别 | 事实（可复核） | 证据 |
|---|---|---|---|
| **D-a**（**E5-a**） | 缺陷（第 1 项） | 生成器 8092 **没有任何页面**：`GET /` = 404；`src/main/resources` 无 `static/`、无 `templates/`；仅 9 条 REST 路由 + 模块内 CLI ⇒ 「页面明确自身职责」不成立 | `raw/item1-http-status.txt:5`；看板 §3.5 ④ / F-23 |
| **D-b**（**E5-b**） | 缺陷（第 3 项，**§2 硬缺口**） | 失败原因无任何页面展示位：API `errorMessage` 可得（全文见 §4.2），页面 `/pipeline`+`/ops` 对 `errorMessage/失败原因/Cannot safely cast` 命中 **0**；详情接口也不返回 `errorMessage` | `raw/item34-probe-results.txt`、`raw/item34-failed-runs.txt` |
| **D-c**（**E5-c**） | 缺陷（第 3 项） | 结果所属**数据源**无任何页面展示位：API/DB 有 `source=spark-ads`，三页对 `spark-ads/数据源/sourceCode/source_id` 命中 **0** | 同上 + `raw/db-readonly-pre-stop.txt` |
| **D-d**（**E5-d**） | 缺陷（第 4 项） | 无任何页面声明「新运行失败 ⇒ 旧结果仍可用」：对 `旧结果/仍可用/上一份/不影响/未发布/回退/保留` 命中 **0** | `raw/item34-probe-results.txt`、`raw/page-8091-ops.text.txt` |
| **O-a**（裁决：**升为缺陷**） | 缺陷（口径混淆） | `/overview`「数据更新」= `2026-09-01 00:00:00`，与「业务时间」同值；来源信封字段 `dataUpdatedAt: "2026-09-01T00:00:00"`（run 47 实际完成于 2026-09-12 21:29:27）⇒ 应为「运行完成时间」，业务时间另列；本轮只记录不改 | `raw/api-pre-stop-dashboards-overview.json`、`raw/page-8091-overview.text.txt` |
| **O-b**（裁决：**并入 F-88**） | 观察 ⇒ **F-88 交叉引用** | 同一快照 `S20260901_47` 的「质量」两页结论不同：`/overview` = **PASS**（信封 `qualityStatus:"PASS"`，同响应 `data.quality={ruleCount:4,passedCount:3,failedRules:["EVENT_ID_UNIQUE"]}`）；`/ops` = **未通过**。与既有登记 **F-88**（`EVENT_ID_UNIQUE` 严重度/阻断口径不一致）同根因 ⇒ **不单独立项**，作为 F-88 的第 3 个可复现观察面 | 同上 + `raw/api-pre-stop-metrics-quality.json` |
| **O-c** | 观察（未升缺陷） | 8091 三页无一句显式职责陈述（11 个关键词在 `/login`、`/overview` 命中 0），只能由标题/导航间接判定；与 8090 的显式声明形成对比 | `raw/item1-responsibility-probe.txt` |
| **O-d** | 观察（未升缺陷） | 一次采集只解析 1 个新文件时，`landing/accepted/44`、`landing/quarantine/44` 仍为**已消费完的其它文件**生成 0 字节同名占位（各 2 个文件），manifest 只登记 1 个文件 1 条记录 | `raw/item2-manifest-44.txt`、`landing/accepted/44`、`landing/quarantine/44` |

> 方法论陷阱（已入台账 **#58**）：跨进程响应比对若直接比整文件 SHA256，会因随机 `traceId` 产生 **10/10 假阳性**；必须先归一化 `traceId` 再比业务体哈希（本轮最终结论即按此口径得出，见 §6.2）。

---

## 8. 本轮**未**验证项（一律标「未测」，不得据此推断通过）

1. **真实触发一次新运行失败并观察页面提示** —— 未测（需跑 Spark 链；且总控 22:03 禁止触发任何 pipeline run）。第 4 项因此为历史态取证。
2. **`POST /api/v1/pipeline-runs` 及其幂等/重试路径** —— 未测（硬约束禁止）。
3. **生成器 8092 的 9 条路由逐条真机观测** —— 未测（本轮仅观测 `GET /` 与 `GET /api/v1/scenarios`）。
4. **生成器 CLI（`plan-append` / `run-start`）实际执行** —— 未测（未跑生成器作业）。
5. **商城下单 / 支付 / 退款 / 取消链路的落盘与采集** —— 未测（本轮只做 1 次 `cart_add`；其余行为类型未覆盖）。
6. **页面「运行进行中」的进行态（BUILDING/VERIFYING 等）展示** —— 未测。
7. **非 admin 角色（如 analyst）视角下的页面展示** —— 未测（全程 admin）。
8. **前端页面上「触发采集」按钮的真实点击** —— 未测（本轮用 `POST /api/v1/ingestion/runs` 触发，规避误触流水线）。
9. **8090/8092 重启后的 outbox 重放/至多一次语义** —— 未测。
10. **D-142 §2 之外的其他 E5 判据**（性能、并发、权限矩阵、集群侧等）—— 未测，属完整 E5 范围，本轮无权主张。

---

## 9. 现场终态与产物清单

**进程终态（22:04）**
| 程序 | 终态 | 说明 |
|---|---|---|
| 8091 分析平台 | **运行中**（PID **61104**） | 与本轮开始时同一 PID，**全程未重启** |
| 8090 模拟商城 | **已停止** | 22:01:32 为边界测试主动停止；**发现时它本就是停止态 ⇒ 终态 = 发现态** |
| 8092 生成器 | **已停止** | 同上（PID 13696 已退出，端口无监听） |

> 我启动的 8090/8092 已按「恢复到发现时状态」处理为停止；重启方法：`scripts/start-all.ps1`（`java` → `javaw`，见 F-47）。

**目录基线复核**
| 路径 | 发现时 | 终态 | 说明 |
|---|---|---|---|
| `landing/events` | 61 | **62** | 新增我的 `2026091221.jsonl`（497 B）；既有 61 个未改未删。**基线正式变更已由总控登记** |
| `landing/manifests` | 43 | **43** | 我的 `44.json` 已**移动**（非复制）park 到 `raw/manifest-parked/`，源路径已不存在 |
| 8091 进程 | PID 61104 | PID 61104 | 未重启 |

**新增产物：总控裁决为「一律保留、不要删」**（历史与证据留痕优先）——`landing/events/2026091221.jsonl`、`landing/accepted/44/`、`landing/quarantine/44/`、`analytics_meta.ingestion_batch id=44`、`mall_simulator.cart_item id=37`、`mall_simulator.event_outbox id=2949240`。

---

## 10. 证据索引（`raw/`）

| 文件 | 内容 |
|---|---|
| `timeline.txt` | 发现时端口/进程/目录基线 |
| `startup-commands.txt` | 8090/8092 实际启动命令行、workdir、jar 指纹、PID |
| `item1-http-status.txt`、`http-*.body.txt` | 三程序 HTTP 状态、content-type、字节数、title |
| `page-8090-*.txt/.png`、`page-8091-*.txt/.png`、`page-8092-generator-root.*` | 各页渲染文本与截图（含首屏、加购前后、查看指标后、停服后） |
| `item1-responsibility-probe.txt` | 职责关键词探针（8091 /login、/overview） |
| `item2-mall-behavior.log`、`landing-newfile-2026091221.jsonl.txt` | 加购动作 + landing 新文件全文与哈希 |
| `item2-ingestion-run.txt`、`item2-manifest-44.txt`、`manifest-parked/README.txt` | 采集请求/响应、manifest 原文、park 与还原命令 |
| `db-readonly-pre-stop.txt`、`db-readonly-after-item2.txt` | 只读 SQL 取证（快照状态、运行状态、cart/outbox 痕迹） |
| `api-pre-stop-*.json`、`api-after-stop-*.json`、`api-*-index.txt` | 10 个接口的**停服前/后**原始响应（含 HTTP 与时间戳） |
| `dom-8091-*.html`、`item34-probe-results.txt`、`item34-failed-runs.txt` | 页面 DOM 全文、关键词探针、失败字段可得性对照 |
| `boundary-stop-step.txt`、`boundary-test-conclusion.txt`、`boundary-after-stop-page.log` | 边界测试全过程与归一化哈希比对 |
| `ruling-followup.txt` | 裁决回执：park 是移动而非复制、`landing/manifests`=43（最大批次号 43）、无新 pipeline_run、终态端口 |
| `8090-mall-stdout.log`、`8092-generator-stdout.log` | 两程序启动与 outbox 发布日志 |
| `tools/*.py`、`tools/dump-api.ps1` | 本轮所有取证脚本（可复跑） |
