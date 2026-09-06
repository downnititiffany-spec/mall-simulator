package com.graduation.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单（§20.1）：金额由订单项计算；状态流转由订单状态机校验。
 */
@Data
@TableName("mall_order")
public class MallOrder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long orderId;

    private Long userId;

    private String status;

    private BigDecimal totalAmount;

    private LocalDateTime createdAt;

    private LocalDateTime paidAt;

    private LocalDateTime completedAt;

    private LocalDateTime cancelledAt;

    private String cancelReason;
}