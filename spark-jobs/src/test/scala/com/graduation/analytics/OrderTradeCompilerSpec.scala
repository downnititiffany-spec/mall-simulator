package com.graduation.analytics

import com.graduation.analytics.algorithm.{OrderTradeCompiler, TradeEvent}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * 订单交易状态机测试（§11.3/§11.4 验收口径）：
 * 状态合并、金额口径、部分/全额退款、refund_id 去重、迟到重算分区、商品项展开。
 */
class OrderTradeCompilerSpec extends AnyFlatSpec with Matchers {

  private def ev(eventId: String, orderId: String, tpe: String, time: String,
                 amount: Option[String] = None, total: Option[String] = None,
                 refundId: Option[String] = None, items: Option[String] = None): TradeEvent =
    TradeEvent(eventId, orderId, tpe, time, amount, total, refundId, items, Some("u1"))

  "OrderTradeCompiler" should "CREATED→PAID→COMPLETED 合并为 PAID 有效支付" in {
    val d = OrderTradeCompiler.compile(Seq(
      ev("e1", "1001", "order_created", "2026-09-01T10:00:00+08:00", total = Some("200.00"),
        items = Some("""[{"product_id":"1","quantity":2,"unit_price":"110.00","discount":"20.00","amount":"200.00"}]""")),
      ev("e2", "1001", "order_paid", "2026-09-01T10:05:00+08:00", amount = Some("200.00"))
    )).get

    d.orderId shouldBe "1001"
    d.status shouldBe "PAID"
    d.finalPaidFlag shouldBe 1
    d.orderAmount shouldBe BigDecimal("200.00")
    d.paidAmount shouldBe BigDecimal("200.00")
    d.netPaidAmount shouldBe BigDecimal("200.00")
    d.finalRefundedFlag shouldBe 0
    d.items should have size 1
    d.items.head.productId shouldBe "1"
    d.items.head.quantity shouldBe 2
    d.items.head.amount shouldBe BigDecimal("200.00")
  }

  it should "两件商品订单在订单明细中产生两行（商品展开）" in {
    val d = OrderTradeCompiler.compile(Seq(
      ev("e1", "1002", "order_created", "2026-09-02T10:00:00+08:00", total = Some("330.00"),
        items = Some("""[{"product_id":"1","quantity":1,"unit_price":"100.00","discount":"0.00","amount":"100.00"},{"product_id":"2","quantity":1,"unit_price":"230.00","discount":"0.00","amount":"230.00"}]""")),
      ev("e2", "1002", "order_paid", "2026-09-02T10:05:00+08:00", amount = Some("330.00"))
    )).get

    d.items should have size 2
    d.items.map(_.productId) should contain allOf ("1", "2")
  }

  it should "取消订单不进入支付（final_paid_flag=0）" in {
    val d = OrderTradeCompiler.compile(Seq(
      ev("e1", "1003", "order_created", "2026-09-03T10:00:00+08:00", total = Some("88.00")),
      ev("e2", "1003", "order_cancelled", "2026-09-03T10:10:00+08:00")
    )).get

    d.status shouldBe "CANCELLED"
    d.finalPaidFlag shouldBe 0
    d.paidAmount shouldBe BigDecimal(0)
  }

  it should "已支付后全额退款：GMV 保留支付额，净销售额为零" in {
    val d = OrderTradeCompiler.compile(Seq(
      ev("e1", "1004", "order_created", "2026-09-04T10:00:00+08:00", total = Some("150.00")),
      ev("e2", "1004", "order_paid", "2026-09-04T10:05:00+08:00", amount = Some("150.00")),
      ev("e3", "1004", "refund_requested", "2026-09-04T11:00:00+08:00", refundId = Some("r1")),
      ev("e4", "1004", "refund_completed", "2026-09-04T11:30:00+08:00", amount = Some("150.00"), refundId = Some("r1"))
    )).get

    d.status shouldBe "REFUNDED"
    d.finalPaidFlag shouldBe 1       // GMV 保留支付额
    d.finalRefundedFlag shouldBe 1   // 全额退款
    d.refundAmount shouldBe BigDecimal("150.00")
    d.netPaidAmount shouldBe BigDecimal(0)  // 净销售额为零
  }

  it should "部分退款 → REFUNDING 且 final_refunded_flag=0" in {
    val d = OrderTradeCompiler.compile(Seq(
      ev("e1", "1005", "order_created", "2026-09-05T10:00:00+08:00", total = Some("100.00")),
      ev("e2", "1005", "order_paid", "2026-09-05T10:05:00+08:00", amount = Some("100.00")),
      ev("e3", "1005", "refund_completed", "2026-09-05T11:00:00+08:00", amount = Some("30.00"), refundId = Some("r1"))
    )).get

    d.status shouldBe "REFUNDING"
    d.finalRefundedFlag shouldBe 0
    d.refundAmount shouldBe BigDecimal("30.00")
    d.netPaidAmount shouldBe BigDecimal("70.00")
  }

  it should "多次退款事件按 refund_id 去重汇总（同一 refund_id 只计一次）" in {
    val d = OrderTradeCompiler.compile(Seq(
      ev("e1", "1006", "order_created", "2026-09-06T10:00:00+08:00", total = Some("120.00")),
      ev("e2", "1006", "order_paid", "2026-09-06T10:05:00+08:00", amount = Some("120.00")),
      // 同 refund_id r1 出现两次（重复投递），金额不同应只计最新一次
      ev("e3", "1006", "refund_completed", "2026-09-06T11:00:00+08:00", amount = Some("50.00"), refundId = Some("r1")),
      ev("e4", "1006", "refund_completed", "2026-09-06T11:10:00+08:00", amount = Some("20.00"), refundId = Some("r1")),
      ev("e5", "1006", "refund_completed", "2026-09-06T12:00:00+08:00", amount = Some("30.00"), refundId = Some("r2"))
    )).get

    d.refundAmount shouldBe BigDecimal("50.00") // r1 最新 20 覆盖 50？取最新 → 20 + r2 30 = 50
    d.netPaidAmount shouldBe BigDecimal("70.00")
  }

  it should "订单归属业务日由 order_created 决定，迟到事件重算到原分区" in {
    // 订单 9/1 创建支付；退款事件 9/2 迟到到达
    val d = OrderTradeCompiler.compile(Seq(
      ev("e1", "1007", "order_created", "2026-09-01T10:00:00+08:00", total = Some("80.00")),
      ev("e2", "1007", "order_paid", "2026-09-01T10:05:00+08:00", amount = Some("80.00")),
      ev("e3", "1007", "refund_requested", "2026-09-02T09:00:00+08:00", refundId = Some("r1")),
      ev("e4", "1007", "refund_completed", "2026-09-02T09:30:00+08:00", amount = Some("80.00"), refundId = Some("r1"))
    )).get

    d.orderDate shouldBe "2026-09-01"
    d.status shouldBe "REFUNDED"
    OrderTradeCompiler.partitionDt(d.orderDate) shouldBe "20260901"
  }
}