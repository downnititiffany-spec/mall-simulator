package com.graduation.analytics.metric;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 质量规则定义的**版本化契约**（V2.5 指导书 §7.3.1 line 520）。
 *
 * <p>指导书原文要求 {@code quality_rule_definition} 含：{@code rule_code}、{@code version}、
 * {@code source_scope}、{@code stage}、{@code severity}、{@code threshold_json}、{@code enabled}、
 * {@code effective_from/to}、{@code checksum}，且 {@code (scope, rule_code, version)} 唯一。
 * 本 record 是它的**Java 投影**：一个实例 = 一行 {@code quality_rule_definition}。</p>
 *
 * <p><b>为什么要有版本</b>：§7.3.1 line 520 明确「当前 {@code RuleSeverity} 的全局 {@code ruleCode}
 * 覆盖<b>不是最终完成形态</b>」。全局覆盖无法回答「这条规则在本次 run 时是什么口径」，
 * 因此历史 run 的结论会随代码改动被**追溯改写**（已登记缺陷 F-93）。版本化后，
 * 一次 run 冻结的是一组 {@code (rule_code, version)} 与指纹，结论可复算。</p>
 *
 * <p><b>阈值一律不放宽</b>：{@code thresholdJson} 只是把既有阈值搬进契约；
 * 例如 {@code EVENT_ID_UNIQUE} 的 {@code 0.0005} 来自设计文稿 §5.4.2，
 * §7.3.1 line 522 要求「未经新裁决不修改」。</p>
 *
 * <p><b>条件严重度</b>：{@link SeverityMode#THRESHOLD_OBSERVATION} 表示「未超批准阈值时是观察项
 * （{@code WARN}），超阈值即阻断」。§7.3.1 line 522 原文：「原始重复事件仅在确定性去重已证
 * 且重复率不超批准阈值时为观察项；超过阈值阻断」。因此把重复率规则写成**固定 WARN**
 * 是错的 —— 那会把高重复率直接放行。</p>
 *
 * @param ruleCode      规则码（大写、去空白后的规范形式）
 * @param version       规则版本号（同一规则码可有多个版本，逐版升）
 * @param sourceScope   适用作用域（{@code *} 表示全源；否则为具体 source_id）
 * @param stage         所属阶段：{@code LANDING}/{@code DWD}/{@code DWS}/{@code ADS}/{@code PUBLISH}/{@code METRIC_PUBLISH}
 * @param severity      该版本的严重度（{@code BLOCKING}/{@code ERROR}/{@code WARN}/{@code INFO}）
 * @param severityMode  严重度是固定值还是按阈值条件判定
 * @param thresholdJson 阈值（JSON 文本）；无阈值时为 {@code null}
 * @param enabled       是否启用
 * @param effectiveFrom 生效起始（ISO-8601 日期或日期时间文本，可为 {@code null} 表示不设下界）
 * @param effectiveTo   生效结束（同上，可为 {@code null} 表示不设上界）
 * @param rationale     中文依据（审计与页面展示用）
 */
public record QualityRuleDefinition(
        String ruleCode,
        int version,
        String sourceScope,
        String stage,
        String severity,
        SeverityMode severityMode,
        String thresholdJson,
        boolean enabled,
        String effectiveFrom,
        String effectiveTo,
        String rationale) {

    /** 全源作用域常量：该规则版本对所有 source_id 生效。 */
    public static final String SCOPE_ALL = "*";

    /** 严重度的判定方式。 */
    public enum SeverityMode {
        /** 严重度恒为 {@link #severity()}，与阈值是否超限无关。 */
        FIXED,
        /**
         * 未超阈值 ⇒ 观察项（{@code WARN}，不阻断）；超阈值 ⇒ 阻断（{@code BLOCKING}）。
         *
         * <p>用于「重复率」这类规则：§7.3.1 line 522 要求「不超批准阈值时为观察项；超过阈值阻断」。
         * 采用本模式时 {@link #severity()} 必须为 {@code WARN}（即"观察项"档），
         * 超阈值后的阻断档由 {@link RuleSeverity#BLOCKING} 给出。</p>
         */
        THRESHOLD_OBSERVATION
    }

    /** 紧凑构造器：规范化与合法性校验（越早失败越好，不产出半合法定义）。 */
    public QualityRuleDefinition {
        ruleCode = normalize(ruleCode);
        if (ruleCode.isEmpty()) {
            throw new IllegalArgumentException("ruleCode 不得为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version 必须 >= 1：" + ruleCode);
        }
        sourceScope = sourceScope == null || sourceScope.isBlank() ? SCOPE_ALL : sourceScope.trim();
        stage = stage == null ? "" : stage.trim().toUpperCase(Locale.ROOT);
        severity = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT);
        if (!RuleSeverity.isKnownSeverity(severity)) {
            throw new IllegalArgumentException(
                    "severity 必须是 BLOCKING/ERROR/WARN/INFO 之一：" + ruleCode + " -> " + severity);
        }
        severityMode = severityMode == null ? SeverityMode.FIXED : severityMode;
        if (severityMode == SeverityMode.THRESHOLD_OBSERVATION && !RuleSeverity.WARN.equals(severity)) {
            throw new IllegalArgumentException(
                    "THRESHOLD_OBSERVATION 的基准 severity 必须是 WARN（观察项档）：" + ruleCode + " -> " + severity);
        }
        if (severityMode == SeverityMode.THRESHOLD_OBSERVATION && (thresholdJson == null || thresholdJson.isBlank())) {
            throw new IllegalArgumentException("THRESHOLD_OBSERVATION 必须给出 thresholdJson：" + ruleCode);
        }
    }

    /** 规范化规则码：{@code null} 安全、去首尾空白、转大写。 */
    public static String normalize(String ruleCode) {
        return ruleCode == null ? "" : ruleCode.trim().toUpperCase(Locale.ROOT);
    }

    /** 该定义的主体是否对本 sourceId 生效（{@code *} 或精确匹配）。 */
    public boolean appliesToSource(String sourceId) {
        if (SCOPE_ALL.equals(sourceScope)) {
            return true;
        }
        return sourceId != null && sourceScope.equals(sourceId.trim());
    }

    /**
     * 本定义的稳定指纹（SHA-256 十六进制小写，64 字符）。
     *
     * <p>作用：一次 run 冻结规则集时，指纹能唯一标识「当时用的到底是哪一版口径」，
     * 事后可校验归档的规则集未被改动（§7.3.1 line 520「冻结完整规则版本与指纹」）。</p>
     *
     * <p>参与哈希的字段覆盖全部语义字段；**不**包含 {@code rationale}（纯说明文本，
     * 改错别字不应使指纹变化）。字段之间用 {@code \u001f} 分隔，避免拼接歧义。</p>
     */
    public String checksum() {
        StringBuilder sb = new StringBuilder();
        sb.append(ruleCode).append('\u001f')
                .append(version).append('\u001f')
                .append(sourceScope).append('\u001f')
                .append(stage).append('\u001f')
                .append(severity).append('\u001f')
                .append(severityMode.name()).append('\u001f')
                .append(thresholdJson == null ? "" : thresholdJson).append('\u001f')
                .append(enabled ? '1' : '0').append('\u001f')
                .append(effectiveFrom == null ? "" : effectiveFrom).append('\u001f')
                .append(effectiveTo == null ? "" : effectiveTo);
        return sha256Hex(sb.toString());
    }

    /** 计算 SHA-256 十六进制小写摘要。JDK 保证 SHA-256 可用，缺失即环境损坏，故直接抛错而非降级。 */
    static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256，环境异常", ex);
        }
    }

    /** 唯一键 {@code (source_scope, rule_code, version)} 的文本形式（用于去重与报错信息）。 */
    public String key() {
        return sourceScope + '/' + ruleCode + '@' + version;
    }
}
