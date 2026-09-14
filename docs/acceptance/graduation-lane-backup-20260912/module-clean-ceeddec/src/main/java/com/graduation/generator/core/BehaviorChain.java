package com.graduation.generator.core;

/**
 * 行为/订单有限状态链（§20.3）：
 *   ORDER_CREATED → {PAID, CANCELLED}
 *   PAID          → {COMPLETED, REFUNDING}
 *   REFUNDING     → {REFUNDED}
 * 纯函数、无副作用，便于单元测试与确定性生成。
 */
public final class BehaviorChain {

    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";
    public static final String COMPLETED = "COMPLETED";
    public static final String REFUNDING = "REFUNDING";
    public static final String REFUNDED = "REFUNDED";

    private BehaviorChain() {
    }

    /**
     * 从当前订单阶段投掷下一步。
     *
     * @param current        当前状态
     * @param rng            种子随机源
     * @param payProb        下单后支付概率（业务基线，场景不重复折扣）
     * @param refundProb     支付后退款概率（场景修正）
     * @return 下一状态或 null（无后续动作）
     */
    public static String next(String current, DistributionKit rng,
                              double payProb, double refundProb) {
        return switch (current) {
            case ORDER_CREATED -> rng.chance(clamp(payProb)) ? PAID : CANCELLED;
            case PAID -> rng.chance(clamp(refundProb)) ? REFUNDING : COMPLETED;
            case REFUNDING -> REFUNDED;
            default -> null;
        };
    }

    private static double clamp(double p) {
        return Math.max(0, Math.min(0.95, p));
    }
}
