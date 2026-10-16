# S3-10 设计差异登记（2026-09-16）

> 类型：**A 类（加法 + 修正实现遗漏）** —— 新增一条在产阻断规则 `ADS_DWS_FUNNEL_RATE_RECONCILE`
> ＋ 一条**只插一行**的加性迁移 `db/meta/V25` ＋ 一处注释陈旧修正。
> 关闭的登记项：`docs/PROJECT_STATUS.md:203` 的 **S3-04 R-1**。
> 纪律：未实测不写结论；本文件不冒充"发布链已端到端验证"。

---

## 0. 一句话结论

`ADS_DWS_FUNNEL_RECONCILE`（BLOCKING）只对账 4 个 stage 的 `user_count` **汇总**，S3-04 新增的
两个整体率列（`overall_buy_rate`/`overall_cart_rate`）以及 3 个 stage 的 `conversion_rate`
在**在产质量门里没有任何跨层守卫**（等价断言只存在于 `AdsFunnelRateReconcileSpec` 这一个测试里）。
本轮把它变成**在产规则**：Spark `dqc` 新增规则 7 —— 逐 stage、逐率列比对 `ads_behavior_funnel__staging`
与 `dws_behavior_funnel_day`（`category_id=-1 AND channel='all'` 的同 dt 行），**NULL 与 NULL 判等**，
不一致即 `BLOCKING` 未通过；同批登记 Java 契约（`QualityRuleCatalog` + `RuleSeverity`）与
加性迁移 `db/meta/V25`（**真库未执行**）。

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 指导书 §7 阶段3 **L149** | 「每个指标需要固定粒度、分子分母、时间窗口、金额/退款口径、**空值规则**和版本」 | 「空值规则」在**对账**语境下必须显式：分母 0 ⇒ 率为 `NULL`（S3-04 已冻结），故对账必须**NULL≡NULL**，不得把"双方都没有值"判成不一致 |
| 指导书 §7 阶段3 **L150** | 「用 Spark SQL 计算销售、用户、商品、漏斗及质量专题，**逐层对账**」 | 漏斗的"逐层"不只是行数/人数：率列是**发布出去的结论本身**，必须有一层对账 |
| 指导书 §7 阶段3 **L152** | 「**宽松漏斗不得强制单调**；比例不能直接按天平均」 | 本规则**不判**比率是否单调、不判比率大小是否异常 ⇒ 与 L152 不冲突（见 §2「不判什么」） |
| 设计 §12.3 **L508**（第 10 项） | 「支付/浏览用户比及 cohort 解释：**宽松口径异常不一概作为阻断规则**」 | 规则**只**判"ADS 与 DWS 是否同一个数"，**不引入任何比率阈值**（`threshold_json` 留 NULL）⇒ 异常值照旧放行、不误伤 |
| 设计 §12.3 **L512** | 「每条规则记录**作用域**、阈值、版本、阶段、实际值、passed、原始/生效严重度；付款 vs 订单、订单项公式、DWD/DWS 对账**三者独立**，不能用一个 `AMOUNT_RECONCILE` 覆盖」 | 率的对账**不并进** `ADS_DWS_FUNNEL_RECONCILE`（计数对账）：两者是**不同断言的独立所有者**；本规则作用域写死在 `QualityCheck` 元数据（`ADS_STAGING` / 目标表 / 比对列清单落 `detail`） |
| 设计 §7.3.1 **L524** | 「未知 `ruleCode` 不能照来路 `WARN` 放行；应**拒绝发布并标未登记**」 | 新码**必须三步同批**：Spark 产出 ＋ Java 档位登记 ＋ 库表种子（否则第一次产出即被读侧判未登记、整链翻红） |
| 设计 §11.3 **L441** / §12.3 L508 | 漏斗列的语义与"不强制单调" | 本规则不改任何口径、不新增列、不改 ADS 透传语义 |
| 既有实现 `AdsSql.funnel`（L115-130） | 4 个 `UNION ALL` 分支：`view` ⇒ `conversion_rate = NULL`；`intent/order/pay` ⇒ DWS 对应率；4 行都带 `overall_buy_rate`/`cart_rate AS overall_cart_rate` | 对账的**期望值来源**就是这条透传链（S3-04 口径④「ADS 只透传不重算」）⇒ 期望值取自 DWS 同 dt 全站行，而非另算一套 |

**缺口原文（本轮关闭的登记，逐字）**：`docs/PROJECT_STATUS.md:203`
> `ADS_DWS_FUNNEL_RECONCILE`（BLOCKING）作用域是 **4 个 stage 的 `user_count` 汇总**，
> **不校验** `overall_buy_rate`/`overall_cart_rate` 两个整体率列 ⇒ S3-04 新列在**在产质量门里
> 没有跨层对账守卫**（等价断言只存在于新 spec）

---

## 2. 语义声明（本轮冻结）

| 项 | 冻结取值 | 依据 |
|---|---|---|
| 规则码 | `ADS_DWS_FUNNEL_RATE_RECONCILE`（新码，**不并进**既有计数码） | §12.3 L512 独立性 |
| 档位 | `BLOCKING`（`FIXED` 模式，作用域 `*`，无阈值） | 不一致 ⇒ 发布出去的漏斗率列直接错 |
| 比对对象 | ADS 暂存表 `ads_behavior_funnel__staging`（`WHERE snapshot_id=? AND dt=?`）↔ DWS `dws_behavior_funnel_day WHERE dt=? AND category_id=-1 AND channel='all'` | S3-04 口径④；**只对齐全站行**，不被维度行带偏 |
| 比对粒度 | **每个 stage 一行**（`view`/`intent`/`order`/`pay`）× 率列集合 `{conversion_rate, overall_buy_rate, overall_cart_rate}`；`view` 的 `conversion_rate` 期望为 `NULL`（显式） | 与 `AdsSql.funnel` 的四分支投影逐格对应 |
| 判等 | `BigDecimal.compareTo == 0`；`NULL ≡ NULL` 视为相等；一侧 `NULL` 一侧有值 ⇒ **不一致** | 指导书 L149 空值规则（分母 0 的 `NULL` 是**已冻结的合法值**） |
| 未知 stage | ADS 出现 4 个约定 stage 之外的行 ⇒ 计入不一致（detail 点名 stage） | §7.3.1 L524 同精神：**不认识的来路不放行** |
| 计数口径 | `checkCount` = 实际比对到的 ADS 行数；`errorCount` = 不一致格数 | 与同文件既有规则的元数据形状一致（S3-04 沿用） |
| **不判什么** | ①不判比率是否"合理/单调"（L152、L508 第 10 项）②不判行数/人数（既有规则 6 的职责）③不判 DWS 自身是否正确（那是 DWS 层规则的职责） | §12.3 L512 独立性；避免一个码覆盖多种断言 |
| 失败面 | 走既有 `blockingFailed`（`AdsQualityJob` 既有分支）⇒ 该 dt 不发布 | 既有代码路径，无新分支 |
| 作用范围 | 本轮**1 个 dt / 1 张 ADS 表**（漏斗）—— 与既有规则 6 同 dt 同粒度 | 复核范围与既有口径一致 |

---

## 3. 为什么是 A 类（逐门核对）

| 门 | 是否触发 | 理由 |
|---|---|---|
| ① DROP TABLE/COLUMN | **否** | 无任何 DDL 变更；V25 只 `INSERT IGNORE` 一行 |
| ② 改已有字段类型/既有业务语义 | **否** | 不改列、不改口径、不改既有码的语义与作用域；`ADS_DWS_FUNNEL_RECONCILE`（计数）与既有 12 条规则的**定义一字未改**（gate 实测 37 行种子与 Java 目录逐字段相等） |
| ③ 改已发布 Flyway migration | **否** | `db/meta/V1–V24`、`db/metric/V1–V10` **字节未动**；新码走**新**迁移 `V25`（S3-06/`V23` 已立先例，判 A 类） |
| ④ 写/迁移正式 3306 数据 | **否** | V25 **未在任何正式库执行**（文件内逐字标注）；本轮 **0 次连库** |
| ⑤ 切 ACTIVE | **否** | 未运行发布作业 |
| ⑥ 改 `contract-specs/**` | **否** | 未改（已检索，见 §7） |
| ⑦ 改 V3.0 总体架构 | **否** | 不加组件、不改分层；只用既有规则目录/既有 `QualityCheck` 结构 |
| ⑧ 改正式项目范围 | **否** | 做的是指导书 L150「逐层对账」在设计 §12.3 规则集内的**一格** |
| ⑨ 删除已发布功能 | **否** | 未删任何码/校验/列；既有 12 条规则全部保留 |
| ⑩ 引入未规划大型组件 | **否** | 零新依赖 |
| ⑪ 两种方案造成重大长期架构分叉 | **否** | 见下（两方案对比后取"新码"一路） |

**为什么不扩既有 `ADS_DWS_FUNNEL_RECONCILE` 的作用域（候选方案对比）**

- **方案 A（本轮采用）**：新增独立规则码 `ADS_DWS_FUNNEL_RATE_RECONCILE`（率列对账）。
  优点：① 与既有**计数**对账互为独立断言（§12.3 L512），"率错但计数对"与"计数错"在报告里可区分；
  ② 不动已发布码的定义（面对门②/③的**最小暴露面**）；③ 期望值来源单一（DWS 同 dt 全站行透传）。
- **方案 B（未采用）**：扩 `ADS_DWS_FUNNEL_RECONCILE` 的判据，把率列塞进同一个码。
  代价：① 该码的 `checkCount`/`threshold`/`detail` 语义当场变化（门②邻域："既有业务语义"= 这个码代表什么）；
  ② 一旦率列红，报告里**看不出**是计数错还是率错，违反 L512 的独立性要求；
  ③ 该码的种子行在**已发布**的 `V19` 里 ⇒ 改定义须改已发布种子（门③），或加一条 `UPDATE`（门④）。
  ⇒ 两方案的长期架构后果**不同但不分叉**（都是"多一条规则"），故不触门⑪。

**同样**，本轮**未**顺手给 `ADS_DWS_FUNNEL_RECONCILE` 加率列、**未**改任何既有阈值、
**未**把本规则扩到销售/用户/商品专题（那需要各自的期望值来源与独立登记，属后续开发项）。

**Anti-Entropy Declaration（本轮）**

- 删除类别：`contract-carrying code`（规则契约载体：Java 目录 + 库表种子）＋ 局部 `code-retirement`（陈旧注释）
- 旧路径／旧声明：①「漏斗率列无跨层守卫」（登记缺口 `PROJECT_STATUS:203`）；
  ② `AdsQualityJob` KDoc 规则 2 把 `ADS_STAGING_SNAPSHOT_ISOLATION` 写成 `BLOCKING`（实现为 `ERROR`，`PROJECT_STATUS:185` 类陈旧）；
  ③ `RuleSeverity` 类注释维护了**第二份**易漂移的规则码计数（写 17/15，实测已 18/17）
- 新唯一所有者：行为所有者 = `AdsQualityJob.funnelRateCheck`（`AdsQualityJob` 是 `dqc` 步的唯一所有者）；
  档位所有者 = `QualityRuleCatalog`／`RuleSeverity`；落库载体 = `db/meta/V25`
- 预期保留行为：既有计数对账（规则 6）、ADS 透传语义、分母 0 ⇒ `NULL`、宽松漏斗不强制单调、
  发布失败面（`blockingFailed`）与规则集其余 36 条全不变
- 预期退役行为：「率列无守卫」与上述两处陈旧/重复表述退役（注释改为**指向唯一对账点**，不再本地维护计数）
- External Boundary Touched：**no**（无外部契约；`contract-specs/**` 未动；已发布迁移字节未动）
- Source-of-Truth Data Risk：**none**（零连库；V25 仅 `INSERT IGNORE` 且**未执行**；无 DROP/UPDATE/DELETE）
- Retirement Decision：内部 → `delete-first`（陈旧注释直接改）；契约载体 → **additive-only**（新码新行，
  旧码旧行不删不改）；真库状态变更 → **not attempted**（`confirmation-first` 未启动）
- Non-edits（**刻意不改**）：`ADS_DWS_FUNNEL_RECONCILE`（已发布码，动它触门②/③）、`db/meta/V19`（已发布）、
  `db/metric/**`、任何 DDL、`contract-specs/**`、两份正式文档、`MP_*` 发布侧规则、`MP_METRIC_VALUE_COUNT` 下限（仍 8）
- 残留风险：**S3-10-R-1**（真库未应用 ⇒ 未测）、**S3-10-R-2**（真实 `spark-submit`/Hive 未跑）、
  **S3-10-R-3**（规则码字面量 ↔ 登记集的自动守卫仍缺，见 §6）
  ⇒ 本轮完成度表述为「**在产质量门已有率列跨层守卫（本地/Scala 测试域实测）**」，
  **不是**"漏斗率列在真实集群上已受守护"

---

## 4. 实现面（1 条新规则 + 1 条加性迁移 + 3 处登记 + 1 处注释修正）

1. **`spark-jobs/.../job/AdsQualityJob.scala`**
   - 新增规则 7：`checks += AdsQualityJob.funnelRateCheck(spark, ns, sid, dt)`（接在规则 6 之后）；
   - 伴生对象新方法 `funnelRateCheck(...)`：读 ADS 暂存（`stage, conversion_rate, overall_buy_rate,
     overall_cart_rate`，按 `stage` 排序）与 DWS 全站行（`category_id=-1 AND channel='all'`）；
     `expectedConversion = Map("view" -> None, "intent"/"order"/"pay" -> DWS 对应率)`，
     `expectedBuyRate`/`expectedCartRate` 取 DWS 同 dt 行；
   - 差异以**逐格**形式落 `detail`（`stage/列(ADS=x ≠ DWS=y)`，`NULL` 显式渲染，最多 6 条后 `…`）；
   - 产出 `QualityCheck("ADS_DWS_FUNNEL_RATE_RECONCILE", "ADS_STAGING", <staging 表>, checked, bad, 判据, "BLOCKING", bad.isEmpty, detail)`；
   - KDoc 规则清单 6 → 7 条；**并修正**规则 2 的陈旧档位措辞（`BLOCKING` → `ERROR`，附降级理由）。
2. **`analytics-server/platform-common/.../metric/QualityRuleCatalog.java`**
   - 新常量 `RULE_ADS_DWS_FUNNEL_RATE_RECONCILE`；
   - 新 `fixed(..., STAGE_ADS, RuleSeverity.BLOCKING, ...)` 定义（version 1、`SCOPE_ALL`、`FIXED`、threshold `null`、rationale 引 §12.3 第 10 项与 NULL 判等）。
3. **`analytics-server/platform-common/.../metric/RuleSeverity.java`**
   - `REGISTERED` 的 ADS 组加入新码；`of()` 新分支 `→ BLOCKING`；`rationale()` 新增该码依据
     （逐字写明"只判跨层一致性，不判比率是否异常"）；类注释**删除重复计数**，改为指向唯一对账点（`RuleSeverityTest` 的 `ALL_REGISTERED_CODES` ＋ 目录）。
4. **`analytics-server/platform-app/.../db/meta/V25__quality_rule_ads_funnel_rate_reconcile.sql`（新）**
   - **单条** `INSERT IGNORE INTO quality_rule_definition (...)` 一行；无建表/无改列/无 UPDATE/DELETE；
   - 文件内逐字标注【本迁移在真库上的执行状态：未执行】＋「未在任何正式库执行」；
   - checksum 算法在文件头逐字节写明（含 `\x1f` 连接序、`rationale` 不参与），并给出本行值
     `3ded2e10b8b4217f00aefe3b1955923cddb35cdfe0a7e07be7edbc3672121a8f`。
5. **测试（`+7` Spark、`+1` Java，另有 2 处既有断言同步）**
   - 新 `spark-jobs/src/test/scala/.../AdsFunnelRateReconcileSpec.scala`（7 条，RED 先红，见 §5）；
   - `DwsAdsChainExecSpec`：`dqc` 断言 `>= 6` → `>= 7` **且**新增
     `cap.checks("dqc").map(_.split('|').head) should contain("ADS_DWS_FUNNEL_RATE_RECONCILE")`
     —— **不只看函数存在，而是钉住"链路上确实产出该码"**（本项目发生过"写了不接线"类缺陷）；
   - `QualityRuleVersionMigrationScriptTest`：新增 `v25OnlyAppendsSeedRows`（结构守卫）＋ 目录全集 36 → 37 三处
     （DisplayName / `hasSize` / 提示文案）＋ `SEED_SCRIPTS` 纳入 V25；
   - `RuleSeverityTest`：`BLOCKING_CODES` 加新码 ＋ 计数注释与 `hasSize` 34 → 35；
   - `RuleSeverityPathConsistencyTest`：注释内计数同步（不写死清单，自动遍历目录）；
   - `SourceRegistryMigrationMySqlIT`：`EXPECTED_META_SCRIPTS` 追加 V25（该 IT **仍未测**，仅保持清单与磁盘一致）。
6. **`scripts/run-tests.ps1`**：基线 `analytics-server 908 → 909`、`spark 226 → 233`，并在基线注释区加 S3-10 段（依据/增量归因）。

---

## 5. 反熵守卫与「无回归」证据

- **RED（先红后绿，归因正确）**：新 spec 在**未改实现**时红，Spark 编译期报
  `value funnelRateCheck is not a member of object com.graduation.analytics.job.AdsQualityJob`
  （`.verify/v3-stage3/s3-10-funnel-rate-reconcile/red/spark-targeted-red.log`，`mvnExit=1`）。
  Java 侧同批 RED 证明**没有 V25 就没有契约载体**：`QualityRuleVersionMigrationScriptTest`
  8 条中 1 失败 3 错误，其中 `UncheckedIOException 读取 V25__quality_rule_ads_funnel_rate_reconcile.sql 失败`
  （`.verify/…/red/java-catalog-red.log`，`mvnExit=1`）。
  ⚠️ 如实说明：RED 的第一轮 GREEN 尝试 **1/7 红**，原因是**我自己的探针写错**
  （`query()` 用 `_.toString` 把 `Long` 变成 `"4"`，与 `Seq(4L)` 不等），改为 `queryLong()` 后 7/7 绿 ——
  这是**探针缺陷**，不是实现缺陷，已留痕（同一 red/green 目录）。
- **GREEN（点名套件）**：
  - Spark `AdsFunnelRateReconcileSpec` **7/7**（`green/spark-targeted-green.log`）；
  - Java `RuleSeverityTest 14/14` ＋ `QualityRuleVersionMigrationScriptTest 8/8` ＋
    `RuleSeverityPathConsistencyTest 4/4`（`green/java-catalog-green.log`）。
- **checksum 值的独立验证（不是自证）**：`v19SeedMatchesTheJavaCatalogExactly` 把**库表种子行的
  checksum 字面量**与 `QualityRuleDefinition#checksum()` 的**计算值**逐行比对（`hasSameSizeAs` ＋ 逐字段串），
  本轮该用例在全量档中 **8/8 通过** ⇒ V25 里写死的 64 位值与 Java 侧算法**逐字节一致**（零漂移）。
  该值另由独立脚本按文件头算法重算过，并先用 `ADS_DWS_FUNNEL_RECONCILE`（`970d21d9…`）与
  `MP_EXPORT_CHECKSUM`（`eab2b869…`）两行**已知值反向复现算法**后再算本行。
- **新规则"真的接线"的证据（不靠代码阅读）**：`DwsAdsChainExecSpec` 在**真实链**（odl→…→fna→dqc→pub）
  上断言 `dqc` 的检查里**含**新码，本轮 spark 全量档通过 ⇒ 该断言成立。
- **双档门禁（fresh 真跑，本轮改动全部落盘后执行）**：
  - **spark 档 `[PASS exit=0]`**：`Total number of tests run = 233`、`套件 28`、
    `succeeded 233, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed = True`、
    `新写=True`、`JDK8=True`、`tests=233 MATCH`（`gate/spark-gate.out.log`、`gate/spark-jobs.log`）。
  - **default 档计数全 MATCH、`[FAIL exit=7]`**：analytics-server `exit=1 Tests run: 909 (F=1 E=0 S=1)`
    （模块明细 `90+350+163+67+92+147`）、mall `13`、generator `106`、三棵树 **1028**（基线 1028）、
    `tests=909/13/106 MATCH`；**唯一红**＝已登记环境性
    `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
    （`expected: 43 but was: 0` —— 本 worktree 无 `landing/manifests`，与 `f36ad3a` 前各轮同一枚；
    本轮**未修、未复制 manifest、未用开关掩盖**）（`gate/default-gate.out.log`）。
  - `[GUARD-EVIDENCE] rowsCompared=37 blockingCodes=33` —— 跨模块档位守卫在**全量档**里实测到 37 行、
    33 个阻断码（含新码），与 Java 目录一致。
- **基线对账**：`analytics-server 908 → 909`（增量 **+1** ＝ 新增 `v25OnlyAppendsSeedRows`）、
  `spark 226 → 233`（增量 **+7** ＝ 新 spec 7 条），**逐个与新增用例数相等**，无套件消失。
- **无回归反证（实测结论）**：全量档 909 条中**除环境性那一条外无一红** ⇒ 既有 12 条规则、
  目录/发布/读侧/阈值漂移/path 一致性等守卫**没有**依赖"率列不被对账"这一状态；
  即本轮的严格化**没有**打翻任何既有断言（也未出现"新规则在既有夹具下误报"，
  因为 `blockingFailed` 会让链测试直接红——`DwsAdsChainExecSpec` 通过即为反证）。

---

## 6. 未做与待批注（不阻塞，编号 `S3-10-R-n`）

- **S3-10-R-1｜V25 真库未应用（未测）**：只证到「迁移文本 ↔ Java 目录 ↔ 档位登记」三方一致；
  `INSERT IGNORE` 在真库上的幂等性、以及"未登记码 ⇒ 拒绝发布"在真库种子缺失时的实际表现**均未测**
  （需副本库 + 受限账号，禁止直连正式库）。**不得**表述为"规则已在新库生效"。
- **S3-10-R-2｜真实运行域未测**：spark 档只证明「Scala `local[1]` + `catalogImplementation=in-memory`
  下的测试通过」；**未**跑真实 `spark-submit`、未连 Hive metastore、未在产链路上发布过任何 dt。
- **S3-10-R-3｜"规则码字面量 ↔ 登记集"仍无自动守卫（本轮识别，未实施）**：
  `QualityCheck("X", …)` 的码是否在 `RuleSeverity`/`QualityRuleCatalog` 登记，目前靠人工（本轮即人工发现缺口）。
  候选：跨模块源码扫描测试（先例：`ai-decision/.../AiSqlDriftTest` 已做跨模块源码解析）——
  拟扫 `spark-jobs/src/main/scala` 内 `QualityCheck("…"` 字面量集合 ⊆ `RuleSeverity.REGISTERED`，
  并对 `AdsQualityJob` 的规则条数做下限断言。**本轮不做**（需先定白名单口径与误报处理）。
- **S3-10-R-4｜率列对账只覆盖漏斗**：销售/用户/商品专题的**率列/派生列**是否也需要同类跨层对账，
  未逐一核对；若需要，应按各自期望值来源**逐条独立登记**（不得复用本码）。
- **S3-10-R-5｜`MP_METRIC_VALUE_COUNT` 下限仍为 8**（S3-04/S3-08 已两度登记）：`cart_rate` 等码参与后
  实际映射码更多，下限未收紧（收紧需先确认"不同 dt 合法值数可变"的口径），本轮**未动**。
- **仍挂起的既有登记（与本轮无冲突，未被本轮覆盖）**：`PROJECT_STATUS.md:184`（`ads_category_sale`/`ads_region_sale`
  无生产者）、`:187`（`dws_region_sale_day` 无 `net`）、`:190`（退款归属期跨业务日）、`:193`（DDL 加列"第二所有者"盲区）、
  `:195`（V2 文案漂移）、`:201`（`R7_ADDED_COLUMNS` 覆盖面）、S3-04 R-3/R-4、S3-09-R-1…R-5。

---

## 7. 证据清单与检索记录

- `.verify/v3-stage3/s3-10-funnel-rate-reconcile/red/spark-targeted-red.log`（RED：`funnelRateCheck … not a member`，`mvnExit=1`）
- `.verify/v3-stage3/s3-10-funnel-rate-reconcile/red/java-catalog-red.log`（RED：1 失败 3 错误，`UncheckedIOException 读取 V25…`）
- `.verify/v3-stage3/s3-10-funnel-rate-reconcile/green/spark-targeted-green.log`（GREEN：`succeeded 7, failed 0`）
- `.verify/v3-stage3/s3-10-funnel-rate-reconcile/green/java-catalog-green.log`（GREEN：14/8/4 全绿）
- `.verify/v3-stage3/s3-10-funnel-rate-reconcile/gate/spark-gate.out.log`（spark `[PASS exit=0]`，233/28 套件，`tests=233 MATCH`）
- `.verify/v3-stage3/s3-10-funnel-rate-reconcile/gate/default-gate.out.log`（default 计数全 MATCH；`[FAIL exit=7]`；
  analytics-server `909 (F=1 E=0 S=1)`；唯一红＝环境性 manifest 用例；`[GUARD-EVIDENCE] rowsCompared=37 blockingCodes=33`）
- 同目录 `gate/spark-jobs.log`、`gate/spark-jdk-version.log`、`gate/default-analytics-server.log`、
  `gate/default-mall-simulator.log`、`gate/default-synthetic-data-generator.log`
- 交叉引用：F-43（`docs/status-history/开发过程事实与决策记录.md`）为同一批证据的文字留痕。
- **检索记录（未触碰声明）**：`contract-specs/**` 内**无** `ADS_DWS_FUNNEL`/`overall_cart_rate` 任何引用（已 grep）；
  guidance/design V3.0 两份正式文档**一字未改**；已发布迁移（`db/metric/V1–V10`、`db/meta/V1–V24`）
  **字节未动**（`git status` 只列 V25 为新增）；未改任何既有规则的档位/阈值/作用域；
  未新增第 9 张 ADS 表；未连 3306/3307、未切 ACTIVE、未跑 `spark-submit`。

---

## 8. 判定与停工条件

**判定：A 类（加法：新规则码 + 加性迁移 + 契约登记）⇒ 登记后自主实施，不停工。**
本轮**无**需另择窗口的动作：零连库、零 ACTIVE 切换、零正式数据写入、已发布迁移字节未动。
唯一"重"的一步是**新增库表种子行**，但它是 `INSERT IGNORE` 单行、走新迁移、且**未执行** ——
按 S3-06/`V23` 先例属 A 类；若总控认为"任何库表种子变更都应先批注"，请批注（届时回滚 V25 即可，
其余改动（Spark 规则/Java 登记/测试）可独立保留 —— 但那时新码会因未登记而被读侧拒绝，
故**不建议**只保留一半）。

按「默认自主连续开发」继续滚动下一开发项：下一步候选见 §6 与本文件"未做"清单，
优先 A 类可自主实施者（阶段3 收口自评 → §12.5 逐格 → 审计中判 A 的"未做"行）。
