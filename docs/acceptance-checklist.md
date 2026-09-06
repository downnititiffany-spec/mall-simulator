# 验收清单（§27.3 目标 ↔ 当前状态 ↔ 证据位置）

> 原则（§10.6）：**所有数字必须是实测结果**；本表由开发过程逐项核对，缺失项如实标注"待环境"。

## 功能/正确性验收

| 验收项 | 目标 | 当前状态 | 证据 |
|---|---|---|---|
| 核心金额对账误差 | < 0.01 | ✅ 实测 0（事件级=订单总额校验 + 层间对账） | `mall-simulator/src/test/.../pipeline/PipelineRunTest`（T10 金额不平阻断发布）；`tests/golden-dataset/expected` 对账 |
| event_id 去重正确率 | 100% | ✅ 唯一键 + DWD row_number 去重 | `EventOutboxServiceTest.duplicateEventIdRejected`；`DwdSql.behaviorClean` 测试 |
| 危险 SQL 拦截率 | 100% | ✅ 50/50 越权题未得逞（Mock 基线） | `experiments/ai-eval-*.json`（blocked_never_attack_succeeded=50） |
| 核心任务黄金数据通过率 | 100% | ✅ 黄金端到端 9 项指标=标准答案 | `GoldenE2ETest` |
| 金额字段精度 | DECIMAL(18,2) | ✅ BigDecimal/字符串链路全程 | 契约文档 §4；`SqlExecutor`（BigDecimal → 字符串） |
| 幂等 | 同键不重复执行 | ✅ | `PipelineRunTest.idempotencyReturnsSameRun`（同 runId、仅 1 行） |
| 快照发布 | 唯一 ACTIVE、失败保旧 | ✅ | `MySqlMetricStoreTest.onlyOneActivePerProfile`；`amountMismatchBlocksPublish` |
| AI 只读 | 不可写业务库 | ✅ 四层校验 + **数据库只读账号 mall_reader 实测**（UPDATE/DROP 被权限层拒绝，ReaderAccountSecurityTest 3/3） |
| 决策闭环 | 全生命周期 | ✅ 12 态状态机 + 基线锁定 + 方向取反 | `DecisionStateMachineTest` / `DecisionServiceTest` |

## 性能验收（本机实测，30 并发 × 600 请求）

| 验收项 | 目标 | 实测 | 证据 |
|---|---|---|---|
| 普通看板 P95 | ≤ 2 秒 | **21-29ms**（10.8 万事件规模） | `experiments/perf-web-tier1.json` |
| 状态查询 P95 | ≤ 1 秒 | 24ms（metrics/overview） | 同上 |
| 普通用户并发 | 30 人目标 | ✅ 30 并发 0 错误 | 同上 |
| AI 交互时延 | ≤ 30 秒 | 9-600ms（Mock 基线，无网络开销） | `experiments/ai-eval-*.json`（avg_ms=9） |

## 数据规模扩展（LOCAL，§10.2 实验一单机部分）

| 事件规模 | 生成耗时 | 流水线耗时 | 证据 |
|---|---:|---:|---:|
| 1.1 万 | ~30s | <1s | 冒烟记录 |
| 10.8 万 | ~290s | 1.3s | 本轮实验（Landing 共 50 文件 383MB 峰值） |

## 待环境项（如实标注，不提前写成结论）

| 项 | 依赖 | 现状 |
|---|---|---|
| Spark 集群性能（100 万-1 亿，分发/广播/Parquet 对照） | Hadoop/Hive/Spark 环境（node01-03 或云端） | 本地 Derby-Hive 四层链已通（13 万事件，见下），集群分档待环境 |
| 真实 LLM 对照（Baseline A/B/C/D） | `LLM_API_KEY` | 框架就绪（`AiQuestionSetTest` 复用），Mock 基线已存 |
| Flume→HDFS 断点恢复实录 | SINGLE_NODE 环境 | 本地等价语义已测（`LocalFileIngestorTest`），集群实录待环境 |
| AI 数值事实一致率（≥95%） | 真实模型 + 人工评分 | 待 key 后运行 |

## 本机已达成补充（2026-09-06 归档）

| 项 | 结果 | 证据 |
|---|---|---|
| Spark 四层作业链本地运行 | sci→odl→bdw→usw→fna 全 SUCCESS（13 万事件） | `experiments/spark-chain-local-20260906-174311.json` |
| Spark 规模扩展（单机） | 1.4万/6.5万/13万 三档吞吐随规模提升 | `experiments/spark-scale-local-20260906.json` |
| 答辩/论文素材 | 16 页网页 PPT + 11 张截图 + 九章论文初稿 | `ppt/index.html`、`docs/thesis-material*/` |
| 一键演示 | 生成→发布→采集→流水线→证据输出脚本化 | `scripts/run-demo.ps1`（实测通过） |

## 版本与复现说明

* 本表生成时间：2026-09-06；技术基线：JDK 17 / Spring Boot 3.2.5 / MySQL 8.0.41 / Scala 2.12 / Spark 3.5（依赖）。
* 所有测试命令：`cd mall-simulator && mvn test`（需 `MALL_DB_PASSWORD` 环境变量）。