# S3-04 设计差异登记（2026-09-16）

> 提交：`2f70051`（`feat(metric): S3-04 漏斗落地整体加购率 cart_rate（DWS cart_users/cart_rate → ADS overall_cart_rate → metric_value）`），
> 分支 `feature/v3-development`，**未 merge main**。
> 本文件性质：**登记（register）**，不是「请求裁定才能动工」的阻塞项。按指导书 §12 L271，
> 代码 Agent **不修改** `docs/guidance/**` 与 `docs/design/**` 两份正式文档；本文件只如实登记
> 「设计原文要求什么／此前实现是什么／本轮改了什么／凭什么判定它不需要停工」。

---

## 0. 一句话结论

设计 §11.2 **L432** 逐字要求 `cart_rate` =「加购去重用户数 ÷ 浏览去重用户数」，
`docs/contracts/metric-dictionary.md` **L23** 已把它写进 16 行字典（源表 `dws_behavior_funnel_day`、日粒度、`event_time`），
`analytics_meta.metric_definition`（V2 种子，**已发布**）也已有该码 —— 但 **DWS / ADS / MySQL 镜像 / `metric_value` / 血缘表全都没有承载**：
`docs/contracts/metric-lineage.md` 自己把它登记在「**字典存在但尚无 ADS 承载**」表里（原文「**未落地**」，
并已写明应当怎么补：「需在 DWS 漏斗增加 `cart_users` 并扩 `ads_behavior_funnel`」）。
本轮按该既有登记计划补齐（DWS 加 `cart_users`+`cart_rate`、ADS 加整体率列 `overall_cart_rate`、
加性迁移 `V7`、发布侧映射 `metric_value(cart_rate)`），**未改任何既有列的名字/类型/顺序/取值算法**，
**未新增第 9 张 ADS 表、未新增漏斗阶段行**。
**判定：A 类（实现遗漏 + 纯加性）**，不触发 11 条破坏性决策门中的任何一条 ⇒ 按「默认自主连续开发」
直接实施、测试、提交。

同时**明确不做**：设计 §11.3 **L443** 要求「分类/渠道粒度见 G-04；**未决不输出假的分类下钻**」，
故本轮**未**给漏斗补 `category_id`/`channel` 维度下钻（G-04 未决），只落全站 `category_id = -1` / `channel = 'all'`。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §11.2 **L432** | 「\| buy_rate/cart_rate \| 支付/加购去重用户数÷浏览去重用户数 \| 标注独立集合或严格cohort \|」 | 分子 = `cart_add` **去重用户数**、分母 = `view` **去重用户数**（两侧都是用户集合，非事件数）；口径在 DWS 内冻结 |
| 字典 `docs/contracts/metric-dictionary.md` **L23** | 「\| cart_rate \| 加购率 \| `加购用户数 ÷ 浏览用户数`（用户去重口径） \| 日 \| event_time \| `dws_behavior_funnel_day` \|」 | 源表/粒度/时间字段与本轮实现**逐项对齐**；分母即同表 `view_users` |
| 字典 **L22** | 「\| cart_add_cnt \| 加购次数 \| `behavior_type='cart_add'` 事件数 \|」 | **不混用**：本轮落的是**率**（`cart_users` 去重用户），不是次数；`cart_add_cnt` 仍未落地（见 §6-R2） |
| 设计 §11.3 **L441** | 「首版当前存在日粒度宽松漏斗 view/intent/order/pay，各步独立去重…**禁止 min 截断**」 | 加购**不是第五阶段**：阶段集合仍恒为 `view/intent/order/pay` 四行；加购率是与 `overall_buy_rate` 同型的**整体率列** |
| 设计 §11.3 **L443** | 「严格顺序漏斗…；**分类/渠道粒度见 G-04**；未决不输出假的分类下钻。总用户不等于各分类 distinct 之和」 | 本轮**只做全站**（`category_id=-1`/`channel='all'`）；分类×渠道下钻**未做**，属 G-04 未决，不伪造 |
| 指导书 §7 阶段3 ①（L148） | 「对每个指标固定粒度、分子分母、**时间窗口**、金额/退款口径、**空值规则**和版本」 | 五要素在本轮一次性冻结（见 §2 口径声明） |
| 指导书 §7 阶段3 ②（L149） | 「Spark SQL 计算销售、用户、商品、漏斗及质量专题，**逐层对账**」 | 新 spec 含 DWS↔ADS 逐列、ADS↔DWD 独立 oracle、列序三向一致三组对账 |
| 指导书 §8 阶段3（L199） | 「稳定指标公式、分层对账与发布制品；失败保旧；**不靠前端/AI临时算出指标**」 | 加购率随制品（ADS + 镜像列 + `metric_value`）落库，不留给前端用「加购/浏览」现场拼 |
| 指导书 §12 **L271** | 「Code Agent 只提交设计差异请求，不自行修改两正式文档」 | 本轮**未改** guidance/design 任何字节 |
| 设计 §9.3 **L329** | 「`ads_behavior_funnel`/`_m`：历史已发布，**分类/渠道粒度缺口**」 | 缺口**仍未补**（G-04 未决）；本轮补的是同一行里的**加购率口径缺口**，两者不混为一谈 |
| 设计 §9.3 **L339** | 「`analytics_metric` 中 8 张 `_m` 是已证实体」 | 表数仍 8，**只加列不加表** |

**失败样例（改前真实状态）**：`metric_definition` 里有 `cart_rate`、字典 L23 与设计 L432 都有口径，
但 ADS 无列 ⇒ 取值为空；若有人「按名字硬算」，最容易踩的坑是把 `intent_users`（**收藏或加购**）当分子
（本夹具 0.8000 而不是 0.6000），或把分子做成 `view ∩ cart_add`（0.4000）——
两种都**不是** L432 写的「加购去重用户数 ÷ 浏览去重用户数」。新 spec 用比值判别力把这两条错路钉死。

---

## 2. 口径声明（本轮冻结，已写进 DWS KDoc、迁移头与语义层）

| 要素 | 本轮冻结取值 | 依据 / 证据 |
|---|---|---|
| 粒度 | 业务日 `dt` 一行（DWS 一行 → ADS 四行各自携带同一整体率） | 与既有 `dws_behavior_funnel_day` / `ads_behavior_funnel` 粒度一致；字典 L23「日」 |
| 时间窗口 | 分区日 `dt`（字典时间字段 `event_time` 决定行为归入哪一天） | DWS 既有窗口定义，本轮未改 |
| 分子 | 当日 `behavior_type = 'cart_add'` 的**去重用户数** `cart_users` | 设计 L432 + 字典 L23；`favorite`、`cart_remove` **都不算**（故 `cart_users ≤ intent_users`，两者不同义） |
| 分母 | 当日 `behavior_type = 'view'` 的**去重用户数**（= 既有 `view_users`，复用不重算） | 字典 L23「浏览用户数」；不接受「浏览次数」 |
| 空值规则 | 分母为 0（当日无浏览用户）⇒ `cart_rate` = **NULL**（`cart_users` 仍如实落库，绝不除零、绝不写 0 冒充「没人加购」）；发布侧遇 NULL **跳过** `metric_value`，但**不带走**同行的 `buy_rate` | 新 spec 用 `isNullAt` 断言；`AdsCartRateSpec` 第 4 例即「无浏览日 `cart_users=1` 而 `cart_rate` 为 NULL」 |
| 维度 | `category_id = -1`、`channel = 'all'`（全站），**不产出分类/渠道下钻** | 设计 L443 + G-04 未决 ⇒ 不伪造 |
| 版本 | `v1`（与 `metric_definition` 既有行一致） | 公式首次落地，无历史口径需区分 |
| ADS 列语义 | `overall_cart_rate` 与 `overall_buy_rate` **同型**：四行重复携带的**整体率**，ADS **只透传**不重算 | 加购不是阶段 ⇒ 无法作为 `conversion_rate`（阶段间转化）表达 |

**为什么必须落成「列」而不是「第五个 stage 行」**：`ads_behavior_funnel` 的行语义是**阶段**
（`stage` + `user_count` + `conversion_rate` = 后一阶段/前一阶段）。加购用户**不构成阶段序列的一环**
（设计 L441 的四阶已定），若硬造 `stage='cart'`，会让所有按 `stage` 遍历页面的消费方多出一档、
并暗示「加购 → 浏览」的假顺序 ⇒ 违反 L441 的「禁止 min 截断」精神。故按 `overall_buy_rate` 的既有模式
（整体率列，四行同值）扩展，**列序追加在末尾**。

**唯一属主**：分子/分母的算法**只在 `DwsSql.funnelDay` 里存在一份**；`AdsSql.funnel` 与发布侧
`MetricPublisher` 都只做透传/映射。`cart_users` 不落 ADS（ADS 只保留可对外展示的率列），
避免出现「两个加购用户数所有者」。

---

## 3. 本轮实施面（含列序所有者与守卫）

| 所有者 | 文件 | 变更 |
|---|---|---|
| Spark DWS 计算 | `spark-jobs/.../sql/DwsSql.scala` | `funnelDay`：子查询加 `COUNT(DISTINCT CASE WHEN behavior_type='cart_add' THEN user_id END) AS cart_users`；投影末尾加 `cart_users` 与 `CASE WHEN b.view_users = 0 THEN NULL ELSE CAST(b.cart_users AS DECIMAL(8,4))/b.view_users END AS cart_rate`；KDoc 写明「加购去重 ≠ `intent_users`，且加购不是第五阶段」 |
| Spark ADS 计算 | `.../sql/AdsSql.scala` | `funnel`：四个阶段行末尾各加 `cart_rate AS overall_cart_rate`（首行为显式别名，其余复用列名）；KDoc 写明四行同值、阶段集合不变 |
| Spark DDL（派生） | `.../job/LocalSchemaInitJob.scala` | DWS 漏斗表末尾加 `cart_users BIGINT, cart_rate DECIMAL(8,4)`；**正式** `ads_behavior_funnel` 与 `ads_behavior_funnel__staging` 末尾各加 `overall_cart_rate DECIMAL(8,4)`（三处列序都与列真源一致） |
| Spark 列真源 | `.../metric/MetricAdsSpec.scala` | `ads_behavior_funnel` 4 → **5** 列（表数仍 8） |
| Java 白名单 | `analytics-server/metric-analysis/.../MetricAdsCatalog.java` | 漏斗 4 → **5** 列（顺序一致） |
| MySQL 镜像 | `platform-app/src/main/resources/db/metric/V7__ads_behavior_funnel_cart_rate.sql` | **新增加性迁移**：`ADD COLUMN overall_cart_rate DECIMAL(8,4) NULL`；头部声明「为什么加性而非改 V2」「空值语义（无浏览 / 历史快照未计算，均不得当 0）」 |
| 迁移挂载说明 | `.../config/MetricFlywayInitializer.java` | KDoc 版本链补「→ V7 漏斗加购率」 |
| 发布侧 | `.../metric/publish/MetricPublisher.java` | 漏斗行**同时**取 `overall_buy_rate` / `overall_cart_rate`（各自「取首个非空」，与行序无关）；新增 `metric_value(cart_rate)`，`period` 仍为 `day:<ISO>`；**某一列为空时各自独立跳过** |
| 语义层（AI 提示词） | `analytics-server/ai-decision/.../ai/SemanticCatalog.java` | 漏斗登记 `overall_cart_rate`（含「四行同值；浏览为 0 时为 NULL」说明） |
| 血缘登记 | `.../ai/evidence/MetricLineage.java`、`docs/contracts/metric-lineage.md` | `cart_rate` 进已落地清单（**#14**）；从「尚无 ADS 承载」表移出（16 = **14 落地 + 2 未落地**） |
| 手持参考副本 | `warehouse/ddl/03-dws.sql`、`warehouse/ddl/04-ads.sql` | 同步（该两文件**无**自动化整体守卫，属人工同步） |
| 守卫（Scala） | `AdsCartRateSpec.scala`（新增 6 条） | DWS 加购口径、ADS 四行同值且 = DWS、**独立 DWD oracle 对账**、无浏览日 NULL 规则、阶段集合仍 4 且 ADS 仍 8 表、正式/暂存列序 = `spec.columns` |
| 守卫（Scala 文本） | `SqlTemplateSpec.scala`（+1 条） | 断言 DWS 加购分子**只取 `cart_add`**、分母 0 → NULL；并断言 ADS 漏斗**没有** `'cart'` 阶段字面量 |
| 守卫（Java） | `MetricPublisherMappingTest.java`（+2 条） | 发布侧 `cart_rate` 映射与 `day:` 期；两列 NULL 时**各自**跳过、互不带走 |

**兼容性承诺（可复核）**：漏斗表既有 4 列（`stage…overall_buy_rate`）与 DWS 既有 10 列的名字/类型/顺序
**一格未动**，新列全部在末尾；`metric V1`–`V6` 迁移文件**未改一个字节**；`AdsSql.TABLES` /
`MetricAdsSpec.TABLES` 仍是 **8** 张表；漏斗 `stage` 取值集合仍是 4 个。

---

## 4. 全量档守卫复核（本轮：**先跑全量**，未出现第二个盲区红）

**背景（S3-03 教训，见 F-36）**：加列类变更的「定向套件跑绿」**不足以**证明加列安全 ——
每一层 DDL 都可能有**第二个所有者**（手写 INSERT 夹具 / 硬编码镜像清单），只有全量档能证明没有漏网。
那次的 6 处红**只**在全量 spark 档出现。

**本轮做法与实测**：把「五处列序所有者」**一次改齐**后，**直接先跑全量 spark 档**（而不是先跑定向再补跑）：

- `Total number of tests run: 202`；`Suites: completed 23, aborted 0`；`succeeded 202, failed 0`；
  基线比对 `tests=202 MATCH`；JDK8 取证 True（日志 `gate/spark-jobs.log`）。
- 本轮**没有**出现「定向绿、全量红」：`AdsRfmRawValueSpec`/`AdsStableOrderSpec` 的手写夹具
  **不含** `dws_behavior_funnel_day`（S3-03 时它们写的是 `dws_user_trade_period`），
  所以「按位置 INSERT」的风险点在本轮不存在；`MetricAdsSpecTest.scala` 那份硬编码 Java 镜像清单
  **已在同一批改动里同步**（这正是 S3-03 §4 表里第 6 条红的位置）。
- 结论（可复核）：**本轮加列未漏任何第二所有者**；该结论的效力**仅**覆盖「本地全量 spark 档」，
  不覆盖下述未实测边界（§6）。

**仍未覆盖该盲区的地方（如实登记）**：`dws_behavior_funnel_day` 与 `ads_behavior_funnel` 在
**非本仓库**（外部脚本 / 在产 Hive / 他人分支）里的按位置 INSERT 无法被本地档证明；
`R7_ADDED_COLUMNS` 只覆盖概览表，**不覆盖**漏斗表（既有登记）。

**本轮真正起作用的第二所有者守卫（不是靠人眼）**：
`AiSqlDriftTest.语义层字段与DDL完全一致`（**双向**：迁移 DDL 列 ⊆ `SemanticCatalog` 列）、
`MetricAdsCatalogDdlConsistencyTest`（迁移文本列序 ↔ Java 白名单列序，逐字相等）、
`MetricAdsSpecTest`（硬编码的 Java 镜像清单 ↔ Spark 列真源）、
`SqlTemplateSpec`（SQL 文本断言）。四处都在本轮的「一次改齐」范围内同步，**并且**都真的会因漏改而变红 ——
这一点由 S3-03 的实测与上述断言的语义共同支持（本轮未做「故意漏一处」的反向取证，故表述到此为止）。

---

## 5. 为什么判定为 A 类（不触门），逐门核对

| 门 | 是否触发 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 否 | 只有 `ADD COLUMN` |
| ② 改已有字段类型或**既有业务语义** | **否** | 既有列的名字/类型/顺序/取值算法**全未动**；`view_users` 被复用为分母，语义未变（原本就是「浏览去重用户数」）；新增的是**此前不存在**的指标列 |
| ③ 改已发布 Flyway migration | 否 | `metric V1`–`V6`、`meta V1`–`V2` **字节未动**；`V7` 为**新增**文件 |
| ④ 写/迁移正式 3306 数据 | 否 | 本轮 **0 次连库**，未触碰 3306/3307/ACTIVE |
| ⑤ 切 ACTIVE | 否 | 未涉及 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | 已全目录检索 `cart_rate`：**0 命中** ⇒ 不覆盖本指标，未改任何契约文件。（`docs/contracts/metric-lineage.md` 属**可编辑**的血缘副本，非 `contract-specs/**`） |
| ⑦ 改 V3.0 总体架构 | 否 | 表/层/链路不变；**未新增第 9 张 ADS 表**（`AdsSql.TABLES`/`MetricAdsSpec.TABLES` 仍 8 张），**未新增漏斗阶段** |
| ⑧ 改正式项目范围 | 否 | 属 §11.2 L432 + 字典 L23 既有要求，未新增范围 |
| ⑨ 删除已发布功能 | 否 | 只增不减 |
| ⑩ 引入未规划大型基础组件 | 否 | 未加依赖、未加组件 |
| ⑪ 两种方案造成重大长期架构分叉 | 否 | 「整体率列」沿用 `overall_buy_rate` 的**既有**模式，无第二个算法属主；备选「新增 stage='cart' 行」被 §11.3 L441 的四阶定义排除，不构成等价分叉 |

**G-04 边界声明**：设计 L443 把「分类/渠道粒度」明确挂到未决项 G-04。本轮**只补全站口径**，
**没有**实现任何分类/渠道下钻，**没有**在 ADS 里写死假的分类行 ⇒ 不触 ⑦/⑧，也不必等 G-04 裁定。

---

## 6. 未实测边界与待批注项（不得越界表述）

1. **MySQL `V7` 真库列存在性未测**：`MetricAdsCatalogDdlConsistencyTest` 只证「迁移文本 ↔ Java 白名单」
   一致（解析 SQL 文本，不连库）。`isolated` 档（WSL 3307）**无监听、本轮未跑**。
2. **两个真库 IT 本轮未运行**（`MetricPublisherMySqlIT`、`MetricAdsMySqlIT`）：
   本轮**刻意未改**其夹具与期望值 —— 新列**可空**，夹具不提供 `overall_cart_rate` ⇒ 发布侧取到 NULL
   ⇒ 跳过 `metric_value(cart_rate)` ⇒ 该 IT 既有的 `metricValues()==11` / `metricValueCount()==11`
   **不需要变**、也不会因本轮加列而失败。但这一推断**没有实测证据**，状态仍为**未测**。
3. **在产 Hive 已建表的补列未做**：`LocalSchemaInitJob` 的 `CREATE TABLE IF NOT EXISTS` 对已存在表不生效
   ⇒ 已建过 `dws_behavior_funnel_day` / `ads_behavior_funnel` 的库需显式 `ALTER TABLE … ADD COLUMNS`（部署事项）；
   `R7_ADDED_COLUMNS` 只覆盖概览表，**不覆盖**漏斗表（既有登记）。
4. **新增列尚无消费方**：`AnalysisService`（L225 仍只读 `overall_buy_rate`）/ `AnalysisViewModel` / 前端
   与 AI 罐装 SQL（`RuleBasedSqlFallback` 仍只 SELECT 旧列）**均未消费**加购率 ⇒ 服务层与页面消费
   归**阶段4/5**（与 S3-01/S3-02 同类边界）。
5. **跨层质量规则未覆盖新列**：`ADS_DWS_FUNNEL_RECONCILE`（BLOCKING）的作用域是
   **4 个 stage 的 `user_count` 汇总**（`AdsQualityJob` L105-121），**不校验** `overall_buy_rate`/
   `overall_cart_rate` ⇒ 新列在**在产质量门里没有对账守卫**（本轮的等价断言只存在于新 spec 里）。
   扩该规则作用域＝改已发布规则定义语义（`QualityRuleCatalog` 是唯一属主 + `db/meta/V19` 已发布种子）⇒ backlog。
6. **`cart_add_cnt`（加购次数）仍未落地**：字典 L22 是**次数**口径、与本轮的**率**不同指标码；
   本轮**未**顺带落地它，也未把 `cart_rate` 当成它的替代。
7. **判别力上限**：新 spec 的 oracle 由 DWD 行为明细独立重算（不读 DWS 结果），但仍是
   `local[1]` + `catalogImplementation=in-memory`，**未跑真实 `spark-submit`、未连 Hive metastore**
   ⇒「本地测试通过」**不得**表述为「在产通过」。
8. **`warehouse/ddl/03-dws.sql`、`04-ads.sql` 无自动化整体守卫**（既有状态）⇒ 本次靠人工同步。
9. **`AiSqlSecurityTest`（L50-60）的白名单桩未改**：它是 mock 桩、不参与 `SemanticCatalog` 漂移检查；
   该套件 33 条本轮全绿（实测），但也**不构成**「语义目录已覆盖新列」的证据 ——
   该证据来自 `AiSqlDriftTest.语义层字段与DDL完全一致` 7 条全绿，
   而它的断言是**双向**的（语义层列 ⊆ 迁移 DDL 列，且除 `snapshot_id` 外迁移 DDL 列 ⊆ 语义层列）
   ⇒ 若本轮只加 DDL 而漏改 `SemanticCatalog`，这条会以「语义层缺少真实列：`ads_behavior_funnel_m.overall_cart_rate`」变红。

**待总控批注（均不阻塞）**：

- **R-1｜`ADS_DWS_FUNNEL_RECONCILE` 是否应扩到整体率列**：现状只对账 4 个 `user_count`。
  若要求把 `overall_buy_rate`/`overall_cart_rate` 纳入，需改 `QualityRuleCatalog` 规则定义 +
  `db/meta/V19` 种子（**已发布迁移** ⇒ 门③邻域，须走新加性迁移），并同步 `AdsQualityJob`。
  *本轮*：不动，登记 backlog。
- **R-2｜`cart_add_cnt`（加购次数）**：字典 L22 存在但无 ADS 承载。是否在 `dws_product_behavior_day`
  已有 `cart` 件数之外，为「漏斗侧加购次数」另设落地位置？*本轮*：不动。
  **→ 2026-09-16 由 S3-08 关闭**：落点裁决为 `ads_operation_overview.{fav_cnt, cart_add_cnt}`（**概览表**，
  不是漏斗侧），理由与实现见 `docs/acceptance/s3-08-fav-cart-count-20260916/DESIGN-DIFF-REGISTER-20260916.md`
  §2（漏斗 stage 行语义是各步去重用户数、设计 L441 固定四阶段；商品侧 `ads_hot_product.fav/cart` 是热度权重分量）。
  同步落地：加性迁移 `db/metric/V10`、字典种子 `db/meta/V24`、发布映射与语义层两列、Spark 行为 spec 6 条 +
  Java 五方对账守卫 5 条。**真库应用 V10/V24 仍未做**（与本文件 §6.2 同类未测边界）。
- **R-3｜G-04（漏斗分类/渠道粒度）**：设计 L329/L443 两处都指向它。本轮按「未决不伪造」只做全站；
  若总控希望先按「`category_id=-1` 之外只补 `channel`」或某种降级方案落地，请批注。
- **R-4｜加购率是否也应进 `OVERVIEW_TO_METRIC` 之类的发布码集合**：现状只进漏斗行映射
  （`metric_value(cart_rate)`，`period=day:`），未进 `/overview` 概览响应。是否需要在阶段4
  把它加入概览 API 的返回，请批注。

---

## 7. 本轮证据（本地，不入库）

`.verify/v3-stage3/s3-04-cart-rate/`：

- `red/spark-spec-red.log`（行为 RED：`AdsCartRateSpec` **succeeded 1 / failed 5**，
  原文 `UNRESOLVED_COLUMN.WITH_SUGGESTION … 'cart_users' cannot be resolved. Did you mean
  [pay_users, order_users, view_users, intent_users, channel]`（×3）
  与 `'overall_cart_rate' cannot be resolved`（×2）；唯一通过者＝列序对账用例，因为它在改前改后都应成立）
- `green/spark-affected-green.log`（受影响 3 个套件 `AdsCartRateSpec` + `SqlTemplateSpec` +
  `MetricAdsSpecTest` 合并跑：**succeeded 34, failed 0**，`All tests passed`）
- `green/java-modules-green.log`（`metric-analysis` + `ai-decision` + `platform-common` 真 Maven：
  **57 / 92 / 90**，含新用例 `MetricPublisherMappingTest` 6/6，`BUILD SUCCESS`、`exit=0`）
- `gate/spark-jobs.log`（**`[PASS exit=0]`**：`Total number of tests run: 202`、
  `Suites: completed 23, aborted 0`、`succeeded 202, failed 0`、`tests=202 MATCH`、`新写=True`、`JDK8=True`）
- `gate/default-all.log`（analytics-server `882 MATCH`（明细 90+350+148+57+92+145）、mall `13 MATCH`、
  generator `106 MATCH`、三棵树 `1001`、无 DRIFT、`[FAIL exit=7]` —— 唯一红为已登记环境性
  `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，本工作树 `landing/manifests` 不存在，
  43 份历史清单仅在主工作树，本轮**未修、未复制 manifest、未用开关掩盖**）
- `commit-msg.txt`（本提交的完整提交信息留痕）
- 交叉引用：F-37（`docs/status-history/开发过程事实与决策记录.md`）为同一批证据的文字留痕。
