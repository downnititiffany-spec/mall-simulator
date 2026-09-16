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
  "salesTrend": [ { "date": "2026-09-01", "orderCount": 5, "saleAmount": 2042.00, "buyerCount": 3 } ],
  "activeTrend": [ { "date": "2026-09-01", "dau": 3, "behaviorCount": 22 } ],
  "quality": { "ruleCount": 4, "passedCount": 4, "failedRules": [] },
  "metricDictionary": [ { "metricCode": "refund_rate", "metricName": "退款率", "formula": "…", "unit": "" } ]
}
```

- `metrics` 来自 `metric_value`（ACTIVE 快照），按 `metricCode` 稳定排序。
- `salesTrend` ← `ads_sale_trend_m`，`activeTrend` ← `ads_active_trend_m`（按 `dt` 升序）。
- `metricDictionary` ← `analytics_meta.metric_definition`（页面"查看指标口径"用）。

### 3.2 `GET /api/v1/analysis/sales?snapshotId=&from=&to=`

```json
{ "trend": [ { "date": "2026-09-01", "orderCount": 5, "saleAmount": 2042.00, "buyerCount": 3,
               "avgOrderValue": 408.40 } ],
  "gmv": 2042.00, "netSale": 1493.00, "refundRate": 0.6000, "fullRefundRate": 0.2000,
  "quality": { "ruleCount": 4, "passedCount": 4, "failedRules": [] } }
```

`gmv`/`netSale`/`refundRate`/`fullRefundRate` 一律取 `metric_value`（**不得前端或后端重算**）；
`ads_category_sale_m` / `ads_region_sale_m` 本期不存在，维度结构字段返回空数组并在 `warnings` 说明。

### 3.3 `GET /api/v1/analysis/products?snapshotId=&topN=`

```json
{ "hot": [ { "productId": 11, "productName": "…", "heat": 9.50, "pv": 100, "fav": 2,
             "cart": 1, "buy": 1, "rank": 1 } ],
  "conversion": [ { "productId": 11, "pvUsers": 3, "buyUsers": 1, "conversionRate": 0.3333 } ],
  "topN": 10 }
```

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
