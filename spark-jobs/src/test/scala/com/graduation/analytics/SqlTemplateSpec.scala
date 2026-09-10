package com.graduation.analytics

import com.graduation.analytics.sql.{AdsSql, DimSql, DwdSql, DwsSql, OdsLoadSql}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * SQL 模板关键子句测试：不依赖 Spark 运行，验证口径/安全/去重逻辑在 SQL 层面正确。
 * R5：7 张 DWS + 8 张 ADS 模板口径断言（§12.1-12.4）。
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

  it should "用户主题 ODS 覆盖 user_registered 且保留画像字段" in {
    val sql = OdsLoadSql.userFromLanding(8L)
    val lower = sql.toLowerCase
    lower should include("event_type in ('user_registered')")
    lower should include("payload_user_id")
    lower should include("payload_age_group")
    lower should include("payload_member_level")
    lower should include("payload_register_time")
    lower should include("'landing' as source_file")
  }

  it should "商品主题 ODS 覆盖商品与库存事件且金额转 DECIMAL" in {
    val sql = OdsLoadSql.productFromLanding(9L)
    val lower = sql.toLowerCase
    lower should include("product_updated")
    lower should include("stock_reserved")
    lower should include("stock_changed")
    lower should include("payload_price")
    lower should include("cast(payload.price as decimal(18,2))")
  }

  it should "交易主题 ODS 覆盖订单/支付/退款且不被 behavior 过滤掉" in {
    val sql = OdsLoadSql.tradeFromLanding(10L)
    val lower = sql.toLowerCase
    lower should include("order_created")
    lower should include("order_paid")
    lower should include("refund_created")
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

  it should "商品维度取每 product_id 最新建档事件并做 unknown key 兜底（库存事件不进快照）" in {
    val sql = DimSql.productSnapshot("20260901")
    val lower = sql.toLowerCase
    lower should include("row_number() over (partition by payload_product_id order by event_time desc)")
    lower should include("'unknown'")
    lower should include("-1")
    lower should include("event_type in ('product_created', 'product_updated')")
    lower should not include "stock_reserved"
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

  "DwsSql" should "用户日宽表按行为类型 CASE 聚合且 buy 来自订单明细" in {
    val sql = DwsSql.userBehaviorDay("20260901")
    val lower = sql.toLowerCase
    lower should include("sum(case when b.behavior_type = 'view' then 1 else 0 end)")
    lower should include("count(distinct b.event_hour)")
    lower should include("group by user_id")
    lower should include("final_paid_flag = 1")
    lower should include("sum(quantity) as buy")
  }

  it should "漏斗 DWS 为宽松用户口径且 order/pay 用户来自订单明细（禁硬编码 0）" in {
    val sql = DwsSql.funnelDay("20260901")
    val lower = sql.toLowerCase
    lower should include("behavior_type in ('favorite','cart_add')")
    lower should include("count(distinct case when behavior_type = 'view' then user_id end)")
    lower should include("count(distinct user_id) as order_users")
    lower should include("count(distinct user_id) as pay_users")
    lower should include("final_paid_flag = 1")
    lower should not include "0 as order_users"
    lower should not include "0 as pay_users"
  }

  it should "商品行为日 buy 来自有效支付订单而非行为枚举，且转化率不能乘状态" in {
    val sql = DwsSql.productBehaviorDay("20260901")
    val lower = sql.toLowerCase
    lower should include("final_paid_flag = 1")
    lower should include("sum(quantity) as buy")
    lower should not include "behavior_type = 'buy'"
  }

  it should "交易日汇总按有效支付口径（order_count=支付订单数）并处理分母 0" in {
    val sql = DwsSql.tradeDay("20260901")
    val lower = sql.toLowerCase
    lower should include("final_paid_flag = 1")
    lower should include("count(distinct case when final_paid_flag = 1 then order_id end)")
    lower should include("case when count(distinct case when final_paid_flag = 1 then order_id end) = 0 then null")
    lower should include("avg_order_value")
  }

  it should "商品销售日/用户交易周期/地区销售日模板存在且口径正确" in {
    val ps = DwsSql.productSaleDay("20260901").toLowerCase
    ps should include("final_paid_flag = 1")
    ps should include("sum(quantity) as sale_count")
    ps should include("count(distinct user_id) as buyer_count")

    val utp = DwsSql.userTradePeriod("20260901", "20260601", "20260901").toLowerCase
    utp should include("final_paid_flag = 1")
    utp should include("count(distinct order_id) as order_count")
    utp should include("'20260601' as period_start")

    val rs = DwsSql.regionSaleDay("20260901").toLowerCase
    rs should include("final_paid_flag = 1")
    rs should include("city_level")
    rs should include("'unknown'")
  }

  "AdsSql" should "大盘退款率分母 0 → null；热门排行限 TopN" in {
    val overview = AdsSql.operationOverview("20260901").toLowerCase
    overview should include("case when t.order_count = 0 then null")
    overview should include("refund_rate")

    val hot = AdsSql.hotProduct("20260901", 50).toLowerCase
    hot should include("row_number() over (order by")
    hot should include("rank_no <= 50")
  }

  it should "R7-0 口径：PV 只计 view、退款率按「有已完成退款的订单」、另立全额退款率" in {
    val overview = AdsSql.operationOverview("20260901").toLowerCase
    // PV 字典口径 = count(view 行为事件)，禁止把全部行为行数当 PV
    overview should include("count(case when behavior_type = 'view' then 1 end) as pv")
    overview should not include "count(*) as pv"
    // 退款率分子 = 有已完成退款（refund_amount>0，部分/全部都算）的支付订单
    overview should include("final_paid_flag = 1 and refund_amount > 0")
    overview should include("as refunded_orders")
    // 全额退款率是独立指标，不能顶替 refund_rate
    overview should include("full_refund_rate")
    overview should include("final_refunded_flag = 1")
    overview should include("as full_refunded_orders")
    // 暂存/正式两条发布路径都必须带新列（否则发布后列错位）
    val stg = AdsSql.operationOverview("20260901", Some("S20260901_99")).toLowerCase
    stg should include("ads_operation_overview__staging")
    stg should include("full_refund_rate")
  }

  it should "漏斗 ADS 展开 4 个 stage 行" in {
    val sql = AdsSql.funnel("20260901").toLowerCase
    sql should include("'view' as stage")
    sql should include("'intent'")
    sql should include("'order'")
    sql should include("'pay'")
    sql should include("overall_buy_rate")
  }

  it should "商品转化 buy_users 取商品销售 DWS 去重买家数而非 buy 件数" in {
    val sql = AdsSql.productConversion("20260901").toLowerCase
    sql should include("buyer_count")
    sql should include("left join dw_dws.dws_product_sale_day")
    sql should include("case when b.uv = 0 then null")
  }

  it should "用户画像 RFM 分层：五分位 + 八类标签 + 生命周期" in {
    val sql = AdsSql.userProfile("20260901", "20260601", "20260901").toLowerCase
    sql should include("ntile(5)")
    sql should include("重要价值")
    sql should include("重要挽留")
    sql should include("流失风险")
    sql should include("新用户")
    sql should include("'rfm-v1' as rule_version")
  }

  it should "数据质量大盘包含 4 条 QualityChecker 同名规则" in {
    val sql = AdsSql.dataQuality("20260901")
    val lower = sql.toLowerCase
    lower should include("'amount_reconcile'")
    lower should include("'required_field_null_rate'")
    lower should include("'event_id_unique'")
    lower should include("'enum_whitelist'")
    lower should include("'duplicate_event'")
    lower should include("reject_record")
  }
}