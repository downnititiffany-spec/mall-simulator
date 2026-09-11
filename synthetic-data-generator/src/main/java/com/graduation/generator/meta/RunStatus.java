package com.graduation.generator.meta;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 运行状态机（V2.1 §4.2 原文：{@code PENDING -> RUNNING -> SUCCESS/FAILED/CANCELLED}）。
 *
 * <p>状态迁移收在一个枚举里，是为了让"非法状态跳转"这种问题在单元测试层面就暴露，
 * 而不是等运行中写库才发现（例如把 CANCELLED 又改成 SUCCESS）。</p>
 *
 * <p><b>对指导书的一处受控扩展</b>：原文只画了 {@code PENDING -> RUNNING}。实际存在"任务已入队但尚未开始执行
 * 就被取消"的真实情形，因此额外允许 {@code PENDING -> CANCELLED}；除此之外不放宽任何迁移，且终态不可再变。
 * 该扩展已登记在《开发过程事实与决策记录》。</p>
 */
public enum RunStatus {

    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED;

    private static final Set<RunStatus> TERMINAL = Collections.unmodifiableSet(
            EnumSet.of(SUCCESS, FAILED, CANCELLED));

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 允许的下一步状态（终态返回空集） */
    public Set<RunStatus> allowedNext() {
        return switch (this) {
            case PENDING -> Collections.unmodifiableSet(EnumSet.of(RUNNING, CANCELLED));
            case RUNNING -> Collections.unmodifiableSet(EnumSet.of(SUCCESS, FAILED, CANCELLED));
            case SUCCESS, FAILED, CANCELLED -> Collections.emptySet();
        };
    }

    public boolean canTransitionTo(RunStatus next) {
        return next != null && allowedNext().contains(next);
    }

    /** 库中取值一律大写；未知值必须显式报错，不得静默当作 PENDING */
    public static RunStatus fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("generation_run.status 不得为空（§4.2）");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知运行状态：" + value + "（合法值 " + EnumSet.allOf(RunStatus.class) + "）");
        }
    }
}
