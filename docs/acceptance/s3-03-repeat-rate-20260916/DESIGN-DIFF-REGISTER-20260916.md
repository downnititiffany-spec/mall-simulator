# S3-03 设计差异登记（2026-09-16）

> 提交：`b896d83`（`feat(ads): S3-03 ads_operation_overview 落地有效复购率 repeat_rate + 观察期声明（设计 §11.2 L433）`），
> 分支 `feature/v3-development`，**未 merge main**。
> 本文件性质：**登记（register）**，不是「请求裁定才能动工」的阻塞项。按指导书 §12 L271，
> 代码 Agent **不修改** `docs/guidance/**` 与 `docs/design/**` 两份正式文档；本文件只如实登记
> 「设计原文要求什么／此前实现是什么／本轮改了什么／凭什么判定它不需要停工」。

---

## 0. 一句话结论

设计 §11.2 **L433** 逐字要求 `repeat_rate` =「有效购买≥2次用户/支付用户；完全退款不算有效复购订单」，
并**硬性附加**「**声明观察期和变体**」；`docs/contracts/metric-dictionary.md` **L30** 已把它写进 16 行字典
（源表 `dws_user_trade_period`、grain「观察期（默认30天）」），`analytics_meta.metric_definition`（V2 种子，
**已发布**）也已有该码 —— 但 **ADS / MySQL 镜像 / `metric_value` / 血缘表全部没有承载**：
`docs/contracts/metric-lineage.md` 自己把它登记在「**字典存在但尚无 ADS 承载**」表里（原文「**未落地**」）。
更关键的是：DWS `dws_user_trade_period` 只有 `order_count`（**含全额退款订单**），
**即使硬算也得不到设计要求的有效口径** —— 同一夹具下「有效复购率 = 0.3333」而「支付复购率（朴素）= 0.6667」。
本轮按设计原文补齐（DWS 加有效订单口径列 + ADS 加 3 列 + 加性迁移 + 发布侧窗口声明），
**未改任何既有列的名字/类型/顺序/取值算法**。
**判定：A 类（实现遗漏 + 纯加性）**，不触发 11 条破坏性决策门中的任何一条 ⇒ 按「默认自主连续开发」
直接实施、测试、提交。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §11.2 **L433** | 「\| repeat_rate \| 有效购买≥2次用户/支付用户；完全退款不算有效复购订单 \| **声明观察期和变体** \|」 | 分子改为**有效**订单数（DWS 新列 `valid_order_count`），分母为支付用户；观察期落 `repeat_period_start/end` 并在 `metric_value.period` 声明 `window:` |
| 设计 §11.2 **L437** | 「黄金55条历史标准值：… 支付订单**5** …」 | 黄金值**未套用**到本轮自建夹具（跨输入套用属禁止项）；本轮夹具自算 oracle 为 3 支付用户 / 1 有效复购用户 |
| 设计 §11.4 **L449** | 「复购区分**支付复购/有效复购**，取消不计、**全退是否剔除按当前有效口径**」 | 变体声明：本轮落地**有效复购率**（默认展示变体）；「支付复购率」登记为口径参数切换（backlog），**不新增指标码** |
| 字典 `docs/contracts/metric-dictionary.md` **L30** | 「\| repeat_rate \| 复购率（有效） \| `观察期内有效支付订单数≥2 的用户数 ÷ 观察期内支付用户数`；取消不计购买，完全退款订单从“有效复购率”排除 \| 观察期（默认30天） \| paid_at \| dws_user_trade_period \|」 | 与本轮实现**逐项对齐**：源表 = `dws_user_trade_period`，分子 = `valid_order_count >= 2`，分母 = 该表用户数 |
| 字典 **L34** | 「复购率**必须声明观察期**；页面默认展示“有效复购率”，另可由口径参数切换“支付复购率”」 | 窗口由 DWS 行声明并 ISO 化落 ADS；变体以「有效」为默认 |
| 指导书 §7 阶段3 ①（L148） | 「对每个指标固定粒度、分子分母、**时间窗口**、金额/退款口径、**空值规则**和版本」 | 五要素在本轮一次性冻结（见 §2 口径声明） |
| 指导书 §7 阶段3 ②（L149） | 「Spark SQL 计算销售、用户、商品、漏斗及质量专题，**逐层对账**」 | 新 spec 含 DWS↔ADS 逐列、ADS↔DWD 独立 oracle、列序三向一致三组对账 |
| 指导书 §8 阶段3（L199） | 「稳定指标公式、分层对账与发布制品；失败保旧；**不靠前端/AI临时算出指标**」 | 复购率随制品（ADS + 镜像列 + `metric_value`）落库，不留给前端用「订单数≥2」临时拼 |
| 指导书 §12 **L271** | 「Code Agent 只提交设计差异请求，不自行修改两正式文档」 | 本轮**未改** guidance/design 任何字节 |

**失败样例（改前真实状态）**：`metric_definition` 里有 `repeat_rate`、页面与 AI 语义目录都可能按码取数，
但 ADS 无列 ⇒ 取值为空；若有人「按名字硬算」，DWS 只能给 `order_count`（含全退）⇒ 得到的是
**支付复购率**（本夹具 0.6667）而不是设计要求的**有效复购率**（0.3333）—— 正是 L433 后半句点名的坑。

---

## 2. 口径声明（本轮冻结，已写进 ADS 列注释、迁移头与语义层）

| 要素 | 本轮冻结取值 | 依据 / 证据 |
|---|---|---|
| 粒度 | 业务日 `dt` 一行（ADS 概览行内一列） | 与 `ads_operation_overview` 既有粒度一致 |
| 分子 | 观察期内**有效购买 ≥2 次**的用户数 | DWD 单一属主：`final_paid_flag=1`（未取消）且 `final_refunded_flag=0`（非全退）；部分退款仍算有效 |
| 分母 | 观察期内**支付用户数** `COUNT(DISTINCT user_id)`（含后续全退用户） | 与既有 `AdsSql.operationOverview` 的漏斗口径/黄金 oracle 的 `buyUsers` 一致 |
| 变体 | **有效复购率**为默认；`支付复购率`（支付订单数≥2）登记为口径参数切换 | 设计 L449 + 字典 L34；**不新增指标码** |
| 时间窗口 | 既有作业参数 `[periodStart, periodEnd]`（`DwsSql.userTradePeriod` 的写入者），由 DWS 行声明并 ISO 化落 `repeat_period_start/end`；缺省 = 统计日当日 | 沿用既有缺省；页面「30 天窗口」需显式传参 ⇒ backlog（阶段4） |
| 空值规则 | 该业务日**无支付用户** ⇒ 复购率与两列窗口**同时为 NULL**（不写 0、不写当日日期冒充窗口） | 新 spec 用 `row.isNullAt(...)` 断言；发布侧遇 NULL 跳过 `metric_value`（列 NOT NULL，宁缺勿造） |
| 版本 | `v1`（与 `metric_definition` 既有行一致） | 公式首次落地，无历史口径需区分 |

**为什么「窗口必须由 DWS 行声明」而不是在 ADS 里写死**：`AdsSql` 不拥有观察期，
`DwsSql.userTradePeriod` 才是窗口的写入者（作业参数）；ADS 只能**回述**上游写下的窗口。
若 ADS 自行按「dt 往前推 30 天」声明，就会出现**两个观察期所有者**，且与 DWS 实际聚合范围可能不一致
（属反熵反面，本轮明确不做）。

---

## 3. 本轮实施面（含列序所有者与守卫）

| 所有者 | 文件 | 变更 |
|---|---|---|
| Spark DWS 计算 | `spark-jobs/.../sql/DwsSql.scala` | `userTradePeriod` 末尾追加 `valid_order_count`（`COUNT(DISTINCT CASE WHEN final_refunded_flag=0 THEN order_id END)`）；KDoc 写明它与 `order_count` 的差别 |
| Spark ADS 计算 | `.../sql/AdsSql.scala` | `operationOverview` 增第 4 个标量子查询（支付用户/有效复购用户/窗口上下界）+ 投影 `repeat_rate`、`repeat_period_start`、`repeat_period_end`；新增无反斜杠的 `isoDayCol`（S3-01 反斜杠坑的同类规避） |
| Spark DDL（派生） | `.../job/LocalSchemaInitJob.scala` | DWS 加 `valid_order_count BIGINT`；正式 + `__staging` 的概览表各加 3 列；`R7_ADDED_COLUMNS` 追加性清单同步（顺序须与 DDL 尾部一致） |
| Spark 列真源 | `.../metric/MetricAdsSpec.scala` | `ads_operation_overview` 9 → **12** 列 |
| Java 白名单 | `analytics-server/metric-analysis/.../MetricAdsCatalog.java` | 概览 9 → **12** 列（顺序一致） |
| MySQL 镜像 | `platform-app/src/main/resources/db/metric/V6__ads_operation_overview_repeat_rate.sql` | **新增加性迁移**：3 列 `NULL` 可空；头部声明**两类 NULL 语义**（无支付用户 / 历史快照未计算） |
| 迁移挂载说明 | `.../config/MetricFlywayInitializer.java` | KDoc 版本链补「→ V6 运营大盘复购率与观察期声明」 |
| 发布侧 | `.../metric/publish/MetricPublisher.java` | `repeat_rate → metric_value(repeat_rate)`；窗口型指标的 `period` 落 `window:<start>..<end>`，未声明窗口时降级 `day:` 并 `WARN`；NULL 值跳过 |
| 语义层（AI 提示词） | `analytics-server/ai-decision/.../ai/SemanticCatalog.java` | 概览登记 `repeat_rate` / `repeat_period_start` / `repeat_period_end` |
| 血缘登记 | `.../ai/evidence/MetricLineage.java`、`docs/contracts/metric-lineage.md` | `repeat_rate` 进已落地清单（#13）；从「尚无 ADS 承载」表移出（16 = **13 落地 + 3 未落地**），并修正原「未落地」原因文案 |
| 手持参考副本 | `warehouse/ddl/03-dws.sql`、`warehouse/ddl/04-ads.sql` | 同步（该两文件**无**自动化整体守卫，属人工同步） |
| 服务层夹具 | `MetricPublisherMySqlIT`、`MetricAdsMySqlIT`、`AiSqlSecurityTest` | 补列 / 白名单（两个真库 IT 本轮**未运行**，属静态一致性编辑，状态仍为未测） |
| 守卫（Scala） | `AdsRepeatRateSpec.scala`（新增 6 条） | DWS 有效订单数与窗口列、ADS 复购率与窗口声明、**独立 DWD 金额 oracle**、无买家 NULL 规则、质量规则不误判、正式/暂存 DDL 列序 |
| 守卫（Java） | `MetricPublisherMappingTest.java`（新增 4 条） | 发布侧映射 + `window:` 声明 + 缺窗口降级 + NULL 跳过；**不连库**，把 `MP_METRIC_VALUE_COUNT` 下限守护交给真实映射 |
| 既有钉子 | `MetricAdsSpecTest.scala`、`AdsRfmRawValueSpec.scala`、`AdsStableOrderSpec.scala` | 见 §4（全量档才暴露的 6 处红） |

**兼容性承诺（可复核）**：概览表既有 9 列（`pv…full_refund_rate`）的名字/类型/顺序**一格未动**，
新 3 列全部在末尾；`dws_user_trade_period` 既有 6 列同样未动；`V1`–`V5` 迁移文件**未改一个字节**。

---

## 4. 全量档才暴露的守卫盲区（本轮第二个盲区，如实登记）

**现象（实测，非读代码推断）**：定向只跑本任务新套件时 **6/6 全绿**；
但**全量** spark 档出现 `189 succeeded / **6 failed**`（日志 `red/full-suite-regression-6-fail.log`）：

| # | 失败位置 | 原文（节选） | 归因 |
|---|---|---|---|
| 1–5 | `AdsRfmRawValueSpec`（4 条）、`AdsStableOrderSpec`（1 条） | `INSERT_COLUMN_ARITY_MISMATCH.NOT_ENOUGH_DATA_COLUMNS … Table columns: … valid_order_count … Data columns: 6` | 这两个套件**自己手写** `INSERT OVERWRITE … dws_user_trade_period`（6 列），DWS 加列后按位置写入 ⇒ 列数不足。**它们不是本次任务的套件，所以定向跑永远看不见** |
| 6 | `MetricAdsSpecTest`（1 条） | `was not equal to List(… full_refund_rate)`（少 `repeat_rate/repeat_period_start/repeat_period_end`） | 该套件**刻意硬编码**一份 Java `MetricAdsCatalog` 列清单（防「Spark 导出列与 MySQL 表不匹配」），是**第二个列序所有者**，未同步 |

**处置**：两处夹具按「该夹具不建模退款 ⇒ `valid_order_count = order_count`」语义补齐
（注释写明理由），并**先复跑受影响的 4 个套件 21/21 绿、再跑全量 195/195 绿**。
**教训（写入 F-36）**：加列类变更的「定向跑绿」**不足以**作为完成判据 ——
**每一层 DDL 都可能有第二个所有者**（手写夹具 / 硬编码镜像清单），只有全量档能证明没有漏网。

---

## 5. 为什么判定为 A 类（不触门），逐门核对

| 门 | 是否触发 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 否 | 只有 `ADD COLUMN` |
| ② 改已有字段类型或**既有业务语义** | **否** | 既有列的名字/类型/顺序/取值算法**全未动**；新增的是**此前不存在**的指标列。★与 `metric_definition`（V2 种子，已发布）行文案的差异见 §6-R1：权威口径取设计 L433 与 `docs/contracts/metric-dictionary.md:30`（均写「有效」），种子行的旧文案不动 |
| ③ 改已发布 Flyway migration | 否 | `metric V1`–`V5`、`meta V1`–`V2` **字节未动**；`V6` 为**新增**文件 |
| ④ 写/迁移正式 3306 数据 | 否 | 本轮 **0 次连库**，未触碰 3306/3307/ACTIVE |
| ⑤ 切 ACTIVE | 否 | 未涉及 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | 已全目录检索 `repeat_rate`/`复购`：**0 命中** ⇒ 不覆盖本指标，未改任何契约文件。（`docs/contracts/metric-lineage.md` 属**可编辑**的血缘副本，非 `contract-specs/**`） |
| ⑦ 改 V3.0 总体架构 | 否 | 表/层/链路不变；**未新增第 9 张 ADS 表**（`AdsSql.TABLES` 仍 8 张） |
| ⑧ 改正式项目范围 | 否 | 属 §11.2 L433 既有要求，未新增范围 |
| ⑨ 删除已发布功能 | 否 | 只增不减 |
| ⑩ 引入未规划大型基础组件 | 否 | 未加依赖、未加组件 |
| ⑪ 两种方案造成重大长期架构分叉 | 否 | 观察期**只有一个所有者**（DWS 写入者），ADS 只回述；备选「ADS 自算 30 天窗口」会造出第二个观察期所有者 ⇒ 不构成等价分叉 |

**「有效 vs 支付」变体的边界声明**：设计 L449 明确要求**区分**两种复购；本轮只落地**有效**这一支
（字典 L34 的页面默认），另一支登记为**口径参数切换**（backlog，阶段4/5）——
这是**未做的部分**，不是「改了语义」。

---

## 6. 未实测边界与待批注项（不得越界表述）

1. **MySQL `V6` 真库列存在性未测**：`MetricAdsCatalogDdlConsistencyTest` 只证「迁移文本 ↔ Java 白名单」
   一致（解析 SQL 文本，不连库）。`isolated` 档（WSL 3307）**无监听、本轮未跑**；
   `MetricPublisherMySqlIT` / `MetricAdsMySqlIT` 本轮**未运行**（夹具已补列与计数 10→11，但**无证据**）。
2. **`V6` 的历史快照语义**：3 列 `NULL` 可空对已存在的历史快照行**保持 NULL**（＝未计算占位，
   **不得当真实 0**，也不得当成「无复购」）；真库上的实际效果**未观测**。
3. **在产 Hive 已建表的补列未做**：`LocalSchemaInitJob` 的 `CREATE TABLE IF NOT EXISTS` 对已存在表不生效
   ⇒ 已建过 `dws_user_trade_period` / `ads_operation_overview` 的库需显式 `ALTER TABLE … ADD COLUMNS`（部署事项）；
   `R7_ADDED_COLUMNS` 只覆盖概览表，**不覆盖**非概览 ADS 表（既有登记）。
4. **新增列尚无消费方**：`AnalysisService` / `AnalysisViewModel` / 前端仍按旧列直通 ⇒ 服务层与页面消费
   复购率与观察期归**阶段4/5**（与 S3-01/S3-02 同类边界）。
5. **观察期的默认值仍是「统计日当日」**：字典写「观察期默认 30 天」，本轮**未改缺省参数**
   （改缺省＝改既有作业语义，属门②邻域）⇒ 页面 30 天窗口必须**显式传参**，登记 backlog。
6. **证据环境**：全部为 Scala `local[1]` ＋ `catalogImplementation=in-memory`，
   **未跑真实 `spark-submit`、未连 Hive metastore** ⇒「本地测试通过」**不得**表述为「在产通过」。
7. **质量规则的边界**：本轮**刻意不把** `repeat_period_*` 加入 `AdsQualityJob.keyPredicates` 的概览谓词 ——
   「无支付用户」是**合法业务状态**（窗口与复购率同为 NULL），加进去会造成**假阳性**。
   同理 `repeat_rate` **不进** `MetricPublishValidator.OVERVIEW_REQUIRED`。
8. **判别力上限**：夹具每单 1 行明细、单次退款；**多行订单的部分退款分摊**与**跨业务日退款重结**仍**未测**
   （与 S3-02 同类边界，退款归属期未冻结）。
9. **`warehouse/ddl/03-dws.sql`、`04-ads.sql` 无自动化整体守卫**（既有状态；D-09 的 `_m` 命名漂移归 `V25-C01`）
   ⇒ 本次靠人工同步。

**待总控批注（均不阻塞）**：

- **R-1｜`metric_definition` 种子行文案与权威字典不一致（已发布迁移，本轮未改）**：
  `db/meta/V2__platform_pipeline_quality.sql:74` 写「`支付订单数>=2 的用户数/支付用户数`」，
  而设计 §11.2 L433 与 `docs/contracts/metric-dictionary.md:30` 写「**有效**…≥2」。
  *现状*：按**设计 + 可编辑字典**实现有效口径；V2 属**已发布迁移**（门③）故**未改**。
  若总控要求种子行文案对齐，需（a）一条新的加性迁移对 `metric_definition` 该行做 `UPDATE`，
  而这**写正式库数据**（门④）⇒ 需先批准；或（b）接受为历史文案漂移并在此登记。
- **R-2｜「支付复购率」变体的落地方式**：本轮按「不新增指标码」处理，预留为**口径参数切换**
  （阶段4/5 的服务层入参）。若总控希望**立即**以第二指标码落地，请批注。
- **R-3｜观察期缺省值**：是否把作业缺省从「统计日当日」改为字典的「默认 30 天」。
  *现状*：未改（改缺省影响既有 `dws_user_trade_period` 语义 ⇒ 门②邻域）；页面 30 天窗口需显式传参。
- **R-4｜`repeat_period_*` 是否应由 DWS 列直接落到 `metric_value` 之外的报表位**（如快照级
  `metric_snapshot.period`）。*现状*：只落 ADS 列 + 单个指标的 `metric_value.period = window:`；
  快照级 `period` 仍是 `day:`（快照可能同时含日粒度与窗口型指标，语义待定）⇒ 已登记 backlog。

---

## 7. 本轮证据（本地，不入库）

`.verify/v3-stage3/s3-03-repeat-rate/`：

- `red/spark-spec-red.log`（行为 RED：`AdsRepeatRateSpec` **succeeded 2 / failed 4**，
  原文 `UNRESOLVED_COLUMN … valid_order_count … Did you mean [order_count, sale_amount, period_end, period_start, user_id]`
  与 ×4 `repeat_rate does not exist. Available: pv, uv, dau, order_count, sale_amount, net_sale_amount,
  avg_order_value, refund_rate, full_refund_rate, snapshot_id, dt`；另 2 条为**既有不变量**，本就绿且必须保持绿）
- `red/java-mapping-red.log`（发布侧 RED：`Tests run: 4, Failures: 1, Errors: 1` ——
  `repeat_rate 却没有映射成指标值，码=[pv, uv, dau, paid_order_cnt, gmv, net_sale, avg_order_value, refund_rate, full_refund_rate, buy_rate]`；
  `missingWindowFallsBackToDayPeriod ? NoSuchElement`）
- `red/full-suite-regression-6-fail.log`（**§4 盲区证据**：定向绿而全量 6 红，含两条归因的完整堆栈）
- `green/spark-spec-green.log`（新套件 **6/6 PASS**）
- `green/spark-affected-green.log`（受影响的 4 个套件 **21/21 PASS**）
- `green/java-modules-green.log`（`metric-analysis` + `ai-decision` + `platform-common` 真 Maven：
  **55 / 92 / 90**，含新套件 `MetricPublisherMappingTest` 4/4，`BUILD SUCCESS`、`exit=0`）
- `gate/spark-jobs.log`（**`[PASS exit=0]`**：`Total number of tests run: 195`、
  `Suites: completed 22, aborted 0`、`succeeded 195, failed 0`、`tests=195 MATCH`、`新写=True`、`JDK8=True`）
- `gate/default-*.log`（analytics-server `880 MATCH`、mall `13 MATCH`、generator `106 MATCH`、
  三棵树 `999`、无 DRIFT、`[FAIL exit=7]` —— 唯一红为已登记环境性
  `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，本工作树 `landing/manifests` 不存在，
  43 份历史清单仅在主工作树，本轮**未修、未复制 manifest、未用开关掩盖**）
- 交叉引用：F-36（`docs/status-history/开发过程事实与决策记录.md`）为同一批证据的文字留痕。
