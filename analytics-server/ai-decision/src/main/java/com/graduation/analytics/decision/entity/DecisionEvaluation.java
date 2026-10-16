package com.graduation.analytics.decision.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 决策效果评价（§20.4 / R8-3 §3.4）：等长窗口前后对比，标注「非因果推断」。
 *
 * <p>窗口口径：baseline 窗口 = [approvedDate-N+1, approvedDate]，逐日引用在批准时冻结；
 * actual 窗口 = [completedDate+1, completedDate+N]，仅读同 runtimeProfileId / sourceId / metric definition 的已发布数据。
 * 两组日期、样本数与逐日快照引用均持久化；只有完整窗口和已验证的聚合公式才产生效果结论。</p>
 */
@Data
@TableName("decision_evaluation")
public class DecisionEvaluation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long decisionId;

    private BigDecimal baselineValue;

    private BigDecimal actualValue;

    private BigDecimal improvementRate;

    private String result;

    private String note;

    private String evaluatedBy;

    private LocalDateTime createdAt;

    // ── R8-3 V14 补列（§3.5） ────────────────────────────────────────────

    /** 前快照兼容锚点：完整基线窗口最后一个快照 id */
    private String baselineSnapshotId;

    /** 参与基线窗口的逐日快照血缘（URL-safe Base64 item，逗号分隔）。 */
    private String baselineSnapshotRefs;

    /** 后快照兼容锚点：完整实际窗口最后一个快照 id */
    private String actualSnapshotId;

    /** 参与实际窗口的逐日快照血缘（URL-safe Base64 item，逗号分隔）。 */
    private String actualSnapshotRefs;

    /** 本次评价固定的数据来源身份。 */
    private Long sourceId;

    /** 本次评价固定的运行环境身份；旧评价为 null。 */
    private Long runtimeProfileId;

    /** 指标自身的口径版本；definitionVersion 保留为评价算法版本。 */
    private String metricDefinitionVersion;

    private Integer baselineSampleCount;

    private Integer actualSampleCount;

    private LocalDate baselineWindowStart;

    private LocalDate baselineWindowEnd;

    /** 评价窗口起（= completedDate+1） */
    private LocalDate windowStart;

    /** 评价窗口止（= completedDate+N） */
    private LocalDate windowEnd;

    /** 窗口内基线聚合值（当前已验证的可加总日指标为完整窗口日值之和） */
    private BigDecimal baselinePeriodValue;

    /** 窗口内实际聚合值 */
    private BigDecimal actualPeriodValue;

    /** 参与聚合的日样本数（baseline + actual；完整评价应为 2 × evalWindowDays） */
    private Integer sampleCount;

    /** 评价口径版本（含可配置阈值版本，§20.4「阈值必须可配置并保存版本」） */
    private String definitionVersion;

    /**
     * 评价窗口长度（天）。契约 §3.5 未列此列，但前端 web/src/utils/tables.js#evaluationRows
     * 一直读取 {@code evalWindowDays} 展示「窗口 N 天」，此前恒为空；随窗口口径落库后语义完整。
     */
    private Integer evalWindowDays;
}
