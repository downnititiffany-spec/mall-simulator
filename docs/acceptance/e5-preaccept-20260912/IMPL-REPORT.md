# IMPL-REPORT — E5 小范围预验收（泳道执行报告）

- 泳道：**E5 小范围预验收（1 页走查）** ｜ 分支：`remediation/r1-boundary`
- 时间窗：**2026-09-12 21:52:55 → 22:04:12**（UTC+8）
- 授权：裁决 **D-142 §2**（`docs/acceptance/m3-step8-parity-20260912/RULINGS-D142-20260912.md`）
- 交付：本报告 + `README.md`（四项结论/边界/缺陷）+ `raw/`（命令、HTTP/接口原文、关键页面文本、截图、时间戳）+ `tools/`（可复跑脚本）

---

## 0. 强制声明

> **本轮不得、也不被声称是「完整 E5 通过」。**
>
> 本轮只做 D-142 §2 授权的**小范围预验收**：三项判据（第 1、3、4 项）均存在**未满足**或**未测**部分；第 2 项虽取得端到端正证，但只覆盖 **1 条 `cart_add` 事件**，未覆盖下单/支付/退款等链路。完整 E5 的其余判据（性能、并发、权限矩阵、集群侧等）**本轮未测**，无任何主张。

---

## 1. 执行步骤（全部可复核）

| 时刻 | 动作 | 产物 |
|---|---|---|
| 21:52:55 | 记录发现时现场（端口/进程/landing 基线），**不启停任何东西** | `raw/timeline.txt` |
| 21:53:07 | 按注册命令启动 8090、8092（`javaw` 替代 `java`，F-47），workdir = 仓库根 | `raw/startup-commands.txt`、`raw/8090-mall-stdout.log`、`raw/8092-generator-stdout.log` |
| 21:53:15–21:56 | 三程序 HTTP 探针 + Playwright 逐页镜像渲染文本/截图（8091 `/overview`、`/pipeline`、`/ops`、`/behavior`、`/sales`；8090 `/mall`、`/admin-products`；8092 `/`） | `raw/item1-http-status.txt`、`raw/page-*` |
| 21:57:19 | 在 8090 页面以既有用户点 **1 次** `+ 加购`（不注册、不下单） | `raw/page-8090-mall-after-addcart.*`、`raw/item2-mall-behavior.log` |
| 21:57:22 | 观测 outbox 发布 → `landing/events/2026091221.jsonl` 新建（497 B / 1 行） | 同上 |
| 21:57:33 | `POST /api/v1/ingestion/runs` → batch **44** SUCCESS（1 条记录） | `raw/item2-ingestion-run.txt`、`raw/item2-manifest-44.txt` |
| 21:58–21:59 | 页面/DOM 双探针（失败原因、数据源、旧结果可用性关键词）+ 点「查看指标」验证归档快照可读；只读 SQL 取证；10 接口 dump | `raw/item34-*`、`raw/db-readonly-pre-stop.txt`、`raw/api-pre-stop-*.json` |
| 22:01:32 | **边界测试**：停 8090/8092（仅此二者），复核 8091 PID 未变 | `raw/boundary-stop-step.txt` |
| 22:01:48 | 停服后同接口/同快照再查一遍 + 页面再查 | `raw/api-after-stop-*.json`、`raw/page-8091-after-stop-*.text.txt` |
| 22:03 | 收到总控硬约束（禁止任何 pipeline run；park 自己的 manifest） | — |
| 22:04:12 | park `landing/manifests/44.json` → `raw/manifest-parked/44.json`（哈希一致）+ 写还原命令 | `raw/manifest-parked/README.txt` |

---

## 2. 方法与工具

- **页面取证**：Playwright 1.60.0 / Python 3.14.5 / chromium 148.0.7778.96（headless）。
  每个页面同时落三份：可见文本（`innerText`）、**完整 DOM**（`page.content()`）、整页截图 —— 关键词探针**同时在文本与 DOM 上跑**，避免「文本没有但 title/aria 里有」的漏判。
- **接口取证**：`tools/dump-api.ps1 -Tag pre-stop|after-stop`，10 个接口逐条记录 **URL + 时刻 + HTTP 状态 + 字节数 + 响应原文**。
- **库取证**：全部 `SELECT`（`--batch --raw --default-character-set=utf8mb4`），先查 `information_schema` 再写查询。
- **对照法**：同一查询在「停 8090/8092 之前/之后」各跑一次，比对**归一化后**业务体哈希。

---

## 3. 本轮踩到/规避的坑（供后续泳道复用）

1. **F-47（进程存活）**：工具调用里 `Start-Process java.exe` 会在 1–4 s 内以 `0xC000013A` 死掉；改用 **`javaw.exe`** 后 8090/8092 跨多次工具调用稳定存活（日志见 `raw/809*.log`）。本轮**未**触碰 8091 的启动方式。
2. **随机 `traceId` 让「逐字节比对」必然失败 —— 已由总控采信入陷阱台账 #58**：停服前后同一接口的整文件 SHA256 **10/10 不一致**，差异 100% 来自响应里的随机 `traceId`（与我自己写的文件头时间戳）。正确做法是**归一化 traceId 后**再比业务体哈希 —— 修正后 10/10 一致。若不察，会得出「关停影响了查询结果」的**假阳性**结论。
3. **`Locator` 没有 `eval_on_selector_all`**（sync API）：首版探针脚本在最后一步抛 `AttributeError` 而**没写出结果文件**；改为 `locator.locator("[title]").all()` 后重跑落盘。教训：探针脚本的写文件动作要放在崩溃点之前，或分片落盘。
4. **PowerShell 控制台中文乱码**：仅影响终端显示，不污染 UTF-8 证据文件；Python 侧统一设 `PYTHONIOENCODING=utf-8`，PowerShell 侧统一 `Set-Content -Encoding utf8NoBOM`。
5. **`Set-Content -Encoding utf8NoBOM -Append` 不可用**（PS 7 报参数不存在）：改为整文件重写。
6. **列名假设**：`mall_simulator.cart_item` 没有 `created_at`（是 `updated_at`）→ 先查 `information_schema.COLUMNS` 再查询。
7. **采集触发的连带效应**：`POST /api/v1/ingestion/runs` 会新增 `landing/manifests/<N>.json`，而其它泳道的流水线运行会经 `findReadyManifest`（READY 且 accepted+quarantined>0 的 max batchId）**吃掉**该批次 ⇒ 按总控要求已 park（§5）。

---

## 4. 合规自查（逐条对照禁止事项）

| 约束 | 本轮执行情况 |
|---|---|
| 不改任何生产代码 | ✅ 未改。本轮 `git status --porcelain` 中属于本泳道的只有 `?? docs/acceptance/e5-preaccept-20260912/`（只读自查，未执行任何 git 写命令）。<br>⚠️ 同一 `git status` 里另有**其它泳道**的未提交改动（`MetricQualityGate`、`DataQualityGate`、`QualityChecker`、`PipelineService`、`JobResultParser`、`TradeDwdJob.scala`、`RuleSeverity.java` 等 F-88 相关文件），**不是本轮产生、本轮也未触碰**；最近一次提交 `dba4381`（22:04:09，P5/F-89/D-143 台账行）同样非本轮产物 |
| 不改数据库业务数据 | ✅ 全部 SQL 为 `SELECT`；业务数据的**新增来自页面操作**（1 行 `cart_item` + 1 行 `event_outbox`），这是第 2 项判据本身要求的行为 |
| 不跑 Spark 链 / 不跑集群作业 | ✅ 未跑（无任何 spark/hive 命令） |
| **不触发任何 pipeline run**（总控 22:03 硬约束） | ✅ **一次都没有**；只读复核 `analytics_meta.pipeline_run` 中 `id>47` 的运行数 = **0** |
| 不重启 8091 | ✅ PID 61104 全程未变（首尾两次实测） |
| 不移动/删除 `landing/events` 既有 61 个文件 | ✅ 文件数 61 → 62（只新增我这一小时的 1 个）；无改名无删除 |
| 不 `git add/commit/push` | ✅ 未执行任何 git 写操作 |
| 记录真实、未测标未测 | ✅ 见 README §8 与本文 §6 |

---

## 5. 采集批次与 manifest 处置（总控硬约束答复）

- **我创建的 ingestion batch id = `44`**（`batchNo = ing-20260912215733-85b570e5`，SUCCESS，recordCount=1）。
- **manifest 文件名 = `44.json`**（原路径 `landing/manifests/44.json`，730 B，sha256 `B74A5A63138539F6936A4AC1C9CB8CFECFBC1A76007AAA8D92F33556CEF82921`）。
- **最终状态：已 park，且是「移动」不是「复制」**（`Move-Item -Force`）⇒ 源路径 `landing/manifests/44.json` **已不存在**（`Test-Path` = **False**）；目标 `docs/acceptance/e5-preaccept-20260912/raw/manifest-parked/44.json` 730 B、sha256 同上（移动前后哈希一致）。
- **`landing/manifests` 现在确实是 43 个文件**：park 前实测 44 个（原有 43 + 我的 `44.json`），park 后两次独立复核均为 **43**（22:04:12 park 记录 + 终态复核）；`manifest-parked/` 内容 = `44.json` + `README.txt`。22:17:25 再复核（`raw/ruling-followup.txt`）：`Test-Path` 源路径 = **False**、文件数 = **43** 且**最大批次号 = 43**（磁盘上已无 44.json）、`pipeline_run` `max(id)=47`/`SUM(id>47)=0`。
- **还原命令**（同时写在 `raw/manifest-parked/README.txt`）：
  ```powershell
  Copy-Item -LiteralPath 'D:\Develop_code\GraduationProject\docs\acceptance\e5-preaccept-20260912\raw\manifest-parked\44.json' `
            -Destination 'D:\Develop_code\GraduationProject\landing\manifests\44.json' -Force
  ```
- **残留风险（已如实标注）**：DB 侧 `ingestion_batch 44`（accepted=1、业务日 2026-09-12）与 `landing/accepted/44/` 仍在原地 ⇒ 若某道跑链且其选批判据只读 DB 不看磁盘 manifest，仍可能选中 44。总控已让 M3 泳道只读钉死 `findReadyManifest` 判据来源（DB 状态 vs 磁盘 manifest）并实测「现在会选哪个批次」，本泳道**不补测**。
- **保留不动（总控指令：一律保留、不要删）**：`landing/events/2026091221.jsonl`（497 B）、`landing/accepted/44/`、`landing/quarantine/44/`、`analytics_meta.ingestion_batch id=44`、`mall_simulator.cart_item id=37`、`mall_simulator.event_outbox id=2949240` —— 历史与证据留痕优先。
- **landing 基线正式变更**：`landing/events` **61 → 62**（+497 B），已由总控登记。

---

## 6. 本轮**未**验证项

与 `README.md §8` 同一份清单（避免两处口径漂移，此处只列标题）：

1. 真实触发新运行失败并观察页面 —— **未测**（需跑 Spark 链且被硬约束禁止）⇒ 第 4 项为**历史态取证**。
2. `POST /api/v1/pipeline-runs` 及其幂等/重试路径 —— **未测**。
3. 生成器 8092 的 9 条路由逐条真机观测 —— **未测**（仅观测 `GET /`、`GET /api/v1/scenarios`）。
4. 生成器 CLI `plan-append` / `run-start` 实跑 —— **未测**。
5. 商城下单/支付/退款/取消链路 —— **未测**（只覆盖 `cart_add`）。
6. 页面「运行进行中」进行态展示 —— **未测**。
7. 非 admin 角色（analyst）视角 —— **未测**。
8. 前端「触发采集」按钮真实点击 —— **未测**（改用 API 触发以免误触流水线）。
9. 8090/8092 重启后的 outbox 重放/至多一次语义 —— **未测**。
10. D-142 §2 之外的全部 E5 判据（性能、并发、权限矩阵、集群侧等）—— **未测**。

---

## 7. 结论与总控裁决（已收讫）

**本泳道结论（四项 + 边界）**
- 第 1 项：独立可访问 ✅；职责陈述——8090 ✅ / 8091 仅隐含 / **8092 无页面 ⇒ 不满足**（D-a）。
- 第 2 项：✅ 端到端正证（页面加购 → cart_item → outbox → `landing/events/2026091221.jsonl` → 采集 batch 44 → manifest），仅覆盖 1 条 `cart_add`。
- 第 3 项：任务状态 ✅、日期+快照 ✅；**失败原因 ✘**（D-b）、**数据源 ✘**（D-c）。
- 第 4 项：失败可见 ✅、**未把旧结果包装成本次成功 ✅（历史态）**；**「旧结果仍可用」显式声明 ✘**（D-d）；实时失败演练**未测**。
- 边界测试：**成立** —— 8090/8092 关停后 8091 对同一快照的 10 个接口与页面渲染结果与关停前**逐字节一致**，历史分析不依赖二者在线。

**总控裁决（2026-09-12，采信本报告实测结论）**
- 四项记：第 1 项 **部分满足**（8092 无页面）、第 2 项 **✅**、第 3 项 **部分满足**（失败原因与数据源不可见）、第 4 项 **历史态满足但「旧结果仍可用」无显式声明**；边界测试 **✅ 成立**；终态（8091 未重启、8090/8092 停止）照本报告采信。
- 缺陷登记：**E5-a**（= D-a，8092 无页面，与看板 §3.5 ④/F-23 同源，保持未修）、**E5-b**（= D-b，D-142 §2 第 3 项硬缺口，挂「平台页面」泳道，不在 E5 本轮修）、**E5-c**（= D-c）、**E5-d**（= D-d，D-142 §1.1/§2 第 4 项未达成项）。
- O-a **由观察升为缺陷**（`dataUpdatedAt` 口径混淆，应为运行完成时间，登记不修）；O-b **并入 F-88**、不单独立项；陷阱 **#58**（随机 `traceId` 假阳性）已采信入台账。
- 保留指令：本次采集/页面行为产物**一律保留不删**；`landing/events` 基线**正式变更为 62**（+497 B）。
- **本轮不动生产代码**：本泳道未做任何代码修改，也未在 E5 范围内修 E5-a…E5-d。
