package com.graduation.analytics

import com.graduation.analytics.sql.EventLandingSchema
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * RED-PHASE PROBE ②（临时，交付前删）：
 * 为 ODS v2 的**两个未实测分支**取事实，避免实现时拍脑袋：
 *  P1 `read.text` + `_metadata.file_path` 在隔离仓库下的实际取值（含 `file:///` 形态）；
 *  P2 `from_json` 把 payload 解析成**字符串列**后，再对字符串跑 `from_json(…, DecimalType)`
 *     是否与「闭合 schema 直接解析」得到同一结果（v1 口径不得改变）；
 *  P3 `SELECT *` 派生表在加列后的下游投影是否仍只取具名列（DWD/DIM 不受牵连）。
 *
 * 本文件不是验收断言。
 */
class P2Probe2Spec extends AnyFlatSpec with Matchers {

  private def withSpark(body: SparkSession => Unit): Unit = {
    val spark = P2TestSupport.spark("p2-probe-2")
    try body(spark) finally P2TestSupport.stop(spark)
  }

  private val payloadStruct: StructType = {
    val f = EventLandingSchema.structType.fields.find(_.name == "payload").get
    f.dataType.asInstanceOf[StructType]
  }

  "PROBE2" should "P1 实测 read.text + _metadata.file_path 的取值形态" in {
    withSpark { spark =>
      val df = spark.read.text(P2TestSupport.goldenUri)
        .withColumn("landing_file", col("_metadata.file_path"))
      println("[PROBE2] P1 rows=" + df.count())
      df.select("landing_file").distinct().collect().foreach { r =>
        println("[PROBE2] P1 _metadata.file_path = [" + r.getString(0) + "]")
      }
      println("[PROBE2] P1 _metadata.file_name = [" +
        df.select(col("_metadata.file_name")).collect()(0).getString(0) + "]")
      println("[PROBE2] P1 input_file_name()    = [" +
        df.select(input_file_name()).collect()(0).getString(0) + "]")

      val rel = spark.read.text("tests/golden-dataset/events")
      println("[PROBE2] P1 relative-input _metadata.file_path = [" +
        rel.select(col("_metadata.file_path")).collect()(0).getString(0) + "]")
    }
  }

  it should "P2 实测 from_json 两级解析 vs 闭合 schema 一级解析是否等价" in {
    withSpark { spark =>
      // 一级：与现状 EventOdsLoadJob 完全相同的读法
      val direct = spark.read.schema(EventLandingSchema.structType).json(P2TestSupport.goldenUri)
        .filter(col("schema_version") === "1.0")
        .select(col("payload.price").as("p_price"),
          col("payload.amount").as("p_amount"),
          col("payload.total_amount").as("p_total"),
          col("payload.items").as("p_items"),
          col("payload.order_id").as("p_order"))

      // 两级：text → 外层 from_json(带 raw payload 字符串) → 内层 from_json(payload 字符串, 原 structType)
      val envelope = StructType(Seq(
        StructField("event_id", StringType),
        StructField("event_type", StringType),
        StructField("event_time", StringType),
        StructField("ingest_time", StringType),
        StructField("source_system", StringType),
        StructField("schema_version", StringType),
        StructField("trace_id", StringType),
        StructField("payload", StringType)))
      val twoStage = spark.read.text(P2TestSupport.goldenUri)
        .select(from_json(col("value"), envelope).as("e"))
        .select(col("e.*"))
        .filter(col("schema_version") === "1.0")
        .select(from_json(col("payload"), payloadStruct).as("p"))
        .select(col("p.price").as("p_price"),
          col("p.amount").as("p_amount"),
          col("p.total_amount").as("p_total"),
          col("p.items").as("p_items"),
          col("p.order_id").as("p_order"))

      println("[PROBE2] P2 direct schema   = " + direct.schema.treeString.replace('\n', ' '))
      println("[PROBE2] P2 twoStage schema = " + twoStage.schema.treeString.replace('\n', ' '))

      val a = direct.collect().map(_.toString).sorted
      val b = twoStage.collect().map(_.toString).sorted
      println("[PROBE2] P2 direct   rows=" + a.length + " count=" + direct.count())
      println("[PROBE2] P2 twoStage rows=" + b.length + " count=" + twoStage.count())
      println("[PROBE2] P2 EQUAL_RAW=" + (a.sameElements(b)))
      if (!a.sameElements(b)) {
        a.zipAll(b, "<none>", "<none>").filter { case (x, y) => x != y }.take(10)
          .foreach { case (x, y) => println(s"[PROBE2] P2 DIFF direct=$x  twoStage=$y") }
      }
      // 关键单点：DECIMAL 列在两条路径下的类型/取值
      println("[PROBE2] P2 direct   price sample = " +
        direct.filter(col("p_price").isNotNull).select("p_price").collect().take(3).mkString(" | "))
      println("[PROBE2] P2 twoStage price sample = " +
        twoStage.filter(col("p_price").isNotNull).select("p_price").collect().take(3).mkString(" | "))
      // items 类型
      println("[PROBE2] P2 direct   items type = " + direct.schema("p_items").dataType)
      println("[PROBE2] P2 twoStage items type = " + twoStage.schema("p_items").dataType)
    }
  }

  it should "P3 实测 payload 字符串两级解析的字节保真与切片一致性" in {
    withSpark { spark =>
      val envelope = StructType(Seq(
        StructField("event_id", StringType),
        StructField("event_type", StringType),
        StructField("event_time", StringType),
        StructField("ingest_time", StringType),
        StructField("source_system", StringType),
        StructField("schema_version", StringType),
        StructField("trace_id", StringType),
        StructField("payload", StringType)))
      val rows = spark.read.text(P2TestSupport.goldenUri)
        .select(from_json(col("value"), envelope).as("e"), col("value").as("raw_line"))
        .select(col("e.*"), col("raw_line"))
        .filter(col("schema_version") === "1.0")
        .collect()
      println("[PROBE2] P3 accepted rows = " + rows.length)
      val line1 = rows.head
      val payloadStr = line1.getAs[String]("payload")
      val rawLine = line1.getAs[String]("raw_line")
      val sliced = com.graduation.analytics.sql.JsonObjectSlicer.slice(rawLine)
      val oracle = "385dee5b723e23f0778d6726140ced55b5e9f5aa45f79d42869b1f753e260445"
      println("[PROBE2] P3 from_json(payload) bytes = " +
        payloadStr.getBytes("UTF-8").length + " sha256=" +
        com.graduation.analytics.sql.JsonObjectSlicer.sha256Hex(payloadStr))
      println("[PROBE2] P3 slicer(raw_line)  bytes = " +
        sliced.right.get.getBytes("UTF-8").length + " sha256=" +
        com.graduation.analytics.sql.JsonObjectSlicer.sha256Hex(sliced.right.get))
      println("[PROBE2] P3 EQUAL=" + (payloadStr == sliced.right.get) +
        " MATCH_ORACLE=" + (com.graduation.analytics.sql.JsonObjectSlicer.sha256Hex(payloadStr) == oracle))

      // 缺 payload 的行（golden 里 0 行）→ from_json 的 payload 字符串是否为 null
      val nulls = rows.count(r => r.getAs[String]("payload") == null)
      println("[PROBE2] P3 payload==null rows = " + nulls)
      // 非对象 payload 的行为
      val probe = spark.createDataFrame(Seq(
        ("""{"payload":{"a":1}}""", "obj"),
        ("""{"payload":"str"}""", "str"),
        ("""{"payload":123}""", "num"),
        ("""{"payload":[1,2]}""", "arr"),
        ("""{"payload":null}""", "null"),
        ("""{"a":1}""", "absent"))).toDF("value", "kind")
        .select(col("kind"),
          from_json(col("value"), envelope).getField("payload").as("p"))
      probe.collect().foreach(r =>
        println("[PROBE2] P3 kind=" + r.getString(0) + " -> payload=[" + r.getString(1) + "]"))
    }
  }
}
