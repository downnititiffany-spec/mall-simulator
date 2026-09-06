package com.graduation.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品（§20.1）：price 不小于 cost；状态可追踪。
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.ASSIGN_ID)
    private Long productId;

    private String productName;

    private Long categoryId;

    private Long brandId;

    private BigDecimal price;

    private BigDecimal cost;

    private String status;

    private LocalDateTime createdAt;
}