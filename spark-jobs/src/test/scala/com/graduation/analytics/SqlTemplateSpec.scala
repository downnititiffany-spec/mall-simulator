package com.graduation.analytics

import com.graduation.analytics.sql.{AdsSql, DwdSql, DwsSql, OdsLoadSql}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * SQL 模板关键子句测试：不依赖 Spark 运行，验证口径/安全/去重逻辑在 SQL 层面正确。
 */
class SqlTemplateSpec extends AnyFlatSpec with Matchers {

  "OdsLoadSql" should "版本过滤并派生 dt/hour 分区" in {
    val sql = OdsLoadSql.behaviorFromLanding("/landing/events")
    val lower = sql.toLowerCase
    lower should include("schema_version = '1.0'")
    lower should include("event_type = 'behavior'")
    lower should include("json.`/landing/events`")
    lower should include("partition (dt, hour)")
    lower should include("regexp_replace(substr(event_time, 1, 10), '-', '')")
    lower should include("substr(event_time, 12, 2)")
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