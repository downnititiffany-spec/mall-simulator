package com.graduation.analytics.ai;

public final class AiFeedbackConflictException extends RuntimeException {
    public AiFeedbackConflictException() {
        super("该问答历史已经提交过反馈");
    }
}
