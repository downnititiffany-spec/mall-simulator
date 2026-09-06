package com.graduation.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

/**
 * 库存（§20.1）：乐观锁 version，库存不得为负。
 */
@Data
@TableName("inventory")
public class Inventory {

    @TableId(type = IdType.INPUT)
    private Long productId;

    private Integer availableQty;

    private Integer reservedQty;

    @Version
    private Integer version;
}