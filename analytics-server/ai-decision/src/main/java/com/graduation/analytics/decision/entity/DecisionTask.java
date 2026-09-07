package com.graduation.analytics.decision.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 决策任务（§22.6）：AI 只能创建 DRAFT；批准必须锁定基线；效果评价闭环。
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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime evaluatedAt;
}