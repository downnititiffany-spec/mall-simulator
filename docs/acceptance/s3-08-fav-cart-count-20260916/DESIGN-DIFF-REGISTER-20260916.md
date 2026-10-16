# S3-08 设计差异登记（2026-09-16）

> 提交：`feat(ads): S3-08 ads_operation_overview 落收藏/加购次数 fav_cnt/cart_add_cnt + 字典种子 V24 补齐`，
> 分支 `feature/v3-development`，**未 merge main**。
> 本文件性质：**登记（register）**，不是「请求裁定才能动工」的阻塞项。按指导书 §12 L271，
> 代码 Agent **不修改** `docs/guidance/**` 与 `docs/design/**` 两份正式文档；本文件只如实登记
> 「设计原文要求什么／此前实现是什么／本轮改了什么／凭什么判定它不需要停工／哪些必须停工」。

---

## 0. 一句话结论

设计 §11.2 **L425** 逐字写「收藏/加购 \| **对应行为事件数**，用户转化时另算去重用户数 \| 行为」；
指标字典 `docs/contracts/metric-dictionary.md:21/:22` 也早已登记
`fav_cnt = count(favorite 事件)`、`cart_add_cnt = count(cart_add 事件)`，源表 `dwd_user_behavior_detail`。
**此前状态**：这两枚码**只有字典、没有任何承载** —— ADS 概览表无对应列、`MetricAdsSpec`/`MetricAdsCatalog`
无列名、`MetricPublisher.OVERVIEW_TO_METRIC` 无映射、`analytics_meta.metric_definition` **无种子行**
（`db/meta/V2__platform_pipeline_quality.sql:63-78` 的 15 行里没有它们）。
也就是说：字典承诺的「收藏次数/加购次数」在系统里既算不出来，也**即使加上列也发布不出去**
（发布校验 `MP_METRIC_DICT_VERSION` 只认 `metric_definition` 里的码）。
本轮把这条链一次做通：① Spark 概览 SQL 按事件条数计算；② ADS 概览表末尾追加两列（Hive 正式/暂存 + MySQL 加性迁移）；
③ 发布映射同名搬运进 `metric_value`；④ 补 meta 字典种子（V24）；⑤ AI 语义层与安全白名单同步；
⑥ 新增**五方对账守卫**（字典 ↔ 种子 ↔ 发布映射 ↔ 迁移 ↔ `MetricAdsSpec`）把「次数 vs 去重人数」钉死。
**判定：A 类（纯加性 + 补齐实现遗漏）**，不触发 11 条破坏性决策门中的任何一条
⇒ 按「默认自主连续开发」直接实施、测试、提交。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §11.2 **L425** | 「\| 收藏/加购 \| **对应行为事件数**，用户转化时另算去重用户数 \| 行为 \|」 | 两列取值 = `favorite`/`cart_add` **事件条数**；「去重用户数」被本轮**显式排除**（守卫钉住 `COUNT(CASE …)` 而非 `COUNT(DISTINCT CASE …)`） |
| 设计 §11.3 **L441** | 「首版当前存在日粒度宽松漏斗view/intent/order/pay，各步独立去重。…禁止min截断。」 | 收藏/加购**不进漏斗**：漏斗阶段固定四步、行语义是**去重用户数**；把它们塞进 stage 行会同时破坏「四个阶段」与「人数语义」两条 |
| 设计 §9.3 **L320** | 「ADS…每行带snapshot／**定义版本**／业务日期，发布可追溯」 | 两码随 `metric_value.definition_version = v1` 发布（版本来自 V24 种子）；ADS 行由 `snapshot_id`/`dt` 定位 |
| 设计 §11.5 **L455** | 「商品排行按热度/销量/金额并有稳定次序键。」 | 商品侧 `ads_hot_product.fav/cart` **保持不动**（那是按商品热度权重的计数，不是全站次数指标）；本轮不碰排序键 |
| 指导书 §7 阶段3 ①（**L148**） | 「对每个指标固定粒度、分子分母、时间窗口、金额/退款口径、**空值规则和版本**。」 | 七要素在 §2 逐项冻结；版本 `v1` 落 V24 种子 |
| 指导书 §7 阶段3 ②（**L149**） | 「Spark SQL 计算销售、用户、商品、漏斗及质量专题，**逐层对账**。」 | Spark 侧新增 ADS ↔ DWD 重算 ↔ DWS `dws_user_behavior_day` 三方对账断言；Java 侧新增五方文本对账守卫 |
| 指导书 §8 **L199** | 「稳定指标公式、分层对账与发布制品；失败保旧；**不靠前端/AI临时算出指标**。」 | 公式落在 Spark SQL 一处；AI 语义层只登记**列名与口径说明**，不提供算法 |
| 指导书 §12 **L271** | 「Code Agent 只提交设计差异请求，不自行修改两正式文档。」 | 本轮**未改** guidance/design 任何字节 |
| 指标字典 `docs/contracts/metric-dictionary.md:21` | 「\| fav_cnt \| 收藏次数 \| `count(favorite 事件)` \| 日 \| event_time \| dwd_user_behavior_detail \|」 | 公式/粒度/时间字段/源表逐字落地 |
| 指标字典 `docs/contracts/metric-dictionary.md:22` | 「\| cart_add_cnt \| 加购次数 \| `count(cart_add 事件)` \| 日 \| event_time \| dwd_user_behavior_detail \|」 | 同上 |
| meta 种子 `db/meta/V2__platform_pipeline_quality.sql:63-78` | 15 行字典种子（**含** `pv`/`uv`/`dau`，**不含** `fav_cnt`/`cart_add_cnt`） | 缺行由本轮 **V24** 以「新码 → 新加性迁移」补齐（与 V13 补 `full_refund_rate` 同型） |
| S3-04 登记 R-2（`docs/acceptance/s3-04-cart-rate-20260916/DESIGN-DIFF-REGISTER-20260916.md:171`） | 「R-2｜`cart_add_cnt`（加购次数）…」登记为「仍未落地，落点建议商品/漏斗侧」 | 本轮**关闭 R-2**：落点定在概览表（理由见 §2 与迁移文件头注），次数口径与加购率**并存不混淆** |
| 设计 §12.5 **L524-535**（发布顺序） | 表形/行数/定义版本/内容验证 | `MP_METRIC_DICT_VERSION` 要求每个待写码在字典里；**这正是「只加列不发种子 = 永远发布失败」的原因**，V24 是该规则的正面满足 |

**失败样例（改前真实状态）**：页面要展示「今日收藏 128 次、加购 240 次」——
ADS 概览表没有这两列，只能退化成让前端/统计接口临时按 DWD 明细现算（违反 L199「不靠前端/AI临时算出指标」），
或者误用漏斗侧 `cart_rate` 的**去重用户数**冒名顶替（数字会系统性偏小，且同一张卡片两处口径不一致）。

---

## 2. 口径声明（本轮冻结，七要素）

| 要素 | 冻结取值 | 依据 / 证据 |
|---|---|---|
| 指标码 | `fav_cnt`、`cart_add_cnt` | 字典 L21/L22（**唯一口径来源**，不得另造码） |
| 粒度 | **日**、全站（不分商品/分类/地区） | 字典「粒度」列 = 日；与同表 `pv` 同粒度 |
| 分子 | `favorite` / `cart_add` 行为**事件条数**（`COUNT(CASE WHEN behavior_type = … THEN 1 END)`） | 设计 L425「对应行为事件数」 |
| 分母 | **无**（计数型指标，不是比率） | 与 `pv` 同型；不引入 `cart_rate` 的分母语义 |
| 时间窗口 | 单业务日 `dt`；归日字段 `event_time`（字典「时间字段」列） | 与 `pv`/`dau` 同型；**不是**观察期窗口型，`period = day:<ISO>` |
| 金额/退款口径 | **不涉及**（按事件计数，不取金额、不扣退款） | 字典公式无任何金额项 |
| 空值规则 | **无该事件的行为日写 0**（不是 NULL）；NULL 只表示「历史快照未计算这两列」 | 与同表 `pv` 一致（不存在即 0）；「未计算 ≠ 真实 0」写进 V10 迁移注释 |
| 排除项 | `cart_remove` **不计**入 `cart_add_cnt`；`view` 不计入 `fav_cnt` | 字典公式逐字只认 `favorite`/`cart_add` 两种事件 |
| 源表 | `dwd_user_behavior_detail`（**直取**，不绕 DWS 二次聚合） | 字典「数据来源」列 = `dwd_user_behavior_detail`；跨层一致性由对账断言钉住（见 §3） |
| 版本 | `v1`（首次落地）；此后再改口径必须升版本 | V24 种子 `definition_version = 'v1'`；守卫要求种子行末尾为 `'v1'` |
| 落点 | `ads_operation_overview`（Hive 正式/暂存）+ `ads_operation_overview_m`（MySQL）+ `metric_value` 两行 | 见 §4 门判定（为何不放漏斗/商品侧） |

**落点为何是概览表（三个候选被逐一排除）**：
1. **漏斗侧**（`ads_behavior_funnel`）：stage 行语义是**各步独立去重用户数**（设计 L441），
   把「次数」塞进去要新增第五个阶段行 —— L441 把阶段固定为 view/intent/order/pay，**禁止**；
   且同一行数值含义会从「人数」变成「次数」，属改既有字段业务语义（门②）。
2. **商品侧**（`ads_hot_product`）：已有按商品的 `fav`/`cart` 计数，但那是**热度公式的输入分量**、
   逐商品一行；「全站当日收藏/加购次数」是另一件事，混用会让「热度权重里的 2×ln(1+收藏数)」与
   「收藏次数指标」变成同一列的两个解释。
3. **概览表**（本轮选择）：与 `pv` 同表同粒度同型（事件数），取数/对账路径最短，
   且**末尾追加**两列即可兼容历史行（历史行读 null = 未计算）。

---

## 3. 落地清单（本轮实际改动，19 个文件）

| # | 层 | 文件 | 动作 |
|---|---|---|---|
| 1 | Spark 计算 | `spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala` | `operationOverview` 子查询 `b` 加两处 `COUNT(CASE WHEN behavior_type = 'favorite'/'cart_add' THEN 1 END)`，投影末尾追加 `b.fav_cnt, b.cart_add_cnt`；方法 Scaladoc 增「S3-08 收藏/加购次数」段（含「次数≠去重人数」告警） |
| 2 | 列真源 | `spark-jobs/src/main/scala/com/graduation/analytics/metric/MetricAdsSpec.scala` | 概览表列末尾追加 `fav_cnt`、`cart_add_cnt`（仍 8 张 ADS） |
| 3 | 运行时 DDL | `spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala` | **4 处**同步：正式建表 DDL、暂存建表 DDL、`adsOperationOverviewDdl`（对账重建用）、`R7_ADDED_COLUMNS`（正式+暂存加性补列），列序与 `MetricAdsSpec` 末尾一致 |
| 4 | 列白名单 | `analytics-server/metric-analysis/.../metric/MetricAdsCatalog.java` | 概览白名单末尾加两列（写入校验只允许白名单内列名） |
| 5 | 镜像一致性回归 | `spark-jobs/src/test/scala/.../MetricAdsSpecTest.scala` | 硬编码的 Java 侧列清单同步（第二个列序所有者） |
| 6 | 发布映射 | `analytics-server/metric-analysis/.../publish/MetricPublisher.java` | `OVERVIEW_TO_METRIC` 加 `fav_cnt→fav_cnt`、`cart_add_cnt→cart_add_cnt`（概览列 → 指标码同名搬运；`period` 走既有 `day:<ISO>` 分支） |
| 7 | 语义层（AI 提示词） | `analytics-server/ai-decision/.../ai/SemanticCatalog.java` | 概览登记两列，且注解写明**次数口径、不是人数** |
| 8 | AI SQL 白名单 | `analytics-server/ai-decision/src/test/java/.../AiSqlSecurityTest.java` | 允许列集合同步加两列（否则 AI 查这两列会被安全校验拒） |
| 9 | MySQL 加性迁移 | `db/metric/V10__ads_operation_overview_fav_cart_cnt.sql`（**新增**） | `ALTER TABLE ads_operation_overview_m ADD COLUMN fav_cnt/cart_add_cnt BIGINT NULL` + 长注释（口径、为何概览表、为何加性、空值语义） |
| 10 | meta 字典种子 | `db/meta/V24__metric_definition_fav_cart_cnt.sql`（**新增**） | 两行 `INSERT … SELECT … WHERE NOT EXISTS`（幂等、不覆盖既有行），与 V13 同型 |
| 11 | 迁移执行器注释 | `analytics-server/platform-app/.../config/MetricFlywayInitializer.java` | 版本链注释补 `→ V10 运营大盘收藏/加购次数` |
| 12 | 静态 DDL 副本 | `warehouse/ddl/04-ads.sql` | 运营大盘块补两列（与运行时 DDL 逐列对齐；该文件是手工同步的静态副本） |
| 13 | **反熵守卫（新）** | `analytics-server/warehouse-pipeline/src/test/java/.../AdsFavCartCountDriftTest.java`（**新增**） | 5 条：字典行齐备且源表 = `dwd_user_behavior_detail` / meta 种子**恰一行**且版本 `v1` / 发布映射同名搬运恰一处 / ADS 三处列名与列序一致且加性迁移唯一 / Spark 公式是**事件条数**（且不得写成 `COUNT(DISTINCT …)`） |
| 14 | 发布侧单测 | `analytics-server/metric-analysis/src/test/java/.../MetricPublisherMappingTest.java` | 新增 `fav_cnt`/`cart_add_cnt` 映射与 `day:` 粒度 1 条；`dailyMetricsKeepDayPeriod` 的点名清单纳入两码；夹具行补两值 |
| 15 | Spark 行为 spec（新） | `spark-jobs/src/test/scala/.../AdsFavCartCountSpec.scala`（**新增**） | 6 条：列序末尾 + formal/staging DDL 列序 == `MetricAdsSpec` / 取值 = 3 与 5 / **次数≠人数判别**（同夹具 DWD 重算：收藏人数 2、加购人数 3）/ ADS == DWD == DWS 三方对账 / 无事件日两列 = 0 而非 NULL / `R7_ADDED_COLUMNS` 末尾两列 == spec 末尾两列 |
| 16 | 未测 IT 夹具 | `MetricPublisherMySqlIT.java`、`MetricAdsMySqlIT.java` | 概览行补两列；`fullDictionary()` 补两码（否则 `MP_METRIC_DICT_VERSION` 必红）；期望值 11 → 13 行（**仍未真跑**，见 §6） |
| 17 | 迁移清单守卫 | `analytics-server/platform-app/src/test/java/.../source/SourceRegistryMigrationMySqlIT.java` | `EXPECTED_META_SCRIPTS` 回填 V22/V23/V24（此前停在 V21，与 `db/meta` 目录已不符；本类 `@EnabledIfSystemProperty("p1.it")`，默认档不跑） |
| 18 | 基线 | `scripts/run-tests.ps1` | `analytics-server 900→906`（守卫 5 条 + 映射 1 条）、`spark 220→226`（新 spec 6 条），并写 S3-08 变更说明 |

**改动的属主收敛程度**：概览表「列清单」原本就有 **5 个所有者**（`MetricAdsSpec`、`MetricAdsCatalog`、
`LocalSchemaInitJob`×4 处、`db/metric` 迁移、语义层/AI 白名单）。本轮**不新增所有者**，只把两列同步进已有 5 处，
并新增一个**只读对账守卫**把这 5 处 + 字典 + 种子串起来（漂移即红）。收拢属主（合并为单一生成源）
属反熵治理动作，需显式裁决，**不在本轮**。

---

## 4. 11 条破坏性决策门逐条判定

| 门 | 是否触发 | 判定依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | **否** | 只 `ADD COLUMN`；无任何 DROP |
| ② 改已有字段类型/既有业务语义 | **否** | 两枚码是**新列新码**；既有 `cart_rate`（加购**率**，去重用户口径）**一格未动**；字典 L22/L23 本就是两个不同指标码 |
| ③ 改已发布 Flyway migration | **否** | 新增 `metric/V10`、`meta/V24`；`db/metric/V1–V9`、`db/meta/V1–V23` 字节未动（改动只在新文件） |
| ④ 写/迁移正式 3306 数据 | **否（本轮不执行迁移）** | 只新增迁移**文本**，未在真库应用；与 V13/V23 同类先例（新增加性迁移落地时不连正式库）。真库应用属运维窗口，见 §6 |
| ⑤ 切 ACTIVE | **否** | 未触碰任何快照状态；未跑发布 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | **否** | `contract-specs/**` 零改动 |
| ⑦ 改 V3.0 总体架构 | **否** | 仍是 8 张 ADS；无新表/新库/新组件；只是概览表末尾两列 |
| ⑧ 改正式项目范围 | **否** | 落地的是设计 L425 与字典早已承诺的两枚码，不是新增范围 |
| ⑨ 删除已发布功能 | **否** | 纯追加；历史行读 null（未计算），旧消费方零影响 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **否** | 未引入任何依赖 |
| ⑪ 方案造成重大长期架构分叉 | **否** | 与 `pv` 同表同型；不新增第二套「次数」承载方式 |

---

## 5. 与既有登记项的关系

| 既有登记 | 本轮处置 |
|---|---|
| S3-04 登记 **R-2**「`cart_add_cnt` 未落地」 | **关闭**（本轮的 `cart_add_cnt`；落点由「商品/漏斗」改判为概览表，理由见 §2） |
| `docs/PROJECT_STATUS.md` 开放项「`cart_add_cnt` 仍未落地」 | **关闭**（同上） |
| `docs/contracts/metric-lineage.md:53`「`cart_add_cnt` …**仍**未落地」 | **关闭**：该行改写为已落地 |
| 字典两向漂移（配置表独有 `stock_days`/`stock_shortage_rate`；md 独有 `fav_cnt`/`cart_add_cnt`） | **只关闭一向**：V24 补齐配置侧两枚次数码。反向（`stock_days`/`stock_shortage_rate` 未进 md、也无承载）**保持开放**，本轮不动 —— 它们是「库存不在事件流内」的既定边界（`metric-lineage.md:46-47`） |
| `SourceRegistryMigrationMySqlIT.EXPECTED_META_SCRIPTS` 停在 V21（V22/V23 已落地未回填） | **顺手修正**：回填 V22/V23/V24。该断言「清单 == 真库已应用集合」，不回填则任何一次 `-Dp1.it=true` 运行必红；本类是 IT，默认档不跑，故此前无人发现 |
| S3-05-R-2（`ads_data_quality` 实时结果）等其它开放项 | 不受影响，继续开放 |

---

## 6. 未测 / BLOCKED 边界（不得当成已验证）

- **真 MySQL 应用 V10/V24**：本轮**未**在 3306/3307 上执行任何迁移；`ads_operation_overview_m` 两列
  与 `metric_definition` 两行的**真库存在性未测**（与 V4–V9 同状态）。证据只到「迁移文本 ↔ Java 白名单/种子解析」一致。
- **`MetricPublisherMySqlIT` / `MetricAdsMySqlIT`**：夹具已同步两列与两码，但**本轮未运行**（需隔离库）。
- **真 `spark-submit` / Hive metastore**：`LocalSchemaInitJob` 的 `ALTER TABLE … ADD COLUMNS` 在**生产 Hive**
  上的行为未测（本地/内存目录每次新建，只能证到 DDL 文本与 spec 一致）。
- **isolated 档**：3307 无监听，本档未运行（基线 `isolated` 未变）。
- **跨业务日/多日重跑**：两列的「无事件日 = 0」只在单日夹具上证过；跨日累计/回填未测。
- **页面/AI 消费方**：`AnalysisService`/`AnalysisViewModel`/前端仍按旧概览列直通，两码**尚无消费方**
  （阶段4 开发项，非阻塞）。

---

## 7. 证据清单（`.verify/v3-stage3/s3-08-fav-cart-count/`）

- `red/spark-spec-red.log`（**行为红**：`succeeded 0, failed 6`，逐条原因可读）
- `red/java-guard-red.log`（新守卫 **5 条中 4 条红**：种子缺失 / 发布映射缺失 / ADS 列缺失 / Spark 公式缺失。
  **第 5 条「字典登记两枚次数码」改前即绿** —— 它守护的是「字典早已登记」这一前提，红不红取决于字典而非本轮改动）
- `red/java-mapping-red.log`（`MetricPublisherMappingTest`：新增用例 FAILURE + `dailyMetricsKeepDayPeriod` ERROR `NoSuchElement`）
- `green/spark-targeted-green.log`（点名 2 套件：`succeeded 11, failed 0`）
- `green/java-targeted-green.log`（点名 5 套件：`AdsFavCartCountDriftTest` 5 + `MetricAdsCatalogDdlConsistencyTest` 3 +
  `MetricPublisherMappingTest` 7 + `AiSqlDriftTest` 7 + `AiSqlSecurityTest` 33，全绿）
- `gate/spark-console.log`（**spark 档 `[PASS exit=0]`**：`Total number of tests run = 226`、`套件 27`、
  `succeeded 226, failed 0`、`新写=True`、`JDK8=True`、`tests=226 MATCH`）
- `gate/default-console.log`（**default 档计数全 MATCH、`[FAIL exit=7]`**：analytics-server
  `906 (F=1 E=0 S=1)`（`90+350+163+65+92+146`）、mall `13`、generator `106`、三棵树 **1025**（基线 1025）；
  **唯一红**＝已登记环境性 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，
  与 S3-07 同一枚，**未修、未复制 manifest、未用开关掩盖**）
- `gate/spark-logs/`、`gate/default-logs/`（各档原始日志）
- 交叉引用：F-41（`docs/status-history/开发过程事实与决策记录.md`）为同一批证据的文字留痕。

---

## 8. 判定与停工条件

**判定：A 类（纯加性 + 补齐实现遗漏）⇒ 登记后自主实施，不停工。**
唯一需要**另择窗口**的是「把 V10/V24 应用到正式/隔离 MySQL」——那属门④范畴（真库 DDL），
本轮只交付迁移文本与守卫，**不申请、也不执行**该动作。
