# N31-02 销售分类/地区维度实现契约（设计差异草案）

- 日期：2026-09-29
- 状态：**C1/C2 完成；C3 已由 D-058 批准；实现进行中，尚未声称链路验收通过**
- 归属：N31-02 腿③。依据 D-054 与批次计划 §4；D-058 仅授权该腿按本契约实施，不修改 V3.0 冻结正文。
- 裁决：2026-09-29 总控回复“按推荐方案整体批准”；原文及范围边界见 `docs/decisions/rulings/MASTER-RULING-20260929-N3102-LEGC-APPROVAL.md`。
- 文档定位：经批准的工作级实现契约；是否纳入未来正式 V3.1 设计文档，由总控在版本发布时决定。

## 1. 当前实现基线（实查）

1. `AnalysisService.sales` 返回 `trend/gmv/netSale/refundRate/fullRefundRate/quality`；`byCategory/byRegion` 固定为空数组，并返回 `UNKNOWN_DIMENSION_TABLE`。这是诚实降级，不是接口运行错误。
2. analytics_metric 只登记并发布 8 张 ADS 服务表；`MetricAdsCatalog` 明确尚无 `ads_category_sale_m`、`ads_region_sale_m`。Flyway metric V3 注释禁止在 Hive 无真实生产者时先建空服务表。
3. Hive 设计 DDL 中有 `ads_category_sale` / `ads_region_sale` 规格，但无 `AdsSql` 生产者、无 `FunnelAdsJob` 输出、无 metric catalog / migration 镜像。按设计 V3.0 §9.3，它们是待发布规格，不是已实现能力。
4. 已有 `dws_product_sale_day(product_id,category_id,sale_count,sale_amount,buyer_count)`；已有 `dws_region_sale_day(region,buyer_count,order_count,sale_amount,net_sale_amount)`。`dws_region_sale_day.region` 实际来源是 DWD `city_level`，SQL 对空值合并为 `unknown`；它不是省/市行政区。
5. `dwd_order_detail` 已有 `category_id`、`city_level`、`quantity`、`amount`、`refund_amount`、`final_paid_flag`；`dim_product` 含 `category_name`、`parent_category_id/name`。`TradeDwdJob` 按日期分区 join 商品维，并以 category_id `-1` 表示未匹配分类；地区空值规范为字符串 `unknown`。
6. Vue `Sales.vue` 当前不绘分类/地区图，并明示两表缺失。`EvidenceBuilder.rowDimension` 已预留从 `SalesData.byCategory/byRegion` 取证据行，但来源归属现在写死成 `ads_sale_trend_m`，未来必须按真实维度表改正。`SemanticCatalog` 目前只将销售趋势/概览纳入 AI SQL 白名单；是否扩维度表应在实际 schema 与权限测试通过后办理。

## 2. 推荐语义（供总控裁定）

### 分类维度

- 粒度：快照 × 业务日 `dt` × 叶子 `category_id`；一条 category 行仅表示该日该分类，不跨父/子层重复加总。
- 事实来源：`dwd_order_detail` 按 paid/final 状态筛选并分组；名称与 parent 字段由同日/有效日的 `dim_product` 维快照取得，join 必须含 `dt`，避免历史维表多分区放大。
- 默认金额：`sale_amount = SUM(amount)`；`net_sale_amount = SUM(amount) - SUM(paid refund_amount)`，公式与 `dws_trade_day` 相同。退款仍归当前订单业务日；跨业务日退款重结不在本片实现。
- `sale_count` 表示销售件数 `SUM(quantity)`，不是订单数。
- `category_id=-1` 或维表匹配缺失归入唯一 `unknown/未分类` 行；必须计入分类销售金额总和，禁止丢弃、强行映射到真实类别或把无数据假装成 0。
- `amount_ratio` 若保留在日 ADS，仅表示该 `dt` 内分类金额占比。查询多日窗口时，不能把每日比例相加；必须按所选窗口聚合金额后重算比例，分母包含 unknown。

### 地区维度

- 本轮可实现的业务含义只能是**城市等级销售分布**（源字段 `city_level`，展示例“一线/二线/unknown”），不是省、市、国家等真实地理区域。
- 为兼容现有 `SalesData.byRegion` 与 Hive 草案，可暂保留 API 名 `byRegion` / Hive 表名 `ads_region_sale`，但 UI 必须显示“城市等级”，并在字段字典注明 `region = city_level`。未来接入有行政区字段的商城时，须新增映射或升版，不可把城市等级画成地图。
- 日粒度来源优先复用 `dws_region_sale_day`；保留 `unknown`，日 `sale_amount/net_sale_amount` 应分别与同日 `dws_trade_day` 完全对账。

### 跨日 distinct 指标（提交裁决，不默默求和）

`buyer_count` 和 `order_count` 是日级 distinct；跨多日直接 `SUM` 会重复计算跨日购买的同一买家/订单。推荐首版维度响应只提供可加总的 `sale_count/sale_amount/net_sale_amount` 与窗口金额占比，不把日 distinct 的和命名为窗口唯一买家/订单数。若总控要求窗口真实 distinct，则需要另定数据结构/聚合路径（例如保留可精确去重的订单-分类键或明确的查询成本），不在本草案擅自扩大存储范围。

## 3. 表与发布映射候选

以下仅为建议，不是 DDL 执行指令；版本号、字段最终集、是否维持既有静态 Hive DDL 列需由总控裁定。

| 逻辑表 | 粒度/键 | 事实列候选 | 镜像服务表 | 备注 |
|---|---|---|---|---|
| `ads_category_sale` | `snapshot_id + dt + category_id` | `category_name,parent_category_id,parent_category_name,sale_count,sale_amount,net_sale_amount` | `analytics_metric.ads_category_sale_m` | unknown `category_id=-1` 必须保留；金额占比可以由窗口聚合后派生 |
| `ads_region_sale` | `snapshot_id + dt + region(city_level)` | `sale_amount,net_sale_amount` | `analytics_metric.ads_region_sale_m` | 原 DWS 可供每日 buyer/order 核验，但跨日不提供错误的 summed-distinct |

建议 ADS 构建仍由现有 `fna` 阶段统一编排，增加两个明确的 Spark SQL producer；沿用 staging→质量核验→Hive 发布→MetricExport→MySQL 发布事务。若添加 MySQL 表，需要新的**加性 Flyway 迁移**（不改历史 migration），主键必须包含 `snapshot_id`，并按 catalog 白名单列校验；具体版本号由实施前扫描决定。

质量门候选：按日 category（含 unknown）`SUM(sale_amount/net_sale_amount)` 与 `dws_trade_day` 对账；region 同式；分类/地区占比的窗口求和在 denominator>0 时约等于 1；空数据窗可合法返回空数组，但缺表/读错表必须有 warning，不能冒充“业务为 0”。发布任一新表失败时，当前 ACTIVE 必须保持旧快照且可读。

## 4. API、前端和 AI 候选契约

API 保持 `GET /api/v1/analysis/sales?snapshotId=&from=&to=` 与现有 envelope / `AnalysisViewModel<SalesData>`。候选 `byCategory` 行：`category_id/category_name/parent_category_id/parent_category_name/sale_count/sale_amount/net_sale_amount/amount_ratio`；候选 `byRegion` 行：`region/sale_amount/net_sale_amount/amount_ratio`。`from/to` 为含端点日期，所有查询都显式 pin 同一个 `snapshotId`；稳定排序为 `sale_amount DESC` 后 ID/名称 ASC，返回行数上限建议 20，空结果返回 `[]`。

前端在销售页加入两个可视块：分类销售排行/占比、城市等级销售构成；显示日期范围、快照号、金额单位与 unknown 类别；未知类别保持可见，不能把列表前端聚合冒充数仓 ADS。区间为多日时，金额与件数可加，window `amount_ratio` 用已返回的聚合金额重算；不展示虚假的跨日 unique buyers/orders。导出列须与页面实际字段一致。

AI 证据包：`EvidenceBuilder.rowDimension` 可以复用已有扩展点，但必须分别标记 `ads_category_sale_m` / `ads_region_sale_m` 的真实来源、`snapshotId`、`from/to`、度量名，不能继续引用 `ads_sale_trend_m`。首版可仅让 AI 消费结构化证据包；如果启用 Text2SQL，再将两张新表和字段加入 `SemanticCatalog` 白名单、提示词与少样本，并验证 snapshot/date 谓词、EXPLAIN 和只读授权，不得因 EvidenceBuilder 已接线而自动扩大 SQL 权限。

## 5. 独立 oracle 与验收建议

由原始输入事件和商品维表构建独立 Python/手算 oracle，和生产 Spark SQL 使用不同实现路径。夹具必须覆盖：同一订单多商品、跨分类、退款/全额退款、未支付但带退款金额、缺失 category 维表、缺失 city_level、跨两日重复买家和订单。分别核对每日 category/region → DWS trade total、ADS staging → 发布 parquet/export → MySQL ADS → API → 页面；允许精确 decimal 或绝对误差 `<0.01`，比率采用 `<0.0005`。还要检验窗口占比而非日比例累加、unknown 是否包含、同日重跑幂等、失败保留旧 ACTIVE、搜索/SQL 权限与 AI 证据 lineage。

## 6. 设计差异与 C3 原裁决清单（历史提案，已由 D-058 关闭）

设计 V3.0 的 §9.3 将两张表列为待发布规格；当前 Hive DDL 与服务表草案不完整/不足以支撑多日区间的准确 unique 指标。推荐方向是先实现**叶子分类 + 城市等级**、保留 unknown、对外只提供可加总维度度量、单一发布快照的方案，但请总控确认：

1. 接受“地区”首版实际定义为城市等级，并将 UI 明确命名为“城市等级销售分布”，还是暂停地区维度直到数据源提供真实行政区？
2. 多日区间是否接受不输出 unique buyer/order 数（避免日粒度 distinct 直接相加），还是要求增加精确跨日去重的数据结构与存储成本？
3. category 以叶子分类为唯一展示粒度、父类字段仅供说明/后续 drilldown，是否符合预期？
4. 是否接受调整历史规划 DDL 字段并以新版本新增服务表 migration；批准后再定字段和 schema version？

本节保留的是 C3 提交前的问题原貌；不得再将“等待裁决”理解为当前状态。D-058 已批准下列推荐语义，授权实现本专题；仍须遵守新增迁移、独立 oracle、同一 snapshotId 和失败保留旧 ACTIVE 等约束。

## 7. D-058 批准的实施口径

| 决策点 | 批准口径 | 实现约束 |
|---|---|---|
| 地区含义 | 城市等级，来源字段 `city_level` | API 可沿用 `byRegion` / `ads_region_sale_m` 命名；UI 必须显示“城市等级”，不画行政区地图 |
| 多日 distinct | 不提供跨日唯一买家数/订单数 | 不累加日级 `buyer_count` / `order_count` 冒充窗口 distinct；金额占比按窗口总额重算 |
| 分类粒度 | 叶子分类 | 父类仅描述，不作为重复汇总层；未匹配分类保留 `category_id=-1` 的唯一 unknown 行 |
| 表与迁移 | 新增 Hive ADS 与 MySQL 镜像服务表 | 采用新加性 Flyway migration；不编辑 V1–V33 历史迁移 |

本契约只覆盖 N31-02 腿③。真实行政区、多日精确 distinct、AI Text2SQL 扩权、V3.1 正式设计发布均不由 D-058 自动授权。

