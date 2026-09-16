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
>
> **v1.5（2026-09-16，S3-21 加性补充）**：按设计 V3.0 **L693**「根路径 `/api/v1`；响应
> `code/message/data/traceId`；**分页 `page`/`size`/`sort`**」与 **L675**「商品分析｜…**稳定排行、分页**」：
> `/api/v1/analysis/products` **加性**新增 `sort` 参数（`字段` 或 `字段,asc|desc`，字段白名单
> `rank`/`heat`/`pv`/`fav`/`cart`/`buy`，缺省 `rank,asc`），补齐 v1.3 明文留下的 L693 第三项（见 §3.3）。
>
> **本版只做「排序键」**：**不**改 ADS 数据、**不**改任何指标口径、**不**新增/改列、**不**改 `page`/`size`/
> `total`/`hasMore` 语义；`filters.sort` 回显**生效值**（缺省也回显 `rank,asc`），未登记字段/非法方向
> ⇒ `PARAM_INVALID`（不新增错误码、不静默降级）。
>
> **仍未实现（不得因本版发布而声称已满足）**：L158 的**限流**、L693/L158 的**「写操作幂等请求头」**、
> L157「归档读取授权」「未知快照不静默回最新」的授权侧裁决、§12.3 L506 不变式。缺口逐条登记在
> `docs/PROJECT_STATUS.md` backlog。
>
> **旧调用方影响**：不传 `sort` ⇒ 与 v1.4 逐行同序；`sort` 为纯加性参数，旧前端不受影响。

> **v1.6（2026-09-16，S3-24 加性补充）**：按指导书 V3.0 §7 阶段4 **L156**「MetricStore/专题服务返回明确
> source、snapshot、**definitionVersion**、时间和**质量信息**」与设计 V3.0 §12.3 **L512**「每条规则记录
> 作用域、阈值、**版本**、阶段、实际值、passed、原始/生效严重度」：`/dashboards/overview`（§3.1）与
> `/analysis/sales`（§3.2）的 `quality` 对象**加性**新增字段 **`ruleVersions`**（规则码 → 规则定义版本），
> 值 = ADS 列 `ads_data_quality_m.rule_version` **原样透传**（同一行、同一 `dt`、同一快照）。
> 该列的写入方在 S3-05 已落地（`V8__ads_data_quality_rule_version.sql`），本版补的是**读取侧消费**。
>
> **键集语义（不得越界表述）**：`ruleVersions` 的键集是 `ruleCount` 的**子集**——**不出现的规则码 =
> 该行 `rule_version` 为 NULL（历史快照"未记录版本"）或不可解析**，**不补 0、不冒充 v1**
> （与写入侧同源判据：`V8__ads_data_quality_rule_version.sql`「允许 NULL…**不写 0 冒充 v1**」）。
> 键序按规则码升序稳定输出（同一快照多次响应键序一致）。
>
> **本版只做「读出既有列」**：不改口径、不重算、不新增/改列、不改 `ruleCount`/`passedCount`/`failedRules`
> 语义（**无版本的行仍计入** `ruleCount`）；不新增错误码、不新增降级编码。
>
> **仍未实现（不得因本版发布而声称已满足）**：阶段5 页面**尚未**展示规则版本（`web/**` 本版未改）；
> 设计 §9.3 **L335**「规则版本与**实时结果**待接齐」的另一半（发布链实时回写）、§12.3 规则 5/6/11、
> L158 **限流**、L157 归档读取授权、§12.3 L506 不变式均**不变**，逐条登记在 `docs/PROJECT_STATUS.md` backlog。
>
> **旧调用方影响**：纯加性字段，旧前端不读不会误读。
>
> **v1.7（2026-09-16，S3-27 加性补充·**读侧消费**）**：按 v1.4 已新增的
> `/dashboards/overview.salesTrend[*].netSaleAmount`（§3.1）与 `/analysis/sales.trend[*].netSaleAmount`（§3.2），
> 阶段5 页面（`web/**`）**开始展示**该字段：`Sales.vue` 的销售趋势图**并列**新增「净销售额」柱、
> 销售明细表与导出 CSV 新增「净销售额(元)」列；`Overview.vue` 的经营概览销售趋势图同图**自动**获得该序列
> （两页共用 `chartOptions.salesTrendOption`）。**本版不改任何响应字段、不改口径、不重算、不新增列**——
> 只是既有字段的**首个页面消费方**；`netSaleAmount` 的取值语义、null 语义与全部口径边界**仍以 v1.4 为准**
> （v1.4 边界 5「阶段5 页面尚未展示该字段…不构成"页面已展示净销售额"」自本版起**已过期**，
> 该句只读作**历史**，不再作为当前事实）。
>
> **展示层口径（不得越界表述）**：
> 1. 页面**只在图例与列名上标注「逐日口径」并原样展示后端值**，**不**做任何纠正/推算：
>    设计 §12.3 **L506**「ADS GMV ≥ 净销售 ≥ 0」在读侧**仍未实现**（v1.4 边界 4 不变）。
> 2. `null`（列缺失/畸形）⇒ 图表**断点**、表格空串、CSV 空串，**不臆造 0**；`0` **原样展示**为 `0.00`
>    —— 因 `0` 与"未计算占位"当前**不可区分**（v1.4 边界 2），页面**不**把 `0` 改写为"—"、也**不**声称无退款。
> 3. 页面**必须**同时给出文字限制说明（逐日口径、退款归属期未冻结、历史 `0` 可能为占位、
>    汇总 `netSale` 与逐日 `netSaleAmount` **不得互相代入**），文案取自单一常量
>    `web/src/utils/chartOptions.js` 的 `NET_SALE_NOTE`，**不得**在两个视图里各写一份。
> 4. 该展示**不**构成"净销售额已可用于真实收入结论"：跨业务日退款不回改历史业务日（v1.4 边界 1 不变）。
>
> **仍未实现（不得因本版发布而声称已满足）**：净销售额的**区间/窗口过滤**（`/analysis/sales` 的
> `from`/`to` 只回显不过滤，见 `docs/PROJECT_STATUS.md` backlog）；退款归属期裁决（设计 §11.2 L428）；
> 设计 §12.3 L506 不等式与规则 5/6/11；阶段5 其余已登记展示项（`quality.ruleVersions` 等）均**不变**。
>
> **v1.8（2026-09-16，S3-28 加性补充·**读侧消费**）**：按 **v1.6** 已新增的
> `/dashboards/overview.quality.ruleVersions`（§3.1）与 `/analysis/sales.quality.ruleVersions`（§3.2），
> 阶段5 页面（`web/**`）**开始展示**该字段：`Sales.vue` 的「质量规则（快照 run）」提示与 `Overview.vue`
> 新增的「数据质量（快照 run）」块**都**显示 ① 规则通过情况（`ruleCount`/`passedCount`/`failedRules`）
> 与 ② **规则版本列表**（`规则码=v版本`，按规则码升序）。**本版不改任何响应字段、不改口径、不重算**——
> 只是该既有字段的首个页面消费方；`ruleVersions` 的键集语义**仍以 v1.6 为准**（键集是 `ruleCount` 的
> **子集**：不出现的规则码 ＝ 该行 `rule_version` 为 NULL 或不可解析）。
>
> **展示层口径（不得越界表述）**：
> 1. 页面**原样展示**后端给出的键值对，**不**为缺失的规则码补 `0`、**不**冒充 `v1`、**不**用「最新版本」
>    之类的默认值填充（与写入侧 `V8__ads_data_quality_rule_version.sql`「允许 NULL…**不写 0 冒充 v1**」同源判据）。
> 2. 规则版本列表**为空**时页面显示明确的「**无版本记录**」，**不**显示空白、**不**显示 `v1`。
> 3. 页面**必须**给出文字限制说明「未列出的规则码＝该行未记录版本」，文案取自单一常量
>    `web/src/utils/quality.js` 的 `RULE_VERSION_NOTE`；两个视图**不得**各写一份（通过情况文案同理，
>    唯一属主是 `qualitySummaryText`）。
> 4. 该展示**只**回答「本次快照是按哪一版规则判定的」；**不**构成「质量已达标」「规则已全部登记版本」
>    或「规则版本与实时结果已接齐」（设计 §9.3 **L335** 的另一半＝发布链实时回写**仍未实现**）。
> 5. 真实 HTTP 响应中 `ruleVersions` 的 JSON 形状与真实库 `rule_version` 取值/NULL 占比**本轮未实测**
>    （沿用 S3-24 的后端证据）；页面渲染**未**由 SFC 编译器或浏览器验证（`web/node_modules` 缺失）。
>
> **仍未实现（不得因本版发布而声称已满足）**：设计 §9.3 L335 的「**实时结果**接齐」；§12.3 规则 5/6/11；
> 阶段5 其余已登记项（`repeat_rate` 周期/口径版本展示、支付复购率变体、E5-c 剩余一半、
> `/analysis/sales` 真窗口过滤）均**不变**。

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

> **S3-35 补记（前端归一化视图，不改后端信封）**：后端信封字段表**不变**，仍以上表为准（**无** `missingNotice`）。
> 前端归一化器 `web/src/utils/envelope.js readEnvelope()` 额外透传一个 `missingNotice`：
> 仅当**非信封接口**（`/pipeline-runs`、`/metrics/*`、`/decisions` 等）在页面 fetcher 内由
> `buildFallbackContext()` 生成「接口未提供（已如实标注，不代替后端编造）：…」时非空；
> 后端统一信封不含该字段 ⇒ 归一化为 `null`（页横幅 `v-if` 为假不显示、导出不写该行，**不臆造缺失说明**）。
> 页面上下文条与导出元信息（`csv.js` 的「# 上下文缺失」行）共用这一份字段：`useAnalysis.exportContext`
> 必须带它，否则「取不到就如实标注」到不了屏幕与导出件。**口径边界**：该字段只描述"接口没给什么"，
> 不是后端返回的字段，不得被当作后端证据引用。

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
  "quality": { "ruleCount": 4, "passedCount": 4, "failedRules": [],
               "ruleVersions": { "AMOUNT_RECONCILE": 1, "ENUM_WHITELIST": 1 } },
  "metricDictionary": [ { "metricCode": "refund_rate", "metricName": "退款率", "formula": "…", "unit": "" } ]
}
```

- `metrics` 来自 `metric_value`（ACTIVE 快照），按 `metricCode` 稳定排序。
- `salesTrend` ← `ads_sale_trend_m`，`activeTrend` ← `ads_active_trend_m`（按 `dt` 升序）。
  **v1.4**：`salesTrend[*].netSaleAmount` ← 同表 `net_sale_amount`（透传，缺列/畸形 = `null`；边界见文首 v1.4）。
- `metricDictionary` ← `analytics_meta.metric_definition`（页面"查看指标口径"用）。
- **v1.6**：`quality.ruleVersions` ← `ads_data_quality_m.rule_version`（**原样透传**；键集语义与
  「不补 0/不冒充 v1」的边界见文首 v1.6）。示例只列部分键：键集是 `ruleCount` 的**子集**。

### 3.2 `GET /api/v1/analysis/sales?snapshotId=&from=&to=`

```json
{ "trend": [ { "date": "2026-09-01", "orderCount": 5, "saleAmount": 2042.00, "buyerCount": 3,
               "avgOrderValue": 408.40, "netSaleAmount": 1493.00 } ],
  "gmv": 2042.00, "netSale": 1493.00, "refundRate": 0.6000, "fullRefundRate": 0.2000,
  "quality": { "ruleCount": 4, "passedCount": 4, "failedRules": [],
               "ruleVersions": { "AMOUNT_RECONCILE": 1, "ENUM_WHITELIST": 1 } } }
```

`gmv`/`netSale`/`refundRate`/`fullRefundRate` 一律取 `metric_value`（**不得前端或后端重算**）；
`ads_category_sale_m` / `ads_region_sale_m` 本期不存在，维度结构字段返回空数组并在 `warnings` 说明。

**v1.4**：`trend[*]` 为 ADS 直读，**新增** `netSaleAmount`（← `ads_sale_trend_m.net_sale_amount`，透传）。
它与汇总字段 `netSale`（← `metric_value`）**来源不同、粒度不同**（逐日 vs 快照汇总），
两者**不得互相代入**。

**v1.6**：`quality.ruleVersions` 与 §3.1 同源同语义（同一个 `quality()` 所有者，两处逐字相同）。

**实测边界（本版据实记录，不修）**：当前实现里 `from`/`to` **只回显在 `filters`，不参与任何过滤**——
`trend` 与该快照全部 `metric_value` 都是**整快照原值**（`AnalysisService.sales()` L191-208 实测）。
故 `netSaleAmount` **不是**"`from`~`to` 区间净额"，真实窗口过滤尚未实现（缺口登记在
`docs/PROJECT_STATUS.md` backlog）。

### 3.3 `GET /api/v1/analysis/products?snapshotId=&page=&size=&sort=&topN=&from=&to=`

```json
{ "hot": [ { "productId": 11, "productName": "…", "heat": 9.50, "pv": 100, "fav": 2,
             "cart": 1, "buy": 1, "rank": 11 } ],
  "conversion": [ { "productId": 11, "pvUsers": 3, "buyUsers": 1, "conversionRate": 0.3333 } ],
  "topN": 10, "page": 2, "size": 10, "total": 128, "hasMore": true }
```

**v1.5 热度榜排序（加性）**：

- **参数 `sort`**（设计 L693「分页 `page`/`size`/`sort`」）：形式 `字段` 或 `字段,asc|desc`；去首尾空白、
  大小写不敏感；`null`/空白 ⇒ `rank,asc`（**与 v1.4 前逐行同序**）。
- **字段白名单（本契约显式冻结）**：`rank`（← `rank_no`）、`heat`（← `heat_score`）、`pv`、`fav`、
  `cart`、`buy`。缺省方向：`rank` 为 `asc`（名次越小越热），其余五个为 `desc`（指标越大越靠前）。
  显式方向覆盖缺省方向。
- **未登记字段 / 非法方向 / 段数 > 2 / 给了逗号却不给方向** ⇒ `PARAM_INVALID`（HTTP 400，**不新增错误码**），
  **不静默降级为缺省序**——静默降级会让客户端以为拿到了排序，且 `filters.sort` 与实际顺序都对不上。
- **`sort` 只换首级排序键**：末级**固定** `product_id` 升序（v1.3「稳定排行」不变），
  故任意 `sort` 下同值集合内顺序都稳定；`heat` 取不到值的行（`heat_score` 缺列/非数值）
  **无论方向都排最后**（**不**当 0：当 0 会让缺值行在降序里冒充末位真值、在升序里直接霸榜）。
- **`sort` 不改窗口口径**：先对**全量**榜排序，再按 `page`/`size` 切窗；`total`/`hasMore` 语义与 v1.3 相同。
  `conversion` 与 `sort` 无关（仍按 `product_id` 升序、全量）。
- `filters` 新增回显 `sort`（**生效值**，规范形式 `字段,asc|desc`，缺省时回显 `rank,asc`）；
  `filters.page`/`filters.size` 仍为生效值，`filters.topN` 仍为请求原值。
- **旧调用方影响**：不传 `sort` 时逐行同序，纯加性。

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
