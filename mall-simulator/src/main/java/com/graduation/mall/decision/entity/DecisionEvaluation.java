package com.graduation.mall.decision.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 决策效果评价（§21.10）：前后对比，标注"非因果推断"。
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
}