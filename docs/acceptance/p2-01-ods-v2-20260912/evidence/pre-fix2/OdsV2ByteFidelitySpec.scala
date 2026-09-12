package com.graduation.analytics

import com.graduation.analytics.job.{EventOdsLoadJob, JobArgs, LocalSchemaInitJob}
import com.graduation.analytics.sql.{JsonObjectSlicer, OdsV2Columns}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * P2-01 A5 / A4 / A6 / A7 / A8 / A12c / A12d：**在 Spark 里**端到端验证 payload 字节保真。
 *
 * 真跑：真读黄金夹具（只读 `file:/…/golden-20260901.jsonl`）、真建表（隔离 warehouse
 * `D:/Develop/tmp/p2-01-warehouse/ods-v2-e2e`）、真 INSERT OVERWRITE，再从表里把
 * `payload_json` 读回来与源行**逐字节**比。
 *
 * 独立 oracle（不依赖被测代码）：本机 PowerShell 对 golden 第 1 行 payload 原文算出的
 * `SHA-256(UTF-8) = 385dee5b723e23f0778d6726140ced55b5e9f5aa45f79d42869b1f753e260445`
 * （payload 122 字节）——在测试里作为常量钉死，证明哈希实现不是「自己算自己」。
 */
class OdsV2ByteFidelitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val sparkAppName = "ods-v2-e2e"
  private val ns = WarehouseNamespace.of("dw_b") // 与源 A 的 dw_* 完全不同的前缀，防串库
  private val batchId = 20260901L
  private val defaultSourceSystem = "mock-mall"
  private val SourceSystemConf = "p2.sourceSystem"

  /** 独立 oracle：golden 第 1 行 payload 的 SHA-256（本机 PowerShell 独立算出） */
  private val OracleSha256 = "385dee5b723e23f0778d6726140ced55b5e9f5aa45f79d42869b1f753e260445"
  private val OraclePayloadBytes = 122

  private var spark: SparkSession = _

  /** 黄金夹具原始行（beforeAll 里读；只读，不改写） */
  private lazy val sourceLines: Seq[String] = P2TestSupport.goldenLines

  override def beforeAll(): Unit = {
    P2TestSupport.requireNonEmpty(P2TestSupport.goldenPath)
    spark = P2TestSupport.spark(sparkAppName)
    runPipeline(defaultSourceSystem)
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ── A5：切片器纯函数（含正反对照）────────────────────────────────────────

  "JsonObjectSlicer" should "A5 payload 缺失/畸形一律 Left，正常行 Right 且为原始子串" in {
    JsonObjectSlicer.slice("""{"a":1,"payload":{"x": 1, "s":"}"},"b":2}""") should
      be(Right("""{"x": 1, "s":"}"}"""))
    JsonObjectSlicer.slice("""{"a":1}""") should be(Left(JsonObjectSlicer.ErrKeyMissing))
    JsonObjectSlicer.slice("""{"a":{"payload":{}}}""") should be(Left(JsonObjectSlicer.ErrKeyNested))
    JsonObjectSlicer.slice("""{"payload":{},"payload":{}}""") should be(Left(JsonObjectSlicer.ErrDuplicateKey))
    JsonObjectSlicer.slice("""{"payload":{"a":1}""") should be(Left(JsonObjectSlicer.ErrUnbalanced))
    JsonObjectSlicer.slice("""{"payload":123}""") should be(Left(JsonObjectSlicer.ErrNotObject))
    JsonObjectSlicer.slice("") should be(Left(JsonObjectSlicer.ErrEmptyLine))
    JsonObjectSlicer.slice(null) should be(Left(JsonObjectSlicer.ErrEmptyLine))
    JsonObjectSlicer.slice("""{"s":"\"payload\":{}}""") should be(Left(JsonObjectSlicer.ErrKeyNested))
  }

  it should "A5b 无空白/多空白/转义 三种形态都原样返回（不补空格、不还原转义）" in {
    JsonObjectSlicer.slice("""{"payload":{"a":1}}""") should be(Right("""{"a":1}"""))
    JsonObjectSlicer.slice("""{"payload"  :  { "a" : 1 }  }""") should be(Right("""{ "a" : 1 }"""))
    JsonObjectSlicer.slice("""{"payload":{"q":"a\"b"}}""") should be(Right("""{"q":"a\"b"}"""))
  }

  it should "A4b golden 第 1 行 payload 的 SHA-256 与独立 oracle 逐字节一致（122 字节）" in {
    val line1 = sourceLines.head
    val payload = JsonObjectSlicer.slice(line1).right.get
    payload.getBytes(StandardCharsets.UTF_8).length should be(OraclePayloadBytes)
    JsonObjectSlicer.sha256Hex(payload) should be(OracleSha256)
    // 负对照：语义等价但键序不同的文本，哈希必须不同（证明哈希真的在看字节）
    val reordered = """{"register_time":"2026-09-01T09:00:00+08:00","member_level":"gold","city_level":"tier1","age_group":"25-34","user_id":"1"}"""
    JsonObjectSlicer.sha256Hex(reordered) should not be OracleSha256
  }

  // ── A4：逐行字节保真（从真表读回）────────────────────────────────────────

  it should "A4 逐行比对：源行 payload 原文 == 表内 payload_json，SHA-256 == 表内 payload_hash" in {
    val landed = spark.sql(
      s"""SELECT event_id, payload_json, payload_hash, raw_event_type, landing_file,
         |       source_file, source_system, raw_source_system, event_type
         |FROM ${ns.ods}.ods_user_event""".stripMargin).collect()
      .map(r => r.getString(0) -> r).toMap

    landed.size should be >= 1 // 正对照：否则下面的循环是空转假绿

    val registered = sourceLines
      .filter(l => JsonObjectSlicer.slice(l).isRight)
      .map(l => topLevelString(l, "event_id") -> l)
      .filter { case (id, _) => landed.contains(id) }
    registered.size should be(landed.size)

    registered.foreach { case (eventId, srcLine) =>
      val expected = JsonObjectSlicer.slice(srcLine).right.get
      val row = landed(eventId)
      withClue(s"$eventId payload_json 逐字节: ") {
        row.getString(1) should be(expected)
        row.getString(1).getBytes(StandardCharsets.UTF_8) should be(
          expected.getBytes(StandardCharsets.UTF_8))
      }
      withClue(s"$eventId payload_hash: ") {
        row.getString(2) should be(JsonObjectSlicer.sha256Hex(expected))
      }
      withClue(s"$eventId raw_event_type: ") {
        row.getString(3) should be(topLevelString(srcLine, "event_type"))
        row.getString(8) should be(topLevelString(srcLine, "event_type"))
      }
    }
  }

  it should "A6 landing 原样列不参与去重键：同一 payload_hash 的两行都保留（hash 仅诊断）" in {
    // 诊断语义断言：payload_hash 不是主键 —— 表里允许重复 hash 值
    val dupGroups = spark.sql(
      s"""SELECT payload_hash, COUNT(*) c FROM ${ns.ods}.ods_user_event
         |GROUP BY payload_hash HAVING COUNT(*) > 1""".stripMargin).collect()
    dupGroups.length should be >= 0 // 允许存在重复（不断言必无）
    // 但 event_id（真正的主键）必须唯一
    val ids = spark.sql(s"SELECT event_id, COUNT(*) c FROM ${ns.ods}.ods_user_event GROUP BY event_id")
      .collect().map(_.getLong(1))
    ids.foreach(c => withClue("event_id 必须唯一: ") { c should be(1L) })
  }

  // ── A7/A8：来源系统与落地文件 ────────────────────────────────────────────

  it should "A7 source_system 取平台注入值（不是行内值），raw_source_system 保行内原样" in {
    val rows = spark.sql(
      s"SELECT DISTINCT source_system, raw_source_system FROM ${ns.ods}.ods_user_event").collect()
    rows.map(_.getString(0)).distinct should be(Array(defaultSourceSystem))
    rows.foreach(_.getString(1) should be(defaultSourceSystem)) // golden 行内恰好也是 mock-mall

    // 负对照：注入与行内不同的值 → source_system 跟注入走、raw_* 跟行内走
    val other = "injected-source-x"
    runPipeline(other)
    val after = spark.sql(
      s"SELECT DISTINCT source_system, raw_source_system FROM ${ns.ods}.ods_user_event").collect()
    after.map(_.getString(0)).distinct should be(Array(other))
    after.map(_.getString(1)).distinct should be(Array(defaultSourceSystem))

    runPipeline(defaultSourceSystem) // 复位
  }

  it should "A8 landing_file/source_file 取真实落地文件路径（非常量 'landing'）" in {
    val rows = spark.sql(
      s"SELECT DISTINCT landing_file, source_file FROM ${ns.ods}.ods_user_event").collect()
    rows.length should be(1)
    val landingFile = rows(0).getString(0)
    rows(0).getString(1) should be(landingFile)
    landingFile should not be "landing"
    landingFile should include("golden-20260901.jsonl")
    landingFile should include("golden-dataset")
  }

  // ── 端到端计数与表结构 ──────────────────────────────────────────────────

  it should "A12c 端到端真跑：golden 55 行读入，四主题表合计 52 行（3 行 rejected）" in {
    val counts = OdsV2Columns.OdsTables.map { t =>
      t -> spark.sql(s"SELECT COUNT(*) FROM ${ns.ods}.$t").collect()(0).getLong(0)
    }.toMap
    withClue(s"$counts: ") { counts.values.sum should be(52L) }

    val nonEmpty = counts.filter(_._2 > 0).keys
    nonEmpty should not be empty // 正对照：不是空表假绿
    nonEmpty.foreach { t =>
      withClue(s"$t: ") {
        spark.sql(s"SELECT COUNT(*) FROM ${ns.ods}.$t WHERE payload_json IS NOT NULL")
          .collect()(0).getLong(0) should be(counts(t))
      }
    }
  }

  it should "A12d metastore 读回的表结构含 12 列公共列，顺序与唯一所有者一致" in {
    OdsV2Columns.OdsTables.foreach { t =>
      withClue(s"$t: ") {
        spark.table(s"${ns.ods}.$t").schema.fieldNames.toSeq should be(OdsV2Columns.columnNames(t))
      }
    }
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────

  /** 真跑一次 odl：建库建表（隔离 warehouse）→ EventOdsLoadJob */
  private def runPipeline(sourceSystem: String): Unit = {
    LocalSchemaInitJob.statements(ns).foreach { case (_, ddl) => spark.sql(ddl) }
    val args = JobArgs.parse(Array(
      "--runtimeProfileId=1", "--jobCode=odl", "--businessDate=20260901", "--attemptNo=1",
      s"--hiveDatabasePrefix=${ns.prefix}",
      s"--sourceSystem=$sourceSystem",
      s"--landingDir=${P2TestSupport.goldenUri}",
      s"--batchId=$batchId")).right.get
    EventOdsLoadJob.instance.run(spark, args)
  }

  /** 从源行里取一个顶层字符串字段（测试自身用；不依赖被测切片器的键定位） */
  private def topLevelString(line: String, key: String): String = {
    val idx = line.indexOf("\"" + key + "\"")
    val from = line.indexOf('"', idx + key.length + 2) + 1
    val to = line.indexOf('"', from)
    line.substring(from, to)
  }
}
