# R2 出口包：CT 集成后的真实本地链复跑（golden-55）

- 证据目录：`docs/acceptance/t2rerun-golden55-post-ct-20260912/`
- 施工单：本目录 `ORDER-1.md`（§1 门禁 G1–G5、§2 范围、§3 预测 P1–P5＋口径红线、§4 E3-a…E3-h 判据、§5 禁改面、§6 未实测/不得声称、§7 回滚）
- 日期：2026-09-12（主跑 17:22–17:28；同输入对照跑 17:34–17:39；E3-d 与 E3 收口 19:17–19:45）
- 跑前 HEAD：`095118afc7cc1a6a2a6287e54422e43cbdb7e08a`（CT 批已集成：`5690ffb` 落地 → `e9a9bdf` 登记 → `095118a` 归档）

## 0. 归属与过程留痕（**必读**）

原执行泳道 `313a4f8f-833d-4b99-bf72-7d02f661dd22` 于 **17:4x 失败退出**（状态 `ready`，收尾消息为空），
留下了采集/主跑/对照跑/守卫的全部原始读数，但**未**写 README、**未**执行 E3-d、**未**完成 E3-b/c/f/g 的逐条判定。
父侧于 **17:17–19:45** 接管并完成：E3-a 事实补全、E3-b、E3-c、E3-d（19:19–19:28 实测）、E3-e（已由泳道跑出，父侧复核）、
E3-f、E3-g、E3-h 引用、本 README、`tools/e3-close-verify.ps1`（可复算自检脚本）。

另外：**17:46–19:17 之间 8090/8091/8092 三个本地服务均停止**（用户指令「暂停推进，把所有进程退出」）；
父侧 19:17:49 **仅**重启 8091（jar 指纹与跑前登记逐值一致 ⇒ 同一制品）。
⇒ **19:1x 之后的读数按「后续时点」登记，不与 17:xx 读数混算，也不构成冻结窗口。**

## 1. 一句话结论

**CT 未破坏指标面**——同输入对照跑（run 43 vs CT 前 run 41）在四个通道上逐值相同：
指标 10/10 五元组全等（含 `unit`/`period`/`definition_version`）、ADS 镜像 8 表按快照逐表相同（`1,4,4,9,1,9,1,1` = 30）、
导出 `totalRows=30` 且 8 个 jsonl 与库侧**按表名对齐**全等、接口 10 值逐字等于 P1-01 冻结基线。
**主跑（run 42）因输入与基线不同（批 41 = 2,740 接受行 vs 批 31 = 1,000 行）不得用于验证 P3/P5 的字面预测。**
E3-d（提交器 `status()` 迁移 + 一次受控 `cancel()`）已由父侧实测补齐并**通过**。
**E3-f 字面判据（数仓跑前跑后逐值相同）不成立**：文件数 972 全等，字节 4,977,445 → 8,177,073（见 §3）。
新增缺陷 **F-85**（pipeline run 无取消端点）、**F-86**（一个历史导出目录不是合法 JSON）。

## 2. 运行事实（E3-a：runId / 批 / 起止 / 阶段 / 快照）

| 项 | 主跑 run **42** | 同输入对照 run **43** |
|---|---|---|
| 触发版本 | `r2-post-ct-20260912-172230` | `r2ctl-b31replay-post-ct-20260912-173418` |
| 起止（DB，权威口径） | 17:22:48.200 → 17:28:12.823（**324 s**） | 17:34:58.008 → 17:39:51.973（**293 s**） |
| 输入批 | 批 41：`QUARANTINED`，接受 **2,740** + 隔离 **4**（6 文件，其中 5 个新文件；golden-55 副本贡献 51+4） | 批 42：`SUCCESS`，接受 **1,000** + 隔离 **0**（1 个新文件） |
| 输入同一性 | 非基线输入 | WAIT_LANDING `checksum=89b82028` = **CT 前 run 41（批 31）** ⇒ 内容级同输入 |
| 阶段 | **8/8 SUCCESS**：WAIT_LANDING 2740、INIT_SCHEMA 37、LOAD_ODS 2740、BUILD_DWD 831、BUILD_DWS 3、BUILD_ADS **22**、QUALITY_CHECK 6、PUBLISH_METRIC 44 | **8/8 SUCCESS**：1000 / 37 / 1000 / 159 / 10 / **30** / 6 / 60 |
| 目标快照 | `S20260901_42`（跑后 ARCHIVED **v10**） | `S20260901_43`（**ACTIVE v11**，当前） |
| 镜像 8 表按快照 | `1,4,4,4,1,4,1,3` = **22** | `1,4,4,9,1,9,1,1` = **30** |
| 十指标 | 与基线不同（输入不同 ⇒ 不可归因） | 与 P1-01 冻结基线逐值相同 |

- 口径（本轮实测，务必照此写）：`ingestion_batch.record_count` 是**接受**行数，**不是**「接受 + 隔离」；
  **总读入 = record_count + quarantine_count**；`QUARANTINED` = 该批**存在隔离行**（0 行则 `SUCCESS`），**不是**失败。
- **口径更正（以此为准）**：泳道 R2-b 行写的「run 43 = 339 s」是**外部墙钟**口径（含注入与提交等待）；
  DB `pipeline_run.started_at/finished_at` 口径为 **293 s**。两者都真，但**不得混用**；本包一律用 DB 口径。
- 两轮 run 的 `pipeline_run.input_batch_id` **均为 NULL**（F-83，全库 43/43 行 NULL）；
  run↔批次的唯一可信链接是 `pipeline_stage_run.evidence` 的 WAIT_LANDING JSON。

## 3. E3-a…E3-h 逐条判定

| 判据 | 要求 | 实测 | 结论 | 证据 |
|---|---|---|---|---|
| **E3-a** | 8/8 阶段 SUCCESS + runId/批号/快照号/起止时刻 | 见 §2（两轮均 8/8；快照链 `_41` v9 → `_42` v10 → `_43` v11 无跳号） | **PASS** | `raw/e3a-poll-run42-*.txt`、`raw/e3a-poll-run43-*.txt`、§2 的 DB 查询 |
| **E3-b** | 指标**逐值**自连接差集 = 0 行 | 配对 10 行（防「零配对=零差异」假绿）／差集 **0** 行／正向对照（+1 扰动）**10** 行／快照指标 10 行；接口 10 项 vs P1-01 冻结基线 **0 处不符** | **PASS** | `raw/e3-close-readings-20260912-192734.txt`（B0–B4） |
| **E3-c** | 镜像按快照 8 表 `COUNT(*)` 与 Hive 侧逐个比对 | 镜像 `_43` = `1,4,4,9,1,9,1,1` = 30；`_41` 同 = 30；导出 `totalRows=30`、8 个 jsonl 与库侧**按表名对齐 8/8 全等**。**对「正式表」的逐行比对 N/A**：ADS 只写 `{table}__staging/snapshot_id=…`，8 张**正式**表本轮**未被写入**（`dw_ads.db` 跑前跑后均 30 文件/54,737 B **逐值相同**，文件 mtime 停在 09-10 18:07）⇒ 见 F-84 | **PASS**（可判定部分）／正式表比对 **N/A（有据）** | 同上（C-*、C-align）+ `raw/state-pre-20260912-171915.txt` |
| **E3-d** | `status()` 迁移序列 + 一次 `cancel()`（`Get-Process` 前后，含子进程/退出码） | 模块测试 4/4 绿（`RUNNING→CANCELLED`、退出码 0/非 0 → SUCCESS/FAILED、未知作业 → SUBMITTED）；父侧长窗口探针：`t+0.1…t+12s` 连续 13 次 `RUNNING` → `cancel()` 即时 `CANCELLED`（+2 s 仍 `CANCELLED`，不回退）；**外部** `Get-Process` 捕获 `cmd/50684` + 子 `PING/5384` 并于 cancel 时刻（相差 ~0.2 s）双双消失 | **PASS** | `raw/e3d-readings-20260912-1924.txt`、`raw/e3d-probe-stdout-*.txt`、`raw/e3d-external-process-events-*.txt`、`raw/e3d-surefire-*.xml`、`raw/e3d-M111Probe.java.txt` |
| **E3-e** | 守卫 ⊆ 台账 + 正向对照 + 台账只减不增 | 退出码 0；正向对照 PASS（`对照样本锚点=4 裸锚点=1 → §2.5 L92`）；锚点 313、裸锚点 199 处/112 行全部在台账内；台账 113 ≤ 基线 113；台账文件与 HEAD 逐字节相同、mtime 13:10:18（早于 R2 窗口） | **PASS** | `raw/guard-bare-anchors.txt`、`raw/guard-ledger-identity.txt` |
| **E3-f** | `spark-warehouse` 跑前跑后 parquet 文件数+字节**相同**，否则逐项列举 | 文件数 **972 全等**；字节 **4,977,445 → 8,177,073（+3,199,628）**：`dw_ads.db` 30/54,737 与 `dw_dim.db` 3/8,198、`probe_r613.db` 2/1,278 **逐值相同**；`dw_ods.db` 866/**4,567,412 → 7,766,992**、`dw_dwd.db` 57/317,273 → 317,297（+24）、`dw_dws.db` 14/28,547 → 28,571（+24）。**归因**：ODS 866 个文件的 mtime **全部落在 17:35:18–17:35:39（20 s 单窗口）** ⇒ 该层是**覆盖写**（非追加；否则会残留 run 41/42 的旧文件）；对照跑输入与 run 41 **checksum 相同**、分区集相同 ⇒ 字节差**可归因于 CT 之后的 ODS 写路径**（**未逐列证实**：未比 parquet schema/列集合） | **不成立**（红，已逐项列举＋归因边界） | `raw/e3-close-readings-20260912-192734.txt`（F-*） |
| **E3-f 附** | 17:28:40 那帧能否作「跑后」读数 | 该帧 `spark-warehouse` = **137 文件/1,234,592 B**，与跑前（972/4,977,445）和现静默窗口（972/8,177,073）**互斥**，且该脚本无逐文件清单、无静默窗口声明 ⇒ **不采信为跑后读数**（判为运行中/枚举进行中的部分读数） | **不采信**（证据方法缺陷，登记陷阱 #41） | `raw/state-post-20260912-172840.txt` |
| **E3-g** | 落地区时点 + 计数 + 字节 | events 59 文件/406,114,133 B（17:19:15）→ **61/406,496,411 B**：**+2 文件/+382,278 B = 18,430（`r9-m1-123006.jsonl` 的 byte-identical golden-55 副本）+ 363,848（批 31 接受件重放副本）逐值闭合**；manifests 40/36,733 → **42/38,861**（+2） | **PASS** | `raw/e3-close-readings-20260912-192734.txt`（G-ev、G-mf） |
| **E3-h** | jar 指纹 | 8091 制品 `33,135,937 B` / sha256 前 16 `C099F307D80135E2`，跑前登记与 19:17:49 重启后**逐值一致** ⇒ 同一制品 | **PASS**（制品同一）／**不得**外推 | `raw/jar-fingerprint.txt`、`raw/proc-8091-cmdline.txt` |

## 4. 预测 P1–P5 的裁定（先写死、后开跑，原件 `raw/predictions-before-run.txt`、`raw/predictions-control-run.txt`）

| 预测 | 字面内容 | 裁定 |
|---|---|---|
| **P1** | golden-55 文件 = 51 接受 / 4 隔离 | **命中**（逐文件分解实测：golden-55 副本 51+4；其余 5 个文件贡献隔离 0。批汇总 2,740+4 = 2,744 = 6 个输入文件非空行之和） |
| **P2** | 离线 schema 校验失败行集合 = 改前集合**减去** items 相关失败行 | **未跑（明确未测）**——ORDER-1 §2 未纳入本轮范围，不得声称已复现 |
| **P3** | 源 A `day:2026-09-01` 十指标与 P1-01 基线逐值一致 | **同输入对照命中**（run 43 十项四元组全等）；**主跑不适用**（输入不同）；**不得**声称主跑命中字面预测 |
| **P4** | 真实链上至少观测到 RUNNING 与 SUCCESS 两态，且 `cancel()` 后进程树不再存在（有 pid/退出码） | **命中**（真实链 `run.status=RUNNING currentStage=INIT_SCHEMA`、`INIT_SCHEMA=RUNNING`；`cancel()` 与进程树回收见 E3-d） |
| **P5** | 同一快照 8 表各为 `1/4/4/9/1/9/1/1`（=30） | **同输入对照命中**（run 43 = 30，逐表相同）；主跑 22（输入不同，不适用） |

## 5. 红项、未测与不得声称

**红项（不得改写为通过）**
1. **E3-f 字面判据不成立**（数仓字节 +3,199,628；逐层见 §3）。归因**仅到**「CT 之后的 ODS 写路径」，**未**逐列证实（未比 schema）。
2. **P2 未跑**（离线 schema 校验未复现）。
3. **F-85**：pipeline run **无取消端点**——实测 `PipelineController` 仅 `POST /api/v1/pipeline-runs`、`GET /{id}`、`POST /{id}/retry`、`GET`；
   唯一的 `/{id}/cancel` 属 `DecisionController`（决策域）。⇒ 「API 取消长跑」现状**不可达**；E3-d 只能在提交器层取证。
4. **F-86**：`metric-staging/S20260901_21/_export.json` **不是合法 JSON**（`tables[0].exportFile` 中 Windows 路径反斜杠未转义，
   严格解析器报 `Bad JSON escape sequence: \D`）；其余 **11/12** 个导出目录（含 `_41`/`_42`/`_43`）合法 ⇒ 属**历史**缺陷、范围已界定。
5. **F-81 旁证不完整**：jar 指纹只能证明**制品同一**，**不能**证明「该 jar 由当前 HEAD 构建」（无 commit/输入指纹列）⇒ E3-h 不得外推。
6. **F-83 / F-84 未修**（仅登记）：`input_batch_id` 全 NULL；ADS 正式表与 `__staging` 双所有者、正式表自 09-10 18:07 起未写。

**未测（明确标记）**
- 集群档（E4 的 1,000 行结论**不外推**到本轮）；`spark-jobs` 模块自动化（E1-b/c/d）、E2 其余、E5 页面级；
- CT 对 E1-b/c/d/E2/E3–E5 的覆盖（CT 批只覆盖 E1-a）；
- 未验证 `CANCELLED` 在**真实 Spark 阶段执行器**中的消费路径（探针用 `ping`，非 `spark-submit`）；未验证集群档提交器的 cancel；
- 未做逐列 parquet schema 对比（E3-f 归因的边界）；
- 未验证「HTTP 层取消后阶段状态一致性」（端点不存在）；
- 本轮**不是**冻结窗口（19:1x 后服务状态变更；非 T2 首跑窗口，**不得**与 T2 首跑直接比较计数）。

**不得声称**（ORDER-1 §6 ＋ 本轮新增）
① E1-a 满足 PLAN 的字面判据（主检基线即 537 tests / 1 failure）；② CT 的 batch 覆盖 E1-b/c/d、E2、E3–E5；
③ E5 或页面级、MySQL 侧导出已完成；④ `ljp` 已在集群验证、CRLF 构建的 jar 在集群行为已验证；
⑤ `sci` DDL 等于生产 `warehouse/ddl/` 口径；⑥ M3 接入完成；⑦ H1/H2 或 M1–M7/L1–L6 已全修；⑧ M1-9 端到端；
⑨ 密钥问题已彻底修复；⑩ T2 的 55 条重跑已复现（P2 未跑）；⑪ pom 中 flag 存在即等于生效；
⑫ E4 超出「单机 1,000 行」范围即 `DONE`；⑬ 契约语义正确（本轮只证形状）；⑭ 台账已收敛；
⑮ 孤儿表读数描述实时快照（`analytics_meta.metric_snapshot|metric_value` 是历史冻结副本，权威在 `analytics_metric`）；
⑯ 批 41 的状态是失败；⑰ 原字面预测 P3/P5 在主跑命中；⑱ 数仓跑前跑后逐值相同；⑲ CT 造成了 ODS 字节差（**归因未逐列证实**）；
⑳ 「用 API 取消 pipeline run」可行。

## 6. 目录内容与复算方法

```
README.md                      本文件（入口）
ORDER-1.md                     施工单（判据来源）
PARENT-VERIFY.md               父侧独立复核（主跑 §1–§5 ＋ E3-e/对照/E3-d 补记）
PROGRESS.md                    泳道过程记录
raw/                           全部原始读数（清单见下）
tools/                         可复算脚本（含自检）
```

| 复算目标 | 命令（在仓库根执行） |
|---|---|
| E3-b / E3-c / E3-f / E3-g 一键复算（30 条判据、带正向对照与条数自检） | `pwsh -File docs/acceptance/t2rerun-golden55-post-ct-20260912/tools/e3-close-verify.ps1` |
| E3-e 守卫 | `pwsh -File scripts/check-bare-anchors.ps1` |
| E3-d 提交器 | `mvn -o test -f analytics-server/pom.xml -pl connection-ingestion -am -Dtest=LocalProcessSparkSubmitterProcessTest -Dsurefire.failIfNoSpecifiedTests=false` |
| 对照跑判定（泳道产出，183 行/CR=0） | `raw/control-verdict-run43-20260912-174203.txt` |

> `tools/e3-close-verify.ps1` 的退出码**如实反映判据**：当前 **PASS 25 / FAIL 2 / DIFF-登记 3，exit 1**
> （FAIL = E3-f 字节差 + 17:28:40 帧不采信；DIFF = 三层逐项列举）。**红项不隐藏、不改写。**

`raw/` 主要文件：`state-pre-*.txt`（跑前：落地区/数仓/MySQL 全量读数）、`state-post-*.txt`（17:28:40 帧，**不采信**）、
`predictions-before-run.txt`、`predictions-control-run.txt`（先写死的预测）、`p1-input-identity.txt`、`p1-batch41-breakdown.txt`（含两条口径专条）、
`e3a-poll-run42/43-*.txt`（阶段轮询原文）、`control-*.txt`（对照跑：输入纯净性、四通道判定、注入响应）、
`guard-*.txt`（守卫两判据）、`jar-fingerprint.txt`、`e3d-*`（E3-d 五项）、`e3-close-readings-20260912-192734.txt`（E3 收口 30 条判据输出）。

## 7. 本轮新增的陷阱与教训（供看板登记）

- **#40**：本机 `Get-CimInstance Win32_Process` **会挂起**（两次超时 180 s / 120 s 后被杀）⇒ 进程取证用 `Get-Process`；
  `Get-NetTCPConnection` 在端口无监听时同样会挂起 ⇒ 优先 `Get-Process` / `Test-Path`。
- **#41**：**运行中对目录树枚举会静默漏读**，产出「内部自洽但与前后帧互斥」的假读数（17:28:40 帧 137 文件 vs 前后 972）。
  状态采集脚本必须：声明**静默窗口**、给**逐文件清单**（否则事后无法追认）、并做**前后帧一致性自检**。
- **#42**：surefire 的属性名是 `-Dsurefire.failIfNoSpecifiedTests=false`；写成 `-DfailIfNoSpecifiedTests`
  会「无匹配测试即 FAILURE、后续模块 SKIPPED」——**不是**测试失败，误读会制造假红。
- **复犯登记**：陷阱 #1（`-Dfile.encoding=UTF-8` 未加引号 ⇒ JVM 收到 `-Dfile` + `.encoding=UTF-8`）本轮**第 3 次**复犯。
- **口径类**：同一事实两套口径（339 s 墙钟 vs 293 s DB）必须择一并标注；「工具调用失败」不得退化为「读数 0」（本轮在自查脚本里又犯一次，已改为显式抛错）。
