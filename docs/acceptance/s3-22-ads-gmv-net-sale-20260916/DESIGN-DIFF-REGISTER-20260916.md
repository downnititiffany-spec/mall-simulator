# S3-22 设计差异登记：ADS 大盘同归属口径不变量「GMV ≥ 净销售 ≥ 0」（在产质量门规则 8）

- 编号：S3-22（对应事实记录 F-55）
- 日期（项目内）：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 起点提交：`8763667`（F-54 / S3-21 商品热度榜排序键）
- 判类请求：**A 类（实现/加性）**——不删表/列、不改既有字段类型与业务语义、**不改已发布 Flyway 迁移**
  （只追加 V26）、不动正式 3306 数据、不切 ACTIVE、不改 `contract-specs/**` 契约语义、
  不改 V3.0 总体架构/范围、不删已发布功能、不引入 V3.0 未规划的大型基础组件、不造成长期架构分叉。

## 0. 结论（先说边界）

本项把设计 §12.3「质量 12 项」的**第 8 项**（L506「同归属口径 ADS GMV≥净销售≥0。」）从
「**未实现（无规则码）**」变为**在产阻断规则**：新规则码 `ADS_GMV_NET_SALE_INVARIANT`（BLOCKING），
在 `dqc`（`AdsQualityJob`）里对 `ads_operation_overview__staging` 本次快照 + 本次 dt 分区逐行判定，
并将该码登记进 Java 规则目录（`QualityRuleCatalog`）与规则库种子（**加性**迁移 V26）。

**一句话**：本项只给 ADS 大盘行加了一条**同归属口径不变量**的判定与登记，
**没有**新指标、**没有**新列、**没有**改任何既有列的类型或语义、**没有**改已发布迁移、
**没有**改前端、**没有**连真库、**没有**新依赖。

因此本项**不得**被表述为：

- ❌ 设计 §12.3 的 12 项已全部实现：第 5（退款≤实付）、6（状态转换）、9（UV≤PV）、11（延迟P95/迟到率）
  仍**未实现**，第 10 项仍为**部分实现**（对账形态，见同目录 `QUALITY-RULES-12-COVERAGE-20260916.md`）。
- ❌ 第 9 项「UV ≤ PV」顺带完成：**没有**。设计 §12.3 L512 明文禁止把不同校验合并成一个码，
  本项**刻意**只加第 8 项；同时**已确认** `AdsQualityJob` 既有 `keyPredicates` 对
  `ads_operation_overview` 只覆盖 `pv/uv/dau` 非空（本轮实测，见 F2），故第 9 项仍是**真缺口**。
- ❌ 本规则在真实 Spark/Hive 集群或真实数据上跑过：只跑了 `local[1]` + in-memory catalog 的 spec，
  **未**执行真实 `spark-submit`、**未**读真实 Hive metastore、**未**在生产快照上产出结果。
- ❌ V26 已在任何真库生效：脚本**未执行**（DB 冻结），其自身文件末尾即写明「未执行」。
- ❌ 生产发布链已在真实环境被本规则阻断过：只证明了「违规行会被判不通过」，
  未证明真实生产数据里出现违规时整链的运维行为。

## 1. 判类依据（逐字原文，本项开工前实测）

| 来源 | 行 | 逐字原文 |
|---|---|---|
| 设计 V3.0 | §12.3 **L506** | 「8. 同归属口径ADS GMV≥净销售≥0。」 |
| 设计 V3.0 | §12.3 **L512** | 「每条规则记录作用域、阈值、版本、阶段、实际值、passed、原始/生效严重度。**付款vs订单、订单项公式、DWD/DWS对账三者独立，不能用一个AMOUNT_RECONCILE覆盖。**」 |
| 设计 V3.0 | **L428** | 净销售口径来源：「净销售=支付−成功退款」（本项据此判「净销售 > GMV」为口径破坏） |
| 指导书 V3.0 | §7 阶段 3 **L148** | 「1. 每个指标固定粒度、分子分母、时间窗口、**金额/退款口径**、**空值规则**、版本。」 |
| 指导书 V3.0 | §7 阶段 3 **L149** | 「2. 用 Spark SQL 计算销售/用户/商品/漏斗及**质量专题**，并**逐层对账**。」 |

> **引用更正（append-only，不改已发布记录）**：S3-21 期在 `docs/PROJECT_STATUS.md` 的 backlog 行与
> F-54 事实记录中，曾把「12 条质量规则」的依据写成「指导书 §7-6」。本轮**实测核对**：
> 指导书 V3.0 全文 `质量` 出现的行只有 L30/L56/L68/L69/L149/L156/L163/L171/L262，
> **没有**「12 条质量规则」清单、也**没有**「至少分别实现和验收」的措辞 ⇒ 该引用**是错的**。
> 唯一权威锚点是**设计 §12.3 `质量12项` L497**，逐条在 L499–L510，记录要求在 L512。
> 本项起按正确锚点表述；旧记录不删不改，由本条更正说明覆盖。

## 2. 冻结事实（本项开始前实测，全部可复现）

| 编号 | 事实 | 证据（命令/文件位置，均在 `8763667` 上实测） |
|---|---|---|
| F1 | 起点提交**全仓没有**本规则码 | `git grep -c "ADS_GMV_NET_SALE_INVARIANT" 8763667` ⇒ **0 命中** |
| F2 | ADS 大盘的关键列在产断言**只覆盖** `pv/uv/dau`，**两个金额列无任何在产守卫** | `git show 8763667:spark-jobs/.../AdsQualityJob.scala` 的 `keyPredicates`：`ads_operation_overview -> "pv IS NULL OR uv IS NULL OR dau IS NULL"`；对照 `ads_sale_trend -> "order_count IS NULL OR sale_amount IS NULL OR net_sale_amount IS NULL"`（同一张 `ads_operation_overview` **没有**金额列断言） |
| F3 | 该缺口此前已被**如实登记**为未实现，未冒充完成 | `docs/acceptance/s3-21-products-sort-20260916/QUALITY-RULES-12-COVERAGE-20260916.md:21,42`（第 8 项「未实现（无规则码）」，附 0 命中证据） |
| F4 | 目录规模在起点为 `catalogDefinitions=37` | 门禁内守卫输出（F-54 登记 F7）：`[GUARD-EVIDENCE] catalogDefinitions=37 comparisons=74 blockingCodesOnFailure=33` |
| F5 | `ads_operation_overview` 按 dt **恒为单行**（两列同一次聚合产出 ⇒ 「同归属口径」成立） | `AdsSql.operationOverview(ns, dt, snapshotId)` L62-97：单行子查询 CROSS JOIN，输出 `pv/uv/dau/order_count/sale_amount/net_sale_amount/…` 共 16 列 |
| F6 | 两列的来源口径**不同且可互相约束** | `DwsSql.tradeDay` L113-128：`sale_amount = SUM(CASE WHEN final_paid_flag=1 THEN amount ELSE 0 END)`；`net_sale_amount = <sale_amount 表达式> − COALESCE(SUM(成功退款),0)` ⇒ 数学上必有 `sale_amount ≥ net_sale_amount`，且退款非负时 `net_sale_amount ≥ 0` |
| F7 | 设计**已给定**黄金值，可用于「真链路不误报」取证 | 设计 **L437**「黄金值 GMV 2042.00 / 净销售 1493.00」；本轮实测在真链路 spec 打印中复现（见 §6 GREEN 证据） |
| F8 | `AdsSql.dataQuality` 现有 **4 个分支（3 个 `UNION ALL`）**，均为 Landing/DWD 侧口径 | `git show 8763667:spark-jobs/.../AdsSql.scala` L379-431：`AMOUNT_RECONCILE` / `REQUIRED_FIELD_NULL_RATE` / `EVENT_ID_UNIQUE` / `ENUM_WHITELIST` ⇒ 与「ADS 行内不变量」不同层（见 §4 偏差 D-1） |
| F9 | V19/V25 均为**已发布**迁移，不得再改（门③） | `V19__quality_rule_definition.sql`（F-88）、`V25__quality_rule_ads_funnel_rate_reconcile.sql`（F-43）；本项**追加** V26 |
| F10 | 新规则码的 `checksum` 算法与 Java 侧逐字节一致，可独立复算 | `QualityRuleDefinition#checksum()` L120-133：`SHA-256(ruleCode \x1f version \x1f sourceScope \x1f stage \x1f severity \x1f severityMode \x1f thresholdJson \x1f enabled \x1f effectiveFrom \x1f effectiveTo)`；本项先用 V25 已知值 `3ded2e10…2121a8f` **反向验证**复算一致，再算本行为 `8326bc0d…1309a3` |

## 3. 口径声明（设计只给不变量；落点与空值规则在此声明并登记）

| 维度 | 本项冻结值 | 理由/出处 |
|---|---|---|
| 作用域 | `ads_operation_overview__staging` 的**本次快照 + 本次 dt** 分区 | `dqc` 只检查本次快照（`validate` 要求 `--outputSnapshotId`）；与规则 6/7 同层 |
| 粒度 | **逐行**（该表按 dt 恒单行） | F5；不聚合、不跨 dt、不跨快照 |
| 判据 | `NOT (sale_amount >= net_sale_amount AND net_sale_amount >= 0)` | L506 逐字不等式 |
| `check_count` | 该快照该 dt 分区**行数** | 与规则 3/6/7 同口径（真实 COUNT，不用估算） |
| `error_count` | 违反判据的**行数** | 同上 |
| **空值规则** | 任一金额列为 **NULL ⇒ 不通过** | 三值逻辑下 `NULL >= x` 求值为 NULL，若按「跳过」处理，「金额列整体未计算」（空跑绿）会被静默放行；与本表既有口径一致（`keyPredicates` 对 `ads_sale_trend` 已要求两列非空）。不可证明的不变量不得放行 |
| 原始严重度 / 生效严重度 | `BLOCKING` / 由目录解析为 `BLOCKING`（`FIXED` 模式） | 净销售是发布口径（L428）：一旦「净销售 > GMV」或为负，GMV/净销售/客单价/退款率**一整组**结论都错，而暂存存在性、关键列非空、漏斗对账**可能同时全绿** ⇒ 只有不变量能发现 |
| `threshold_json` | **NULL**（不设阈值） | 不变量逐行可判、无需容差；且 L508「宽松口径异常不一概作为阻断规则」针对的是支付/浏览比与 cohort 解释，与本项无关 |
| 阶段 | `ADS_STAGING`（`layer`），目标表 = `ads_operation_overview__staging` | 与规则 1-3、6、7 同层，运维页字段齐全（L512 要求） |
| 规则版本 | `version = 1`（新码首版） | `fixed(...)` 助手固定 version 1 / `source_scope=*` / `FIXED` / threshold null / enabled true |
| **只判不改** | 不修改/回填/置 0 任何数据；读侧不加启发式纠正 | 与 S3-20 登记「读侧不做启发式纠正」一致；实测见 §6「原样保留」用例 |
| **独立成码** | 第 9 项「UV ≤ PV」**不并入** | L512 明文：不同校验不得用一个码覆盖 |

## 4. 实施清单与偏差登记

### 4.1 实施清单（1 个代码提交 + 1 个 docs 提交）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala` | javadoc 规则清单 + 规则 8；`run()` 在规则 7 之后接线 `checks += AdsQualityJob.gmvNetSaleInvariantCheck(...)`；伴生对象新增判据函数（判据/空值规则/只判不改/不合并/不设阈值全部写在 javadoc） |
| 2 | `analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/QualityRuleCatalog.java` | 新增常量 `RULE_ADS_GMV_NET_SALE_INVARIANT` + `fixed(...)` 目录条目（含 rationale） |
| 3 | `analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/RuleSeverity.java` | `REGISTERED` 加码、`of(String)` 加 case、`rationale(String)` 加 case、类 javadoc 记录本轮新增 |
| 4 | `analytics-server/platform-app/src/main/resources/db/meta/V26__quality_rule_ads_gmv_net_sale_invariant.sql` | **新增加性迁移**：单条 `INSERT IGNORE` 登记一行（含 checksum 与「未执行」声明） |
| 5 | 守卫测试（Java） | `RuleSeverityTest`（新增码进 `BLOCKING_CODES`；码集合断言 35→36，**测试方法数不变**，故 platform-common 计数仍 93）、`QualityRuleVersionMigrationScriptTest`（V26 常量 + 种子集 + 目录 37→38 + 新增 `v26OnlyAppendsSeedRows` ⇒ platform-app 154→155）、`SourceRegistryMigrationMySqlIT`（迁移清单加 V26）、`RuleSeverityPathConsistencyTest`（注释 37→38，自动覆盖新码） |
| 6 | 测试（Scala） | 新增 `AdsGmvNetSaleInvariantSpec`（9 例）；`DwsAdsChainExecSpec` 接线断言 `>= 8` + `contain("ADS_GMV_NET_SALE_INVARIANT")`；`FixtureWriteShapeSpec` 的**冻结写入点清单**登记 `AdsGmvNetSaleInvariantSpec.scala -> 3`（守卫要求新增写入点显式落地，见 §6.3） |
| 7 | `scripts/run-tests.ps1` | 计数基线（platform-app +1、spark +9）与口径注释 |

### 4.2 偏差登记 D-1（与 S3-21 期候选方案的差异，**必须显式说明**）

S3-21 的覆盖核对表在「可实施性排序」里推测第 8 项需动到「`QualityRuleCatalog` + 新加性迁移 +
**`AdsSql.dataQuality` UNION 分支** + Java/Scala 两套漂移守卫」。

**实际实现不落 `AdsSql.dataQuality`，而落 `AdsQualityJob` 的在产阻断检查**，理由（实测支撑）：

1. **层次不同（F8）**：`AdsSql.dataQuality` 的 4 个分支全部是 **Landing/DWD/ODS 侧**口径
   （金额对账、必填率、event_id 唯一、枚举白名单），由 ODS/DWD 作业产出到 `ads_data_quality`；
   而本项判的是 **ADS 结果行内部两列之间的关系**，属于「ADS 写完后、发布前」的门 —— 正是 `dqc` 的职责。
2. **先例一致（F2 同族）**：同一张 ADS 表上的另一条行内/跨层不变式规则（规则 7
   `ADS_DWS_FUNNEL_RATE_RECONCILE`，S3-10）就是落在 `AdsQualityJob` 并把结论放进
   `JobResult.checks`；本项沿用同一落点，避免「同类规则两处实现」的新分叉。
3. **阻断语义正确**：`AdsQualityJob` 的尾部逻辑把「任何 BLOCKING 未通过」变为 `JobResult.failed`
   ⇒ 编排方置阶段失败、**不执行 PUBLISH_METRIC**（§16.3），这正是 L506 需要的阻断面。
4. **登记仍两处齐备**：Java 目录（读侧严重度解析）+ 规则库种子（V26）都已登记，未登记码的
   fail-closed 兜底不会触发。

该偏差**不改变**设计要求（不变量、归属口径、独立成码、记录要素均照做），仅改变**落点**，
且落点与既有同类规则一致 ⇒ 仍属 **A 类（实现/加性）**，不构成架构分叉（门⑪不触）。

## 5. 11 门逐门核对

| 门 | 判定 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 不触 | 零 DDL；V26 只有一条 `INSERT IGNORE`，守卫测试断言全文无 create/alter/drop/truncate/update/delete/replace |
| ② 改已有字段类型或既有业务语义 | 不触 | 不新增/不修改任何列；`ads_operation_overview` 16 列与 `MetricAdsSpec` 白名单不变；规则是**新增**判定，既有列取值路径不变 |
| ③ 改已发布 Flyway migration | 不触 | V19/V25 **字节未改**（`git status` 不含二者）；只**追加** V26（F9） |
| ④ 写/迁移正式 3306 数据 | 不触 | 未连库；V26 未在任何真库执行（脚本自述「未执行」，守卫测试强制该字样存在） |
| ⑤ 切 ACTIVE | 不触 | 无快照状态变更 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 不触 | `contract-specs/**` 零改动；无 HTTP 接口/响应字段变化 |
| ⑦ 改 V3.0 总体架构 | 不触 | 数据流不变：ADS 作业 → 暂存分区 → `dqc` 判定 → `JobResult.checks`（新增一条）→ 原发布链 |
| ⑧ 改正式项目范围 | 不触 | 实现设计**已冻结**的 L506 要求，范围不变 |
| ⑨ 删除已发布功能 | 不触 | 纯加性：既有 7 条规则与其余检查一字未改 |
| ⑩ 引入 V3.0 未规划的大型基础组件 | 不触 | 零新依赖、零新组件（只用既有 `SparkSession`/`QualityCheck`） |
| ⑪ 两种方案造成重大长期架构分叉 | 不触 | §4.2 的落点偏差与既有同类规则同构，非新架构路径；无并行第二实现 |

## 6. 实测证据（TDD：先 RED 后 GREEN）

### 6.1 RED（实现前，三点齐备）

| # | 命令 | 实测结果 |
|---|---|---|
| R1 | `mvn -f analytics-server/pom.xml test -pl platform-common -Dtest=RuleSeverityTest` | `Tests run: 14, Failures: 2`：`everyCodeHasRationale`（新码无 rationale ⇒ 判「未登记」）、`catalogCoversEveryRegisteredCode`（`Expecting empty but was: ["ADS_GMV_NET_SALE_INVARIANT"]`） |
| R2 | 同反应堆跑到 `platform-app`（`-pl platform-app -am -Dtest=… -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=true`） | `QualityRuleVersionMigrationScriptTest: Tests run: 9, Failures: 2, Errors: 2`：`v19SeedMatchesTheJavaCatalogExactly`（目录应为 38）、`migrationVersionsAreAssignedAndUnique`（V26 必须存在）、`v26OnlyAppendsSeedRows` + `seedSeverityModesSatisfyTheirInvariants`（`NoSuchFileException: …/db/meta/V26__quality_rule_ads_gmv_net_sale_invariant.sql`） |
| R3 | `mvn -f spark-jobs/pom.xml test -Dsuites=com.graduation.analytics.AdsGmvNetSaleInvariantSpec` | 测试编译失败：`AdsGmvNetSaleInvariantSpec.scala:147: value gmvNetSaleInvariantCheck is not a member of object com.graduation.analytics.job.AdsQualityJob` |

### 6.2 GREEN（实现后，定向套件）

| # | 套件 | 实测结果 |
|---|---|---|
| G1 | `RuleSeverityTest` | **14/14 通过**（含新码 rationale 非空、目录 36 码全覆盖） |
| G2 | `RuleSeverityPathConsistencyTest` | **4/4 通过**（对目录每条定义比对 `DataQualityGate` 与 `MetricPublishValidator.blocked` 的严重度 ⇒ 新码自动纳入，零漏配） |
| G3 | `QualityRuleVersionMigrationScriptTest` | **9/9 通过**（种子并集 **38 行** ↔ Java 目录逐字段相等 ⇒ 新码 checksum 零漂移；V26 单语句、只 `INSERT IGNORE`、含「未执行」字样） |
| G4 | `AdsGmvNetSaleInvariantSpec` | **9/9 通过**，覆盖：真链路通过（GMV=30.00/净销售=28.00，含一笔成功退款）、净销售>GMV 命中、净销售为负命中、GMV 为负命中、金额列 NULL 判不通过、`GMV==净销售` 通过（「≥」不是「>」）、`0==0` 通过、**快照隔离**（同 dt 别的快照违规不影响本快照）、**只判不改**（违规行判定后原值保留） |
| G5 | `DwsAdsChainExecSpec`（接线取证） | **31/31 通过**，链路打印实测：`[s206][check:dqc] ADS_GMV_NET_SALE_INVARIANT\|BLOCKING\|passed=true\|check=1\|err=0\|1 行均满足 …（sale_amount 最大=2042.00, net_sale_amount 最大=1493.00；本表按 dt 恒为单行）` ⇒ 新码**确实被产出于真实链路**（不只函数存在），且恰与设计 L437 黄金值一致；`dqc` checks 数 ≥ 8 |

### 6.3 本轮自查/门禁发现并修掉的三个自身缺陷（留痕，不掩盖）

1. **夹具自身缺陷（Spark 语义）**：首版 `copyAs` 写成 `INSERT OVERWRITE TABLE t … SELECT … FROM t`，
   Spark 直接拒绝 `cannotOverwriteTableThatIsBeingReadFromError`（新 spec 8 例红，1 例通过）。
2. **块注释被 `*/` 提前闭合**：`AdsQualityJob` 的 javadoc 里写了 `` `docs/acceptance/s3-22-*/` ``，
   其中的 `*/` 结束注释，导致其后 30+ 行中文变成 Scala 代码（`unclosed quoted identifier` 等 30 余条报错）。
   教训：块注释内**不得**出现 `*/` 字面。
3. **被门禁守卫抓出的第二个夹具缺陷（守卫确实有效）**：修掉 (1) 后的第二版 `copyAs` 用
   **动态拼接投影**（`${cols.indices.map(literal).mkString(", ")}`）+ 司机端取行再写字面量。
   定向套件（只跑新 spec）**全绿**，但 **spark 全档**把 `FixtureWriteShapeSpec` 的
   A1/A3/A4 三条守卫跑红：
   - A1：手写写入点**清单**是**有意冻结**的 ⇒ 新 spec 的 3 条写入必须显式落地；
   - A3/A4：该 spec 要求夹具写入**逐列命名**（与 `LocalSchemaInitJob` 唯一所有者同序、分区子句同序），
     动态拼接投影只有 1 个「项」（`1 was not equal to 14`）⇒ 守卫的列名核对形同虚设。

   最终改法：`writeOverview(...)` 按 `ads_operation_overview__staging` 的**唯一所有者列序**
   **逐列命名**写死 14 个非分区列（`snapshot_id`/`dt` 为静态分区，值必须是字符串字面量），
   金额两列由参数给出（`None` ⇒ `CAST(NULL AS DECIMAL(18,2))`）；并在 `FrozenWriteCounts` 里
   登记 `AdsGmvNetSaleInvariantSpec.scala -> 3`（行为 + 订单 + 大盘三条写入）。

   **这条留痕的意义**：定向套件全绿**不等于**档级通过；本项若只跑定向套件就提交，
   会把一个「夹具写入形状守卫被架空」的缺陷带进仓库。**未跑全档不得声称通过**由此再次得证。

### 6.4 档级门禁实测（**定向套件 ≠ 档级通过**，两档都跑）

| 档 | 命令（`scripts/run-tests.ps1`） | 实测结果 |
|---|---|---|
| default | `-Suite default -RunId s322_20260916_def2`（基线更新前先用 `…_def` + `-AllowCountDrift` 量数） | 计数 **MATCH**：analytics-server **944**（`93+350+163+90+93+155`）/ mall **13** / generator **106** ⇒ 三棵树 **1063 = 基线 1063**；唯一红 = 已登记环境性红 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（platform-app `Tests run: 155, Failures: 1`，其余零红）⇒ 打印 `[FAIL exit=7]`，**这是预期**（「计数 MATCH + 唯一红＝该已登记环境性红」，不是 exit=0） |
| spark | `-Suite spark -RunId s322_20260916_spark2` | **`[PASS exit=0]`**：`Total number of tests run = 284`；`Suites: completed 34, aborted 0`；`Tests: succeeded 284, failed 0`；`All tests passed = True`；基线比对 `tests=284 MATCH`；`mvn exit=0`；`新写=True`（mtime ≥ 启动时刻）；`JDK8=True`（`mvn -version` 输出含 `Java version: 1.8`） |

量数轮曾出现的 3 例红（`FixtureWriteShapeSpec` A1/A3/A4）**已定位并修掉**，原因与最终改法见 §6.3 第 3 条；
修后 **spark 全档 284/284 全绿**，且 `dqc` 链路打印中新码 `ADS_GMV_NET_SALE_INVARIANT|BLOCKING|passed=true|check=1|err=0`
（金额 2042.00 / 1493.00 = 设计 L437 黄金值）在**全档运行**中同样出现 —— 该证据不是定向跑出来的。

## 7. 证明边界（不得越界表述）

1. 本项的「已实现」= 存在**可执行判定点 + 已登记规则码 + 在产链路被调用**（G5）；
   **不等于**在真实数据/真实集群上跑过并通过。
2. **未测**（延续 `docs/PROJECT_STATUS.md` 未测清单）：真实 `spark-submit` 与真实 Hive metastore；
   V26 在真库的执行；生产 3306 的 ADS 实际值；真实 HTTP 查询该质量结论；孤立档（3307 无监听）。
3. 本项**未**触碰：`contract-specs/**`、`README.md`、指导书/设计 V3.0、V2.x 历史文档、
   生产 v1 profile、已发布迁移、3306/3307 生产数据、`scripts/run-isolated-tests.ps1`、
   `IngestionManifestRuntimePatrolTest`、`ReferenceMapping`/`map_layer_types()`。
4. 设计 §12.3 的其余缺口（第 5/6/9/11 项与第 10 项剩余部分）**仍未实现**，
   本项不得被引用为「质量 12 项已完成」。
