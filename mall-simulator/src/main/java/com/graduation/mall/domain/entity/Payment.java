package com.graduation.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付（§20.1）：首版模拟支付，不接真实支付渠道。
 */
@Data
@TableName("payment")
public class Payment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long paymentId;

    private Long orderId;

    private Long userId;

    private BigDecimal amount;

    private String status;

    private LocalDateTime paidAt;
}