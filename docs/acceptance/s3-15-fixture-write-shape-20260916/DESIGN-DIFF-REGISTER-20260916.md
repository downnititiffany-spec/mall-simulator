# S3-15 设计差异登记（2026-09-16）

- 分支：`feature/v3-development`（工作树 `D:\Develop_code\GraduationProject-wt\v3-dev`）
- 任务编号：**S3-15**；事实记录号 **F-48**
- 本文件性质：**A 类（实现遗漏 / 加性守卫）** 的设计差异登记 —— 只做**加法**：新增一个**纯测试侧**静态守卫，
  **零生产改动**（零 DDL、零参考副本、零 Flyway、零 Java、零 `contract-specs`、零连库）。

## 0. 一句话结论

`PROJECT_STATUS` 中「DDL 加列类变更存在**第二所有者**盲区」行（**本轮插入记录前位于 L224**）
在本轮关闭**仍开放的 (a) 一半**：
S3-11/S3-13/S3-14 三方守卫只盯 **main 侧生产写入投影**，而 S3-03 实测的 5 条红恰恰来自**测试夹具自己手写**的
`INSERT OVERWRITE … dws_user_trade_period`（DWS 加列后**按位置**写入 ⇒ 静默串列）。本轮新增
`FixtureWriteShapeSpec`（9 条，无需 Spark），把 **test 树里 22 处手写写入 / 12 个文件**全部纳入
「唯一所有者 `LocalSchemaInitJob` 表形」对账，并**冻结清单**（新增或删除写入点都必须显式落地）。

## 1. 设计原文（逐字引用，标尺）

- 设计 §9.2 **L322**：「历史DDL声明ODS4/DWD3/DIM5/DWS7/ADS10，**不代表29张都有正确数据**…
  **目录schema与数据库真实表形状必须对照，不靠文件名推理**」。
- 指导书 §7 阶段3 **L149**：「Spark SQL 计算销售、用户、商品、漏斗及质量专题，**逐层对账**」；
  **L148** 固定「粒度/口径/时间窗口/空值规则/版本」；**L151** 导出 ADS 制品核 schema/行数/checksum。
- `PROJECT_STATUS` 的「DDL 加列类变更存在**第二所有者**盲区」行（**本任务直接对标项**，**本轮插入记录前位于 L224**）
  原文要点：① 两个既有套件**自己手写**
  `INSERT OVERWRITE … dws_user_trade_period`（6 列），DWS 加列后按位置写入 ⇒ **全量档 5 条红**；
  ② `MetricAdsSpecTest` 刻意硬编码 Java 列清单 ⇒ 未同步即红；「两者**定向跑本任务套件时完全看不见**
  （新套件 6/6 绿而全量 189/6 红）」；**过程结论**：「加列类变更**不得以「定向跑绿」作为完成判据**」；
  **候选静态守卫**：「扫描 `INSERT … SELECT` 的目标列清单 vs `LocalSchemaInitJob` DDL 列数/列名；
  以及把硬编码镜像清单改为**读取** Java 白名单」。
- 先例（同型守卫已覆盖的面）：`OdsV2SchemaOwnerSpec`（ODS）→ `DwsSchemaOwnerSpec`（S3-11）→
  `DwdDimSchemaOwnerSpec`（S3-13）→ `AdsSchemaOwnerSpec`（S3-14）。**四者都只覆盖 main 侧**。

## 2. 本轮冻结的事实（实测，非推测）

1. **扫描面实测**：`spark-jobs/src/test/scala` 下 `.scala` 文件 **34** 个（含各子包）；排除
   **3** 个白盒自检文件（`AdsSchemaOwnerSpec` / `DwdDimSchemaOwnerSpec` / `FixtureWriteShapeSpec`
   —— 守卫族自己的负例 SQL 就写在字符串里，**本来就是错的**）。
2. **手写写入点实测 22 处 / 12 个文件**（行号为**真实文件行号**，逐条打印留痕）：
   `AdsCartRateSpec` 121,145 ／ `AdsFavCartCountSpec` 130,154 ／ `AdsFunnelRateReconcileSpec` 90,103 ／
   `AdsHotProductHeatRuleVersionSpec` 117 ／ `AdsQualityRuleVersionSpec` 119,135,158 ／
   `AdsRepeatRateSpec` 122,142 ／ `AdsRfmRawValueSpec` 90,116 ／ `AdsSaleTrendNetSaleSpec` 87,110,245 ／
   `AdsStableOrderSpec` 50,68 ／ `DwsAdsChainExecSpec` 1140 ／ `DwsRegionNetSaleSpec` 82 ／ `SurrogateKeySpec` 437。
   该清单**冻结**在 `FrozenWriteCounts` 中：**新增一处写入、或删掉一处写入，都必须显式落地**。
3. **静态不可判的目标恰好 1 处**：`DwsAdsChainExecSpec.scala :: $stg` —— 目标写在局部
   `val stg = AdsSql.staging(ns, t)`，`t` 取自 `AdsSql.TABLES` **循环变量**，静态不可判；
   已登记入 `RegisteredUnresolvedTargets` 并写明原因，且 A2 **双向**核对（多登记会红、少登记也会红）。
4. **可判写入 21 处**全部通过四项核对：目标表在唯一所有者名下、**投影项数 == 所有者数据列数 + 动态分区列数**、
   投影里**有名字的项**与所有者**同位列名**逐一相等、`PARTITION(…)` 列名同序且静态值必须是字符串字面量。
5. **列名核对的覆盖面（防「装饰性守卫」）**：21 条写入的投影项合计 **292**，其中**命名项 272**（**93.2%**）
   参与同位列名核对；其余 20 项是表达式／字面量（如 `CAST(NULL AS DECIMAL(18,2))`、`1L`），
   按项数参与核对、不参与列名核对。守卫断言该比值 **> 0.7**。
6. **唯一所有者实测**：`LocalSchemaInitJob.statements(ns)` 中 `CREATE TABLE` 语句 **32** 条、
   裸表名 **32** 个（**无重名**，A8 断言 `> 24` 且语句数 == 表名数）。
   （必须按 `CREATE TABLE` 过滤：同名空间下还有 `CREATE DATABASE …` —— S3-11 首轮夹具缺陷。）
7. **`dws_*` 层夹具写入**（S3-03 五条红的同族）：`AdsFunnelRateReconcileSpec:90`
   `dws_behavior_funnel_day`（12 项）、`AdsHotProductHeatRuleVersionSpec:117` 与 `AdsStableOrderSpec:50`
   `dws_product_behavior_day`（7 项）、`AdsRfmRawValueSpec:90` 与 `AdsStableOrderSpec:68`
   `dws_user_trade_period`（7 项）—— 本轮实测**全部与所有者同形**，即 S3-03 之后新增的 DWS 列
   **没有**再产生同类漂移（这是**实测结论**，不是"应该没问题"）。

## 3. 为什么是 A 类（逐门核对）

| 门 | 是否触碰 | 依据 |
| --- | --- | --- |
| ① DROP TABLE/COLUMN | 否 | 只读文本；未删任何列/表 |
| ② 改字段类型或既有业务语义 | 否 | 未改任何列类型、未改任何口径 |
| ③ 改已发布 Flyway 迁移 | 否 | `db/metric/V1-V10`、`db/meta/V1-V25` 零改动 |
| ④ 写/迁移正式 3306 数据 | 否 | 未建立任何数据库连接 |
| ⑤ 切 ACTIVE | 否 | 未触碰发布/激活逻辑 |
| ⑥ 改 `contract-specs/**` 契约语义 | 否 | `contract-specs/**` 零改动 |
| ⑦ 改 V3.0 总体架构 | 否 | 只加一个测试守卫 |
| ⑧ 改正式项目范围 | 否 | 对标 `PROJECT_STATUS:224` **既有**登记项的 (a) 一半 |
| ⑨ 删除已发布功能 | 否 | 无删除 |
| ⑩ 引入未规划大型基础组件 | 否 | 无新依赖（只用 scalatest ＋ JDK `Files`） |
| ⑪ 重大长期架构分叉 | 否 | 静态守卫，判定点唯一 |

**判为 A 类的核心理由**：这是**修实现遗漏**（守卫族的覆盖面缺 test 侧）＋ **纯加法**（新文件、新用例），
且**新增的用例本身就是「未实测不写结论」的执行器** —— 它把此前靠人工发现的盲区变成可重复的红/绿判定。

## 4. 本轮实施清单

1. **新增** `spark-jobs/src/test/scala/com/graduation/analytics/FixtureWriteShapeSpec.scala`（**9 条**，无 Spark）：
   - A1 扫描面非空 ＋ 逐文件写入点计数 == `FrozenWriteCounts`（并打印完整清单为证据）；
   - A2 不可判目标集 == `RegisteredUnresolvedTargets` **双向** ＋ 登记必须写清原因；
   - A3 可判条数对账 ＋ 每条 `checkProjection` ＋ **命名项占比 > 0.7**（反装饰性守卫）；
   - A4 每条可判写入的 `PARTITION` 与所有者同名同序、静态值必须是字符串字面量；
   - A5 自检：排除清单里的文件都存在、确实**含 `INSERT` 字面量**、文件名属于守卫族、清单恰 3 项
     （防"拿排除清单把普通套件屏蔽掉"）；
   - A6 自检：**注释里的 `INSERT` 不算写入点**（`DwsSchemaOwnerSpec` 的 `INSERT` 只在注释中 ⇒ 必须扫出 0 条）；
   - A7 自检：把**真实夹具**的投影对调两位、或删掉一项，同一判定点必须红；
   - A8 所有者裸表名唯一（无重名）＋ 覆盖各层；
   - A9 覆盖面：`可判条数 + 已登记不可判条数 == 总条数`（不存在被悄悄跳过的写入）。
2. **`scripts/run-tests.ps1`**：`$BaselineSpark` `266 → 275`（+9 ＝ 新 spec 条数），并写明本轮口径与边界。
3. **零生产改动**：`warehouse/ddl/**`、`spark-jobs/src/main/scala/**`、`analytics-server/**`
   （含全部 Flyway 迁移文本）、`contract-specs/**` **未改一个字节**（`git status` 可验，见 §7）。

## 5. 反熵守卫与「无回归」证据

### 5.1 RED（先红后绿；**前两轮红全是我自己夹具的缺陷**，如实留痕）

- **RED #0（编译失败）**：`mvnExit=1`，三条 `[ERROR]`：
  `not found: value WarehouseNamespace`、`not found: value LocalSchemaInitJob`（缺 import）、
  `missing argument list for method partitions in class Parsed`（`Parsed.partitions` 是**方法**不是 Map 字段，
  须写 `owner.partitions(table)`）。⇒ 判**夹具缺陷**，改正后重跑。
- **RED #1（关键：扫描器一条都没认出来）**：`[S3-15] 扫描文件数=34…手写写入点=0`，A1 `0 was not greater than 0`。
  根因：夹具 SQL 写在 `s"""…""".stripMargin` 里，关键字之间隔的是「换行 ＋ 边距符 `|`」（典型 `\n         |SELECT`），
  我的 `skipWs` **只跳空白** ⇒ 目标之后读 `PARTITION`/`SELECT` 全部落空，**34 个文件全扫空**。
  修正：新增 `skipSeparators`（空白 ＋ `|`）。
- **RED #2（关键：`${AdsSql.staging(ns,"x")}` 被整条丢弃）**：`Tests: succeeded 8, failed 1`，
  A1 差异 `HashMap$HashTrieMap("AdsFunnelRateReconcileSpec.scala": 1 -> 2)` —— 该文件**只扫出 1 处**、
  冻结值要求 2 处（其余 11 个文件全部对上）。
  定位方式：把扫描器算法逐函数**复刻成一次性探针脚本**（`.verify/s315/scan-probe.ps1`）跑同一文件，
  探针逐字打印：`target='${AdsSql.staging(ns, "ads_behavior_funnel")' end=…` ⇒ **括号配平只读到 `)`，
  尾部的 `}` 没吃掉**，于是下一步先撞上 `}`、`PARTITION`/`SELECT` 全读空 ⇒ 整条被丢。
  修正：读目标时把紧跟 `)` 的 `}` 一并消费。
- **RED #3（定位缺陷：行号漂移）**：删除式剥注释让扫描偏移前移，`AdsFunnelRateReconcileSpec` 真实 **L90**
  被报成 **L69**（差 21 行）。⇒ 改为**原位抹空格（换行保留）**，行号与真实文件**一一对应**
  （失败信息里的 `文件:行` 必须是维护者能直接跳过去的行）。
- **RED #4（端到端「守卫确实咬得住」证据，改动随后已还原）**：把**真实夹具**
  `AdsStableOrderSpec.scala:51` 的投影 `product_id, category_id, …` **对调**为 `category_id, product_id, …`
  （只改这一处、只跑本守卫）⇒ `mvnExit=1`、`Tests: succeeded 7, failed 2`（**A3／A4 同时红**），
  消息逐字：`AdsStableOrderSpec.scala:50 → dws_product_behavior_day（目标 ${ns.dws}.dws_product_behavior_day）:
  第 1 项 'category_id' 与所有者同位列名不一致: "[category]_id" was not equal to "[product]_id"`。
  证据：`.verify/s315/red-mutation-TestSuite.txt`；随后 `git checkout --` **还原**，
  `git status --short` 回到只有「本 spec（新增）＋ `run-tests.ps1`（基线）」两处预期改动。
  ⇒ 这条证明守卫**在真实文件上**、经**完整扫描链路**（剥注释 → 目标解析 → 分区 → 投影 → 与所有者比对）
  确实会把「加列/改列序类漂移」判红，而不是只在内存构造的负例上成立。

### 5.2 GREEN（点名套件）

`-Dsuites=com.graduation.analytics.FixtureWriteShapeSpec`（JDK8）：
`Total number of tests run: 9`、`Tests: succeeded 9, failed 0, canceled 0, ignored 0, pending 0`、`mvnExit=0`。
证据：`.verify/s315/green-targeted-TestSuite.txt`。
（GREEN 说明：**22 处手写写入全部与唯一所有者同形**；其中 S3-03 同族的 `dws_*` 4 处见 §2.7。）

### 5.3 双档门禁（fresh 真跑，全部改动落盘后）

> **门禁版本 = 待提交版本**：本 spec 最后一次改动（A8 增加一行**证据打印**，无断言/用例数变化）之后
> **重跑**了 spark 档（RunId `s315_20260916_spark2`），故下表的 275 就是**待提交修订**的实测值，
> 不是"改动前跑过、改动后推导"。本 spec 共跑过两轮 spark 档（`…_spark` / `…_spark2`），**两轮都 `[PASS exit=0]` 且 275**。

- **spark 档 `[PASS exit=0]`**：`Total number of tests run = 275`、`套件 33`（上轮 32，+1 ＝ 新 spec）、
  `succeeded 275, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed = True`、
  `新写=True`、`JDK8=True`、`mvn exit=0`、`基线比对：tests=275 MATCH`（基线 `266→275`，增量 **+9**）。
  证据：`.verify/s315/gate/spark-jobs.log`（内含 `-Dp2.test.runId=s315_20260916_spark2`）、
  `.verify/s315/gate/spark-jdk-version.log`。
- **default 档计数全 MATCH、`[FAIL exit=7]`**：`analytics-server exit=1 Tests run: 909 (F=1 E=0 S=1)`
  （明细 `90+350+163+67+92+147`）、`mall-simulator exit=0 Tests run: 13`、
  `synthetic-data-generator exit=0 Tests run: 106`、三棵树 `合计 = 1028（基线 1028）`；
  唯一红仍是**已登记环境性** `com.graduation.analytics.ingestion.IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`
  （本工作树无 `landing/manifests` 历史清单，与 S3-13/S3-14 同因），本轮**未修、未复制 manifest、未用开关掩盖**。
  证据：`.verify/s315/gate/default-analytics-server.log`、`default-mall-simulator.log`、
  `default-synthetic-data-generator.log`。
  本档同时是**"未波及"对照证据**：改动面不含任何 Java 源 ⇒ Java 侧三项计数与上轮**完全相同**。

### 5.4 不得越界表述

- 本守卫判的是**表形**：目标表、投影**项数**、**有名字的项**的同位列名、`PARTITION` 子句。
  它**不判**口径、**不判**指标值、**不判**运行期物理落盘（本地链与真 Hive 不等价）。
- 「夹具与所有者**同形**」**推不出**「夹具**数据正确**」—— 数值正确性由各套件自己的断言负责。
- 表达式项（`CAST(…)`／字面量／`${…}`）**只参与项数核对**，不参与列名核对；命名项占比 **93.2%**，
  剩下 **6.8%** 是**未覆盖**的列名面，如实记为边界。
- 本守卫**未**对 1 处**静态不可判**写入（`DwsAdsChainExecSpec :: $stg`）做任何判定 —— 只登记，不计入通过面。

## 6. 未测与边界

1. 真集群 `DESCRIBE` / 物理列序**未测**：证据域＝**纯静态文件解析**（本轮连 Spark 都没起）。
2. 真 `spark-submit` / Parquet 落盘**未跑** ⇒「投影列序即落盘列序」只有静态推导。
3. 隔离档（3307 无监听）与全部 `*MySqlIT` **未测**（沿用既有登记）。
4. **扫描器局限（如实声明）**：① 只认**大写** `INSERT OVERWRITE/INTO`（本仓测试夹具的书写约定；
   实测小写仅出现在 `toLowerCase` 的断言期望串里，不是真写入）；② **不识别字符串字面量里的 `//`**
   —— 若将来出现，本守卫把后半行当注释抹掉 ⇒ 可能**漏报**该条（**偏严**，绝不会把注释当语句误报）。
5. `DwsAdsChainExecSpec:1140` 那处写入（`$stg`）在**未测**之列：目标表不可静态解析，故该条的
   列数/列序**没有**被本守卫覆盖。
6. 排除清单（3 个自检文件）**不含**其守卫族以外的文件，且 A5 双向核对；
   但清单本身是**人工维护**的白名单 —— 新增同类自检文件时必须显式登记。

## 7. 检索证据（「未改即证据」）

- `git status --short`（本轮收尾）：仅 `scripts/run-tests.ps1`（基线）与
  `spark-jobs/src/test/scala/com/graduation/analytics/FixtureWriteShapeSpec.scala`（新增）两处；
  另有 `docs/**` 两处文档改动（本登记 ＋ `PROJECT_STATUS` ＋ `status-history`）。
- 生产面零改动的直接证据：`warehouse/ddl/**`、`spark-jobs/src/main/scala/**`、`analytics-server/**`、
  `contract-specs/**` 均**不在**改动清单内；default 档 Java 计数与上轮**完全相同**（§5.3）亦是旁证。

## 8. 遗留 / 后续（登记，不擅自实施）

1. `PROJECT_STATUS:224` 的 **(b) 一半仍开放**：`MetricAdsSpecTest` **刻意硬编码**的 Java
   `MetricAdsCatalog` 列清单，应改为**读取** Java 白名单（同一盲区的另一半；本轮未做，因它触及
   `analytics-server` 既有测试的判定方式，需单独一个开发项）。
2. `PROJECT_STATUS:211`（Spark 规则码字面量 ↔ 登记集的**跨模块**静态守卫，先例 `AiSqlDriftTest`）**仍开放**。
3. 参考副本 `warehouse/ddl/04-ads.sql` 里已登记的漂移列（`ads_operation_overview.snapshot_id`，D-09/V25-C01）
   **仍未删** —— 删列落在 **HARD DECISION 门①** 邻域，须先走设计差异裁定（S3-14 已登记，本轮未动）。
4. `ads_category_sale`/`ads_region_sale` 两张 ADS **仍无生产者**（指导书 L150 要求补分类/地区专题；
   加表会动已发布的「8 张 ADS」契约 ⇒ 门②/门⑥ 邻域，待总控口径）。
