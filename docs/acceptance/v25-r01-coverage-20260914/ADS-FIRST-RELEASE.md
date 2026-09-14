# ADS 首版落地顺序、输入表与 oracle（总控裁决 ②）

- 口径来源：总控裁决 ②「优先选择**能形成完整数据闭环、能做演示、能做论文实验的最小指标集合**，不为『看起来企业级』继续扩大表数量。项目已规划的 9 个场景**不要重新设计**——只回答『先落哪些、先后顺序、每条输入表与 oracle 是什么』。超出该集合的 ADS 想法登记为『后续可选』，**不列入首版完成判据**。」
- 表名与来源：`warehouse/ddl/04-ads.sql`（**实测 10 张**，L10/L26/L37/L46/L61/L72/L83/L95/L105/L124）＋ `warehouse/ddl/03-dws.sql`（**实测 7 张** DWS：L9/L23/L40/L54/L66/L79/L92）
- **本文件是顺序与判据提案，不是已完成声明**。全部条目的当前状态见 `MATRIX.md` §7-3/§7-4。

---

## 1. 已规划集合（9 场景 / 10 张表）——不新增、不重设计

| # | 场景 | ADS 表（DDL 实测名） | DDL 位置 | 产出作业（主代码实测） |
|---|---|---|---|---|
| 1 | 运营大盘 | `ads_operation_overview` | `04-ads.sql:10` | 有引用（`AdsQualityJob.scala:68`）；**写入点待其泳道钉死** |
| 2 | 漏斗 | `ads_behavior_funnel` | `04-ads.sql:26` | `FunnelAdsJob.scala:9` |
| 3 | 活跃趋势 | `ads_active_trend` | `04-ads.sql:37` | 有引用（`AdsQualityJob.scala:69`） |
| 4 | 热门商品 | `ads_hot_product` | `04-ads.sql:46` | 有引用（`AdsQualityJob.scala:71`） |
| 5 | 商品转化 | `ads_product_conversion` | `04-ads.sql:61` | 有引用（`AdsQualityJob.scala:72`） |
| 6 | 销售趋势 | `ads_sale_trend` | `04-ads.sql:72` | 有引用（`AdsQualityJob.scala:73`） |
| 7 | 分类销售 | `ads_category_sale` | `04-ads.sql:83` | **仅目录登记**（`MetricAdsCatalog.java:13`）⇒ **无产出作业** |
| 8 | 地区销售 | `ads_region_sale` | `04-ads.sql:95` | **仅目录登记**（`MetricAdsCatalog.java:13`）⇒ **无产出作业** |
| 9 | 用户画像 | `ads_user_profile`（代码/旧审计作 `ads_user_profile_m`） | `04-ads.sql:105` | `AdsSql.scala:154` |
| — | 质量月表 | `ads_data_quality`（代码/旧审计作 `ads_data_quality_m`） | `04-ads.sql:124` | `AdsQualityJob.scala` |

**待确认（新发现，见 `MATRIX.md` 缺口 #12）**：DDL 用 `ads_user_profile` / `ads_data_quality`，而代码与旧审计使用带 `_m` 后缀的表名 ⇒ 命名需统一，否则「表存在」与「作业写入的表存在」可能不是同一张表。

## 2. 先落哪些：三批，按「闭环最短 + oracle 最强」排序

**判据**：① 输入表是否已存在且已被作业产出；② 该指标是否有**可独立重算的 oracle**（能用 DWD 明细或 DWS 直接对账）；③ 是否能支撑演示与论文实验的最小集合。

### 第 1 批（先落，形成金额闭环 + 可演示大盘）
| 顺序 | 场景 | 输入表（实测 DDW/DWS 名） | oracle（可独立重算） |
|---|---|---|---|
| 1-1 | 销售趋势 `ads_sale_trend` | `dws_trade_day`（`03-dws.sql:66`） | 逐日 `net_sale_amount = paid − refund`，且区间 Σ = `dwd_order_detail` 直接聚合；`net ≥ 0` |
| 1-2 | 运营大盘 `ads_operation_overview` | `dws_trade_day` + `dws_user_behavior_day`（`:9`） | GMV/订单数/退款额与 1-1 同源对账；**UV ≤ PV**；`snapshot_id` 非空 |
| 1-3 | 地区销售 `ads_region_sale` | `dws_region_sale_day`（`:92`） | Σ各地区 = 总额（含未匹配地区行）；`order_count` 与 `dws_trade_day` 一致 |
| 1-4 | 分类销售 `ads_category_sale` | `dws_product_sale_day`（`:54`）+ `dim_product`（`02-dims.sql`） | Σ各分类 = 总额（含 `-1/未分类` 行，不静默丢）；分类数 = dim_product 去重数 |
| 1-5 | 质量月表 `ads_data_quality` | `data_quality_result`（`analytics_meta`，实测 467 行） | 六类质量字段与 `quality_rule_definition`/结果表逐项一致；不达标行必须能被追到 stage/rule |

**为什么先这 5 条**：1-3、1-4 正是当前**没有产出作业**的两张（上表），而它们只依赖已存在的 `dws_region_sale_day` / `dws_product_sale_day` ⇒ **补作业即可闭环，不需新造上游**；1-1/1-2 提供金额对账基准；1-5 是「页面数字可信」的前提。

### 第 2 批（行为面，依赖漏斗粒度修复）
| 顺序 | 场景 | 输入表 | oracle |
|---|---|---|---|
| 2-1 | 活跃趋势 `ads_active_trend` | `dws_user_behavior_day`（`:9`） | DAU = `count(distinct user_id)`；日活跃 ≥ 当日支付用户数 |
| 2-2 | 热门商品 `ads_hot_product` | `dws_product_behavior_day`（`:40`） | `rank` 连续无重复；pv 合计 = 行为明细 pv；`product_name` 非空的覆盖率须显式记录（`AdsSql.scala:100` 已记历史 LEFT JOIN 全 NULL 事故） |
| 2-3 | 漏斗 `ads_behavior_funnel` | `dws_behavior_funnel_day`（`:23`） | 各步 ≤ 上一步；末步 = `dws_trade_day` 支付用户数 |

**前置阻塞（必须先进）**：`DwsSql.scala:41-45` 把漏斗粒度硬编码为 `-1 AS category_id, 'all' AS channel`（单行「日」）⇒ 2-3 的「分类×渠道」oracle **当前不成立**。见 `DECISIONS.md` 组一 E-01（命中「论文内容/范围」判据）。

### 第 3 批（转化与画像，依赖前两批稳定 + 口径字典）
| 顺序 | 场景 | 输入表 | oracle |
|---|---|---|---|
| 3-1 | 商品转化 `ads_product_conversion` | `dws_product_behavior_day` + `dws_product_sale_day` | 转化率 ∈ [0,1]；分母为 0 时显式 `NULL`（不得写 0）；分子分母可回算到 2-2/1-4 |
| 3-2 | 用户画像 `ads_user_profile` | `dws_user_trade_period`（`:79`）+ `dws_user_behavior_day` | R/F/M 由 DWD 明细独立重算一致；分群占比合计 = 1；生命周期/偏好/版本字段非空 |

## 3. 超出该集合 ⇒ **后续可选**（不列入首版完成判据）

以下一律**不阻塞**首版验收，也不得写进「首版要做的表」：Doris/ClickHouse 双写与对照查询（`V25-X02/X03` = DEFERRED）、实时大屏/秒级刷新、多租户/多商城横向对比盘、AI 归因与同比环比预测、任意新增 ADS 表、`ads_*` 维度的「企业级」细分（渠道×活动×人群交叉表等）。

**理由**：总控裁决 ② 明确「不为『看起来企业级』继续扩大表数量」；且 §7.4 已把 Doris/ClickHouse 定为第二/第三阶段。

## 4. 本文件不能证明什么

1. **不能**证明任何一张 ADS 表当前有正确数据：本文件只给**顺序与 oracle 设计**；表内容状态见 `MATRIX.md` §7-4（实产 8/10，且 `net_sale_amount` 未落 ADS）。
2. **不能**证明 1-1～1-5 的输入表已被作业填充：DWS 的「有产出作业」与「有当日数据」是两回事，后者需 E3（WSL 链）取证。
3. **不能**证明 oracle 已在自动化测试中实现：本文件中所有 oracle 均为**建议判据**，是否已落地见对应任务行。
4. **不能**证明 2-3 的漏斗维度可用（当前硬编码退化，见 §2 前置阻塞）。
5. 本文件**未**按 §10 任务包格式补齐 Owner/允许范围/反馈时间等字段（那属看板职责，见 `DECISIONS.md` D-06）。
