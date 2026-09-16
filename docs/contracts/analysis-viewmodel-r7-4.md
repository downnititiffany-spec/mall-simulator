# R7-4 分析接口 ViewModel 契约（冻结版 v1）

> 依据：指导书 V2.0 §18.1 / §18.3 / §24.6。**本文件是 R7-4 后端与前端唯一契约**，
> 两侧实现必须逐字段对齐；若实现中确需改动，先改本文件再改代码。

> **v1.1（2026-09-16，S3-16 加性补充）**：按指导书 V3.0 §7 阶段4 L156 与设计 V3.0 §11.2 L435 /
> §11.4 L449 / §15 L676，`/analysis/users` 与 `/analysis/rfm` 补**消费已落库的 RFM 原值**
> （`r_days`/`f_count`/`m_amount`）与**观察窗口**（`period_start`/`period_end`）。
> **既有字段语义一律不变**；新增字段与两个新增降级编码（`RFM_RAW_VALUES_UNAVAILABLE`、
> `RFM_PERIOD_UNAVAILABLE`）均为加性，旧前端不读它们也不会误读。五列的落库链
> （Hive ADS → mxp 清单 → MySQL `V4` → Java 白名单）在 S2-06/S3-01 已完成，v1.1 只补读取侧。

> **v1.2（2026-09-16，S3-17 加性补充）**：按指导书 V3.0 §7 阶段4 **L156**「MetricStore/专题服务返回
> **明确 source**、snapshot、definitionVersion、时间和质量信息」与设计 V3.0 §11.1 **L414**「MetricValue：
> **source作用域**、snapshotId…」/ §17.6，统一信封补 **`source`** 字段（值 = `metric_snapshot.source`）。
> **既有字段语义一律不变**，新字段加性；旧前端不读它不会误读。
>
> **口径边界（不得越界表述，依 P2-04 裁决逐字）**：本字段是**发布方/生产者**标识
> （`metric_snapshot.source`，§17.6「成功快照只接受 `spark-ads`」），**不是**业务源身份
> —— `docs/acceptance/p2-04-dwd-source-projection-20260912/RULINGS-P2-04-20260912.md:40`
> 「其 `source` 列实测值为发布方 `spark-ads`，**不是**源身份 ⇒ 不得把 `source` 当源身份使用」；
> 业务源身份由 per-source warehouse namespace 与 ODS/DWD 的 `source_system`/`source_instance_id` 承载，
> 本期分析信封**不**提供源身份（缺口已登记，见 `PROJECT_STATUS` backlog）。

> **v1.3（2026-09-16，S3-18 加性补充）**：按指导书 V3.0 §7 阶段4 **L158**「异步任务返回标识，提供阶段/
> 失败/重试反馈；**分页**、限流、超时统一」与 §8 阶段4 完成标准（L200）「真实快照查询、权限/空态/错误、
> **分页正确**；任务请求不阻塞到 Spark 结束」，设计 V3.0 **L693**「根路径 `/api/v1`；响应
> `code/message/data/traceId`；**分页 `page`/`size`/`sort`**」与 **L675**「商品分析｜热度/销量/转化/退款、
> **稳定排行、分页**」：`/api/v1/analysis/products` 的**热度榜**改真分页（`page`/`size`），
> 排行**稳定化**（`rank_no` 升序、同 rank 以 `product_id` 升序打破平局）。
>
> **本版只落 L158 的「分页」一项**：`sort` 参数（设计 L693 同一行）与「限流」「超时统一」**本版未实现**，
> 缺口逐条登记在 `docs/PROJECT_STATUS.md` backlog，**不得**因本版发布而声称 L158 已满足。
>
> **既有字段语义**：请求侧 `topN` 兼容不变（仍原样回显在 `filters`）；`data.topN` 由「前 N 名」收紧为
> 「**本次窗口上限**（= 生效 `size`）」。**`page` 缺省即 1，旧前端不传 `page` ⇒ page=1 ⇒ 与 v1.2 行为
> 逐字段相同**（`hot` 前 size 行、`topN` 同值、`conversion` 同全量），故对既有调用方零变化；
> 新增的 `page`/`size`/`total`/`hasMore` 四个字段为加性，旧前端不读不会误读。
>
> **v1.4（2026-09-16，S3-20 加性补充）**：按设计 V3.0 §9.3 **L333**「`ads_sale_trend`：历史已发布，
> **net_sale 等字段需补**」、§11.2 **L428**「净销售｜**同口径支付金额 − 成功退款金额**｜真实收入方向，
> 退款归属期需冻结」与指导书 V3.0 §7 阶段3 L148「对每个指标固定粒度、分子分母、时间窗口、**金额/退款口径**、
> 空值规则和版本」：`/dashboards/overview` 的 `salesTrend[*]` 与 `/analysis/sales` 的 `trend[*]`
> **加性**新增字段 **`netSaleAmount`**（净销售额，元），值 = ADS 列 `ads_sale_trend_m.net_sale_amount`
> **原样透传**（同一行、同一 `dt`，与 `saleAmount` 并列）。
>
> **本版只做「读出既有列」**，**不**改口径、**不**重算、**不**新增列：`gmv`/`netSale`（汇总）仍只取
> `metric_value`（§3.2 原样保留）；`netSaleAmount` 与 `netSale` **不是一个东西**——前者是**逐日趋势**，
> 后者是**该快照汇总**，两者可能因窗口不同而不相等（前端不得互相代入）。
>
> **口径边界（不得越界表述）**：
> 1. 本值 = 当日口径净额，**跨业务日到账的退款不回改**历史业务日（设计 L428「退款归属期需冻结」尚**未裁决**，
>    见 `docs/PROJECT_STATUS.md` backlog）⇒ **不得**称其为"最终到账净收入"。
> 2. 该列由加性迁移以 `NOT NULL DEFAULT 0` 回填 ⇒ **历史快照的 `0` 可能是"未计算"占位**，与"当日零净额"
>    在当前数据上**不可区分**；`0` **不得**被读作"无退款"。
> 3. 列缺失或值畸形 ⇒ `null`（**不臆造 0**，与 `saleAmount` 同一 `AdsRows.asDecimal` 语义）。
> 4. 设计 §12.3 **L506**「同归属口径 ADS GMV ≥ 净销售 ≥ 0」**本版未实现**（读侧**不做**启发式纠正，
>    写侧规则缺口另行登记）⇒ 本响应**不保证**该不等式。
> 5. 阶段5 页面**尚未**展示该字段（`web/src/views/Sales.vue`、`Overview.vue` 本版未改）⇒ 不构成
>    "页面已展示净销售额"。
>
> **旧调用方影响**：纯加性字段，旧前端不读不会误读；`page`/`size`/`total`/`hasMore` 等 v1.3 语义不变。

## 1. 总原则

1. 分析服务（`AnalysisService`、`RfmService`）**只能读指标库**（`MetricStore` / `MetricAdsReader`，
   即 `metric_read` 只读账号）。禁止读 landing JSON、禁止查 `mall_*` 商城业务表、禁止 Controller 现场聚合。
2. 一次请求**只解析一个 `snapshotId`**，并在整个响应内固定使用；响应必须回显该 `snapshotId`。
3. 取值顺序：请求带 `snapshotId` → 用它；否则取该 runtime profile 的 `ACTIVE` 快照。
   没有 ACTIVE 快照时返回**空 data + warning**（不得编造数值、不得回退到 Landing）。
4. 响应是**图表语义数据**，不是 ECharts option。比值同时给 `decimal` 与单位/百分号语义。

## 2. 统一信封（§18.3）

所有 `/api/v1/analysis/**` 与 `/api/v1/dashboards/overview` 的 `data` 字段统一为：

```json
{
  "snapshotId": "S20260901_24",
  "source": "spark-ads",
  "businessTime": "2026-09-01T00:00:00",
  "dataUpdatedAt": "2026-09-10T20:12:33",
  "definitionVersion": "v2",
  "qualityStatus": "PASS",
  "filters": { "from": "2026-09-01", "to": "2026-09-01", "snapshotId": "S20260901_24" },
  "warnings": [],
  "data": { }
}
```

| 字段 | 类型 | 来源/口径 |
|---|---|---|
| `snapshotId` | string，可为 null | 本次请求固定的快照；无 ACTIVE 时为 null |
| `source` | string，可为 null（**v1.2 新增**） | `metric_snapshot.source`——该快照的**发布方/生产者**（§17.6 成功快照只接受 `spark-ads`）；无可用快照时 null；取到快照但列为空串 ⇒ `""`（与 `definitionVersion` 同口径，不臆造值）。**不是业务源身份**，见文首 v1.2 口径边界 |
| `businessTime` | string ISO，可为 null | `metric_snapshot.business_time` |
| `dataUpdatedAt` | string ISO，可为 null | `metric_snapshot.data_updated_at`（无则 LOAD 时间） |
| `definitionVersion` | string | `metric_snapshot.definition_version`（核心指标口径版本） |
| `qualityStatus` | `PASS` / `FAIL` / `UNKNOWN` | 该快照对应 run 的质量门结论（`analytics_meta` 质量结果）；取不到 = `UNKNOWN` |
| `filters` | object | 原样回显生效筛选（含快照、日期区间、topN） |
| `warnings` | string[] | 如 `["NO_ACTIVE_SNAPSHOT"]`、`["UNKNOWN_DIMENSION_TABLE"]`、`["RFM_AMOUNT_UNAVAILABLE"]`（v1.1 起 M 原值**可得时不再挂**）；**不吞掉**降级事实 |
| `data` | object | 各端点自有结构（见 §3） |

HTTP 仍走既有 `ApiResponse`（`code=OK` + `traceId`），信封放在 `data` 内。

## 3. 端点与 `data` 结构

### 3.1 `GET /api/v1/dashboards/overview?snapshotId=&from=&to=`

```json
{
  "metrics": [
    { "metricCode": "gmv", "metricName": "GMV", "value": 2042.00, "unit": "元",
      "period": "day:2026-09-01", "definitionVersion": "v1" }
  ],
  "salesTrend": [ { "date": "2026-09-01", "orderCount": 5, "saleAmount": 2042.00, "buyerCount": 3,
                    "netSaleAmount": 1493.00 } ],
  "activeTrend": [ { "date": "2026-09-01", "dau": 3, "behaviorCount": 22 } ],
  "quality": { "ruleCount": 4, "passedCount": 4, "failedRules": [] },
  "metricDictionary": [ { "metricCode": "refund_rate", "metricName": "退款率", "formula": "…", "unit": "" } ]
}
```

- `metrics` 来自 `metric_value`（ACTIVE 快照），按 `metricCode` 稳定排序。
- `salesTrend` ← `ads_sale_trend_m`，`activeTrend` ← `ads_active_trend_m`（按 `dt` 升序）。
  **v1.4**：`salesTrend[*].netSaleAmount` ← 同表 `net_sale_amount`（透传，缺列/畸形 = `null`；边界见文首 v1.4）。
- `metricDictionary` ← `analytics_meta.metric_definition`（页面"查看指标口径"用）。

### 3.2 `GET /api/v1/analysis/sales?snapshotId=&from=&to=`

```json
{ "trend": [ { "date": "2026-09-01", "orderCount": 5, "saleAmount": 2042.00, "buyerCount": 3,
               "avgOrderValue": 408.40, "netSaleAmount": 1493.00 } ],
  "gmv": 2042.00, "netSale": 1493.00, "refundRate": 0.6000, "fullRefundRate": 0.2000,
  "quality": { "ruleCount": 4, "passedCount": 4, "failedRules": [] } }
```

`gmv`/`netSale`/`refundRate`/`fullRefundRate` 一律取 `metric_value`（**不得前端或后端重算**）；
`ads_category_sale_m` / `ads_region_sale_m` 本期不存在，维度结构字段返回空数组并在 `warnings` 说明。

**v1.4**：`trend[*]` 为 ADS 直读，**新增** `netSaleAmount`（← `ads_sale_trend_m.net_sale_amount`，透传）。
它与汇总字段 `netSale`（← `metric_value`）**来源不同、粒度不同**（逐日 vs 快照汇总），
两者**不得互相代入**。

**实测边界（本版据实记录，不修）**：当前实现里 `from`/`to` **只回显在 `filters`，不参与任何过滤**——
`trend` 与该快照全部 `metric_value` 都是**整快照原值**（`AnalysisService.sales()` L191-208 实测）。
故 `netSaleAmount` **不是**"`from`~`to` 区间净额"，真实窗口过滤尚未实现（缺口登记在
`docs/PROJECT_STATUS.md` backlog）。

### 3.3 `GET /api/v1/analysis/products?snapshotId=&page=&size=&topN=&from=&to=`

```json
{ "hot": [ { "productId": 11, "productName": "…", "heat": 9.50, "pv": 100, "fav": 2,
             "cart": 1, "buy": 1, "rank": 11 } ],
  "conversion": [ { "productId": 11, "pvUsers": 3, "buyUsers": 1, "conversionRate": 0.3333 } ],
  "topN": 10, "page": 2, "size": 10, "total": 128, "hasMore": true }
```

**v1.3 热度榜分页（加性）**：

- **参数**：`page`（从 1 开始，缺省 1）、`size`（缺省 10，上限 100）。`size` 显式给定时以 `size` 为准；
  未给定时 `size = min(topN, 100)`（`topN` 缺省 10）——即**旧参数 `topN` 退化为窗口大小**，
  旧前端（只传 `topN`）行为不变。
- **窗口语义**：`hot` 返回排行第 `[(page-1)*size+1, page*size]` 名；`total` = 该快照排行**可用总行数**；
  `hasMore = page*size < total`；`page` 超出 `total` 时 `hot` 为**空数组**且 `total`/`hasMore` 如实返回
  （**空页不是错误**，属四态里的「无数据」）。
- **稳定排行**：先按 `rank_no` 升序，**同 `rank_no` 以 `product_id` 升序**打破平局（设计 L675「稳定排行」；
  避免同 rank 行在不同请求/不同 JVM 下顺序漂移）。
- **非法参数**：显式传入 `page < 1` 或 `size < 1` ⇒ 抛 `PARAM_INVALID`（HTTP 400，错误码所有者
  `GlobalExceptionHandler.mapStatus`，**不新增错误码**），**不做静默钳制**——静默改值会让「回显值 ≠ 实际生效值」。
  兼容保留：旧参数 `topN <= 0` 仍按 v1.2 语义取缺省 10（**不**因本版转为错误）。
- **`conversion` 不分页**：`ads_product_conversion_m` 保持**全量**返回（既有实现理由：按 product_id 全量，
  截断会漏商品），分页只作用于 `hot`。
- `filters` 新增回显 `page`/`size`（**生效值**）；`filters.topN` 仍为**请求原值**（缺省时回显 10，
  与 v1.2 一致）。

### 3.4 `GET /api/v1/analysis/funnel?snapshotId=`

```json
{ "stages": [ { "stage": "view", "label": "浏览", "users": 3, "rate": 1.0000 } ],
  "overallBuyRate": 1.0000,
  "windowNote": "同一 businessDate 内的行为窗口（口径版本 v1）" }
```

`stages` 来自 `ads_behavior_funnel_m`（`stage`/`user_count`/`conversion_rate`），
漏斗口径与观察窗口必须随响应返回（§18.2 用户行为）。

### 3.5 `GET /api/v1/analysis/users?snapshotId=`

```json
{ "rfmSegments": [ { "valueGroup": "高价值", "users": 1, "amount": 1496.00, "avgRecencyDays": 0,
                     "orders": 2 } ],
  "lifecycle": [ { "state": "活跃期", "users": 3 } ],
  "preference": [ { "categoryId": 1, "users": 2 } ],
  "ruleVersion": "v1",
  "periodStart": "2026-08-02", "periodEnd": "2026-09-01" }
```

全部来自 `ads_user_profile_m`（聚合，不返回个人明细）。`ads_user_profile_m` 只有聚合列时，
`avgRecencyDays` 允许为 null（不得造数）。

**v1.1 原值与观察窗口（加性）**：

- `amount`：该分组用户的 **M 原值合计** Σ`m_amount`（观察期有效支付金额，`scale=2`）；
  `orders`：**F 原值合计** Σ`f_count`（观察期有效支付订单数）；`avgRecencyDays`：**R 原值均值**，
  逐行优先取 `r_days`（Spark 侧原值所有者），仅当该行无 `r_days` 时才退回
  `calc_date − last_buy_date` 回算并在 `warnings` 里挂 `RFM_RAW_VALUES_UNAVAILABLE`。
- `periodStart`/`periodEnd`：本次评分**实际使用的观察窗口**（ISO 日期），来自 `period_start`/`period_end`；
  行间不一致或缺列时为 **null**（不猜窗口）并挂 `RFM_PERIOD_UNAVAILABLE`。
- 原值列**不可用**时对应字段为 `null`（不用 0 或分档求和冒充，设计 V3.0 §11.4 L449）；
  `m_amount` 全缺时另挂 `RFM_AMOUNT_UNAVAILABLE`（**该编码仅在原值不可用时出现**）。

### 3.6 `GET /api/v1/analysis/rfm?snapshotId=`

见 §3.5 的 `rfmSegments` + `rfmMatrix`（8 类，缺失类目补 0 人数，注明 `ruleVersion`）；
v1.1 起 `data` 同样带 `periodStart`/`periodEnd`（与 §3.5 同义）。

## 4. 四态与前端约定

- 前端每张图表必须有 `loading` / `empty` / `error` / `stale` 四态；
  `stale` 定义：切换筛选后旧数据仍在屏（请求未回来），需显示"数据更新中"并禁止导出错版数据。
- 导出（CSV）必须携带当前 `filters`、`snapshotId`、生成时间。
- `/mall`、商品后台（`/admin-products`）、生成器控制台**全部迁出分析前端**。

## 5. 一致性验收（§18.5）

选定黄金数据 4 个值（GMV 2042.00、订单数 5、PV 7、退款率 0.6000），
断言 **Hive ADS = MySQL ACTIVE = REST API = 页面 DOM** 四处一致；
AI EvidencePackage 一侧属 R8，R7-4 只登记为待办。
