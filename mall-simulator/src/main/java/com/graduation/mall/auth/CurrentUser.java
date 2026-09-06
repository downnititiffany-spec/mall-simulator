package com.graduation.mall.auth;

/**
 * 当前登录用户快照（AuthInterceptor 校验通过后放入 ThreadLocal 供本次请求使用）。
 */
public record CurrentUser(Long userId, String username, String role) {
}