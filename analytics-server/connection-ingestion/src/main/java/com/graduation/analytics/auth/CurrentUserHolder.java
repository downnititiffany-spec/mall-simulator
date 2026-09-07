package com.graduation.analytics.auth;

/**
 * 当前登录用户 ThreadLocal 持有器：AuthInterceptor.preHandle 写入，
 * afterCompletion 清理，避免线程池复用导致串号。
 */
public final class CurrentUserHolder {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    public static void set(CurrentUser current) {
        HOLDER.set(current);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}