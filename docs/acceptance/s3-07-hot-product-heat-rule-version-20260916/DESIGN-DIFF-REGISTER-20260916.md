# S3-07 设计差异登记（2026-09-16）

> 提交：`feat(ads): S3-07 ads_hot_product 接齐热度权重定义版本 rule_version + 排行决胜键补 buy DESC`，
> 分支 `feature/v3-development`，**未 merge main**。
> 本文件性质：**登记（register）**，不是「请求裁定才能动工」的阻塞项。按指导书 §12 L271，
> 代码 Agent **不修改** `docs/guidance/**` 与 `docs/design/**` 两份正式文档；本文件只如实登记
> 「设计原文要求什么／此前实现是什么／本轮改了什么／凭什么判定它不需要停工／哪些必须停工」。

---

## 0. 一句话结论

设计 §11.2 **L434** 逐字写 `product_heat` 是「**版本化业务权重**」；设计 §9.3 **L320** 要求
「ADS 每行带 snapshot／**定义版本**／业务日期，发布可追溯」；设计 §11.5 **L455** 要求
「商品排行按热度/销量/金额并有**稳定次序键**」；指导书 §7 阶段3（L148）要求「对每个指标固定
粒度、分子分母、时间窗口、金额/退款口径、空值规则**和版本**」。
**此前状态**：`ads_hot_product` 只有 8 列，**没有任何版本列** —— 权重调整后无法回答「这一行按哪一版
权重算的」（V2 审计 `docs/audit/v2-completeness-audit.md:177`「§13.3 权重必须保存在规则版本中｜
**未做**｜`AdsSql.scala:100,102` 字面常量；`ads_hot_product` 无 `rule_version` 列｜权重调整无版本可溯」）；
且排行次序键**只实现了两级**（`heat DESC, product_id ASC`），缺设计要求的销量级
（同一审计 `:178`「§13.3 排行 `row_number(order by heat desc, buy desc, product_id asc)`｜**部分**｜
仅 `ORDER BY heat DESC`｜缺决胜键」）。热度公式本身还被**抄了两遍**（外层算 `heat_score`、
窗口函数里再写一遍同样的四个 `LOG1P`）。
本轮把三件事一次做齐：① ADS 行落 `rule_version`（= 指标字典 `product_heat.definition_version`）；
② 次序键补齐为「热度降序 → **销量降序** → 商品号升序」；③ 公式收敛为**一处文字属主**。
**判定：A 类（修正实现遗漏 + 稳定排序键 + 纯加性）**，不触发 11 条破坏性决策门中的任何一条
⇒ 按「默认自主连续开发」直接实施、测试、提交。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §11.2 **L434** | 「\| product_heat \| 1·ln(1+PV)+2·ln(1+收藏)+3·ln(1+加购)+5·ln(1+支付件数) \| **版本化业务权重**，不称学习模型 \|」 | 权重取值**一格未动**；补「版本」这一枚可追溯键（`rule_version = v1`） |
| 设计 §9.3 **L320** | 「ADS…每行带 snapshot／**定义版本**／业务日期，发布可追溯」 | `rule_version` 随行落库（`snapshot_id`/`dt` 已有） |
| 设计 §11.5 **L455** | 「商品排行按热度/销量/金额并有**稳定次序键**」 | 次序键补中间一级 `buy DESC`，使热度并列时**销量高者**排前（见 §3 门②） |
| 指导书 §7 阶段3 ①（L148） | 「对每个指标固定粒度、分子分母、时间窗口、金额/退款口径、**空值规则和版本**」 | 版本键落 ADS；空值语义在 §2 冻结 |
| 指导书 §7 阶段3 ②（L150） | 「逐层对账」 | 新增两侧守卫（Spark 真跑断言 + Java 字典↔SQL 逐字对账） |
| 指导书 §12 **L271** | 「Code Agent 只提交设计差异请求，不自行修改两正式文档」 | 本轮**未改** guidance/design 任何字节 |
| 指标字典 `docs/contracts/metric-dictionary.md:31` | 「product_heat…权重来自业务设定，**存配置表**」 | 版本键取值即配置表（`metric_definition`）里 `product_heat.definition_version` |
| meta 种子 `db/meta/V2__platform_pipeline_quality.sql:75` | `('product_heat', '商品热度', '1×ln(1+PV)+2×ln(1+收藏)+3×ln(1+加购)+5×ln(1+支付件数)', 'day', 'event_time', '', 'v1')` | 该行 `definition_version = 'v1'` 是版本语义所有者；Spark 侧常量与它逐字一致 |
| V2 指导书（history）**L639** | 排行三级形态 `row_number(order by heat desc, buy desc, product_id asc)` | 本轮实现形态与之逐字一致 |
| V2 审计 **L177** | 「§13.3 权重必须保存在规则版本中｜**未做**｜…`ads_hot_product` 无 `rule_version` 列」 | 本轮**关闭该审计项**（列已落 + 两侧守卫） |
| V2 审计 **L178** | 「§13.3 排行 `row_number(order by heat desc, buy desc, product_id asc)`｜**部分**｜仅 `ORDER BY heat DESC`｜缺决胜键」 | 本轮**关闭该审计项的一半**（三级形态已实现；S2-06 已补 `product_id`，本轮补 `buy`） |
| 设计 §12.5 **L524-535**（发布顺序） | 表形/行数/定义版本/内容验证 | `MP_METRIC_DICT_VERSION` 校验的是 `metric_value.definition_version`（`MetricPublishValidator.java:173-187`）；**发布链不读 ADS 的 `rule_version`** ⇒ 本轮**无需**改任何发布校验（已实测：发布侧无引用） |

**失败样例（改前真实状态）**：业务把 `buy` 权重从 5 调成 8 ⇒ 只改 `db/meta/V2...L75` 的公式文本，
忘了改 `AdsSql`：`metric_definition` 说 v2、ADS 行照旧按 v1 算，**没有任何门禁会发现**——
`MP_METRIC_DICT_VERSION` 只看 `metric_value` 与字典，ADS 侧连版本列都没有。反之只改 `AdsSql`
也会出现同样分裂。本轮把「字典版本/权重 ↔ Spark 实现」变成**逐字对账的守卫**（漂移即红），
并让 ADS 行**自带版本**，使这种分裂在数据上可发现。

---

## 2. 口径声明（本轮冻结）

| 要素 | 本轮冻结取值 | 依据 / 证据 |
|---|---|---|
| 权重取值 | `1.0*LOG1P(pv) + 2.0*LOG1P(fav) + 3.0*LOG1P(cart) + 5.0*LOG1P(buy)` | 与 §11.2 L434 公式文本、meta L75 种子**逐条对应**（`1/2/3/5`）；本轮**不改任何数字** |
| 公式文字属主 | **`AdsSql.hotProduct` 内只有一处**（子查询算 `heat_score`，窗口按该列排序） | 此前同一 SQL 里出现两遍（外层 + 窗口）；两处字面量将来会各自漂移，且窗口那处一旦被改就会改变名次而外层值不变 |
| 版本键名 | `rule_version` | 与 `ads_user_profile.rule_version`（`'rfm-v2'`，STRING）、meta `data_quality_result.rule_version` 同名；§9.3 L320 用的词就是「定义版本」 |
| 版本取值 | `'v1'`（`AdsSql.HeatRuleVersion`，**单一常量字面量**） | = meta `metric_definition` 中 `product_heat` 行的 `definition_version`（L75），与 `metric_value.definition_version` **同一枚键空间** |
| 版本列类型 | Hive `STRING`／MySQL `VARCHAR(16) NULL` | 与 `ads_user_profile_m.rule_version` 同型；`v1` 等短版本号够用 |
| 列位置 | **追加在末尾**（`…, rank_no, rule_version`） | 五处属主列序一致：`MetricAdsSpec`（Spark 列真源）、`LocalSchemaInitJob`（formal + `__staging`）、`MetricAdsCatalog`（Java 白名单）、`warehouse/ddl/04-ads.sql`（冻结参考副本）、迁移 `V9` |
| 排序键 | `ROW_NUMBER() OVER (ORDER BY heat_score DESC, buy DESC, product_id ASC)` | V2 指导书 L639 与 V2 审计 L178 的目标形态；`rank_no` 语义不变（**1 = 最热**） |
| 历史行版本 | **NULL ＝「未记录版本」** | S3-07 之前发布的行没有版本可填，**不写 `'v1'` 冒充**（补写即伪造可溯性）；与 S3-05 的 `ads_data_quality.rule_version` 空值语义同一条纪律 |
| 历史行 `rank_no` | **不回填、不重算** | 摘要/历史快照一经发布不可变（§12.5）；新次序只作用于**新发布** |

---

## 3. 为什么是 A 类：11 条破坏性决策门逐条核对

| # | 门 | 判定 | 理由（可核对） |
|---|---|---|---|
| ① | DROP TABLE/COLUMN | **未触发** | 只 `ADD COLUMN`；`ads_hot_product` 既有 8 列名字/类型/顺序一格未动 |
| ② | 改既有字段类型或**既有业务语义** | **未触发（本轮最需要说明的一条）** | `rank_no` 语义（1 = 名次最高、连续 1..N、`WHERE rank_no <= topN` 入选判据）**未变**；变的是**热度并列组内**的裁决：由「仅商品号升序」细化为「先销量降序、再商品号升序」。这正是 §11.5 L455 与审计 L178 要求的形态，属**补齐未实现的规格**，不是改变规格。三条佐证：(a) S2-06 先例 —— 当年补 `product_id` 一级就是同一类「稳定次序键」修正，已按 A 类落地并发布；(b) §11.5 L455 明文要求销量参与排序；(c) 非并列商品的名次**逐位不变**（本轮 Spark 夹具实测：`103` 热 41.6 稳居第 1、`104` 热 0 稳居第 5）。**如实登记的可见影响**：热度并列时 TopN 入选集合会变 —— 本轮 RED 实测 `TopN=2` 由 `Set(103,101)` 变为 `Set(103,102)`（旧实现按商品号取 101，新实现按销量取 102）。这是**修正后的正确结果**，且只发生在并列组；不涉及历史数据回填 |
| ③ | 改已发布 Flyway migration | **未触发** | `metric V1–V8`、`meta V1–V22`（含 V19 种子、V23）**字节未动**；仅**新增** `db/metric/V9__ads_hot_product_heat_rule_version.sql` |
| ④ | 写/迁移正式 3306 数据 | **未触发** | 本轮 **0 次连库**；未触碰 3306/3307/ACTIVE |
| ⑤ | 切 ACTIVE | **未触发** | 未执行任何发布动作 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | **未触发** | 未改 `contract-specs/**` 任何字节（已全目录检索 `rule_version`／`heat_score`／`ads_hot_product`：**0 命中**）；`docs/contracts/metric-lineage.md` 是**开发侧血缘文档**、非冻结契约，且按 S3-01…S3-06 先例**就地加注**（L22/L29/L30 均有同类「Sxx 补」注记） |
| ⑦ | 改 V3.0 总体架构 | **未触发** | 仍 **8** 张 ADS 表、仍同一发布链、未引入新组件 |
| ⑧ | 改正式项目范围 | **未触发** | 只补一个版本列 + 一个排序级 + 一处公式收敛 |
| ⑨ | 删除已发布功能 | **未触发** | `heat_score`/`pv`/`fav`/`cart`/`buy`/`rank_no`/`product_name`/`product_id` 全部保留；`WHERE rank_no <= topN` 改写为在外层过滤（**结果集语义相同**，见 §5 实测） |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **未触发** | 无新依赖、无新组件；`SemanticCatalog` 只加**一行字段描述**（被守卫强制，见 §5） |
| ⑪ | 两种方案造成重大长期架构分叉 | **未触发** | 未做属主合并/收敛动作（字典仍是权重语义所有者、Spark 仍是计算实现），只把一致性变成**可测** |

**结论**：A 类 ⇒ 登记 + 自主设计 + 实现 + 测试 + commit + push，**不申请停工**。

---

## 4. 改动清单（代码提交，14 个文件）

**Spark 侧（4）**
1. `spark-jobs/.../sql/AdsSql.scala` —— 新增单一常量 `HeatRuleVersion: String = "v1"`（KDoc 写明语义所有者在 meta `metric_definition`，一致性由 Java 守卫对账）；`hotProduct` 三段式重写：子查询算 `heat_score`（**唯一公式**）→ 窗口 `ORDER BY heat_score DESC, buy DESC, product_id ASC` + `'$HeatRuleVersion' AS rule_version` → 外层投影 `…, t.rank_no, t.rule_version` 并 `WHERE rank_no <= topN`。
2. `spark-jobs/.../metric/MetricAdsSpec.scala` —— `ads_hot_product` 列 8→**9**（末尾 `rule_version`）。
3. `spark-jobs/.../job/LocalSchemaInitJob.scala` —— formal + `ads_hot_product__staging` 两处建表末尾追加 `rule_version STRING`。
4. `spark-jobs/src/test/scala/.../AdsHotProductHeatRuleVersionSpec.scala`（**新增**，8 条）。

**Java 侧（5）**
5. `analytics-server/metric-analysis/.../metric/MetricAdsCatalog.java` —— `ads_hot_product_m` 白名单 8→**9**。
6. `analytics-server/ai-decision/.../ai/SemanticCatalog.java` —— 登记 `rule_version` 字段描述（**守卫强制**，见 §5；无任何 AI 逻辑改动）。
7. `analytics-server/platform-app/src/main/resources/db/metric/V9__ads_hot_product_heat_rule_version.sql`（**新增**，加性 `ALTER TABLE … ADD COLUMN rule_version VARCHAR(16) NULL`，头部五段说明：依据／为何加性而非改 V2/V3（门③）／为何追加在末尾／历史行 NULL 的含义／为何不做读侧复算）。
8. `analytics-server/platform-app/.../config/MetricFlywayInitializer.java` —— KDoc 版本链 `V8 → V9`。
9. `analytics-server/warehouse-pipeline/src/test/java/.../pipeline/AdsHotProductHeatWeightDriftTest.java`（**新增**，5 条反熵守卫）。

**测试夹具与文档（5）**
10. `spark-jobs/src/test/scala/.../MetricAdsSpecTest.scala` —— 硬编码镜像清单加 `rule_version`（该测试**刻意**要求 Java 侧列清单与 Spark 真源逐字相等）。
11. `analytics-server/metric-analysis/src/test/java/.../publish/MetricPublisherMySqlIT.java` —— 热门商品夹具补 `rule_version = "v1"`（**未运行**，见 §7）。
12. `warehouse/ddl/04-ads.sql` —— 冻结参考副本同步（注释补次序键与版本语义）。
13. `docs/contracts/metric-lineage.md` —— #11 行 ADS/MySQL 字段补 `rule_version`（就地加注，S3-01…S3-06 先例）。
14. `scripts/run-tests.ps1` —— 基线 `analytics-server 895→900`、`spark 212→220` + S3-07 来历注释。

**未动**：`docs/guidance/**`、`docs/design/**`、`contract-specs/**`、`meta V1–V22`、`metric V1–V8`、
`R7_ADDED_COLUMNS`（仍只覆盖概览表，见 §7-R-2）、3306/3307 数据、`AnalysisService`/前端/AI 罐装 SQL。

---

## 5. RED 归因、GREEN 与守卫

### 5.1 RED（先红，且红在正确的原因上）

- **Spark 新套件**（`red/spark-spec-red.log`）：`Total number of tests run: 8`，`succeeded 3, failed 5`。
  失败五条的原因逐条可读：
  ① 列清单末位 `"r[ank_no]" was not equal to "r[ule_version]"`；
  ② 每行版本断言 `ADS 热门商品 … 缺列 rule_version（现有列 = …, rank_no, snapshot_id, dt）`；
  ③ 名次 `Map(101→2, 102→3, 105→4, 103→1, 104→5)` ≠ 期望 `Map(101→3, 102→2, …)`（101/102/105 热度**逐位相等**，旧实现按商品号把 101 排前、新实现按销量把 102 排前）；
  ④ `TopN=2` 入选 `Set(103,101)` ≠ 期望 `Set(103,102)`；
  ⑤ 公式属主 `log1p 出现 8 次` ≠ 4 次（**证明公式当时确实抄了两遍**）。
  三条通过的是「夹具前提（101/102/105 热度逐位相等）」「`heat_score` 与独立复算逐位相等」「formal/staging DDL 列序 == 真源」——后者当时尚未改列，通过属**预期**。
- **RED 过程中的一次自我纠错（如实留痕）**：第一版 spec（`red/spark-compile-red.log`）引用了尚不存在的
  `AdsSql.HeatRuleVersion` ⇒ 是**编译失败**而非行为红。纠正做法：spec 只钉**渲染后的字面量**
  `'v1' as rule_version`（不引用常量），把「常量 ↔ 字典版本」的对账交给**能编译成功却行为失败**的 Java 文本守卫。
- **Java 反熵守卫**（`red/java-guard-red.log`）：`Tests run: 5, Failures: 5`，原因逐条：
  ① `val HeatRuleVersion: String = "…"` **一处都没有**（`实际 []`）；② Spark 权重与字典不一致；
  ③ `LOG1P` 8 次（公式抄两遍）；④ 版本列引用 **0** 处；⑤ 三级次序键缺失。

### 5.2 GREEN

- **Spark 点名 3 套件**（`green/spark-targeted.log`）：`Tests: succeeded 17, failed 0`（新套件 8 + `AdsStableOrderSpec` + `MetricAdsSpecTest`）。
- **Java 点名 3 套件**（`green/java-targeted.log`）：`AdsHotProductHeatWeightDriftTest` **5/5**、
  `MetricAdsCatalogDdlConsistencyTest` **3/3**（解析 `db/metric/V*.sql`，含新 `V9` 的 `ADD COLUMN`）、
  `AiSqlDriftTest` **7/7**（含「语义层字段与 DDL 完全一致」双向断言）。
- **`SemanticCatalog` 为何必须同步（守卫强制，非范围扩张）**：`AiSqlDriftTest.语义层字段与DDL完全一致()`
  要求 `SemanticCatalog.TABLES` 覆盖真实 DDL 的**每一列**（`snapshot_id` 除外）⇒ 迁移加了
  `rule_version` 而不登记该守卫即红。改动只是**一行中文列描述**（Text2SQL 提示词因此多列出一个真实列），
  AI 判定逻辑、SQL 生成、安全白名单**均未改**。

### 5.3 反熵守卫（本轮核心增量，只读、不改任何属主的值）

`AdsHotProductHeatWeightDriftTest`（Java，5 条）**静态解析两处源码**逐条对账：
① 版本一致（`AdsSql.HeatRuleVersion` == 字典 `product_heat` 种子行末位的 `definition_version`）；
② 权重一致（Spark 的 `N.0*LOG1P(` 取值序列 == 字典的 `N×ln(` 取值序列，且恰 4 项）；
③ 公式**单一文字属主**（`def hotProduct` 内 `LOG1P` 恰好 4 次）；
④ 版本列由**单一常量**渲染（`'$HeatRuleVersion' AS rule_version` 恰好 1 处，且常量声明恰好 1 处）；
⑤ 三级稳定次序键存在且落在 `ROW_NUMBER() OVER (` 窗口内。
**属主收敛（把字典或 Spark 任一处删掉、合并成单一来源）属破坏性动作，本轮不做**，按 anti-entropy 纪律留待总控确认。

### 5.4 全量门禁（fresh，两档真跑）

- **spark 档 `[PASS exit=0]`**（`gate/spark-console.log`）：`Total number of tests run = 220`、
  `Suites: completed 26, aborted 0`、`succeeded 220, failed 0`、`新写=True`、`JDK8=True`、`tests=220 MATCH`。
- **default 档计数全 MATCH、`[FAIL exit=7]`**（`gate/default-console.log`）：analytics-server
  `900 (F=1 E=0 S=1)`（明细 `90+350+158+64+92+146`）、mall `13`、generator `106`、三棵树 `1019`（基线 1019）、无 DRIFT。
  唯一红仍是已登记环境性 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
  （`expected: 43 but was: 0`，本工作树 `landing/manifests` 不存在，43 份历史清单仅在主工作树）——
  本轮**未修、未复制 manifest、未用开关掩盖**。
- **基线 `895/1014/212 ⇒ 900/1019/220`**：spark `+8` = 新套件 8 条；analytics-server `+5` = 新守卫 5 条（落在 `warehouse-pipeline` 153→**158**）。
- **未做反向取证（不得越界表述）**：本轮**未**用「故意漏改一处」证明各守卫确实会红（RED 阶段已实际红过 ①②③④⑤ 五项中的全部，但那是**改前**的自然状态，不等于对**将来**漏改的完备保证）。

---

## 6. 兼容性与不可变性核对

- `ads_hot_product` 既有 8 列的名字/类型/顺序**一格未动**；新列在末尾（Hive DDL 与 MySQL 迁移一致）。
- `metric V1–V8`、`meta V1–V2`（含 V19 种子、V23）**字节未动**；`V9` 为**新增文件**。
- `AdsSql.TABLES`／`MetricAdsSpec.TABLES`／`MetricAdsCatalog.ALL` 仍 **8** 张表。
- `WHERE rank_no <= topN` 的**过滤位置**从子查询内层移到外层：语义相同（`rank_no` 由窗口函数产生，
  过滤条件不变），实测两种物理落下结果一致（`AdsStableOrderSpec` + 新套件双物理序断言全绿）。
- 读侧（`AnalysisService`/前端）**未改**：`rule_version` 是**新增列**，既有读取语句按列名取数，不受影响；
  `order by rank_no` 的消费方式不变（`rank_no` 仍连续 1..N）。
- 本轮 **0 次连库**；未触碰 3306/3307/ACTIVE、未删任何已发布能力、未新增第 9 张 ADS 表、
  未增删任何质量规则或改任何阈值。

---

## 7. 未做与待批注（不阻塞；编号 `S3-07-R-n`）

- **S3-07-R-1｜读侧历史版本复算**：设计 §12.4 L520 的读侧复算仍归 F-93 及相关 backlog（「不阻塞无关业务开发」），
  本轮只保证**新行**带版本，**不**提供「按指定版本重算榜单」的读侧能力。
- **S3-07-R-2｜在产 Hive 已建表的补列**：`CREATE TABLE IF NOT EXISTS` 对已存在表**不生效** ⇒ 需部署时显式
  `ALTER TABLE … ADD COLUMNS`；且 `R7_ADDED_COLUMNS`（`LocalSchemaInitJob` 内的运行期补列）**仍只覆盖
  `ads_operation_overview`**，未扩到热门商品表。属**部署事项**，本轮未做（这是 S3-01…S3-06 一以贯之的已登记边界）。
- **S3-07-R-3｜MySQL `V9` 真库列存在性未测**：`isolated` 档（WSL 3307）**无监听、本轮未跑**；
  `MetricPublisherMySqlIT`/`MetricAdsMySqlIT` 本轮**均未运行**（夹具已补 `rule_version`，**无实测证据**）。
- **S3-07-R-4｜历史行不回填**：S3-07 之前发布的 `ads_hot_product_m` 行 `rule_version` 保持 NULL、`rank_no`
  保持旧次序。若总控要求「历史行也标注 v1」或「按新次序重排历史快照」，属**数据迁移**（门④邻域）⇒ 请批注。
- **S3-07-R-5｜热度权重变体/学习模型**：设计 §11.2 L434 明文「**不称学习模型**」⇒ 本轮不引入任何学习型权重；
  若后续要做模型化排序，属**范围/架构变更**（门⑧/⑪邻域）。
- **S3-07-R-6｜「按销量/金额」排行**：§11.5 L455 提到「按热度/销量/金额」，本轮只落地**热度榜**的稳定次序键
  （销量只作为**决胜级**参与）；是否需要独立的「销量榜/金额榜」属读侧能力，归**阶段4**，请批注。
- **S3-07-R-7｜`rule_version` 暂无消费方**：`AnalysisService`/前端/AI 证据包均未消费该列 ⇒ 归**阶段4/5**。

---

## 8. 边界（不得越界表述）

1. 证据仍是 Scala `local[1]` ＋ `catalogImplementation=in-memory`：**未跑真实 `spark-submit`、未连 Hive metastore**。
2. **`V9` 未在任何真库执行** —— `MetricAdsCatalogDdlConsistencyTest` 只证「迁移文本 ↔ Java 白名单」一致，
   **不证明**该列已在任何库中存在（`MetricPublisherMySqlIT` 夹具已补列但**未运行**）。
3. 排序稳定性的证据边界：两种物理落下（升序/降序写入）在本轮夹具下名次**完全一致**；这不等于
   「任意集群/任意分区布局下都稳定」——`ROW_NUMBER` 的三级键是**确定性来源**，但真实 Hive 上的
   执行计划/并行度影响**未测**。
4. 「关掉公式第二处」的收益是可维护性（单一属主），**不等于**旧实现算错：旧实现两处字面量当时**取值相同**
   （RED 阶段实测 `heat_score` 与独立复算逐位相等即证），本轮改的是**将来漂移的风险面**。
5. 本轮 **0 次连库**；未触碰 3306/3307/ACTIVE、未改已发布迁移、未改 `contract-specs/**`、
   未改 `docs/guidance/**` 与 `docs/design/**`、未删任何已发布能力、未新增第 9 张 ADS 表。

---

## 9. 证据清单（`.verify/v3-stage3/s3-07-hot-product-heat-rule-version/`）

- `red/spark-compile-red.log`（第一版 spec 引用不存在常量的**编译失败**，自我纠错留痕）
- `red/spark-spec-red.log`（**行为红**：`succeeded 3, failed 5`，五条原因逐条可读）
- `red/java-guard-red.log`（守卫 **5/5 红**：常量缺失 / 权重不一致 / 公式两遍 / 版本列 0 处 / 次序键缺失）
- `green/spark-targeted.log`（点名 3 套件 `succeeded 17, failed 0`）
- `green/java-targeted.log`（点名 3 套件：5 + 3 + 7 全绿，含 `V9` 解析与语义层双向一致性）
- `gate/spark-console.log`（**`[PASS exit=0]`**：220 / 26 套件 / `tests=220 MATCH` / `新写=True` / `JDK8=True`）
- `gate/default-console.log`（analytics `900 MATCH`、mall `13 MATCH`、generator `106 MATCH`、三棵树 `1019`、
  `[FAIL exit=7]` —— 唯一红为已登记环境性 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`）
- `gate/spark-logs/`、`gate/default-logs/`（各档原始日志）
- 交叉引用：F-40（`docs/status-history/开发过程事实与决策记录.md`）为同一批证据的文字留痕。
