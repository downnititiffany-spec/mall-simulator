package com.graduation.analytics.analysis;

import com.graduation.analytics.metric.MetricQualityGate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R7-4 分析接口统一信封（冻结契约 docs/contracts/analysis-viewmodel-r7-4.md §2，指导书 §18.3）。
 *
 * <p>所有 {@code /api/v1/analysis/**} 与 {@code /api/v1/dashboards/overview} 的 {@code data}
 * 都是本结构；HTTP 外层仍是既有的 {@code ApiResponse}（code/message/traceId）。</p>
 *
 * <p>放在 metric-analysis 的分析包而不是 platform-common：信封是分析接口的返回契约，
 * 由 AnalysisService 产出、AnalysisController 透传（platform-app → metric-analysis 单向依赖），
 * 放进 platform-common 只会让公共模块背上看板语义。</p>
 *
 * @param snapshotId        本次请求固定使用的快照号；无可用快照时为 null
 * @param businessTime      快照业务时间（ISO-8601，秒精度）
 * @param dataUpdatedAt     数据更新时间（ISO-8601）；取不到时退回快照创建时间
 * @param definitionVersion 快照使用的指标口径版本
 * @param qualityStatus     质量门结论：PASS / FAIL / UNKNOWN
 * @param filters           原样回显的生效筛选（快照、日期区间、topN 等）
 * @param data              各端点自有结构（图表语义数据，不是 ECharts option）
 * @param warnings          降级事实（如 NO_ACTIVE_SNAPSHOT），不吞掉
 * @param <T>               端点数据结构
 */
public record AnalysisViewModel<T>(
        String snapshotId,
        String businessTime,
        String dataUpdatedAt,
        String definitionVersion,
        String qualityStatus,
        Map<String, Object> filters,
        T data,
        List<String> warnings) {

    /** 无 ACTIVE 快照：空 data + 本警告，禁止回退落地区事件、禁止造数（契约 §1.3） */
    public static final String WARN_NO_ACTIVE_SNAPSHOT = "NO_ACTIVE_SNAPSHOT";

    /** 请求显式指定的快照号在指标库中不存在（不能假装读了它） */
    public static final String WARN_UNKNOWN_SNAPSHOT = "UNKNOWN_SNAPSHOT";

    /** 契约要求但本期没有 Hive 来源的维度表（分类/地区结构），只能返回空数组并显式警告 */
    public static final String WARN_UNKNOWN_DIMENSION_TABLE = "UNKNOWN_DIMENSION_TABLE";

    /** ads_user_profile_m 无消费额列，八类消费额无法从指标库取值（不用 m 分冒充金额） */
    public static final String WARN_RFM_AMOUNT_UNAVAILABLE = "RFM_AMOUNT_UNAVAILABLE";

    /** 同一快照内出现多个 rule_version（画像规则版本不唯一） */
    public static final String WARN_MULTIPLE_RULE_VERSIONS = "MULTIPLE_RULE_VERSIONS";

    /** 质量结果查询失败（meta 库不可用等），结论降级为 UNKNOWN 而不是伪造成 PASS */
    public static final String WARN_QUALITY_STATUS_UNAVAILABLE = "QUALITY_STATUS_UNAVAILABLE";

    /** 紧凑构造器兜底：filters/warnings 永不为 null，且保持入参顺序（便于肉眼对账） */
    public AnalysisViewModel {
        filters = filters == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(filters));
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static <T> AnalysisViewModel<T> of(String snapshotId, String businessTime, String dataUpdatedAt,
                                             String definitionVersion, String qualityStatus,
                                             Map<String, Object> filters, T data, List<String> warnings) {
        return new AnalysisViewModel<>(snapshotId, businessTime, dataUpdatedAt, definitionVersion,
                qualityStatus, filters, data, warnings);
    }

    /**
     * 无可用快照：snapshotId/时间/口径版本一律 null，qualityStatus=UNKNOWN，data 为空对象。
     *
     * <p>为什么 data 给空对象而不是 null：前端四态判空是"data 里没有行"（契约 §4 empty 态），
     * 给 null 会让解包层多一层特判。</p>
     *
     * <p>取用注意：空信封里的 data 是 {@code Map.of()}，**不要**把它赋给具体端点 record 类型
     * （会造成 ClassCastException）；判空请先看 {@code snapshotId == null} / {@code warnings} 是否含
     * {@link #WARN_NO_ACTIVE_SNAPSHOT}，HTTP 序列化按运行时类型输出，不受影响。</p>
     */
    @SuppressWarnings("unchecked")
    public static <T> AnalysisViewModel<T> empty(Map<String, Object> filters, List<String> warnings) {
        return new AnalysisViewModel<>(null, null, null, null, MetricQualityGate.UNKNOWN,
                filters, (T) Map.of(), warnings);
    }
}
