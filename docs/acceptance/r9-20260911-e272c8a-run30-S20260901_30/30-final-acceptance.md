# R9 最终验收清单（对标《项目完整实施指导书 V2.0》§30）

> 采集口径：**2026-09-11**，代码版本 `e272c8a`，黄金链 run **30**，发布快照 **S20260901_30**（ACTIVE，业务日期 20260901）。
> 目录名中的 commit 指**产生该 run 的代码提交 `e272c8a`**（两个缺陷修复）；其后的证据/文档提交不含代码变更。
> 本目录全部数字来自真实 MySQL / 真实 Hive（Derby metastore）/ 真实 HTTP API / 真实磁盘，无 Mock、无估算。
> 逐项判定规则：**✅ = 当轮真机实测通过**；**⚠️ = 达成但有如实登记的边界**；**❌ = 未达成**（本轮无 ❌）。

## 0. 本轮验收基线（一句话事实）

`POST /api/v1/ingestion/runs` 采集 55 行黄金夹具 → 8 阶段流水线 `WAIT_LANDING→…→PUBLISH_METRIC` 全部 SUCCESS →
Hive 四层有数（ODS 15/16/14/4、DWD 14/7/1、DWS 5 单 2042.00、ADS 概览 7/3/3/5/2042.00/1493.00/408.40）→
MySQL `analytics_metric` 发布唯一 ACTIVE 快照 → 页面/AI 与指标库同源（`16-api-responses.json` 实测 `snapshotId=S20260901_30`）。

| 证据文件 | 用途 |
|---|---|
| `01-pipeline-run.tsv` / `02-pipeline-stage-run.tsv` | run 终态、8 阶段状态、`externalJobId`、证据 JSON 合法性 |
| `03-spark-job-run.tsv` / `04-spark-job-summary.tsv` | 10 条 Spark 作业行（作业码/外部作业 id/输入输出行数/输出分区 JSON） |
| `15-hive-layers.txt` | ODS/DWD/DWS/ADS 四层行数、分区、金额、质量规则、`__staging` 快照隔离 |
| `10`–`14` | 指标库快照表、ACTIVE 指标值、8 张 ADS 宽表行数、概览宽表整行、质量规则结果 |
| `20-reconciliation.tsv` | 黄金标准答案 vs 指标库逐项对账（**不一致项 = 0**） |
| `16-api-responses.json` | 11 个真实 HTTP 响应（指标/漏斗/商品/趋势/RFM/流水线/决策/AI 健康/看板） |
| `17-screenshots/`（平台 13 张 + `mall/` 4 张 = 17 张） | 平台 8 页 + 商城/商品后台/生成器 3 页真机截图（Playwright，PNG 头校验） |
| `21`–`29` | 可靠性/故障注入/续跑实验原始证据（§23.3） |
| `30-final-acceptance.md` | 本文件 |
| `31-metric-publish-it-regression.log` | 指标发布真库集成测试（PV/退款率口径回归，真实 MySQL，1/1 PASS） |
| `32-spark-jobs-tests.log` | Scala 作业单测（修复后：succeeded 46 / failed 0，BUILD SUCCESS） |
| `scripts/`（8 个） | 采集与复现脚本随证据归档（`.verify/` 被 `.gitignore` 排除，故一并入库） |
| `README.md` | 逐文件索引（本目录 **62 个文件**：37 顶层 + 13 平台截图 + 4 商城截图 + 8 脚本，含字节数） |

---

## 1. 架构

| # | 验收项 | 判定 | 真实证据 | 说明 |
|---|---|---|---|---|
| A1 | 分析平台与模拟商城是不同进程、数据库、前端和构建产物 | ✅ | `docs/compatibility-matrix.md` L0 段；`17-screenshots/mall/02-mall.png`（商城 8090，进程内页头可见）；`16-api-responses.json`（平台 8091） | 平台 8091（`platform-app`，Spring Boot 单进程）、商城 8090（`mall-simulator` 独立进程）；库分离 `analytics_meta`/`analytics_metric` vs `mall_simulator`；前端两份构建产物（`analytics-server/platform-app/src/main/resources/static` 与商城页面），分别 `npm build` |
| A2 | 平台无需模拟商城即可运行黄金文件分析 | ✅ | `01-pipeline-run.tsv`（run 30 `source_data_version=r9-prunefix-*`）、`15-hive-layers.txt` | run 30 全链输入是**磁盘上的 55 行黄金 JSONL**（`landing/events/*.jsonl`），商城进程未参与；脚本 `.verify/r9-prune-fix-verify.ps1` 仅调用平台 HTTP |
| A3 | 平台不存在对商城业务库或生成器代码的依赖 | ✅ | `analytics-server/**/pom.xml` 无任何 `mall` 依赖（仅 2 处注释提及迁移来源）；平台源码 `com.graduation.mall` 引用数 = **0** | 代码级扫描实测；平台数据源只有 `analytics_meta`（主）、`analytics_metric`（写/只读）三源 |

## 2. 数据链

| # | 验收项 | 判定 | 真实证据 | 说明 |
|---|---|---|---|---|
| B1 | 真实来源→Landing→ODS→DWD→DWS→ADS→MySQL→页面完整运行 | ✅ | `01`–`04`、`15-hive-layers.txt`、`10`–`13`、`16-api-responses.json`、`17-screenshots/` | 一条 run 贯穿 8 阶段；`15-hive-layers.txt` 给出四层真实行数，`13-ads-overview-active.tsv` 给出 ACTIVE 宽表整行，`16-api-responses.json` 给出页面同源响应 |
| B2 | 每层有表、分区、行数、runId、jobId、snapshotId 证据 | ✅ | `02-pipeline-stage-run.tsv`（阶段×`external_job_id`）、`03-spark-job-run.tsv`（`output_partitions_json`）、`15-hive-layers.txt`（分区与行数）、`10-metric-snapshot.tsv`（snapshot） | 10 条作业行全部带非空 `externalJobId`（`lp-<ts>-<hex>`）；正式 ADS 分区只有 `dt`，快照维度落在 `__staging`（`snapshot_id,dt`），发布采用**元数据指针切换** |
| B3 | PV、退款率等口径冲突已解决并有回归测试 | ✅ | `20-reconciliation.tsv`（34 行，A 段 11 行中 **10 行 YES、1 行为 `—`**）；`11-metric-value-active.tsv`；`31-metric-publish-it-regression.log` | R7-0 统一口径：`pv` 只计 `view` 行为 = 7（原按行为明细 14 已纠正）、`refund_rate=0.6000`、`full_refund_rate=0.2000`；真库回归 `MetricPublisherMySqlIT`（`-Dmetric.it=true`）**实跑 1/1 PASS、0 skipped**：正常快照发布 8 行 ADS + 10 条指标值，字典版本不符的快照被 `MP_VERIFY_FAILED`/`MP_METRIC_DICT_VERSION` 写库前拦下。**边界**：A 段第 12 行 `full_refund_rate` 因**黄金文件未列该口径**（R7-0 新增）而标 `—`，即该指标只有「指标库 vs ADS 宽表」的 B 段对账（YES），没有人工黄金基线比对 |

## 3. 可靠性

| # | 验收项 | 判定 | 真实证据 | 说明 |
|---|---|---|---|---|
| C1 | 重复运行幂等；失败重试只从失败阶段开始 | ✅ | `21-reliability-experiments.tsv` C1/C2/C3/C5-3 | C1 同键两次 POST → 同一 runId 25；C2 同键并发 6 次 → 唯一 runId 1、DB 1 行；C3 `retry-from-stage` 前 5 阶段与后 3 阶段时间戳间隔 800.1 分钟（未重跑已完成阶段）；C5-3 resume 只新增 1 行 `QUALITY_CHECK`，前 6 阶段时间戳不变 |
| C2 | Spark/应用/发布故障均可恢复 | ⚠️ | `21-reliability-experiments.tsv` C5-1/C5-2/C5-6、C3；R8 登记（`docs/remediation-status.md` R8 段） | 应用故障：杀进程→重启后状态由 DB 复原、`resume` 返回 200 且仅重跑失败阶段（C5-1/2）；Spark 作业故障：`retry-from-stage BUILD_ADS` 真实重跑并 SUCCESS（C3）；发布故障：`PUBLISH_METRIC` 证据截断失效与 `mxp` 导出非递归删除两个缺陷已修复并复验。**边界**：C5 那次失败 run（run 26）因当时存在缺陷 D-R9-1 暂存被误删，resume 后仍 FAILED——属既有损伤不可逆，不是"恢复失败回避"；修复后端到端重跑（run 30）SUCCESS（C5-6） |
| C3 | 质量失败不会污染正式 Hive 或 MySQL ACTIVE | ✅ | `22-qualityfail-run26.json`、`23-qualityfail-isolation.sql`、`21-reliability-experiments.tsv` C4/C4b/C4c/C4d、`29-resume-evidence.tsv` | 篡改夹具（`paid_amount=0.01`）run 26 终态 FAILED/`PIPELINE_QUALITY_FAILED`；ACTIVE 仍为前一快照、快照总数不变、黄金 ACTIVE 行未被改写；隔离核对 SQL 显示正式 ADS 空分区 vs 黄金分区完好 |
| C4 | 本轮新发现并修复的两个真实缺陷（Mock 覆盖不到） | ✅ | `26-prune-fix-verification.tsv`（**10/10 PASS**）、`27-prune-fix-verify.log`、`28-prunefix-run.json` | **D-R9-1** `AdsPublishJob` 暂存清理缺 `dt` 限定 → 发布一个业务日期会删掉其他日期的未发布暂存分区（交错运行误报 `ADS_STAGING_PRESENT` 8 表全空而失败，且失败 run 的 resume 永久失败）。修复后实测：异日期分区 `S20260907_TEST/dt=20260907` **存活**，同日期历史 `S20260901_29/dt=20260901` **仍被回收**（P2/P3/P4）。**D-R9-2** `TradeDwdJob` 关联 `dim_user/dim_product` 未按生效日期过滤 → 维度每日快照多分区笛卡尔放大：订单明细 7 行→28 行、**金额类指标 ×4**（GMV 2042.00→8168.00、净销售额 1493.00→5972.00、客单价 408.40→1633.60；去重计数类如订单数/PV/UV 不变）。**放大值曾真实进入 ACTIVE**：修复前 run 29 于 `11:02:25` 发布的 `S20260901_29` 即放大值，`11:09:19` 被 run 30（修复后代码）覆盖为黄金值；逐快照原始值见 `33-snapshot-history-values.tsv`（仅该快照异常，22/23/24/25/30 全为黄金）。修复后实测 DWD=7 行、DWS=5/2042.00/1493.00/408.40、ACTIVE 指标=黄金（P7/P8，`26-prune-fix-verification.tsv`） |

## 4. 产品

| # | 验收项 | 判定 | 真实证据 | 说明 |
|---|---|---|---|---|
| D1 | 普通员工可在 Web 完成选择日期、查看分析、读解释、导出和创建决策草稿 | ✅ | `18-r7-4-dom-report.json`（**22/22**）、`17-screenshots/`、`16-api-responses.json` | 8 页 DOM 实测（大盘/趋势/漏斗/商品/RFM/问数/决策/运维）覆盖日期选择、指标卡、图表、解释面板、导出与决策草稿创建；接口层 `16-api-responses.json` 给出对应真实响应。**边界**：DOM 报告采集时上下文快照为 **`S20260901_22`（run 22）**（见该文件 `contextBefore/contextAfter` 字段），是本包内页面侧证据的复用；run 30 快照 `S20260901_30` 的同源性由 `16-api-responses.json` 与 `18-r8-accept-report.json` 断言 |
| D2 | 页面有加载、空、错误、过期和无权限状态 | ⚠️ | `18-r7-4-dom-report.json`、`18-r8-accept-report.json`（A 身份 4 项）、`MetricAdsDaoTest.selectActiveWithoutActiveSnapshotReturnsEmpty`、`AnalysisServiceTest.noActiveSnapshotReturnsEmptyEnvelopeForEveryEndpoint` | 加载/空/错误在真机 DOM 采集，越权在 R8 身份组实测（403）；**只做单测**的是"无 ACTIVE 快照"分支（真机恒有 ACTIVE，无法自然构造，已如实登记）；**页面「无权限态」只有后端 403 实测、无对应 DOM/截图证据**；"过期"以 `MIXED_DEFINITION_VERSIONS`/`NO_COMPARISON_PERIOD` 警告语义呈现 |
| D3 | 商城和随机生成器页面不在分析平台前端 | ✅ | `docs/compatibility-matrix.md`；`17-screenshots/mall/02-mall.png`、`03-admin-products.png`、`04-generator.png`（8090）；平台 13 张截图 | 平台前端构建产物不含商城/生成器页面；两者由 `scripts/start-all.ps1` 分别启动、可分别停止 |

## 5. AI 与安全

| # | 验收项 | 判定 | 真实证据 | 说明 |
|---|---|---|---|---|
| E1 | AI 数字全部来自 EvidencePackage 并带 evidenceRef | ✅ | `18-r8-accept-report.json`（C 六段解释 12 项）、`18-r8-evidence-truncation-proof.json`（12/12） | 六段模板由 `EvidenceBuilder` 组装，每个数值带 `evidenceRef`；模型不可用或数值守卫失败 → 回落模板，绝不 5xx；`limitations`/`warnings` 如实输出（含 `UNKNOWN_DIMENSION_TABLE`、`RFM_AMOUNT_UNAVAILABLE`） |
| E2 | Text-to-SQL 通过 AST、日期、LIMIT、EXPLAIN、超时和只读账号限制 | ✅ | `18-r8-accept-report.json`（D 安全问数 5、E 攻击集 7）、`AiSqlSecurityTest`、`AiSqlDriftTest`、`SqlSafetyValidator`/`QueryCostGuard`/`SqlExecutor`；`docs/remediation-status.md` R7 段（`metric_read` 只读账号 `GRANT SELECT ON analytics_metric.*`） | JSqlParser AST 全字段校验 + 函数白名单 + 快照钉住 + `LIMIT 200` + 日期区间落界；`EXPLAIN` 估算 fail-closed；执行层只读数据源 + `setReadOnly` + 30s 超时 + maxRows；日期口径唯一所有者 `AiScope.DT_FORMAT`（紧凑 `yyyyMMdd`，ISO 一律拒绝） |
| E3 | AI 只能创建 DRAFT，审批人来自当前认证用户 | ✅ | `18-r8-accept-report.json`（F 决策 12）、`DecisionServiceTest`、`DecisionControllerIdentityTest`、迁移 V14 | 12 态状态机；非法流转 400 `DECISION_STATE_ILLEGAL`；`AuthInterceptor` 已删除 `X-User-Id` 回退（伪造头实测无效），审计归属真实认证用户 |
| E4 | 越权与注入攻击集通过 | ✅ | `18-r8-accept-report.json`（**PASS=53 FAIL=0**，A–F 六组）、`AiSqlSecurityTest` | 攻击集 7/7 未得逞；问句层 `SQL_QUESTION_UNSAFE` 在生成 SQL 之前拒绝（不落 `sql_text`，仍写审计）；`operation_audit_log` fail-closed 记录越权尝试 |

## 6. 文档与论文

| # | 验收项 | 判定 | 真实证据 | 说明 |
|---|---|---|---|---|
| F1 | README、部署手册、接口文档和实际架构一致 | ✅ | `README.md`、`docs/README.md`、`docs/deployment.md`、`docs/compatibility-matrix.md`、`docs/contracts/*` | 按真实两进程/两端口/两库边界重写：8 阶段流水线、`scripts/start-all.ps1` 参数、开发代理指向 8091、Derby 单写者警告、遗留脚本 `scripts/run-spark-chain.ps1` 标注"不可作为链路证据" |
| F2 | 所有文稿修改前均有备份 | ✅ | `docs/backups/`（**实测递归 54 个文件**，含 `remediation-status-preR*.md`、`deployment.md.20260911.pre-r8-docs-align.bak` 及本轮 `docs/backups/r9-*`） | 每个整改阶段改前落一份时间戳备份；本轮 R9 新增备份见 `docs/backups/` 索引。抽样命中，"所有文稿"无法穷证 |
| F3 | 论文中的功能、指标、截图和实验都能映射到真实代码与验收证据 | ✅ | `docs/thesis-materials/证据映射表.md`（**实测主表 74 条映射**：57 已实测 / 9 部分 / 8 未实测）、`docs/thesis-materials/results-tables.md`、`docs/thesis-materials/screenshot-list.md`、`docs/thesis-draft/**` | 论文初稿已按真实证据订正：黄金对账口径、未实测的性能/决策数字改为 ⚠️ 占位、阶段数 7→8、页面数 7→9、Scala 作业 6→11、Flyway V1–V15、去掉未使用的 Snappy、开发代理端口、`node --test`、决策 12 态常量、`user_session` 鉴权、29 张表口径。**残留**：`docs/thesis-materials/thesis-outline.md` 仍引用已不存在的 `1.png`/`GoldenE2ETest`/`AdsMaterializer`（待同步） |

## 7. 测试与实验计数（本轮实跑）

| 范围 | 结果 | 落点 |
|---|---|---|
| 平台 `analytics-server`（7 个 reactor 模块） | **303/303，BUILD SUCCESS** | `24-fulltest-analytics-server.log` |
| 平台真库集成测试（指标发布，默认跳过需 `-Dmetric.it=true`） | **1/1 PASS（0 skipped）** | `31-metric-publish-it-regression.log` |
| `spark-jobs`（Scala 单测） | **46/46**（`Tests: succeeded 46, failed 0`） | `32-spark-jobs-tests.log`（修复后重建实跑） |
| `mall-simulator` | 54/54（**沿用 2026-09-10 登记结果，本轮未重跑**） | `docs/remediation-status.md` R8 段 |
| 平台前端 | `node --test` **74/74**（无 vitest） | `docs/remediation-status.md` R7-4 段 |
| 页面真机 DOM | 平台 **22/22**、商城 **17/17** | `18-r7-4-dom-report.json`、`18-r7-4-mall-dom-report.json` |
| R8 真机验收 | **53/53** + 证据截断复验 **12/12** | `18-r8-accept-report.json`、`18-r8-evidence-truncation-proof.json` |
| R9 可靠性实验 | C1–C4d 8/8 PASS；C5-1…C5-6 PASS；D-R9-1/D-R9-2 修复验证 **10/10 PASS** | `21-reliability-experiments.tsv`、`26-prune-fix-verification.tsv` |

## 8. 如实登记的边界（未达标/未做，不写成结论）

1. **集群规模**：1M–100M 事件分档、分发/广播/Parquet 对照未做（无 Hadoop/Hive 集群）；`SPARK_SUBMITTER=SINGLE_NODE|REMOTE_CLUSTER` 分支未激活（当前 `LOCAL`：`LocalProcessSparkSubmitter`，其 `status()` 恒返回 `SUBMITTED`）。
2. **真实 LLM**：无 `LLM_API_KEY`，`providerUsed=template`，故 `ai_call_log` 为 0 行；Baseline A/B/C/D 对照与"AI 数值事实一致率 ≥95%"未测（`experiments/ai-eval-*.json` 是 Mock 基线，不作为安全结论）。
3. **性能基线**：`experiments/perf-web-tier1.json` 属 2026-09-06 **旧链**数据，本轮未在整改后平台重测 P95；论文中相关数字已改为 ⚠️ 占位。
4. **Flume→HDFS 断点恢复**：本地等价语义（字节偏移 + manifest）已测，集群实录待环境；Parquet Snappy 压缩在本仓库 0 处命中（未启用）。
5. **契约 vs 真实质量结果**：契约写 `4/4/[]`，真实 run 为 `4/3/["EVENT_ID_UNIQUE"]`（14 检查 1 错，阈值 0.0005，非核心规则不阻断）。
6. **采集口径分歧**：`ingestion_batch` 记 `record_count=51 / quarantine=4`，契约口径为 accept 52 / reject 3（多隔离的一行是重复 `event_id=golden-evt-037`），`LOAD_ODS` 实测装载 51 条；批次状态 `QUARANTINED` 但 accepted 部分仍被流水线消费（已登记，未改语义）。
7. **`dt=20260903` 分区**：来自 C4 故障注入夹具（`paid_amount=0.01`），`dwd_order_detail` 该分区 28 行是**修复前**扇出结果，修复后未重跑该测试日期（保留为故障注入痕迹，不代表黄金口径）。
8. **失败 run 保留**：run 26（`PIPELINE_QUALITY_FAILED`）、27（`RUN_INTERRUPTED`，2026-09-04 空数据）、28（`RUN_EMPTY_DATA`）按设计保留为故障证据，不清理、不 resume（避免发布空快照）。
9. **其他已登记偏差**：归档快照可读且未做权限校验（`MySqlMetricStore` 查询与 `findSnapshot` 均无 status 过滤，客户端可传任意 snapshotId）；`ads_user_profile_m` 无金额列（`RFM_AMOUNT_UNAVAILABLE`）；类目/地区 ADS 无载体（`UNKNOWN_DIMENSION_TABLE`）；`ADS_STAGING_SNAPSHOT_ISOLATION` 故意保持 ERROR 级；`/admin/users` 不返回 `status`；`cart_rate` 与 `repeat_rate` 无 ADS 落地（`repeat_rate` 仅存在于契约夹具期望值）；`WAIT_LANDING.evidence` 为 JSON + ` | ` 审计混合文本；`metadata_app`（第 4 库）未使用。
   - 订正（2026-09-11 复核）：本清单早期版本写「`metric_snapshot.version` 恒为 1」**不成立**——发布器取 `MAX(version)+1`（`MetricPublishRepository.java:38-41,60`），真库实测 `S20260901_23=1 → _24=2 → _22=3 → _25=4 → _29=5 → _30=6`；仅首次发布取 DDL 默认值 1。同步已修正 `docs/remediation-status.md` 对应行。

## 9. 结论

§30 六域（指导书原文 **19 项**，见 `docs/项目完整实施指导书 V2.0.md:1547-1580`）：**17 项 ✅、2 项 ⚠️（C2 故障恢复、D2 页面状态）、0 项 ❌**。
本表另加 1 行自检项 C4（本轮新发现并修复的缺陷，非指导书条目），故本表共 20 行 = **18 ✅ / 2 ⚠️ / 0 ❌**。
两项 ⚠️ 的差距均已在第 8 节逐条列明，不涉及黄金链正确性。
本轮最重的收获是**用真实链挖出并修掉两个 Mock 不可能发现的缺陷**（D-R9-1 暂存清理越界、D-R9-2 维度跨分区扇出），修复后端到端复验 10/10 PASS，黄金口径与标准答案逐项一致（不一致项 0）。


