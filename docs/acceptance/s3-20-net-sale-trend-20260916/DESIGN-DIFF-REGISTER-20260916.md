# S3-20 设计差异登记：销售趋势净销售额（`netSaleAmount`）消费侧

- 编号：S3-20（对应事实记录 F-53）
- 日期（项目内）：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 起点提交：`6955b18`（F-52 / S3-19 阶段4 只读查询超时统一）
- 判类请求：**A 类（实现/加性）**——不删表/列、不改既有字段类型与业务语义、不改已发布 Flyway 迁移、
  不动正式 3306 数据、不切 ACTIVE、不改 `contract-specs/**` 契约语义、不改 V3.0 总体架构/范围、
  不删已发布功能、不引入 V3.0 未规划的大型基础组件、不造成长期架构分叉。

## 0. 结论（先说边界）

本项把**已落库但无消费方**的 ADS 净销售额列接到阶段4 查询服务：`/dashboards/overview` 的
`salesTrend[*]` 与 `/analysis/sales` 的 `trend[*]` **加性**新增 `netSaleAmount`。

**一句话**：本项只是「**把已经在 MySQL 里、也已经被 DAO 查出来的那一列读出来**」，
**没有**新增算法、**没有**改口径、**没有**补历史、**没有**让前端显示它。

因此本项**不得**被表述为：净销售额口径已冻结 ✅ / 退款归属期已处理 ❌ / 设计 L506 不变式已实现 ❌ /
页面已展示净销售额 ❌ / 真库取值已核 ❌。

## 1. 判类依据（逐字原文）

| 来源 | 行 | 逐字原文 |
|---|---|---|
| 设计 V3.0 | **L333** | 「\| ads_sale_trend \| ads_sale_trend_m \| 历史已发布，net_sale等字段需补 \|」 |
| 设计 V3.0 | **L428** | 「\| 净销售 \| 同口径支付金额−成功退款金额 \| 真实收入方向，退款归属期需冻结 \|」 |
| 设计 V3.0 | **L437** | 黄金 55 条含「净销售 **1493.00**」（与 PV7/UV3/DAU3/支付订单5/GMV 2042.00 同组） |
| 指导书 V3.0 | §7 阶段3 L148 | 「对每个指标固定粒度、分子分母、时间窗口、**金额/退款口径**、空值规则和版本。」 |
| 指导书 V3.0 | §7 阶段4 L156 | 「MetricStore/专题服务返回明确 source、snapshot、definitionVersion、时间和质量信息。」 |
| `docs/PROJECT_STATUS.md` | backlog | 「`ads_sale_trend_m.net_sale_amount` **尚无消费方**：`AnalysisService.T_SALE_TREND`/`AnalysisViewModel` 仍按旧 4 列直通」⇒「阶段4 开发项（非阻塞），进入阶段4 服务层时按设计 §11.2 消费净额」 |

## 2. 冻结事实（本项开始前实测，全部可复现）

| 编号 | 事实 | 证据（命令/文件位置） |
|---|---|---|
| F1 | ADS→MySQL 的**列所有者唯一**：Spark `MetricAdsSpec.scala:34` 末列 = `net_sale_amount`；Java 白名单 `MetricAdsCatalog.java:31` 同序末列 | `git grep -n net_sale_amount -- spark-jobs analytics-server/metric-analysis/src/main` |
| F2 | 该列**已经落 MySQL**：`db/metric/V5__ads_sale_trend_net_sale.sql:11-12`（`ALTER TABLE … ADD COLUMN net_sale_amount DECIMAL(18,2) NOT NULL DEFAULT 0`） | 该文件全文 |
| F3 | 读侧 DAO **本来就返回该列**：`MetricAdsReader.selectSql()` 按 `MetricAdsCatalog` 白名单拼列（`MetricAdsReader.java:118-136`）⇒ 缺口**只在消费侧**，DAO 无需改动 | 该文件 |
| F4 | 生产者侧**已同式**：`AdsSql.scala:212-223` 直接从 `dws_trade_day.net_sale_amount` 透传（ADS 不另写算法），且注释声明「同一 dt 上 `ads_sale_trend.net_sale_amount` 与 `ads_operation_overview.net_sale_amount` 必须逐值相等」 | `AdsSql.scala` L212-223 |
| F5 | 发布侧**已映射**：`MetricPublisher.java:53` `OVERVIEW_TO_METRIC.put("net_sale_amount","net_sale")`；`MetricPublishValidator.java:197` 对账 `net_sale` ↔ 行 `net_sale_amount` | 两处 |
| F6 | 消费侧**旧形态确为 4 列**：`SalesTrendPoint(date, orderCount, saleAmount, buyerCount, avgOrderValue)`，`salesTrend()` 不投影 `net_sale_amount` | 起点提交的 `AnalysisService.java` |
| F7 | **历史占位语义由 V5 自己声明**：`V5__ads_sale_trend_net_sale.sql:8-10`「历史快照行补 0 而不是 NULL…**0 是"未知/未回填"的占位**；旧快照的净额**不得**据此当作真实 0 参与分析」 | 该文件 |
| F8 | `from`/`to` **不参与过滤**（本项据实发现，属既有缺口）：`AnalysisService.sales()` L191-208 只把它们 `echoDateRange` 进 `filters`，`trend` 与 `metric_value` 都是整快照原值 | 该文件 |
| F9 | 阶段5 前端**未**消费该字段（本项未改前端）：`web/src/utils/chartOptions.js:11-23`、`views/Sales.vue:47,106,143`、`views/Overview.vue:121` 只读 `saleAmount`/`orderCount`/`buyerCount`/`avgOrderValue` | `git grep -n "salesTrend\|saleAmount" -- web/src` |

## 3. 11 门逐门核对（全部“不触”）

| 门 | 判定 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 不触 | 无任何 DDL 变更；`V5` 字节未动 |
| ② 改已有字段类型或既有业务语义 | 不触 | `SalesTrendPoint` **末尾追加**组件；`saleAmount`/`orderCount`/`buyerCount`/`avgOrderValue` 语义与取值路径逐字未变；`data.gmv`/`data.netSale`（汇总）仍只取 `metric_value` |
| ③ 改已发布 Flyway migration | 不触 | `V5`、`V2` 及全部已发布迁移**字节未动**（`git status` 无 `db/` 变更） |
| ④ 写/迁移正式 3306 数据 | 不触 | 本项无任何数据库写入（未连库、未跑 IT） |
| ⑤ 切 ACTIVE | 不触 | 无快照状态变更 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 不触 | 未触碰 `contract-specs/**`；改动的是 **R7-4 实现契约** `docs/contracts/analysis-viewmodel-r7-4.md`，且按该文件 L3-4 程序**加性**升版 v1.4（未改任何既有字段语义） |
| ⑦ 改 V3.0 总体架构 | 不触 | 无架构/链路变化：数据流仍是 ADS →(已有 DAO)→ 服务 → 响应 |
| ⑧ 改正式项目范围 | 不触 | 消费设计**已冻结**的列，范围不变 |
| ⑨ 删除已发布功能 | 不触 | 纯加性 |
| ⑩ 引入 V3.0 未规划的大型基础组件 | 不触 | 零新依赖、零新组件 |
| ⑪ 两种方案造成重大长期架构分叉 | 不触 | 唯一实现路径（透传既有列）；无备选架构 |

## 4. 实施清单（1 个代码提交 + 1 个 docs 提交）

代码侧（`feat(analysis): …`）：

1. `AnalysisService.SalesTrendPoint` 末尾**加性**追加 `BigDecimal netSaleAmount`（含口径边界 javadoc）。
2. `AnalysisService.salesTrend()` 末列投影 `AdsRows.asDecimal(row.get("net_sale_amount"))`。
3. 契约文档 `docs/contracts/analysis-viewmodel-r7-4.md` **v1.4**：文首版本块 + §3.1/§3.2 两处示例 JSON
   与字段说明（`overview.salesTrend[*]`、`sales.trend[*]`）。
4. 测试（新增 3 个 + 既有 4 处断言加强）：
   - `trendNetSaleAmountIsNullWhenAdsColumnAbsent`：ADS 缺列 ⇒ `null`，且**汇总** `netSale` 仍取
     `metric_value`（两个来源不得互相顶替）；
   - `trendNetSaleAmountParsesDriverForms`：`"500.00"`（字符串驱动形态）/`BigDecimal`/`"abc"`（畸形）
     ⇒ 解析/解析/`null`，顺序仍按 `dt` 升序；
   - `overviewTrendAlsoCarriesNetSaleAmount`：`overview` 与 `sales` **共用同一记录**，两处都给净额；
   - 夹具 `ads_sale_trend_m` 两行补 `net_sale_amount`（`500.00`/`1493.00`，与设计 L437 黄金值一致）。

> **程序偏差（据实记录）**：契约文档 L3-4 自订程序是「先改本文件再改代码」。本次**先写了测试与实现、
> 文档随后在同一次提交内补齐**，未按该顺序执行。偏差为过程性、不改变契约内容，但**如实登记**，
> 不以“同提交即等价”掩盖。

文档侧（`docs(status): F-53 …`）：本登记文件 + `docs/status-history/开发过程事实与决策记录.md` F-53 +
`docs/PROJECT_STATUS.md`（计数口径链、阶段4 段、backlog 关闭/新增行）。

## 5. 实测证据（RED / GREEN / 门禁）

| 步骤 | 命令 | 结果 |
|---|---|---|
| RED | `mvn -o -f analytics-server/pom.xml test -Dtest=AnalysisServiceTest` | **exit=1**，`COMPILATION ERROR` 9 处「找不到符号」：`AnalysisServiceTest.java:[131,44][132,44][181,47][199,47][220,32][221,32][222,32][233,71][234,63]`（新断言引用的 `netSaleAmount()` 尚不存在）。**边界**：签名/新 API 的 TDD 红只能表现为编译失败，无运行期红 |
| GREEN | 同上 | **exit=0**，`Tests run: 21, Failures: 0, Errors: 0, Skipped: 0 -- in …AnalysisServiceTest`（18 → 21，+3） |
| 门禁（第 1 次） | `.\scripts\run-tests.ps1 -Suite default -RunId s320_20260916_def -LogDir .verify\s320\def -Confirm` | **exit=1（真实回归，已被门禁抓住）**：`AnalysisSourcePolicyTest.everyAdsTableLiteralIsWhitelisted:83` —— 「AnalysisService.java 引用了白名单外的表名 `ads_sale_trend`」。成因：本项**javadoc 里逐字引用了设计 L333 的 Hive 裸表名**；该源码策略门禁（`AnalysisSourcePolicyTest.java:54` `Pattern ads_[a-z_]+` 扫分析包生产源码）要求分析包只能出现白名单表名。**修复**：改写 javadoc，不引裸表名（改为文字描述 + 行号锚点），并复测全包 `ads_` 裸名集合 = 7 个，全部在白名单内 |
| 门禁（第 2 次） | `-RunId s320_20260916_def2 -LogDir .verify\s320\def2` | 见 §5.1（本次收尾时补填） |

### 5.1 门禁通过记录

- 命令：`.\scripts\run-tests.ps1 -Suite default -RunId s320_20260916_def2 -LogDir .verify\s320\def2 -Confirm`
- 实测：`analytics-server` **936** 用例（`93+350+163+83+93+154`，metric-analysis 80→83）、
  `mall-simulator` 13、`synthetic-data-generator` 106 ⇒ 三棵树 **1055**（旧基线 1052 ⇒ 计数漂移＝本项 +3）；
  **唯一失败** = 已登记环境性红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`
  （`expected: 43, but was: 0`，S3-16…S3-19 历次相同，**不修不掩**）。
- 基线随本项更新（`scripts/run-tests.ps1`：`analytics-server` 933→936，并补 S3-20 口径注释块），
  复跑 `-RunId s320_20260916_def3 -LogDir .verify\s320\def3` 复核「计数 MATCH」。
- **表述纪律**：本档实测为「**计数 MATCH + 唯一红 = 该已登记环境性红**」，脚本因此输出 `[FAIL exit=7]`；
  **不得**把它写成 `[PASS exit=0]`，也不得把该环境性红算作本项失败。

## 6. 未测与边界（不得越界表述）

| 编号 | 未测/边界 | 说明 |
|---|---|---|
| U1 | **真库取值分布未测** | 未连 3306/3307，未跑 `AnalysisGoldenMySqlIT`（D 类，从未运行）⇒ 「真库 `ads_sale_trend_m.net_sale_amount` 实际值」**无证据** |
| U2 | **真 HTTP 响应未测** | 无 `@SpringBootTest`、未起 8091 ⇒ JSON 键名 `netSaleAmount` 只有编译期/契约文档证据，**无真实 HTTP 抓包证据** |
| U3 | 历史快照 `0` 的二义性**无法在数据上区分** | F7 已由 V5 声明「0 = 未回填占位」；本项只保证「缺列 ⇒ null」，**不**把 `0` 转成 `null`（那会篡改既有列语义 ⇒ 触门②） |
| U4 | 跨业务日退款**不回改**历史净额 | 设计 L428「退款归属期需冻结」**未裁决**（awaiting 总控） |
| U5 | 设计 **L506**「同归属口径 ADS GMV ≥ 净销售 ≥ 0」**未实现** | 全仓检索：仅 `AdsQualityJob.scala:158` 有 `net_sale_amount IS NULL` 非空检查、`TradeDwdJob.scala:76` 注释；**无**该不等式的规则码/断言。本项**不**在读侧加启发式纠正（否则变成第二个口径所有者） |
| U6 | `from`/`to` 不参与过滤（F8） | 既有缺口，本项**只据实记录**并登记 backlog，未修 |
| U7 | 阶段5 页面未展示 | F9；`web/src/**` 本项未改 ⇒ 不构成「页面已展示净销售额」 |
| U8 | 阶段4 其它骨架缺口 | 设计 L569「最多 90 天 / LIMIT 上限 200 / 超时 30 秒」的**读上限**仍未做；L544 Store 能力描述仍未实现（沿用 S3-19 登记） |

## 7. 检索证据（本项结论的可复现依据）

- `git grep -n "net_sale_amount" -- db`：`db/` 目录**不存在**；真实迁移在
  `analytics-server/platform-app/src/main/resources/db/metric/**`（本登记 §2 F2 用的就是该路径）。
- 分析包 ADS 裸名集合（修复后）：`ads_active_trend_m, ads_behavior_funnel_m, ads_data_quality_m,
  ads_hot_product_m, ads_product_conversion_m, ads_sale_trend_m, ads_user_profile_m` → 全部 ∈ 白名单。
- 前端消费点：`web/src/utils/chartOptions.js:11-23`、`views/Sales.vue:47,106,143`、`views/Overview.vue:121`。

## 8. 遗留与移交（R 类）

| 编号 | 遗留 | 处置 |
|---|---|---|
| R1 | 设计 L506 不变式（GMV ≥ 净销售 ≥ 0）未实现 | **登记 backlog**（写侧规则/质量规则候选）；读侧不自行实现。若按“质量规则集第 8 条”实现，需新增规则码 ⇒ 另行判类（可能触⑪：读侧启发式 vs 写侧规则两条路线） |
| R2 | 12 条质量规则覆盖（设计 §7.3 L503-515 / 指导书 §7-6）：④⑤⑥⑧⑨⑩⑪⑫ 等长期 `未做` | 本项**新登记一行 backlog**（此前只在 `docs/acceptance/v25-r01-coverage-20260914/MATRIX.md:145` 与 `docs/audit/v2-completeness-audit.md:351` 有限定判定） |
| R3 | `from`/`to` 不参与过滤（F8） | **新登记 backlog**（本项据实发现） |
| R4 | 阶段5 页面展示净销售额 | 登记为阶段5 开发项（前端一行 + 契约已就绪）；本项不越阶段实施 |
| R5 | 历史快照 `0` 占位与真实零净额不可区分 | 需「旧快照重跑发布」才能消除；登记 backlog |
| R6 | 读上限（90 天 / LIMIT 200 / maxRows）与 L544 能力描述 | 沿用 S3-19 登记，未动 |
