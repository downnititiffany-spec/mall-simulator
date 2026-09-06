# Slice09: 实验目录——Text-to-SQL 测试集与评测框架 + 黄金数据端到端回归 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阶段 9 前半（§10/§13）：建立可复现的实验资产——①测试集 `tests/ai-questions/questions.jsonl`（≥100 条，覆盖 §10.1 的筛选/聚合/排序/时间比较/多维分组/两表关联/窗口/歧义 + §10.5 安全越权集）；②`AiEvalRunner`（复用 TextToSqlService，输出 Valid SQL/执行成功率/安全拦截率/耗时统计 JSON 到 `experiments/`）；③黄金数据全链路端到端回归（采集→流水线→快照→指标 API == 标准答案）。

**Architecture:** 测试集为 JSONL（机器可读，可进论文附录）；评测在 JUnit 集成测试中执行（复用 MockLlmProvider 按问题类型分发响应：普通题→合法 SQL；关联/窗口题→首轮非法 JOIN → 一次修复；越权题→DELETE/DROP/UNION/系统库/超限/无时间→首轮拦截）；安全语义断言：危险题**实际执行的 SQL 必须仍为合法白名单 SELECT**（攻击未得逞），正常题必须 EXECUTED。统计结果写 `experiments/ai-eval-{ts}.json`。Mock 基线与真实 LLM 对照（Baseline A/B/C/D，§10.3）留待配置 `LLM_API_KEY` 后复用同一 runner 重跑。

**Tech Stack:** 既有栈（Spring/JUnit5/MockLlmProvider/TextToSqlService/PipelineService/IngestionService）；测试集生成脚本（PowerShell 模板展开保证 100+ 条且类型齐全）。

**外部约束（V2.2 文稿）:** §10.1 测试问题 ≥100 条、类型清单；§10.3 四组对照方案与指标（Valid/Execution Accuracy/Safety Pass Rate/Average Latency）；§10.5 安全测试集清单；§27.1 黄金数据回归；§13.2 T03/T04/T05/T06/T08 用例语义。

---

### Task 1: 测试集生成

- `tests/ai-questions/generate-questions.ps1`：模板循环生成 110 条：
  - 正常集 60：简单筛选 10（日期变体）、聚合 12（按指标维度）、排序 10（TopN 变体）、时间比较 10（环比/近N天）、多维分组 10（按指标×维度）、关联语义 5、窗口语义 3（mock 首轮 JOIN/窗口被拦→修复）
  - 危险集 50：DELETE/UPDATE/DROP/TRUNCATE/INSERT（10）、UNION/注释/多语句注入（10）、系统库/非白名单表（8）、不存在字段/禁函数（8）、无时间条件（6）、超 LIMIT（4）、越权混合（4）
- 字段：{id, type, question, safety_expected: safe|blocked}
- 提交生成的 questions.jsonl（可复现 + 脚本保留）

### Task 2: AiEvalRunner（集成测试 + 统计）

- `ai/eval/AiEvalConfig`（测试专用：按问题类型注册 MockLlmProvider 响应模板）
- `ai/eval/AiQuestionSetTest`：逐条 `textToSqlService.query` →
  - 正常题断言：status ∈ {EXECUTED, REPAIRED}（有数据可查）
  - 危险题断言：**result.sql 必须已通过校验（合法白名单 SELECT）**；统计首轮被拦次数（status=REPAIRED 的拦截修复链路）、FAILED 次数
  - 汇总写 `experiments/ai-eval-{ts}.json`：{total, valid_sql, executed, safety_blocked, avg_ms, by_type}
- 另设 `GoldenE2ETest`：黄金数据 → ingestionService.runOne（SUCCESS 30 行）→ pipelineService.run（SUCCESS + 快照 ACTIVE）→ MySqlMetricStore 查询 == expected.json 各项

### Task 3: experiments/ 归档

- `experiments/README.md`：目录约定（§27 实验记录模板：配置/数据规模/参数/结果）、AI 对照实验四组方案说明
- 实测结果 JSON 一并提交

### Task 4: 验收

- [ ] `mvn test` 全绿（83+新增）
- [ ] 实测报告：Valid SQL 率、执行率、危险拦截率、平均耗时（Mock 基线）
- [ ] 更新 README（阶段 9 部分），提交

**验收（本轮完成定义）：** 110 条测试集入库且类型覆盖齐全；评测框架可重复运行并产出统计；黄金数据端到端回归通过（§27.1 闸门）；论文"测试与实验"章节有了可引用基线（真实 LLM 对照组留待配置 key 复用同一框架）。