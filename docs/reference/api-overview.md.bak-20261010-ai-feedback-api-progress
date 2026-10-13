# API 总览（/api/v1，2026-09-11 对齐 R8 真实实现）

> §24 接口约定：路径统一 `/api/v1`；响应 `{code, message, data, traceId}`；写操作支持 `Idempotency-Key`
> 头；日期 ISO-8601；金额字符串/精确十进制。错误 `{code, message, traceId, retryable}`（§23.2）。
>
> 本文只写**已实现并被实测覆盖**的端点；权限列是方法级 `@RequiresPermission` 的真实取值（§21.1）。
> 平台 8091（分析+AI+决策+运维）、商城 8090（模拟商城，独立库、无平台端点）——两者除登录接口同形外无共享。

## 0. 认证与权限（§21.1/§21.2）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | /api/v1/auth/login | 匿名 | `{username,password}` → `data.{token,username,role,realName}`；`admin/admin123`、`operator/operator123`、`analyst/analyst123` |
| POST | /api/v1/auth/logout | 登录 | 失效服务端会话 |
| GET | /api/v1/auth/me | 登录 | 当前用户与角色 |

- 鉴权头只认 `Authorization: Bearer <token>`；**不存在 `X-User-Id` 回退**（§21.2，缺失/伪造 → 401）。
- 无 token → `401 UNAUTHORIZED`；有 token 但权限不足 → `403 FORBIDDEN_PERMISSION`（`message` 附所需权限码）。
- 角色 → 权限矩阵（`RolePermissions`）：

| permissionCode | admin | data_dev | operator | analyst |
|---|---:|---:|---:|---:|
| user:manage | ✓ | | | |
| runtime:manage | ✓ | ✓ | | |
| pipeline:run | ✓ | ✓ | ✓ | |
| ops:log:view | ✓ | ✓ | ✓ | |
| dashboard:view | ✓ | ✓ | ✓ | ✓ |
| ai:query | ✓ | ✓ | ✓ | ✓ |
| decision:create | ✓ | ✓ | ✓ | ✓ |
| decision:approve | ✓ | | ✓ | |
| ai:audit:view | ✓ | | | |

## 1. 分析看板与指标（dashboard:view）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/v1/dashboards/overview | 大盘：`data.{snapshotId,businessTime,dataUpdatedAt,definitionVersion,qualityStatus,data,warnings}` |
| GET | /api/v1/analysis/sales?from&to&snapshotId | 销售趋势（按日） |
| GET | /api/v1/analysis/products?topN&from&to&snapshotId | 商品热度 TopN |
| GET | /api/v1/analysis/funnel?date&snapshotId | 四阶段漏斗（view/intent/order/pay） |
| GET | /api/v1/analysis/users?from&to&snapshotId | 活跃趋势（DAU/行为量） |
| GET | /api/v1/analysis/rfm?snapshotId | RFM 8 类分层（`amount` 缺列时如实为 null + `RFM_AMOUNT_UNAVAILABLE`） |
| GET | /api/v1/metrics/overview | 指标查询（默认最新 ACTIVE 快照；`?snapshotId=` 指定，**不存在不回退 ACTIVE**） |
| GET | /api/v1/metrics/snapshots | 快照列表（BUILDING/VERIFYING/ACTIVE/ARCHIVED/FAILED） |
| GET | /api/v1/metrics/health | 指标服务健康检查（无需权限） |

`snapshotId` 一律可选；降级（缺维表/缺列）走 `warnings`，**不造数**（如 `UNKNOWN_DIMENSION_TABLE`、`UNKNOWN_SNAPSHOT`）。

## 2. AI 智能分析（ai:query）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/ai/explanations | 证据包 + 六段解释（R8-1/R8-3）；body 可选 `{snapshotId,timeRange,question}` |
| POST | /api/v1/ai/queries | 自然语言问数：受控 Text-to-SQL + 执行 + 证据摘要（§19.4/§19.6） |
| POST | /api/v1/ai/analyses | 仅解释（不返回证据包全量） |
| GET | /api/v1/ai/history/my?limit | 我的最近问答（登录用户自己的历史） |
| GET | /api/v1/ai/audit/history?limit | 全量问数审计（**ai:audit:view**，analyst/operator 403） |
| GET | /api/v1/ai/audit/calls?limit | 模型调用日志（**ai:audit:view**） |

`/ai/explanations` →

```jsonc
{ "code":"OK", "data": {
  "evidence":  { /* EvidencePackage v1 全量，字段见 docs/contracts/r8-evidence-security-decision.md §1 */ },
  "narrative": { "summary":"…", "sections":[{"title":"…","lines":["…"]}],   // 固定 6 段
                 "limitations":["…"], "providerUsed":"template|llm", "templateVersion":"evidence_v1" } },
  "traceId":"…" }
```

`/ai/queries` →

```jsonc
{ "code":"OK", "data": {
  "query":       { "status":"EXECUTED|REJECTED|FAILED", "sql":"…", "tables":["ads_*"], "rowsReturned":4,
                   "elapsedMs":12, "rows":[…], "errorCode":null, "assumptions":[…] },
  "explanation": { "summary":"…", "facts":[…], "limitations":[…] },
  "evidenceSummary": "快照 S20260901_24（业务日 2026-09-01）…",
  "evidenceId": "EV-20260911-3f2a9c" }, "traceId":"…" }
```

安全硬约束（§19.5，`SqlSafetyValidator` + `QueryCostGuard`）：单条 `SELECT`、单表（禁 JOIN/UNION/子查询/CTE）、
禁 `SELECT *`、列名/函数白名单、必须 `snapshot_id = '<ACTIVE>'` + `dt` 范围（≤90 天）、自动 `LIMIT 200`、
`EXPLAIN` 预估超阈值拒绝；执行用只读账号（`metric_read`，DB 层仅 SELECT）+ `readOnly` + 30s 超时 + `maxRows=200`。
被拒也落 `ai_query_history`（`status=REJECTED` + 规则码），全量动作落 `operation_audit_log`（§21.4，不存问题原文只存哈希）。

## 3. 决策中心（§20）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | /api/v1/decisions | decision:create | AI 建议创建草稿（`source=ai` **强制 DRAFT**） |
| POST | /api/v1/decisions/{id}/submit | decision:create | 提交审批（`action/owner/目标/窗口/证据` 不齐 → 400 `PARAM_INVALID`） |
| POST | /api/v1/decisions/{id}/approve \| reject | decision:approve | 审批（reject 必须带 `reason`） |
| POST | /api/v1/decisions/{id}/start \| complete \| cancel | decision:create | 执行推进（cancel 必须带 `reason`） |
| POST | /api/v1/decisions/{id}/evaluate | decision:create | 效果评价：等长窗口 + 前/后快照，数据不足 → `INSUFFICIENT_DATA` |
| GET | /api/v1/decisions?limit | dashboard:view | 决策列表 |
| GET | /api/v1/decisions/{id}/evaluations | dashboard:view | 评价历史 |

**12 态**状态机（`DecisionStateMachine`）：`DRAFT → PENDING_REVIEW → APPROVED → IN_PROGRESS → COMPLETED → EVALUATING`
→ 终态 `EFFECTIVE / PARTIAL / INEFFECTIVE / INSUFFICIENT_DATA`；审核前可 `REJECTED`，执行中可 `CANCELLED`（必须原因）。
非法流转（如 DRAFT 直接 start）→ 4xx 且**同样留审计 FAILED 行**；审批人/创建人取登录会话，不可伪造。

## 4. 运维与流水线（ops:log:view / pipeline:run / runtime:manage / user:manage）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | /api/v1/ingestion/status | ops:log:view | 采集状态（待采文件/断点/最新批次）。**B-08/D-022（2026-09-11）**：另有 `newFileCount`=**还有可采集完整行**的文件数（≠ 整目录累计 `pendingFiles`，也≠ 已采完的文件）与 `lastArrivalAt`=landing 目录最新文件到达时间（无文件时为 `null`）；两者共同回答"平台多久没收到数据"，**不代表**数据源存活判定（不探活生产者，D-002） |
| POST | /api/v1/ingestion/runs | pipeline:run | 手动采集一轮（断点续采）。**B-08/D-022**：响应增 `noNewData`——本次**没读到任何新字节**即 `true`（此时 `recordCount=0`、批次仍如实记 `SUCCESS`）；尾部无换行的残行按 Taildir 语义**不算**新数据、等待写全（DEF-12） |
| GET | /api/v1/ingestion/batches | ops:log:view | 批次列表 |
| POST | /api/v1/pipeline-runs | pipeline:run | 创建流水线（**8 阶段**：WAIT_LANDING→INIT_SCHEMA→LOAD_ODS→BUILD_DWD→BUILD_DWS→BUILD_ADS→QUALITY_CHECK→PUBLISH_METRIC；支持 Idempotency-Key） |
| GET | /api/v1/pipeline-runs/{id} | ops:log:view | 阶段明细（含 evidence/records/errorCode） |
| GET | /api/v1/pipeline-runs | ops:log:view | 运行列表 |
| POST | /api/v1/pipeline-runs/{id}/retry | pipeline:run | 失败重试（attempt_no+1） |
| POST | /api/v1/admin/pipeline-runs/{id}/resume \| mark-failed \| retry-from-stage | pipeline:run | 恢复/人工判死/从阶段重跑 |
| GET | /api/v1/admin/pipeline-runs/recovery-report | ops:log:view | 恢复报告 |
| GET | /api/v1/metrics/quality | ops:log:view | 数据质量结果（层/严重度/快照） |
| GET/POST/PUT | /api/v1/runtime-profiles | runtime:manage | 运行时配置（含 `spark_submit_path`、`/test`、`/activate`、`/disable`） |
| GET | /api/v1/health | 匿名 | 存活探针 |
| GET/POST | /api/v1/admin/users | user:manage | 用户管理（列表/新增/`{id}/toggle`/`{id}/reset-password`；参数错误 400，不伪装 500） |

## 5. 模拟商城（localhost:8090，独立库）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/auth/login \| logout | 商城登录/登出（`admin/admin123`） |
| GET | /api/v1/auth/me | 当前商城用户 |
| POST | /api/v1/mall/users | 注册（发 `user_registered` 事件） |
| GET | /api/v1/mall/products \| /products/{productId} | 商品列表（分类筛选）/ 详情 |
| POST/GET | /api/v1/mall/cart/items | 加购 / 查看购物车 |
| POST | /api/v1/mall/orders | 下单（事务写 order + outbox） |
| GET | /api/v1/mall/orders | 我的订单 |
| POST | /api/v1/mall/orders/{id}/pay \| cancel \| refunds | 支付/取消/申请退款 |
| POST | /api/v1/mall/refunds/{id}/complete | 完成退款 |
| GET | /api/v1/mall/outbox/status | 未发布事件数/最新滚动文件 |
| POST | /api/v1/mall/outbox/publish | 手动触发发布 |
| GET/POST | /api/v1/admin/products | 商品管理（`/{id}/price`、`/{id}/stock`、`/{id}/status`） |
| GET/POST | /api/v1/admin/users | 商城用户管理（`/{id}/toggle`、`/{id}/reset-password`） |

**已退役（M1-7，2026-09-11）**：`/api/v1/generator/runs`、`/api/v1/generator/scenarios` 及商城前端"数据生成"页
**已删除**——场景化造数归第三个程序 `synthetic-data-generator`（端口 8092，见 `contract-specs/openapi/generator-api.v1.yaml`）。
商城侧只剩"下单/加购/退款/发布 outbox"的正常经营行为，实测 `GET /api/v1/generator/scenarios` → **404**（§3.4-2 验收项）。

商城与平台**不共享 cookie/localStorage 键**（`mall_token` vs `analytics_token`），交叉端口调用被两侧
boundary 测试与 DOM 验收常驻守卫（8090 对平台端点一律 401）。

## 6. 错误码（§23.2 分类）

| code | HTTP | 类别 | retryable | 说明 |
|---|---|---:|---|---|
| UNAUTHORIZED | 401 | 安全 | false | 未登录/令牌失效 |
| FORBIDDEN_PERMISSION | 403 | 安全 | false | 权限码不足（message 附所需码） |
| PARAM_INVALID | 400 | 参数 | false | 请求参数/状态流转非法（含**请求体缺失/非 JSON**） |
| FORBIDDEN_OPERATION | 400 | 业务 | false | 业务守卫（如停用当前登录账号） |
| DECISION_STATE_ILLEGAL | 400 | 业务 | false | 决策状态机非法流转（如 DRAFT 直接 start） |
| UNSUPPORTED_MEDIA_TYPE | 415 | 参数 | false | `Content-Type` 不是 `application/json`（写接口） |
| METHOD_NOT_ALLOWED | 405 | 参数 | false | 方法不支持（如对只读端点用 POST） |
| SQL_REJECTED / SQL_COST_TOO_HIGH / SQL_PARSE_ERROR / SQL_DATE_OUT_OF_SCOPE | 200（`query.status=REJECTED`） | 安全 | false | AI 问数被 AST/成本/日期口径规则拒绝，已入审计 |
| SQL_QUESTION_UNSAFE | 200（`query.status=REJECTED`，`errors` 列留痕） | 安全 | false | **问句层**注入筛命中（DML/DDL/粘贴 SQL/注入噪声），生成 SQL 之前即拒绝 |
| UNKNOWN_SNAPSHOT / UNKNOWN_DIMENSION_TABLE / RFM_AMOUNT_UNAVAILABLE | 200（`warnings`） | 数据 | false | 如实降级，不造数 |
| PIPELINE_QUALITY_FAILED | 200/4xx | 数据质量 | false | 质量门未过，指标未发布（保旧快照） |
| RUN_EMPTY_LANDING / RUN_EMPTY_DATA | 200/4xx | 数据质量 | true（补数据后） | 无可用数据 |
| USER_NOT_FOUND | 4xx | 业务 | false | 平台用户域（账号不存在）；**商城域错误码**（商品/库存/订单/退款）由商城程序自己定义与返回，平台不复制、不映射（M1-6/AE-04 已删除平台侧那 8 个常量，映射改由每源配置承载） |
| LLM 相关（AUTH/NETWORK/TIMEOUT） | 200（`providerUsed=template`） | 外部 | true（限次） | 模型失败 → 固定模板，绝不 5xx |
