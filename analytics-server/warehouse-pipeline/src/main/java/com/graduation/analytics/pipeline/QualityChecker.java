package com.graduation.analytics.pipeline;

import com.graduation.analytics.contracts.EventContract;
import com.graduation.analytics.contracts.EventEnvelope;
import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.RuleSeverity;
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
        return check(events, runId, batchOrderTotals, QualityRuleCatalog.DEFAULT.freeze(null));
    }

    /**
     * 质量规则全量执行（**按指定冻结规则集**，V2.5 §7.3.1 line 520/522 的正解入口）。
     *
     * <p>执行顺序（§7.3.1 line 522）：<b>选择作用域/版本</b>（本参数 {@code rules} 由调用方冻结）
     * → 计算指标与阈值判定（下列 R1–R4）→ 决定严重度（{@link #rule} 内按版本取有效严重度）
     * → 汇总门禁（{@code corePassed}，再由 {@link DataQualityGate} 给最终结论）。</p>
     *
     * @param rules 本次 run 冻结的规则版本集；不得为 {@code null}
     */
    public QualitySummary check(List<EventEnvelope> events, long runId,
                                Map<String, BigDecimal> batchOrderTotals,
                                QualityRuleCatalog.FrozenRules rules) {
        List<DataQualityResult> results = new ArrayList<>();

        // R1 金额对账：支付金额必须等于对应订单总额（核心规则，§5.4.1 对账性）
        // 对账口径：支付事件取自**业务日切片**（本次要发布的数据）；订单总额取自**整批索引**（该订单在谁说都不变的应付额）。
        // 注意（§7.3.1 line 526）：本规则只覆盖「付款 vs 订单总额」这一种校验；
        // 「订单项公式」与「DWD↔DWS 金额」是**另外两种独立校验**，不得用一个 AMOUNT_RECONCILE 测试替代全部。
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
                    "支付金额 vs 订单总额（对账范围=整批订单；金额不等 " + mismatch + " 笔 / 整批无订单 " + orphanPaid + " 笔）",
                    rules));
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
                    "<=0.001", rate.compareTo(NULL_RATE_MAX) <= 0, "user_id/product_id 空值率", rules));
        }
        // R3 event_id 重复率（条件观察项）
        // §7.3.1 line 522：仅在「确定性去重已证」且「重复率不超批准阈值 0.0005」时为观察项；超过阈值阻断。
        // 去重的确定性依据：DwdSql.behaviorClean 的 ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time)
        // 取 rn=1，重复行进 dwd_reject_record 的 DUPLICATE_EVENT；TradeDwdJob 另有 dropDuplicates("event_id")。
        // 阈值 0.0005 来自设计文稿 §5.4.2，§7.3.1 line 522 明确「未经新裁决不修改」。
        {
            long checks = events.size();
            long dup = checks - events.stream().map(EventEnvelope::eventId).distinct().count();
            BigDecimal rate = checks == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(dup).divide(BigDecimal.valueOf(checks), 6, java.math.RoundingMode.HALF_UP);
            boolean withinThreshold = rate.compareTo(DUP_RATE_MAX) <= 0;
            results.add(rule(runId, "EVENT_ID_UNIQUE", checks, dup,
                    "<=0.0005", withinThreshold,
                    "event_id 重复率=" + rate.toPlainString() + (withinThreshold ? "（未超批准阈值，观察项）" : "（超批准阈值，阻断）"),
                    rules));
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
            results.add(rule(runId, "ENUM_WHITELIST", checks, bad, "0", bad == 0, "行为枚举白名单", rules));
        }
        // 核心结论 = 所有阻断级规则（BLOCKING/ERROR）是否全过（D-142 §1）。
        // 旧实现只看 AMOUNT_RECONCILE 一条，导致「必需字段缺失/非法枚举未过」时编排仍放行。
        boolean corePassed = !anyBlockingFailed(results, rules);
        log.info("quality check: rules={} corePassed={} ruleFingerprint={}",
                results.size(), corePassed, rules.fingerprint());
        return new QualitySummary(results, corePassed);
    }

    /**
     * 阻断级规则（版本化判据）是否有未通过项 —— 与 {@link DataQualityGate} 用同一口径，
     * 避免「编排以为通过、质量门判失败」两套结论（D-142 §1）。
     *
     * <p>判据走 {@link RuleSeverity#resolve}（V2.5 §7.3.1：按冻结规则版本判定），
     * 不看库里的字面 severity；{@code passed} 为 NULL（未判定）按未通过处理。</p>
     *
     * <p><b>未登记规则码按阻断处理</b>：这里是编排侧的快速结论，未登记码在
     * {@link DataQualityGate#decisionForRun} 会被单独列出并给出「停止发布 + 报未登记规则」
     * 的可定位理由（§7.3.1 line 524/528）。</p>
     */
    static boolean anyBlockingFailed(List<DataQualityResult> results) {
        return anyBlockingFailed(results, QualityRuleCatalog.DEFAULT.freeze(null));
    }

    /** 按指定冻结规则集判定（写侧传入本次 run 冻结的版本集时使用）。 */
    static boolean anyBlockingFailed(List<DataQualityResult> results,
                                    QualityRuleCatalog.FrozenRules rules) {
        return results != null && results.stream()
                .anyMatch(r -> RuleSeverity.blocks(rules, r.getRuleCode(), r.getPassed()));
    }

    /**
     * 构造一条质量结果。
     *
     * <p><b>写侧按统一契约产生严重度</b>（V2.5 §7.3.1 line 520「写侧仍须按统一契约产生正确严重度」）：
     * 严重度不再由本类各写各的，也不再用「全局规则码映射」定档，而是走
     * {@link RuleSeverity#resolve} 按**冻结的规则版本**取有效严重度。</p>
     *
     * <p>为什么传 {@code passed}：条件观察项（如重复率规则）的严重度取决于阈值判定结果 ——
     * 未超批准阈值 ⇒ {@code WARN}（观察项）；超阈值 ⇒ 升为阻断（§7.3.1 line 522
     * 「超过阈值阻断」）。因此同一规则码在通过/未通过两种情况下**落库的严重度可以不同**，
     * 这是设计意图，不是不一致。</p>
     *
     * <p>未登记规则码：{@link RuleSeverity#resolve} 会给出 {@code registered=false}；
     * 本方法仍落一个保守值（{@code UNREGISTERED}=BLOCKING），而**停机决策**由
     * {@link DataQualityGate#decisionForRun} 负责 —— 那里会把未登记码单独列出并要求先登记规则版本
     * （§7.3.1 line 524）。本类不静默放行，也不在此抛异常打断整批检查。</p>
     */
    private DataQualityResult rule(long runId, String code, long checks, long errors,
                                   String threshold, boolean passed, String detailPrefix,
                                   QualityRuleCatalog.FrozenRules rules) {
        DataQualityResult r = new DataQualityResult();
        r.setRunId(runId);
        r.setRuleCode(code);
        RuleSeverity.RuleVerdict verdict = RuleSeverity.resolve(rules, code, passed ? 1 : 0);
        r.setSeverity(verdict.effectiveSeverity());
        r.setCheckCount(checks);
        r.setErrorCount(errors);
        r.setErrorRate(checks == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(errors).divide(BigDecimal.valueOf(checks), 6, java.math.RoundingMode.HALF_UP));
        r.setThreshold(threshold);
        r.setPassed(passed ? 1 : 0);
        r.setDetail(detailPrefix + (passed ? " OK" : " FAILED") + " [" + verdict.explanation() + "]");
        r.setCreatedAt(java.time.LocalDateTime.now());
        if (!verdict.registered()) {
            log.warn("未登记规则码 {}（run {}）：落库为保守值 {}，须先登记规则版本再发布",
                    code, runId, verdict.effectiveSeverity());
        }
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