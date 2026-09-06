package com.graduation.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商城用户（§20.1）：只使用模拟属性，不生成真实个人信息。
 */
@Data
@TableName("mall_user")
public class MallUser {

    @TableId(type = IdType.ASSIGN_ID)
    private Long userId;

    private String ageGroup;

    private String cityLevel;

    private String memberLevel;

    private LocalDateTime registerTime;

    private LocalDateTime createdAt;
}