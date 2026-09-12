package com.graduation.analytics.sql

import java.nio.charset.StandardCharsets
import java.util.Locale

import com.graduation.analytics.{P2TestSupport, SurrogateKeyVectorSupport}
import com.graduation.analytics.SurrogateKeyVectorSupport.{keyExprTestFixture, sqlLiteral}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.apache.spark.sql.SparkSession
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * P2-03 E2：代理键契约对账（Scala 侧）。
 *
 * **语义唯一所有者 = 契约文件**：本套件通过 `SurrogateKeyVectorSupport` **运行时读**
 * `contract-specs/specs/surrogate-key.v1.json`（23 向量），断言里**不内嵌任何期望值**。
 * 契约 `status = DRAFT-2026-09-12` ⇒ 本套件通过**不等于**「契约已冻结」。
 *
 * 覆盖（对应施工单 A6 的 ①–⑤）：
 *  A6① 契约全 23 向量在 Scala 侧复算命中（含 3 条 `expect=NULL_KEY`）
 *  A6② category/brand/coupon 向量（仅单测覆盖，真链无数据 —— A7）
 *  A6③ 空/缺 ⇒ NULL 且不丢行（负例）
 *  A6④ 同一三元组恒同键、不同三元组不同键（幂等与单射）
 *  A6⑤ 旧列与新列同表共存、旧列取值不变（防回归）
 *  以及 SQL 侧（真实 Spark）逐向量复算：证明 `SurrogateKey.toBIGINT` 与内存实现**同值**。
 *
 * 数据安全：本套件用 `P2TestSupport.spark` 的**隔离 warehouse**（`D:/Develop/tmp/p2-01-warehouse/…`），
 * 绝不写真实 `spark-warehouse`；黄金夹具只读。
 */
class SurrogateKeySpec extends AnyFlatSpec with Matchers {

  private val vectors = SurrogateKeyVectorSupport.vectors

  /** 契约里实体的封闭枚举（D-083）；`order` 不在其中（D-093） */
  private val enum = SurrogateKeyVectorSupport.entityEnum

  // ------------------------------------------------------------------
  // A6① / A6②：23 向量逐条复算
  // ------------------------------------------------------------------

  "契约向量集" should "可被读到且条数为 23（含 3 条 NULL_KEY）" in {
    vectors.size shouldBe 23
    vectors.map(_.id) shouldBe (1 to 23).map(i => f"V$i%02d")
    vectors.count(_.expect == "NULL_KEY") shouldBe 3
    enum shouldBe Seq("user", "product", "category", "brand", "coupon")
    withClue("契约 status 仍为 DRAFT，不得声称已冻结：") {
      SurrogateKeyVectorSupport.specStatus shouldBe "DRAFT-2026-09-12"
    }
  }

  it should "每一条 KEY 向量：normalized / material / sha256 / keyHex16 / keyBigint 全部命中" in {
    val keyed = vectors.filter(_.expect == "KEY")
    keyed.size shouldBe 20
    keyed.foreach { v =>
      withClue(s"${v.id}(${v.kind}) ") {
        // 归一化
        SurrogateKey.normalize(v.rawInput) shouldBe v.normalized
        // 材料（source 取契约该行的 source，如 V16 的 other-src）
        SurrogateKey.material(v.source, v.entity, SurrogateKey.normalize(v.rawInput)) shouldBe v.material
        // 全文摘要
        val d = SurrogateKey.sha256(v.source, v.entity, SurrogateKey.normalize(v.rawInput))
        d.map(b => f"${b & 0xFF}%02x").mkString shouldBe v.sha256
        // 摘要前 8 字节原值（未清符号位）——**与 keyHex16 故意不同**的载体口径（D-088）
        SurrogateKey.digestPrefixHex16(d) shouldBe v.digestPrefixHex16
        // 最终键值
        val key = SurrogateKey.derive(v.source, v.entity, v.rawInput).get
        SurrogateKey.keyHex16(key) shouldBe v.keyHex16.get
        key shouldBe v.keyBigint.get
      }
    }
  }

  it should "每一条 NULL_KEY 向量：不生成键（None），且契约 rawInput 的三种区分靠 rawInputNotation" in {
    // 契约把 V13/V14/V15 的 `rawInput` 统一写成 JSON null（载体歧义，判据缺陷 B 的残留），
    // 三者的区分在 `kind`/`rawInputNotation`：空串 / 仅空白 / 真 null。
    // 本断言对**三种真实输入**都要求「不生成键」。散文记号不再进入判据（expect 才是判据）。
    val nullVectors = vectors.filter(_.expect == "NULL_KEY")
    nullVectors.map(_.kind).sorted shouldBe Seq("null", "仅空白", "空串").sorted
    Seq(
      "V13" -> "",          // 空串
      "V14" -> "   ",       // 仅空白
      "V15" -> null         // 真 null
    ).foreach { case (id, raw) =>
      val v = nullVectors.find(_.id == id).get
      withClue(s"$id(${v.kind}) 应不生成键：") {
        SurrogateKey.derive(v.source, v.entity, raw) shouldBe None
        // 归一化如实反映输入（空串 → ""；只有 null 才 → null），**判据是 isMissing**，
        // 不是「normalize 必须返回 null」——后者会把空串与 null 混为一谈。
        SurrogateKey.isMissing(SurrogateKey.normalize(raw)) shouldBe true
      }
    }
    SurrogateKey.normalize("") shouldBe ""
    SurrogateKey.normalize("   ") shouldBe ""
    SurrogateKey.normalize(null) shouldBe null
    // 反面控制：若把 `(NULL)` 这类散文记号当字面值，就会凭空生成一个键（总控首轮复核踩过的坑）
    SurrogateKey.derive("mock-mall", "user", "(NULL)") shouldBe defined
  }

  // ------------------------------------------------------------------
  // A6②：非 user/product 实体（真链无数据，仅单测覆盖 —— A7）
  // ------------------------------------------------------------------

  it should "category / brand / coupon 向量复算命中（仅单测可达，真链无数据）" in {
    // 契约 23 向量里真链可达的实体只有 user/product；category/brand/coupon 走同一算法，
    // 这里用**契约的同一 material 公式**构造三类实体的探针，并交叉验证与契约向量的关系。
    val src = "mock-mall"
    Seq("category", "brand", "coupon").foreach { e =>
      withClue(s"$e: ") {
        SurrogateKey.EntityEnum should contain(e)
        val probe = SurrogateKey.derive(src, e, "C0001").get
        probe should be >= 1L
        // 同一 raw id、不同实体 ⇒ 不同键（A6④ 单射性的一部分）
        SurrogateKey.derive(src, e, "C0001").get shouldBe probe
        SurrogateKey.derive(src, "user", "C0001").get should not be probe
        // 材料公式与契约一致
        SurrogateKey.material(src, e, "C0001") shouldBe s"$src|$e|C0001"
      }
    }
    // 契约里 category/brand 只有 material 公式上的意义；用 V17 的模式（同 raw 不同 entity）钉住
    val v17 = vectors.find(_.id == "V17").get
    SurrogateKey.derive(v17.source, v17.entity, v17.rawInput).get shouldBe v17.keyBigint.get
  }

  it should "契约枚举外的实体一律拒绝（D-093：order 不得套用代理键算法）" in {
    val ex = intercept[IllegalArgumentException] {
      SurrogateKey.keyExpr("'mock-mall'", "order", "t.order_id")
    }
    ex.getMessage should include("order")
    SurrogateKey.EntityEnum should not contain "order"
    // 契约 23 向量里有 3 条用**枚举外**实体（V06 order / V20 payment / V21 refund，见契约 `openItems`
    // 「实体枚举待补」），它们各有独立的 source|entity|raw 三元组，故**不减少**单射组数。
    // 「算法在该输入上的取值」仍必须与契约同值（这是哈希函数的事实），
    // 但**不构成任何可落列的实体**——生产路径 `keyExpr` 对这三个实体一律抛错（见上）。
    Map("V06" -> "order", "V20" -> "payment", "V21" -> "refund").foreach { case (id, ent) =>
      val v = vectors.find(_.id == id).get
      v.entity shouldBe ent
      SurrogateKey.EntityEnum should not contain ent
      SurrogateKey.derive(v.source, v.entity, v.rawInput).get shouldBe v.keyBigint.get
      SurrogateKey.material(v.source, v.entity, SurrogateKey.normalize(v.rawInput)) shouldBe v.material
      val thrown = intercept[IllegalArgumentException] {
        SurrogateKey.keyExpr("'mock-mall'", ent, "t.x")
      }
      thrown.getMessage should include(ent)
    }
  }

  // ------------------------------------------------------------------
  // A6④：幂等与单射
  // ------------------------------------------------------------------

  it should "同一三元组恒同键（幂等），不同三元组不同键（单射，17 组无碰撞）" in {
    val keyed = vectors.filter(_.expect == "KEY")
    val triples = keyed.map(v => (v.source, v.entity, SurrogateKey.normalize(v.rawInput)))
    // **实测读数（不是估计）**：20 条 KEY 向量落在 17 个不同三元组上——
    // V01=V12（UUID 大小写，同为 89A6DB2C-…）、V04=V07=V08（前缀 id 的大小写与首尾空格）
    // 共用同一个三元组，其余 17 条各自独立（含 3 条枚举外实体 order/payment/refund，
    // 它们各有独立三元组，故**不减少**组数；能否落列是另一件事，见枚举拒绝用例）。
    triples.distinct.size shouldBe 17
    triples.size shouldBe 20
    val keys = triples.distinct.map { case (s, e, n) => SurrogateKey.derive(s, e, n).get }
    keys.distinct.size shouldBe keys.size // 无碰撞
    keys.foreach { k => k should be >= 1L; k should not be -1L }
    // 恒同键：重复三元组必得同键
    keyed.foreach { v =>
      SurrogateKey.derive(v.source, v.entity, v.rawInput).get shouldBe v.keyBigint.get
    }
  }

  it should "键恒在 [1, 2^63-1]：清符号位 + 全零取 1（D-085）" in {
    val d = new Array[Byte](32)
    SurrogateKey.keyFromDigest(d) shouldBe 1L                       // 全零摘要 → 1（0 键被禁止）
    val neg = Array[Byte](0x80.toByte) ++ Array.fill[Byte](31)(0)
    SurrogateKey.keyFromDigest(neg) shouldBe 1L                     // 清符号位后为 0 → 1
    val hi = Array[Byte](0xFF.toByte) ++ Array.fill[Byte](31)(0xFF.toByte)
    SurrogateKey.keyFromDigest(hi) shouldBe Long.MaxValue           // 上界：只清最高 1 位，其余 63 位保留
    SurrogateKey.SignBitClearMask shouldBe Long.MaxValue            // 掩码位宽正是 63（曾误用 0x7F）
    // 单比特上界探针：0x8000…00 清符号位后 = 0 → 1；0xC000…00 清符号位后 = 0x4000…00
    SurrogateKey.keyFromDigest(Array[Byte](0xC0.toByte) ++ Array.fill[Byte](31)(0)) shouldBe
      (Long.MaxValue / 2 + 1)
    vectors.filter(_.expect == "KEY").foreach { v =>
      val k = SurrogateKey.derive(v.source, v.entity, v.rawInput).get
      withClue(s"${v.id}: ") { k should be > 0L; k should be <= Long.MaxValue }
    }
  }

  // ------------------------------------------------------------------
  // SQL 侧同值（真实 Spark，隔离 warehouse）
  // ------------------------------------------------------------------

  "SurrogateKey.toBIGINT（SQL 表达式）" should "在真实 Spark 上逐向量与内存实现同值" in {
    val spark = P2TestSupport.spark("surrogate-key-vectors")
    try {
      // **为什么不走 `SurrogateKey.keyExpr`**：契约 23 向量里有 3 条用枚举外实体
      // （V06 `order` / V20 `payment` / V21 `refund`），而 `keyExpr` 带生产护栏、
      // 对它们**必须抛错**。要逐向量验证「SQL 与内存同值」，只能经测试夹具的裸公式；
      // 生产护栏由 `契约枚举外的实体一律拒绝` 用例单独钉死，二者不可互相替代。
      // 夹具公式与生产实现是否同值，由下面第一个断言在同一 Spark 会话里直接比对。
      val rows = vectors.map(v => (v.id, v.source, v.entity, v.rawInput))
      val exprs = rows.map { case (id, s, e, raw) =>
        s"SELECT '$id' AS id, ${keyExprTestFixture(sqlLiteral(s), e, sqlLiteral(raw))} AS k"
      }.mkString(" UNION ALL ")

      val got = spark.sql(exprs).collect().map(r => r.getString(0) -> Option(r.get(1))).toMap
      // 逐向量**主动打印**读数（证据留存）：否则「23/23 命中」只存在于断言里，
      // 日志里看不到任何数字，父侧无法只读复算。
      println("[vector] id  | kind           | expect   | SQL                  | contract")
      vectors.foreach { v =>
        val expect = if (v.expect == "NULL_KEY") None else v.keyBigint.map(_.asInstanceOf[AnyRef])
        println(f"[vector] ${v.id}%-3s | ${v.kind}%-14s | ${v.expect}%-8s | " +
          f"${got(v.id).map(_.toString).getOrElse("NULL")}%-20s | " +
          f"${expect.map(_.toString).getOrElse("NULL")}")
        withClue(s"${v.id}(${v.kind}) SQL 侧 vs 契约：") {
          got(v.id).map(_.asInstanceOf[Long]) shouldBe expect.map(_.asInstanceOf[Long])
        }
      }

      // 夹具公式 ≡ 生产实现：对**枚举内**向量两种表达式必须给出同一个键（防夹具漂移）
      val inEnum = vectors.filter(v => SurrogateKey.EntityEnum.contains(v.entity)
        && v.expect == "KEY")
      inEnum.size should be > 15 // 23 向量中枚举内占多数；实测枚举外恰 3 条
      val pairs = inEnum.map { v =>
        s"""SELECT '${v.id}' AS id,
           |  ${SurrogateKey.keyExpr(sqlLiteral(v.source), v.entity, sqlLiteral(v.rawInput))} AS prod,
           |  ${keyExprTestFixture(sqlLiteral(v.source), v.entity, sqlLiteral(v.rawInput))} AS fixture
           |""".stripMargin
      }.mkString(" UNION ALL ")
      spark.sql(pairs).collect().foreach { r =>
        withClue(s"${r.getString(0)} 生产表达式 vs 测试夹具：") {
          r.getLong(1) shouldBe r.getLong(2)
        }
      }
    } finally P2TestSupport.stop(spark)
  }

  it should "空串 / 仅空白 / NULL 三种输入在 SQL 侧都得到 NULL（不落哨兵、不合并）" in {
    val spark = P2TestSupport.spark("surrogate-key-empty")
    try {
      val src = "mock-mall"
      // **实测缺陷（第一轮 E2）**：早先写成 `toBIGINT("$src", …)` —— 第一个形参是
      // **列/表达式**，传进去的 `'mock-mall'`（含引号）被判为「表达式」后原样进 CASE guard，
      // 生成 `CASE WHEN 'mock-mall' IS NULL` ⇒ `PARSE_SYNTAX_ERROR`。
      // 「源编码是常量」必须走 `literalSource`（它负责渲染字面量并做非空校验）。
      val sql = s"""SELECT
           |  ${SurrogateKey.literalSource(src, "user", "''")} AS empty_str,
           |  ${SurrogateKey.literalSource(src, "user", "'   '")} AS blank,
           |  ${SurrogateKey.literalSource(src, "user", "CAST(NULL AS STRING)")} AS null_raw,
           |  ${SurrogateKey.toBIGINT("CAST(NULL AS STRING)", "user", "'U1'")} AS null_src,
           |  ${SurrogateKey.literalSource(src, "user", "'U000065'")} AS present
           |""".stripMargin
      val r = spark.sql(sql).collect()(0)
      for (i <- 0 until 4) withClue(s"列 $i 应为 NULL：") { r.isNullAt(i) shouldBe true }
      r.isNullAt(4) shouldBe false
      // 与契约 V04 同值（源/实体/归一化 id 三元组相同）
      r.getLong(4) shouldBe vectors.find(_.id == "V04").get.keyBigint.get
      // 常量源入口（literalSource）不做 NULL 传播：空源编码是管道故障，必须当场抛错
      val ex = intercept[IllegalArgumentException] { SurrogateKey.literalSource("  ", "user", "'U1'") }
      ex.getMessage should include("sourceSystem")
    } finally P2TestSupport.stop(spark)
  }

  // ------------------------------------------------------------------
  // A6③ + A6⑤：真实 SQL 链（隔离 warehouse，黄金 55 条只读）
  // ------------------------------------------------------------------

  /**
   * A6⑤ + A6③ 的真实链：建 ODS 表 → 载入黄金 55 条 → 跑 `DimSql` / `DwdSql` 真 SQL
   * → 断言「旧列与新列同表共存」「旧列取值不变」「空 id 行 ⇒ 新键 NULL 且不丢行」。
   *
   * 隔离纪律：warehouse 在 `D:/Develop/tmp/…`；黄金夹具**只读**（`P2TestSupport.goldenUri`）。
   * 本用例承载「**E3-lite 真实链冒烟**」的证据：真实 Spark 本地模式 + 真实 parquet 落盘 + 真实 SQL。
   */
  "代理键真链（隔离 warehouse + 黄金 55 条）" should "旧列不变且新键列与契约同值（A6③/A6⑤）" in {
    val spark: SparkSession = P2TestSupport.spark("surrogate-key-chain")
    try {
      val ns = WarehouseNamespace.defaultNamespace // dw_ods/dw_dwd/dw_dim（测试域内存 catalog）
      import spark.implicits._

      // 1) 建表：直接复用生产建表语句的唯一所有者（含 P2-03 新增列）
      com.graduation.analytics.job.LocalSchemaInitJob.statements(ns).foreach { case (_, s) =>
        spark.sql(s)
      }
      // 2) 黄金 55 条 → 显式 Schema 的 landing 视图（只读绝对路径；与 `EventOdsLoadJob` 同口径）
      val lines = P2TestSupport.goldenLines
      lines.size shouldBe 55
      // UDF 注册复用生产所有者（幂等），测试内**不**另写切片/哈希实现
      com.graduation.analytics.job.EventOdsLoadJob.registerUdfs(spark)
      val landing = spark.read.textFile(P2TestSupport.goldenUri)
      landing.createOrReplaceTempView("landing")
      import org.apache.spark.sql.functions.{col, from_json, input_file_name}
      // 与 `EventOdsLoadJob.run` 第 88–102 行同一投影（闭合 schema 一次解析 + 原文行 + 落地文件）
      val projected = landing
        .select(from_json(col("value"), com.graduation.analytics.sql.OdsLoadSql.landingSchema).as("e"),
          col("value").as(com.graduation.analytics.sql.OdsLoadSql.ColRawLine))
        .select(col("e.*"), col(com.graduation.analytics.sql.OdsLoadSql.ColRawLine))
        .withColumn(com.graduation.analytics.sql.OdsLoadSql.ColLandingFile, input_file_name())
      val valid = projected.filter(
        col("schema_version") === "1.0" &&
          col("event_id").isNotNull && col("event_type").isNotNull && col("event_time").isNotNull)
      valid.createOrReplaceTempView(com.graduation.analytics.sql.OdsLoadSql.LANDING_VIEW)

      // 3) 用生产 ODS 模板落到 ODS（真实 INSERT，真实 parquet）
      val batchId = 9001L
      spark.sql(com.graduation.analytics.sql.OdsLoadSql.userFromLanding(ns, "mock-mall", batchId))
      spark.sql(com.graduation.analytics.sql.OdsLoadSql.productFromLanding(ns, "mock-mall", batchId))
      spark.sql(com.graduation.analytics.sql.OdsLoadSql.behaviorFromLanding(ns, "mock-mall", batchId))
      spark.sql(com.graduation.analytics.sql.OdsLoadSql.tradeFromLanding(ns, "mock-mall", batchId))
      spark.sql(s"SELECT COUNT(*) FROM ${ns.ods}.ods_behavior_event").collect()(0).getLong(0) should be > 0L

      // 4) 真实 DIM / DWD SQL
      spark.sql(DimSql.userSnapshot(ns, "20260901"))
      spark.sql(DimSql.productSnapshot(ns, "20260901"))
      val dwdSql = DwdSql.behaviorClean(ns, "20260901")
      spark.sql(dwdSql)

      // 5) A6⑤：旧列与新列在**同一张表**里共存，且旧列取值与旧口径一致
      val dwd = spark.table(ns.table("dwd", "dwd_user_behavior_detail"))
      dwd.columns should contain allOf ("user_id", "product_id", "category_id",
        "user_key", "product_key", "category_key")
      val dimU = spark.table(ns.table("dim", "dim_user"))
      dimU.columns should contain allOf ("user_id", "user_key")
      val dimP = spark.table(ns.table("dim", "dim_product"))
      dimP.columns should contain allOf ("product_id", "category_id", "brand_id",
        "product_key", "category_key", "parent_category_key", "brand_key")

      // 旧列 = 旧口径（黄金集 user_id 是纯数字 1..N ⇒ IdCodec 原样通过；不变式仍由本断言钉住）
      val oldVsRaw = spark.sql(
        s"""SELECT COUNT(*) AS c FROM ${ns.dwd}.dwd_user_behavior_detail b
           |JOIN ${ns.ods}.ods_behavior_event o
           |  ON o.event_id = b.behavior_id
           |WHERE CAST(o.payload_user_id AS BIGINT) <> b.user_id""".stripMargin).collect()(0).getLong(0)
      oldVsRaw shouldBe 0L

      // 新列 = 契约口径（用 SQL 从 ODS 原始 id 复算，与落库值逐行比对）
      val mismatch = spark.sql(
        s"""SELECT COUNT(*) AS c
           |FROM ${ns.dwd}.dwd_user_behavior_detail b
           |JOIN ${ns.ods}.ods_behavior_event o ON o.event_id = b.behavior_id
           |WHERE b.user_key IS DISTINCT FROM ${SurrogateKey.toBIGINT("o.source_system", "user", "o.payload_user_id")}
           |   OR b.product_key IS DISTINCT FROM ${SurrogateKey.toBIGINT("o.source_system", "product", "o.payload_product_id")}
           |""".stripMargin).collect()(0).getLong(0)
      mismatch shouldBe 0L

      // 新键与旧键**必须不同域**（这是 P2-03 换源不合并的立论基础，实测而非推断）
      val sameDomain = spark.sql(
        s"""SELECT COUNT(*) AS c FROM ${ns.dwd}.dwd_user_behavior_detail
           |WHERE user_key = user_id""".stripMargin).collect()(0).getLong(0)
      sameDomain shouldBe 0L

      // 5b) **A5 等价性实测**：旧口径 JOIN（`u.user_id`）与新口径 JOIN（`u.user_key`）在
      //     **同一批 ODS 行**上必须产出**相同的行数**。
      //
      // 本轮 A5 的授权是「JOIN 谓词保持旧口径不变」（改 JOIN 属 P2-04）——所以这里
      // **不是**去改 `DwdSql:51/52`，而是**量出**「若将来换成新键会不会丢行/膨胀」，
      // 给 P2-04 留下可复算的基线。跑不出来就必须写「未实测」，不得用推断代替。
      //
      // 为什么可能不等：新键在空/缺 id 时是 NULL，而 NULL 不匹配任何行 ⇒ 维表补不上；
      // 但 `behaviorClean` 第 54/55 行已过滤 `payload_*_id IS NOT NULL`，
      // 且黄金集 id 是纯数字（`IdCodec` 原样通过）⇒ 预期**逐行相等**。这是要证的东西。
      val joinOld = spark.sql(
        s"""SELECT COUNT(*) AS c
           |FROM ${ns.ods}.ods_behavior_event rn
           |LEFT JOIN ${ns.dim}.dim_user u
           |  ON u.user_id = ${com.graduation.analytics.sql.IdCodec.toBIGINT("rn.payload_user_id")}
           | AND u.dt = '20260901'
           |LEFT JOIN ${ns.dim}.dim_product p
           |  ON p.product_id = ${com.graduation.analytics.sql.IdCodec.toBIGINT("rn.payload_product_id")}
           | AND p.dt = '20260901'
           |WHERE rn.dt = '20260901' AND rn.schema_version = '1.0'
           |  AND rn.payload_user_id IS NOT NULL AND rn.payload_product_id IS NOT NULL
           |  AND rn.payload_behavior_type IN ('view','favorite','cart_add','cart_remove','search')
           |""".stripMargin).collect()(0).getLong(0)
      val joinNew = spark.sql(
        s"""SELECT COUNT(*) AS c
           |FROM ${ns.ods}.ods_behavior_event rn
           |LEFT JOIN ${ns.dim}.dim_user u
           |  ON u.user_key = ${SurrogateKey.toBIGINT("rn.source_system", "user", "rn.payload_user_id")}
           | AND u.dt = '20260901'
           |LEFT JOIN ${ns.dim}.dim_product p
           |  ON p.product_key = ${SurrogateKey.toBIGINT("rn.source_system", "product", "rn.payload_product_id")}
           | AND p.dt = '20260901'
           |WHERE rn.dt = '20260901' AND rn.schema_version = '1.0'
           |  AND rn.payload_user_id IS NOT NULL AND rn.payload_product_id IS NOT NULL
           |  AND rn.payload_behavior_type IN ('view','favorite','cart_add','cart_remove','search')
           |""".stripMargin).collect()(0).getLong(0)
      // 阳性对照：证明这两条 SQL 真读到了数据（不是空表对空表得 0 = 0）
      joinOld should be > 0L
      // 读数**主动打印**（证据留存）：否则「相等」无法在日志里被父侧复算
      println(s"[A5] 旧口径 JOIN 行数 = $joinOld")
      println(s"[A5] 新口径 JOIN 行数 = $joinNew")
      withClue(s"A5 等价性：旧 JOIN 行数=$joinOld，新 JOIN 行数=$joinNew；两者必须相等") {
        joinNew shouldBe joinOld
      }
      // 补一条**维表命中**等价性（行数相等还不够：两侧都没补上维表也会相等）
      def dimHits(keyCol: String, keyExpr: String): Long = spark.sql(
        s"""SELECT COUNT(*) AS c
           |FROM ${ns.ods}.ods_behavior_event rn
           |LEFT JOIN ${ns.dim}.dim_user u
           |  ON u.$keyCol = $keyExpr AND u.dt = '20260901'
           |WHERE rn.dt = '20260901' AND rn.schema_version = '1.0'
           |  AND rn.payload_user_id IS NOT NULL
           |  AND u.$keyCol IS NOT NULL
           |""".stripMargin).collect()(0).getLong(0)
      val hitsOld = dimHits("user_id",
        com.graduation.analytics.sql.IdCodec.toBIGINT("rn.payload_user_id"))
      val hitsNew = dimHits("user_key",
        SurrogateKey.toBIGINT("rn.source_system", "user", "rn.payload_user_id"))
      withClue(s"A5 维表命中：旧=$hitsOld 新=$hitsNew") { hitsNew shouldBe hitsOld }
      println(s"[A5] 旧口径维表命中行数 = $hitsOld")
      println(s"[A5] 新口径维表命中行数 = $hitsNew")

      // 5c) **A7 真链覆盖实测**：五实体里哪几个在真链上真的取到了**非 NULL 键**。
      //
      // 为什么必须实测：我一度把 A7 写成「category/brand/coupon 零命中」——那是错的，
      // 实测 `DimSql.scala:76/77/78` **有**生产调用点。真正的问题不是「没写」，
      // 而是「写了但真链上取不到值」。这两件事的处置完全不同，不能混为一谈。
      val cov = spark.sql(
        s"""SELECT
           |  COUNT(*) AS rows_total,
           |  SUM(CASE WHEN product_key       IS NOT NULL THEN 1 ELSE 0 END) AS k_product,
           |  SUM(CASE WHEN category_key      IS NOT NULL THEN 1 ELSE 0 END) AS k_category,
           |  SUM(CASE WHEN parent_category_key IS NOT NULL THEN 1 ELSE 0 END) AS k_parent_category,
           |  SUM(CASE WHEN brand_key         IS NOT NULL THEN 1 ELSE 0 END) AS k_brand
           |FROM ${ns.dim}.dim_product WHERE dt = '20260901'""".stripMargin).collect()(0)
      println(s"[A7] dim_product 行数 = ${cov.getLong(0)}")
      println(s"[A7] product_key        非 NULL = ${cov.getLong(1)}")
      println(s"[A7] category_key       非 NULL = ${cov.getLong(2)}")
      println(s"[A7] parent_category_key 非 NULL = ${cov.getLong(3)}")
      println(s"[A7] brand_key          非 NULL = ${cov.getLong(4)}")
      // 阳性对照：product_key 必须 > 0，否则说明整张维表是空的、下面所有 0 都不可信
      cov.getLong(1) should be > 0L

      // 6) A6③ 负例：空/缺 raw id ⇒ 新键 NULL 且**不丢行**
      val probe = spark.sql("SELECT '   ' AS payload_user_id, 'P1' AS payload_product_id").toDF()
      probe.createOrReplaceTempView("probe_empty")
      spark.sql(
        s"""INSERT INTO ${ns.dwd}.dwd_user_behavior_detail PARTITION(dt = '20260902')
           |SELECT 'probe-evt-1' AS behavior_id,
           |       CAST(NULL AS BIGINT) AS user_id, CAST(NULL AS BIGINT) AS product_id,
           |       CAST(-1 AS BIGINT) AS category_id, 'view' AS behavior_type,
           |       CURRENT_TIMESTAMP() AS event_time, '2026-09-02' AS event_date,
           |       CAST(10 AS INT) AS event_hour, CAST(NULL AS STRING) AS city_level,
           |       'app' AS channel, 's-1' AS session_id, CAST(1 AS BIGINT) AS source_batch_id,
           |       ${SurrogateKey.literalSource("mock-mall", "user", "p.payload_user_id")} AS user_key,
           |       ${SurrogateKey.literalSource("mock-mall", "product", "p.payload_product_id")} AS product_key,
           |       CAST(NULL AS BIGINT) AS category_key
           |FROM probe_empty p""".stripMargin)
      val rows = spark.sql(
        s"""SELECT COUNT(*) AS c, SUM(CASE WHEN user_key IS NULL THEN 1 ELSE 0 END) AS null_keys
           |FROM ${ns.dwd}.dwd_user_behavior_detail WHERE dt = '20260902'""".stripMargin).collect()(0)
      rows.getLong(0) shouldBe 1L   // 不丢行
      rows.getLong(1) shouldBe 1L   // 键为 NULL
      val keyVal = spark.sql(
        s"""SELECT user_key FROM ${ns.dwd}.dwd_user_behavior_detail
           |WHERE dt = '20260902' AND behavior_id = 'probe-evt-1'""".stripMargin).collect()(0)
      keyVal.isNullAt(0) shouldBe true
      // 绝不允许把空 id 落成哨兵键（D-087 forbidden）
      spark.sql(
        s"""SELECT COUNT(*) FROM ${ns.dwd}.dwd_user_behavior_detail
           |WHERE dt = '20260902' AND (user_key = -1 OR user_key = 0)""".stripMargin)
        .collect()(0).getLong(0) shouldBe 0L
    } finally P2TestSupport.stop(spark)
  }

  // ------------------------------------------------------------------
  // 归一化的文化无关性（D-084：土耳其语 i/İ 陷阱）
  // ------------------------------------------------------------------

  it should "大写归一必须文化无关（Locale.ROOT）——土耳其语区域下不得变形" in {
    val saved = Locale.getDefault
    try {
      Locale.setDefault(new Locale("tr", "TR"))
      // 无参 toUpperCase() 在 tr 区域会把 'i' → 'İ'（U+0130），与 Spark 侧 SQL upper() 分歧
      "i".toUpperCase(Locale.ROOT) shouldBe "I"
      SurrogateKey.normalize("user:u000001") shouldBe "USER:U000001"
      val v22 = vectors.find(_.id == "V22").get
      SurrogateKey.derive(v22.source, v22.entity, v22.rawInput).get shouldBe v22.keyBigint.get
      // 材料里的 UTF-8 字节数必须与编码实现一致（多字节边界：V09 中文 3 字节/字）
      SurrogateKey.materialUtf8("mock-mall", "user", "用户一").length shouldBe
        "mock-mall|user|用户一".getBytes(StandardCharsets.UTF_8).length
      // **实测读数**：`mock-mall|user|用户一` = 10 + 1 + 4 + 1 + 3×3 = 24 字节
      // （首轮我按 UTF-8 3 字节/字错算成 21，实际 24 —— 以 `getBytes` 的读数为准）。
      SurrogateKey.materialUtf8("mock-mall", "user", "用户一").length shouldBe 24
      // 与契约 V09 同值：SQL 侧 `sha2` 走 UTF-8 字节，必须与内存侧逐字节一致
      val v09 = vectors.find(_.id == "V09").get
      SurrogateKey.materialUtf8(v09.source, v09.entity, v09.normalized).length shouldBe
        v09.material.getBytes(StandardCharsets.UTF_8).length
    } finally Locale.setDefault(saved)
  }
}
