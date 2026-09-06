package com.graduation.mall.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（sys_user，V7__security_auth.sql）：username 唯一，password_hash 为 BCrypt；
 * status 1 启用 / 0 禁用；role 为 admin | operator | analyst | data_dev。
 */
@Data
@TableName("sys_user")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String realName;

    private String role;

    private Integer status;

    private LocalDateTime createdAt;
}