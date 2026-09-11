package com.graduation.analytics.ai;

import com.graduation.analytics.ai.sql.AiScope;

import java.util.List;

/**
 * 规则回退（§3.5.5/§16 风险控制：AI 费用或网络不可用时的降级路径）：
 * 推荐问题 → 固定模板 SQL（安全且在白名单内）。
 *
 * <p>反熵 ①（R7-4）：模板列名必须等于已发布 ADS 物化表的真实列名（曾出现 net_sale_amount/gmv
 * 在 ads_sale_trend_m / ads_operation_overview_m 上不存在的漂移，运行时直接 Unknown column）；
 * AiSqlDriftTest 常驻比对 DDL，任何漂移即红。</p>
 *
 * <p>反熵 ②（R8-2 契约 §2.2/§2.3）：模板**全部**改为带 {@code snapshot_id} 与 {@code dt} 字面量范围的
 * 单表查询 —— 删除原先的 {@code snapshot_id = (SELECT MAX(...))} 与 {@code dt = (SELECT MAX(dt) ...)}
 * 子查询。回退路径与模型路径走同一套校验规则（无子查询/无 JOIN/有快照钉住/有日期范围），
 * 否则回退会变成绕过校验器的后门。scope 缺失 → 返回 error 而非拼一条「无快照」的 SQL。</p>
 */
public final class RuleBasedSqlFallback {

    /** sql 为 null 表示无法生成（scope 缺失）；assumptions/error 说明原因 */
    public record Fallback(String sql, List<String> assumptions, String error) {

        public boolean ok() {
            return sql != null && !sql.isBlank();
        }
    }

    private RuleBasedSqlFallback() {
    }

    /** 兼容入口：无 scope 时拒绝生成（fail-closed），避免出现无快照钉住的 SQL */
    public static Fallback resolve(String question, List<String> tables) {
        return resolve(question, tables, null);
    }

    public static Fallback resolve(String question, List<String> tables, AiScope scope) {
        if (scope == null || scope.snapshotId() == null || scope.businessDate() == null) {
            return new Fallback(null, List.of(),
                    "NO_ACTIVE_SNAPSHOT：规则回退模板必须携带 ACTIVE 快照与日期字面量，缺少 scope 时拒绝生成 SQL");
        }
        String q = question == null ? "" : question;
        String day = scope.dtTo(); // 紧凑 yyyyMMdd，见 AiScope.DT_FORMAT（2026-09-11 真机事故）
        String pin = SemanticCatalog.snapshotPin(scope);
        String today = "dt >= '" + day + "' AND dt <= '" + day + "'";

        if (q.contains("漏斗") || q.contains("转化")) {
            return new Fallback(
                    "SELECT dt, stage, user_count, conversion_rate, overall_buy_rate FROM ads_behavior_funnel_m WHERE "
                            + pin + " AND " + today + " ORDER BY stage LIMIT 4",
                    List.of("默认取 ACTIVE 快照业务日（" + day + "）的四个漏斗阶段"),
                    null);
        }
        if (q.contains("商品") || q.contains("排行") || q.contains("热度")) {
            return new Fallback(
                    "SELECT dt, product_id, product_name, heat_score, pv, fav, cart, buy, rank_no "
                            + "FROM ads_hot_product_m WHERE " + pin + " AND " + today
                            + " ORDER BY rank_no LIMIT 10",
                    List.of("默认取 ACTIVE 快照业务日（" + day + "）热度前 10 商品"),
                    null);
        }
        if (q.contains("活跃") || q.contains("大盘") || q.contains("gmv") || q.contains("退款")) {
            return new Fallback(
                    "SELECT dt, pv, uv, dau, order_count, sale_amount, net_sale_amount, avg_order_value, refund_rate "
                            + "FROM ads_operation_overview_m WHERE " + pin + " AND " + today + " LIMIT 1",
                    List.of("默认取 ACTIVE 快照业务日（" + day + "）的大盘指标"),
                    null);
        }
        // 默认：销售趋势（ACTIVE 快照业务日往前 7 天，含端点，仍在校验器的 90 天窗口内）
        String weekAgo = AiScope.dt(scope.businessDate().minusDays(6));
        return new Fallback(
                "SELECT dt, order_count, buyer_count, sale_amount, avg_order_value "
                        + "FROM ads_sale_trend_m WHERE " + pin
                        + " AND dt >= '" + weekAgo + "' AND dt <= '" + day + "' ORDER BY dt LIMIT 7",
                List.of("默认取 ACTIVE 快照业务日（" + day + "）往前 7 天的有效支付口径"),
                null);
    }
}
