# Slice07: AI 智能分析——语义层 + 受控 Text-to-SQL + 证据解释 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阶段 8 核心链路（§8）：业务语义层 → 主题/Schema 选择 → LLM 生成 SQL（JSON）→ JSqlParser 四层安全校验 → 只读执行（LIMIT/超时）→ 一次受控修复 → 证据包 → 结构化解释。LLM 供应商可替换（无 key 时规则回退，AI 下线不影响固定链路）。审计落 `ai_query_history` / `ai_call_log`。

**Architecture:** `ai` 包：`LlmProvider`（§19.3 AiProvider 接口；OpenAI 兼容实现 + Mock 测试实现）；`SemanticCatalog`（ADS 白名单表+字段语义+中文别名+少样本，§8.2）；`SqlSafetyValidator`（§8.6 四层：字符串预检 → JSqlParser AST → 表白名单/字段存在 → LIMIT/禁函数/资源限制）；`SqlExecutor`（MySQL 只读执行：30s 超时、<=1000 行）；`TextToSqlService`（§8.4 流程+§8.7 一次修复）；`ExplanationService`（§8.8 结果+证据 → 结构化解解释 JSON → 本地验证：数值必须来自结果、facts 带 evidenceIds，§22.3）。无 LLM key → `RuleBasedSqlFallback`（推荐问题模板 SQL）保证演示链路完整体验。

**Tech Stack:** Java 17、Spring Boot 3.2.5、JSqlParser 4.9、MyBatis-Plus、Flyway V4、JUnit 5、Jackson。

**外部约束（V2.2 文稿）:** §8.1 只查 ADS 白名单；§8.5 只 SELECT/WITH、必有时间条件、默认近 30 天写入 assumptions、明细限行；§8.6 四层校验 + 资源限制（500/1000、30s、脱敏）；§8.7 最多修复一次；§8.8 证据约束（不能引用不存在的数值）；§22.3 结构化 JSON 输出契约；§12 安全（错误不泄露连接信息）。

---

### Task 1: 依赖与审计表

- `pom.xml` 加 `com.github.jsqlparser:jsqlparser:4.9`
- `V4__ai_audit.sql`：`ai_query_history`（id, user, question, sql, tables, status, rows, elapsed_ms, feedback, errors, created_at）、`ai_call_log`（id, use_case, provider, model, prompt_version, input_tokens, output_tokens, elapsed_ms, status, error, created_at）

### Task 2: LlmProvider

- `ai/llm/LlmProvider.java`：`AiResponse complete(AiRequest)`、`healthCheck()`
- `ai/llm/OpenAiCompatLlmProvider.java`：REST /chat/completions，base-url/api-key/model 走 `LLM_BASE_URL/LLM_API_KEY/LLM_MODEL`（无 key → healthCheck false）
- `ai/llm/JsonExtractor.java`：从模型输出提取 JSON（code fences / markdown / 裸对象）
- Test: `JsonExtractorTest`

### Task 3: SemanticCatalog（§8.2）

- 3 张 ADS 表白名单：`ads_sale_trend`（sale_amount 销售额=有效支付金额、order_count、buyer_count、avg_order_value 客单价）、`ads_operation_overview`（pv/uv/dau/gmv/net_sale_amount/refund_rate）、`ads_behavior_funnel`（stage/user_count/conversion_rate）；字段别名表（销售额|GMV→sale_amount 等）；每表 2 条少样本
- `semanticSelect(question)`：主题关键词 → 候选表（1-3 张）→ Schema JSON 字符串
- Test: `SemanticCatalogTest`（别名映射、主题选择、样本完整）

### Task 4: SqlSafetyValidator（§8.6）

- ①字符串预检：多语句/注释逃逸/DDL-DML 关键词
- ②JSqlParser：根为 Select 或只读 With；无 INTO/JOIN 超 3 表（§8.6 资源限制）；禁函数名单
- ③表白名单 + 字段存在于语义目录
- ④LIMIT 强制（无 LIMIT → 重写追加 500；>1000 → 截断）与超时提示返回
- Test: `SqlSafetyValidatorTest`：delete/drop/union 注入/注释绕过/无时间范围/不存在字段/超大 limit —— 全拦截或重写

### Task 5: SqlExecutor

- 通过 DataSource 只读连接执行（`Connection.setReadOnly(true)` + 30s 查询超时），结果 List<Map<String,Object>>（BigDecimal → String 防精度丢失），>=500 → 截断告知
- Test: `SqlExecutorTest`（插 ACTIVE 快照数据 → SELECT 执行返回正确）

### Task 6: TextToSqlService（§8.4/§8.7）

- 流程：问题规范化 → 主题识别 → Schema 裁剪 + 指标别名 + 少样本 → prompt（内嵌安全规则）→ LLM JSON（intent/metrics/sql/assumptions）→ 校验 → 失败反馈+修复一次 → 执行 → 证据
- LLM 不可用 → `RuleBasedSqlFallback`（推荐问题模板）
- 审计：query_history + call_log
- Test: `TextToSqlServiceTest`（MockLlmProvider 生成合法 SQL → 全链成功；生成危险 SQL → 拦截；一次修复成功）

### Task 7: 证据解释（§8.8/§22.2-22.3）

- `EvidenceBuilder`：{snapshotId, question, sql, tables, returnedRows, elapsedMs, timeRange, definitions}
- `ExplanationService`：结果摘要+证据 → LLM 结构化 JSON（summary/facts[evidenceIds]/possibleCauses/suggestions/limitations）→ `AiOutputValidator` 校验（facts 数值存在于结果、evidenceId 存在、禁词）→ 返回
- Test: `ExplanationServiceTest`（Mock 输出合格/不合格两种情况）

### Task 8: 接口与前端

- `AiController`：POST /api/v1/ai/queries（自然语言查询 → 完整结果对象）、POST /api/v1/ai/analyses（基于 evidence 解释）
- 前端 `AiAssistant.vue` 接入：问题输入 → 推荐问题 → 执行状态（理解/生成/校验/查询/解释）→ 结论+依据+SQL 证据折叠
- 验收：mvn test 全绿；MockLLM 全链冒烟（查询→校验→执行→解释→审计行）；降级模式验证；build 前端；提交

**验收（本轮完成定义）：** 危险 SQL 100% 拦截（含注入/删除/越权样例）；合法自然语言查询经"生成→校验→执行→解释"闭环返回证据链结果；审计表有记录；无 key 时规则回退仍可查询推荐问题；页面可展示证据（SQL+行数+耗时）。