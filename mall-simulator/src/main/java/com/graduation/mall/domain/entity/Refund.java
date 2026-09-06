package com.graduation.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款（§20.1）：退款金额不得大于已付金额。
 */
@Data
@TableName("refund")
public class Refund {

    @TableId(type = IdType.ASSIGN_ID)
    private Long refundId;

    private Long orderId;

    private Long userId;

    private BigDecimal amount;

    private String reason;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}