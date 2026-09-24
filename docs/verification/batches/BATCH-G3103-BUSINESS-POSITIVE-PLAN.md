# BATCH-G3103-BUSINESS-POSITIVE 批次计划（指导书 V3.1 §7 G31-03）

> **2026-09-23 开发提速增量（离线并行切片）**：决策 API 安全负向控制器新增测试 `DecisionControllerApiSecurityTest`，3/3 PASS；覆盖分析员审批/拒绝权限、伪造身份字段、非法状态映射及失败审计调用。该切片使用真实 Controller/AuthInterceptor、mock 下游服务，不覆盖 DB 审计持久化、跨源快照写侧守卫或完整 03.5 真链，因此 03.5 仍未完成。前端 sourceId/AI 快照身份窄测 14/14 PASS，Web build 675 modules 成功；Spark 受影响套件 49/49 PASS（JDK8，`local[1]` + in-memory catalog），不等于全量 Spark 档或 Hive/HDFS 验收。8091/5176/3307 当前停止；3306 不接触。开发提速采用并行离线审查/单测，所有共享隔离库的写任务及依赖快照的顺序链仍串行；G31-03 总体状态不变。

> **2026-09-23 后续离线切片**：CSV 元信息新增“业务来源 ID”，只接收正安全整数；missing/非法 sourceId 标为缺失，不推断。真实 `useAnalysis` composable→CSV 的五点回归验证 sourceId、snapshotId、definitionVersion、后端 filters 日期窗口与 GMV 指标值一致；`analysisFivePointContext/sourceIdContext/context/csv` 定向测试 40/40 PASS。决策服务新增 D1 重评测试：首次窗口无数据时 INSUFFICIENT_DATA，之后同一任务补齐完整三日窗口重评为 EFFECTIVE；决策服务/跨源/状态机测试共 42/42 PASS。以上均为离线代码层证据；不替代真实 API、数据库快照、审计持久化或浏览器 E2E，因此 03.3 及 03.6 仍不能标为完整完成。

> **2026-09-23 追加验证（来源血缘小样本 + C6）**：最新隔离域 `g3103x_20260923_1630_a1_*` 上实际应用 V30/V31/V12；追加 2 条 JSONL 正样本后 ingestion batch 3 成功，pipeline run 3 的 8 阶段全 SUCCESS，ACTIVE 快照变为 `S20260924_3`。run 列表 API、快照读取及 overview/sales API 返回 `sourceId=1`、`snapshotId=S20260924_3`、`definitionVersion=v2`；指标 API 返回 14 项。`/pipeline-runs/{id}` 的详情 DTO 不提供 sourceId，来源核对使用 run 列表接口，不将 DTO 缺字段误判为 DB 缺值。后端窄套件 93/93、Web 371/371 PASS。真实 Chromium C6 + 真 API：登录 Enter、AI 输入 Enter、建议 chip 各自按预期提交；AI Enter 和 chip 各恰发 1 次 `/ai/queries`，响应均 HTTP 200 / `EXECUTED`；分析员读取 runtime profile 得 403，页面异常 0。证据在 ignored 的 `target/v25-it/g3103-fresh-20260923-a1/attempt-v31-20260923-1717/`，本次尚未归档到版本控制。**边界**：AI 无真实模型凭据，建议数为 0，因此 AI→建议→DRAFT 正向链仍未通过；本轮 SourceId 验证为服务/API/快照交叉验证，未完成新快照的 DOM 字段展示复验。当前 8091、5176、3307 已停止；未找到该 RunId 专属 MySQL 启动入口，且不安全地复用历史脚本。故 03.3、03.4–03.7 保持 PARTIAL/TODO；C6 只关闭已验证的交互请求次数子项，不等于整批通过。没有 Hive/HDFS/多节点验收。

- 指导书条目：§7 G31-03「从『页面不报错』到『能完成业务』」（03.1~03.7）
- 批次 ID：`BATCH-G3103-BUSINESS-POSITIVE`
- 数据域：复用 stage7q1_20260918_152245 四库 3307 域（与 G31-02 同模式；RunId `g3103_20260920_052106` 仅为证据/落盘标签）
- 证据根：`target/v25-it/g3103_20260920_052106/`
- 被测代码：feature/v3-development HEAD（写计划时 `git rev-parse HEAD` 快照见 start-result.json）

> **2026-09-23 执行前勘误（保留原证据，不据此重跑）**：本计划最初的样本细节早于 D-035/后续夹具修订。当前唯一数值依据是 `fixtures/source-a-e3/MANIFEST-SHA256.txt`、`ORACLE.md` 与 `oracle.py --machine`：E3=99 行，E4=71 行；E4 业务日从夹具读取（当前为 2026-09-24），不得继续硬编码为 2026-09-21；并列热度为 14.370443。原 2026-09-20 attempt 的 E3/E4/决策证据保持历史原样。本批旧数据域 `stage7q1_20260918_152245` 当前不能直接启动：现 WSL 未运行 3307 且 MySQL 数据目录缺失；真实浏览器验收前必须建立或恢复经验证的隔离环境，并生成新的 attempt 证据。

> **2026-09-23 attempt-2 增量（以实测为准）**：新建 run-scoped WSL MySQL 8 隔离数据域 `g3103x_20260923_1630_a1_*`（仅 `127.0.0.1:3307`），平台 `8091` + Windows Spark LOCAL `local[1]`；没有复用旧 stage7q1。E3 99 行与 E4 71 行均 accepted，quarantine/errors 均为 0。E3 run 1 第一次因 Spark 子进程未继承 `HADOOP_HOME` 在 INIT_SCHEMA 失败；设置 Windows Hadoop `D:\soft\hadoop\hadoop-3.3.4` 后，run retry 跳过已成功 WAIT_LANDING 并从失败阶段恢复，attempt 2 8 阶段全 SUCCESS；E4 run 2 8 阶段全 SUCCESS。快照 A=`S20260918_1`、B=`S20260924_2`，B ACTIVE / A ARCHIVED 可读。两个快照各 14 个指标，独立 oracle/API/只读 MySQL 按 code、period、value 全项匹配（14/14 × 2）；E3 漏斗、商品排行并列与 RFM 买家数也匹配。真实 Chromium 分析员五页（overview/sales/behavior/products/rfm）均读真 API HTTP 200、页面异常 0；销售分类/地区维度按 `UNKNOWN_DIMENSION_TABLE` 如实为空。AI 页在无 LLM 凭据环境下实测 `rule-based` Text-to-SQL `EXECUTED` + `template` 解释 + 证据 ID，但建议数为 0，故未伪造转决策草稿。

**执行进度（2026-09-23，attempt-2 + 竞态单测/浏览器节流增量）**：03.1 的 E3 漏斗/商品排行与两期指标 API/DB/oracle 对账、03.2 的两期销售/RFM 核心数值及五个主要页面已完成本机小样本证据，属**部分完成**（销售类目/地区维度本期按预期不发布）。03.3 的 composable 异步行为单测 2/2 PASS：旧请求最后返回不得覆盖新快照/数据；`cancel()` 后迟到响应不得写状态。真实 Chromium 销售页在 CDP 1.8 秒网络延迟下，刷新期间日期输入/刷新按钮锁定，附加 DOM click 未触发第二个 sales API 请求，当前快照仍显示、页面异常 0；结果为 `target/v25-it/g3103-fresh-20260923-a1/browser-sales-loading-guard-smoke.json`。前端全量 `npm test` 365/365 PASS，`npm run build` 成功（675 modules）。以上覆盖请求优先级与 UI 防重入，但不等于 sourceId/snapshotId/definitionVersion/window/value 五点逐项一致性对账，故 03.3 仍为**部分完成**。03.4–03.6 AI 建议转决策的完整正向/负向与评价、03.7 页面剩余角色/动作验收、C6 补验仍未完成。决策正向需要真实 LLM 输出可核验建议，或经总控批准的确定性 LLM 测试适配器；本 attempt 没有配置真实模型，不手工伪造“AI 建议”。LOCAL Windows Spark + 本地嵌入式 metastore 不是 Hive/HDFS/多节点验收；Pipeline SUCCESS 不代表整批 PASS。

## 1. 数据集分工（§7.1）

| 层 | 内容 | 本批落点 |
|---|---|---|
| E0 空态 | 0 条；验空态/无 ACTIVE/未知快照，不伪造数据 | 复用 BATCH-W 空态证据 + 新增未知 snapshotId 探针（错误响应，无假数据） |
| E1 边界 | 已由 G31-01/G31-02 单测与夹具覆盖 | 不重复 |
| E2 业务链 | 来源→计算→发布→页面，可复用已冻结 1011 事件 | stage7q1 存量证据（S20260918_17） |
| E3 正样本（A 腿） | 20~100 条，映射/算法/边界 + 独立 oracle | `fixtures/source-a-e3/e3-events.jsonl`（当前 99 行，跨 09-17/09-18），businessTime=2026-09-18 → 快照 A |
| E4 跨日样本（B 腿） | 数十条跨基期/观察期样本，供决策评估 | `fixtures/source-a-e3/e4-events.jsonl`（当前 71 行）；业务日由夹具生成参数决定，当前文件为 2026-09-24 → 快照 B |

约束遵守：合成行为只来自手写夹具（canonical-event.v1 生成器文件夹具，等价 stage7q1 黄金夹具的构造方式）；不声称 mock-mall 有新端点；库存/成本不编造（不触发库存事件）。

## 2. E3 数值设计（独立 oracle 的输入，完整推导见 fixtures/source-a-e3/ORACLE.md）

- 15 用户 g3u01..g3u15（09-17 注册 g3u01..g3u08）、5 商品 g3p01..g3p05（09-17 product_created；09-18 product_updated 保 dim 名）。
- 09-17（仅入 ODS/DWD 证明跨日装载）：8 行为 + 1 支付单 g3o0001（g3u01, g3p01, 100）+ 1 取消单。
- 09-18：行为 45（view 30 → pv=30/uv=10/dau=10；favorite 6；cart_add 8；cart_remove 1）；交易 12 支付单（gmv=1010，aov 原值 1010/12，发布精度 84.17）+ 2 取消单 + 1 单全额退款 80（net_sale=930，refund_rate=full_refund_rate=1/12=0.0833）。完整行数、distinct 人数及每项结果以当前 oracle 输出为准。
- 漏斗（03.1）：view_users=10 / intent_users=6 / cart_users=6 / order_users=8 / pay_users=6；intent=0.6、order=8/6=1.3333、pay=0.75、overall_buy=0.6、cart=0.6；「分页稳定」用 hot_product API 分页两次结果一致验证。
- 排行并列（03.1）：heat(g3p02)=heat(g3p03)=14.370443（pv=6,fav=2,cart=2,buy=3 全同）→ 并列按 (buy DESC, product_id ASC) 稳定列位；至少 3 商品（5 个）。
- RFM（03.2）：6 支付用户原值 r_days=0，f=4/3/2/1/1/1，m=400/270/160/70/60/50（全异 → m_ntile 确定性）；r/f/value_group 做「与排序+并列组一致」一致性断言（Spark NTILE 并列分组次序非确定，不假钉）；repeat_rate=2/6=0.3333，period 2026-09-18..2026-09-18（窗口声明随行）。
- F-35 边界：退款归属订单业务日（同日退款），跨日重结不实现、不测试。

## 3. E4 数值设计（B 腿）

- 业务日从 E4 夹具读取（当前文件为 2026-09-24；每次生成后以 oracle 输出为准）；7 注册用户 + 8 行为用户：31 view（pv=31/uv=8）+ 2 favorite + 1 cart_add。
- 13 支付单（7 买家）金额 1200（g3p01×5@100 + g3p02×4@88 + g3p03×4@87），2 单全额退款各 100 → net_sale=1000，aov=1200/13=92.3077，refund_rate=2/13=0.1538。

## 4. 任务→证据映射

| 任务 | 手段 | 证据 |
|---|---|---|
| 03.1 正样本 | 快照 A 后 API + 浏览器：排行≥3 含并列、分页稳定、漏斗可对账（人数与率双查） | `20-*.json`（A cells/漏斗/排行）+ 截图 |
| 03.2 销售/RFM 窗口 | trade/RFM API 原值核对（窗口、退款归属、R/F/M 原值、distinct 人数）；不足场景=无数据日不可计算 | `22-*.json` |
| 03.3 上下文固定 | 五点一致（sourceId/snapshotId/definitionVersion/window/值）+ 慢 A 响应不得覆盖已选 B（Playwright route 延迟拦截；兜底=现有 vitest + 代码引用） | `30-*.json`/截图 |
| 03.4 决策正向 | 4 决策全链 DRAFT→PENDING_REVIEW→APPROVED→IN_PROGRESS→COMPLETED→评价，真实身份（analyst 建、admin 审）真实 API | `40-*.json` |
| 03.5 决策负向 | analyst 越权审批→403+审计 FAILED；伪造 createdBy 忽略；跳状态/重复提交→IllegalDecisionStateException+审计 FAILED；跨源证据（snapshotId=fixture-shop-b 快照 S20260918_15）→ 拒绝（D-034 新守卫）| `50-*.json` |
| 03.6 评价四类 | D1=INSUFFICIENT_DATA（评于 B 发布前，actual==baseline）→补数据（E4）后重评 EFFECTIVE；D2=PARTIAL（pv 30→31, 0.0333）；D3=INEFFECTIVE（refund_rate DOWN，−0.8463）；D4=EFFECTIVE（gmv 1010→1200, 0.1881） | `60-*.json` |
| 03.7 员工验收 | 真实 Chromium：analyst 登录→Overview 筛选→Products→AI 解释→转决策草稿→Decisions 查进度；管理页对 analyst 服务端 403 | `70-*.png` |
| C6 补验 | 浏览器：Login/AiAssistant Enter 提交、chip 单次 POST 网络捕获、DOM click 通道 | `80-*.json` |

决策锚定（03.3 固定语义）：D1 avg_order_value UP target 90；D2 pv UP；D3 refund_rate DOWN；D4 gmv UP。基线全部=A（评估时 A ACTIVE）；B 发布后 D2/D3/D4 评估，D1 先评（INSUFFICIENT）再重评（EFFECTIVE，验证 INSUFFICIENT_DATA 可再 EVALUATING，不冻结）。

## 5. 边界（本批显式声明）

- 不新增 ADS 表（现有 8 张够用）；不改已冻结 DWS/ADS 口径；F-35 同日退款；跨业务日重结不做。
- 3306 永久零接触；口令通道 = V25_IT_* 进程内（零落盘）；platform secret 不入日志。
- push 授权已用尽：仅本地提交，不 push。
- fixture-shop-b（源 2）状态不动：跨源证据用其已发布快照 S20260918_15，不新增源 2 数据。
- 平台生命周期：g3102 平台先按 pidfile 停止 → g3103-start（F2a/F2b 守卫）→ 批内保持运行。

## 6. 2026-09-23 开发提速 / 采集断点修正增量

- 工作目录基线继续使用 `D:\Develop_code\GraduationProject-wt\v3-dev`（`feature/v3-development`，HEAD `2a11b60a24332e02fc3b4927dbef5e524a3b5b15`）；不从 `D:\Develop_code\GraduationProject` 备份目录重做。
- 并行范围：已把 WSL/页面相关链路前置复核、采集故障根因复核、前端单测分别并行；Maven reactor 共用各模块 `target`，因此 Java 侧只开一个 reactor；Spark 多套件共用 `TestSuite.txt`，真实隔离 MySQL/ACTIVE 快照链按 03.4→03.7 串行。没有同时运行两个 Spark/Maven writer 或共享数据库任务。
- **新缺陷**：manifest 写失败时旧代码的采集文件 checkpoint 已提前提交，`RunResult` 仍可能是 SUCCESS 且 `manifestPath=null`；pipeline 只消费 READY manifest，因此该批事件会滞留在 accepted 目录且下轮不能自然重读。
- **最小修正**：批次内文件先延迟提交 checkpoint；完整 manifest 以同目录临时文件写完后 rename 为 READY；manifest 发布失败记 FAILED、无 manifest、断点保持旧值；后续新批次能重读并发布。成功发布后再写 checkpoint；若此时 MySQL 写断点失败，READY 数据已交付，允许至少一次重投并由 DWD `event_id` 去重。本轮没有扩展公开 API 或加迁移。
- **fresh 测试**：首次窄测找到测试 mock 仍 stub 旧入口的适配问题，按真实新编排入口同步测试 mock 后复跑；最终 `connection-ingestion` 7 个目标测试类 **30/30 PASS**，包括 manifest 故障→不推进→修复后重试成功。随后单一 Maven reactor 汇总当前已改 Java 模块用例 **29 类 / 255/255 PASS**；`web/npm test` 独立运行 **373/373 PASS**。前一轮临时失败已由后续 fresh 成功轮覆盖，不作为当前失败信号。
- **严格边界**：本轮仅为 L0/L1 代码与测试修正，未连接 MySQL、未重启 8091/5176/3307、未触碰 3306，也未跑 Hive/HDFS/Spark 集群。原子 manifest rename 与 MySQL checkpoint 之间仍存在进程崩溃窗口，严格跨资源 exactly-once / crash recovery 未实现；需要时另行设计 outbox/恢复工作流。本轮不改变 03.1–03.3 partial、03.4–03.7 todo 的总体状态，也不等价于真实页面/快照验收。
