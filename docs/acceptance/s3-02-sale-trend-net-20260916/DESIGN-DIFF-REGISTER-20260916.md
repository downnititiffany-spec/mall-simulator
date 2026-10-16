# S3-02 设计差异登记（2026-09-16）

> 提交：`5d3ef0e`（`feat(ads): S3-02 ads_sale_trend 补净销售额（设计 §9.3 L333 / §11.2 L428）+ 修语义层守卫 ALTER 盲区`），
> 分支 `feature/v3-development`，**未 merge main**。
> 本文件性质：**登记（register）**，不是「请求裁定才能动工」的阻塞项。按指导书 §12 L271，
> 代码 Agent **不修改** `docs/guidance/**` 与 `docs/design/**` 两份正式文档；本文件只如实登记
> 「设计原文要求什么／此前实现是什么／本轮改了什么／凭什么判定它不需要停工」。

---

## 0. 一句话结论

设计 §9.3 **L333** 逐字写 `ads_sale_trend`/`ads_sale_trend_m`「历史已发布，**net_sale 等字段需补**」，
§11.2 **L428** 给出净销售口径；此前趋势表**只有 4 列**（`order_count/buyer_count/sale_amount/avg_order_value`），
**没有净销售额** ⇒ 同一 dt 上趋势表与大盘表（已带 `net_sale_amount`）**给出两种收入视图**。
本轮按原文补齐（纯**末尾追加** 1 列，值**直接取上游 `dws_trade_day.net_sale_amount`**，不在 ADS 另写算法），
并同轮修掉一处使该变更**无法被守住**的守卫盲区（语义层漂移守卫解析不到 `ALTER TABLE … ADD COLUMN`）。
**判定：A 类（加法 + 修正守卫遗漏）**，不触发 11 条破坏性决策门中的任何一条 ⇒ 按「默认自主连续开发」
直接实施、测试、提交。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §9.3 **L333** | 「\| ads_sale_trend \| ads_sale_trend_m \| 历史已发布，**net_sale等字段需补** \|」 | 本次即该「补」；Spark 投影 + Java 白名单 + MySQL 加性迁移同步 |
| 设计 §11.2 **L428** | 「\| 净销售 \| 同口径支付金额−成功退款金额 \| 真实收入方向，**退款归属期需冻结** \|」 | 净额**只透传**上游 `dws_trade_day.net_sale_amount`（退款只计 `final_paid_flag=1`）；「归属期冻结」的现状边界见 §5-③ |
| 设计 §11.2 **L437** | 「黄金55条历史标准值：… 净销售**1493.00** …只针对该输入和对应定义版本」 | 新 spec 用**自建夹具**核 `150.00`；黄金 1493.00 只作注释锚点，**未**把黄金值套到本夹具（避免跨输入套用） |
| 指导书 §7 阶段3 ①（L148） | 「对每个指标固定粒度、分子分母、时间窗口、**金额/退款口径**、空值规则和版本」 | 退款口径 = 只扣已支付订单的退款额；空值规则由质量门规则 3 覆盖新列 |
| 指导书 §7 阶段3 ②（L149） | 「Spark SQL 计算销售、用户、商品、漏斗及质量专题，**逐层对账**」 | 新增「ADS ↔ DWS 逐列一致」与「ADS ↔ ADS 跨表同口径」两组对账断言 |
| 指导书 §8 阶段3（L199） | 「稳定指标公式、**分层对账**与发布制品；失败保旧；**不靠前端/AI临时算出指标**」 | 净额随制品落库（ADS + 镜像列），不再需要前端用「销售额−退款」临时拼 |
| 指导书 §12 **L271** | 「Code Agent 只提交设计差异请求，不自行修改两正式文档」 | 本轮**未改** guidance/design 任何字节 |

**「字段需补」的失败样例（改前真实状态）**：趋势表能回答「今天卖了多少」，**不能**回答「扣掉退款还剩多少」；
而大盘表同一 dt 能回答。两张 ADS 表对同一个业务日**给出两个收入方向** —— 若页面上两处并排展示，
用户会看到「趋势净额缺失」与「大盘净额有值」，属于 §9.3 明确点名的缺口。

---

## 2. 本轮实施面（五处列序所有者 + 参考副本 + 守卫）

| 所有者 | 文件 | 变更 |
|---|---|---|
| Spark 计算 | `spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala` | `saleTrend` 投影末尾追加 `net_sale_amount`（直接 `SELECT … net_sale_amount FROM dws_trade_day`）；KDoc 写明口径、同源理由与「跨业务日退款重结未实现」边界 |
| Spark DDL（派生） | `.../job/LocalSchemaInitJob.scala` | 正式表 + `ads_sale_trend__staging` 同步补 `net_sale_amount DECIMAL(18,2)` |
| Spark 列真源 | `.../metric/MetricAdsSpec.scala` | `ads_sale_trend` 4 → **5** 列 |
| Java 白名单 | `analytics-server/metric-analysis/.../MetricAdsCatalog.java` | `ads_sale_trend_m` 同步 5 列 |
| MySQL 镜像 | `platform-app/src/main/resources/db/metric/V5__ads_sale_trend_net_sale.sql` | **新增加性迁移**（单条 `ALTER TABLE … ADD COLUMN`，`NOT NULL DEFAULT 0`） |
| 迁移挂载说明 | `.../config/MetricFlywayInitializer.java` | KDoc 版本链补「→ V5 销售趋势净销售额」 |
| 语义层（AI 提示词） | `analytics-server/ai-decision/.../ai/SemanticCatalog.java` | `saleTrend.put("net_sale_amount", …)`（守卫 RED 后补，见 §3） |
| 质量门 | `.../job/AdsQualityJob.scala` | 规则 3 判据抽到伴生对象 `keyPredicates(ns)`，sale-trend 谓词补 `net_sale_amount IS NULL` |
| 手持参考副本 | `warehouse/ddl/04-ads.sql` | 同步（该文件**无**自动化整体守卫，见 §4-④） |
| 服务层夹具 | `MetricPublisherMySqlIT.java`、`MetricPublishValidatorTest.java`、`AiSqlSecurityTest.java`、`TextToSqlAuditTest.java` | 真库 IT 与白名单替身夹具补列（其中 `MetricPublisherMySqlIT` 本轮**未运行**） |
| 守卫（Scala） | `AdsSaleTrendNetSaleSpec.scala`（新增 6 条） | 透传/血缘/跨表对账/DDL 列序/质量规则真命中/SQL 文本钉 |
| 守卫（Java） | `AiSqlDriftTest.java` | 加性迁移解析（CREATE + 有序 ALTER）+ 新自测 `迁移解析覆盖后续ALTER加列` |
| 既有钉子 | `MetricAdsSpecTest.scala` | Java 镜像列数同步 |

**兼容性承诺（可复核）**：`ads_sale_trend` 已发布的 4 列（`order_count, buyer_count, sale_amount,
avg_order_value`）**名字/类型/顺序一格未动**，新列在末尾；`V1`–`V4` 迁移文件**未改一个字节**。

---

## 3. 同轮修掉的守卫盲区（为什么它属于「修正遗漏」而不是「加测试凑数」）

**现象（实测，非读代码推断）**：`AiSqlDriftTest.ddlTables()` 原先只匹配 `CREATE TABLE … ENGINE`，
**任何** `ALTER TABLE … ADD COLUMN` 都不进表模型 ⇒ 该套件的核心断言「**语义层缺少真实列**」
对**加性迁移新增的列永不触发**。

**三段证据（`red/` 目录，均本地实跑）**：

| 段 | 状态 | 实测结果 | 说明 |
|---|---|---|---|
| ① 行为 RED | 新 spec 先写 | `AdsSaleTrendNetSaleSpec` **6 run / 1 pass / 5 fail**，失败原文 `UNRESOLVED_COLUMN.WITH_SUGGESTION … net_sale_amount` | 证明「净额确实不存在」，不是断言写错 |
| ② 守卫盲区 | `V5` 已存在 + 白名单已加列 + **语义层未加** | `MetricAdsCatalogDdlConsistencyTest` **3/3 PASS** 且 `AiSqlDriftTest` **6/6 PASS** | **这条绿就是缺陷证据**：真实列存在但语义层缺登记，守卫一声不响 |
| ③ 修后 RED→GREEN | 解析器升级后 | `AiSqlDriftTest` **7 run / 1 fail**，原文 `语义层缺少真实列：ads_sale_trend_m.net_sale_amount（Prompt 不完整会诱发模型编造列名）`（`AiSqlDriftTest.java:184`）→ 补 `SemanticCatalog` 后 **7/7 PASS** | 守卫恢复牙齿，且新自测同时覆盖 V5 与 V4 的加列 |

**为什么算缺陷**：语义层是 Text2SQL 的**提示词真源**，列缺失会让模型**编造列名**（对话失败或返回错数），
而守卫的存在意义正是「DDL 变了、提示词没跟上」这一类漂移。守卫**只对了一半输入形态**（只认 CREATE），
属实现遗漏而非口径问题。

**残留（如实登记，未改）**：迁移文件名按**字典序**排序，`V10+` 会错序；当前解析结果进 `Set`，
错序无害，故本轮不动（见 backlog）。

---

## 4. 为什么判定为 A 类（不触门），逐门核对

| 门 | 是否触发 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 否 | 只 `ADD COLUMN`，无任何 DROP |
| ② 改已有字段类型或**既有业务语义** | **否** | ①4 个既有列类型/名字/顺序未动；②`order_count/buyer_count/sale_amount/avg_order_value` 的**取值算法一字未改**（同一条 `SELECT … FROM dws_trade_day`，只多选一列）；③净额口径由设计 §11.2 L428 给定，且**取上游已有列**，未新造公式 |
| ③ 改已发布 Flyway migration | 否 | `V1`–`V4` 字节未动，`V5` 为**新增**文件 |
| ④ 写/迁移正式 3306 数据 | 否 | 本轮 **0 次连库**，未触碰 3306/3307/ACTIVE |
| ⑤ 切 ACTIVE | 否 | 未涉及 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | 已全目录检索：`contract-specs/**` 内**无** `ads_` 前缀表名，**无** `net_sale`/`sale_trend`/`sale_amount`/`buyer_count` 任何引用 ⇒ **不覆盖本表**，未改任何契约文件。（`warehouse/ddl/04-ads.sql` 是**人工参考副本**、不在 `contract-specs/**`） |
| ⑦ 改 V3.0 总体架构 | 否 | 表/层/链路不变 |
| ⑧ 改正式项目范围 | 否 | 属 §9.3 L333 既有要求，未新增范围 |
| ⑨ 删除已发布功能 | 否 | 只增不减；旧 4 列仍可读 |
| ⑩ 引入未规划大型基础组件 | 否 | 无新组件（未加依赖） |
| ⑪ 两种方案造成重大长期架构分叉 | 否 | 净额**只有一个实现**（DWS 拥有公式，ADS 透传）；备选方案「ADS 里重算 `SUM(amount−refund)`」会造出**第二个口径所有者**，属反熵反面 ⇒ 不构成等价分叉 |

**边界声明**：「退款归属期冻结」（L428 后半句）本轮**只做到同日透传**，跨业务日重结**未实现** ——
这是**未做的部分**，不是「改了语义」；已在 §5-③ 与 backlog 明确登记。

---

## 5. 未实测边界（不得越界表述）

1. **MySQL 新列真库存在性未测**：`MetricAdsCatalogDdlConsistencyTest` 只证「迁移文本 ↔ Java 白名单」
   一致（解析 SQL 文本，不连库）。`isolated` 档（WSL 3307）**无监听、本轮未跑**；
   `MetricPublisherMySqlIT` 本轮**未运行**（夹具已补列，但无证据）。
2. **`V5` 的历史回填语义**：`NOT NULL DEFAULT 0` 对**已存在的历史快照行**回填 `0`，这是
   **未知占位，不得当真实 0**（已写进迁移头部注释）；真库上的实际回填效果**未观测**。
3. **退款归属期跨业务日重结未实现**：现状 = 退款额按 `OrderTradeCompiler` 累计到**该订单所在业务日**
   的明细行，与大盘表完全一致；「跨日落地的退款是否应回改历史业务日净额」属**口径冻结类**问题，
   本轮只登记（潜在门 ②/⑪ 邻域，需总控先冻结归属期）。
4. **在产 Hive 已建表的补列未做**：`LocalSchemaInitJob` 的 `CREATE TABLE IF NOT EXISTS`
   对**已存在**表不生效 ⇒ 已建过 `ads_sale_trend` 的库需显式 `ALTER TABLE … ADD COLUMNS`（部署事项）。
5. **新增列尚无消费方**：`AnalysisService.T_SALE_TREND`/`AnalysisViewModel` 仍按旧 4 列直通
   ⇒ 服务层/页面消费净额归**阶段4/5**。
6. **证据环境**：全部为 Scala `local[1]` ＋ `catalogImplementation=in-memory`，
   **未跑真实 `spark-submit`、未连 Hive metastore** ⇒「本地测试通过」**不得**表述为「在产通过」。
7. **`warehouse/ddl/04-ads.sql` 无自动化整体守卫**（既有状态；D-09 已把 `_m` 命名漂移归 `V25-C01`）
   ⇒ 该文件本次靠人工同步，未被任何测试覆盖。
8. **判别力的上限**：净额口径的判别探针（未支付订单带退款额）证明的是「实现是否按
   `final_paid_flag=1` 过滤」；**多行订单的部分退款分摊**仍**未测**（夹具每单 1 行明细）。
9. **`repeat_rate` 本轮未做**：§11.3 L433 要求「**声明观察期和变体**」，属需先冻结口径的独立任务
   （下一任务），本轮不夹带。

---

## 6. 若总控认为需另行处置

本登记**不阻塞**任何后续开发。若总控对以下任一处置有不同意见，请在登记上批注，代码侧按批注执行
（均为小改，不影响主线）：

- **R-1**：净销售额是否应在 ADS 侧**独立成列**（本轮方案：透传 DWS 已有列，单一口径所有者）。
  *现状*：已按透传实施并有跨表对账断言；替代方案是在 ADS 重算，会造出第二个口径所有者。
- **R-2**：「退款归属期冻结」是否要在阶段3 内做**跨业务日重结**。
  *现状*：仅同日透传，跨日重结登记为边界；涉及「归本期/归原单期」两方案 ⇒ 需先冻结口径。
- **R-3**：`V5` 对历史快照回填 `0` 是否可接受（现按「未知占位」登记）。
  *现状*：若不可接受，替代做法是允许 `NULL` 或按 `sale_amount − refund_amount` 反算历史行
  （后者会**改变历史语义** ⇒ 反而触门 ②，故本轮未做）。
- **R-4**：`AiSqlDriftTest` 迁移解析的**字典序排序**是否现在改成数值排序。
  *现状*：`V10+` 会错序，当前进 `Set` 无害 ⇒ 未改，登记为 backlog。

---

## 7. 本轮证据（本地，不入库）

`.verify/v3-stage3/s3-02-sale-trend-net/`：

- `red/spec-red.log`（行为 RED：`AdsSaleTrendNetSaleSpec` 6 run / 1 pass / 5 fail，
  `UNRESOLVED_COLUMN.WITH_SUGGESTION … net_sale_amount`）
- `red/guard-blindspot.log`（**守卫盲区证据**：`V5` + 白名单已加列而语义层未加时，
  `MetricAdsCatalogDdlConsistencyTest` 3/3 PASS 且 `AiSqlDriftTest` 6/6 PASS）
- `red/guard-red.log`（解析器升级后 `AiSqlDriftTest` 7 run / 1 fail：
  `语义层缺少真实列：ads_sale_trend_m.net_sale_amount`）
- `green/spec-green.log`（聚焦套件 `AdsSaleTrendNetSaleSpec` **6/6 PASS**）
- `green/modules-green.log`（`analytics-server` 四模块真 Maven：platform-common **90**、
  metric-analysis **51**、ai-decision **92**（含新自测），`BUILD SUCCESS`、`exit=0`）
- `green/gate-spark.log`（**`[PASS exit=0]`**：`Total number of tests run: 189`、
  `Suites: completed 21, aborted 0`、`succeeded 189, failed 0`、`tests=189 MATCH`、`新写=True`、`JDK8=True`）
- `green/gate-default.log`（analytics-server `876 MATCH`、mall `13 MATCH`、generator `106 MATCH`、
  三棵树 `995`、无 DRIFT、`[FAIL exit=7]` —— 唯一红为已登记环境性
  `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`）
- 交叉引用：F-35（`docs/status-history/开发过程事实与决策记录.md`）为同一批证据的文字留痕。
