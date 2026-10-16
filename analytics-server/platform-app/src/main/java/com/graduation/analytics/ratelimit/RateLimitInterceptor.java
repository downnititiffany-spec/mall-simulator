package com.graduation.analytics.ratelimit;

import com.graduation.analytics.auth.AuthenticationRequiredException;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final InMemoryRequestRateLimiter limiter;

    public RateLimitInterceptor(InMemoryRequestRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited policy = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RateLimited.class);
        if (policy == null) {
            return true;
        }
        CurrentUser user = CurrentUserHolder.get();
        if (user == null) {
            throw new AuthenticationRequiredException("未登录或会话已过期");
        }
        String key = user.userId() + ":" + policy.scope();
        InMemoryRequestRateLimiter.Permit permit = limiter.acquire(key, policy.requests(), policy.windowSeconds());
        if (permit.allowed()) {
            return true;
        }
        throw new RateLimitExceededException(permit.retryAfterSeconds());
    }
}
