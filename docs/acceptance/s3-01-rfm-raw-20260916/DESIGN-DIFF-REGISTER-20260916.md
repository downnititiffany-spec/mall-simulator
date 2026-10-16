# S3-01 设计差异登记（2026-09-16）

> 提交：`1bd16b7`（`feat(ads): S3-01 画像补 R/F/M 原值与观察窗口 + 修窗口正则被解析器吃转义`），
> 分支 `feature/v3-development`，**未 merge main**。
> 本文件性质：**登记（register）**，不是「请求裁定才能动工」的阻塞项。按指导书 §12 L271，
> 代码 Agent **不修改** `docs/guidance/**` 与 `docs/design/**` 两份正式文档；本文件只如实登记
> 「设计原文要求什么／此前实现是什么／本轮改了什么／凭什么判定它不需要停工」。

---

## 0. 一句话结论

设计 §11.4 **L447** 要求「记录 R/F/M **原值**、score、segment、窗口、`rule_version`，**不仅存标签**」，
此前实现**只存标签**；本轮按原文补齐（纯**末尾追加**列），并同轮修掉一处使 **R 分档完全失效**的
实现缺陷（窗口 ISO 化正则被 SQL 解析器吃掉反斜杠转义）。**判定：A 类（加法 + 修正实现遗漏）**，
不触发 11 条破坏性决策门中的任何一条，故按「默认自主连续开发」直接实施、测试、提交。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §11.4 **L447** | 「R低更好，F/M高更好；五分位评分，按高/低组合得到8类。NTILE的同值加稳定源/用户ID排序保证可复现，但同值用户可能被拆分，必须展示评分规则；是否改为同值同分属于口径变化。**记录R/F/M原值、score、segment、窗口、rule_version，不仅存标签。**小样本明确"仅演示"。」 | 补 `r_days`/`f_count`/`m_amount` + `period_start`/`period_end`；`rule_version` 升 `rfm-v2` |
| 设计 §9.3 **L334** | 「`ads_user_profile` / `ads_user_profile_m`：**历史已发布，RFM 完整性需补**。」 | 本次即该「补」；Java 白名单 + MySQL 加性迁移同步 |
| 指导书 §7 阶段3 ① | 「固定粒度／分子分母／时间窗口／金额退款口径／空值规则／版本」 | 窗口与版本落列（粒度/退款口径见 §4 边界） |
| 指导书 §8 阶段3 最小完成标准 | 「稳定指标公式、分层对账与发布制品；失败保旧；不靠前端/AI 临时算出指标」 | 原值随制品落库，不再需要前端反推 |
| 指导书 §12 L271 | 「Code Agent 只提交设计差异请求，不自行修改两正式文档」 | 本轮**未改** guidance/design 任何字节 |

**「不仅存标签」的失败样例（改前真实状态）**：`r=5` 一行只说明「在五分位里 R 最差」，
既不能回答「上次购买距今几天」，也不能回答「这批用户的观察期是哪一段」；
也无法与上游 `dws_user_trade_period` 做**金额/窗口**对账 —— 即「只存标签」的字面后果。

---

## 2. 本轮实施面（五处 + 参考副本 + 守卫）

| 所有者 | 文件 | 变更 |
|---|---|---|
| Spark 计算 | `spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala` | 投影末尾追加 5 列；新增无反斜杠的 `isoDay`（见 §3） |
| Spark DDL（派生） | `.../job/LocalSchemaInitJob.scala` | 正式表 + `__staging` 同步补 5 列 |
| Spark 列真源 | `.../metric/MetricAdsSpec.scala` | `ads_user_profile` 12 → **17** 列 |
| Java 白名单 | `analytics-server/metric-analysis/.../MetricAdsCatalog.java` | `ads_user_profile_m` 同步 17 列 |
| MySQL 镜像 | `platform-app/src/main/resources/db/metric/V4__ads_user_profile_rfm_raw.sql` | **新增加性迁移**（单条 `ALTER TABLE … ADD COLUMN`×5，`NOT NULL DEFAULT`，沿用 DEF-10 约定） |
| 手持参考副本 | `warehouse/ddl/04-ads.sql` | 同步（该文件无自动化整体守卫，见 §4-④） |
| 服务层夹具 | `MetricPublisherMySqlIT.java` | 真库 IT 夹具补列（该 IT 本轮**未运行**） |
| 守卫（Java） | `MetricAdsCatalogDdlConsistencyTest.java`（新增 3 条） | 解析 `db/metric/*.sql`（按版本号升序 CREATE + 有序 ALTER）与白名单**逐表逐列**比对 |
| 守卫（Scala） | `AdsRfmRawValueSpec.scala`（新增 6 条） | 原值/窗口/分档/标签 + 血缘一致性 + DDL 列序 |
| 既有钉子 | `MetricAdsSpecTest.scala`、`SqlTemplateSpec.scala` | Java 镜像列数与 `'rfm-v2' as rule_version` 同步 |

**兼容性承诺（可复核）**：V3 已发布的 12 列（`user_id, r, f, m, value_group, active_level,
favorite_category, last_active_date, last_buy_date, lifecycle_state, rule_version, calc_date`）
**名字/类型/顺序一格未动**，新列一律在末尾；`V1`–`V3` 迁移文件**未改一个字节**。

---

## 3. 同轮修掉的实现缺陷（为什么它属于「修正遗漏」而不是「口径变更」）

**现象（实测，非读代码推断）**：`AdsSql.userProfile` 原用
`regexp_replace('20260901', '(\d{4})(\d{2})(\d{2})', '$1-$2-$3')` 把窗口转 ISO。实测 Spark SQL
**字符串字面量会吃掉未识别的反斜杠转义**：SQL 文本 `'(\d{4})'` 的解析结果是 `(d{4})`
（写成 `'(\\d{4})'` 才是 `(\d{4})`）⇒ 正则不匹配、`regexp_replace` **原样返回** `20260901`；
且 `DATEDIFF('20260901','2026-09-01') = NULL`。

**五个可独立观测的后果（RED 实测原文）**：

| # | 后果 | RED 实测 | GREEN 实测（修后） |
|---|---|---|---|
| ① | `r_ntile` 排序键恒 `NULL` ⇒ R 分档退化为 `user_id` 次序 | `r = {1:5, 2:4, 3:3, 4:2, 5:1}` | `r = {1:1, 2:2, 3:4, 4:3, 5:5}`（随 `r_days` 92/43/12/31/0 反向单调） |
| ② | R 原值落空 | `r_days` 全 `<NULL>` | `92 / 43 / 12 / 31 / 0` |
| ③ | `active_level` 恒「低」 | 全「低」 | 高/中/低/高/低 |
| ④ | `lifecycle_state` 恒「活跃」，「新用户」**永不成立** | 全「活跃」 | 流失风险/沉默/活跃/新用户/活跃 |
| ⑤ | 窗口落原始口径而非评分窗口 | `20260801` | `2026-08-01` / `2026-09-01` |

**修复**：改为**无反斜杠**的 `substr`/`concat`（`AdsSql.isoDay`），并把该实测事实写进 KDoc
（防止后人「顺手改回正则」）。

**为什么不是口径变更**：§11.4 的**定义口径**（R低更好、F/M高更好、五分位、8 类、
同值加稳定次序键）**一个字都没改**；改的是「实现是否真的按该口径算」。
`rule_version` 的语义正是「这批数据按哪版口径产生」⇒ 取值改变**必须**升版本
（`rfm-v1` → **`rfm-v2`**），否则新旧口径的数据会在同一标签下混算。
**未改写任何历史数据与已发布产物**：`docs/acceptance/**` 中带 `rfm-v1` 的 jsonl 原文**未动**。

---

## 4. 为什么判定为 A 类（不触门），逐门核对

| 门 | 是否触发 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 否 | 只 `ADD COLUMN`，无任何 DROP |
| ② 改已有字段类型或**既有业务语义** | **否** | ①**类型未动**（12 列类型逐一未改，新列独立）；②§11.4 评分**定义**未变 —— 变的是「实现是否按定义算」，属**修正实现遗漏**；③`rule_version` 升版**正是**为避免语义混算的既定机制，而非改写语义 |
| ③ 改已发布 Flyway migration | 否 | `V1`–`V3` 字节未动，`V4` 为**新增**文件 |
| ④ 写/迁移正式 3306 数据 | 否 | 本轮 **0 次连库**，未触碰 3306/3307/ACTIVE |
| ⑤ 切 ACTIVE | 否 | 未涉及 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | 已逐文件核对：`contract-specs/**` 内**无** `ads_user_profile`/`ads_user_profile_m`/`r_days`/`f_count`/`m_amount`/`period_start`/`period_end`/`rule_version` 任何引用 ⇒ **不覆盖本表**，未改任何契约文件 |
| ⑦ 改 V3.0 总体架构 | 否 | 表/层/链路不变 |
| ⑧ 改正式项目范围 | 否 | 属 §9.3/§11.4 既有要求，未新增范围 |
| ⑨ 删除已发布功能 | 否 | 只增不减；旧 12 列仍可读 |
| ⑩ 引入未规划大型基础组件 | 否 | 无新组件（未加依赖） |
| ⑪ 两种方案造成重大长期架构分叉 | 否 | 唯一可替代方案（SQL 正则）已被实测证伪，不存在分叉 |

**对 F-33 既有判断的自我更正（如实留痕）**：F-33 backlog 曾把本项登记为
「属门 ②/⑥ 邻域，**只登记不擅改**」。逐条核对后**改为 A 类**，依据即上表 ②⑥ 两行
（列集纯加法 + 契约零覆盖）。同一类「先推断后核对」的纠偏在 F-33 已出现过两次，
本次为第三次实例 —— 教训不变：**「涉及是否属门」的登记必须先核对两侧实际文件**。

---

## 5. 未实测边界（不得越界表述）

1. **MySQL 新列真库存在性未测**：`MetricAdsCatalogDdlConsistencyTest` 只证「迁移文本 ↔ Java 白名单」
   一致（解析 SQL 文本，不连库）。`isolated` 档（WSL 3307）**无监听、本轮未跑**；
   `MetricPublisherMySqlIT` 本轮**未运行**。
2. **在产 Hive 已建表的补列未做**：`LocalSchemaInitJob` 的 `CREATE TABLE IF NOT EXISTS`
   对**已存在**表不生效 ⇒ 已建过 `ads_user_profile` 的库需显式
   `ALTER TABLE … ADD COLUMNS`（部署事项，不在本任务范围；已在代码 KDoc 登记）。
3. **证据环境**：全部为 Scala `local[1]` ＋ `catalogImplementation=in-memory`，
   **未跑真实 `spark-submit`、未连 Hive metastore** ⇒「本地测试通过」**不得**表述为「在产通过」。
4. **`warehouse/ddl/04-ads.sql` 无自动化整体守卫**（既有状态；D-09 已把 `_m` 命名漂移归 `V25-C01`）
   ⇒ 该文件本次靠人工同步，未被任何测试覆盖。
5. **新增列尚无消费方**：`RfmService`/`AnalysisViewModel` 仍按旧 12 列直通；
   审计 §13.4「RFM 金额缺失／用 m 分求和冒充金额」在**服务层仍未修** ⇒ 归**阶段4**任务。
6. **空值/退款口径**：`f_count`/`m_amount` 直接取 DWS `dws_user_trade_period` 的
   `order_count`/`sale_amount`（口径由 `DwsSql.userTradePeriod` 拥有）。**多行订单的部分退款分摊**
   仍**未测**（夹具每单 1 行明细），`m_amount` 的退款后口径未独立验证。
7. **评分规则展示**：§11.4 要求「必须展示评分规则」——本次落库了 `rule_version` 与窗口，
   但**前端/接口展示**未做（阶段5）。
8. **同值同分**：§11.4 明确「是否改为同值同分属于口径变化」⇒ **不改**，维持 `NTILE` 同值可拆分，
   由 `rule_version` 与窗口列提供可复现依据。

---

## 6. 若总控认为需另行处置

本登记**不阻塞**任何后续开发。若总控对以下任一处置有不同意见，请在登记上批注，代码侧按批注执行
（均为小改，不影响主线）：

- **R-1**：`rule_version` 的取值命名（`rfm-v2`）是否需要另行定义命名规范。
  *现状*：值改变必须升版本，否则新旧数据在同一标签下混算；本轮取 `rfm-v2`。
- **R-2**：新增列的**在产 Hive 补列**（`ALTER TABLE … ADD COLUMNS`）是否纳入部署脚本/验收清单。
  *现状*：仅登记为边界，未实施。
- **R-3**：服务层消费原值（`r_days`/`m_amount`，审计 §13.4）是否提前到阶段3 一并做。
  *现状*：按指导书阶段划分归**阶段4**。

---

## 7. 本轮证据（本地，不入库）

`.verify/v3-stage3/s3-01-rfm-raw/`：

- `red/console.log`、`red2-defect/console.log`（缺陷判别 RED：`r` 取 `user_id` 次序、窗口 `20260801`）
- `guard-teeth/console.log`（Java 守卫 RED）+ `console-green.log`（GREEN 3/3）
- `focused-green2/console3.log`（`AdsRfmRawValueSpec` `succeeded 6, failed 0`、`BUILD SUCCESS`）
- `spark-gate-final2.log`（**`[PASS exit=0]`**：`Total number of tests run = 183`、
  `Suites: completed 20, aborted 0`、`succeeded 183, failed 0`、`tests=183 MATCH`、`新写=True`、`JDK8=True`）
- `default-gate2.log`（`analytics-server 875 MATCH`、`mall 13 MATCH`、`generator 106 MATCH`、
  三棵树 `994`、无 DRIFT、`[FAIL exit=7]` —— 唯一红为已登记环境性
  `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`）
- `spark-gate.log`、`spark-gate-final.log`、`default-gate.log`（**无效运行**：RunId/Tee 日志目录冲突，
  保留作过程证据，不作通过依据）
