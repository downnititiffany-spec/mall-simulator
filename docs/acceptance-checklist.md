# 验收清单（§27.3 目标 ↔ 真实状态 ↔ 证据位置）

> 原则（V2.0 §10.6）：**所有数字必须是实测结果**；Mock/未测项一律标 ⚠️ 并写清缺什么。
> 本轮定稿：**2026-09-11**，代码版本 `e272c8a`，黄金链 run **30**，ACTIVE 快照 **S20260901_30**（业务日期 20260901）。
> 完整 §30 逐项判定见 `docs/acceptance/r9-20260911-e272c8a-run30-S20260901_30/30-final-acceptance.md`（16 ✅ / 2 ⚠️ / 0 ❌）。

## 功能与正确性（当轮真机实测）

| 验收项 | 目标 | 实测结果 | 证据 |
|---|---|---|---|
| 核心金额对账误差 | < 0.01 | ✅ 黄金链**不一致项 = 0**：ADS 概览 7/3/3/5/2042.00/1493.00/408.40、净额 1493.00、AOV 408.40 与标准答案逐项相同 | `docs/acceptance/r9-...-run30-.../20-reconciliation.tsv`、`13-ads-overview-active.tsv`、`15-hive-layers.txt` |
| event_id 去重 | 100% | ✅ 55 行夹具 → 行为明细 14 行（1 条重复 `golden-evt-037` 计入 `dwd_reject_record.DUPLICATE_EVENT`，`EVENT_ID_UNIQUE` error=1 只记不阻断） | `15-hive-layers.txt` §quality；`spark-jobs` `DwdSql` + `tests/golden-dataset/expected` |
| 危险 SQL 拦截率 | 100% | ✅ 攻击集 7/7 未得逞（真机）；生成 SQL 之前先过问句层 `SQL_QUESTION_UNSAFE` | `18-r8-accept-report.json`（PASS=53）、`AiSqlSecurityTest` |
| 黄金数据通过率 | 100% | ✅ 契约口径 9 项核心指标全部命中（run 30 SUCCESS，attempt 1） | `20-reconciliation.tsv`、`11-metric-value-active.tsv` |
| 金额精度 | DECIMAL(18,2) | ✅ BigDecimal/字符串全程，落库 `DECIMAL(18,2)`（MySQL 侧 `8168.00 → 2042.00` 修复即为此链路实测） | `15-hive-layers.txt`、`12-ads-table-counts.tsv` |
| 幂等 | 同键不重复执行 | ✅ 同键两次 POST → 同一 runId；同键并发 6 次 → 唯一 runId 1 / DB 1 行 | `21-reliability-experiments.tsv` C1/C2；`PipelineServiceTest.sameIdempotencyKeyReturnsOriginalTask` |
| 快照发布 | 唯一 ACTIVE、失败保旧 | ✅ `metric_snapshot` 单 ACTIVE（S20260901_30），其余 ARCHIVED；质量失败 run 26 未产生新 ACTIVE | `10-metric-snapshot.tsv`、`22-qualityfail-run26.json`；`MetricAdsDaoTest.selectActivePinsOneSnapshot` |
| AI 只读 | 不可写业务库 | ✅ 执行层只读数据源 + `setReadOnly` + 30s 超时；只读账号 `metric_read` 仅 `GRANT SELECT ON analytics_metric.*` | `docs/remediation-status.md` R7 段、`SqlSafetyValidator`/`SqlExecutor` |
| 决策闭环 | 全生命周期 | ✅ 12 态状态机 + 基线锁定 + 方向取反；AI 只能建 DRAFT，非法流转 400 | `18-r8-accept-report.json` F 组 12/12；`DecisionStateMachineTest`、`DecisionServiceTest` |
| 发布前校验 | 不合法不得入库 | ✅ 真库集成测试实跑 1/1（0 skipped）：正常快照发布 8 行 ADS + 10 条指标值；字典版本不符被 `MP_VERIFY_FAILED`/`MP_METRIC_DICT_VERSION` 拦下 | `31-metric-publish-it-regression.log`（`-Dmetric.it=true`） |

## 可靠性与故障注入（§23.3 强制失败测试）

| 验收项 | 实测结果 | 证据 |
|---|---|---|
| 同键幂等/并发 | ✅ C1/C2 PASS | `21-reliability-experiments.tsv` |
| 失败重试只从失败阶段开始 | ✅ C3 PASS（前 5 阶段与后 3 阶段时间戳间隔 800.1 分钟，未重跑已完成阶段）；C5-3 resume 仅新增 1 行 `QUALITY_CHECK` | 同上 + `29-resume-evidence.tsv` |
| 质量门阻断且不污染 | ✅ C4/C4b/C4c/C4d PASS（终态 FAILED/`PIPELINE_QUALITY_FAILED`，ACTIVE 与快照数不变，黄金行未被改写） | `22-qualityfail-run26.json`、`23-qualityfail-isolation.sql` |
| 进程被杀重启后恢复 | ✅ C5-1/C5-2 PASS（状态由 DB 复原；`resume` 200 OK，仅失败阶段及之后重跑） | `21-...` C5 组、`29-resume-evidence.tsv` |
| 本轮修掉的两个真实缺陷 | ✅ **10/10 PASS**：D-R9-1 暂存清理越界（异日期暂存分区存活、同日期历史仍回收）；D-R9-2 维度跨分区扇出（DWD 7 行、DWS/GMV 回到黄金） | `26-prune-fix-verification.tsv`、`27-prune-fix-verify.log`、`28-prunefix-run.json` |

## 规模扩展（单机部分，2026-09-06 归档数据）

| 事件规模 | 结果 | 证据 |
|---|---|---|
| 1.44 万 / 6.48 万 / 12.96 万 | ✅ 单机吞吐随规模提升（旧链脚本 `scripts/run-spark-chain.ps1`，**不含 `tdw`/`dim`**，不作为平台链路证据） | `experiments/spark-scale-local-20260906.json` |

## 待环境项（如实标注，不提前写成结论）

| 项 | 依赖 | 现状 |
|---|---|---|
| Spark 集群性能（100 万–1 亿、分发/广播/Parquet 对照） | Hadoop/Hive/Spark 集群 | ❌ 未做；平台 `SPARK_SUBMITTER=LOCAL`（`LocalProcessSparkSubmitter`，`status()` 恒 `SUBMITTED`），`SINGLE_NODE`/`REMOTE_CLUSTER` 分支未激活 |
| 真实 LLM 对照（Baseline A/B/C/D）与 AI 数值事实一致率 ≥95% | `LLM_API_KEY` | ❌ 未做；`providerUsed=template`，`ai_call_log` 0 行；`experiments/ai-eval-*.json` 是 Mock 基线，不作为安全结论 |
| Flume→HDFS 断点恢复实录 | SINGLE_NODE 环境 | ❌ 未做；本地等价语义（字节偏移 + manifest + accepted/quarantine）已实测 |
| 整改后平台性能基线（P95） | 本机复测 | ❌ 未做；`experiments/perf-web-tier1.json` 属 2026-09-06 旧链数据 |
| Parquet Snappy 压缩对照 | — | ❌ 未启用（仓库内 0 处命中） |

## 测试计数（本轮实跑）

| 范围 | 结果 | 落点 |
|---|---|---|
| 平台 `analytics-server`（7 个 reactor 模块，5 个含测试） | **303/303**，BUILD SUCCESS（29+96+38+91+49） | `24-fulltest-analytics-server.log` |
| 平台真库集成测试（默认跳过） | **1/1 PASS**（0 skipped） | `31-metric-publish-it-regression.log` |
| `spark-jobs` | **46/46** | 缺陷修复后重建（`mvn -f spark-jobs/pom.xml package`） |
| `mall-simulator` | 54/54（沿用 2026-09-10 登记，本轮未重跑） | `docs/remediation-status.md` R8 段 |
| 平台前端 | `node --test` **74/74** | `docs/remediation-status.md` R7-4 段 |
| 页面真机 DOM | 平台 **22/22**、商城 **17/17** | `18-r7-4-dom-report.json`、`18-r7-4-mall-dom-report.json` |
| R8 真机验收 | **53/53** + 证据截断复验 **12/12** | `18-r8-accept-report.json`、`18-r8-evidence-truncation-proof.json` |

## 版本与复现说明

* 定稿时间 2026-09-11；技术基线 JDK 17 / Spring Boot 3.2.5 / MySQL 8.0.41 / Scala 2.12 / Spark 3.5.1（本地 `D:\Develop\spark-3.5.1-bin-hadoop3`）/ Derby metastore。
* 黄金链复现步骤：投放 `tests/golden-dataset/events/golden-20260901.jsonl` → `POST /api/v1/ingestion/runs` → `POST /api/v1/pipeline-runs`（`runtimeProfileId=1, pipelineCode=ODS_TO_ADS, businessTime=2026-09-01T00:00:00, sourceDataVersion=<新值>`）→ 轮询 `GET /api/v1/pipeline-runs/{id}`。
* 全量测试命令：`mvn -f analytics-server/pom.xml test`；真库集成测试另加 `-Dmetric.it=true -Dtest=MetricPublisherMySqlIT -Dsurefire.failIfNoSpecifiedTests=false`。
* 验收证据目录命名（§23.5）：`docs/acceptance/r9-<yyyyMMdd>-<commit>-run<runId>-<snapshotId>/`；历史包 `r9-20260911-fabc6cb-run22-...`（修复前缺陷复现）与 `...-run25-...`（修复过程）保留为对照。
