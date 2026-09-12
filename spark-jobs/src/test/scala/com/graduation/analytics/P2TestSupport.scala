package com.graduation.analytics

import org.apache.spark.sql.SparkSession

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

/**
 * P2-01 测试夹具（只给 P2-01 的套件用）。
 *
 * 隔离纪律（硬约束）：**绝不写真实 `spark-warehouse`**。本夹具把
 *  - `spark.sql.warehouse.dir` → `D:/Develop/tmp/p2-01-warehouse/<suite>/<runId>`
 * 指到独立临时目录，测试报告里如实写这个路径。
 *
 * 黄金夹具 `tests/golden-dataset/events/golden-20260901.jsonl` **只读**：
 * 需要落盘给 Spark 读时不复制、不改写，直接用绝对路径（`file:/…`）读。
 */
object P2TestSupport {

  /** 独立 warehouse 根（与在产 `D:\Develop_code\GraduationProject\spark-warehouse` 无关） */
  val TempRoot = "D:/Develop/tmp"

  /** 黄金夹具（只读输入） */
  val GoldenRelative = "tests/golden-dataset/events/golden-20260901.jsonl"

  /** 向上找仓库根（Maven 的 CWD 是模块目录） */
  lazy val repoRoot: Path = {
    val start = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath.normalize()
    var dir: Path = start
    while (dir != null && !Files.isRegularFile(dir.resolve(GoldenRelative))) dir = dir.getParent
    if (dir == null) throw new IllegalStateException(s"找不到仓库根：从 $start 向上未发现 $GoldenRelative")
    dir
  }

  lazy val goldenPath: Path = repoRoot.resolve(GoldenRelative)

  /** 黄金夹具原始行（UTF-8；行尾 CR 已剥离，与 Spark text 源一致） */
  lazy val goldenLines: Seq[String] = {
    val raw = new String(Files.readAllBytes(goldenPath), StandardCharsets.UTF_8)
    raw.split("\n", -1).toSeq.map(_.stripSuffix("\r")).filter(_.nonEmpty)
  }

  /** 黄金夹具文件 URI（`file:/…`，Spark/Hadoop 双方都认） */
  lazy val goldenUri: String = goldenPath.toUri.toString

  /**
   * 建一个与在产仓库完全隔离的 SparkSession。
   *
   * **为什么不 `enableHiveSupport()`（实测事实，非选择）**：
   * `spark-jobs/pom.xml` 把 `spark-hive_2.12` 声明为 `provided`，测试 classpath 里没有 Hive 类 ⇒
   * `enableHiveSupport()` 抛 `IllegalArgumentException: Unable to instantiate SparkSession with Hive support
   * because Hive classes are not found.`；而本机 `D:\maven_repository` 里**只有 `spark-hive_2.12:3.3.2`**
   * （本项目是 Spark **3.5.1**）且 **没有 `hive-exec`** ⇒ 离线状态下**无法**把 3.5.1 的 Hive 摆上测试 classpath。
   * 因此测试统一走 Spark 默认 catalog（`spark.sql.catalogImplementation=in-memory`），
   * 建表语句用的是 `USING parquet PARTITIONED BY (…)` 语法，在其上**可以照常执行**。
   *
   * **这条差异必须写进报告**：测试域走的是内存 catalog，在产 `spark-submit` 走的是
   * `D:\Develop\spark-3.5.1-bin-hadoop3` 发行版里的 Hive（`enableHiveSupport()`）⇒
   * 「测试通过」不等于「在产 Hive metastore 上通过」，二者不可互相替代取证。
   *
   * 隔离纪律（硬约束）：**绝不写真实 `spark-warehouse`**。
   *
   * @param suiteName 用于隔离目录名（各套件互不共享）
   */
  /**
   * 本次测试 JVM 的运行标识（每次运行唯一），用于给每个套件的仓库根再加一层。
   *
   * **为什么必须有（实测，20260912 E2 第三轮）**：`catalogImplementation=in-memory` 只让 **catalog**
   * 每轮新建，**仓库目录**是留在磁盘上的——上一轮建过
   * `…/p2-01-warehouse/ods-v2-edge/dw_edge_ods.db/ods_user_event` 之后，下一轮在空 catalog 里
   * 重新 `CREATE TABLE IF NOT EXISTS`，Spark 发现 location 已存在 →
   * `[LOCATION_ALREADY_EXISTS]` 直接把 `beforeAll` 打崩（`OdsV2EdgeCaseSpec`、`OdsV2ByteFidelitySpec`
   * 两个套件 ABORTED，86/100 例）。本轮禁止删除任何文件（含测试临时目录），
   * 所以取「每轮换根目录」而不是「开跑前清目录」；可用 `-Dp2.test.runId=…` 显式指定以便复现。
   */
  private val runId: String = sys.props.get("p2.test.runId").filter(_.nonEmpty).getOrElse {
    val ts = java.time.LocalDateTime.now()
      .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    s"$ts-${ProcessHandle.current().pid()}"
  }

  def spark(suiteName: String): SparkSession = {
    val warehouse = s"$TempRoot/p2-01-warehouse/$suiteName/$runId"
    Files.createDirectories(Paths.get(warehouse))

    val session = SparkSession.builder()
      .appName(s"p2-01-$suiteName")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.warehouse.dir", warehouse)
      .config("spark.sql.catalogImplementation", "in-memory")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.sql.session.timeZone", "Asia/Shanghai")
      .config("spark.sql.sources.partitionOverwriteMode", "dynamic")
      .getOrCreate()
    session.sparkContext.setLogLevel("ERROR")
    session
  }

  /** 关掉会话（套件末尾调；元数据落在隔离目录里，不影响在产仓库） */
  def stop(session: SparkSession): Unit =
    if (session != null) session.stop()

  /** 断言文件存在且非空（读任何文件前先断言，硬约束） */
  def requireNonEmpty(path: Path): Unit = {
    if (!Files.isRegularFile(path)) throw new IllegalStateException(s"文件不存在：$path")
    if (Files.size(path) <= 0) throw new IllegalStateException(s"文件为空：$path")
  }

  /** Java 集合 → Scala 序列（避免各套件重复 imports） */
  def seqOf[T](it: java.util.Iterator[T]): Seq[T] = it.asScala.toSeq
}
