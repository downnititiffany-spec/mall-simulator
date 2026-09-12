package com.graduation.analytics

import com.graduation.analytics.job.TradeDwdJob
import com.graduation.analytics.sql.{AdsSql, DimSql, DwdSql, DwsSql, IdCodec, OdsLoadSql}
import com.graduation.analytics.warehouse.WarehouseNamespace
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * 契约字符串 id → 数仓 BIGINT 的归一化规则（DEF-05 修复，决策 B-07 候选 ② / D-023）。
 *
 * 两条断言口径：
 * 1. **规则语义**：剥前缀 → 数字；无法数字化 → `None`（SQL 侧得到 NULL，不兜底成 0）；
 * 2. **负向守卫**：五张 SQL 模板（含 `TradeDwdJob.orderDetailInsertSql`）不得再出现
 *    对契约 id 的直接 `CAST(... AS BIGINT)`——归一化只允许经 `IdCodec` 一处。
 *
 * P1-04：模板首参是库名空间，本用例用缺省命名空间（库名与改造前一致）。
 * P2-01：ODS 模板新增第二参 `sourceSystem`（平台注入值），本类统一传测试常量 `SrcSys`。
 */
class IdCodecSpec extends AnyFlatSpec with Matchers {

  private val ns = WarehouseNamespace.defaultNamespace

  /** 测试用注入值（在产必须由 `--sourceSystem` 注入） */
  private val SrcSys = "mock-mall"

  "IdCodec" should "剥掉契约 id 的字母前缀（U/P/O/C/B）并转数字" in {
    IdCodec.normalize("U000065") shouldBe Some(65L)
    IdCodec.normalize("P00030") shouldBe Some(30L)
    IdCodec.normalize("O00000001") shouldBe Some(1L)
    IdCodec.normalize("C0001") shouldBe Some(1L)
    IdCodec.normalize("B0001") shouldBe Some(1L)
  }

  it should "纯数字 id 原样通过（兼容旧夹具与商城自增主键）" in {
    IdCodec.normalize("123") shouldBe Some(123L)
    IdCodec.normalize("0007") shouldBe Some(7L)
  }

  it should "无法数字化的取值返回 None 而不是 0（不静默兜底）" in {
    IdCodec.normalize("") shouldBe None
    IdCodec.normalize("view") shouldBe None
    IdCodec.normalize("U12A") shouldBe None
    IdCodec.normalize("U-1") shouldBe None // 关键：不得解析成 -1（与维度 unknown key 哨兵冲突）
    IdCodec.normalize("-1") shouldBe None
    IdCodec.normalize("1.5") shouldBe None
    IdCodec.normalize(null) shouldBe None
  }

  it should "生成与规则一致的 SQL 表达式（全匹配形状，不匹配 → NULL）" in {
    IdCodec.toBIGINT("rn.payload_user_id") shouldBe
      "CAST(REGEXP_EXTRACT(rn.payload_user_id, '^[A-Za-z]*([0-9]+)$', 1) AS BIGINT)"
    IdCodec.IdPattern shouldBe "^[A-Za-z]*([0-9]+)$"
  }

  private val directCast = """(?i)cast\s*\(\s*[a-z_]*\.?payload_(user|product|order|category|parent_category|brand)_id\s+as\s+bigint""".r

  /** 负向守卫：任何模板都不得对契约 id 直接 CAST */
  private def assertNoDirectCast(label: String, sql: String): Unit = {
    val hits = directCast.findAllIn(sql).toList
    withClue(s"$label 仍存在对契约 id 的直接 CAST（应改用 IdCodec.toBIGINT）：$hits\n") {
      hits shouldBe Nil
    }
  }

  /** id 承接层：既不得直接 CAST，又必须真的出现归一化表达式 */
  private def assertNormalized(label: String, sql: String): Unit = {
    assertNoDirectCast(label, sql)
    withClue(s"$label 缺少 REGEXP_EXTRACT 形式的 id 归一化：\n") {
      sql.toLowerCase should include("regexp_extract(")
    }
  }

  "所有 id 承接点" should "经 IdCodec 归一化，不再直接 CAST 契约 id" in {
    assertNormalized("DwdSql.behaviorClean", DwdSql.behaviorClean(ns, "20260901"))
    assertNormalized("DimSql.userSnapshot", DimSql.userSnapshot(ns, "20260901"))
    assertNormalized("DimSql.productSnapshot", DimSql.productSnapshot(ns, "20260901"))
    assertNormalized("TradeDwdJob.orderDetailInsertSql", TradeDwdJob.orderDetailInsertSql(ns, "20260901"))
    // 下游（DWS/ADS）与 ODS 载入层不得自行转换契约 id：ODS 保留字符串原文，DWS/ADS 只读 DWD 数字键
    assertNoDirectCast("AdsSql.operationOverview", AdsSql.operationOverview(ns, "20260901"))
    assertNoDirectCast("DwsSql.userBehaviorDay", DwsSql.userBehaviorDay(ns, "20260901"))
    assertNoDirectCast("OdsLoadSql.behaviorFromLanding", OdsLoadSql.behaviorFromLanding(ns, SrcSys, 7L))
  }

  it should "行为/维度/交易三条 id 链的归一化次数与列对应正确" in {
    val behavior = DwdSql.behaviorClean(ns, "20260901")
    behavior.sliding("payload_user_id".length).count(_ == "payload_user_id") shouldBe 3 // select + join + not null 过滤
    behavior should include(IdCodec.toBIGINT("rn.payload_user_id"))
    behavior should include(IdCodec.toBIGINT("rn.payload_product_id"))

    val dimUser = DimSql.userSnapshot(ns, "20260901")
    dimUser should include(IdCodec.toBIGINT("u.payload_user_id"))

    val dimProduct = DimSql.productSnapshot(ns, "20260901")
    Seq("payload_product_id", "payload_category_id", "payload_parent_category_id", "payload_brand_id")
      .foreach(c => dimProduct should include(IdCodec.toBIGINT(s"p.$c")))
    // category/parent/brand 的 unknown key 兜底（-1）必须仍存在
    dimProduct.sliding("-1".length).count(_ == "-1") should be >= 3

    val order = TradeDwdJob.orderDetailInsertSql(ns, "20260901")
    order should include(IdCodec.toBIGINT("t.order_id"))
    order should include(IdCodec.toBIGINT("t.user_id"))
    order should include(IdCodec.toBIGINT("t.product_id"))
    // 维度分区谓词不得丢失（R9 修正 D-R9-2）
    order should include("p.dt = '20260901'")
    order should include("u.dt = '20260901'")
  }

  it should "ODS 载入层保留字符串原文（不在 ODS 提前转型）" in {
    val behaviorOds = OdsLoadSql.behaviorFromLanding(ns, SrcSys, 7L)
    behaviorOds should include("payload.user_id AS payload_user_id")
    behaviorOds.toLowerCase should not include "as bigint"
  }
}
