package com.graduation.analytics

import com.graduation.analytics.sql.{AdsSql, DimSql, DwdSql, DwsSql, OdsLoadSql}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * SQL 模板关键子句测试：不依赖 Spark 运行，验证口径/安全/去重逻辑在 SQL 层面正确。
 */
class SqlTemplateSpec extends AnyFlatSpec with Matchers {

  "OdsLoadSql" should "版本过滤并派生 dt/hour 分区" in {
    val sql = OdsLoadSql.behaviorFromLanding(7L)
    val lower = sql.toLowerCase
    lower should include("schema_version = '1.0'")
    lower should include("event_type = 'behavior'")
    lower should include("landing_valid")
    lower should include("partition (dt, hour)")
    lower should include("regexp_replace(substr(event_time, 1, 10), '-', '')")
    lower should include("substr(event_time, 12, 2)")
    lower should include("7 as ingest_batch_id")
  }

  it should "用户主题 ODS 覆盖 user_created/user_updated 且保留画像字段" in {
    val sql = OdsLoadSql.userFromLanding(8L)
    val lower = sql.toLowerCase
    lower should include("event_type in ('user_created', 'user_updated')")
    lower should include("payload_user_id")
    lower should include("payload_age_group")
    lower should include("payload_member_level")
    lower should include("payload_register_time")
    lower should include("'landing' as source_file")
  }

  it should "商品主题 ODS 覆盖商品与库存事件且金额转 DECIMAL" in {
    val sql = OdsLoadSql.productFromLanding(9L)
    val lower = sql.toLowerCase
    lower should include("product_status_changed")
    lower should include("inventory_changed")
    lower should include("payload_price")
    lower should include("cast(payload.price as decimal(18,2))")
  }

  it should "交易主题 ODS 覆盖订单/支付/退款且不被 behavior 过滤掉" in {
    val sql = OdsLoadSql.tradeFromLanding(10L)
    val lower = sql.toLowerCase
    lower should include("order_created")
    lower should include("order_paid")
    lower should include("refund_completed")
    lower should include("payload_items")
    lower should include("payload_refund_id")
    lower should not include "event_type = 'behavior'"
  }

  it should "隔离行按未知版本或缺失主键筛选" in {
    val sql = OdsLoadSql.rejectedSelect()
    val lower = sql.toLowerCase
    lower should include("schema_version <> '1.0'")
    lower should include("event_id is null")
    lower should include("bad_version_or_key")
  }

  "DimSql" should "用户维度取每 user_id 最新事件生成快照并保留来源批次" in {
    val sql = DimSql.userSnapshot("20260901")
    val lower = sql.toLowerCase
    lower should include("row_number() over (partition by payload_user_id order by event_time desc)")
    lower should include("payload_user_id")
    lower should include("source_batch_id")
    lower should include("partition(dt = '20260901')")
  }

  it should "商品维度取每 product_id 最新事件并做 unknown key 兜底" in {
    val sql = DimSql.productSnapshot("20260901")
    val lower = sql.toLowerCase
    lower should include("row_number() over (partition by payload_product_id order by event_time desc)")
    lower should include("'unknown'")
    lower should include("-1")
  }

  "DwdSql" should "event_id 去重且只保留合法枚举" in {
    val sql = DwdSql.behaviorClean("20260901")
    val lower = sql.toLowerCase
    lower should include("row_number() over (partition by event_id order by ingest_time)")
    lower should include("rn.rn = 1")
    lower should include("behavior_type in ('view','favorite','cart_add','cart_remove','search')")
    lower should include("partition(dt = '20260901')")
    lower should include("payload_user_id is not null")
  }

  it should "重复事件写入拒绝记录" in {
    val sql = DwdSql.duplicateReject("20260901")
    sql.toLowerCase should include("group by event_id having count(*) > 1")
    sql.toLowerCase should include("'duplicate_event'")
  }

  "DwsSql" should "用户日宽表按行为类型 CASE 聚合" in {
    val sql = DwsSql.userBehaviorDay("20260901")
    val lower = sql.toLowerCase
    lower should include("sum(case when behavior_type = 'view' then 1 else 0 end)")
    lower should include("count(distinct event_hour)")
    lower should include("group by user_id")
  }

  it should "漏斗 DWS 为宽松用户口径（favorite/cart_add 意向）" in {
    val sql = DwsSql.funnelDay("20260901")
    sql.toLowerCase should include("behavior_type in ('favorite','cart_add')")
    sql.toLowerCase should include("count(distinct case when behavior_type = 'view' then user_id end)")
  }

  it should "交易日汇总按有效支付口径并处理分母为 0" in {
    val sql = DwsSql.tradeDay("20260901")
    val lower = sql.toLowerCase
    lower should include("final_paid_flag = 1")
    lower should include("case when count(distinct order_id) = 0 then null")
  }

  "AdsSql" should "大盘退款率分母为 0 → null；热门排行限 TopN" in {
    val overview = AdsSql.operationOverview("20260901").toLowerCase
    overview should include("case when t.order_count = 0 then null")
    overview should include("refund_rate")

    val hot = AdsSql.hotProduct("20260901", 50).toLowerCase
    hot should include("row_number() over (order by")
    hot should include("rank_no <= 50")
  }
}