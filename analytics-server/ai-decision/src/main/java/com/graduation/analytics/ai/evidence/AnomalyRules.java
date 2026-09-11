package com.graduation.analytics.ai.evidence;

import com.graduation.analytics.ai.evidence.EvidencePackage.AnomalyCandidate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 候选异常规则（§19.1「异常检测作为候选 + 阈值偏离」、§19.2 anomalies）。
 *
 * <p>反熵与诚实边界：</p>
 * <ul>
 *   <li>本类**只做阈值规则**，不训练模型、不做预测；命中即「候选」，不是结论；</li>
 *   <li>每条文案强制带「可能相关，不构成因果」，与 §19.1/§20.4 的不因果表述一致；</li>
 *   <li>观测值缺失（指标取不到）时**不产生异常**，也**不补 0**；</li>
 *   <li>阈值是常量并在此登记（可复核），避免阈值散落在模板或前端里各写一份。</li>
 * </ul>
 */
public final class AnomalyRules {

    private AnomalyRules() {
    }

    /** 退款率上限（含税/全额口径分开看；超过即候选异常） */
    public static final BigDecimal REFUND_RATE_MAX = new BigDecimal("0.30");
    /** 全额退款率上限 */
    public static final BigDecimal FULL_REFUND_RATE_MAX = new BigDecimal("0.15");
    /** 购买转化率下限 */
    public static final BigDecimal BUY_RATE_MIN = new BigDecimal("0.30");
    /** 环比变动幅度下限（超过即候选异常） */
    public static final BigDecimal DELTA_RATE_ALERT = new BigDecimal("0.30");
    /** 参与环比告警的指标（金额/流量/单量；转化率/退款率走各自阈值规则，避免重复告警） */
    public static final List<String> DELTA_WATCH_CODES =
            List.of("gmv", "net_sale", "pv", "uv", "dau", "paid_order_cnt");

    public static final String RULE_REFUND_RATE_HIGH = "REFUND_RATE_HIGH";
    public static final String RULE_FULL_REFUND_RATE_HIGH = "FULL_REFUND_RATE_HIGH";
    public static final String RULE_BUY_RATE_LOW = "BUY_RATE_LOW";
    public static final String RULE_PERIOD_SHIFT = "PERIOD_SHIFT_OVER_30PCT";
    public static final String RULE_QUALITY_RULE_FAILED = "QUALITY_RULE_FAILED";

    private static final String NOT_CAUSAL = "（可能相关，不构成因果）";

    /**
     * 运行阈值规则。
     *
     * @param values      指标码 → 当期取值（缺失的码不在 map 中）
     * @param deltaRates  指标码 → 环比变动率（无可比上期时为空 map）
     * @param quality     质量可信度（BLOCKING 失败 → 质量规则异常升级为 HIGH）
     * @param snapshotId  当前快照（写 evidenceRef）
     */
    public static List<AnomalyCandidate> evaluate(Map<String, BigDecimal> values,
                                                  Map<String, BigDecimal> deltaRates,
                                                  EvidencePackage.DataQuality quality,
                                                  String snapshotId) {
        List<AnomalyCandidate> out = new ArrayList<>();
        addUpper(out, values, "refund_rate", RULE_REFUND_RATE_HIGH, REFUND_RATE_MAX, "MEDIUM", snapshotId);
        addUpper(out, values, "full_refund_rate", RULE_FULL_REFUND_RATE_HIGH, FULL_REFUND_RATE_MAX, "HIGH", snapshotId);
        addLower(out, values, "buy_rate", RULE_BUY_RATE_LOW, BUY_RATE_MIN, "MEDIUM", snapshotId);
        addShift(out, deltaRates, snapshotId);
        addQuality(out, quality, snapshotId);
        return List.copyOf(out);
    }

    /** 上界规则：observed > threshold → 异常，偏离 = observed - threshold */
    private static void addUpper(List<AnomalyCandidate> out, Map<String, BigDecimal> values,
                                 String metricCode, String ruleCode, BigDecimal threshold,
                                 String severity, String snapshotId) {
        BigDecimal observed = values.get(metricCode);
        if (observed == null || observed.compareTo(threshold) <= 0) {
            return;
        }
        out.add(new AnomalyCandidate(ruleCode, metricCode, severity,
                plain(observed), plain(threshold), plain(observed.subtract(threshold)),
                metricCode + " 高于阈值 " + plain(threshold) + "，当前 " + plain(observed) + NOT_CAUSAL,
                ref(metricCode, snapshotId)));
    }

    /** 下界规则：observed < threshold → 异常，偏离 = threshold - observed */
    private static void addLower(List<AnomalyCandidate> out, Map<String, BigDecimal> values,
                                 String metricCode, String ruleCode, BigDecimal threshold,
                                 String severity, String snapshotId) {
        BigDecimal observed = values.get(metricCode);
        if (observed == null || observed.compareTo(threshold) >= 0) {
            return;
        }
        out.add(new AnomalyCandidate(ruleCode, metricCode, severity,
                plain(observed), plain(threshold), plain(threshold.subtract(observed)),
                metricCode + " 低于阈值 " + plain(threshold) + "，当前 " + plain(observed) + NOT_CAUSAL,
                ref(metricCode, snapshotId)));
    }

    /** 环比规则：|deltaRate| >= 30% 且指标在观察清单内 */
    private static void addShift(List<AnomalyCandidate> out, Map<String, BigDecimal> deltaRates,
                                 String snapshotId) {
        for (String code : DELTA_WATCH_CODES) {
            BigDecimal rate = deltaRates.get(code);
            if (rate == null || rate.abs().compareTo(DELTA_RATE_ALERT) < 0) {
                continue;
            }
            out.add(new AnomalyCandidate(RULE_PERIOD_SHIFT, code, "LOW",
                    plain(rate), plain(DELTA_RATE_ALERT), plain(rate),
                    code + " 环比变动 " + plain(rate) + "，超过 ±" + plain(DELTA_RATE_ALERT) + NOT_CAUSAL,
                    ref(code, snapshotId)));
        }
    }

    /** 质量规则异常：未通过的规则逐条登记；质量门 FAIL（BLOCKING 未过）→ HIGH */
    private static void addQuality(List<AnomalyCandidate> out, EvidencePackage.DataQuality quality,
                                   String snapshotId) {
        if (quality == null) {
            return;
        }
        String severity = EvidencePackage.DataQuality.GATE_FAIL.equals(quality.gateStatus()) ? "HIGH" : "MEDIUM";
        for (String ruleCode : quality.failedRules()) {
            out.add(new AnomalyCandidate(RULE_QUALITY_RULE_FAILED, null, severity,
                    "failed", "passed", null,
                    "质量规则 " + ruleCode + " 未通过，本次数据需先核查再采信" + NOT_CAUSAL,
                    "ads_data_quality_m." + ruleCode + "@" + nullSafe(snapshotId)));
        }
    }

    private static String ref(String metricCode, String snapshotId) {
        return "metric_value." + metricCode + "@" + nullSafe(snapshotId);
    }

    private static String nullSafe(String s) {
        return s == null ? "unknown" : s;
    }

    /** 阈值/偏离一律 4 位小数的 plain string（与 metric_value 的 DECIMAL(18,4) 口径一致） */
    private static String plain(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
