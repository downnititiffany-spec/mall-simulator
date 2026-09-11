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
 * <p>窗口口径：
 * baseline 窗口 = [approvedDate-N+1, approvedDate]（批准前 N 天），读 {@code baselineSnapshotId} 钉住的快照；
 * actual 窗口 = [completedDate+1, completedDate+N]（完成后 N 天），读评价时的最新 ACTIVE 快照。
 * {@code windowStart/windowEnd} 保存的是 **actual 评价窗口**，其长度等于 {@code evalWindowDays}。</p>
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

    /** 前快照：批准时钉住的基线快照 id */
    private String baselineSnapshotId;

    /** 后快照：评价时读取的最新已发布快照 id */
    private String actualSnapshotId;

    /** 评价窗口起（= completedDate+1） */
    private LocalDate windowStart;

    /** 评价窗口止（= completedDate+N） */
    private LocalDate windowEnd;

    /** 窗口内基线聚合值（当前口径：快照粒度观测值，见 DecisionService 说明） */
    private BigDecimal baselinePeriodValue;

    /** 窗口内实际聚合值 */
    private BigDecimal actualPeriodValue;

    /** 参与聚合的样本观测数（baseline + actual 的指标行数） */
    private Integer sampleCount;

    /** 评价口径版本（含可配置阈值版本，§20.4「阈值必须可配置并保存版本」） */
    private String definitionVersion;

    /**
     * 评价窗口长度（天）。契约 §3.5 未列此列，但前端 web/src/utils/tables.js#evaluationRows
     * 一直读取 {@code evalWindowDays} 展示「窗口 N 天」，此前恒为空；随窗口口径落库后语义完整。
     */
    private Integer evalWindowDays;
}
