# 论文章节素材映射（§15.1 九章）

> 用途：写作时按章取素材；**所有数字来自 experiments/ 与验收清单，未实测不写结论**。

## 第一章 绪论
- 背景与问题：文稿 §1.2（5 个问题点）
- 研究内容/创新点：§9.1-9.6 六项 → 对应实现证据：
  - 9.1 面向数仓语义的 Text-to-SQL → `SemanticCatalog`（语义/别名/少样本）+ 消融框架 `AiQuestionSetTest`
  - 9.2 多级校验与修复 → `SqlSafetyValidator` 四层 + REPAIRED 闭环（评测 repaired_total=58）
  - 9.3 证据链报告 → `ExplanationService`（SQL/行数/耗时/快照/口径全部回传）
  - 9.4 大数据与智能交互分层 → `AdsMaterializer`（AI 只查 ADS 物化，不动明细）
  - 9.5 场景驱动可复现数据 → `SimulationEngine`（种子复现测试）+ 11 场景 ExpectedEffect
  - 9.6 指标快照决策闭环 → `DecisionService` + 12 态状态机 + 效果评价

## 第二章 相关技术
- 版本选型：文稿 §4.1 对照表 → 实测版本（JDK17/SB3.2.5/MySQL8.0.41/Scala2.12.19/Spark3.5.1 依赖）
- 强调"论文写真实版本"：`docs/deployment.md` 与 pom 为准

## 第三章 需求分析
- 角色/用例/指标：文稿 §2.1/2.3 + `docs/contracts/metric-dictionary.md`（15 项口径 v1）

## 第四章 系统总体设计
- 架构图/部署图/模块图：文稿 §3 图 → 复用 1.png 及前端页面截图
- 模块数 12 个 → README 阶段表可佐证

## 第五章 数仓与 Spark 设计实现（核心章）
- 表清单：`warehouse/ddl/00-04`（ODS 4/DWD 3/DIM 5/DWS 7/ADS 10）
- 血缘：`warehouse/README.md`（odl→bdw→usw→fna 依赖链）
- Spark 作业源码：`spark-jobs/src/main/scala/...`（4 作业 + 算法 + SQL 模板）
- 口径：event_id 去重/有效支付/分母 0→NULL——`SqlTemplateSpec` 断言可引
- 质量规则：`QualityChecker`（金额对账核心阻断）→ `data_quality_result`

## 第六章 智能分析模型设计实现（核心章）
- 语义层 `SemanticCatalog` / 流程 `TextToSqlService` / 四层校验 `SqlSafetyValidator`
- 证据解释 `ExplanationService` + `AiOutputValidator`（结构化 JSON 契约）
- 降级路径 `RuleBasedSqlFallback`（§3.5.5 实测：无 key 全链可跑）

## 第七章 系统实现
- 页面截图点：`docs/thesis-materials/screenshot-list.md`
- 接口：`docs/api-overview.md`
- 审计：`ai_query_history`/`ai_call_log`/`sys_operation_log` 语义

## 第八章 实验与结果分析（论文心脏）
- 黄金对账：`GoldenE2ETest`（9 项指标=标准答案）→ 表格素材
- Text-to-SQL：`experiments/ai-eval-*.json`（valid/executed/blocked/avg_ms；Mock 基线）
- 性能：`experiments/perf-web-tier1.json`（30 并发 P95 21-29ms）+ 优化前后对比
  （3.9s/7.4s → 21ms/29ms，185-254x）← **重点图表**
- 数据规模：LOCAL 1.1万/10.8万事件流水线耗时（<1s/1.3s）
- 安全：50/50 越权未得逞（拦截率 100%）
- **待补**：Spark 集群分档性能（100万-1亿）、真实 LLM 消融 A/B/C/D、AI 数值事实一致率人工评分

## 第九章 总结与展望
- 成果：阶段 1-10 完成清单；不足：集群验证、真实模型、实时化未做
- 展望：Kafka/SeaTunnel/Structured Streaming、Doris 指标服务、KMeans 可选实验

## 写作纪律（§15.2）
- 每章按"问题→方法→实现→实验→分析"闭环组织；
- 实验数字 → 引用 `experiments/` 原始 JSON；截图 → 按 `screenshot-list.md` 采集；
- 引用借鉴项目（text-to-sql-main 等）时保持来源说明（§16 风险控制）。