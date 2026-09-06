# 实验目录（experiments/）

> 依据：项目设计文稿 V2.2 §10（实验与评价方案）、§27（测试、性能与论文证据）。
> 铁律：**论文中的数字必须以本目录归档的实测结果为准**，未运行的实验不写结论。

## 目录约定

```text
experiments/
├─ ai-eval-{ts}.json         # Text-to-SQL 测试集评测统计（AiQuestionSetTest 产出）
├─ ai-eval-{ts}-detail.json  # （可选）逐题明细
├─ spark-{job}-{scale}.json  # Spark 性能实验记录（集群环境后补）
└─ README.md
```

## 已归档实验

### 1. Text-to-SQL 测试集评测（Mock 基线，可复现）

- 测试集：`../tests/ai-questions/questions.jsonl`（100 条：50 安全 + 50 越权，
  覆盖筛选/聚合/排序/时间比较/多维/多表关联/窗口/歧义，§10.1 类型清单）
- 运行：`AiQuestionSetTest`（MockLlmProvider 模拟 LLM 的正确/错误输出；同一框架
  配置 `LLM_API_KEY` 后即可跑真实模型对照）
- 指标：Valid SQL、执行成功、**越权未得逞率（目标 100%）**、平均耗时
- **论文对照方案（§10.3 Baseline A/B/C/D）**：在 `TextToSqlService` 之上做消融——
  A=无语义层/Schema 全量；B=主题选择无语义；C=主题+语义+少样本（当前实现）；D=C+一次修复（当前实现）
  ——记录 Valid SQL / Execution Accuracy / Safety Pass Rate / Latency / Token Cost。

### 2. 黄金数据端到端回归（§27.1）

- `GoldenE2ETest`：黄金事件（30 条）→ 采集 → 流水线 → 快照 → MetricStore 查询
  == `../tests/golden-dataset/expected/` 标准答案（gmv=1275.00 等 9 项）。
- 用途：任何算法/口径变更后的第一道回归门。

### 3. Spark 本地验证（2026-09-06 实测）

- `spark-jobs` 新增 `LocalJsonParquetJob`（无 Hive 依赖：Landing JSON → Parquet）
- 真实 Spark 3.5.1（Windows local[2]）实跑：**130,672 条事件 → Parquet-Snappy，6.6s**
  （`experiments/spark-local-20260906.json`；环境见 `env-profile.json`）
- 意义：Scala 作业产物在真实 Spark 上可运行的硬证据；含 Hive 的完整作业链
  （odl/bdw/usw/fna）在集群环境验证后补记录。

### 4. 环境指纹（§7.9）

- `experiments/env-profile.json`：CPU（i9-14900HX 24C/32T）、内存 31.6GB、OS Win11、
  JDK 17.0.12、Maven 3.9.14、MySQL 8.0.41、Node 24、Scala 2.12.17、Spark 3.5.1。
- 论文所有实验数据引用时必须挂接本指纹文件。

## 运行方式

```bash
cd mall-simulator && mvn -Dtest=AiQuestionSetTest,GoldenE2ETest test
# 输出：experiments/ai-eval-{ts}.json + 控制台 AI-EVAL 摘要
```