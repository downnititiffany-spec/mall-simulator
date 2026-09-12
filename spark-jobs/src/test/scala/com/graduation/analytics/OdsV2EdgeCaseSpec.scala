package com.graduation.analytics

import com.graduation.analytics.job.{EventOdsLoadJob, JobArgs, LocalSchemaInitJob}
import com.graduation.analytics.sql.JsonObjectSlicer
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/**
 * P2-01 A5c / A6 / A6b：**受控夹具**上的边界行为。
 *
 * 黄金夹具里没有「缺 payload」「payload 内嵌套大括号/转义引号」「同一 payload 重复出现」这几类行，
 * 所以这里在**隔离临时目录**里显式造 5 行受控输入（不碰仓库里的 `tests/` 与 `landing/`），
 * 逐类钉死行为：
 *  - 缺 `payload` 键 / 值非对象 → 行照常入库（不丢行），`payload_json`、`payload_hash` 双 NULL；
 *  - payload 内含 `}`、`{`、转义引号 → 切片按深度/引号状态正确收尾，**逐字节**等于源行那一段；
 *  - 两行 payload 完全相同 → `payload_hash` 相同、两行都保留（hash 不是去重键，仅诊断）。
 */
class OdsV2EdgeCaseSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val ns = WarehouseNamespace.of("dw_edge")
  private val sparkAppName = "ods-v2-edge"
  private val batchId = 20260902L
  private val sourceSystem = "edge-src"

  private var spark: SparkSession = _

  /** 受控输入（每条都是**完整的 landing JSON 行**；`|` 只用于可读性，写入时去掉） */
  private val InnerJson = """{"user_id":"1","nested":{"a":1,"b":"}"},"q":"he said \"hi\"","list":[{"x":2}]}"""

  private val lines: Seq[String] = Seq(
    // 0) 正常：payload 内含嵌套对象、字符串里的 `}`、转义引号、数组
    s"""{"event_id":"edge-1","event_type":"user_registered","event_time":"2026-09-02T10:00:00+08:00","ingest_time":"2026-09-02T10:00:01+08:00","source_system":"row-src","schema_version":"1.0","trace_id":"t-1","payload":$InnerJson}""",
    // 1) 缺 payload 键（但有合法主键）→ 必须入库且双 NULL
    """{"event_id":"edge-2","event_type":"user_registered","event_time":"2026-09-02T11:00:00+08:00","ingest_time":"2026-09-02T11:00:01+08:00","source_system":"row-src","schema_version":"1.0","trace_id":"t-2"}""",
    // 2) payload 值非对象（字符串）→ 切不出对象语义，双 NULL
    """{"event_id":"edge-3","event_type":"user_registered","event_time":"2026-09-02T12:00:00+08:00","ingest_time":"2026-09-02T12:00:01+08:00","source_system":"row-src","schema_version":"1.0","trace_id":"t-3","payload":"not-an-object"}""",
    // 3) payload 与第 0 行完全相同 → hash 相同，两行都要在
    s"""{"event_id":"edge-4","event_type":"user_registered","event_time":"2026-09-02T13:00:00+08:00","ingest_time":"2026-09-02T13:00:01+08:00","source_system":"other-src","schema_version":"1.0","trace_id":"t-4","payload":$InnerJson}""",
    // 4) 整行少一个右括号 ⇒ **非法 JSON 行**：既有 valid 闸拒绝它（不抛异常、不中断整批）；
    //    「值括号不配平 → 切不出 payload」在切片层由 A5c 的纯函数断言钉住
    """{"event_id":"edge-5","event_type":"user_registered","event_time":"2026-09-02T14:00:00+08:00","ingest_time":"2026-09-02T14:00:01+08:00","source_system":"row-src","schema_version":"1.0","trace_id":"t-5","payload":{"user_id":"1"}"""
  )

  private val landingDir: String = s"${P2TestSupport.TempRoot}/p2-01-edge-landing"

  override def beforeAll(): Unit = {
    // 落地只读输入：写进隔离临时目录（不用删除类命令，文件按固定名覆盖写）
    Files.createDirectories(Paths.get(landingDir))
    val fixture = Paths.get(landingDir, "edge-20260902.jsonl")
    Files.write(fixture,
      (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
    P2TestSupport.requireNonEmpty(fixture)

    spark = P2TestSupport.spark(sparkAppName)
    LocalSchemaInitJob.statements(ns).foreach { case (_, ddl) => spark.sql(ddl) }
    val args = JobArgs.parse(Array(
      "--runtimeProfileId=1", "--jobCode=odl", "--businessDate=20260902", "--attemptNo=1",
      s"--hiveDatabasePrefix=${ns.prefix}",
      s"--sourceSystem=$sourceSystem",
      s"--landingDir=file:///$landingDir",
      s"--batchId=$batchId")).right.get
    EventOdsLoadJob.instance.run(spark, args)
  }

  override def afterAll(): Unit = P2TestSupport.stop(spark)

  // ── A5c：受控行的切片结果 ───────────────────────────────────────────────

  "JsonObjectSlicer on controlled fixtures" should "A5c 嵌套/转义行切出的原文与源行那一段逐字节相同" in {
    val sliced = JsonObjectSlicer.slice(lines.head)
    sliced.isRight should be(true)
    val payload = sliced.right.get
    payload should be(InnerJson)
    payload.getBytes(StandardCharsets.UTF_8) should be(InnerJson.getBytes(StandardCharsets.UTF_8))
    // 源码里的字面写法与切出来的一致（证明没有做任何转义还原）
    payload should include("""\"hi\"""")
    payload should include(""""b":"}"""")
    payload should include("""[{"x":2}]""")

    JsonObjectSlicer.slice(lines(1)) should be(Left(JsonObjectSlicer.ErrKeyMissing))
    JsonObjectSlicer.slice(lines(2)) should be(Left(JsonObjectSlicer.ErrNotObject))
    JsonObjectSlicer.slice(lines(4)) should be(Left(JsonObjectSlicer.ErrUnbalanced))
  }

  // ── A6：缺/坏 payload 不丢行 ────────────────────────────────────────────

  it should "A6 坏 payload 不丢行：缺 payload / 值非对象两行照常入库且双 NULL；整行残缺的行走既有 valid 闸拒绝" in {
    val rows = spark.sql(
      s"""SELECT event_id, payload_json, payload_hash, raw_event_type, raw_source_system,
         |       source_system, landing_file
         |FROM ${ns.ods}.ods_user_event ORDER BY event_id""".stripMargin).collect()

    // 20260912 归属裁定（**测试侧缺陷**）：edge-5 是「整行少一个右括号」的**非法 JSON 行**，
    // 既有 valid 闸（`event_id is not null`，落地区先经 from_json 解析）本来就拒绝这类行——
    // 与黄金夹具第 53 行 `not-valid-json-line-with-no-braces-{{{` 走同一条通道，
    // OdsV2ByteFidelitySpec A12c「55 行读入 / 52 入库 / 3 拒绝」是同一行为的独立证据。
    // 所以这里钉「4 行入库、残缺行不落表」，而不是把非法 JSON 行当合法行收下；
    // 「值括号不配平 → 切不出 payload」由 A5c 的纯函数断言（slice(lines(4)) == Left）钉住。
    rows.map(_.getString(0)) should be(Array("edge-1", "edge-2", "edge-3", "edge-4"))

    val byId = rows.map(r => r.getString(0) -> r).toMap
    Seq("edge-2", "edge-3").foreach { id =>
      withClue(s"$id: ") {
        byId(id).getString(1) should be(null)
        byId(id).getString(2) should be(null)
        // 行没被丢掉：其余列照常有值
        byId(id).getString(3) should be("user_registered")
      }
    }

    // 正对照：能切的两行必须真的有值（不是「一律 NULL」的假绿）
    Seq("edge-1", "edge-4").foreach { id =>
      withClue(s"$id: ") {
        byId(id).getString(1) should be(InnerJson)
        byId(id).getString(2) should be(JsonObjectSlicer.sha256Hex(InnerJson))
      }
    }
  }

  it should "A6b 相同 payload 的 hash 相同但两行都在（hash 仅诊断，不是去重键）" in {
    val hashes = spark.sql(
      s"""SELECT payload_hash, COUNT(*) c FROM ${ns.ods}.ods_user_event
         |WHERE payload_hash IS NOT NULL GROUP BY payload_hash""".stripMargin).collect()
    hashes.length should be(1)
    hashes(0).getLong(1) should be(2L)
    hashes(0).getString(0) should be(JsonObjectSlicer.sha256Hex(InnerJson))
  }

  it should "A7b source_system 全表等于注入值；raw_source_system 保留行内差异（row-src/other-src）" in {
    val distinct = spark.sql(
      s"SELECT DISTINCT source_system FROM ${ns.ods}.ods_user_event").collect()
      .map(_.getString(0))
    distinct should be(Array(sourceSystem))

    val raw = spark.sql(
      s"SELECT DISTINCT raw_source_system FROM ${ns.ods}.ods_user_event ORDER BY 1").collect()
      .map(_.getString(0))
    raw.toSet should be(Set("row-src", "other-src"))
  }

  it should "A8b 五行 landing_file 都指向受控夹具文件，source_file 与之相等" in {
    val rows = spark.sql(
      s"SELECT DISTINCT landing_file, source_file FROM ${ns.ods}.ods_user_event").collect()
    rows.length should be(1)
    rows(0).getString(0) should include("edge-20260902.jsonl")
    rows(0).getString(0) should not be "landing"
    rows(0).getString(1) should be(rows(0).getString(0))
  }
}
