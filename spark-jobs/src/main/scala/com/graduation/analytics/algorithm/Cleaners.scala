package com.graduation.analytics.algorithm

/**
 * 清洗规则（§7.1）：时间标准化、枚举/金额校验谓词 —— 纯函数，可单元测试。
 */
object Cleaners {

  val BEHAVIOR_TYPES: Set[String] =
    Set("view", "favorite", "cart_add", "cart_remove", "search")

  val ORDER_STATUSES: Set[String] =
    Set("CREATED", "PAID", "COMPLETED", "CANCELLED", "REFUNDING", "REFUNDED")

  val AMOUNT_PATTERN = "^\\d+(\\.\\d{1,2})?$".r

  /** ISO-8601（含纳秒/偏移）或 "yyyy-MM-dd HH:mm:ss" → 标准化字符串；解析失败返回 None（隔离） */
  def normalizeTime(iso: String): Option[String] = {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    try {
      val s = iso.trim
      val parsed: java.time.temporal.TemporalAccessor =
        if (s.indexOf('T') >= 0 || s.endsWith("Z") || s.indexOf('+') > 10) {
          java.time.OffsetDateTime.parse(s)   // ISO-8601 带时区偏移
        } else {
          java.time.LocalDateTime.parse(s, fmt)
        }
      Some(fmt.format(parsed))
    } catch {
      case _: Exception => None
    }
  }

  def validAmount(s: String): Boolean = AMOUNT_PATTERN.pattern.matcher(s.trim).matches()

  def validBehaviorType(s: String): Boolean = BEHAVIOR_TYPES.contains(s)

  def validOrderStatus(s: String): Boolean = ORDER_STATUSES.contains(s)

  def isMissing(s: String): Boolean = s == null || s.trim.isEmpty
}