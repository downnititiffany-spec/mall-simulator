# S3-25 设计差异登记：设计 §12.3 第 9 项「同过滤条件 UV ≤ PV」的 **DWS 同型站点**（独立规则码）

- 任务编号：**S3-25**（V3.0 持续执行模式，滚动选出的第一个尚未满足验收条件的开发项）
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `feature/v3-development`
- 起点提交：`bbe7d26`（`docs(status): F-57 S3-24 记录…`）
- 代码提交：`f55bd61`（11 个文件，含新迁移 `V28` 与新规格 `DwsUvPvInvariantSpec`）
- 日期（项目内）：**2026-09-16**
- 类别判定：**A 类（实现/加性）** —— 新增规则码 + 目录/严重度登记 + **新增**加性迁移 + Spark 判据
  + 用例；未触任何 HARD DECISION 门（逐门表见 §5）

## 0. 证明边界（先说不能证明什么）

1. 本轮**没有**连生产 3306 库任何读写；**V28 未在真库执行** ⇒ 不得称「新规则已在正式库登记生效」。
2. 本轮**没有**真实 Hive/metastore、没有 `spark-submit`：Spark 结论**只**证明
   「Scala `local[1]` ＋ `catalogImplementation=in-memory` 下通过」（`P2TestSupport` 自陈边界）。
3. `dws_product_behavior_day` 的**真实数据表现**（是否空分区、NULL 占比、商品行数分布）**未测**。
4. 本轮**未改** `web/**`、未改 `contract-specs/**`、未改设计/指导书 V3.0、未改任何**已发布**迁移
   （V19/V25/V26/V27 字节未动）。
5. 覆盖结论**未变**：12 项规则仍是「已实现 8 / 部分 1（第 10 项）/ 未实现 3（5/6/11）」——
   第 9 项此前已按 **ADS 站点**计入已实现，本轮补的是**同型第二站点**，**不得**称「12 项完成」，
   **也不得**称「全仓 UV ≤ PV 已守卫」（只核对了 ADS 与 DWS 两处）。

## 1. 逐字锚点（权威三件套）

| 来源 | 位置 | 逐字要点 |
|---|---|---|
| 设计 V3.0 | §12.3 **L507** | 「9. 同过滤条件UV≤PV。」 |
| 设计 V3.0 | §12.3 **L512** | 「每条规则记录作用域、阈值、版本、阶段、实际值、passed、原始/生效严重度。付款 vs 订单、订单项公式、DWD/DWS 对账三者独立，**不能用一个 AMOUNT_RECONCILE 覆盖**。」 |
| 设计 V3.0 | §12.3 **L508** | 「宽松口径异常不一概作为阻断规则」（＝第 10 项**跨口径比率**；本项是同口径不变量，**不援引**） |
| 指导书 V3.0 | 阶段3 **L148** | 「对每个指标固定粒度、**分子分母**、时间窗口、金额/退款口径、**空值规则**和版本」 |
| 已登记缺口 | F-56 **遗留①** / S3-24 backlog 单元格 | 「`dws_product_behavior_day` 的 uv≤pv **同型站点**（`DwsSql.scala:96-97`）判 A 类」＝本项即该候选 |

## 2. 开工前实测事实（F1–F9，改前取证）

- **F1（缺口真实）**：全仓 `git grep -n -E "uv *<=? *pv|pv *>=? *uv" -- spark-jobs analytics-server`
  **只命中 `AdsUvPvInvariantSpec.scala:104` 的注释** ⇒ 该表的 `uv ≤ pv` 在**产质量门里 0 个守卫**。
- **F2（构造性不变量）**：`DwsSql.scala:96` `pv = SUM(CASE WHEN b.behavior_type = 'view' THEN 1 ELSE 0 END)`、
  `:97` `uv = COUNT(DISTINCT CASE WHEN b.behavior_type = 'view' THEN b.user_id END)` ——
  两列出自**同一个**过滤条件、同表 `dwd_user_behavior_detail`（别名 b）、同 `b.dt = '$dt'`（`:108`）。
  粒度 = `product_id × category_id`（`:109` `GROUP BY b.product_id, b.category_id, COALESCE(s.buy,0)`）。
- **F3（下游影响真实）**：该表是 `ads_hot_product`（`AdsSql:185` 读 `pv/fav/cart/buy` 算
  `1.0*LOG1P(pv)+…`）与 `ads_product_conversion`（`AdsSql:202` `b.uv AS pv_users` **直连**）的
  唯一直接来源 ⇒ 破坏后商品热度/商品转化结论整体错，而「暂存存在性、关键列非空、金额不变量」
  可能**同时全绿**。
- **F4（落点唯一）**：在产质量作业只有 `AdsQualityJob`（无 DWS 质量作业），且它**已经**读 DWS
  （`dws_behavior_funnel_day`）⇒ 落点＝新增伴生方法并接线，**不新建作业、不引入组件**。
- **F5（NULL 无直接所有者）**：`AdsQualityJob.keyPredicates` 的键集**只覆盖 8 张 ADS `__staging` 表**，
  对 `dws_product_behavior_day` **无任何谓词**；ADS 侧对 `pv/uv` 的非空守卫在
  `ads_operation_overview__staging`（`pv IS NULL OR uv IS NULL OR dau IS NULL`）与派生表
  `ads_product_conversion__staging`（`pv_users IS NULL`）上 —— 与本表**不是同一站点**。
- **F6（无 snapshot 维度）**：`LocalSchemaInitJob.scala:102` 本表 =
  `(product_id, category_id, pv, uv, fav, cart, buy) PARTITIONED BY (dt)` ⇒ 规则**不接收 `sid`**。
- **F7（空分区另有所有者）**：`PartitionEvidence.collect` 只**记录** `rowCount`，不因 0 行判失败；
  而 `ADS_STAGING_PRESENT`（BLOCKING）要求 8 张暂存表本次快照分区**行数 > 0**，本表空 ⇒ 商品转化
  暂存空 ⇒ 既有阻断已生效。
- **F8（登记链必须先落库）**：§7.3.1 line 524 规定未登记规则码一律「停止发布并报未登记规则」；
  目录（Java）与 `quality_rule_definition`（DB 种子）**必须一致**，故新码必须**同时**落
  `QualityRuleCatalog` 与**新增**迁移（已发布迁移不得改 ⇒ 只能追加 V28）。
- **F9（checksum 算法可复现）**：`QualityRuleDefinition#checksum()` =
  `SHA-256(ruleCode \x1f version \x1f sourceScope \x1f stage \x1f severity \x1f severityMode \x1f
  thresholdJson(缺省空串) \x1f enabled(1/0) \x1f effectiveFrom \x1f effectiveTo)`（小写 hex；
  `*` 为全作用域）。先用 V27 已知值 `2140ab3e…d296` 反向验证算法复现一致，再算本行 ⇒
  `DWS_UV_PV_INVARIANT` 行 checksum ＝ `6c61c21dcb2e5fe893e7965d28cf1f9f266af996a84f5a865f1341e070e41b09`；
  该值不是「自己算自己信」——由 `QualityRuleVersionMigrationScriptTest` 的 **SQL↔Java 零漂移对账**
  实测确认（§6.2）。

## 3. 口径声明（本轮冻结的判据）

1. **作用域** ＝ `dws_product_behavior_day` 的**本次 `dt` 分区**（该表无 snapshot 维度 ⇒ 规则不接收
   `sid`；与既有 `ADS_DWS_FUNNEL_RECONCILE` 同 dt 读 DWS 的作用域同型）。跨快照隔离由 ADS 侧规则
   负责，本规则**不冒充**。
2. **判据** ＝ `pv IS NULL OR uv IS NULL OR uv > pv` 的行数必须为 0；
   `check_count` ＝ 该分区行数，`error_count` ＝ 违反行数；**无阈值**（`threshold_json` 为 NULL：
   不变量没有可放宽的阈值，放宽即把口径破坏放行）。
3. **空值规则** ＝ `pv`/`uv` 任一为 NULL ⇒ **不通过**。理由：ADS 站点上这两列 NULL 有**直接**
   唯一所有者（`keyPredicates` 对大盘表的谓词，S3-23 故不重复判定）；**本表没有**这样的所有者
   （F5）⇒ 按**唯一所有者原则**由本码承担；三值逻辑下 `uv > pv` 在 NULL 时求值为 NULL，
   不显式判 NULL 则「两列整体未计算」会被静默放行。
4. **空分区不判违反** ＝ `checked = 0 ⇒ passed = true`：存在性/非空另有 BLOCKING 所有者
   （F7），本规则只判不变量，避免同一缺陷双重阻断。
5. **只判不改**：违规行不得被静默修正/裁剪/置 0；**档位 BLOCKING**（口径破坏会让发布出去的商品
   浏览结论整体错）。
6. **不合并**：与 `ADS_UV_PV_INVARIANT`（S3-23）同型但**不同码** —— 表、粒度（逐行 vs 一次聚合成
   一行）、分区维度（无 snapshot vs 按 snapshot 隔离）都不同，一处通过不能证明另一处通过
   （L512）。也**不得**并入第 8 项金额不变量。
7. **不改数据面**：不建表/不改列/不删行/不改任何既有行的档位与阈值；V28 只**插入**一行。

## 4. 实现面（11 个文件）

| 文件 | 改动 | 说明 |
|---|---|---|
| `platform-common/.../metric/QualityRuleCatalog.java` | ＋常量 `RULE_DWS_UV_PV_INVARIANT`、＋`fixed(…, STAGE_DWS, BLOCKING, …)` | 目录 39 → **40** |
| `platform-common/.../metric/RuleSeverity.java` | 类注释 + `REGISTERED` + `of()`→BLOCKING + `rationale()` 中文依据 | 登记码 37 → **38** |
| `platform-app/.../db/meta/V28__quality_rule_dws_uv_pv_invariant.sql` | **新增**：单条 `INSERT IGNORE` | 头部写明依据/为何另立码/为何 BLOCKING/为何自判 NULL/checksum 算法/为何不改旧迁移/**未在真库执行** |
| `spark-jobs/.../job/AdsQualityJob.scala` | ＋`dwsUvPvInvariantCheck(spark, ns, dt)`、＋调用点、类注释补第 10 条 | 不接 `sid`；只判不改 |
| `spark-jobs/src/test/scala/.../DwsUvPvInvariantSpec.scala` | **新增** 10 条用例 | 见 §6.1 |
| `spark-jobs/src/test/scala/.../FixtureWriteShapeSpec.scala` | 写入点清单 +1 文件/3 条 | 静态写入形状守卫 |
| `spark-jobs/src/test/scala/.../DwsAdsChainExecSpec.scala` | `dqc` checks `>= 9`→`>= 10`、＋`contain("DWS_UV_PV_INVARIANT")` | 防「只写函数不接线」 |
| `platform-common/.../metric/RuleSeverityTest.java` | BLOCKING 清单 +1、`all` 37→38 | 严重度目录一致性 |
| `platform-app/.../migration/QualityRuleVersionMigrationScriptTest.java` | ＋`V28` 常量/`SEED_SCRIPTS`/存在断言/`v28OnlyAppendsSeedRows`、全集 39→40 | SQL↔Java 零漂移对账 |
| `platform-app/.../source/SourceRegistryMigrationMySqlIT.java` | 迁移清单 +`V28` | 未执行（无监听库），仅清单一致 |
| `scripts/run-tests.ps1` | `$BaselineSpark` 293→**303**、`analytics-server` 948→**949**、注释块 | 量数轮真值＋收口轮结论 |

## 5. 11 门 HARD DECISION 逐门判定（全部「否」）

| 门 | 判定 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | 否 | V28 只有一条 `INSERT IGNORE`，无任何 DDL |
| ② 改已有字段类型/业务语义 | 否 | 未动任何列定义与既有取值语义 |
| ③ 改已发布 Flyway migration | 否 | V28 是**新文件**；V19/V25/V26/V27 **字节未改**（`git diff` 实测） |
| ④ 写/迁移正式 3306 数据 | 否 | 零连库；迁移**未执行** |
| ⑤ 切 ACTIVE | 否 | 不涉及发布/切换 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | 本轮**未改**该目录（也未改 `docs/contracts/**`） |
| ⑦ 改 V3.0 总体架构 | 否 | 不新建作业/组件；只在既有 `dqc` 加一条规则，复用既有 `QualityCheck` 通道 |
| ⑧ 改正式项目范围 | 否 | 实现的是设计 §12.3 第 9 项**已写明**的内容，未扩范围 |
| ⑨ 删除已发布功能 | 否 | 无删除；旧码与旧规则一条未动 |
| ⑩ 引入 V3.0 未规划大型基础组件 | 否 | 无新依赖 |
| ⑪ 两种方案重大长期架构分叉 | 否 | 「另立一码 vs 合并进 ADS 码」的取舍由**设计 L512 逐字**给出方向（不得合并），非自由分叉 |

## 6. 实测证据（未实测不写结论）

### 6.1 定向套件（RED → GREEN 与行为判别力）

- **夹具自身缺陷的 RED**：首轮 `s325_dev1` 定向跑三套件 ⇒ `Tests: succeeded 47, failed 3`，
  三条红**全部**在 `FixtureWriteShapeSpec`（A2/A3/A9）—— 原因是我把写入目标写成局部变量
  `$productBehavior`（该守卫只静态解析 `AdsSql.staging|formal(ns, "表")` 形态）。**修法**：写入语句
  直接写字面目标 `${ns.dws}.dws_product_behavior_day`（可静态解析，与所有者列序/分区对齐受检），
  该 val 仅用于读取。**未**用「登记例外」掩盖（例外只配给真正的运行期目标）。
- **GREEN**：`s325_dev2` ⇒ `DwsUvPvInvariantSpec`(10) + `FixtureWriteShapeSpec`(9) ＝
  `Tests: succeeded 19, failed 0`；`DwsAdsChainExecSpec` 在 `s325_dev1` 已全绿（含新码断言）。
- **Java 定向**：`mvn -o -pl platform-common,platform-app -am -Dtest=RuleSeverityTest,QualityRuleVersionMigrationScriptTest`
  ⇒ `RuleSeverityTest` 14/14、`QualityRuleVersionMigrationScriptTest` **11/11**（原 10 ＋ 新 `v28` 用例）。
  ⇒ checksum 的「SQL 种子 == Java 目录」**零漂移**是**实测**结论（不是脚本自算自证）。

### 6.2 档级门禁（fresh 真跑，四轮，RunId 可复核）

| 轮 | RunId | 结果 |
|---|---|---|
| spark 量数 | `s325_20260916_spark1` | `Total number of tests run = 303`、`succeeded 303, failed 0`、36 套件；`tests=303 DRIFT(基线 293)` 被 `-AllowCountDrift` 放行（`[FAIL exit=7]` 属量数轮预期） |
| spark 收口 | `s325_20260916_spark2` | `tests=303 MATCH`、`All tests passed`、`spark PASS`、**`[PASS exit=0]`** |
| default 量数 | `s325_20260916_def1` | analytics-server `Tests run: 949 (F=1 E=0 S=1)`、明细 `93+350+163+93+93+157`、mall 13、generator 106、三棵树 **1068**（DRIFT 1067） |
| default 收口 | `s325_20260916_def2` | `949 MATCH`／`13 MATCH`／`106 MATCH`、三棵树 **1068 ＝ 基线 1068**、**无 DRIFT** |

- **唯一红（default 档）** ＝ **已登记环境性红** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
  （`default-analytics-server.log:1158-1159`；platform-app 汇总 `:1200 Tests run: 157, Failures: 1`）
  ⇒ **未修、未用开关掩盖**；default 档结论 = 「**计数 MATCH ＋ 唯一红＝该已登记环境性红**」，
  脚本 `[FAIL exit=7]` 为**预期**，**不得**写成 `[PASS exit=0]`。
- **spark 档证明边界**：只证明 `local[1]` ＋ in-memory catalog 下通过，**不得**表述为
  「生产 Hive/Spark 集群已通过」。
- 证据目录：`.verify/s325_spark1`、`.verify/s325_spark2`、`.verify/s325_def1`、`.verify/s325_def2`
  （gitignored，重启后可复跑）。

### 6.3 链路证据（非仅单测）

`s206` 链实跑输出中出现：
`[s206][check:dqc] DWS_UV_PV_INVARIANT|BLOCKING|passed=true|check=4|err=0|4 行均满足 uv ≤ pv
（pv 最大=3, uv 最大=3；作用域＝dt=20260901 分区，该表无 snapshot 维度）`
⇒ 新规则**确实接线**并经生产链路产出（`dqc` 由 9 条变 **10 条**），与 `ADS_UV_PV_INVARIANT` 并列独立。

### 6.4 未采纳的证据（如实登记）

default 量数轮之前，我**未**先跑定向套件就试图量数（该轮被夹具红中断，`s325_dev1` 即为该轮定向
排障），故 `s325_def1` 的 949 读数**不含**任何被掩盖的红；四轮 RunId 全部保留，可复核。

## 7. 未测边界与未做（不得越界表述）

1. **V28 未在真库执行**（DB 冻结：3306 只读、3307 无监听）⇒ 不得称「已在正式库登记生效」。
2. `dws_product_behavior_day` 的**真实数据/空分区/NULL 占比未测**（无真实 Hive/metastore）。
3. 12 项规则**未完成**（已实现 8 / 部分 1 / 未实现 3），第 9 项的**其它可能站点未逐一核对**；
   不得称「12 项完成」，也不得称「全仓 UV ≤ PV 已守卫」。
4. 设计 §9.3 **L335** 的另一半「**实时结果**待接齐」（发布链实时回写）**不变**。
5. 指导书 **L158 限流**、**L157 归档读取授权**、**L159 发布/查询账号分离**、§12.3 **L506** 不变式
   均**不变**，仍登记在 `docs/PROJECT_STATUS.md` backlog。
6. 阶段5 页面**未**新增展示（`web/**` 本轮未改）；F-57 遗留的阶段5「页面未展示 `quality.ruleVersions`」
   **不变**。
7. 本轮**未**把新规则并入 AI 证据包（设计未要求）。
8. `SourceRegistryMigrationMySqlIT`／`QualityRuleVersionMigrationScriptTest` 中涉及真库的用例
   **未执行**（无监听库），只保证清单与静态对账一致。

## 8. 结论

- 本项为 **A 类加性实现**，11 门逐门**否**；实测：定向 GREEN（19/19、Java 14+11）→
  **spark 档 `[PASS exit=0]` 303/303** → default 档计数 MATCH（949/13/106，三棵树 1068 ＝ 基线）
  且唯一红为已登记环境性红。
- 交付物：DWS 站点 `UV ≤ PV` 在产 BLOCKING 守卫（`DWS_UV_PV_INVARIANT`，独立成码）＋ 加性迁移 `V28`
  ＋ 10 条行为/结构/口径守卫用例 ＋ 三条反熵守卫同步（写入形状、链路产出、SQL↔Java 零漂移）。
- 提交：代码/迁移/脚本 1 个提交（`f55bd61`）＋ 文档（本文件、`status-history` F-58、
  `docs/PROJECT_STATUS.md`）1 个提交，均推送到 `origin/feature/v3-development`。
