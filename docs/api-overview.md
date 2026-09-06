# API 总览（/api/v1）

> §24 接口约定：路径统一 `/api/v1`；响应 `{code, message, data, traceId}`；写操作支持 `Idempotency-Key`
> 头；日期 ISO-8601；金额字符串/精确十进制。错误 `{code, message, traceId, retryable}`（§23.2）。

## 模拟商城（localhost:8090）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/mall/users | 注册（发送 user_registered 事件） |
| GET | /api/v1/mall/products | 商品列表（分类筛选） |
| POST | /api/v1/mall/cart/items | 加购 |
| POST | /api/v1/mall/orders | 下单（事务写 order+outbox） |
| POST | /api/v1/mall/orders/{id}/pay | 支付 |
| POST | /api/v1/mall/orders/{id}/cancel | 取消（释放库存） |
| POST | /api/v1/mall/orders/{id}/refunds | 申请退款 |
| POST | /api/v1/mall/refunds/{id}/complete | 完成退款 |
| GET | /api/v1/mall/outbox/status | 未发布事件数/最新滚动文件 |
| POST | /api/v1/mall/outbox/publish | 手动触发发布 |

## 生成器 / 采集

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/generator/runs | 场景化生成（userCount/eventsPerSecond/窗口/种子/场景/脏数据比例） |
| GET | /api/v1/generator/scenarios | 11 个场景与预期方向 |
| GET | /api/v1/ingestion/status | 采集状态（待采文件/断点数/最新批次） |
| POST | /api/v1/ingestion/runs | 手动采集一轮（断点续采） |
| GET | /api/v1/ingestion/batches | 批次列表 |

## 流水线 / 指标

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/pipeline-runs | 创建流水线（7 阶段；支持 Idempotency-Key） |
| GET | /api/v1/pipeline-runs/{id} | 阶段明细 |
| POST | /api/v1/pipeline-runs/{id}/retry | 失败重试（attempt_no+1） |
| GET | /api/v1/metrics/overview | 指标查询（默认最新 ACTIVE 快照） |
| GET | /api/v1/metrics/snapshots | 快照列表（BUILDING/VERIFYING/ACTIVE/ARCHIVED/FAILED） |
| GET | /api/v1/metrics/health | 指标服务健康检查 |

## 分析看板（普通员工）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/v1/dashboards/overview | 大盘（快照指标 + 近 7 日销售/活跃趋势） |
| GET | /api/v1/analysis/sales?from&to | 销售趋势（订单/金额/买家，按日） |
| GET | /api/v1/analysis/products?topN&from&to | 商品热度 TopN（§21.7 对数权重） |
| GET | /api/v1/analysis/funnel?date | 宽松用户漏斗四阶段 |
| GET | /api/v1/analysis/users?from&to | 活跃趋势（DAU/行为量） |

## AI 智能分析

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/ai/queries | 自然语言问答：受控 SQL + 执行 + 证据解释（完整链路） |
| POST | /api/v1/ai/analyses | 仅证据解释 |

AI 查询响应结构：`{query:{status,sql,tables,rowsReturned,elapsedMs,rows,assumptions,providerUsed},
explanation:{summary,facts[],possibleCauses[],suggestions[],limitations[],evidence:{snapshotId,sql,...}}}`。

## 决策中心

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/decisions | AI 创建草稿（强制 DRAFT） |
| POST | /api/v1/decisions/{id}/submit \| approve \| reject \| start \| complete \| cancel \| evaluate | 状态推进（12 态状态机） |
| GET | /api/v1/decisions | 决策列表 |
| GET | /api/v1/decisions/{id}/evaluations | 效果评价 |

## 错误码示例（§23.2 分类）

| code | 类别 | retryable | 说明 |
|---|---|---|---|
| PIPELINE_QUALITY_FAILED | DATA_QUALITY | false | 金额对账未通过，指标未发布 |
| RUN_EMPTY_LANDING / RUN_EMPTY_DATA | DATA_QUALITY | true（补数据后） | 无可用数据 |
| USER_NOT_FOUND / ORDER_STATE_ILLEGAL 等 | 业务 | false | 商城域错误 |
| LLM 相关（AUTH/NETWORK/TIMEOUT） | AI_FORMAT/外部 | true（限次） | 模型调用失败，规则回退或重试 |
| 安全拦截 | SECURITY | false | 危险 SQL/越权，入审计