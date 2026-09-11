package com.graduation.analytics.decision.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 决策任务（§22.6 / R8-3 §20.2-§20.3）：AI 只能创建 DRAFT；批准必须锁定基线快照；
 * 效果评价按等长窗口闭环，窗口内数据不足不得判无效。
 */
@Data
@TableName("decision_task")
public class DecisionTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String decisionNo;

    private String source;

    private String suggestionSnapshotId;

    private String title;

    private String action;

    private String targetMetricCode;

    private String targetDirection;

    private BigDecimal baselineValue;

    private BigDecimal targetValue;

    private Integer evalWindowDays;

    private String owner;

    private LocalDate dueDate;

    private String status;

    private String risk;

    private String rejectReason;

    private String cancelReason;

    private String createdBy;

    private String approvedBy;

    /**
     * R8-3 V14 补列：批准时钉住的基线快照（§20.4「前快照」）。
     * 评价的 baseline 只认这个快照，防止事后换快照伪造基线。
     */
    private String baselineSnapshotId;

    /** R8-3 V14 补列：目标指标的口径版本（批准时快照行的 definition_version） */
    private String definitionVersion;

    /** R8-3 V14 补列：AI 建议对应的证据包 id（§20.3 提交审批前必须齐备） */
    private String evidencePackageId;

    /** R8-3 V14 补列：审批备注（批准/驳回审批意见） */
    private String approvalNote;

    /** R8-3 V14 补列：执行备注（执行过程记录） */
    private String executionNote;

    /** R8-3 V14 补列：批准时间（§20.4 基线窗口 [approvedDate-N+1, approvedDate] 的唯一依据） */
    private LocalDateTime approvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime evaluatedAt;
}