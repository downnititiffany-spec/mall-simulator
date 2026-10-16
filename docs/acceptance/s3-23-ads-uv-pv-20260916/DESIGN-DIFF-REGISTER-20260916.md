# S3-23 设计差异登记：设计 §12.3 第 9 项「同过滤条件 UV ≤ PV」

- 日期（项目内）：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 开工起点提交：`2540bd2`（F-55 / S3-22 文档提交之后、S3-23 开工前）
- 本轮 F 号：**F-56**
- 判类：**A 类（IMPLEMENTATION / ADDITIVE）**——新增**独立规则码** `ADS_UV_PV_INVARIANT`
  ＋ **加性**迁移 `V27__quality_rule_ads_uv_pv_invariant.sql`；不删不改任何既有字段/语义/迁移
- 11 门命中：**无**

## 0. 证明边界（先写清楚「本轮没有证明什么」）

1. 本轮只证明**写侧暂存门**：规则读 `ads_operation_overview__staging` 的「本次快照 + 本次 dt」分区。
   **未**在真实 `spark-submit` ＋ 真实 Hive metastore 上运行；全部证据来自 `spark-jobs` 的
   ScalaTest（`P2TestSupport.spark` 隔离仓库）。
2. `V27` 迁移**未在真库执行**（DB 冻结：不对 3306 执行任何 DDL/DML）。「库中已生效」不得由本轮推断。
3. 未读生产 3306 的 `analytics_meta.data_quality_result` 验证本码落库值；未做真实 HTTP 查询验证
   「页面/接口能看到本码结论」。
4. 孤立档（3307）无监听进程，**未运行**；本轮不涉及孤立档断言。
5. 本规则的作用域是**ADS 大盘**（`ads_operation_overview`）。同型不变量在
   `dws_product_behavior_day`（DWS，按商品×日）**同样成立但本轮未落**（见 §2 F6）——
   不得因本轮存在而声称「全仓 UV≤PV 已守卫」。

## 1. 设计标尺（逐字，唯一权威）

| 位置 | 逐字原文 |
|---|---|
| 设计 V3.0 **L507** | 「9. 同过滤条件UV≤PV。」 |
| 设计 V3.0 **L506** | 「8. 同归属口径ADS GMV≥净销售≥0。」 |
| 设计 V3.0 **L512** | 「每条规则记录作用域、阈值、版本、阶段、实际值、passed、原始/生效严重度。付款vs订单、订单项公式、DWD/DWS对账三者独立，不能用一个AMOUNT_RECONCILE覆盖。」 |
| 指导书 V3.0 **L148**（阶段 3） | 「对每个指标固定粒度、**分子分母**、时间窗口、金额/退款口径、空值规则和版本。」 |

**引用核对（本轮实测）**：`docs/guidance/项目完整实施指导书 V3.0.md` 全文出现「质量」的行仅
L30/L56/L68/L69/L149/L156/L163/L171/L262，**没有** 12 项清单、**没有**「§7-6」这一节名
⇒ 「12 条质量规则」的唯一权威锚点仍是**设计 §12.3 L497（清单 L499–L510）＋ L512**（该更正在
S3-22 已按 append-only 记录，本轮沿用，不新增第二份更正）。

L512 的直接后果：第 9 项**必须独立成码**，既不与第 8 项合并，也不与任何次数量/对账码合并
（S3-22 只落了第 8 项，第 9 项当时登记为「未实现」并由本轮承接，见
`docs/acceptance/s3-22-ads-gmv-net-sale-20260916/QUALITY-RULES-12-COVERAGE-20260916.md` L56/L71）。

## 2. 开工前实测事实（命中数均为实测值，命令在该提交上执行）

| 编号 | 事实 | 证据 |
|---|---|---|
| F1 | **缺口真实**：全仓不存在任何 `uv ≤ pv` / `pv ≥ uv` 形式的判定 | `git grep -n -E "uv *<=? *pv\|pv *>=? *uv" 2540bd2 -- spark-jobs analytics-server` ⇒ **0 命中** |
| F2 | 大盘 `pv`/`uv` **确在同一个过滤条件下**（⇒ 不变量是构造性的、可逐行判定） | `AdsSql.scala:77-78` 逐字：`COUNT(CASE WHEN behavior_type = 'view' THEN 1 END) AS pv`、`COUNT(DISTINCT CASE WHEN behavior_type = 'view' THEN user_id END) AS uv`，同表 `dwd_user_behavior_detail`、同 `dt` |
| F3 | 同表另有一列**不同过滤条件**，`dau > uv` **合法** ⇒ 本规则不得牵连 | `AdsSql.scala:79` 逐字：`COUNT(DISTINCT user_id) AS dau`（**全事件**去重用户数，无 `behavior_type` 过滤） |
| F4 | 既有「关键列」规则对大盘表**只判非空、不判不等式** | `AdsQualityJob.keyPredicates`（L158）逐字：`AdsSql.staging(ns, "ads_operation_overview") -> "pv IS NULL OR uv IS NULL OR dau IS NULL"`；档位 BLOCKING（目录 `fixed(RULE_ADS_STAGING_KEY_NOT_NULL, STAGE_ADS, RuleSeverity.BLOCKING, "ADS 关键列逐表真实 COUNT，阈值 0")`；链路实测打印 `ADS_STAGING_KEY_NOT_NULL\|BLOCKING\|passed=true`） |
| F5 | ADS 侧含 `pv`+`uv` 列的表**只有大盘**（正式 + 暂存同形） | `LocalSchemaInitJob.scala:131`（`statements` 内正式 DDL）、`:257`（`adsOperationOverviewDdl` 对账重建用，注释要求与 `statements` 一致）、`:185`（`ads_operation_overview__staging`）；`ads_active_trend`（`:145-147`）只有 `dau`/`behavior_count`，无 `uv` |
| F6 | **同型不变量的第二处**（本轮**不落**，登记为候选） | `DwsSql.scala:96-97`：`SUM(CASE WHEN b.behavior_type = 'view' THEN 1 ELSE 0 END) AS pv`、`COUNT(DISTINCT CASE WHEN b.behavior_type = 'view' THEN b.user_id END) AS uv`（`dws_product_behavior_day`，按 商品×dt 分组 ⇒ 逐行同样成立） |
| F7 | 「归属口径」（第 8 项）与「过滤条件」（第 9 项）**是两把尺子** | L506 管**金额列之间**的同归属关系；L507 管**次数与去重用户数之间**的同过滤条件关系。两列集合不相交（金额列 vs `pv`/`uv`）⇒ 无法用一个码覆盖（L512） |
| F8 | 本轮**不触碰**设计 L508 的放宽条款 | L508「宽松口径异常不一概作为阻断规则」限定于第 10 项「支付/浏览用户比及 cohort 解释」（**跨口径比率**）；第 9 项是**同口径不变量**，不属于该项 ⇒ 不援引为放宽依据 |
| F9 | 事实：`ads_operation_overview` 按生产 SQL 恒为**单行/分区**（两列由同一次聚合产出） | `AdsSql.scala:76-82` 的 `b` 子查询是一次无 `GROUP BY` 聚合；S3-22 登记文件 §3 已声明，本轮沿用 |
| F10 | 目录/登记/迁移的**改动前**计数（用于证明本轮是加性、且增量可核对） | `QualityRuleCatalog` 定义 **38** 条；`RuleSeverityTest` 登记码 **36** 个（阻断级 14）；`QualityRuleVersionMigrationScriptTest` 用例 **9** 个；`SEED_SCRIPTS = [V19,V23,V25,V26]` |

## 3. 口径声明（设计只给不变量与限定词；落点与空值规则由本节声明并登记）

| # | 口径 | 内容与理由 |
|---|---|---|
| ① | **作用域** | `ads_operation_overview__staging` 的「`snapshot_id` = 本次快照 AND `dt` = 本次业务日」分区。与第 8 项同层同落点（`AdsQualityJob`，ADS_STAGING 层）。 |
| ② | **判据** | 违反式 `uv > pv` 的行数必须为 0；`check_count` = 被检查行数，`error_count` = 违反行数。**阈值列留空**：不变量没有可调阈值，放宽阈值等于把口径破坏放行。 |
| ③ | **「同过滤条件」是作用域判据，不是修辞** | 本规则的成立前提是 `pv` 与 `uv` 取自**同一个** `CASE WHEN behavior_type = 'view'`。该前提由行为 spec 的**结构守卫**用例静态钉住（从生产 SQL 文本中抽出两行的过滤谓词并断言**逐字相等**且 = `behavior_type = 'view'`，同时断言 `pv` 无 `DISTINCT`、`uv` 为 `COUNT(DISTINCT user_id)`）——即「生产 SQL 若被改成两列不同口径，该用例立刻变红」，而不是只写在注释里。 |
| ④ | **空值规则：本规则不判 NULL，唯一所有者是既有非空规则** | `pv`/`uv` 为 NULL 时 `uv > pv` 求值为 NULL（三值逻辑），本规则不计为违反。NULL 的判定**唯一所有者**是同一次 job 内既有的 `ADS_STAGING_KEY_NOT_NULL`（F4，档位 BLOCKING）⇒ 该行在**发布层面**依旧不放行，「不可证明者不得放行」**未被削弱**，且同一缺陷不被两条规则重复计数/双重阻断。行为 spec 用「`pv` 为 NULL」用例把该唯一所有者钉住：断言 `keyPredicates` 对大盘表的谓词确实含 `pv IS NULL` 与 `uv IS NULL`；谓词若被移除即失败（届时本口径须重新裁决，而不是静默放行）。**这是本轮与第 8 项的口径差异**：第 8 项金额两列当时**没有任何**既有非空断言，故第 8 项自行判 NULL 为不通过；第 9 项两列**已有** BLOCKING 非空断言，故按唯一所有者原则不重复判定。 |
| ⑤ | **只判不改** | 违规行不得被静默修正/裁剪/置 0，规则只产出结论（读侧不加启发式纠正）；行为 spec 断言违规行判定后两列原值保留。 |
| ⑥ | **档位 BLOCKING** | 理由：`pv`/`uv` 是转化率、人均浏览、浏览→支付漏斗的**分子/分母**；`uv > pv` 意味着两列已取自不同过滤条件，则整组浏览类结论不可信，而暂存存在性、关键列非空、金额不变量可能同时全绿。设计 L508 的放宽条款只针对第 10 项的跨口径比率（F8），第 9 项不适用。 |
| ⑦ | **规则版本与登记要素（L512）** | `version = 1`（新码首版）、`source_scope = *`、`severity_mode = FIXED`、`threshold_json = NULL`、`enabled = 1`，由 `V27` 的登记行承载，并带与 Java 目录逐字一致的指纹 `checksum`（§6.2 守卫逐字段对账）。L512 要求的「作用域/阈值/阶段/实际值/passed/原始+生效严重度」在链路 `JobResult.checks` 串中可读（§6.2 链路实测行）。 |

## 4. 实现面（加性；一行一所有者）

| 文件 | 改动 |
|---|---|
| `spark-jobs/.../job/AdsQualityJob.scala` | 新增伴生方法 `uvPvInvariantCheck(spark, ns, sid, dt)`（作用域 = 大盘暂存分区；违反式 `uv > pv`；通过/失败各给实际值，失败预览 ≤6 行）；`run()` 中在规则 8 之后接线；类 javadoc 规则清单补第 9 条 |
| `analytics-server/platform-common/.../metric/QualityRuleCatalog.java` | 新增常量 `RULE_ADS_UV_PV_INVARIANT` ＋ `fixed(..., STAGE_ADS, RuleSeverity.BLOCKING, rationale)` 目录条目（定义 38→39） |
| `.../metric/RuleSeverity.java` | `REGISTERED` 集合、`of()`、`rationale()`、类 javadoc 各 +1 条 |
| `.../metric/RuleSeverityTest.java` | 阻断码清单 +1、集合规模 36→37、说明注释同步（**用例数不变** ⇒ platform-common 仍为 93） |
| `analytics-server/platform-app/src/main/resources/db/meta/V27__quality_rule_ads_uv_pv_invariant.sql` | **新增加性迁移**：单条 `INSERT IGNORE` 一行；标注「本迁移在真库上的执行状态：未执行」 |
| `.../migration/QualityRuleVersionMigrationScriptTest.java` | `V27` 常量、`SEED_SCRIPTS` +V27、号位/存在性断言、新增 `v27OnlyAppendsSeedRows()`、目录与种子并集 38→39（用例 9→10 ⇒ platform-app 155→156） |
| `.../source/SourceRegistryMigrationMySqlIT.java` | 迁移清单 +V27（该 IT 属 D 类真库用例，**本轮未运行**） |
| `spark-jobs/src/test/scala/.../AdsUvPvInvariantSpec.scala` | **新增行为 spec**（9 个用例，见 §6.2） |
| `spark-jobs/src/test/scala/.../FixtureWriteShapeSpec.scala` | `FrozenWriteCounts` 显式登记新 spec 的写入点数（3）——**同一提交内完成** |
| `spark-jobs/src/test/scala/.../DwsAdsChainExecSpec.scala` | 真链路 `dqc` 断言 `>= 8`→`>= 9`，并 `contain("ADS_UV_PV_INVARIANT")` |
| `scripts/run-tests.ps1` | 登记基线随实测更新（见 §6.4）＋ 本轮说明块 |

### 4.1 偏差

**无。** 落点与第 8 项一致（`AdsQualityJob` 的 ADS 暂存门），语义与设计 L507 逐字一致；
`AdsSql.dataQuality` 的 4 个 UNION 分支仍是 Landing/DWD 侧、**未改动**（第 8 项已说明同理由）。
`V26` 与更早迁移**逐字节未改**；`contract-specs/**`、`web/**` 未触碰。

## 5. 11 条 HARD DECISION 门逐门核对

| 门 | 是否触及 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 否 | 全文无 `DROP`；V27 只 `INSERT IGNORE` 一行 |
| ② 改已有字段类型/既有业务语义 | 否 | 未改任何既有列/规则码/口径；新码是**新增**语义 |
| ③ 改已发布 Flyway migration | 否 | `V19`/`V25`/`V26` 逐字节未改，只**新增** `V27`（加性） |
| ④ 写/迁移正式 3306 数据 | 否 | 未连库、未执行 DDL/DML（§0.2） |
| ⑤ 切 ACTIVE | 否 | 未触及快照/指针切换 |
| ⑥ 改 `contract-specs/**` 既有契约语义 | 否 | 未触碰该目录 |
| ⑦ 改 V3.0 总体架构 | 否 | 只加一条质量门规则 |
| ⑧ 改正式项目范围 | 否 | 第 9 项是设计 §12.3 已列要求，本轮是**补实现**，非扩范围 |
| ⑨ 删除已发布功能 | 否 | 只增不删；`FixtureWriteCounts` 为显式新增登记 |
| ⑩ 引入 V3.0 未规划大型基础组件 | 否 | 无新组件/新依赖 |
| ⑪ 两方案造成重大长期架构分叉 | 否 | 落点沿用同族既有作业；无分叉 |

## 6. 证据

### 6.1 RED（先写测试并看到失败）

`AdsUvPvInvariantSpec.scala` 先于实现落地；定向运行（JDK8）失败原因为**行为缺失**：

```
[ERROR] .../AdsUvPvInvariantSpec.scala:176: value uvPvInvariantCheck is not a member of object
        com.graduation.analytics.job.AdsQualityJob
[ERROR] one error found
[INFO] BUILD FAILURE
```

### 6.2 GREEN（定向）

| 项 | 实测 |
|---|---|
| Scala 定向：`-Dsuites=AdsUvPvInvariantSpec,FixtureWriteShapeSpec` | `Tests: succeeded 18, failed 0`；`BUILD SUCCESS` |
| 写入形状守卫（S3-15）同轮取证 | `AdsUvPvInvariantSpec.scala:93 → dwd_order_detail 投影=22`、`:121 → dwd_user_behavior_detail 投影=15`、`:163 → ads_operation_overview__staging 投影=14`（逐列命名、与唯一所有者同序） |
| Java 定向：`RuleSeverityTest` | `Tests run: 14, Failures: 0`（**用例数不变**） |
| Java 定向：`QualityRuleVersionMigrationScriptTest` | `Tests run: 10, Failures: 0`（9→10，新增 `v27OnlyAppendsSeedRows`） |
| Java 定向：`RuleSeverityPathConsistencyTest`（守卫） | `[GUARD-EVIDENCE] catalogDefinitions=39 comparisons=78 blockingCodesOnFailure=35`（38/76/34 → +1/+2/+1）；`ADS_UV_PV_INVARIANT` 出现在阻断码清单内 |
| 真链路 `dqc` 实测打印（`DwsAdsChainExecSpec`，S1 + 重放/S2 各一行） | `[s206][check:dqc] ADS_UV_PV_INVARIANT\|BLOCKING\|passed=true\|check=1\|err=0\|单行（uv ≤ pv）：pv 实际值=7, uv 实际值=3…NULL 归 ADS_STAGING_KEY_NOT_NULL 判定，本规则不重复判定` |

行为 spec 的 9 个用例（逐条对应 §3 口径）：

1. 真跑生产链路（`dwd_user_behavior_detail` 三次浏览、两用户 ⇒ `pv=3`/`uv=2`）⇒ 通过，
   **且先断言 `pv`/`uv` 确有值且不相等**（防「0 ≤ 0」假绿）；
2. `uv > pv`（2/5）⇒ 命中，`detail` 给出两列实际值；
3. 边界 `uv == pv`（2/2）⇒ 通过（「≤」不是「<」）；
4. 边界 `0 == 0`（无浏览）⇒ 通过；
5. 快照隔离：本快照合法而行、同 `dt` 另一快照违规 ⇒ 本快照通过、违规快照自身命中；
6. 只判不改：违规行判定后两列原值保留；
7. `pv` 为 NULL ⇒ 本规则不重复判定（`passed=true`）**且** `keyPredicates` 对大盘表确实含
   `pv IS NULL`/`uv IS NULL`（唯一所有者耦合守卫）；
8. `dau > uv`（4 > 2，`uv ≤ pv` 成立）⇒ 通过（**作用域边界**：不同过滤条件的列不牵连）；
9. 结构守卫：从 `AdsSql.operationOverview` 文本抽出 `pv`/`uv` 两行的 `CASE WHEN … THEN` 谓词，
   断言**逐字相等**且 = `behavior_type = 'view'`，且 `pv` 无 `DISTINCT`、`uv` 为 `COUNT(DISTINCT user_id)`。

### 6.3 自查缺陷（本轮被守卫/自查抓出并修正，留痕）

| # | 现象 | 处置 |
|---|---|---|
| 1 | 夹具若只写大盘暂存行、不写 `dwd_order_detail`/DWS，`operationOverview` 的 `t`/`r`/`u` 三个非聚合子查询为空 ⇒ 大盘**一行都不产出**，规则会在空分区上「通过」（假绿） | 真链路用例改为**完整跑链路**（订单 → `DwsSql.tradeDay` → `DwsSql.userTradePeriod` → `AdsSql.operationOverview`），并在断言前钉住 `pv=3`/`uv=2` |
| 2 | 若沿用「复制基线行再改两列」的夹具写法，会触发 Spark 的 `cannotOverwriteTableThatIsBeingReadFromError`（自读覆盖写） | 目标行按所有者列序**逐列命名**独立写出（14 列），同时把动态拼投影换成静态命名投影，保住 `FixtureWriteShapeSpec` 的判别力（`FrozenWriteCounts` 同提交登记 3） |
| 3 | 若把 `dau` 一并纳入判据（如 `uv > pv OR uv > dau`），会把**不同过滤条件**的列拉进来，`dau > uv` 的合法数据将被误判为口径破坏 | 判据只写 `uv > pv`；并新增用例 8 把该边界钉住（`dau > uv` 必须通过） |

### 6.4 档级门禁（两档，独立 RunId；数字为实测）

| 档 | RunId | 结果 |
|---|---|---|
| default（量数轮，`-AllowCountDrift`） | `s323_20260916_def` | `tests=945 DRIFT(基线 944)`／`13 MATCH`／`106 MATCH`；三棵树 1064（基线 1063）；明细 `93+350+163+90+93+**156**` ⇒ 增量**只**在 platform-app（155→156），platform-common 仍 93 |
| default（收口轮） | `s323_20260916_def2` | `tests=945 **MATCH**`／`13 MATCH`／`106 MATCH`；**三棵树 1064 = 基线 1064，无 DRIFT**；日志 `default-analytics-server.log:1209` = `Tests run: 156, Failures: 1`；**唯一红 = 已登记环境性红** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（L1167-1168，与 S3-16～S3-22 同一登记项，未修、未掩盖）⇒ 结论是「**计数 MATCH + 唯一红＝该已登记环境性红**」，脚本 `[FAIL exit=7]` 为**预期**，**不得**写成 `[PASS exit=0]` |
| spark（量数轮，`-AllowCountDrift`） | `s323_20260916_spark` | `Total number of tests run = 293`（基线 284，+9 = 新增 spec 9 例）、`Tests: succeeded 293, failed 0`、`All tests passed = True`、`JDK8=True`、`新写=True` |
| spark（收口轮） | `s323_20260916_spark2` | **`[PASS exit=0]`**：`Total number of tests run = 293`、`Suites: completed 35, aborted 0`、`Tests: succeeded 293, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed = True`、基线比对 `tests=293 MATCH`、`mvn exit=0`、`新写=True`、`JDK8=True` |

基线同步（`scripts/run-tests.ps1`）：`$BaselineDefault['analytics-server']` 944→**945**、
`$BaselineSpark` 284→**293**（`mall` 13、`generator` 106 未变；三棵树 1063→**1064**）。
量数轮先量真值、再改基线、再跑收口轮验证 MATCH —— 顺序与 S3-22 一致。
证据目录 `.verify/s323_20260916_def2`、`.verify/s323_20260916_spark2`（gitignored）。

**证明边界（门禁层面）**：spark 档只证明 **Scala `local[*]` + in-memory catalog** 下测试通过，
`mvn` 由脚本直调（非真实 `spark-submit`）；**不得**表述为生产 Spark/Hive 集群已通过。

## 7. 未测边界（不得越界表述）

1. 真实 `spark-submit` + 真实 Hive metastore；真实 Parquet landing。
2. `V27` 在真库（3306）的执行与 `analytics_meta.data_quality_result` 的实际落库行。
3. 真实 HTTP/页面呈现（本码结论是否出现在质量页/接口响应）。
4. 孤立档（3307 无监听）。
5. `dws_product_behavior_day` 的同型不变量（F6）**本轮未落** ⇒ 不得声称「DWS 侧 UV≤PV 已守卫」。
6. 设计 §12.3 第 5/6/11 项与第 10 项剩余部分仍未实现 ⇒ 不得声称「质量 12 项完成」。
