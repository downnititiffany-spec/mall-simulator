# DESIGN-DIFF-REGISTER — S3-30：`MetricAdsSpecTest` 的列清单第二所有者收口（改读 Java 白名单）（2026-09-16）

> 任务号 **S3-30**。分支 `feature/v3-development`（**未 merge main**）。工作树
> `D:\Develop_code\GraduationProject-wt\v3-dev`；主检出 `D:\Develop_code\GraduationProject` **未动**。
> 本文件随代码同批提交；**证据只落在 `$env:TEMP` 与 gitignored `.verify/**`**，不进仓库。

## §0 证明边界（先说清本项**不**证什么）

1. 本项只证「**`MetricAdsSpecTest` 的期望列清单来自唯一所有者（Java 白名单）源文件**，且该比较在
   所有者真变时确实会红」，**不**证任何表形/口径**本身**正确。
2. **不**证 DDL 守卫族已完备；**不**证「Java 白名单 ↔ Hive 所有者」之间不存在**任何**残余静默面。
3. 证据域仍是 **Scala `local[1]` ＋ in-memory catalog** 的 ScalaTest，**没有**真 `spark-submit`、
   **没有** Hive metastore、**没有** MySQL（**0 次连库**）。
4. 前端/DOM **未涉及**（本项零 `web/**` 改动）。
5. 本项是**测试树内部**的所有者收口，**未改任何生产代码**：`MetricAdsCatalog.java` 实测前**故意改过又还原**
   （`git status` 对该文件**无输出**＝字节复原），最终提交里**不含**该文件。
6. 移除镜像副本时**顺带移走了它的「冻结副本」作用**，本项**不**声称"什么都没少"；该作用的归属见 §3.5。
7. 本项**不**动 `contract-specs/**`、`docs/contracts/**`、DDL/Flyway、`README.md`、指导书/设计 V3.0。

## §1 逐字锚点（改前状态，行号＝改前实测）

| 锚点 | 位置 | 逐字要点 |
| --- | --- | --- |
| backlog 行（本项来源） | `docs/PROJECT_STATUS.md` L379 状态列 | 「**(a)（S3-15，见下）**」已关闭后原文：「**仍开放：仅剩 (b)** —— `MetricAdsSpecTest` 硬编码 Java `MetricAdsCatalog` 列清单 ⇒ 应改为**读取** Java 白名单（触及 `analytics-server` 既有测试判定方式，需单独开发项）」 |
| 被收口的镜像 | `spark-jobs/src/test/scala/com/graduation/analytics/MetricAdsSpecTest.scala:19-34`（改前） | `private val javaCatalog = Seq(` ＋ 8 条 `"ads_…_m" -> Seq(…)`，逐字复刻 `MetricAdsCatalog.ALL` |
| 同文件设计意图自陈 | 同文件 `:13-14`（改前） | 「这里**刻意**把 Java 侧清单**硬编码**一份：一旦任一侧加了列/改了顺序，本用例立刻变红」 |
| 比较点（唯一，未删） | 同文件 `:56-63`（改前） | `MetricAdsSpec.TABLES.foreach { t => t.columns should be(javaCatalogMap(t.mysqlTable)) }` |
| 所有者 | `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsCatalog.java:21,24-55` | `record MetricAdsCatalog(String name, List<String> columns, List<String> keyColumns)`；`public static final List<MetricAdsCatalog> ALL = List.of(new MetricAdsCatalog("ads_operation_overview_m", List.of(...), List.of()), …)` **共 8 条** |
| JDK/依赖约束（决定实现路径） | `spark-jobs/pom.xml:18-19`（`maven.compiler.source/target=8`）、`:90`（`<release>8</release>`）；依赖仅 spark-core/spark-sql/hadoop-client/scalatest/jackson | spark-jobs **无** `metric-analysis` 依赖，且后者是 **JDK 17** |
| 仓库根读文件既有做法 | `spark-jobs/src/test/scala/com/graduation/analytics/P2TestSupport.scala:28-34`（`repoRoot` 向上找）；`AdsSchemaOwnerSpec.scala:124-128`（`readRepoFile`） | 守卫族一律**读仓库内文本**，不跨模块取类 |
| Java 白名单 ↔ 迁移 DDL 已有守卫 | `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsCatalogDdlConsistencyTest.java:72-86`（`everyCatalogTableHasExactlyTheMigratedColumns`：`assertEquals(spec.columns(), migrated)`） | 证明「(J) Java 白名单 ↔ (M) `db/metric` 迁移 DDL」这一环**已被钉住** |
| Hive 侧冻结快照（承担"一起漂"确认） | `spark-jobs/src/test/scala/com/graduation/analytics/AdsSchemaOwnerSpec.scala:57`（`Frozen`）、`:290`（`columnSeqOf(owner, table) should be(Frozen(table))`）、`:366-370`（`Frozen.size should be(8)`） | 8 张 ADS 表的列名/类型/顺序**冻结**在 Hive 侧，与所有者逐列一致 |

## §2 改前实测（F1–F6，全部实跑）

- **F1｜盲区是事实，不是纸面推测**：把所有者 `MetricAdsCatalog.java` 的
  `ads_operation_overview_m` 列清单**删掉一列** `cart_add_cnt`（仅此一处改动），跑
  `-Dsuites=com.graduation.analytics.MetricAdsSpecTest` ⇒ **`Tests: succeeded 5, failed 0`、exit=0**
  （证据 `$env:TEMP\s330_blindspot1.log`）。即：**所有者少一列，本 spec 照样绿**，因为它只拿
  `MetricAdsSpec`（Scala 导出）与自己那份**副本**相比 —— 副本没动，比对照样成立。
- **F2｜这正是 backlog 记录的事故形态**：spec 的 KDoc 自陈目标是拦「Spark 导出的列与 MySQL 表不匹配
  这种只会在发布时才炸的问题」，而 F1 表明它在**所有者侧**变化时**完全不响** ⇒ 该副本是**第二所有者**
  （列清单在同一仓库里有三处：`LocalSchemaInitJob` DDL 所有者、`MetricAdsCatalog` Java 白名单、本副本）。
- **F3｜改前该 spec 用例数＝5**（改后 7）：`覆盖 8 张 ADS…`、`P1-04 库名前缀…`、`列清单与 Java 侧…一致`、
  `R7-0 口径列已贯通…`、`MetricExportJob.posix…`。
- **F4｜「加模块依赖直读 Java API」不可行（架构级）**：`spark-jobs` 编译面是 **JDK 8**
  （`pom.xml:18-19` ＋ `<release>8</release>`，且该闸门在 S3 系轮次里被专门修过），`metric-analysis`
  是 **JDK 17**，两者**无** Maven 依赖关系 ⇒ 为读一份列清单而把 JDK8↔JDK17 绑在一起属于**架构级分叉**，
  与「只是消掉一份重复清单」的收益不成比例（**门⑪邻域**，故不走这条路）。
- **F5｜既有守卫族的做法就是读文本**：`P2TestSupport.repoRoot` ＋ 各 `*SchemaOwnerSpec` 读
  `warehouse/ddl` 的 SQL 参考副本、`FixtureWriteShapeSpec` 读 Scala 源 ⇒ 本项沿用同型做法（读所有者**源文件**）。
- **F6｜改前 `git status` 只有 2 处预期改动**：`MetricAdsSpecTest.scala`（M）＋ 新增
  `MetricAdsCatalogSource.scala`（??）；`MetricAdsCatalog.java` **无输出**（探针已还原）。

## §3 口径声明（7 条，防越界）

1. **「所有者」唯一定义**：`MetricAdsCatalog.java` 的 `ALL`＝analytics_metric 列白名单的**唯一所有者**；
   `MetricAdsSpecTest` 里的副本**不是**所有者（本项删除之）。比较方向固定为
   **Scala 导出（被比较方）→ Java 白名单（所有者）**。
2. **解析面**＝只取表名 ＋ **第一个** `List.of(...)`（列清单，按源文件顺序）；`keyColumns` 不在本解析面
   （本项不判主键）。**不排序、不去重**：源文件顺序本身是判定依据。
3. **形态脱节必须响亮失败**：找不到 `ALL = List.of(`、或「`new MetricAdsCatalog(` 条目数 ≠ 解析出的明细数」
   ⇒ 抛 `IllegalArgumentException`；**绝不**静默返回空/短清单（否则逐表比对退化成空转）。
4. **反证必须存在**：本类源文件里若再出现**行首** `"ads_…_m" -> Seq(` 形态的镜像 ⇒ 同一用例变红
   （第二所有者不许复活）。判定式取**行首条目**形态，因为真正的镜像是 `val … = Seq(` 里一行一条映射。
5. **移除副本**顺带移走了它的「冻结副本」作用（能抓住"Java 与 Spark 一起改"）。该作用**已经**由
   `AdsSchemaOwnerSpec.Frozen`（Hive 侧 8 张表，`Frozen↔所有者` 逐列钉住）承担，且
   `MetricAdsCatalogDdlConsistencyTest` 钉住 Java↔MySQL 迁移 ⇒ 四方同时漂**仍**会被要求**显式**改
   `Frozen`；故**不新增**静默面。**但**：本项**不**声称"治理后覆盖与原副本逐项等价"（原副本能抓住的
   「Java+Spark 同改而 Frozen 也同改」这一组合，改后只由 `Frozen` 的显式确认承担）。
6. **不动生产**：`MetricAdsCatalog.java`、DDL、迁移、其他任何 `src/main/**` 一律不改；探针改动必须
   `git status` 证明还原。
7. **不越界表述**：不得说「列清单一致性已被完备保证」，只能说「本 spec 的期望值现在读自唯一所有者，
   且所有者真变时本 spec 会红（探针实测）」。

## §4 实现面

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `spark-jobs/src/test/scala/com/graduation/analytics/MetricAdsCatalogSource.scala` | **新增**（61 行） | 测试专用只读解析器：`private[analytics] object MetricAdsCatalogSource`，`parse(javaSource): Seq[(String, Seq[String])]`；三处 `require` 把「形态脱节/解析面为空/条目数与明细数不等」变成响亮失败 |
| `spark-jobs/src/test/scala/com/graduation/analytics/MetricAdsSpecTest.scala` | 改（+54/−19） | 删除 17 行硬编码镜像；`javaCatalog` 改为 `MetricAdsCatalogSource.parse(readRepoFile(javaCatalogRelative))`；新增 2 条用例（`:68` 读取自所有者＋反证；`:89` 解析器牙齿＋形态脱节抛错）；KDoc 改写为"S3-30 起读所有者文件"并写清盲区实测 |
| `scripts/run-tests.ps1` | 改（基线） | `$BaselineSpark` 303 → **305**（＋ S3-30 注释块：量数轮/收口轮 RunId、证据文件名、边界） |
| 契约 | **不改** | 本项零对外语义、零 API/DDL/口径变化 ⇒ **无契约条目可加**；`docs/contracts/**` 与 `contract-specs/**` 本轮均未动（判断登记于此备复核） |

## §5 11 门逐门否

①DROP TABLE/COLUMN：**否**（未执行任何 DDL；`MetricAdsCatalog.java` 的删列只出现在**临时探针**里且已还原，
`git status` 无该文件）。②改已有字段类型/既有业务语义：**否**。③改已发布 Flyway 迁移：**否**。
④写/迁移正式 3306 数据：**否**（0 次连库）。⑤切 ACTIVE：**否**。⑥改 `contract-specs/**` 已有契约语义：**否**。
⑦改 V3.0 总体架构：**否**。⑧改正式项目范围：**否**。⑨删除已发布功能：**否**（只删测试内的重复清单）。
⑩引入 V3.0 未规划大型基础组件：**否**（零新依赖；**未**为读一份清单而引入跨 JDK 模块依赖）。
⑪两种方案造成重大长期架构分叉：**否**（F4 已排除"加跨 JDK 依赖"方案；本项仅测试树内部）。

⇒ **A 类（实现/加性，测试守卫内部）**，自主登记→设计→实现→测试→commit→继续，无需总控裁决。

## §6 实测记录（RED → GREEN → 牙齿探针 → 档级回归）

### §6.1 RED（先写测试再看它红）

- 先改 `MetricAdsSpecTest`（引 `MetricAdsCatalogSource` ＋ 2 条新用例），**解析器尚未存在**：
  `mvn -o -f spark-jobs/pom.xml -Dsuites=com.graduation.analytics.MetricAdsSpecTest test`
  ⇒ `exit=1`、`BUILD FAILURE`、`scala-maven-plugin:testCompile` 失败，逐字：
  `MetricAdsSpecTest.scala:37/90/92/94: not found: value MetricAdsCatalogSource`（**4 处**）、`four errors found`。
  证据 `$env:TEMP\s330_red1.log`。**性质**：行为缺失（编译红）—— 与本仓库 S3-29 同口径。
- **失败轮（如实登记，不并入 GREEN）**：
  - GREEN 尝试 1（`s330_green1.log`）：`MetricAdsCatalogSource.scala:5: unclosed comment` —— 我在 KDoc 里写了
    `warehouse/ddl/*.sql`，其中的 `*/` **提前闭合了文档注释**（自伤缺陷，非环境问题）。改为
    「读 `warehouse/ddl` 的 SQL 参考副本」后通过编译。
  - GREEN 尝试 2（`s330_green2.log`）：编译过了，`Tests: succeeded 6, failed 1`；红项正是新用例，逐字
    `Some(""ads_xxx_m" ->) was not equal to None (MetricAdsSpecTest.scala:82)` ⇒ **判定式匹配到了它自己的
    注释文本**（注释里描述了该形态）。改判定式为必须带 `-> Seq(`。
  - GREEN 尝试 3（`s330_green3.log`）：仍 `succeeded 6, failed 1`，逐字
    `Some(""ads_x_m" -> Seq() was not equal to None (MetricAdsSpecTest.scala:84)` ⇒ 这次匹配到**牙齿用例自己的
    合成样例** `Seq("ads_x_m" -> Seq("a", "b"))`。改判定式为**行首条目**形态（`(?m)^\s*"ads_…_m"\s*->\s*Seq\(`）。
  - 三次失败都是本项引入的**真实自伤**，逐条留在登记里（**不以"最后绿了"覆盖过程**）。

### §6.2 GREEN

- `s330_green4.log`：`-Dsuites=…MetricAdsSpecTest` ⇒ `exit=0`、`BUILD SUCCESS`、
  `Total number of tests run: 7`、`Tests: succeeded 7, failed 0, canceled 0, ignored 0, pending 0`。
- 用例名单（`spark-jobs/target/surefire-reports/TestSuite.txt` 实读）含新增两条：
  `S3-30：期望列清单读取自唯一所有者文件（不是本类内的硬编码镜像）`、
  `S3-30：解析器有牙齿（所有者真加/减列时解析结果随之变化；形态脱节即抛错）` ⇒ **确认真的跑了，不是 0 计数**。

### §6.3 牙齿探针（两个方向，均真跑）

- **探针 A（所有者侧，改后）**：再次删掉 `MetricAdsCatalog.java` 的 `ads_operation_overview_m.cart_add_cnt`
  （与 F1 同一处）⇒ `Tests: succeeded 6, failed 1`、`exit=1`，红项＝`列清单与 Java 侧 MetricAdsCatalog 完全一致（含顺序）`
  （`MetricAdsSpecTest.scala:63`），逐字线索含
  `ads_operation_overview_m: List(…, "fav_cnt", "cart_add_cnt") was not equal to List(…, "fav_cnt")`
  （前者＝Scala 导出，后者＝**读自所有者**）。证据 `s330_probeA1.log`。
  ⇒ 与 **F1 同一处变动、改前绿/改后红**，盲区已被关闭（对照证据成对存在）。随后**已还原**（`git status` 无该文件）。
- **探针 B（被比较方侧，反证）**：临时把镜像形态塞回本类源文件
  （`private val mirrorProbe = Seq(\n "ads_sale_trend_m" -> Seq("order_count"))`）⇒ `Tests: succeeded 6, failed 1`、
  红项＝`S3-30：期望列清单读取自唯一所有者文件…`，逐字 `Some("    "ads_sale_trend_m" -> Seq() was not equal to None
  (MetricAdsSpecTest.scala:89)`。证据 `s330_probeB1.log`。⇒ 反证**有牙齿**（第二所有者回流即被拦）。随后**已删除**。

### §6.4 档级回归（真跑，两轮；完成判据取**全量档**，不取定向跑绿）

> 依据本 backlog 行自身的过程结论（S3-03）：**「加列类变更不得以"定向跑绿"作为完成判据」**；
> 本项虽为测试树改动，同样按全量 spark 档收口。

| 轮 | RunId | 结果 |
| --- | --- | --- |
| 量数轮 | `s330_20260916_spark1` | `Total number of tests run = 305`、`Suites: completed 36, aborted 0`、`Tests: succeeded 305, failed 0, canceled 0, ignored 0, pending 0`、`All tests passed = True`、`mvn exit=0`、`JDK8=True`、TestSuite.txt `新写=True`；逐字 `基线比对：tests=305 DRIFT(基线 303) ⇒ 基线漂移`（**+2**＝本项新增 2 条用例）；档摘要 `spark FAIL`（因 DRIFT 未收口）、`[FAIL exit=7]` **预期** |
| 基线更新 | `scripts/run-tests.ps1` | `$BaselineSpark` 303 → **305**（附 S3-30 注释块：本项来源、盲区实测、RED/GREEN/探针文件名、JDK 约束、边界） |
| 收口轮 | `s330_20260916_spark2` | `Tests run: 305 (failed=0 aborted=0 ignored=0)`、`套件 36`、`mvn exit=0`、`新写=True`、`JDK8=True`；逐字 `基线比对：tests=305 MATCH`；档摘要 **`spark PASS`**、**`[PASS exit=0]`**、wrapper `exit code: 0` ⇒ **计数 MATCH ＋ 全绿**（该档无已登记环境性红；本档不跑 IT，IT 按既有裁决不计入） |

- **未加** `-AllowCountDrift`（+2 是**有意**新增用例，靠"量数轮→改基线→收口轮"的既有收口方式处理）。
- `default` / `isolated` 两档**本轮未重跑**：`git status` 实测**零** `analytics-server/**` 改动
  （唯一触碰的 analytics 侧文件 `MetricAdsCatalog.java` 已字节还原），故三棵树计数不受影响；
  该跳过是**有意**的，且**不**据此声称这两档"已通过"。

## §7 未测与边界（不得越界表述）

1. **未测**真 `spark-submit`／Hive metastore／Parquet 落盘；证据域仅 ScalaTest `local[1]` ＋ in-memory。
2. **未测**任何 IT（`MetricAdsMySqlIT`/`MetricPublisherMySqlIT`/`SourceRegistryMigrationMySqlIT` 等按既有裁决
   不计入统一门禁）；**0 次连库**。
3. **未改** `MetricAdsCatalog.java`／任何 DDL／迁移／`src/main/**`／`web/**`／`pom.xml`（零新依赖）。
4. **未**把守卫扩到别的所有者（如 `AdsSql` 投影、DWS/DWD 层）——超出本项范围。
5. **未**证「列清单一致性已被完备保证」；本项只证该 spec 的期望值来源与红/绿行为。
6. **不**声称"移除副本与保留副本的覆盖逐项等价"（§3.5 已写明唯一差异与其归属）。
7. 未触碰 `contract-specs/**`、`docs/contracts/**`、`README.md`、指导书/设计 V3.0、V2.x 历史文档、
   `scripts/run-isolated-tests.ps1`、`IngestionManifestRuntimePatrolTest`、`ReferenceMapping`/`map_layer_types()`、
   已发布 Flyway 迁移、3306/3307 正式数据；未新增权限码；未用 `@SpringBootTest`。

## §8 结论与后续

- **结论**：backlog 行 L379 的**最后开放项 (b)** 已收口（A 类）：`MetricAdsSpecTest` 不再持有列清单副本，
  期望值**读自唯一所有者** `MetricAdsCatalog.java`，并有「读取自所有者」＋「解析器有牙齿」两条自检；
  盲区以**同一处变动的改前绿/改后红**成对证据关闭。该 backlog 行的 (a)(c)(d) 已在此前轮次关闭，
  故该行**全部开放项清零**（行本身按 append-only 追加更新描述，**不删行**）。
- **下一轮**：按 `docs/PROJECT_STATUS.md` backlog **表序重扫**取表序第一的「未阻塞且无需裁决」项，
  本登记**不**预先承诺具体项（行号随文件增长漂移）；`repeat_rate` 系列列消费方、`cart_rate` 未进概览 API、
  DIM 三张表未决口径、`db/meta` 真库存在性（DEV-004）、R-1/R-3/R-4 等待批注项**均不变**。
- **登记文件**：本文件（`docs/acceptance/s3-30-metric-ads-catalog-single-owner-20260916/`）。
