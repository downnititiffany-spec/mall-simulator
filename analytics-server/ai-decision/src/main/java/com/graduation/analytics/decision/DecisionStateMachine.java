package com.graduation.analytics.decision;

/**
 * 决策状态机（§22.6）：
 * DRAFT → PENDING_REVIEW → APPROVED → IN_PROGRESS → COMPLETED → EVALUATING
 *   → EFFECTIVE | PARTIAL | INEFFECTIVE | INSUFFICIENT_DATA
 * 审核前任意状态可 REJECTED；执行中可 CANCELLED（必须记录原因）。
 * 纯逻辑，可单元测试。
 */
public final class DecisionStateMachine {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String EVALUATING = "EVALUATING";
    public static final String EFFECTIVE = "EFFECTIVE";
    public static final String PARTIAL = "PARTIAL";
    public static final String INEFFECTIVE = "INEFFECTIVE";
    public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    private DecisionStateMachine() {
    }

    /** 合法流转判定 */
    public static boolean allowed(String from, String to) {
        return switch (from) {
            case DRAFT -> to.equals(PENDING_REVIEW) || to.equals(REJECTED);
            case PENDING_REVIEW -> to.equals(APPROVED) || to.equals(REJECTED);
            case APPROVED -> to.equals(IN_PROGRESS);
            case IN_PROGRESS -> to.equals(COMPLETED) || to.equals(CANCELLED);
            case COMPLETED -> to.equals(EVALUATING);
            case EVALUATING -> to.equals(EFFECTIVE) || to.equals(PARTIAL)
                    || to.equals(INEFFECTIVE) || to.equals(INSUFFICIENT_DATA);
            default -> false; // 终态（EFFECTIVE/PARTIAL/INEFFECTIVE/INSUFFICIENT_DATA/REJECTED/CANCELLED）
        };
    }

    /**
     * 校验流转，失败抛 {@link IllegalDecisionStateException}。
     */
    public static void validate(String from, String to) {
        if (!allowed(from, to)) {
            throw new IllegalDecisionStateException(from, to);
        }
    }

    public static class IllegalDecisionStateException extends RuntimeException {
        private final String from;
        private final String to;

        public IllegalDecisionStateException(String from, String to) {
            super("非法决策状态流转: " + from + " -> " + to);
            this.from = from;
            this.to = to;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }
    }
}