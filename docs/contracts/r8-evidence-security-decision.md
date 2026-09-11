# R8 契约：证据包 / 安全问数 / 身份与决策（v1，2026-09-11 冻结）

依据：`docs/项目完整实施指导书 V2.0.md` §19（AI 辅助体系）、§20（决策中心）、§21（权限/安全/审计）、
§22.4（API）、§24.7/24.8/24.9（R8-1/2/3 工作清单）、§25（类改造导航）。
本文是 R8 三个子阶段的**接口冻结**：三个并行工作面（R8-1 证据包、R8-2 安全问数、R8-3 身份与决策）
各自只改自己名下的文件，跨面调用只走本文固定的类型与签名。

## 1. R8-1：EvidencePackage v1（§19.2）

不可变结构，**AI 不重新计算数字**：所有数值都从快照/ADS 读取后原样装入，页面与解释共用同一份。

```jsonc
{
  "evidenceId": "EV-20260911-3f2a9c",          // 本次证据包唯一 ID
  "templateVersion": "evidence_v1",             // 版本化模板（§24.7）
  "snapshotId": "S20260901_24",                 // ACTIVE 快照
  "definitionVersion": "v2",                    // 指标口径版本
  "generatedAt": "2026-09-11T09:12:33",
  "period":       {"from": "2026-09-01", "to": "2026-09-01"},
  "currentPeriod":    {"from": "2026-09-01", "to": "2026-09-01"},
  "comparisonPeriod": {"from": "2026-08-31", "to": "2026-08-31"},   // 等长上期；无数据则 null
  "facts": [ {"metricCode":"sale_amount","value":"2042.00","unit":"元",
              "period":{"from":"2026-09-01","to":"2026-09-01"},
              "evidenceRef":"ads_operation_overview_m.sale_amount@S20260901_24"} ],
  "comparisons": [ {"metricCode":"sale_amount","current":"2042.00","baseline":null,
                    "delta":null,"deltaRate":null,"evidenceRef":"..."} ],
  "dimensions": { "product": [ {"key":"1","label":"机械键盘","value":"1200.00","share":"0.5876","metricCode":"sale_amount","evidenceRef":"..."} ],
                  "category": [], "region": [], "channel": [] },   // 无表 → 空数组 + 警告
  "anomalies": [ {"ruleCode":"REFUND_RATE_HIGH","metricCode":"refund_rate","severity":"MEDIUM",
                  "observed":"0.6000","threshold":"0.3000","deviation":"0.3000",
                  "statement":"退款率高于阈值，属可能相关，不构成因果","evidenceRef":"..."} ],
  "dataQuality": {"status":"PASS","ruleTotal":4,"rulePassed":3,
                  "failedRules":["EVENT_ID_UNIQUE"],"lateRate":null,"warnings":[]},
  "lineage": {"adsTables":["ads_operation_overview_m"],"metricTables":["metric_value","metric_snapshot"],
              "pipelineRunId":21,"snapshotId":"S20260901_24"},
  "warnings": []
}
```

规则：

1. 每条 `facts/comparisons/dimensions/anomalies` 必须带 `evidenceRef`（`表.列@快照` 或 `指标编码@快照`）。
2. `comparisons` 的 `delta/deltaRate` 由**基线数据是否可用**决定；基线缺失时为 `null` 且 `warnings` 记 `NO_COMPARISON_PERIOD`，
   不得用 0 冒充（与 §20.4「数据不足不得归类为无效」同精神）。
3. 维度贡献只登记**ADS 里真实存在的维度**：无分类/地区 ADS 时 `category/region` 为 `[]` 且 `warnings` 记 `UNKNOWN_DIMENSION_TABLE`。
4. `anomalies` 只产生**候选异常 + 阈值偏离**，文案必须含「可能相关，不构成因果」（§19.1）。
5. 证据包**不落库为文本**，只落 `evidenceId` + `templateVersion`（决策任务 `evidence_package_id` 字段引用）。

Java 接口（**R8-1 名下，其他面只调用不修改**）：

```java
package com.graduation.analytics.ai.evidence;

public record EvidenceRequest(String snapshotId,      // null → ACTIVE
                              String question,
                              String timeRange,       // 允许 "yyyy-MM-dd" / "yyyy-MM-dd~yyyy-MM-dd" / null→快照业务日
                              String requestedBy) {}

public record EvidencePackage(...) { /* 上述字段；Jackson 可序列化 */ }

public final class EvidenceService {
    public EvidencePackage build(EvidenceRequest req);          // 只读快照/ADS，不调 LLM
    public ExplanationResult explain(EvidencePackage pkg, String question); // 模板优先，LLM 仅改写
    public EvidencePackage latest();                            // 供 /ai/explanations 无参调用
}
```

固定模板（§19.3，**LLM 不可用也必须输出**）：①发生了什么 ②与上期相比 ③哪些维度贡献最大
④数据质量是否可信 ⑤可采取哪些核查/行动 ⑥有哪些限制。LLM 失败 → 返回模板结果，`providerUsed="template"`。

## 2. R8-2：安全问数（§19.4/19.5、§24.8）

### 2.1 语义目录只登记已发布 MySQL ADS（§24.8 第一条）

允许表（`analytics_metric`，全部来自 `db/metric/V*.sql` 已发布 ADS）：
`ads_operation_overview_m`、`ads_sale_trend_m`、`ads_behavior_funnel_m`、`ads_hot_product_m`、`ads_product_conversion_m`。
`AiSqlDriftTest` 常驻校验目录字段与迁移文件一致。

### 2.2 日期与快照必须参数化注入（§19.5，禁止 `MAX(...)` 子查询）

```java
package com.graduation.analytics.ai.sql;

public record AiScope(String snapshotId, String definitionVersion,
                      java.time.LocalDate businessDate,   // ACTIVE 快照业务日
                      java.time.LocalDate minAllowedDate, // businessDate-89
                      int maxScanDays, int rowLimit) {}

public class AiScopeResolver {                 // 走 metricReadDataSource（metric_read 账号）
    public AiScope resolve();                  // SELECT snapshot_id, business_time, definition_version
}                                              //   FROM metric_snapshot WHERE status='ACTIVE'
```

生成的 SQL 里 `snapshot_id` / `dt` **必须是字面量**（由 `AiScopeResolver` 注入后再校验），
不得出现 `(SELECT MAX(snapshot_id) ...)`、`(SELECT MAX(dt) ...)`。目录 few-shot 同步改为参数化示例。

### 2.3 校验器硬规则（`SqlPolicy` + `SqlSafetyValidator`）

| 规则 | 判据 |
|---|---|
| 单语句 | 仅一个 `SELECT`；禁 INSERT/UPDATE/DELETE/DDL/CALL/SET、多语句、注释绕过 |
| 单表 | **禁 JOIN / UNION / 子查询 / CTE / 窗口外表**（比原「最多 3 表」更严，§19.5 第一阶段） |
| 列白名单 | **递归**遍历 `SELECT / WHERE / GROUP BY / ORDER BY / HAVING` 及**函数实参**中的每个 `Column` 节点，全部命中目录 |
| 禁 `SELECT *` | 显式列 |
| 函数白名单 | `SUM,AVG,COUNT,MIN,MAX,ROUND,ABS,COALESCE,IFNULL,FLOOR,CEIL,DATE,CAST`（大小写无关） |
| 日期范围 | WHERE 必须含 `dt >= ? AND dt <= ?` 形式（字面量，且落在 `[minAllowedDate, businessDate]`），扫描 ≤90 天 |
| 快照钉住 | WHERE 必须含 `snapshot_id = '<ACTIVE>'` |
| LIMIT | 自动补 `LIMIT 200`；>200 直接改写为 200（`rowLimit=200`） |
| EXPLAIN 成本 | `QueryCostGuard`：`EXPLAIN <sql>` 预估扫描行 > `ai.sql.max-explain-rows`（默认 500000）→ 拒绝 `SQL_COST_TOO_HIGH` |
| 执行 | `metricReadDataSource` + `conn.setReadOnly(true)` + `setQueryTimeout(30)` + `setMaxRows(200)`；缺源 fail-closed（现状保留） |

审计：`ai_query_history` 增记 `tables`、`snapshotId`、`scope_min_date`、`scope_max_date`、`explain_rows`；
拒绝也写一行（`status=REJECTED`，`errors=<规则码>`）。

### 2.4 验收（§24.8 末条：攻击集与越权集全部通过）

`AiSqlSecurityTest`（单测，强制全绿）至少覆盖：多语句、注释绕过、DDL/DML、`JOIN`、子查询、CTE、`UNION`、
`SELECT *`、未白名单列、未白名单函数、`information_schema`、无日期条件、超 90 天、超范围日期、
非 ACTIVE 快照、`LIMIT 100000` 改写、EXPLAIN 超阈值、空 SQL。
越权集：`analyst` 调 `/api/v1/ai/audit/*` → 403；`operator` 调 `/api/v1/admin/*` → 403（R8-3 提供）。

### 2.5 实现口径登记（2026-09-11 追加，R8-2 完工后由主会话固化）

契约 §2.3 只写了阈值，以下四条是实现者定下的口径，**在此固化以免后续被当成"实现跑偏"**，
也避免第二个人再写一套：

1. **EXPLAIN 行数口径**：>1 张表取各表 `rows` 之和；单表多行计划取**乘积**（最坏情况，偏保守）；
   缺 `rows` 列 / 不可解析 / 负值 / 空计划 / EXPLAIN 抛异常一律**拒绝执行**（fail-closed）。
   代价：复杂计划估算偏大时可能误拒——接受，宁可拒也不放行。
2. **EXPLAIN 只读第一个结果集**（`SqlExecutor.explain`）：MySQL 8 的 `EXPLAIN` 单结果集够用；
   若改成 `EXPLAIN FORMAT=JSON` 或分区表多结果集，此处必须同步改。
3. **规则回退模板的时间窗口**：漏斗/热销/概览取快照业务日**单日**，趋势取 `[业务日-6, 业务日]` 共 **7 天**。
   校验器只保证 ≤90 天且落在允许区间内，**没有规则强制"趋势必须是 7 天"**——这是语义选择，不是契约要求。
4. **为测试放宽的可见性**：`AiScopeResolver(JdbcTemplate)`、`QueryCostGuard(SqlExecutor, JdbcTemplate, long)`
   两个构造器是 `public`（测试在 `...ai` 包、类在 `...ai.sql` 包，包私有访问不到）。
   生产注入路径不变，属于**已知的可见性放宽**，不是第二套入口。

已知真 bug 修复记录（勿回退）：JSqlParser 4.9 的 `Select#getPlainSelect()` / `getSetOperationList()`
在类型不符时**抛 `ClassCastException`** 而非返回 null，必须用 `instanceof SetOperationList` 判分支
（`SqlSafetyValidator:120-126`），否则所有普通 SELECT 都会被误判成 `SQL_PARSE_ERROR`。

### 2.6 R8-3 实现口径登记（2026-09-11，与指导书字面不同但有意为之）

5. **决策状态名 `PENDING_REVIEW` ≠ 指导书 §20.3 的 `PENDING_APPROVAL`**：状态机（R7 期落地，12 态：
   `DRAFT/PENDING_REVIEW/APPROVED/IN_PROGRESS/COMPLETED/EVALUATING/EFFECTIVE/PARTIAL/INEFFECTIVE/INSUFFICIENT_DATA/REJECTED/CANCELLED`）
   与前端、既有数据行都用 `PENDING_REVIEW`。**语义完全相同**，改名会波及已落库行与前端映射，
   收益为零 → 保留实现侧命名，此处登记为**命名偏差**（不是缺状态）。
6. **效果评价按快照粒度观测**：`MetricStore` 没有"日期区间聚合"API，故 `baseline/actual` 取**窗口端点快照**的
   指标值 + `sample_count`（快照数）如实记录，`window_start/window_end` 仍是等长窗口的**语义边界**。
   窗口内样本不足 → `INSUFFICIENT_DATA`（不判 INEFFECTIVE）。契约 §3.4 的公式在"快照即日粒度"下等价。
7. **`providerUsed` 是推断值**：`ExplanationResult` 无 provider 字段，`AiController` 用
   "最终摘要 == 模板摘要 → `template`，否则 `llm`"判定，覆盖"模型不可用"与"模型改写被数值守卫拒绝"两种情形。
   若 `ai/**` 后续暴露 provider 字段，应改直读并删除推断。
8. **`UserAdminController` 的包名与目录不一致**：类在 `com.graduation.analytics.auth` 包但文件位于
   `controller/` 目录（历史迁移残留）。功能无影响，登记为**目录/包名偏差**，重排包结构时一并修。
9. **`ai_query_history.user_id` 保留 V3 的 `DEFAULT 'demo'`**：V14 只加列不改默认值；平台写入路径始终显式传
   真实用户（R8-3 验收 ⑤ 已实测伪造头无效），默认值仅对"绕过平台直连 MySQL 插入"生效 → 保留。
10. **审计写入 fail-closed 是刻意的**：决策/用户管理/AI 问数写审计失败即整体失败（宁可 5xx 也不留无痕动作）；
    唯一例外是只读的 `/ai/explanations` 在审计表缺失时降级为告警（只读接口不因运维表缺失而不可用）。

### 2.7 主会话收口登记（2026-09-11 真机验收后追加）

11. **ADS 日期列口径 = 紧凑 `yyyyMMdd`，唯一所有者 `AiScope.DT_FORMAT`**（**真机事故修复，勿回退**）：
    `analytics_metric` 的 ADS 镜像表 `dt` / `last_active_date` / `last_buy_date` / `calc_date` 都是
    `varchar`，**存紧凑 `20260901`**，不是 ISO `2026-09-01`。修复前 AI 生成的是 ISO 区间
    （`dt >= '2026-08-26' AND dt <= '2026-09-01'`），字符串比较下上界恒假 → **四类问法全部静默 0 行**
    （HTTP 200、`status=EXECUTED`、`rowsReturned=0`，不报错——最危险的一种失败）。
    现在生成器（`SemanticCatalog` 少样本 / `RuleBasedSqlFallback` 模板 / `TextToSqlService` 提示词与修复提示）、
    校验器（`SqlSafetyValidator.parseStrictDate` 只认 `\d{8}`）、`AiScope.dtFrom()/dtTo()` 全部引用同一常量；
    **ISO 字面量一律拒绝**（`SQL_DATE_OUT_OF_SCOPE`，loud 失败），并由 `AiSqlSecurityTest.dt字面量格式唯一`
    与 `AiSqlDriftTest.AI查询必须带参数化日期区间` 常驻守卫。
    注意：审计列 `ai_query_history.scope_min_date/scope_max_date` 是 DB `DATE`，仍写 ISO（`scope.businessDate()`）。
12. **问句层注入筛 `QuestionSafetyScreen` 与 AST 校验是两道独立防线**（§19.5 补强）：
    指导书 §19.5 只规定 SQL 生成后的 AST 校验。真机验收发现：攻击问法（如"删除所有指标数据"）经规则回退层
    被**无害化改写成一条普通 SELECT**，于是 `status=EXECUTED`、AST 校验器从未被触发——攻击集"通过"是假绿。
    现在在**生成 SQL 之前**先过问句筛（DML/DDL 关键词、粘贴 SQL、注入噪声、中文写动词），命中即
    `status=REJECTED`、`errors=[SQL_QUESTION_UNSAFE] …`、不落 `sql_text`，且仍在 `finally` 写审计行
    （拒绝也要留痕）。筛选在 `scopeResolver.resolve()` **之后**执行，使被拒行仍带快照与日期作用域便于取证。
13. **HTTP 层错误映射（本轮补的三条，避免"参数错误伪装 500"）**：请求体缺失/非 JSON →
    `400 PARAM_INVALID`；`Content-Type` 不是 `application/json` → `415 UNSUPPORTED_MEDIA_TYPE`；
    方法不支持 → `405 METHOD_NOT_ALLOWED`（新增常量见 `platform-common/PlatformBizException`；
    该文件在 R8 时名为 `MallBizException`，2026-09-11 M1-6/AE-04 改名，见决策记录 D-023）。
    未捕获异常仍 `500 INTERNAL`，但日志前缀已由误导性的 "mall internal error" 改为
    `unhandled server error [METHOD /path]`（只记方法+路径，不带 query，凭据不入日志）。
14. **`ai_query_history.user_id` 是 `varchar(64)`，存的是用户名**（`analyst`），不是 `sys_user.id` 数字。
    验收脚本与排查口径按用户名比对；伪造 `X-User-Id` 头对该列无影响（R8 验收 A4 实测）。
15. **阶段证据列宽回归（跨阶段缺陷，2026-09-11 真机复现并修复）**：`pipeline_stage_run.evidence` 在 V9
    里是 `VARCHAR(4000)`，代码侧"超长截断"用 `substring` 切在字符串中间 → 落库的是**非法 JSON**；
    重试/恢复路径 `stageEvidence()` 解析失败后**静默退化成空 Map** → `PUBLISH_METRIC` 报
    `RUN_PUBLISH_NO_ADS`「缺少 BUILD_ADS 真实作业证据，拒绝发布」（实测 `pipeline_run` 21/22 两次真实发布失败，
    证据长度恰好停在 4000）。修复：V15 列型改 `MEDIUMTEXT`；`PipelineService.evidenceJson` 改为**结构化缩减**
    （逐列表限量 + `_evidenceTruncated` 标注），无论怎么截都保持合法 JSON；恢复审计追加上限同样随列宽取值。
    真机证据：`.verify/r8-evidence-truncation-proof.ps1` 对 run 22 执行 `retry-from-stage BUILD_ADS`
    重跑真实 Spark 作业 → 证据 >4000 字符且可解析、`PUBLISH_METRIC` **SUCCESS**、快照切换 `ACTIVE`（P1–P6 全绿）。


## 3. R8-3：身份与决策（§20、§21、§24.9）

### 3.1 身份：删除 `X-User-Id` 回退（§21.2）

`AiController` / `DecisionController` 及任何控制器**不得**出现 `X-User-Id` 或 `"demo"` 回退；
用户一律取 `CurrentUserHolder.get()`；缺失即由 `AuthInterceptor` 401。
`afterCompletion` 清理 ThreadLocal（现状保留）。

### 3.2 permissionCode RBAC（§21.1、§24.9）

不再用 URL 前缀猜角色。`permissionCode` 常量与角色矩阵：

| permissionCode | admin | data_dev | operator | analyst |
|---|---:|---:|---:|---:|
| `user:manage` | ✓ | | | |
| `runtime:manage` | ✓ | ✓ | | |
| `pipeline:run` | ✓ | ✓ | ✓（只运行） | |
| `ops:log:view` | ✓ | ✓ | ✓（只读摘要） | |
| `dashboard:view` | ✓ | ✓ | ✓ | ✓ |
| `ai:query` | ✓ | ✓ | ✓ | ✓ |
| `decision:create` | ✓ | ✓ | ✓ | ✓ |
| `decision:approve` | ✓ | | ✓（按授权） | |
| `ai:audit:view` | ✓ | | | |

实现：`PermissionCode`（常量）+ `RolePermissions`（矩阵）+ `@RequiresPermission("code")`（方法/类级）
+ `AuthInterceptor` 或 `PermissionInterceptor` 读取注解 → 无权限 403 `FORBIDDEN_PERMISSION`（附所需权限码）。
`AuthService` 的角色白名单补齐 `data_dev`（现有：admin/operator/analyst）。

### 3.3 决策：AI 只创建 DRAFT + 真实当前用户 + 审计（§20.3、§21.4）

- `source=ai` 只能落 `DRAFT`（服务层强制，非法状态直接拒绝）。
- 提交审批前校验 `action/owner/target/window/evidence` 齐全（缺 → `PARAM_INVALID`）。
- `approved_by / created_by / evaluated_by` 一律真实当前用户，**不可伪造**（无 `X-User-Id`）。
- REJECTED/CANCELLED 必须带原因。
- 全部动作写 `operation_audit_log`（§21.4）：`trace_id,user_id,role,action,resource_type,resource_id,before_digest,after_digest,reason,ip,result,created_at`。

### 3.4 效果评价：等长窗口 + 前后快照（§20.4、§24.9）

```
baseline = 决策批准前 N 天目标指标聚合（窗口 = [approvedDate-N+1, approvedDate]）
actual   = 决策完成后 N 天目标指标聚合（窗口 = [completedDate+1, completedDate+N]）
UP:   improvement = (actual - baseline) / |baseline|
DOWN: improvement = (baseline - actual) / |baseline|
```
- 保存 `baseline_snapshot_id` / `actual_snapshot_id`（前后快照）与 `window_start/window_end`、
  `baseline_period_value` / `actual_period_value` / `sample_count` / `definition_version`。
- `baseline=0` 或任一窗口样本不足 → `INSUFFICIENT_DATA`（**不得**归为 INEFFECTIVE）。
- 分级阈值可配置并记版本：达到目标 → EFFECTIVE；改善未达标 → PARTIAL；未改善 → INEFFECTIVE。
- 结论文案必须为「执行前后指标变化」，不得称因果（§20.4 末段）。

### 3.5 迁移（meta 库 V14）

```
ALTER TABLE decision_task      ADD baseline_snapshot_id, definition_version, evidence_package_id,
                                   approval_note, execution_note
ALTER TABLE decision_evaluation ADD baseline_snapshot_id, actual_snapshot_id, window_start, window_end,
                                   baseline_period_value, actual_period_value, sample_count, definition_version
CREATE TABLE operation_audit_log (...)
```

## 4. 工作面与文件归属（避免并行冲突）

| 面 | 拥有文件 | 交付判据 |
|---|---|---|
| R8-1（主会话） | `ai/evidence/**`（新）、`ai/ExplanationService.java` | 证据包字段齐全、模板六段可解析、无模型也完整、每数值有 evidenceRef |
| R8-2（子代理 A） | `ai/SemanticCatalog.java`、`ai/TextToSqlService.java`、`ai/RuleBasedSqlFallback.java`、`ai/sql/**`（含新 `SqlPolicy`/`AiScopeResolver`/`QueryCostGuard`）、`ai/test/**` | §2.3 表格全部规则 + 攻击集全绿 |
| R8-3（子代理 B） | `platform-app` 控制器（`AiController`/`DecisionController`/其他）、`auth/**`（`AuthInterceptor`/`PermissionCode`/`RolePermissions`/`AuthService`）、`decision/**`、`db/meta/V14__*` | 无 `X-User-Id`、permissionCode 矩阵、AI 仅 DRAFT、窗口评价 + 审计落库 |

集成（主会话在两面完成后）：`/ai/explanations` 接 `EvidenceService`、`/ai/queries` 响应内嵌证据包摘要、
RBAC 越权集端到端实测、DOM 回归（`.verify/r7-4-dom.py` 22 项不得退化）、`docs/remediation-status.md` 登记。
