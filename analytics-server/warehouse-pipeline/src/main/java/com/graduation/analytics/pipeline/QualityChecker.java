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
        List<DataQualityResult> results = new ArrayList<>();
        boolean corePassed = true;

        // R1 金额对账：支付金额必须等于对应订单总额（核心规则，§5.4.1 对账性）
        {
            Map<String, BigDecimal> totalByOrder = new HashMap<>();
            Map<String, BigDecimal> paidByOrder = new HashMap<>();
            for (EventEnvelope e : events) {
                String orderId = str(e.payload().get("order_id"));
                if (EventContract.ORDER_CREATED.equals(e.eventType()) && !orderId.isEmpty()) {
                    totalByOrder.put(orderId, dec(e.payload().get("total_amount")));
                } else if (EventContract.ORDER_PAID.equals(e.eventType()) && !orderId.isEmpty()) {
                    paidByOrder.put(orderId, dec(e.payload().get("amount")));
                }
            }
            long mismatch = 0;
            for (Map.Entry<String, BigDecimal> paid : paidByOrder.entrySet()) {
                BigDecimal total = totalByOrder.get(paid.getKey());
                if (total == null || total.compareTo(paid.getValue()) != 0) {
                    mismatch++;
                }
            }
            long checks = paidByOrder.size();
            results.add(rule(runId, "AMOUNT_RECONCILE", checks, mismatch, "0.01", mismatch == 0,
                    "支付金额 vs 订单总额"));
            if (mismatch > 0) {
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

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static BigDecimal dec(Object v) {
        return new BigDecimal(String.valueOf(v));
    }
}