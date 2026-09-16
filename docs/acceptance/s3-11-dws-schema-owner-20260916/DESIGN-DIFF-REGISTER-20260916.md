# S3-11 设计差异登记（2026-09-16）

> 类型：**A 类（修正实现遗漏 ＋ 新增静态守卫）** —— 把 DWS 参考副本 `warehouse/ddl/03-dws.sql`
> 的 `dws_user_behavior_day` 列序改齐到**唯一所有者**与**写入投影**，并新增三方一致守卫
> `DwsSchemaOwnerSpec`（7 条）。
> 关闭的登记项：`docs/PROJECT_STATUS.md:200`（DDL 加列类变更「第二所有者」盲区）的
> **DWS 参考副本 / 写入投影** 这一切片（另一半仍开放，见 §8）。
> 纪律：未实测不写结论；本文件不冒充"真集群表形已验证"。

---

## 0. 一句话结论

DWS 物理表形有**三份**声明：① 参考副本 `warehouse/ddl/03-dws.sql`（`CREATE EXTERNAL TABLE`）
② 唯一所有者 `LocalSchemaInitJob.statements(ns)`（`CREATE TABLE … USING parquet`）
③ **写入投影** `DwsSql.*` 的 `INSERT OVERWRITE … SELECT` 列表。
Spark 按**位置**写 Parquet ⇒ 三份列序只要有一份不同，就**静默串列**：不报错、行数不变、
`SELECT *` 也看不出，只有按列名读指标时才发现值错位 —— 且上层对账会"自证正确"（两边都读同一份错位数据）。

本轮实测（RED 证据见 §5）：漂移**已经发生**。`dws_user_behavior_day` 参考副本为
`user_id, pv, fav, cart, **buy**, search, active_hours`，而所有者与写入投影为
`user_id, pv, fav, cart, search, active_hours, **buy**` ⇒ 若按参考副本建表、按写入投影灌数，
`search`/`active_hours`/`buy` **三列互换**。

**归因（本轮从 git 历史实测得出，不是推测）**：`warehouse/ddl/03-dws.sql` 在 `e745950`
（2026-09-06，"阶段5 数仓DDL+Scala Spark首批作业"）**被创建时**就把 `buy` 放在第 5 位
（`user_id, pv, fav, cart, buy, search, active_hours`）；而同一日的 `cde8e49`（"四层数仓 Spark 作业链本地全通"）
里**所有者与写入投影**是 `user_id, pv, fav, cart, search, active_hours`（**无 `buy`**），
`buy` 随后作为**加法扩列**被追加到所有者与写入投影的**末尾**
（`docs/acceptance/graduation-lane-backup-20260912/…LocalSchemaInitJob.scala` 可见 `… search, active_hours, buy`，
`DwsSql.userBehaviorDay` 当前投影同序）。即：**所有者与写入投影是一直同序演化的一对**，
参考副本自创建起就抄成了另一套列序，且两者从未被同一份测试比对过 ⇒ 漂移无人看守。
（**旁证**：历史设计文稿 V2.2 §6.4 **L781** 把该表核心指标写作「pv、fav、cart、**buy**、active_hours」
（无 `search`，且 `buy` 在 `active_hours` 之前）⇒ 参考副本的列序与该 V2.x 表述**同源**。
V2.x 为**历史文稿（只读，本轮未改）**，不作为 V3.0 的表形依据；V3.0 设计 §12.1 **L313** 只声明列**集**、不声明列序，
故列序"真相"取**写入投影**。）

---

## 1. 设计原文（逐字引用，标尺）

| 位置 | 原文 | 本轮对应动作 |
|---|---|---|
| 设计 §12.1 **L322** | 「历史DDL声明ODS4/DWD3/DIM5/DWS7/ADS10，**不代表29张都有正确数据**。…**目录schema与数据库真实表形状必须对照，不靠文件名推理**」 | 参考副本正是"历史DDL 声明"⇒ 本轮**对照**它与所有者/写入投影；**不靠**文件名、注释、行数推理 |
| 设计 §12.1 **L313** | 「DWS dws_user_behavior_day ｜ source+user+dt，PV/收藏/加购/活跃」 | 该表 7 列（`user_id/pv/fav/cart/search/active_hours/buy`）在本轮冻结为快照（§2） |
| 设计 §12.1 **L319** | 「DWS dws_region_sale_day ｜ source+region+dt，**sale/net/order/buyer**」 | 现状缺 `net_sale_amount` ⇒ 本轮**只登记**、不在本切片改（列增是独立变更，见 §8 S3-12） |
| 指导书 §7 阶段3 **L150** | 「用 Spark SQL 计算…**逐层对账**」 | 列序一致性是"逐层"的**物理前提**：列序错 ⇒ 上层对账可能"自证正确" |
| 设计 §12.3 **L512** | 五类断言**互相独立**，每条规则独立记录作用域/版本/阶段 | 本守卫是**测试侧静态断言**：不新增规则码、不改任何在产规则的语义与档位 |

---

## 2. 语义声明（本轮冻结）

| 项 | 冻结取值 | 依据 |
|---|---|---|
| DWS 表集 | **7 张**：`dws_user_behavior_day`、`dws_behavior_funnel_day`、`dws_product_behavior_day`、`dws_product_sale_day`、`dws_trade_day`、`dws_user_trade_period`、`dws_region_sale_day` | 所有者 `ns.dws` 名下的**建表**语句数（实测该命名空间共 8 条语句，第 8 条是 `CREATE DATABASE IF NOT EXISTS dw_dws`，`LocalSchemaInitJob.scala:40`，必须按 `CREATE TABLE` 过滤） |
| 列序"真相" | **写入投影**（`DwsSql` 的 `SELECT` 列表）＝ 落 Parquet 的实际列序 | Spark 按位置写 |
| 三方一致 | 列**名/类型/顺序**逐列相等；分区列仅 `dt STRING` 且不得混进普通列；参考副本**不得用 `ALTER TABLE` 旁路加列** | 本轮冻结 |
| 冻结快照 | `DwsSchemaOwnerSpec.Frozen`（7 表 × 列名+类型）＝ 第四份独立依据 | 防"三份一起漂"（例如三处同时漏加一列时前三条全绿）；改它必须**显式**，逼一次"这是不是有意的表形变更"判断 |
| 对齐方向 | 参考副本 → 所有者/写入投影（**不是**反向） | ① 所有者是项目约定的唯一所有者；② 写入投影（决定物理落盘）+ 所有者本来一致；③ `graduation-lane-backup-20260912` 的旧所有者快照也是同一列序 ⇒ 三票对一票 |
| **不判什么** | 不判任何口径/指标值；不判运行期物理落盘；不判 DWD/DIM/ODS/ADS 参考副本；不判"两个既有套件自己手写的 INSERT"（§8） | 归因边界；避免一个守卫越界当"全仓正确性"证明 |
| 证明边界 | 只证「**仓库内三份文本**一致 ＋ Scala 侧写入语句列序一致」 | `P2TestSupport` 自陈：测试域＝Scala `local[1]` ＋ in-memory catalog，**不等于**在产 Hive metastore |

---

## 3. 为什么是 A 类（逐门核对）

| 门 | 是否触发 | 理由 |
|---|---|---|
| ① DROP TABLE/COLUMN | **否** | 无任何 `DROP`；无迁移文件；参考副本改动只在 `CREATE … IF NOT EXISTS` 体内调整列**顺序**，列集合一个字未增减 |
| ② 改已有字段类型/既有业务语义 | **否** | 类型逐字未改（`BIGINT`/`INT`/`DECIMAL(18,4)` 等一律不动）；列**集合**不变；改的是参考副本的列序，使其与**已经生效**的所有者/写入投影一致 ⇒ 不改变任何既有语义。**特别说明**：`03-dws.sql` 是历史声明脚本（设计 L322 自陈"不代表正确数据"），且 `CREATE EXTERNAL TABLE IF NOT EXISTS` 对**已存在**表是 no-op ⇒ 本改动**不触发任何既有表的物理变更** |
| ③ 改已发布 Flyway migration | **否** | 本轮**不新增也不修改**任何迁移；`db/meta/V1–V25`、`db/metric/V1–V10` 字节未动（`git status --short` 未列出，见 §7） |
| ④ 写/迁移正式 3306 数据 | **否** | 本轮 **0 次连库**；无 SQL 触库 |
| ⑤ 切 ACTIVE | **否** | 未运行发布作业 |
| ⑥ 改 `contract-specs/**` | **否** | 未改；且检索证明**契约里根本没有 DWS 表的列声明**（§7 检索为空） |
| ⑦ 改 V3.0 总体架构 | **否** | 不加组件、不改分层；只加测试与注释 |
| ⑧ 改正式项目范围 | **否** | 做的是设计 L322「目录 schema 与真实表形状必须对照」的**一格** |
| ⑨ 删除已发布功能 | **否** | 无删除 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **否** | 无新依赖、无新进程 |
| ⑪ 两种方案造成重大长期架构分叉 | **否** | 不涉方案选择；`S3-12`（补 `net` 列）亦按"加法追加"单一做法，未形成分叉 |

**未被本切片处理（留在 §8 登记）**：真集群上**已存在**表的物理列序（`CREATE … IF NOT EXISTS` 不改已存在表）
—— 那需要 `DESCRIBE`/重建决策，属**未测**项，不是本轮改动的前置。

---

## 4. 本轮实施清单

| # | 文件 | 动作 |
|---|---|---|
| 1 | `warehouse/ddl/03-dws.sql` | `dws_user_behavior_day` 列序改齐：`search`、`active_hours` 提到 `buy` 之前（`buy BIGINT` 移到最后）；表头加 5 行**"参考副本 ≠ 唯一所有者"**提示（含三方清单、禁 `ALTER TABLE` 旁路、指向守卫 spec、历史漂移一句话） |
| 2 | `spark-jobs/src/test/scala/com/graduation/analytics/DwsSchemaOwnerSpec.scala`（**新**，302 行，7 条） | A1 参考副本↔所有者逐列（名/类型/序）；A2 分区列＝`dt STRING` 且不混进普通列；A3 参考副本禁 `ALTER TABLE`（**剥 `--` 注释后**扫语句）；B1 写入投影列序↔所有者；B2 每张表恰好一条 `INSERT OVERWRITE` 且指向自称的表；C1 所有者＝冻结快照；C2 参考副本表集＝所有者表集。夹具 `DwsWriteProjection`：顶层逗号切分 + `AS` 别名/纯列引用，认不出的形态**抛异常**（宁可响，不许把解析落空当"零列一致"） |
| 3 | `scripts/run-tests.ps1` | spark 基线 `233 → 240`，并加 S3-11 依据段（依据/增量归因逐条） |

**未改（必须一字未动）**：`DwsSql.scala`、`LocalSchemaInitJob.scala`（两者为**只读对照物**）、
任何 Java 源、任何迁移、`contract-specs/**`、两册正式文档、`warehouse/ddl/00-ods.sql`/`01-dwd.sql`/`02-dims.sql`/`04-ads.sql`。

---

## 5. 反熵守卫与「无回归」证据

### 5.1 RED（先红后绿；两轮都如实留痕）

- **RED 首轮**：`4 失败 / 3 通过`，但 4 条全红在**我自己的夹具**上 —— `LocalSchemaInitJob.statements(ns)`
  过滤 `_._1 == ns.dws` 得 **8** 条（含 `CREATE DATABASE IF NOT EXISTS dw_dws`）而非 7。
  ⇒ 判为**夹具缺陷**（不是实现缺陷），修法是加 `CREATE TABLE` 过滤并把自检改为比对 `Frozen.size`
  （`.verify/…/red/spark-targeted-red.log`，`mvnExit=1`）。
- **RED 二轮（关键证据）**：`6 通过 / 1 失败`，**唯一红**恰好落在真缺陷上：
  ```
  dws_user_behavior_day 参考副本与所有者不一致:
    List((user_id,BIGINT),…,(buy,BIGINT),(search,BIGINT),(active_hours,INT))
      was not equal to
    List((user_id,BIGINT),…,(search,BIGINT),(active_hours,INT),(buy,BIGINT))
  ```
  ⇒ ① 守卫**确实**能抓到已发生的漂移；② 另 6 张表三方一致（不是"全仓都在漂"）
  （`.verify/…/red/spark-targeted-red.log`，`mvnExit=1`）。
- **GREEN 首轮 6/7**：A3 红是**守卫实现缺陷** —— 我在 `03-dws.sql` 头部逐字写了「不得用 `ALTER TABLE` 旁路」，
  而 A3 用朴素文本扫描（连注释一起扫）⇒ 把自己的提示当成违规。改为**剥 `--` 行注释后只扫语句**
  （并在 spec 里注明该剥法的局限：不识别字符串字面量里的 `--`，偏严不偏松）。

### 5.2 GREEN（点名套件）

- `DwsSchemaOwnerSpec` **7/7**、`Tests: succeeded 7, failed 0`、`BUILD SUCCESS`、`mvnExit=0`
  （`.verify/v3-stage3/s3-11-dws-schema-owner/green/spark-targeted-green.log`）。
- 靶向命令：`mvn -o -f spark-jobs/pom.xml test "-Dsuites=com.graduation.analytics.DwsSchemaOwnerSpec"`（JDK8）。

### 5.3 双档门禁（fresh 真跑，全部改动落盘后）

见 §5.4 实测记录（`gate/spark-gate.out.log`、`gate/default-gate.out.log`）。

### 5.4 门禁实测记录

- **spark 档 `[PASS exit=0]`**（`gate/spark-gate.out.log`，RunId `s311_20260917_spark`）：
  `Total number of tests run = 240`、`Suites: completed 29, aborted 0`、
  `Tests: succeeded 240, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed = True`、
  `本轮新写（mtime ≥ 启动时刻）= True`、`JDK8 取证：Java version: 1.8 = True`、
  `基线比对：tests=240 MATCH`（基线 `233 → 240`，增量 **+7** 与新增用例数**逐条相等**；套件 `28 → 29`，+1 = 新 spec）。
- **default 档计数全 MATCH、`[FAIL exit=7]`**（`gate/default-gate.out.log`，RunId `s311_20260916_def`）：
  `analytics-server 909 (F=1 E=0 S=1)`（明细 `90+350+163+67+92+147`）、`mall-simulator 13`、
  `synthetic-data-generator 106`、**三棵树 `1028`（基线 1028）**、**无 DRIFT**。
  唯一红是**已登记环境性**缺口 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
  （`expected: 43`；本工作树无 `landing/manifests`）—— 本轮**未修、未复制 manifest、未用开关掩盖**。
  **本档为"未波及"对照证据**：本轮改了 `scripts/run-tests.ps1` 基线注释与 `warehouse/ddl/03-dws.sql`（非 Java 源），
  Java 侧计数应与上轮**完全相同**（`909/13/106/1028` MATCH 印证）。

### 5.5 不得越界表述

本守卫**不证**：真 Hive/HDFS 上**已存在**表的物理列序（`CREATE … IF NOT EXISTS` 不改已存在表）、
真集群写入、`spark-submit`、metastore 行为。这些仍是 §6 的未测项。

---

## 6. 未测与边界

| 项 | 状态 |
|---|---|
| 真集群上**已存在**的 `dw_dws.dws_user_behavior_day` 物理列序 | **未测**：若某环境历史上按**漂移的**参考副本建过表，其物理列序与写入投影不符 ⇒ 需人工 `DESCRIBE` 对照 + 重建/迁移决策（本轮 0 次连库） |
| `03-dws.sql` 在真 Hive 上执行 | **未执行**（历史脚本，本轮只改文本） |
| 真 `spark-submit` / Hive metastore / Parquet 落盘 | **未跑**（测试域＝Scala `local[1]` ＋ in-memory catalog） |
| `ALTER TABLE … ADD COLUMNS` 在有数据表上的行为 | **未测**（S3-12 补 `net` 列时会正面遇到，属已知部署注意事项） |
| 隔离档（3307） | **未测**（无监听） |
| ADS 侧三方一致 | **不适用**，差异属已登记的有意差异（§8） |

---

## 7. 检索证据（"未改即证据"）

| 检查 | 命令/方式 | 结果 |
|---|---|---|
| 本轮改动面 | `git status --short` | 仅 3 个路径：` M scripts/run-tests.ps1`、` M warehouse/ddl/03-dws.sql`、`?? …/DwsSchemaOwnerSpec.scala` |
| 契约里有无 DWS 列声明（门⑥） | 对 `contract-specs/**`（8 个文件：README、VERSION、openapi×1、schemas×3、specs×3）检索 `dws_user_behavior_day\|dws_region_sale_day\|active_hours` | **0 命中** ⇒ 契约不声明 DWS 列序，门⑥ 未触发 |
| 迁移是否被碰 | `git status --short` 未列出 `db/meta/**`、`db/metric/**` | 未碰；本轮**不新增迁移** |
| 主源码是否被碰 | 同上；`DwsSql.scala`/`LocalSchemaInitJob.scala` 为**只读对照物** | 未碰 |
| 其它参考副本 | `00-ods.sql`（已有 `OdsV2SchemaOwnerSpec` 守卫）、`01-dwd.sql`、`02-dims.sql`、`04-ads.sql` | 本轮未碰；`01/02/04` 尚无同型守卫 ⇒ §8 候选 |

---

## 8. 遗留 / 后续（登记，不擅自实施）

| 项 | 类型 | 处置 |
|---|---|---|
| `dws_region_sale_day` 缺 `net_sale_amount`（设计 §12.1 **L319** 逐字含 `net`） | **A 类候选（下一切片 S3-12）** | 加性追加列 ⇒ 四处同批：写入投影 + 所有者 + 参考副本 + `Frozen` 快照；并注意 `CREATE TABLE IF NOT EXISTS` **不会**给已存在表加列（真环境需 `ALTER TABLE … ADD COLUMNS`，属已知部署事项，参考 S3-07-R-2/S3-08-R-1 同类登记） |
| ADS 侧**不可照搬**本守卫 | 登记（**未实现**） | `warehouse/ddl/04-ads.sql` 与所有者的差异是**已登记的有意差异**：① 参考副本多一个 `snapshot_id` **数据列**（值恒为 `dt`、语义错误；`04-ads.sql:13` 已注明，运行时由 `LocalSchemaInitJob.scala:283-301` 检测并重建为无该列结构）② 8 张 `{table}__staging` 表**不在**参考副本（副本只声明正式表）③ `ads_category_sale`/`ads_region_sale` 是无生产者的规格冻结镜像（`PROJECT_STATUS:189`）⇒ 若将来做 ADS 守卫，必须先把这三类差异写成**显式白名单**，不能套用 DWS 的逐列相等断言 |
| `PROJECT_STATUS:200` ① 的另一半：「两个既有套件**自己手写** `INSERT OVERWRITE … dws_user_trade_period`」 | **仍开放** | 本守卫只覆盖 `DwsSql` 的写入投影，**不覆盖**测试夹具里手写的 INSERT 列清单 ⇒ 候选：扫描 `spark-jobs/src/test/**` 里手写 INSERT 的列数/列序 |
| `01-dwd.sql` / `02-dims.sql` 无同型三方守卫 | 候选 | 可复用本 spec 的解析器与断言形状（`00-ods.sql` 已有 `OdsV2SchemaOwnerSpec`） |
| 真环境已存在表的物理列序（§6） | **未测** | 需 `DESCRIBE` + 单独立项；不得用本轮的文本对齐冒充"真集群已一致" |
