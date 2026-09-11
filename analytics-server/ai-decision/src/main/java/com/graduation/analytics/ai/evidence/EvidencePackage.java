package com.graduation.analytics.ai.evidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据包（V2.0 §19.2、§24.7）：一次分析结论的**全部可复核依据**，字段与指导书一一对应。
 *
 * <p>三条不变量（[R8-1 契约](docs/contracts/r8-evidence-security-decision.md) §1）：</p>
 * <ol>
 *   <li>数值一律来自已发布快照/ADS，本类只做装载，**不重算口径**；</li>
 *   <li>每条 fact/comparison/dimension/anomaly 都带 {@code evidenceRef}（{@code 表.列@快照}），可回溯；</li>
 *   <li>拿不到就是拿不到：缺口写进 {@code warnings}，**不用 0 或估算值冒充**。</li>
 * </ol>
 *
 * @param evidenceId        证据包 ID（页面与决策任务引用它，不落库正文）
 * @param templateVersion   模板版本（§19.3 固定模板版本化）
 * @param snapshotId        ACTIVE 快照号；不可用时为 null（此时事实为空 + 警告）
 * @param definitionVersion 快照口径版本
 * @param generatedAt       生成时间（ISO-8601 秒精度）
 * @param period            请求期间
 * @param currentPeriod     解析后的当期（无输入时 = 快照业务日）
 * @param comparisonPeriod  等长上期；取不到为 null（§20.4 同精神：数据不足不得编造）
 * @param facts             指标事实
 * @param comparisons       上期对比
 * @param dimensions        维度贡献：product/category/region/channel（无来源表 → 空数组 + 警告）
 * @param anomalies         候选异常（规则 + 阈值 + 偏离），**非因果结论**
 * @param dataQuality       质量可信度
 * @param lineage           血缘：Hive ADS → MySQL 表 → pipelineRunId
 * @param warnings          降级事实（不吞掉）
 */
public record EvidencePackage(
        String evidenceId,
        String templateVersion,
        String snapshotId,
        String definitionVersion,
        String generatedAt,
        Period period,
        Period currentPeriod,
        Period comparisonPeriod,
        List<Fact> facts,
        List<Comparison> comparisons,
        Map<String, List<DimensionContribution>> dimensions,
        List<AnomalyCandidate> anomalies,
        DataQuality dataQuality,
        Lineage lineage,
        List<String> warnings) {

    /** 模板版本：字段/段落结构变更必须改版本号（§19.3 版本化模板） */
    public static final String TEMPLATE_VERSION = "evidence_v1";

    /** 维度键（§19.2 dimensions 的四个维度；无来源表的维度返回空数组） */
    public static final List<String> DIMENSION_KEYS = List.of("product", "category", "region", "channel");

    /** 无 ACTIVE 快照 */
    public static final String WARN_NO_ACTIVE_SNAPSHOT = "NO_ACTIVE_SNAPSHOT";
    /** 显式指定的快照号不存在 */
    public static final String WARN_UNKNOWN_SNAPSHOT = "UNKNOWN_SNAPSHOT";
    /** 找不到等长上期的快照 → 本期不做对比（不得用 0 冒充基线） */
    public static final String WARN_NO_COMPARISON_PERIOD = "NO_COMPARISON_PERIOD";
    /** 多日窗口暂不支持对比（metric_value 只有快照粒度的单日聚合） */
    public static final String WARN_COMPARISON_WINDOW_UNSUPPORTED = "COMPARISON_WINDOW_UNSUPPORTED";
    /** 契约要求但本期无 Hive 来源的维度表 */
    public static final String WARN_UNKNOWN_DIMENSION_TABLE = "UNKNOWN_DIMENSION_TABLE";
    /** 只读 ADS 读取不可用（维度/质量取不到，facts 仍来自快照指标） */
    public static final String WARN_ADS_READ_UNAVAILABLE = "ADS_READ_UNAVAILABLE";
    /** 快照内出现多个指标口径版本（同一快照的口径不唯一） */
    public static final String WARN_MIXED_DEFINITION_VERSIONS = "MIXED_DEFINITION_VERSIONS";
    /** 请求的 timeRange 无法解析成日期 → 退回快照业务日（并把事实写进警告） */
    public static final String WARN_TIME_RANGE_IGNORED = "TIME_RANGE_IGNORED";
    /** 请求期间与快照业务日不一致：数值仍是快照业务日的值，必须显式说明 */
    public static final String WARN_REQUESTED_PERIOD_NOT_SNAPSHOT_DATE = "REQUESTED_PERIOD_NOT_SNAPSHOT_DATE";
    /** 质量结论查询不可用 → UNKNOWN，不伪造成 PASS */
    public static final String WARN_QUALITY_STATUS_UNAVAILABLE = "QUALITY_STATUS_UNAVAILABLE";

    public EvidencePackage {
        templateVersion = templateVersion == null ? TEMPLATE_VERSION : templateVersion;
        facts = facts == null ? List.of() : List.copyOf(facts);
        comparisons = comparisons == null ? List.of() : List.copyOf(comparisons);
        dimensions = dimensions == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    // ── §19.2 子结构 ──────────────────────────────────────────────────────

    /** 期间（ISO 日期，闭区间） */
    public record Period(String from, String to) {
    }

    /**
     * 指标事实。
     *
     * @param metricCode  指标码（来自 metric_definition，禁自造码）
     * @param metricName  指标中文名（字典缺该码时留空，不臆造）
     * @param value       取值（字符串，避免 BigDecimal 精度在序列化时丢失）
     * @param unit        单位
     * @param period      取值期间（如 {@code day:2026-09-01}）
     * @param evidenceRef 证据引用（{@code 表.列@快照}）
     */
    public record Fact(String metricCode, String metricName, String value, String unit,
                       String period, String evidenceRef) {
    }

    /**
     * 上期对比（§19.2 comparisons）。
     *
     * @param baseline    基线值（上一期）；缺失为 null
     * @param delta       current - baseline；缺失为 null
     * @param deltaRate   delta / |baseline|；baseline 为 0 或缺失时为 null
     * @param baselineRef 基线证据引用（含基线快照号）
     */
    public record Comparison(String metricCode, String metricName, String current, String baseline,
                             String delta, String deltaRate, String evidenceRef, String baselineRef) {
    }

    /**
     * 维度贡献。
     *
     * @param metricCode 该维度使用的度量码（如 {@code product_heat}），不是被解释的目标指标
     * @param share      占比（贡献值 / 该维度贡献值合计，4 位小数）
     */
    public record DimensionContribution(String key, String label, String value, String share,
                                        String metricCode, String evidenceRef) {
    }

    /**
     * 候选异常（§19.1：规则命中 + 阈值偏离；文案必须含「可能相关，不构成因果」）。
     *
     * @param observed  观测值
     * @param threshold 阈值
     * @param deviation 偏离量（观测 - 阈值）
     */
    public record AnomalyCandidate(String ruleCode, String metricCode, String severity,
                                   String observed, String threshold, String deviation,
                                   String statement, String evidenceRef) {
    }

    /** 数据质量（§19.2 dataQuality）：质量门结论 + 规则条数/未通过规则码 */
    public record DataQuality(String gateStatus, int ruleTotal, int rulePassed,
                              List<String> failedRules, List<String> warnings) {
        public static final String GATE_PASS = "PASS";
        public static final String GATE_FAIL = "FAIL";
        public static final String GATE_UNKNOWN = "UNKNOWN";

        public DataQuality {
            failedRules = failedRules == null ? List.of() : List.copyOf(failedRules);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static DataQuality unknown(String warning) {
            return new DataQuality(GATE_UNKNOWN, 0, 0, List.of(), List.of(warning));
        }
    }

    /**
     * 血缘（§19.2 lineage）：Hive ADS 表 → MySQL ADS 表 → 发布批次。
     *
     * @param pipelineRunId 指标快照的发布批次（取不到为 null，不编造）
     */
    public record Lineage(List<String> hiveAdsTables, List<String> mysqlTables,
                          Long pipelineRunId, String snapshotId) {
        public Lineage {
            hiveAdsTables = hiveAdsTables == null ? List.of() : List.copyOf(hiveAdsTables);
            mysqlTables = mysqlTables == null ? List.of() : List.copyOf(mysqlTables);
        }
    }
}
