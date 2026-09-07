package com.graduation.analytics.metric;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.metric.mapper.MetricSnapshotMapper;
import com.graduation.analytics.metric.mapper.MetricValueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * MySQL 侧 ADS 物化（阶段 8）：快照发布成功后，把最新 ACTIVE 快照的 metric_value
 * 展宽为白名单宽表（§8.1 AI 只查 ADS；物化表是 Text-to-SQL 的执行目标）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdsMaterializer {

    private final JdbcTemplate jdbc;
    private final MetricSnapshotMapper snapshotMapper;
    private final MetricValueMapper valueMapper;

    /** 刷新当前 ACTIVE 快照的三张物化表（DELETE 该 dt 后 INSERT） */
    public int refreshActive() {
        MetricSnapshot active = snapshotMapper.selectOne(new LambdaQueryWrapper<MetricSnapshot>()
                .eq(MetricSnapshot::getStatus, MetricSnapshot.STATUS_ACTIVE)
                .orderByDesc(MetricSnapshot::getId)
                .last("LIMIT 1"));
        if (active == null) {
            return 0;
        }
        String snapshotId = active.getSnapshotId();
        // dt 从 period 提取：day:2026-09-04
        String dt = null;
        Map<String, BigDecimal> values = new HashMap<>();
        for (MetricValue v : valueMapper.selectList(new LambdaQueryWrapper<MetricValue>()
                .eq(MetricValue::getSnapshotId, snapshotId))) {
            values.put(v.getMetricCode(), v.getMetricValue());
            if (dt == null && v.getPeriod() != null && v.getPeriod().startsWith("day:")) {
                dt = v.getPeriod().substring(4);
            }
        }
        if (dt == null) {
            return 0;
        }
        jdbc.update("DELETE FROM ads_operation_overview_m WHERE dt = ?", dt);
        jdbc.update("DELETE FROM ads_sale_trend_m WHERE dt = ?", dt);
        jdbc.update("DELETE FROM ads_behavior_funnel_m WHERE dt = ?", dt);

        jdbc.update("INSERT INTO ads_operation_overview_m (dt,pv,uv,dau,paid_order_cnt,gmv,net_sale_amount," +
                        "avg_order_value,refund_rate,snapshot_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
                dt, num(values, "pv"), num(values, "uv"), num(values, "dau"),
                num(values, "paid_order_cnt"), num(values, "gmv"), num(values, "net_sale"),
                nullable(values, "avg_order_value"), nullable(values, "refund_rate"), snapshotId);

        jdbc.update("INSERT INTO ads_sale_trend_m (dt,order_count,buyer_count,sale_amount,net_sale_amount," +
                        "avg_order_value,snapshot_id) VALUES (?,?,?,?,?,?,?)",
                dt, num(values, "paid_order_cnt"), num(values, "uv"), num(values, "gmv"),
                num(values, "net_sale"), nullable(values, "avg_order_value"), snapshotId);

        String[][] stages = {{"view", "funnel_view"}, {"intent", "funnel_intent"},
                {"order", "funnel_order"}, {"pay", "funnel_pay"}};
        for (String[] stage : stages) {
            jdbc.update("INSERT INTO ads_behavior_funnel_m (dt,stage,user_count,conversion_rate,snapshot_id) " +
                            "VALUES (?,?,?,?,?)",
                    dt, stage[0], num(values, stage[1]), null, snapshotId);
        }
        log.info("ads materialized for {} (snapshot {})", dt, snapshotId);
        return 3;
    }

    private static long num(Map<String, BigDecimal> m, String key) {
        BigDecimal v = m.get(key);
        return v == null ? 0 : v.longValue();
    }

    private static BigDecimal nullable(Map<String, BigDecimal> m, String key) {
        return m.get(key);
    }
}