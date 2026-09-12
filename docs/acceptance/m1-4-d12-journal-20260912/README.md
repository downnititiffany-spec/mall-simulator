# M1-4 D12 流水两列取证 + U11 真实请求量收口（2026-09-12 10:38–10:48，总控执行）

**结论一句话**：D12 已修复并**用真机正向运行收口**——`operation-journal.jsonl` 升 `schema_version 1.1`，新增**派生列 `real_http`** 与**显式列 `local_accounting`**；真机运行 `m1-4-d12-journal-v11-v1-20260912-104653-f1bc` 终态 `SUCCESS`、`success_count=120 / failed_count=0`，流水 **121 行 = `real_http` 114 + `local_accounting` 7 + `SKIPPED` 0**，且**逐操作与商城库内真实增量完全对齐**（10 用户 / 51 订单 / 51 条目 / 15 支付 / 36 取消 / 1 退款）⇒ **U11 收口：该次运行商城侧实际收到 HTTP 请求 115 次**（114 + 1 条退款两跳）。

**不得由此宣称**：M1-4 整体 DONE（S5 仍未开工）；`behavior` / `reset_state` 能力已具备；平台采集与流水线端到端（本轮**未触发** 8091 任何采集/流水线）；D10（失败分支逐类统计）已修；U1/U3 那轮历史流水（1.0）的 33 条 `listProducts` 已被追溯改写（**未改**，见 §9.4）。逐条见 §9。

---

## 1. 授权与边界

| 项 | 内容 |
|---|---|
| 依据 | 任务看板 L227 **D12**：「`operation-journal.jsonl` 把"本地对齐记账"写成 `listProducts`，**高估**对商城的真实读请求量；总控复算确认只有 `canonical_id == null` 那 1 条可确认是真 HTTP。下一轮必须给流水加 `real_http` / `local_accounting` 显式字段（属**证据真伪**问题）」 |
| 本轮边界 | 生成器源码/测试（仅 `synthetic-data-generator`）+ 一次真机正向运行；**不改**商城与分析平台源码；不动 `analytics_meta` / `analytics_metric`；不清理 `generator_meta` 与任何历史证据；不删旧流水 |
| 不在边界内 | 平台采集与流水线、商城目录/用户治理、`source_registry` / `runtime_profile`、D10 失败路径统计 |
| 时序 | 10:38 RED → 10:42/10:42 GREEN（22/0/0/0）→ 10:42 全模块（`-DforkCount=0`，见 §4.3）→ 10:43 全模块基线配方 **97/0/0/0** → 10:44 首次打包失败（瘦 jar 覆盖，见 §8.1）→ 10:45:24 先停 8092 再打包成功 → 10:46:41 CLI 建计划 → 10:46:48 带凭据启动新 8092 → 10:46:53 发起运行（1.2 s 完成）→ 10:47:26 只读复算 14 条断言全绿 → 10:47:47 P10 补正 |

## 2. 缺陷与修法

| 项 | 事实 |
|---|---|
| 原始症状（D12） | 一条"把商城既有目录商品**对齐**到本地规范 ID"的动作，被记成 `operation=listProducts`、`status=OK`，与**真正发起**的目录读请求无法区分。上一轮真机 journal 641 行里 `listProducts` **33 条**，其中只有 `canonical_id == null` 的 **1 条**（预检兼凭据校验）真发过 HTTP，其余 32 条是本地对齐 ⇒ **高估 32 次读请求** |
| 修法（加法） | ① 显式列 `local_accounting`（布尔，**写入时确定**）；② 派生列 `real_http`（**不落第二个权威**：由 `http_method != null && route != null` 派生）；③ 构造器不变量：`local_accounting=true` 且带 `http_method`/`route` ⇒ 立即 `IllegalArgumentException`；`local_accounting=true` 且 `status != OK` ⇒ 同样拒绝；④ `OperationJournal.append(...)` 增第 9 参数，新增语义化入口 `appendLocal(...)`（本地记账只能走它）；⑤ 退款完成复用从"写 OK 行 + 记缺口"改为 `recordGap(...) + return false`（未找到可复用退款单就是缺口，不是成功）；⑥ 报表 note 明写三分类口径与"退款一条流水含两次 HTTP" |
| 为什么 `real_http` 是派生而非第二列的事实 | 反熵原则：**"是否发过请求"只能有一个权威**——`http_method` / `route` 的存在性（适配器真正发请求时才填）。若再存一列布尔，两列就可能互相矛盾（历史上 D12 正是"标签与事实不符"）。派生 + 构造器不变量后，二者在构造期即被锁死一致 |
| 兼容性 | `schema_version` 1.0 → **1.1**（`contract-specs/README.md` L42：「新增字段 → `schema_version` 升 `1.1` 起」）。**旧 1.0 流水一律留在磁盘原样不动**（本轮无任何历史数据改写） |

## 3. 变更清单

| 文件 | 改动 |
|---|---|
| `engine/OperationJournalEntry.java` | record 增 `localAccounting`；新增派生 `realHttp()`；构造器两条不变量；`toJson()` 键序 `seq, operation, supported, real_http, local_accounting, http_method, route, canonical_id, external_id, status, detail`；`supported` 的 Javadoc 改为「能力门判定，不表示发过请求」并写明 D12 三分类 |
| `engine/OperationJournal.java` | 新增 `SCHEMA_VERSION = "1.1"`（含 1.0/1.1 说明）；`append(...)` 增第 9 参数；新增 `appendLocal(...)`；新增 `realHttpCount()` / `localAccountingCount()` |
| `engine/MallApiDispatchSink.java` | 11 处写流水全部改签名；真调用 7 处传 `false`；本地记账 3 处改走 `appendLocal`；`dispatchRefundComplete` 未找到可复用退款单时改记缺口并返回失败 |
| `engine/MallApiGenerationEngine.java` | 预检两条流水（失败/成功）显式传 `false` |
| `service/GenerationRunService.java` | 制品 `schema_version` 按 kind 取值（流水取 `OperationJournal.SCHEMA_VERSION`=1.1，其余仍 1.0）；运行报告 note 增 D12 口径说明 |
| `test/.../MallApiGenerationEngineTest.java` | 新增 `journalSeparatesRealHttpFromLocalAccounting`（用商城侧交换计数做 oracle）；既有 happy-path oracle 改为只数 `realHttp()` |
| `test/.../MallApiGenerationSmokeTest.java` | 流水块改为解析 `real_http` / `local_accounting`，断言两键存在、互斥、真调用必带 method+route、本地记账必不带，并断言 `preflightRealReads == 1` |

**未动**：`contract-specs/**`（没有任何冻结文档规定流水列集 ⇒ 流水属**实现自定义制品**，契约优先不适用）、`scripts/start-all.ps1`（F-17 仍开放）。

## 4. 测试证据

| 层 | 命令 | 结果 | 日志 |
|---|---|---|---|
| RED（先证伪） | `mvn -o test -f synthetic-data-generator/pom.xml '-DforkCount=0' -Dtest=MallApiGenerationEngineTest` | **失败且失败原因正确**：`real_http 必须只数真发过请求的操作… ==> expected: <378> but was: <0>`（字段不存在） | `raw/e2-d12-RED-20260912-103852.log` |
| GREEN-1 | 同上 + `-Dtest=MallApiGenerationEngineTest,MallApiGenerationSmokeTest` | Engine 14/0/0/0 绿；Smoke 1 条失败：`真读目录的流水行只该有预检那一条 ==> expected: <1> but was: <0>`（**我的分类器把预检行也跳过了**，因为它同样 `canonical_id == null`） | `raw/e2-d12-GREEN-20260912-104202.log` |
| GREEN-2 | 同上（修分类器后） | **22/0/0/0 BUILD SUCCESS** | `raw/e2-d12-GREEN2-20260912-104234.log` |
| E2 全模块 | `mvn -o test -f synthetic-data-generator/pom.xml`（**基线配方**：`MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'`、`JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'`） | **97/0/0/0 BUILD SUCCESS**（基线 96 + 本轮新增 1） | `raw/e2-generator-full-baseline-recipe-20260912-104352.log` |

### 4.3 一次被我自己制造出来的"回归"（如实登记，非系统缺陷）

10:42:57 我用**带 `-DforkCount=0`** 的命令跑了全模块，得 `Tests run: 97, Failures: 3, Errors: 1`，四条全部落在 `GeneratorBoundarySourcePolicyTest`，报错都是「…必须存在（守卫不可空跑）」与 `NoSuchFileException: D:\Develop_code\GraduationProject\pom.xml`。根因：该守卫用 `Path.of("").toAbsolutePath()` 当**模块根**（注释写明「Maven surefire 的工作目录是本模块根目录」）；而 `-DforkCount=0` 让测试**在 Maven 进程内**执行，`user.dir` 变成我 shell 的当前目录（仓库根）⇒ 扫描路径 `src/main/java` 落空。**去掉 `-DforkCount=0` 后 97/0/0/0**（§4 末行）。结论：**不是本轮代码造成的回归**；顺带证明该守卫的"不可空跑"断言设计正确——路径不对时它会响亮失败，而不是静默通过。留档 `raw/e2-generator-full-20260912-104257.log`。

## 5. 真机执行链与预注册预测

预注册预测写于发起运行之前（`scripts/d12-live-run.ps1` §0；该次执行的日志因脚本缺陷只剩 1 行，见 §8.2，预测原文在脚本 `§0` 段内可见）。

| 预测 | 内容 | 实测 | 判定 |
|---|---|---|---|
| P1 | 终态 `SUCCESS`、`success_count=120`、`failed_count=0` | `SUCCESS` / 120 / 0；`started=10:46:53.246`、`finished=10:46:54.457`、checksum `85ab8d1f…a8a7` | ✅ |
| P2 | 事件数 120、`user_registered=10`、`product_created=6` | 120 / 10 / 6（与 `usersFor(120)`、`productsFor(120)` 公式逐字相符） | ✅ |
| P3 | `order_created` 落在 40–65 | **51** | ✅ |
| P4 | 流水 121 行、无 `SKIPPED` | 121 行、`SKIPPED=0`、非 OK 0 | ✅ |
| P5 | `real_http = 1+120−6−refund_completed`、`local = 6+refund_completed` | `real=114`、`local=7`（`refund_completed=1`） | ✅ |
| P6 | 预检行 `real_http=true` 且 `canonical_id` 空；商品对齐行 `real=false`、不带 method/route | 预检 real 1 / local 0；对齐 real **0** / local **6**；越界行 0 | ✅ |
| P7 | journal 制品 `schema_version=1.1`、事件流 1.0 | 库内 `generation_artifact`：`OPERATION_JOURNAL` **1.1**（121 行 / 30,594 B）、`EVENT_JSONL` 1.0、`MANIFEST` 1.0 | ✅ |
| P8 | 商城增量逐操作等于流水真实行数 | 见 §6 表，7 项全等 | ✅ |
| P9 | 无 `INSUFFICIENT_STOCK`、无商城 5xx | 非 OK 状态 0 条；池内 6 件商品 `available_qty>0` | ✅ |
| P10 | 平台侧零副作用 | `analytics_meta` 40/40/105、`analytics_metric` 9 与 U1/U3 轮后态**完全一致**；`pipeline_run` 最新 09:26:27、`metric_snapshot` 最新 09:31:13 均早于本轮 10:46:53 | ✅（补正后） |

**样例流水（原文，未改写）**：

```json
{"seq":1,"operation":"listProducts","supported":true,"real_http":true,"local_accounting":false,"http_method":"GET","route":"/api/v1/mall/products","canonical_id":null,"external_id":null,"status":"OK","detail":"目录 43 件，在售 43 件（预检兼凭据校验）"}
{"seq":12,"operation":"listProducts","supported":true,"real_http":false,"local_accounting":true,"http_method":null,"route":null,"canonical_id":"P00001","external_id":"1001","status":"OK","detail":"对齐商城目录商品：磁吸手机支架"}
{"seq":39,"operation":"refund","supported":true,"real_http":false,"local_accounting":true,"http_method":null,"route":null,"canonical_id":"O00000010","external_id":"2098604231447048194","status":"OK","detail":"复用已完成退款"}
```

## 6. U11 收口判据（真机 HTTP 请求量）

| 操作 | 流水真实行数（`real_http=true`） | 商城库内独立观测（窗口 `started_at` 起） | 判定 |
|---|---|---|---|
| `createSyntheticUser` | 10 | Δ`mall_user` = **10** | ✅ |
| `createOrder` | 51 | Δ`mall_order` = **51**（Δ`order_item` = 51） | ✅ |
| `pay` | 15 | Δ`payment`(`paid_at`) = **15**，Δ订单 `paid_at` = **15** | ✅ |
| `cancel` | 36 | Δ订单 `cancelled_at` = **36** | ✅ |
| `refund` | 1（**该行含两次 HTTP**：申请 + 完成） | Δ`refund` = **1**（`COMPLETED`，created 10:46:53.622 → completed .631） | ✅ |
| `listProducts`（预检真读） | 1 | —（读操作无库内行，属流水侧自证） | — |
| `listProducts`（目录对齐，本地） | 0 | —（本地记账 6 条） | ✅ |

**收口口径**：`real_http` 行数 **114** + 退款行的第二次调用 **1** = **商城侧实际收到 HTTP 请求 115 次**。

**诚实边界**：`real_http` 的语义是「**发起过**请求」，本次 `failed_count=0`、非 OK 0 条 ⇒ 本次不存在"发了但被拒"的尝试，故 114 = 全部成功调用；**若将来有失败尝试，`real_http` 行数会大于商城侧成功行数**（这正是它"含失败的尝试"的字面含义，报告 note 已写明）。115 **不含**被商城拒绝、适配器重试、探针（`POST /targets/118/test` 另发 11 条代表路由请求）等本口径外的请求。

**订单状态自洽**：窗口内 `CANCELLED=36 + PAID=14 + REFUNDED=1 = 51` = `createOrder` 行数；`cancel 36` 与 `CANCELLED` 等值，`pay 15` = 14 PAID + 1 REFUNDED（退款单来自已支付单）⇒ 与 U1/U3 轮同一口径。

## 7. 未改动核对

| 对象 | 运行前 | 运行后 |
|---|---|---|
| `analytics_meta.ingestion_batch` / `pipeline_run` / `file_checkpoint`、`analytics_metric.metric_snapshot` | 40 / 40 / 105 / 9 | **40 / 40 / 105 / 9（同值）** |
| 三程序 | 8090 pid 41228（09:40:49）· 8091 pid 47132（09:14:59）· 8092 未监听（10:45:24 已停） | 8090 pid 41228 · 8091 pid 47132 **（未重启）** · 8092 pid **16996**（10:46:48，**换新 jar + 注入凭据**，见 §8.1） |
| `mall_simulator.product` / `inventory` | 43 / 38 | 43 / 38（`product_created` 6 条是**对齐**既有目录，不新建） |
| 无在飞运行 | `non_terminal=0` | `non_terminal=0` |

## 8. 本轮偏差与脚本自身缺陷（如实登记，不改写任何既有结论）

| # | 偏差 | 事实与理由 |
|---|---|---|
| ① | **打包顺序**：10:44:26 在 8092 运行中直接 `mvn package` ⇒ `spring-boot:repackage` 失败（`Unable to rename …jar`，文件被进程占用），**但 jar 插件已把"瘦 jar"（235,196 B）写到同一路径**，现场留下一个**不可执行**的 jar | 我的操作顺序错误，不是构建配置问题。10:45:24 改为 **先停 8092 → 打包成功**（26,218,013 B fat jar，sha256 `4285BA7C…5858`，`BOOT-INF/` 条目 170、含 `OperationJournalEntry.class` 自证），再以新 jar 启动 8092。留档 `raw/e1-d12-package-20260912-104426.log`（失败）与 `raw/e1-d12-package-r2-20260912-104524.log`（成功） |
| ② | **8092 换新 jar 重启（10:45:24 停 → 10:46:48 起，pid 24692→16996）** | 本轮必须让新代码生效；重启前 `non_terminal=0`；凭据仍只注入进程环境（登录响应只打印 `code` 与 token 长度，值不落盘、不打印） |
| ③ | `d12-live-run.ps1` 两处**脚本自身缺陷** | (a) 事件统计循环用了 `$l`，PowerShell 变量名**大小写不敏感** ⇒ 与日志列表 `$L` 同名，把证据列表覆盖成字符串，**该次证据文件只剩 1 行**（`raw/d12-live-20260912-104642.log`，375 B，保留原样）；(b) 终态断言取 `successCount`，而 RunView 字段是 `success_count` ⇒ 唯一那条"失败断言"是脚本误判。真机运行本身**成功**（SUCCESS 120/0，商城读数与库内行为准）。⇒ 判据复算改由**只读**脚本 `d12-verify.ps1` 独立完成（不重复扰动商城），14 条断言全绿 |
| ④ | `d12-verify.ps1` 首版两处查询缺陷 | (a) 计划行查询用了不存在的 `rate` 列（真列名 `rate_per_second`）；(b) **P10 平台侧副作用误查 `mall_simulator` 的同名表**（1/10/10/3，口径完全不同）。两处都由"不吞 stderr"暴露出来（`mysql` 报错可见），补正证据 `raw/d12-p10-analytics-20260912-104747.log`。**这两处都是我的脚本缺陷，与系统无关**——但也说明「拿错库名会得到看似正常的数字」，与 F-18 同类 |
| ⑤ | 运行目录 `generator-output/` 与 `.verify/` 为 gitignored 运行产物 | 流水/事件流原文以**磁盘 sha256 + 库内 `generation_artifact`（uri/bytes/records/checksum）**三处互证方式入库，不把运行产物塞进 git |

## 9. 未取证 / 不可宣称

1. **D10 未修**：失败运行（`outcome == null` 分支）的 `event_stats` 仍为空数组（看板 L226）。本轮只覆盖**成功路径**，**不构成** D10 证据。
2. **`behavior` / `reset_state` 仍 `UNDETERMINED`**：本轮 120 条事件不含这两类；`reset_state` 未调用。报告 note「因能力缺口跳过 0 次」指本轮事件构成不涉及该能力。
3. **115 这个数是"本次运行"的**：不含探针请求、不含被拒/重试、不含将来可能的失败尝试；不同 `event_count` 下 `real_http` 与 `refund` 行数都会变（公式已在报告 note 里写明）。
4. **旧 1.0 流水不追溯改写**：U1/U3 那轮 641 行仍是 1.0（其 33 条 `listProducts` 的 real/local 归属**不在旧文件里**，只在本轮 README §2 用"复算口径"说明）。**任何按 1.0 流水统计"真实请求量"的结论仍然是高估的。**
5. **不构成 T2 / E5 证据**：本轮未触发平台采集与流水线；55 条黄金链与员工可用页面仍未开工。
6. **M1-4 整体仍为 `DONE_LIMITED`**：U1/U3 已关闭，D12 本轮关闭，但 S5、D10、U11 之外的 U 项仍未清。
7. **F-17 未修**：`scripts/start-all.ps1` 仍不注入 `GENERATOR_TARGET_TOKEN` ⇒ 由该脚本启动的 8092 依旧无法执行 `MALL_API`（本轮是手工注入 + 手工重启绕开的）。

## 10. 文件清单（本目录）

| 文件 | 字节 | 用途 |
|---|---|---|
| `raw/e2-d12-RED-20260912-103852.log` | 4,682 | RED：新判据在旧代码上以**正确原因**失败（`expected: <378> but was: <0>`） |
| `raw/e2-d12-GREEN-20260912-104202.log` | 13,494 | GREEN-1：Engine 14/0/0/0 绿；Smoke 1 条失败（我的分类器缺陷，见 §4） |
| `raw/e2-d12-GREEN2-20260912-104234.log` | 11,243 | GREEN-2：两个类合计 **22/0/0/0 BUILD SUCCESS** |
| `raw/e2-generator-full-20260912-104257.log` | 27,715 | 带 `-DforkCount=0` 的全模块：97/3 failures/1 error，全在边界守卫（§4.3，非回归） |
| `raw/e2-generator-full-baseline-recipe-20260912-104352.log` | 21,386 | **基线配方全模块：97/0/0/0 BUILD SUCCESS** |
| `raw/e1-d12-package-20260912-104426.log` | 2,774 | 偏差①：8092 运行时打包失败（repackage 无法 rename），瘦 jar 覆盖现场 |
| `raw/e1-d12-package-r2-20260912-104524.log` | 2,789 | 偏差①修法：停 8092 后打包成功 + fat jar 自证（`BOOT-INF/` 170 条、sha256） |
| `raw/d12-live-20260912-104642.log` | 375 | 真机运行那次执行的残缺证据（偏差③a：只剩 1 行），保留原样 |
| `raw/d12-verify-20260912-104726.log` | 11,486 | **只读复算 14 条断言全绿**：流水逐字段分解、样例行、库内制品（schema_version）、逐类事件、报告全文、商城增量逐操作对照、三程序身份 |
| `raw/d12-p10-analytics-20260912-104747.log` | 1,032 | 偏差④b 补正：平台侧真实口径（`analytics_meta` / `analytics_metric`）与运行前一致 |
| `scripts/d12-live-run.ps1` | 20,112 | 真机执行脚本（**修正版**：已修 `$l`/`$L` 同名与 `success_count` 属性名，缺陷说明写入文件头） |
| `scripts/d12-verify.ps1` | 15,247 | **只读**复算脚本（可对任意 run_id 重跑：`pwsh -File scripts/d12-verify.ps1 -RunId <id>`） |

## 11. 复现命令要点

```powershell
# 0) 打包（必须先停 8092，否则 repackage 失败且留下不可执行的瘦 jar —— 本轮实测）
Stop-Process -Id (Get-NetTCPConnection -State Listen -LocalPort 8092).OwningProcess -Force
mvn -o package -DskipTests -f synthetic-data-generator/pom.xml
java -jar ... # 用 jar tf 确认 BOOT-INF 存在（fat jar 自证）
# 1) 建计划（仅 CLI 有入口；--rate 的库列名是 rate_per_second）
java '-Dfile.encoding=UTF-8' -jar synthetic-data-generator/target/*.jar --spring.main.web-application-type=none `
  --generator.cli=plan-append --plan-id=m1-4-d12-journal-v11 --mode=MALL_API --target-id=118 `
  --scenario=normal --seed=20260912 --start=2026-09-12T10:00:00 --end=2026-09-12T11:00:00 --event-count=120
# 2) 带凭据启动 8092（值只进进程环境）
$env:GENERATOR_TARGET_TOKEN = <32 字符商城 token>; Start-Process java -ArgumentList '-jar', <fat jar> -WorkingDirectory synthetic-data-generator
curl.exe -s -X POST http://127.0.0.1:8092/api/v1/targets/118/test
# 3) 发起运行（必须断言 HTTP 码）
curl.exe -s -w '|HTTP=%{http_code}' -X POST http://127.0.0.1:8092/api/v1/generation-runs -H 'Content-Type: application/json' -d '{"plan_id":"m1-4-d12-journal-v11","version":1}'
# 4) 只读复算（可重复执行）
pwsh -File docs/acceptance/m1-4-d12-journal-20260912/scripts/d12-verify.ps1 -RunId <run_id>
```

## 12. 对既有记录的影响

- **D12 关闭**：看板 L227 的 D12 由「未修」转为**已修 + 真机正向取证**（run `m1-4-d12-journal-v11-v1-20260912-104653-f1bc`）。看板原文不改写，另加关闭批注。
- **U11 关闭（本轮口径）**：真机 HTTP 请求量不再是"流水行数当请求数"，而是 `real_http` 行数 + 退款行数；本轮实测 **115**（U1/U3 轮那次的历史流水为 1.0，未追溯改写，见 §9.4）。
- **新增事实 F-19**：`GeneratorBoundarySourcePolicyTest` 的路径守卫依赖「surefire 工作目录 = 模块根」，用 `-DforkCount=0`（进程内执行）会让 `user.dir` 变成调用方 cwd ⇒ 守卫集体响亮失败。**判据：全模块回归必须用基线配方，不要加 `-DforkCount=0`。**
- **新增事实 F-20**：应用在跑时 `mvn package` ⇒ `spring-boot:repackage` 因 jar 被占用而失败，**但瘦 jar 已覆盖同一路径**，磁盘上留下不可执行 jar（错误信息只说 rename 失败，不提示"现场已被破坏"）。**判据：打包前先停进程，打完后用 `jar tf | BOOT-INF` 自证是 fat jar。**
- **新增决策 D-046**：流水"是否发过请求"的**唯一权威**是 `http_method`/`route` 的存在性，`real_http` 是**派生视图**（不落第二个存储权威），并加构造器不变量禁止矛盾组合；流水 `schema_version` 升 1.1，旧 1.0 流水原地保留、不追溯改写。
- M1-4 行仍为 `DONE_LIMITED`（理由见 §9.6）。
