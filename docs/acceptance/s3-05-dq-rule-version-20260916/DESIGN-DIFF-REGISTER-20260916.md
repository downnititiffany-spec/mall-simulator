# S3-05 设计差异登记（2026-09-16）

> 提交：`9d1ab33`（`feat(ads-quality): S3-05 ADS 质量大盘接齐规则定义版本 + 规则码/版本/阈值三方反熵守卫`），
> 分支 `feature/v3-development`，**未 merge main**。
> 本文件性质：**登记（register）**，不是「请求裁定才能动工」的阻塞项。按指导书 §12 L271，
> 代码 Agent **不修改** `docs/guidance/**` 与 `docs/design/**` 两份正式文档；本文件只如实登记
> 「设计原文要求什么／此前实现是什么／本轮改了什么／凭什么判定它不需要停工／哪些必须停工」。

---

## 0. 一句话结论

设计 §9.3 **L335** 逐字写「`ads_data_quality`/`ads_data_quality_m`：历史已发布，**规则版本与实时结果待接齐**」，
§9.3 **L320** 要求「ADS…每行带 snapshot/**定义版本**/业务日期，发布可追溯」，§12.3 **L512** 要求
「每条规则记录作用域、阈值、**版本**、阶段、实际值、passed、原始/生效严重度」。
**此前状态**：meta 侧 `data_quality_result` 在 `V20__data_quality_result_rule_version.sql` 里已有
`rule_version`/`effective_severity`/`rule_fingerprint`（L512 在 **meta** 侧已满足），但
**ADS 大盘行没有任何版本列** —— 一行 `AMOUNT_RECONCILE passed=0` 无法回答「依据哪一版规则判的」。
本轮补上 ADS 侧的 `rule_version`（**纯末尾追加可空列**），并为「同一批阈值有**三个文字属主**」这一
既存反熵风险新增**只读**三方对账守卫。**判定：A 类（实现遗漏 + 纯加性）**，不触发 11 条破坏性决策门
中的任何一条 ⇒ 按「默认自主连续开发」直接实施、测试、提交。

同时**明确不做且已登记为门⑦邻域**：`ads_category_sale`/`ads_region_sale` **实产**（§9.3 **L336-337**）。
指导书 §7 阶段3 **L150** 确实要求「补**分类/地区**等尚无完整产出的专题」，但
`db/metric/V3__metric_ads_r7.sql:2` 已明文声明「**本期 Hive 侧只有 8 张 ADS，因此 `ads_category_sale_m` /
`ads_region_sale_m` 不建**」，且「8 张 `_m`」是**已发布契约**（`QualityRuleCatalog` 的
`ADS_STAGING_PRESENT` 判据「8 张暂存表」、`MXP_EXPORT_COMPLETE`、`MetricPublishValidator.MP_MANIFEST_TABLES`、
`MetricAdsSpecTest` 的 `should be(8)`）⇒ 加第 9/10 张表会改动已发布镜像契约，属**门⑦邻域（需总控冻结新版镜像规格）**。
设计自己的措辞「**待发布规格冻结镜像**」也指向同一结论。**本轮只登记该项，切换去做 L335（设计行序 L335 在 L336 之前）。**

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §9.3 **L335** | 「\| `ads_data_quality`/`ads_data_quality_m` \| 历史已发布，**规则版本与实时结果待接齐** \|」 | 补「规则版本」一半：ADS 行携带 `rule_version`；「实时结果」另一半见 §6-R-2（未做） |
| 设计 §9.3 **L320** | 「ADS 下节 **10 个逻辑专题**，每行带 snapshot/**定义版本**/业务日期，发布可追溯」 | 本项目 ADS 实产为 **8** 张（L339 已证），本轮让其中质量专题的行具备「定义版本」可追溯键 |
| 设计 §12.3 **L512** | 「每条规则记录**作用域、阈值、版本、阶段、实际值、passed、原始/生效严重度**」 | 作用域/阶段/严重度三者的运行时真值**已在 meta** `data_quality_result`（`layer`/`target_table`/`severity`/`effective_severity`/`rule_fingerprint`，V11/V12/V20）；本轮补 ADS 侧缺失的「版本」，**不**在 ADS 复制其余四项（避免第二属主） |
| 设计 §12.4 **L516** | 「先冻结规则集 → actual → threshold → 有效 severity → 门禁」 | 规则集/阈值**本轮一字未改**；新增守卫只**核对**目录与两处实现是否一致 |
| 设计 §12.4 **L520** | 「读侧历史版本的复算…归 F-93 及相关 backlog，**不阻塞无关业务开发**」 | 明确本项**不构成**阻塞；读侧复算**未做**（见 §6-R-3） |
| 设计 §9.3 **L336-337** | 「\| `ads_category_sale`/`_m` \| **待发布规格冻结镜像**（规划有DDL，不宣称已有实产） \|」「\| `ads_region_sale`/`_m` \| 同上 \|」 | **本轮不做**：需门⑦冻结新版镜像规格（见 §5 与 §6-R-1） |
| 指导书 §7 阶段3 ①（L148） | 「对每个指标固定粒度、分子分母、时间窗口、金额/退款口径、**空值规则和版本**」 | 质量规则的「版本」落成 ADS 列；空值规则写明（历史行 NULL ＝ 未记录版本） |
| 指导书 §7 阶段3 ②（L149） | 「Spark SQL 计算销售、用户、商品、漏斗及**质量专题**，**逐层对账**」 | 新 spec 对 4 条规则逐条做「实际值/阈值/passed」对账；新守卫对「目录 ↔ Spark ↔ Java」三方对账 |
| 指导书 §8 阶段3（L199） | 「稳定指标公式、分层对账与发布制品；失败保旧；**不靠前端/AI临时算出指标**」 | 版本随制品落库，不留给前端/服务层现算 |
| 指导书 §12 **L271** | 「Code Agent 只提交设计差异请求，不自行修改两正式文档」 | 本轮**未改** guidance/design 任何字节 |
| 设计 §9.3 **L339** | 「`analytics_metric` 中 8 张 `_m` 是已证实体」 | 表数仍 **8**，**只加列不加表** |

**失败样例（改前真实状态）**：`data_quality_result`（meta）里 `rule_version=1`、`effective_severity=WARN` 一应俱全，
而 `ads_data_quality_m`（ADS 镜像，页面/证据引用的就是它）只有 `rule_code/check_count/error_count/error_rate/passed/threshold`
⇒ 从大盘行**无法**判定「这条 `EVENT_ID_UNIQUE passed=0` 是 v1 规则判的还是别的版本判的」；
一旦有人调整阈值（例如把 `dupRateMax` 从 0.0005 放宽），**新旧口径的 passed 会混在同一张表里且不可区分** ——
这正是 §11.4 对 RFM 提出的「不仅存标签」同类问题在质量专题上的复现。

---

## 2. 口径声明（本轮冻结）

| 要素 | 本轮冻结取值 | 依据 / 证据 |
|---|---|---|
| 列名 | `rule_version`（与 meta `data_quality_result.rule_version` **同名**） | §12.3 L512「版本」；同名便于「大盘行 ↔ 门禁结果」用同一枚键对齐 |
| 类型 | `INT`（Hive `INT`；MySQL `INT NULL`） | 与 `V20__data_quality_result_rule_version.sql` 的 `rule_version INT` **一致**；目录里 `QualityRuleDefinition.version()` 就是 int |
| 语义 | 「**判这一行时**所用的规则定义版本」= `quality_rule_definition`（代码内目录 `QualityRuleCatalog`）中该规则码的 `version` | 目录是版本化**单一所有者**（`CATALOG_VERSION="qrc-1"`、`COMPAT_POLICY_VERSION="compat-v1"`）；本轮 4 条规则均为 **1** |
| 列序 | **末尾追加**：`rule_code, check_count, error_count, error_rate, passed, threshold, rule_version` | 与 `MetricAdsSpec`（Spark 列真源）、`MetricAdsCatalog`（Java 白名单）、`warehouse/ddl/04-ads.sql`、`LocalSchemaInitJob`、加性迁移 `V8` 五处一致 |
| 值来源（Spark） | 单一字面量常量 `AdsSql.QualityRuleVersion`，四条规则分支**一律引用它** | 避免「四个分支各写各的字面量」这一新属主；源码级守卫断言引用次数 == 4 且常量声明唯一 |
| 空值规则 | 列**可空**；历史快照行保持 **NULL ＝「未记录版本」**，**不写 0 冒充 v1**（0 不是任何已发布版本） | 写进迁移头与 `AdsSql` KDoc；`V8` 不提供 DEFAULT、不回填 |
| 规则集合 | 仍 **4** 条：`AMOUNT_RECONCILE`/`REQUIRED_FIELD_NULL_RATE`/`EVENT_ID_UNIQUE`/`ENUM_WHITELIST` | **未增删任何规则** |
| 阈值 | `'0.01'`/`'0.001'`/`'0.0005'`/`'0'` **一字未改** | 本轮**只读核对**，不改值 |
| 实际值算法 | `check_count`/`error_count`/`error_rate`/`passed` 的 SQL **一字未改** | 新 spec 的期望值与 S3-05 之前完全一致（RED 时 3 条算法类用例本来就是绿的） |
| 作用域/阶段/严重度 | **不进 ADS** | 运行时真值在 meta `data_quality_result`（`layer`/`target_table`/`severity`/`effective_severity`/`rule_fingerprint`）⇒ ADS 再抄一份即**第二个严重度属主**（V2 审计 §24.3 同一类） |

---

## 3. 本轮实施面（含属主与守卫）

| 所有者 | 文件 | 变更 |
|---|---|---|
| Spark ADS 计算 | `spark-jobs/.../sql/AdsSql.scala` | 新增 `val QualityRuleVersion = 1`；`dataQuality` 四条分支各加 `$QualityRuleVersion AS rule_version`；外层列清单加 `rule_version`；KDoc 写明语义、为什么必须落列、为什么不抄严重度 |
| Spark 列真源 | `.../metric/MetricAdsSpec.scala` | `ads_data_quality` 6 → **7** 列（表数仍 8） |
| Spark 派生 DDL | `.../job/LocalSchemaInitJob.scala` | **正式** `ads_data_quality` 与 `ads_data_quality__staging` 两处建表各加 `rule_version INT` |
| 手持参考副本 | `warehouse/ddl/04-ads.sql` | 同步（该文件**无**自动化整体守卫，属人工同步，既有状态） |
| Java 白名单 | `analytics-server/metric-analysis/.../MetricAdsCatalog.java` | 质量表 6 → **7** 列（顺序一致） |
| MySQL 镜像 | `platform-app/src/main/resources/db/metric/V8__ads_data_quality_rule_version.sql` | **新增加性迁移**：`ADD COLUMN rule_version INT NULL`；头部声明「为什么加性而非改 V3」「为什么不抄严重度」「历史行 NULL 的含义」 |
| 迁移挂载说明 | `.../config/MetricFlywayInitializer.java` | KDoc 版本链补「→ V8 质量大盘规则定义版本」 |
| 真库 IT 夹具 | `.../metric/publish/MetricPublisherMySqlIT.java` | 质量夹具补 `rule_version=1`（夹具与列集合对齐；**本轮该 IT 未运行**） |
| 守卫（Scala 行为） | `spark-jobs/src/test/scala/.../AdsQualityRuleVersionSpec.scala`（新增 **5** 条） | 列清单与 `rule_version` 末列、逐行版本非空且 ==1、4 条规则的实际值/阈值/passed 对账（含「坏日/好日」双夹具）、formal DDL 列序 == `spec.columns :+ dt`、staging == `spec.columns ++ {snapshot_id, dt}`、`TABLES.size == 8` |
| 守卫（Scala 文本） | `.../SqlTemplateSpec.scala`（+1 条） | 断言外层列清单含 `rule_version`，且四条分支由**同一个**常量渲染（渲染结果 4 处 `1 AS rule_version`），并断言 `AdsSql.QualityRuleVersion == 1` |
| 守卫（Java 三方对账） | `analytics-server/warehouse-pipeline/src/test/java/.../QualityRuleThresholdDriftTest.java`（新增 **5** 条） | **静态解析三处源码**：① ADS 规则码均已登记且无重复；② ADS 版本 == 目录 `version()`；③ ADS 阈值 == 目录 `thresholdJson()` 唯一数值；④ `QualityChecker` 阈值 == 目录阈值；⑤ ADS 规则集合 == 目录中 `STAGE_LANDING && enabled && thresholdJson != null` 的集合。并钉住版本常量声明唯一 + 分支引用 4 次 |
| 基线 | `scripts/run-tests.ps1` | spark `202→208`、analytics-server `882→887`（含来历说明） |

**兼容性承诺（可复核）**：`ads_data_quality` 既有 6 列的名字/类型/顺序**一格未动**，新列在**末尾**；
`metric V1`–`V7`、`meta V1`–`V2` 迁移文件**未改一个字节**（`V8` 为**新增**文件）；
`AdsSql.TABLES` / `MetricAdsSpec.TABLES` / `MetricAdsCatalog.ALL` 仍 **8** 张表；
规则集合仍 4 条、阈值 4 个值**未动**；`AdsSql.dataQuality` 的实际值 SQL **未动**。

---

## 4. 反熵守卫：为什么这轮要加它，以及它守住了什么

**既存风险（本轮实测确认，不是推测）**：同一批阈值在仓库里有**三个文字属主** ——

| 属主 | 位置 | 形态 |
|---|---|---|
| 定义目录（唯一权威） | `platform-common/.../quality/QualityRuleCatalog.java` | `thresholdJson` 结构体：`{"maxAbsDiff":0.01}` / `{"nullRateMax":0.001}` / `{"dupRateMax":0.0005,"dedupDeterministic":true}` / `{"illegalRatio":0}` |
| Spark 实现 | `spark-jobs/.../sql/AdsSql.scala` `dataQuality` | SQL 字面量 `'0.01'` / `'0.001'` / `'0.0005'` / `'0'` |
| Java 实现 | `warehouse-pipeline/.../QualityChecker.java` | Java 字面量（`NULL_RATE_MAX`/`DUP_RATE_MAX` 常量 + 调用处 `"0.01"`/`"<=0.001"`/`"<=0.0005"`/`"0"`） |

三者任一处被单独改动（例如只把目录里的 `dupRateMax` 放宽、忘了同步 Spark SQL），
**当时没有任何门禁会变红**：`SqlTemplateSpec` 只断言 SQL 里出现规则码，不核对阈值；
`RuleSeverityTest` 只管 severity；`MetricPublisherMappingTest` 只管发布映射。
本轮新增的守卫把这三处**逐条对账**（数值层面，且要求目录 `thresholdJson` 里该规则的**唯一数值**），
从此任一漂移即 `RED`。

**守卫边界（不得越界表述）**：① 它是**静态源码解析**（正则），不是运行时断言 ⇒
「三处一致」只在**文本层面**被证明；② 它**只读**，不改任何属主的值；
③ **属主收敛**（删掉 Spark/Java 里重复的字面量、改为从目录取值）是**破坏性动作**，
按 anti-entropy 规则需**总控明确确认**后才做 —— 本轮**不做**，只登记（§6-R-5）；
④ `QualityChecker` 的解析器只认 `rule(runId, "CODE", checks, errors, "阈值", …)` 这一种调用形态，
若未来该调用被重构，守卫会**失败**（而不是静默通过）—— 这是刻意的 fail-closed 取向。

---

## 5. 为什么判定为 A 类（不触门），逐门核对

| 门 | 是否触发 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 否 | 只有 `ADD COLUMN` |
| ② 改已有字段类型或**既有业务语义** | **否** | 既有 6 列名字/类型/顺序/取值算法全未动；`rule_version` 是**此前不存在**的可追溯键，不改变任何既有列的含义；规则集合/阈值/hit 判据未动 |
| ③ 改已发布 Flyway migration | 否 | `metric V1`–`V7`、`meta V1`–`V2` **字节未动**；`V8` 为**新增**文件 |
| ④ 写/迁移正式 3306 数据 | 否 | 本轮 **0 次连库**，未触碰 3306/3307/ACTIVE |
| ⑤ 切 ACTIVE | 否 | 未涉及 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | 已全目录检索 `rule_version`/`ads_data_quality`：**0 命中** ⇒ 不覆盖本项，未改任何契约文件 |
| ⑦ 改 V3.0 总体架构 | 否 | 表/层/链路不变；**未新增第 9 张 ADS 表**（`TABLES` 仍 8 张），**未增删质量规则**；注：`ads_category_sale`/`ads_region_sale` 实产**属这一门**，故本轮**不做**（§0/§6-R-1） |
| ⑧ 改正式项目范围 | 否 | 属 §9.3 L335 + L320 + §12.3 L512 既有要求，未新增范围 |
| ⑨ 删除已发布功能 | 否 | 只增不减（列 + 守卫） |
| ⑩ 引入未规划大型基础组件 | 否 | 未加依赖、未加组件 |
| ⑪ 两种方案造成重大长期架构分叉 | 否 | 「版本列」沿用同库 `ads_user_profile_m.rule_version`（`rfm-v2`）与 meta `data_quality_result.rule_version` 的**既有**模式；备选方案「把作用域/阶段/严重度一并抄进 ADS」被 §12.3 的 meta 单一属主现状排除，不构成等价分叉 |

---

## 6. 未实测边界与待批注项（不得越界表述）

1. **MySQL `V8` 真库列存在性未测**：`MetricAdsCatalogDdlConsistencyTest` 只证「迁移文本 ↔ Java 白名单」
   一致（解析 SQL 文本，不连库）；`isolated` 档（WSL 3307）**无监听、本轮未跑**。
2. **两个真库 IT 本轮未运行**：`MetricPublisherMySqlIT` 夹具已补 `rule_version`（保持夹具与列集合对齐，
   避免无辜变红），`MetricAdsMySqlIT` 未改；两者**均无本轮实测证据**。
3. **在产 Hive 已建表的补列未做**：`LocalSchemaInitJob` 的 `CREATE TABLE IF NOT EXISTS` 对已存在表不生效
   ⇒ 已建过 `ads_data_quality`/`ads_data_quality__staging` 的库需显式 `ALTER TABLE … ADD COLUMNS`（部署事项）。
4. **没有门禁证明「ADS 的版本值 == 目录版本」在运行期成立**：新守卫证的是**源码文本**层面；
   运行期由新 spec 断言「落库的每行 `rule_version == 1`」，而 1 恰好等于本轮目录版本 ——
   这一等价关系是**人读代码 + 两条测试各证一半**，不是单点强约束。
5. **判别力上限**：全部证据是 Scala `local[1]` ＋ `catalogImplementation=in-memory`，
   **未跑真实 `spark-submit`、未连 Hive metastore** ⇒「本地测试通过」**不得**表述为「在产通过」。
6. **`warehouse/ddl/04-ads.sql` 无自动化整体守卫**（既有状态）⇒ 本次靠人工同步。
7. **质量大盘的新列暂无消费方**：`AnalysisService.quality()` 仍只读 `rule_code`/`passed`；
   前端质量卡与 AI 证据（`AnomalyRules` 用 `ads_data_quality_m.<ruleCode>@<snapshot>`）
   **均未消费** `rule_version` ⇒ 归**阶段4/5**。

**待总控批注（均不阻塞本轮）**：

- **R-1｜`ads_category_sale`/`ads_region_sale` 实产（§9.3 L336-337 + 指导书 L150）＝ 门⑦邻域，须总控冻结新版镜像规格**。
  现状：Hive DDL（`warehouse/ddl/04-ads.sql`）与 `dws_region_sale_day` 已声明，但
  `V3__metric_ads_r7.sql:2` 明文「本期只有 8 张 ADS ⇒ 不建这两张 `_m`」，「8 张」还被
  `QualityRuleCatalog.ADS_STAGING_PRESENT`（「8 张暂存表」）/`MXP_EXPORT_COMPLETE`、
  `MetricPublishValidator.MP_MANIFEST_TABLES`、`MetricAdsSpecTest.should be(8)` 三处判据钉住。
  加表会同时改动**已发布镜像契约**与质量门判据 ⇒ 需总控冻结新版规格（含新的 `_m` 建表迁移号、
  发布清单条目数、`ADS_STAGING_PRESENT` 的期望表数与阈值）后才可实施。**本轮不动。**
- **R-2｜L335 同行的「实时结果」（发布链实时回写）未做**：当前质量结果由 Spark 批次产出，
  没有「实时/流式」回写 ADS 大盘的链路；是否属 V3.0 范围、以何种机制接齐，请批注。
- **R-3｜§12.4 L520 读侧历史版本复算**：设计已把它归 F-93 及相关 backlog 且明写「不阻塞无关业务开发」，
  本轮**未做**；若希望提前启动，请指定优先级。
- **R-4｜§12.5 L528 `mxp` checksum**：未做（既有登记项）。本轮加列使 `ads_data_quality_m` 的
  导出清单列集合发生变化 ⇒ 若将来启用 checksum，本表的期望值需按新列集合重算。
- **R-5｜阈值属主收敛（破坏性）**：见 §4 边界③。是否把 Spark/Java 的文字阈值改为**从目录取值**
  （或由目录生成），请总控确认；本轮只加只读守卫。
- **R-6｜`rule_version` 是否也在 ADS 落「生效严重度」**：本轮**刻意不落**（避免第二严重度属主，§2）。
  若总控要求大盘行自带 `effective_severity`（例如让页面直接显示「告警/阻断」），需要先裁定
  「谁是大盘严重度属主」，并同步 `V8` 的列集合。

---

## 7. 本轮证据（本地，不入库）

`.verify/v3-stage3/s3-05-dq-rule-version/`：

- `red/java-guard-red.log`（守卫 RED：`Tests run: 5, Failures: 5`，5 条失败原因**均为**
  `ADS 质量大盘 <规则码> 缺 <版本> AS rule_version（§9.3 L335 要求规则版本随行落库）` ⇒ 归因正确）
- `red/spark-spec-red.log`（行为 RED：`Tests: succeeded 3, failed 2`，两条失败正是「缺版本列」与
  「四条规则逐行带版本」；其余 3 条（阈值/实际值对账、列序对账）在改前改后都应成立，本来就绿）
- `green/spark-spec-green.log`（新套件 `5/5`，`All tests passed`）
- `green/java-guard-green.log`（守卫 `5/5`，`BUILD SUCCESS`）
- `green/spark-affected-green.log`（受影响 4 套件 `MetricAdsSpecTest`+`SqlTemplateSpec`+
  `AdsQualityRuleVersionSpec`+`WarehouseNamespaceSpec` 合并跑：**succeeded 40, failed 0**）
- `green/java-all-green.log`（`analytics-server` 全反应堆：`90+350+153+57+92+145 = 887`，
  唯一红为已登记环境性 `IngestionManifestRuntimePatrolTest`）
- `gate/spark-console.log`（**`[PASS exit=0]`**：`Tests run: 208 (failed=0)`、`套件 24`、`新写=True`、`JDK8=True`）
- `gate/default-console.log`（analytics `887 MATCH`、mall `13 MATCH`、generator `106 MATCH`、
  三棵树 `1006`（基线 1006）、`[FAIL exit=7]` —— 唯一红为已登记环境性
  `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，本工作树 `landing/manifests` 不存在，
  43 份历史清单仅在主工作树，本轮**未修、未复制 manifest、未用开关掩盖**）
- `gate/spark-jobs.log`、`gate/default-*.log`（各档原始日志）
- `commit-msg.txt`（本提交的完整提交信息留痕）
- 交叉引用：F-38（`docs/status-history/开发过程事实与决策记录.md`）为同一批证据的文字留痕。
