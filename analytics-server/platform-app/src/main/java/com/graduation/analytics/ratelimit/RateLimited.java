package com.graduation.analytics.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Per-authenticated-user, per-operation fixed-window request budget. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {
    String scope();
    int requests() default 10;
    int windowSeconds() default 60;
}
