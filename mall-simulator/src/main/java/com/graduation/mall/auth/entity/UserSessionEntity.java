package com.graduation.mall.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录会话（user_session，V7__security_auth.sql）：token 为去横线 UUID（32 位），
 * 有效期 expires_at = 登录时刻 + 24 小时；login_ip 记录来源 IP。
 */
@Data
@TableName("user_session")
public class UserSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String token;

    private Long userId;

    private LocalDateTime expiresAt;

    private String loginIp;

    private LocalDateTime createdAt;
}