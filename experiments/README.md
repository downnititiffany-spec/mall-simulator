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

### 3. Spark 性能实验（待集群环境，模板）

```text
每次实验记录（§7.9）：executor 数、cores、memory、driver memory、shuffle partitions、
输入记录数、输入/输出文件数、总耗时、失败次数；数据规模 100万/1000万/5000万；
对照组：基础方案 vs 分区裁剪 vs Parquet vs 广播连接 vs 并行度调整（§10.2 实验二）。
```

## 运行方式

```bash
cd mall-simulator && mvn -Dtest=AiQuestionSetTest,GoldenE2ETest test
# 输出：experiments/ai-eval-{ts}.json + 控制台 AI-EVAL 摘要
```