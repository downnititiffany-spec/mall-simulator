# R7-4 分析接口 ViewModel 契约（冻结版 v1）

> 依据：指导书 V2.0 §18.1 / §18.3 / §24.6。**本文件是 R7-4 后端与前端唯一契约**，
> 两侧实现必须逐字段对齐；若实现中确需改动，先改本文件再改代码。

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
| `businessTime` | string ISO，可为 null | `metric_snapshot.business_time` |
| `dataUpdatedAt` | string ISO，可为 null | `metric_snapshot.data_updated_at`（无则 LOAD 时间） |
| `definitionVersion` | string | `metric_snapshot.definition_version`（核心指标口径版本） |
| `qualityStatus` | `PASS` / `FAIL` / `UNKNOWN` | 该快照对应 run 的质量门结论（`analytics_meta` 质量结果）；取不到 = `UNKNOWN` |
| `filters` | object | 原样回显生效筛选（含快照、日期区间、topN） |
| `warnings` | string[] | 如 `["NO_ACTIVE_SNAPSHOT"]`、`["UNKNOWN_DIMENSION_TABLE"]`；**不吞掉**降级事实 |
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
{ "rfmSegments": [ { "valueGroup": "高价值", "users": 1, "amount": 1496.00, "avgRecencyDays": 0 } ],
  "lifecycle": [ { "state": "活跃期", "users": 3 } ],
  "preference": [ { "categoryId": 1, "users": 2 } ],
  "ruleVersion": "v1" }
```

全部来自 `ads_user_profile_m`（聚合，不返回个人明细）。`ads_user_profile_m` 只有聚合列时，
`avgRecencyDays` 允许为 null（不得造数）。

### 3.6 `GET /api/v1/analysis/rfm?snapshotId=`

见 §3.5 的 `rfmSegments` + `rfmMatrix`（8 类，缺失类目补 0 人数，注明 `ruleVersion`）。

## 4. 四态与前端约定

- 前端每张图表必须有 `loading` / `empty` / `error` / `stale` 四态；
  `stale` 定义：切换筛选后旧数据仍在屏（请求未回来），需显示"数据更新中"并禁止导出错版数据。
- 导出（CSV）必须携带当前 `filters`、`snapshotId`、生成时间。
- `/mall`、商品后台（`/admin-products`）、生成器控制台**全部迁出分析前端**。

## 5. 一致性验收（§18.5）

选定黄金数据 4 个值（GMV 2042.00、订单数 5、PV 7、退款率 0.6000），
断言 **Hive ADS = MySQL ACTIVE = REST API = 页面 DOM** 四处一致；
AI EvidencePackage 一侧属 R8，R7-4 只登记为待办。
