package com.graduation.analytics.algorithm

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

/**
 * 订单交易状态机（§11.3 TradeDwdJob 核心算法）：
 * 把创建/支付/取消/退款事件合并为订单级明细 —— 纯函数、无 Spark 依赖、可单元测试。
 *
 * 状态转移：
 *   CREATED → PAID → COMPLETED
 *   CREATED → CANCELLED
 *   PAID → REFUND_REQUESTED → REFUNDED
 *
 * 金额口径（§11.3）：
 *   order_amount   = order_created.total_amount（订单优惠后应付总额）
 *   paid_amount    = 最新 order_paid.amount（实际成功支付）
 *   refund_amount  = refund_completed 按 refund_id 去重后汇总
 *   net_paid_amount= paid_amount - refund_amount
 *   final_paid_flag    ：1=存在有效支付且订单未被取消
 *   final_refunded_flag：1=已完成退款 >= 支付金额（全额退款）
 *
 * 部分退款：refund_amount < paid_amount → 状态 REFUNDING，final_refunded_flag=0。
 */
case class OrderItem(
    productId: String,
    quantity: Long = 0L,
    unitPrice: BigDecimal = BigDecimal(0),
    discount: BigDecimal = BigDecimal(0),
    amount: BigDecimal = BigDecimal(0))

case class TradeOrderDetail(
    orderId: String,
    userId: String,
    status: String,              // CREATED/PAID/COMPLETED/CANCELLED/REFUNDING/REFUNDED
    orderTime: String,           // order_created.event_time
    orderDate: String,           // yyyy-MM-dd（订单归属业务日 → dt 分区）
    paidAt: Option[String],
    orderAmount: BigDecimal,
    paidAmount: BigDecimal,
    refundAmount: BigDecimal,
    netPaidAmount: BigDecimal,
    finalPaidFlag: Int,
    finalRefundedFlag: Int,
    items: Seq[OrderItem])

/** 单条交易事件（编译器输入） */
case class TradeEvent(
    eventId: String,
    orderId: String,
    eventType: String,           // order_created/order_paid/order_cancelled/refund_requested/refund_completed
    eventTime: String,
    amount: Option[String],      // 支付/退款金额字符串
    totalAmount: Option[String], // 订单应付总额
    refundId: Option[String],
    itemsJson: Option[String],
    userId: Option[String])

object OrderTradeCompiler {

  private val mapper = new ObjectMapper()

  /** 解析 order_created.items JSON 数组 → OrderItem 序列；解析失败返回 Nil（由调用方隔离） */
  def parseItems(json: String): Seq[OrderItem] = {
    if (json == null || json.trim.isEmpty) return Nil
    try {
      val arr: JsonNode = mapper.readTree(json)
      if (!arr.isArray) return Nil
      val out = List.newBuilder[OrderItem]
      arr.elements().forEachRemaining { node =>
        val num = (n: String) => Option(node.get(n))
          .filter(_.isValueNode).map(_.asText()).filter(_.nonEmpty)
          .flatMap(s => scala.util.Try(BigDecimal(s)).toOption).getOrElse(BigDecimal(0))
        out += OrderItem(
          productId = Option(node.get("product_id")).map(_.asText()).getOrElse(""),
          quantity = Option(node.get("quantity")).map(_.asText())
            .flatMap(s => scala.util.Try(s.toLong).toOption).getOrElse(0L),
          unitPrice = num("unit_price"),
          discount = num("discount"),
          amount = num("amount"))
      }
      out.result()
    } catch {
      case _: Exception => Nil
    }
  }

  /**
   * 合并一个订单的全部交易事件 → 订单明细。
   * 输入事件应为同一 order_id；event_id 去重由调用方（Job DataFrame dropDuplicates）先完成。
   * 取事件最新者优先：按 event_time 升序顺序折叠（靠后的支付/退款覆盖先前值）。
   */
  def compile(events: Seq[TradeEvent]): Option[TradeOrderDetail] = {
    if (events.isEmpty) return None
    val sorted = events.sortBy(_.eventTime)   // 时间升序，后者为最新状态

    val created = sorted.find(_.eventType == "order_created")
    if (created.isEmpty) return None          // 无创建事件不构成订单明细

    val userId = created.flatMap(_.userId).orElse(
      sorted.find(_.eventType == "order_paid").flatMap(_.userId)).getOrElse("")

    val orderAmount = created.flatMap(_.totalAmount)
      .orElse(sorted.find(_.eventType == "order_paid").flatMap(_.amount))
      .flatMap(parseAmount).getOrElse(BigDecimal(0))

    // 支付：取最后一个 order_paid（最新）
    val paid = sorted.filter(_.eventType == "order_paid").lastOption
    val paidAmount = paid.flatMap(_.amount).flatMap(parseAmount).getOrElse(BigDecimal(0))
    val paidAt = paid.map(_.eventTime)
    val cancelled = sorted.exists(_.eventType == "order_cancelled")

    // 退款：refund_completed 按 refund_id 去重（多个相同 refund_id 事件只计一次），汇总金额
    val refundAmount = sorted.filter(e => e.eventType == "refund_completed")
      .foldLeft(Map.empty[String, BigDecimal]) { (acc, e) =>
        val key = e.refundId.getOrElse(e.eventId)
        e.amount.flatMap(parseAmount).fold(acc)(amt => acc.updated(key, amt))
      }.values.sum

    val netPaid = paidAmount - refundAmount
    val finalPaidFlag = if (!cancelled && paidAmount > 0) 1 else 0
    val fullyRefunded = refundAmount > 0 && refundAmount >= paidAmount
    val finalRefundedFlag = if (fullyRefunded) 1 else 0

    val status: String =
      if (cancelled) "CANCELLED"
      else if (refundAmount > 0 && refundAmount < paidAmount) "REFUNDING"
      else if (refundAmount > 0 && refundAmount >= paidAmount) "REFUNDED"
      else if (paidAmount > 0) "PAID"
      else "CREATED"

    val orderTime = created.map(_.eventTime).getOrElse("")
    Some(TradeOrderDetail(
      orderId = created.map(_.orderId).getOrElse(""),
      userId = userId,
      status = status,
      orderTime = orderTime,
      orderDate = orderTime.take(10),
      paidAt = paidAt,
      orderAmount = orderAmount,
      paidAmount = paidAmount,
      refundAmount = refundAmount,
      netPaidAmount = netPaid,
      finalPaidFlag = finalPaidFlag,
      finalRefundedFlag = finalRefundedFlag,
      items = created.flatMap(_.itemsJson).map(parseItems).getOrElse(Nil)
    ))
  }

  private def parseAmount(s: String): Option[BigDecimal] =
    if (s == null || s.trim.isEmpty) None else scala.util.Try(BigDecimal(s.trim)).toOption

  /** 订单归属业务日分区：yyyy-MM-dd → yyyyMMdd（与 Hive dt 分区一致，§12.5） */
  def partitionDt(orderDate: String): String =
    orderDate.replaceAll("-", "")
}