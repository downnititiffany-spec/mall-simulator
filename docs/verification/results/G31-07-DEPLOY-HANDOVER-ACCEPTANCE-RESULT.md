# G31-07 部署交接与本版验收 — 结果报告（推荐裁定：**限定通过候选**，待总控签收）

- **批次**：G31-07（V3.1 指导书批次序列末批）
- **日期**：2026-09-25
- **计划**：`docs/verification/batches/BATCH-G31-07-HANDOVER-ACCEPTANCE-PLAN.md`（推导链 5 条已在该计划 §0 登记：V3.1 原文不可恢复，验收项从冻结 V3.0 阶段 8 L185-191 推导）
- **裁决边界（必须先读）**：按 V3.0 L212 + L243 治理规则，**代码 Agent 不可自行宣布完整验收**。本报告只提交验收证据与推荐裁定；「通过 / 限定通过 / 未通过」的最终判断权与签收权归总控。

## 0. 「一次可复现产品验收」的运行形态（非重复性声明）

本次验收不重跑任何整链。按计划 §1 的运行形态由三部分构成，全部证据落盘可复核：

1. **当日实链**：G31-05 WSL 单节点 HDFS 整链（RunId `g3105_20260925_121316`，05.1~05.6 全 PASS）+ G31-04 故障恢复演练（`g3104b_20260925_104346`，04.1~04.4 全 PASS）——同日（证据时钟 2026-09-25）真实执行的完整采集→数仓→Spark→发布链。
2. **终验腿（本批 07.2）**：对常驻平台栈（g3103 runId `g3103_20260920_052106`）的只读核验，9 腿全绿（§2）。
3. **界面证据引用**：BATCH-W 浏览器 E2E + G31-02 第二来源向导 + G31-03 C6 补验（不重跑，引用原证据）。

## 1. V3.1 指导书批次链收口状态

| 批次 | 内容 | 状态 | 结果文档 / 证据 |
|---|---|---|---|
| G31-00 / BATCH-W | 浏览器 E2E | ✅ PASS（W-1~W-9 全满足） | `docs/verification/results/BATCH-W-STAGE7-BROWSER-E2E-RESULT.md` |
| G31-01 | 测试隔离 | ✅ PASS | `docs/verification/results/G31-01-TEST-ISOLATION-RESULT.md` |
| G31-02 | 第二来源异构夹具 | ✅ PASS | `docs/verification/results/G31-02-SECOND-SOURCE-RESULT.md` |
| G31-03 | 业务正向链 E3 | ✅ PASS（含 2026-09-23 追加验证关闭 BATCH-W C6 缓冲项） | `docs/verification/results/G31-03-E3-LOCAL-CHAIN-20260923-RESULT.md`；追加证据 `target/v25-it/g3103-fresh-20260923-a1/attempt-v31-20260923-1717/` |
| G31-04 | 故障恢复演练 | ✅ PASS（04.1~04.4）＋缺陷 F-G4-1 登记走变更请求 | `docs/verification/results/G31-04-FAULT-RECOVERY-RESULT.md` |
| G31-05 | WSL 单节点 HDFS 整链 | ✅ PASS（05.1~05.6）＋缺陷 D-041 / 运行约束 D-042 登记 | `docs/verification/results/G31-05-WSL-SINGLE-NODE-CHAIN-RESULT.md` |
| G31-06 | 真实 AI provider | ⛔ **BLOCKED**（D-039：无受控凭据不得伪造；stub 本地替身已交付） | DECISION_LOG D-039；本报告 §6 未交付项正式列明 |
| G31-07 | 部署交接与本版验收 | ✅ 本批（07.1/07.2/07.3/07.4/07.5 全部完成） | 本文 + `docs/handover/deployment-freeze-20260925.md` |

## 2. 07.2 终验腿结果（9/9 腿 PASS）

驱动 `target/v25-it/g3107_20260925_142915/scripts/g3107-acceptance.ps1`（只读；除登录会话与 AI 探针两条设计内记录外零写库、零重启、零 3306 接触）。执行时间 2026-09-25 14:36，`drill-state.json` outcome=PASS：

| # | 腿 | 结果 | 证据文件 |
|---|---|---|---|
| 1 | 健康检查 | OK（`data.ok=true`，metric_read 通路） | `evidence/health.json` |
| 2 | 登录冒烟（admin + analyst 演示账号） | OK | `evidence/login-smoke.json` |
| 3 | 运行时 profile 留证 | 记录 | `evidence/runtime-profiles.json` |
| 4 | pipeline runs 留证 | 记录 | `evidence/pipeline-runs.json` |
| 5 | 快照 ACTIVE 唯一性 | `S20260921_20` 唯一 ACTIVE | `evidence/snapshots.json` |
| 6 | overview 14 指标指纹 | **14/14 == item4 oracle**（容差 0.0005），无多余指标 | `evidence/overview.json` + `evidence/metric-compare.json` |
| 7 | F2b 日志守卫 | platform.log 尾 200KB **零 `:3306`**；3307 两库 JDBC + landing root 均有证据 | `evidence/f2b-log-guard.json` |
| 8 | stub LLM 探针 | providerUsed=**stub-local**、EXECUTED、建议数 2 | `evidence/ai-probe.json` |
| 9 | 进程身份链 | identity JSON → java(50980) ← wrapper(41292)，marker=g3103，jarSha256=f6d0c3a4… | `evidence/process-identity.json` |

overview 指纹逐项（item4 权威 oracle，`target/v25-it/item4/90-03-page-overview-response.json`）：pv=31, uv=8, paid_order_cnt=13, fav_cnt=2, cart_add_cnt=1, buy_rate=0.875, cart_rate=0.125, refund_rate=0.1538, full_refund_rate=0.1538, dau=8, avg_order_value=92.31, repeat_rate=0.5714, net_sale=1000.0, gmv=1200.0。

**驱动迭代诚实登记**：07.2 驱动共 3 轮执行——run1 FAIL[5]（快照载荷 `data` 为直接数组而驱动按命名列表查找）、run2 FAIL[5]（PowerShell 递归 `,$found` 包装 + 管道成员枚举导致 8 条全过）、run3 9/9 PASS。三轮均为**验证工具层缺陷**，产品代码零改动；全部执行留痕（drill-state + evidence）未清理。

## 3. V3.0 阶段 7 五任务对账（L179-183）

| # | 任务原文（摘） | 对账结论 | 限定条件 |
|---|---|---|---|
| ① | 独立生成器调用商城 HTTP，下单/支付/退款形成 Outbox 和日志 | ✅ BATCH-S/S-R1：producer→mall 真实 HTTP、Outbox+滚动唯一性 | — |
| ② | 小规模串通采集→数仓→Spark→发布→页面→AI/决策；对账 | ✅ BATCH-T-R3 + G31-03 E3 业务正向链：摄取/隔离对账、指标量纲对账、页面+AI/决策在链上 | F-G4-1：同业务日多次运行指标回归已登记未修（变更请求待总控） |
| ③ | 第二来源异构夹具复用同一平台，同 ID 不串源；真实外部商城按授权样例另验 | ✅ G31-02 第二来源（异构夹具、字段适配、不串源） | 真实外部商城授权样例**未验**（无授权环境）——限定 |
| ④ | 检验源停机、重放、阶段失败、发布中断后旧快照仍读得到 | ✅ G31-04：kill Spark 子进程/平台进程/mysqld 三类故障，旧 ACTIVE 快照跨故障逐位不变，全部恢复路径走通 | F-G4-1 边界：同业务日重放场景不得作验收口径（见该缺陷边界） |
| ⑤ | HDFS/Hive/集群验证按环境目标单独登记，不用 local[1] 测试替代 | ✅ G31-05 WSL 单节点 HDFS 档登记（真实 Flume→HDFS→摄取→Spark HDFS warehouse→发布导出，非 local[1] 替代） | 集群档 REMOTE_CLUSTER 未验——限定；YARN/多节点/共享 HMS 未验 |

## 4. L189 清册（正/负样本、真实链、界面、同环境实验）

- **正样本**：golden-20260901-positive.jsonl（50 行）；mock-mall 串通采集（G31-03）；stage7q1 producer 真链（BATCH-T 系）。
- **负样本**：摄取隔离/quarantine（BATCH-R/V 系）；质量门 BLOCKING 语义（ADS_STAGING_PRESENT v2 迁移与规则版本化，未提交工作树改动）；故障注入负路径（G31-04 三类）。
- **真实链**：真实 Flume 1.11.0 agent→HDFS raw→平台 HDFS 摄取（50/0，manifest READY）→WSL Spark 全链 10/10→发布导出 8 表（G31-05）；平台 ODS_TO_ADS 8 阶段真链→ACTIVE 切换→页面（G31-03/04/05.5）。
- **界面**：BATCH-W 浏览器 E2E（W-1~W-9）；G31-02 第二来源向导；G31-03 C6 补验（登录回车、AI 回车、chip 单次 POST，真实 Chromium）。
- **同环境实验**：同 jar 同夹具同业务日的 ADS 确定性对照（G31-05 05.4：7/8 表字节级全同 + 1 表排序后全同）。
- **性能声明**：**不报告任何性能提升**——无可复核性能基线，符合 L189 后半句约束。

## 5. 缺陷与变更请求清单（全部待总控裁决，本批零代码修改）

| 编号 | 内容 | 状态 | 证据 |
|---|---|---|---|
| F-G4-1 / D-040 | ODS 动态分区 INSERT OVERWRITE 默认 STATIC 模式 → 同业务日增量批次清空历史分区，链式传导发布回归 | 变更请求待总控（候选修复 3 案） | `target/v25-it/g3104b_20260925_104346/F-G4-1-REGRESSION-FINDING.md` |
| D-041 | `file_checkpoint.file_identity` VARCHAR(64) 装不下 HDFS 档身份（≈94 字符）→ HDFS 档断点续读失效（交付不受影响，at-least-once 如实登记） | 变更请求待总控（追加式迁移候选） | `target/v25-it/g3105_20260925_121316/logs/platform-start1.log` L106-113 |
| D-042 | WSL 档两条运行约束（mxp exportDir 必须 `file:///`；Derby create=true 目录不得预建） | 运维绑定约束（非缺陷，交接冻结文档 §6 已收录） | G31-05 结果文档 §4 |
| D-039 | 真实 LLM 无受控凭据 → G31-06 BLOCKED；stub 本地替身交付（零外呼零费用） | BLOCKED 登记，不伪造 | DECISION_LOG D-039 |
| 验证工具层缺陷 | G31-04 attempt-1 驱动缺陷中止；G31-05 attempt-1 XBM0J、fingerprint NaN 驱动读层缺陷；G31-07 驱动 2 轮迭代 | 均诚实归因、证据保留，产品代码零改动 | 各批次结果文档 |

工作树未提交改动（质量规则 v2 + V29 迁移 + 配套测试）按交接指令**原样保留**，未 commit 未 push；其测试基线调整需求见滚动清单 #0。

## 6. 未交付项（进入后续计划，不得表述为本版能力）

1. **REMOTE_CLUSTER 集群档**（滚动清单 #5）。
2. **真实 LLM provider 接入**（G31-06 BLOCKED，D-039；恢复条件=provider/凭据引用/数据外发许可/费用上限四要素齐备）。
3. YARN / 多节点 / 共享 Hive Metastore（V3.0 设计 §5.1 明确不要求，本版未验证）。
4. 真实外部商城授权样例验证（L181 后半句）。
5. **论文/答辩材料与演示流程**：按 L190 在结果冻结后统一编写（post-freeze 工作，不在本版交付物内）。
6. **Doris 对比实验**：按 L191 不伪装成本版已实现能力，进入后续计划。

## 7. 推荐裁定与限定条件

**推荐裁定：限定通过候选。**

- **通过面**：阶段 7 五任务在 LOCAL 真实链路版 + WSL 单节点 HDFS 档上全部有可复核证据；07.2 终验 9/9 全绿；部署交接冻结文档齐备；V3.1 批次链除 G31-06 外全部收口。
- **限定面（裁定「限定」而非「通过」的依据）**：① F-G4-1 与 D-041 两项平台缺陷未修复（变更请求待裁决）；② 真实 LLM 不可用（G31-06 BLOCKED，AI 腿仅 stub 替身）；③ REMOTE_CLUSTER / YARN / 多节点 / 共享 HMS / 真实外部商城未验；④ 论文答辩材料 post-freeze 未写。
- **签收**：按 L212 + L243，本报告为**候选**状态；总控签收（通过/退回/追加验证项）后本版结果方冻结。

## 8. 证据索引

- 交接冻结：`docs/handover/deployment-freeze-20260925.md`
- 终验证据根：`target/v25-it/g3107_20260925_142915/`（scripts/ + evidence/10 份 JSON + drill-state.json）
- 批次计划：`docs/verification/batches/BATCH-G31-07-HANDOVER-ACCEPTANCE-PLAN.md`
- 各批次结果：§1 表所列 `docs/verification/results/` 文件
- 决策登记：`docs/decisions/DECISION_LOG.md` D-039 / D-040 / D-041 / D-042 / D-043（本批登记）
- 滚动清单对账：`docs/verification/STAGE7-REMAINING-SCOPE-20260919.md`（07.4 更新）

## 9. 总控裁定记录与登记更正（2026-09-25 追加，D-044）

总控对本报告作出**分对象裁定**：

1. **证据包与部署交接：签收完成**。§2 的 9/9 终验有记录支持；其检查的是已运行平台的状态，并非重新执行一次完整数据链。
2. **本版产品验收：暂不签「限定通过」**。F-G4-1（同业务日再次增量运行可能清空历史 ODS 数据，D-040）与 D-041（HDFS 文件身份写不进 checkpoint，重试可能重复摄取）直接影响持续分析的正确性与幂等性，应先修复并做小规模针对性回归，再重跑受影响链路和本终验（合并重跑，不逐修复全量重跑）。§7 的「限定通过候选」推荐就此失效，产品验收状态=**未通过（待修复）**。
3. **真实 LLM 与远程集群继续明确标为未验收范围**：stub 能力不写成真实模型通过；WSL 单节点通过不写成远程集群通过。

**修复路线（两个短批次）**：G31-08 同日增量写入修复（判据：**原有数据保留＋新增数据计入＋重跑不重复**）→ G31-09 checkpoint 列宽与索引约束修复（判据：**同一 HDFS 文件重试不重读**）。硬约束：仅开启动态分区覆盖不足以保证同一分区内旧记录不被覆盖，修复方案必须覆盖同分区内旧记录保留。

**登记更正**：本文档及冻结文档所称「16 个未提交文件」为本会话初始快照的过时计数（该组质量规则 v2 改动已随 `8b4e4da`/`2a11b60`/`b25b47f` 等提交收编）。冻结时点工作树实际未提交改动：**34 个已跟踪文件**（30 个产品/夹具/脚本 + 4 个登记文档，+1412/−172）+ 一批新增未跟踪文件。**归属清单（提交前核对基准）**：

| 归属 | 文件 | 处置 |
|---|---|---|
| 在途产品改动（接手前存在的改动线，G31-04~07 零触碰，保留） | connection-ingestion 摄取/landing 存储线：13 M（IngestionService、LocalFileIngestor、LandingInputScanner、LandingLayout、Hdfs/Local LandingStorage、LocalProcessSparkSubmitter 等）+ 9 新文件（LandingStorageResolver、HdfsFlumeRawIngestionIT、HdfsIngestionServiceIT、IngestionStoragePipelineTest、LandingStorageScannerTest、HdfsLandingStorageIT、HdfsLandingStorageScannerTest、LandingStorageContractTest、SeekableLandingInputTest）；metric-analysis 发布/导出线：6 M + 1 新（MetricExportPath）；warehouse-pipeline：2 M；ai-decision：2 M（ExplanationService 及其测试） | 原样保留；提交时按改动线分组单独 commit，逐文件 diff 复核 |
| G31-03 C6/浏览器腿配套（更早会话产物，未提交） | web/src/utils/chartState.js、web/src/views/（AiAssistant/Behavior/Overview/Products/Rfm）、web/tests/chartState.test.js、web/tests/aiButtonStyle.test.js；fixtures/source-a-e3（MANIFEST-SHA256.txt、ORACLE.md） | 原样保留；提交时归 G31-03 线，逐文件 diff 复核 |
| G31-04~07 登记与验证产物（本会话） | docs/PROJECT_STATUS.md、docs/decisions/DECISION_LOG.md、docs/verification/CURRENT_BATCH.md、docs/verification/STAGE7-REMAINING-SCOPE-20260919.md（M）；docs/verification/results/G31-04-…/G31-05-…/G31-07-…、docs/verification/batches/BATCH-G31-04/05/07-…、docs/handover/deployment-freeze-20260925.md、6 份 `*.bak-20260925-*`（??） | 随 G31-08/09 收口按登记惯例提交 |
| 非仓库内容 | `.zcode/`（计划/会话状态）、根 PROJECT_STATUS.md（非权威副本，V3.0 交接时已知） | 不提交 |
