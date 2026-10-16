# S3-14 设计差异登记（2026-09-16）

> 权威：`docs/guidance/项目完整实施指导书 V3.0.md`、`docs/design/项目设计文档 V3.0.md`、`docs/PROJECT_STATUS.md`。
> 本文件是**登记**，不是裁定：凡未实测项一律标注「未测」，不得读作已通过。

## 0. 一句话结论

ADS 层（8 张正式表 ＋ 8 张 `__staging` 暂存表）的物理表形此前**没有任何守卫**：参考副本
`warehouse/ddl/04-ads.sql`、唯一所有者 `LocalSchemaInitJob` 的 `ns.ads` 段、写入投影 `AdsSql` 的
8 个 `INSERT OVERWRITE … SELECT`，三者之间的漂移无人看守。本轮补上同型守卫
（`AdsSchemaOwnerSpec`，12 条），并把三处**已知差异全部写成显式白名单并钉住精确形态**：
参考副本多一个数据列、少 2 张镜像表、少 8 张暂存表。至此四层表形守卫齐备
（ODS `OdsV2SchemaOwnerSpec` / DWD+DIM `DwdDimSchemaOwnerSpec` / DWS `DwsSchemaOwnerSpec` / ADS 本轮）。

**生产侧零改动**：本轮**没有**修改 `04-ads.sql`、`AdsSql.scala`、`LocalSchemaInitJob.scala` 任何一个字节，
没有新增或修改任何 Flyway 迁移，没有连库。改动面＝新增 1 个测试文件 ＋ `scripts/run-tests.ps1` 基线常量 ＋ 三处文档。

## 1. 设计原文（逐字引用，标尺）

- 设计 §9.2 **L320**：`| ADS | 下节10个逻辑专题 | 每行带snapshot/定义版本/业务日期，发布可追溯 |`
  ⇒ 参考副本声明 **10** 张 ADS 逻辑表。
- 设计 §9.2 **L322**：「历史DDL声明ODS4/DWD3/DIM5/DWS7/ADS10，不代表29张都有正确数据。已知DIM仅user/product有日常产出、分类/地区ADS缺生产链，需逐项补证。**目录schema与数据库真实表形状必须对照，不靠文件名推理**。」
- 指导书 §7 阶段3 **L149**：「Spark SQL 计算销售、用户、商品、漏斗及质量专题，逐层对账。」
- 指导书 §7 阶段3 **L150**：「补分类/地区等尚无完整产出的专题；缺库存/成本不得生成利润或库存风险结论。」
- 指导书 §7 阶段3 **L151**：「导出 ADS 制品，核 schema、行数、checksum，MySQL 暂存验证后切 ACTIVE。」
- 设计 §12.5 **L528**：`-> mxp导出+manifest/checksum -> MySQL BUILDING/staging`（ADS 是全链的出口层）。

## 2. 本轮冻结的事实（实测，非推测）

| 事实 | 证据（实测位置） |
|---|---|
| 参考副本声明 **10** 张 ADS 表 | `warehouse/ddl/04-ads.sql` L15/L37/L49/L58/L74/L87/L99/L111/L121/L148 |
| 唯一所有者的 `ns.ads` 段 = **16** 条建表语句（8 正式 ＋ 8 `__staging`） | `LocalSchemaInitJob.statements` L129-229（经 `_._1 == ns.ads` ＋ `contains("CREATE TABLE")` 过滤） |
| 写入作业的表集 = **8** 张 | `AdsSql.TABLES`（`AdsSql.scala` L15-17） |
| ADS 写入的**唯一入口** = 每张表恰好 1 处 `insertTarget` 调用（共 8 处） | `AdsSql.scala` L64/102/117/175/196/222/295/381 |
| ADS 层 `INSERT OVERWRITE TABLE` 字面量只出现 **2** 次（正式 / 暂存两个分支） | `AdsSql.scala` L31、L32 |
| 参考副本 `ads_operation_overview` **15** 列（末位多一个 `snapshot_id STRING`） | `04-ads.sql` L30；本文件 §5.1 的 RED 消息逐字给出 `List(14: ("snapshot_id", "STRING") -> )` |
| 所有者 `ads_operation_overview` **14** 列（无 `snapshot_id`） | `LocalSchemaInitJob` L130-137；`reconcile` L294-304 在真库检测到该列即 `DROP` ＋ 重建（注释逐字：「值恒为 dt，语义错误」） |
| 暂存表分区 = `(snapshot_id STRING, dt STRING)`；正式表分区 = `(dt STRING)` | `LocalSchemaInitJob` L183-229 / L129-178 |
| 2 张镜像表（`ads_category_sale`/`ads_region_sale`）**无所有者、零写入** | 不在 `LocalSchemaInitJob` 的 `ns.ads` 段、不在 `AdsSql.TABLES`；`analytics-server/platform-app/src/main/resources/db/metric/V3__metric_ads_r7.sql` L1-3 逐字：「ADS 服务表补齐：8 张 Hive ADS 对应的 MySQL 宽表。本期 Hive 侧只有 8 张 ADS，因此 `ads_category_sale_m` / `ads_region_sale_m` **不建**（无 Hive 来源就先建空表 = 造假数据，禁止）」 |
| 参考副本**没有**任何 `__staging` 表 | `04-ads.sql` 全文（10 张全是正式表） |

## 3. 为什么是 A 类（逐门核对）

| 门 | 是否触及 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | **否** | 无任何 DROP；**未**删除 `04-ads.sql` 里那个多出来的列（见下） |
| ② 改已有字段类型或既有业务语义 | **否** | 未改任何列的类型/语义；未改任何生产 SQL |
| ③ 改已发布 Flyway migration | **否** | `git status analytics-server/` 为空；`…/resources/db/metric/V1-V10`、`…/resources/db/meta/V1-V25` 字节未动 |
| ④ 写/迁移正式 3306 数据 | **否** | 零连库（本守卫是纯静态文件解析，不起 Spark、不连 MySQL） |
| ⑤ 切 ACTIVE | **否** | 未触碰发布链与快照状态 |
| ⑥ 改 `contract-specs/**` 既有契约语义 | **否** | `git status contract-specs/` 为空 |
| ⑦ 改 V3.0 总体架构 | **否** | 只加测试与登记 |
| ⑧ 改正式项目范围 | **否** | 未新增/删除任何生产表与作业 |
| ⑨ 删除已发布功能 | **否** | 无删除 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **否** | 无新依赖（复用既有 `StaticOdsDdl`/`DmlWriteProjection`/`P2TestSupport` 夹具） |
| ⑪ 两种方案造成重大长期架构分叉 | **否** | 无方案分叉 |

**唯一一处「本该修但本轮不修」**：参考副本 `04-ads.sql` 的 `ads_operation_overview` 多一个**数据列**
`snapshot_id STRING`（已登记缺陷 **D-09 / V25-C01**，`04-ads.sql:13-14` 自陈）。
删掉参考副本里**已声明的一列**落在**门①（DROP COLUMN）邻域**，须先走设计差异裁定 ⇒
本轮**只登记 ＋ 精确钉住**（多一列、在末位、类型 STRING，三者任一变化即红），使该漂移不可能悄悄长大；
**参考副本一旦被修正，`A2` 用例会红 —— 那是要求删除白名单项的通知，不是回归**。

## 4. 本轮实施清单

1. 新增 `spark-jobs/src/test/scala/com/graduation/analytics/AdsSchemaOwnerSpec.scala`（12 条，见 §5）。
2. 修改 `scripts/run-tests.ps1`：`$BaselineSpark` `254 → 266`，并写入 S3-14 的 12 条构成与「未改 DDL/生产 SQL」声明。
3. 新增本登记文件。
4. 修改 `docs/PROJECT_STATUS.md`：新增 S3-14 事实行；把 `:218` 行的 `(d)` 切片标为**已关闭（S3-14）**。
5. 修改 `docs/status-history/开发过程事实与决策记录.md`：新增 `F-47` 条目。

**未改动**（"未改即证据"）：`warehouse/ddl/04-ads.sql`、`spark-jobs/src/main/scala/**`（含 `AdsSql.scala`、
`LocalSchemaInitJob.scala`、`AdsPublishJob.scala`、`AdsQualityJob.scala`）、`db/**`、`contract-specs/**`、
Java 侧 `analytics-server/**`。

## 5. 反熵守卫与「无回归」证据

`AdsSchemaOwnerSpec` 12 条：`A1` 参考副本表集 = 8 张所有者表 ＋ 2 张镜像表白名单且其余表逐列一致
（列名/类型/顺序）｜`A2` 已登记漂移的精确形态｜`A3` 分区列 `dt STRING` 且不混进普通列｜
`A4` 参考副本禁 `ALTER TABLE` 旁路｜`B1` 所有者正式表集 == `AdsSql.TABLES` ＋ 冻结快照逐列｜
`B2` 暂存表数据列 == 正式表数据列、分区 `snapshot_id`+`dt`｜`B3` 参考副本无 `__staging` 表、
所有者的 8 张暂存表 = 正式表名 ＋ `__staging`｜`C1` 8 张正式写入投影列序｜`C2` 8 张暂存写入投影列序｜
`C3` 写入唯一入口（每表 1 处 `insertTarget` ＋ ADS 层仅 2 处 `INSERT OVERWRITE TABLE` ＋
全仓 main 无以 `ads_` 表名直写的旁路）｜`C4` **守卫自检**（投影列序对调、静态分区子句换错时同一判定点必须红）｜
`C5` 冻结快照 `Frozen`（第四份独立依据，防「三份一起漂」）。

### 5.1 RED（先红后绿，两轮都如实留痕）

先按**严格形态**写断言（不带任何白名单），实测差异是否真的存在：

- **RED #0（夹具/语法缺陷，自陈）**：`mvnExit=1`，编译错误 ——
  `value mapValues is not a member of scala.collection.IterableView`。根因：**Scala 2.12** 的
  `Map.view.mapValues` 返回 `IterableView`，2.13 才可直接 `mapValues`。改法＝显式 `map { case (k, v) => k -> v.size }`。
  **这是我自己写坏的夹具，不是被测对象的缺陷**，如实留痕。
- **RED #1（正确的红，两处差异被点名）**：`Tests: succeeded 10, failed 2`（RunId `s314_20260916_red`）：
  - `A1`：`Set(ads_product_conversion, ads_active_trend, ads_hot_product, ads_behavior_funnel, ads_sale_trend, ads_data_quality, ads_operation_overview, ads_user_profile) was not equal to Set(…10 张…)`
    ⇒ **镜像表白名单是必需的**（参考副本确实多 2 张）。
  - `A2`：`ads_operation_overview 参考副本与所有者不一致: List(...)`，diff 逐字含
    `List(14: ("snapshot_id", "STRING") -> )` ⇒ **漂移白名单是必需的**（参考副本确实在第 15 位多一列）。
  - 其余 10 条当场全绿（`B1`–`B3`、`C1`–`C5`），说明表集/分区/投影/唯一入口四面本来就是一致的。

### 5.2 GREEN（点名套件）

`Tests: succeeded 12, failed 0`，`mvnExit=0`（RunId `s314_20260916_green`）。

### 5.3 双档门禁（fresh 真跑，全部改动落盘后）

跑法（两条命令，全部改动落盘后、fresh 真跑）：
`pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default -RunId s314_20260916_def -LogDir .verify/s314/gate -Confirm`
与 `… -Suite spark -RunId s314_20260916_spark …`。

- **spark 档 `[PASS exit=0]`**：`Total number of tests run = 266`、`Suites: completed 32, aborted 0`（上轮 31，**+1 = 新 spec**）、
  `succeeded 266, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed = True`、`新写=True`、`JDK8=True`
  （`Java version: 1.8`）、`mvn exit=0`、`tests=266 MATCH`（基线 `254→266`，增量 **+12** ＝ 新 spec 条数）。
- **default 档计数全 MATCH、`[FAIL exit=7]`**：`analytics-server 909（F=1 E=0 S=1；模块汇总行 6，明细 90+350+163+67+92+147）`、
  `mall-simulator 13`、`synthetic-data-generator 106`、三棵树 `1028`（基线 `1028`），三项计数均打印 `MATCH`（**无 DRIFT**）。
- 唯一红仍是**已登记环境性** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（断言点 `:61`；
  与 S3-13 同因：本工作树无 `landing/manifests` 历史清单。中文部分在重定向日志里是 cp936，此处只引用可辨部分）
  —— 本轮**未修、未复制 manifest、未用开关掩盖**。
- default 档同时是**"未波及"对照证据**：本轮改动面**不含任何 Java 源**（纯 Spark 测试侧 ＋ 脚本常量 ＋ 文档）
  ⇒ Java 侧计数与上轮**完全相同**（`909/13/106`、三棵树 `1028`）。

### 5.4 不得越界表述

本守卫只判**表形 / 列序 / 写入目标 / 分区子句形态**。它**不**判任何口径、**不**判任何指标值、
**不**判真实 Hive 上的物理落盘与列序（`P2TestSupport` 自陈：测试域 ≠ 生产 Hive metastore）。
「三方一致 ⇒ 数据正确」是**错误**推论：本守卫保证的是「三份声明不会各自漂移」，不是「算出来的数是错的还是对的」。
同理，白名单里那 2 张镜像表是否**应当**有实产，本守卫**不作断言**（属 G-04 / 门⑦⑧）。

## 6. 未测与边界

- 真 Hive `DESCRIBE` / 物理列序：**未测**（本地链只用 Spark in-memory catalog 建表）。
- 真集群 `spark-submit` ＋ Parquet 落盘：**未跑**。
- 隔离档（3307 无监听）与所有 `*MySqlIT`：**未测**。
- 参考副本 `04-ads.sql` 从未在真 Hive 上执行过 ⇒「按它建表会得到什么」只有静态推导，**无实测**。
- 参考副本的 `snapshot_id` 数据列漂移：**本轮未修**（门①邻域，待裁定），见 §3。
- 分类/地区 ADS 的实产（`ads_category_sale`/`ads_region_sale`）仍未实现：**未决口径 ＋ 门⑦⑧/G-04**，本轮只钉住"没人偷偷建一半"。
- Java 侧 `MetricAdsCatalog` ↔ MySQL 迁移文本、`MetricAdsSpec` ↔ 运行时暂存表列的一致性由既有 Java/Spark 套件覆盖，**不在本守卫**（避免第二所有者）。

## 7. 检索证据（"未改即证据"）

- `git status --porcelain` 的改动面仅：新增 `AdsSchemaOwnerSpec.scala`、`scripts/run-tests.ps1`、本登记、
  `docs/PROJECT_STATUS.md`、`docs/status-history/开发过程事实与决策记录.md`。
- `git status --porcelain warehouse/ contract-specs/ analytics-server/` 全空 ⇒ DDL 参考副本、契约、Java 侧（含全部 Flyway 迁移文本）均未动。
- `AdsSql.scala` / `LocalSchemaInitJob.scala` 未出现在改动面 ⇒ 生产 SQL 与唯一所有者零改动（本轮守卫是**只读**对账）。

## 8. 遗留 / 后续（登记，不擅自实施）

1. **`04-ads.sql` 的 `snapshot_id` 数据列修正**（门①邻域）——裁定后须同步删除 `AdsSchemaOwnerSpec` 的
   `RegisteredSnapshotIdDriftTables` 白名单项（用例会先红，提醒删）。
2. **2 张镜像表的实产**（分类/地区专题）：指导书 L150 明写"补尚无完整产出的专题"，但口径未决 ＋ 门⑦⑧/G-04 ⇒ 待裁定。
3. `PROJECT_STATUS:218(a)`：测试夹具**自己手写** `INSERT … SELECT` 列清单的静态守卫（S3-11 只覆盖
   `DwsSql`/`DwdSql`/`DimSql`/`TradeDwdJob` 的写入投影，不覆盖测试夹具手写的 INSERT）。
4. `PROJECT_STATUS:218(b)`：`MetricAdsSpecTest` 里**硬编码**的 Java 白名单镜像清单改为**读取** Java 白名单（消除第二所有者）。
5. `PROJECT_STATUS:211`：「Spark 规则码字面量 ↔ 登记集」跨模块静态守卫（先例 `AiSqlDriftTest`）。
