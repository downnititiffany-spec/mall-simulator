# M1-4 D10 收口：失败运行也必须留下逐类事件账本（2026-09-12 10:55–11:03，总控执行）

**一句话结论**：终态 `FAILED` 的真机运行（`error_code=RUN_FAILED`、`failed_count=0`、840 条事件已进规范流、19 次商城操作被真实拒绝）
现在同样留下**逐类事件账本**：库内 `generator_meta.generation_event_stat` 7 类共 840 条、运行报告 `event_stats` 与之一致、
规范流 840 行**独立重算**一致，三方金额按"分"整数比较**完全相等**。修复前同一形态的运行（U1/U3 轮 195 条入流）是 `event_stats: []`
（事实记录 L1155）——**本轮把"缺的那一半"补上了**，且不是靠"失败时另算一份"，而是靠把账本收归**一个**归属。

---

## 1. 授权与边界

- **授权**：用户 2026-09-12「另外继续下一步，继续推进项目」；本轮改的是自建程序（生成器）的代码与测试，并在**自建商城**上跑一次真实失败运行
  （追加性写入：新增用户/订单/退款与库存扣减，非破坏性；同性质写入已由 D-041 批准）。
- **不动**：8090/8091 的代码、配置、数据（只经 HTTP 与只读 SQL 与其交互）；D12 轮的既有证据与运行数据；历史证据文本（只追加，不改写）。
- **契约边界**：`event_stats` 的**字段名与结构不变**（`{event_type, count, amount}`），变的是它在**失败运行**里也不为空；
  `GenerationEngine` 接口**新增**一个 4 参重载（纯加法，3 参重载保留为 `default`）。
- **凭据纪律**：商城会话令牌只在进程环境里传递（`GENERATOR_TARGET_TOKEN`），**值未写入任何文件、未打印**；日志里只有长度。
- **只读取证**：收口脚本 `scripts/d10-verify.ps1` 不写库、不发商城请求（金额用整数分比较，避免浮点尾差）。

## 2. 缺陷与修法（D10）

**缺陷（真机形态已取证，事实记录 L1155）**：`MallApiGenerationEngine.runForTarget` 在**事件已经写进规范流之后**、因
`failed > 0` 抛 `MallOperationException`；`GenerationRunService.execute` 的局部变量 `outcome` 因此**根本不存在**，
旧 `persistEventStats` 遇到 `outcome == null` 直接 `return`，旧 `writeReport` 又把统计写成 `outcome == null ? List.of() : …`
⇒ 库内 `generation_event_stat` **零行**、报告 `event_stats: []`。**失败运行的逐类分布随异常一起丢了**——
而"失败前到底进了多少条、都是什么类型"恰恰是排查失败最需要的信息。

**修法（反熵口径）**：把"事件是否进了规范流"的记账权，从"引擎内部的局部 `Map` + 成功后回传给 `outcome`"改成
**运行账本 `EventStatsRecorder`：由调用方（`GenerationRunService`）持有，引擎每成功写一条就记一笔**（`sink.write` 成功之后）。
于是成功与失败**共用同一条代码路径**，不存在"失败分支另算一份统计"：

- `GenerationEngine` 接口把 `run(request, sink, cancelled, eventStats)` **提升为契约方法**，3 参重载退化为 `default`（引擎自建账本），
  接口 javadoc 增一条硬约束：「逐类账本交给调用方」——账本若留在引擎内部，失败样本就只剩 `event_stats: []`；
- `FileModeGenerationEngine`：局部 `Map<String, EventTypeStat> stats` 被账本字段取代，`outcome.eventStats()` **就是**账本快照
  （同一本账，不再是第二处统计）；
- `MallApiGenerationEngine`：账本经 `ForwardingSink` 记"真的转发成功"的事件；它内部那个文件引擎**另建一本账**
  （它数的是"**尝试**"、含被商城拒绝的），两本账**不共用**——共用就等于给同一件事留两处数字，重复计数；
  3 参 `run` 仍是响亮失败（`UnsupportedOperationException`），现在的失败信息与 4 参入口完全一致；
- `GenerationRunService`：`persistEventStats` **删掉** `outcome == null` 早退；`writeReport` 的 `stats` 改从账本取，
  并在失败路径写入口径 note：账本为空 ⇒「运行在写入任何事件之前就失败了：本次 event_stats 为空是事实（规范流里一条都没有），不是统计缺失」；
  账本非空 ⇒「部分真相，N 条 = success_count；被商城拒绝、未进规范流的事件不在其中」。

**为什么"空"与"缺失"必须分开写**：`event_stats: []` 有两个完全不同的含义——"真的一条都没写进去"（事实）与"我们没记"（缺陷）。
修复后由 note 明确区分，避免下一次再把前者读成后者。

## 3. 变更清单

| 文件 | 改动 | 目的 |
| --- | --- | --- |
| `synthetic-data-generator/.../engine/EventStatsRecorder.java` | **新增** | 运行账本（按事件类型累计条数与金额）；javadoc 写明"不含金额口径、口径留在引擎里"、与 `OperationJournal` 同构（服务持有、引擎写入） |
| `.../engine/GenerationEngine.java` | 4 参 `run` 升为契约，3 参降为 `default`；javadoc 增第 5 条约束 | 让"账本归属调用方"成为**接口级**约定，而不是某个实现的私事 |
| `.../engine/FileModeGenerationEngine.java` | 账本字段替换局部统计；写入成功后 `eventStats.record(...)`；`outcome.eventStats()` 取账本快照 | 成功/失败同源 |
| `.../engine/MallApiGenerationEngine.java` | 账本经 5/6/7 参 `runForTarget` 贯通；`ForwardingSink` 记入账本；删掉 `stats()` 访问器；内部文件引擎用独立账本并加注释说明口径差异 | 失败前已入流的事件必须留下分布 |
| `.../service/GenerationRunService.java` | 建账本并传给两个引擎；`persistEventStats` 去掉 `outcome == null` 早退；`writeReport` 从账本取 `stats` 并写失败口径 note | 失败运行也写库、也写报告 |
| `.../engine/FileModeGenerationEngineTest.java` | +1 用例 | 账本归属与"账本 = 磁盘行数"不变量 |
| `.../engine/MallApiGenerationEngineTest.java` | +1 用例 | 引擎抛异常后，账本仍是"失败前真的进了规范流"的逐类分布、且与规范流逐类相等（不重不漏） |
| `.../web/MallApiGenerationSmokeTest.java` | +1 用例 | 服务级端到端：FAILED 运行下库内/报告/规范流三方一致 + 失败 note |
| `.gitignore` | +`generator-output/` | 运行产物（规范流/流水/报告）不入库；此前该目录是**未忽略的未跟踪目录** |
| 本目录 | README + `scripts/d10-verify.ps1` + `raw/*.log` ×11 | 证据与可复现只读复算 |

## 4. 测试证据

| 层次 | 命令（要点） | 结果 | 日志 |
| --- | --- | --- | --- |
| E1 | `mvn -o test-compile -f synthetic-data-generator/pom.xml` | **先失败**：`GenerationRunService.java:310` 无法把 4 参 `run` 应用到 `GenerationEngine`（服务持有的是**接口**，我只把 4 参重载挂在具体类上） | `raw/e1-d10-compile-20260912-105646.log` |
| E1 | 同上（把 4 参提升为接口契约后） | `BUILD SUCCESS`（68 源文件 + 18 测试源文件） | `raw/e1-d10-compile-20260912-105719.log` |
| E2 RED | `-Dtest=MallApiGenerationSmokeTest#failedRunStillKeepsPerClassEventStats -DforkCount=0` | **失败**：`失败运行也必须留下逐类分布（D10：event_stats 不许为空） ==> expected: <false> but was: <true>`；`Tests run: 1, Failures: 1, Errors: 0`（2.927 s） | `raw/e2-d10-RED-20260912-105517.log` |
| E2 GREEN | 同上 | **通过**（2.748 s） | `raw/e2-d10-GREEN2-20260912-105750.log` |
| E2 全模块 | `mvn -o test -f synthetic-data-generator/pom.xml`（基线配方，**不加** `-DforkCount=0`，见 F-19） | `Tests run: 100, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`（基线 97 ⇒ +3） | `raw/e2-d10-generator-full-20260912-105845.log` |
| E3 真机 | CLI `plan-append` + `run-start`（860 事件、seed 20260912、target 118） | 终态 `FAILED`（预期），10.7 s | `raw/e3-d10-plan-append-…log` / `raw/e3-d10-run-20260912-110136.log` |
| E3 复算 | `pwsh -File …/d10-verify.ps1 -RunId …` | **全部判据通过**（22 项 PASS） | `raw/e3-d10-verify-20260912-110316.log` |

新增的三条用例（都在既有测试类里，用真实 `FakeMallServer`/真实 `JsonlEventSink`，不打桩）：

1. `MallApiGenerationSmokeTest#failedRunStillKeepsPerClassEventStats`（服务级）：FAILED 运行 → 库内逐类条数/金额与
   **独立重算**（金额口径从 payload 字段再推一遍：`order_created→total_amount`、`order_paid`/`refund_created`/`refund_completed→amount`、其余为 0）
   相等；报告 `event_stats` 相等；`Σ 条数 = success_count`；正控 `order_created.amount > 0`。
2. `MallApiGenerationEngineTest#eventStatsLedgerSurvivesMallRejection`（引擎级）：真实被拒抛异常后，**调用方账本非空**，
   且与规范流逐类相等（多一条 = 文件引擎那本"尝试账"被共用，少一条 = 漏记）。
3. `FileModeGenerationEngineTest#perTypeLedgerIsOwnedByCallerAndMatchesTheStream`（引擎级）：
   `outcome.eventStats()` 与调用方账本快照**同一个内容**、账本条数 = `sink.eventRecords()` = 事件预算。

## 5. 真机执行链与预注册预测

**链路**（每一步都有原始日志）：

1. 停 8092（打包前必须停，F-20）→ `mvn -o package -DskipTests` → `BUILD SUCCESS`；产物自证是 fat jar：
   **26,219,654 B**、`jar tf | Select-String '^BOOT-INF/'` = **171 条**、sha256 `11EE6F7F8C1D7FD6FC0CA8F4DAC1704B8E518D3C58F316BEC52C3D8A286D8F22`
   （对照 F-20 记录的上一个 fat jar：170 条 / 26,218,013 B ⇒ 多的那一条正是 `EventStatsRecorder.class`）→ `raw/e1-d10-package-20260912-110056.log`；
2. 重启 8092（pid **19268**，工作目录=仓库根，令牌经**进程环境**注入），探活 `/api/v1/scenarios` 通过；
3. `plan-append`：`plan_id=m1-4-d10-failpath-v1 version=1 mode=MALL_API scenario=normal seed=20260912 event_count=860`；
4. `run-start`：run_id `m1-4-d10-failpath-v1-v1-20260912-110139-8803`，`SUCCESS` 以外的终态 ⇒ CLI 退出码 **2**（预期）；
5. 只读复算脚本 22 项判据全绿。

**为什么这次必然失败（运行前的推理，可从商城库直接核对）**：`productsFor(860)=clamp(860/20,4,2000)=43` ⇒ 商品池 = **全部 43 件在售商品**；
其中 **5 件没有 `inventory` 行**（`product_id`：`2096441182850224129`、`2096441182913138689`、`2096441182976053249`、`2096441183038967809`、`2096441183038967810`），
而商城下单走 `UPDATE inventory … WHERE available_qty >= ?`，缺行商品必然 0 行 ⇒ `MallBizException(INSUFFICIENT_STOCK)` ⇒ HTTP 400。

**预注册与实测**：

| # | 预测 | 来源 | 实测 | 结论 |
| --- | --- | --- | --- | --- |
| P1 | 本次运行终态是 `FAILED`（不是 SUCCESS） | 启动命令横幅（随原始日志留档） | `status=FAILED`、`error_code=RUN_FAILED`、`success_count=840`、`failed_count=0` | ✅ |
| P2 | CLI 退出码 2（非 0），且不得当作命令失败 | 启动命令横幅 | `exit=2`，用时 10.7 s | ✅ |
| P3 | 失败运行 `event_stats` 非空、`Σ 条数 = success_count` | 已由 E2 用例先钉死（RED→GREEN） | 库 7 类 840；报告 840；规范流 840 行 | ✅ |
| P4 | 触发原因是**无库存行商品**导致的 `INSUFFICIENT_STOCK` | 运行前推理，**未落横幅**（半预注册） | 9 次 `INSUFFICIENT_STOCK`，点名 4 件商品，SQL 复核 4 件**全部** `NO_INVENTORY_ROW` | ✅ |
| P5 | 商城侧增量与规范流逐类条数相等 | 运行前推理 | Δ`mall_order` +355 = `order_created` 355；Δ`mall_user` +71 = `user_registered` 71；Δ`refund` +8 = `refund_created` 8；Δ`order_item` +355 | ✅ |
| P6 | 平台侧零改动 | 本轮不触 8091 | `ingestion_batch 40 / pipeline_run 40 / file_checkpoint 105 / metric_snapshot 9` 与运行前同值 | ✅ |

**被拒 19 次的完整分布（真实流水）**：9 次 `createOrder → HTTP 400 INSUFFICIENT_STOCK`（4 件无库存行商品）+
10 次「订单在商城侧还没有外部 ID（下单事件未成功派发），拒绝用假 ID 继续」（pay 4 / cancel 5 / refund 1）——
**下游拒绝是上游拒绝的忠实后果**，不是第二类缺陷。另有 1 条 `SKIPPED`（`refund` 复用未找到可复用退款单，`supported=false`，D-046 已定的缺口口径）。

**流水算术（本轮补充的正控）**：861 行 = `real_http` **809** + `local_accounting` **51** + `SKIPPED` **1**；
`FAILED` **19**（= 报告里的被拒次数）；`OK` **841** = 入流 840 + 预检 1；
逐操作 `OK` 条数与入流条数**逐项相等**：`createOrder 355 / cancel 239 / pay 116 / createSyntheticUser 71 / refund 16 / listProducts 44(=预检 1 + 商品对齐 43)`。

## 6. 收口判据

| # | 判据 | 结果 |
| --- | --- | --- |
| ① | E1 编译通过（生成器模块 test-compile） | ✅ `raw/e1-d10-compile-20260912-105719.log` |
| ② | E2 RED→GREEN：新用例修复前失败、修复后通过；全模块无回归 | ✅ 100/0/0/0 |
| ③ | E3 真机失败运行：库内 / 报告 / 规范流**三方逐类一致**（条数与金额），`Σ = success_count` | ✅ 22 项断言全绿 |
| ④ | 反熵：逐类统计**只有一个归属**；成功与失败**同一条代码路径**，无"失败分支另算一份" | ✅ 见 §2 |
| ⑤ | 未改动核对：平台侧零改动、商城侧只有本轮运行的追加写入、8090/8091 进程未重启 | ✅ 见 §7 |

⇒ **D10（失败运行逐类统计为空）本轮关闭**；M1-4 整体仍 `DONE_LIMITED`（T2/E5 未取证，§9）。

## 7. 未改动核对

- **平台侧**：`analytics_meta.ingestion_batch=40`、`analytics_meta.pipeline_run=40`、`analytics_meta.file_checkpoint=105`、
  `analytics_metric.metric_snapshot=9` —— 与本轮开始前**同值**（未触发采集/流水线）。
- **商城侧**：代码与结构零改动；数据只有本轮运行的真实追加（orders +355 / users +71 / refunds +8 / items +355，见 P5）。
- **进程**：8090 pid 41228、8091 pid 47132 **未重启**（启动时间不变）；8092 因打包要求重启一次（pid 16996 → 19268，新 fat jar）。
- **既有证据**：D12 轮 README/脚本/日志与 U1/U3 轮证据**未改一字**；事实记录只追加。

## 8. 本轮偏差与脚本自身缺陷（如实登记，日志一律留档）

1. **接口/实现错配导致一次编译失败**：我把 4 参 `run` 挂在 `FileModeGenerationEngine` 上，而 `GenerationRunService` 持有的是
   `GenerationEngine` **接口** ⇒ `无法将…run应用到给定类型`。修法是**把 4 参提升为接口契约**（比在服务里强转具体类型更正确：
   "账本归属调用方"本就该是每个引擎实现的义务）。日志 `raw/e1-d10-compile-20260912-105646.log`。
2. **我把运行报告当成"已注册制品"**：新用例最初用 `artifactBySuffix(runId, "run-report.json")` 取报告，实测**该文件不注册为制品**
   （`generation_artifact` 只登记 events/journal/manifest）⇒ 断言 `expected: not <null>` 失败（`raw/e2-d10-GREEN-20260912-105727.log`）。
   修法：与既有成功路径用例一致，**按约定路径读盘** `target/it-mall-output/<runId>/run-report.json`。**这不是被测代码的缺陷**，
   但也**顺手登记为一条口径**：报告的取用方式是"约定路径"，不是制品表。
3. **复算脚本首次运行把仓库根算错**：`$PSScriptRoot` 上溯 3 级得到 `docs\`，于是报"运行目录不存在"（`raw/e3-d10-verify-20260912-110309.log` 留档，未删）。
   修法：上溯 4 级 + 注释写明层级。
4. **预注册强度弱于 D12 轮**：P1/P2 写在启动命令横幅里（随原始日志带时间戳留档），**没有独立的预测文件**；P4 属运行前推理但未落横幅，
   故标"半预注册"。下一轮真机取证应先把预测写进证据目录再执行。

## 9. 未取证 / 不可宣称

- **T2（55 条黄金链，真实本地 MySQL/Spark/Hive）与 E5（员工可用页面）本轮未取证** ⇒ 不得宣称 M1-4 整体 `DONE`，仍是 `DONE_LIMITED`。
- **文件模式的"服务级失败分支"无稳定触发手段**：本轮只有**引擎级**不变量用例覆盖（`MallApiGenerationEngineTest`），
  服务级失败用例走的是 MALL_API 引擎；文件模式的异常路径（如磁盘写失败）没有可注入的失败点，未造。
- **失败路径的残留字段（已登记、本轮未修）**：报告里 `expected_quarantine_counts = {}`、`dirty_sample_types = []`，
  且 MALL_API 的 gap/ID 映射说明（来自 `outcome.notes()`）在失败运行时**同样缺失**。它们的共同原因与本缺陷同族
  （这些信息都挂在 `outcome` 上），但**不在 D10 的收口范围内** ⇒ 登记为残留，未修、未宣称已修。
- **负证覆盖 4/5**：5 件无库存行商品中，本轮真实被点中并被拒的是 4 件（第 33 位 `2096441182850224129` 未被抽到）
  ⇒ 「无库存行 ⇒ 不可买」得到**首次真实负向证据**，但覆盖是 4/5，不是 5/5。
- **`behavior` / `reset_state` 能力仍是 `UNDETERMINED`**（与本轮无关，状态不变）。
- **本轮真机运行不是 T2/E5 证据**，也不改变 U11 的历史口径（U1/U3 轮 1.0 流水仍高估真实请求量）。

## 10. 文件清单（本目录）

```
README.md                                  本文件
scripts/d10-verify.ps1                     只读复算：库内 / 报告 / 规范流 / 流水四方一致（22 项判据）
raw/e1-d10-compile-20260912-105646.log     编译失败（接口/实现错配）
raw/e1-d10-compile-20260912-105719.log     编译成功
raw/e2-d10-RED-20260912-105517.log         新用例修复前失败（RED）
raw/e2-d10-GREEN-20260912-105727.log       第一次 GREEN 尝试（报告当成注册制品 ⇒ 断言失败，偏差 §8.2）
raw/e2-d10-GREEN2-20260912-105750.log      新用例通过
raw/e2-d10-generator-full-20260912-105845.log  生成器全模块 100/0/0/0
raw/e1-d10-package-20260912-110056.log     停 8092 → 打包 → fat jar 自证
raw/e3-d10-plan-append-20260912-110125.log 建计划（860 事件）
raw/e3-d10-run-20260912-110136.log         真机运行：FAILED + 19 次真实拒绝 + 两件制品
raw/e3-d10-verify-20260912-110309.log      复算脚本首次运行（路径算错 ⇒ 未通过，留档）
raw/e3-d10-verify-20260912-110316.log      复算脚本全绿（收口证据）
```

运行产物（不入库，`.`gitignore` 已加 `generator-output/`）：`generator-output/m1-4-d10-failpath-v1-v1-20260912-110139-8803/`
（`events-0001.jsonl` 355,850 B / 840 行，checksum `2b5ab155551e46bdd53815cb9667e2915e4aa8dc4b70f6a439c0d54324e61f8c`；
`operation-journal.jsonl` 220,086 B / 861 行，schema 1.1，checksum `3d9658f76c16356939fb63101bc54899ae967728d09fece89786879dea7ab19e`；
`run-report.json` 2,719 B；`events-0001.manifest.json` 485 B）。

## 11. 复现命令要点

```powershell
# 0) 打包必须先停 8092（否则 repackage 失败且留下不可执行的瘦 jar —— F-20 实测）
Stop-Process -Id <8092 的 pid> -Force
mvn -o package -DskipTests -f synthetic-data-generator/pom.xml
jar tf synthetic-data-generator\target\synthetic-data-generator-0.1.0-SNAPSHOT.jar | Select-String '^BOOT-INF/'   # fat jar 自证

# 1) 起 8092：令牌只进进程环境（工作目录必须是仓库根）
$env:GENERATOR_TARGET_TOKEN = (商城 /api/v1/auth/login 取回的令牌)   # 不要写进任何文件
java -Dfile.encoding=UTF-8 -jar synthetic-data-generator\target\synthetic-data-generator-0.1.0-SNAPSHOT.jar

# 2) 建计划 + 跑运行（--spring.main.web-application-type=none 避免与在跑的 8092 抢端口）
java -jar <fat jar> --spring.main.web-application-type=none --generator.cli=plan-append `
  --plan-id=m1-4-d10-failpath-v1 --mode=MALL_API --target-id=118 --scenario=normal --seed=20260912 `
  --start=2026-09-08T00:00:00Z --end=2026-09-14T00:00:00Z --event-count=860 --rate=0 --dirty-profile=none
java -jar <fat jar> --spring.main.web-application-type=none --generator.cli=run-start `
  --plan-id=m1-4-d10-failpath-v1 --version=1 --wait-seconds=600     # 预期退出码 2（FAILED 终态）

# 3) 只读复算（可重复执行；不写库、不发商城请求）
pwsh -NoProfile -File docs\acceptance\m1-4-d10-failpath-20260912\scripts\d10-verify.ps1 `
  -RunId m1-4-d10-failpath-v1-v1-20260912-110139-8803
```

**注意**：`-Dtest=类#方法` 里的 `#` 是 PowerShell 注释起始符 ⇒ 必须整体加引号；生成器全模块回归**不要**加 `-DforkCount=0`（F-19）。

## 12. 对既有记录的影响

- **D12 轮结论不变**：本轮改动只改"账本归属"，不产生第二个统计权威；D12 那次运行的 `SUCCESS` 路径数据、流水 `real_http`/`local_accounting`
  口径、U11 = 115 次真实 HTTP 请求的结论**都不受影响**（本轮未重跑该运行，也未改其证据文本）。
- **D10 状态**：由「仍开放（缺的一半）」改为「**已关闭**（本机真实链路 + 只读复算全绿）」；事实记录新增 **D-047**（决策）、
  **F-21**（失败路径残留字段与"报告不注册为制品"）、**F-22**（无库存行商品的首次真实负证：9 次拒绝覆盖 4/5 件）；看板 §3.5/§3.12 同步。
- **U11**：本轮运行不是 U11 的替代证据；U1/U3 轮 1.0 流水依旧**高估**真实请求量（事实记录 D-046 边界不变）。
- **口径新增**：失败运行的 `event_stats` 语义 = 「失败前已进入规范流的逐类分布（部分真相）」——引用该字段时必须带上这半句。
