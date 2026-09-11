package com.graduation.analytics.sql

/**
 * 契约字符串 id → 数仓 BIGINT 代理键的**唯一**映射规则。
 *
 * 背景（DEF-05，实测）：冻结契约把身份放在 `payload` 内的**字符串**字段
 * （`user_id=U000065`、`product_id=P00030`、`order_id=O00000001`、`category_id=C0001`、`brand_id=B0001`），
 * 而数仓 `dwd/dws/ads/dim` 的 id 列是 `BIGINT`；直接 `CAST(... AS BIGINT)` 会**静默返回 NULL**
 * （run 36 实测：ODS `U000058` → NULL，DWD `rows=13 / null_uid=13 / null_pid=13`），
 * 导致 `PUB_DQ_BLOCKING_RULES`(13/13) 与 `ADS_STAGING_KEY_NOT_NULL`(3/14) 阻断发布。
 *
 * 决策：B-07 候选 ② / D-023——**只在 ODS→DWD 边界做一次归一化**，数仓模型、指标、页面与已发布快照都不动。
 * 规则 = **全匹配**「字母前缀 + 纯数字」才转 `BIGINT`，否则 `NULL`（由质量规则判定，**不静默兜底成 0**）。
 * 全匹配（而不是"剥前缀后尽力解析"）是有意的：`U12A`/`U-1`/`view` 这类取值必须得到 `NULL`，
 * 否则 `U-1` 会被解析成 `-1`，与维度表的 unknown key 哨兵 `-1` 混淆（`IdCodecSpec` 已固化该断言）。
 *
 * 纪律：该规则**只在此处定义**。任何地方都不得再手写 `CAST(payload_*_id AS BIGINT)`
 * （`IdCodecSpec` 会扫描三个 SQL 模板对象做负向守卫）。
 */
object IdCodec {

  /**
   * 契约 id 的完整形状：可选字母前缀 + 纯数字。
   * group 1 = 数字部分（user `U000065` → `000065`；product `P00030` → `00030`；纯数字 `123` → `123`）。
   */
  final val IdPattern: String = "^[A-Za-z]*([0-9]+)$"

  /** 本地判定用（与 `IdPattern` 同语义，去掉捕获组） */
  private final val IdShape = "^[A-Za-z]*[0-9]+$"

  /**
   * 归一化 SQL 表达式：`CAST(REGEXP_EXTRACT(<expr>, '^[A-Za-z]*([0-9]+)$', 1) AS BIGINT)`。
   * `REGEXP_EXTRACT` 不匹配时返回空串，`CAST('' AS BIGINT)` = `NULL`——即"形状不合规 → NULL"。
   *
   * @param expr 承接契约字符串 id 的 SQL 表达式（列引用，如 `rn.payload_user_id`）
   */
  def toBIGINT(expr: String): String =
    s"CAST(REGEXP_EXTRACT($expr, '$IdPattern', 1) AS BIGINT)"

  /**
   * 与 SQL 同语义的本地实现（供快速单测与文档核对）。
   * SQL 侧用 `REGEXP_EXTRACT`、本地用 `String.matches`（同一 Java 正则引擎语义）。
   */
  def normalize(id: String): Option[Long] =
    Option(id)
      .filter(_.matches(IdShape))
      .map(_.replaceAll("^[A-Za-z]+", ""))
      .flatMap(s => scala.util.Try(s.toLong).toOption)
}
