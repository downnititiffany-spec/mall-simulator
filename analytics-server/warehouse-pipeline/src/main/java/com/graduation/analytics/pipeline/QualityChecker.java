package com.graduation.analytics.pipeline;

import com.graduation.analytics.contracts.EventContract;
import com.graduation.analytics.contracts.EventEnvelope;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 质量规则引擎（§5.4）：核心规则 = 金额对账（失败阻断发布）、必要字段空值率、
 * event_id 重复率、非法枚举比例。结果为 DataQualityResult 列表。
 */
@Slf4j
@Component
public class QualityChecker {

    private static final Set<String> BEHAVIORS =
            Set.of("view", "favorite", "cart_add", "cart_remove", "search");
    private static final BigDecimal NULL_RATE_MAX = new BigDecimal("0.001");   // 0.1%
    private static final BigDecimal DUP_RATE_MAX = new BigDecimal("0.0005");   // 0.05%

    public record QualitySummary(List<DataQualityResult> results, boolean corePassed) {
    }

    public QualitySummary check(List<EventEnvelope> events, long runId) {
        return check(events, runId, orderTotalsOf(events));
    }

    /**
     * 质量规则全量执行。
     *
     * @param batchOrderTotals 订单号 → 应付总额（`order_created.total_amount`），**必须来自整批 accepted 数据**，
     *                         不能只来自业务日切片。原因（DEF-04，2026-09-11 实测）：平台按业务日装载事件
     *                         （`PipelineService.datePrefix`），而电商订单天然跨日（T 日下单、T+1 日支付）。
     *                         若对账只看切片，切片内的 order_paid 查不到同日 order_created，就会被判成
     *                         "支付金额 vs 订单总额 FAILED"（run 35 实测：54 笔支付里 27 笔误判，阻断整条链路）。
     */
    public QualitySummary check(List<EventEnvelope> events, long runId, Map<String, BigDecimal> batchOrderTotals) {
        List<DataQualityResult> results = new ArrayList<>();
        boolean corePassed = true;

        // R1 金额对账：支付金额必须等于对应订单总额（核心规则，§5.4.1 对账性）
        // 对账口径：支付事件取自**业务日切片**（本次要发布的数据）；订单总额取自**整批索引**（该订单在谁说都不变的应付额）。
        {
            Map<String, BigDecimal> paidByOrder = new HashMap<>();
            for (EventEnvelope e : events) {
                String orderId = str(e.payload().get("order_id"));
                if (EventContract.ORDER_PAID.equals(e.eventType()) && !orderId.isEmpty()) {
                    paidByOrder.put(orderId, dec(e.payload().get("amount")));
                }
            }
            long mismatch = 0;    // 整批有该订单，但金额对不上
            long orphanPaid = 0;  // 整批都没有该订单的 order_created（孤儿支付）
            for (Map.Entry<String, BigDecimal> paid : paidByOrder.entrySet()) {
                BigDecimal total = batchOrderTotals.get(paid.getKey());
                if (total == null) {
                    orphanPaid++;
                } else if (total.compareTo(paid.getValue()) != 0) {
                    mismatch++;
                }
            }
            long errors = mismatch + orphanPaid;
            long checks = paidByOrder.size();
            results.add(rule(runId, "AMOUNT_RECONCILE", checks, errors, "0.01", errors == 0,
                    "支付金额 vs 订单总额（对账范围=整批订单；金额不等 " + mismatch + " 笔 / 整批无订单 " + orphanPaid + " 笔）"));
            if (errors > 0) {
                corePassed = false;
            }
        }
        // R2 必要字段空值率（行为事件 user_id/product_id）
        {
            long checks = 0;
            long missing = 0;
            for (EventEnvelope e : events) {
                if (EventContract.BEHAVIOR.equals(e.eventType())) {
                    checks++;
                    if (isBlank(e.payload().get("user_id")) || isBlank(e.payload().get("product_id"))) {
                        missing++;
                    }
                }
            }
            BigDecimal rate = checks == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(missing).divide(BigDecimal.valueOf(checks), 6, java.math.RoundingMode.HALF_UP);
            results.add(rule(runId, "REQUIRED_FIELD_NULL_RATE", checks, missing,
                    "<=0.001", rate.compareTo(NULL_RATE_MAX) <= 0, "user_id/product_id 空值率"));
        }
        // R3 event_id 重复率
        {
            long checks = events.size();
            long dup = checks - events.stream().map(EventEnvelope::eventId).distinct().count();
            BigDecimal rate = checks == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(dup).divide(BigDecimal.valueOf(checks), 6, java.math.RoundingMode.HALF_UP);
            results.add(rule(runId, "EVENT_ID_UNIQUE", checks, dup,
                    "<=0.0005", rate.compareTo(DUP_RATE_MAX) <= 0, "event_id 重复率"));
        }
        // R4 非法枚举
        {
            long checks = 0;
            long bad = 0;
            for (EventEnvelope e : events) {
                if (EventContract.BEHAVIOR.equals(e.eventType())) {
                    checks++;
                    if (!BEHAVIORS.contains(str(e.payload().get("behavior_type")))) {
                        bad++;
                    }
                }
            }
            results.add(rule(runId, "ENUM_WHITELIST", checks, bad, "0", bad == 0, "行为枚举白名单"));
        }
        log.info("quality check: rules={} corePassed={}", results.size(), corePassed);
        return new QualitySummary(results, corePassed);
    }

    private DataQualityResult rule(long runId, String code, long checks, long errors,
                                   String threshold, boolean passed, String detailPrefix) {
        DataQualityResult r = new DataQualityResult();
        r.setRunId(runId);
        r.setRuleCode(code);
        r.setCheckCount(checks);
        r.setErrorCount(errors);
        r.setErrorRate(checks == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(errors).divide(BigDecimal.valueOf(checks), 6, java.math.RoundingMode.HALF_UP));
        r.setThreshold(threshold);
        r.setPassed(passed ? 1 : 0);
        r.setDetail(detailPrefix + (passed ? " OK" : " FAILED"));
        r.setCreatedAt(java.time.LocalDateTime.now());
        return r;
    }

    private static boolean isBlank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    /** 从同一批事件里抽取订单总额索引（保留旧的两参重载语义：对账范围=传入事件集）。 */
    private static Map<String, BigDecimal> orderTotalsOf(List<EventEnvelope> events) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (EventEnvelope e : events) {
            String orderId = str(e.payload().get("order_id"));
            if (EventContract.ORDER_CREATED.equals(e.eventType()) && !orderId.isEmpty()) {
                putTotal(totals, orderId, e.payload().get("total_amount"));
            }
        }
        return totals;
    }

    /**
     * 写入订单总额；缺失或非法金额**跳过并记日志**，不再让整条质量检查因单个脏字段抛异常
     * （旧实现对 `total_amount` 直接 {@code new BigDecimal("null")} 会抛 NumberFormatException 打断 QUALITY_CHECK）。
     */
    static void putTotal(Map<String, BigDecimal> totals, String orderId, Object totalAmount) {
        if (totalAmount == null || String.valueOf(totalAmount).isBlank()) {
            log.warn("order {} 缺 total_amount，不进入对账索引", orderId);
            return;
        }
        try {
            totals.put(orderId, dec(totalAmount));
        } catch (NumberFormatException ex) {
            log.warn("order {} 的 total_amount 非法：{}", orderId, totalAmount);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static BigDecimal dec(Object v) {
        return new BigDecimal(String.valueOf(v));
    }
}