package com.graduation.mall.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为状态链单元测试（§20.3：订单生命期有限状态）。
 */
class BehaviorChainTest {

    @Test
    void 下单后概率0必取消概率1必支付() {
        DistributionKit rng0 = new DistributionKit(1);
        assertEquals(BehaviorChain.CANCELLED,
                BehaviorChain.next(BehaviorChain.ORDER_CREATED, rng0, 0.0, 0.05));
        DistributionKit rng1 = new DistributionKit(1);
        assertEquals(BehaviorChain.PAID,
                BehaviorChain.next(BehaviorChain.ORDER_CREATED, rng1, 1.0, 0.05));
    }

    @Test
    void 支付后概率边界决定完成或退款() {
        DistributionKit rng0 = new DistributionKit(2);
        assertEquals(BehaviorChain.COMPLETED,
                BehaviorChain.next(BehaviorChain.PAID, rng0, 1.0, 0.0));
        DistributionKit rng1 = new DistributionKit(2);
        assertEquals(BehaviorChain.REFUNDING,
                BehaviorChain.next(BehaviorChain.PAID, rng1, 1.0, 1.0));
    }

    @Test
    void 退款中必到已退款_终态不再前进() {
        DistributionKit rng = new DistributionKit(3);
        assertEquals(BehaviorChain.REFUNDED, BehaviorChain.next(BehaviorChain.REFUNDING, rng, 1.0, 1.0));
        assertNull(BehaviorChain.next(BehaviorChain.REFUNDED, rng, 1.0, 1.0));
        assertNull(BehaviorChain.next(BehaviorChain.CANCELLED, rng, 1.0, 1.0));
        assertNull(BehaviorChain.next("UNKNOWN_STATE", rng, 1.0, 1.0));
    }

    @Test
    void 概率边界被截断() {
        // 1.5 → 截断为 0.95：结果只能是 PAID 或 CANCELLED（不抛异常）
        DistributionKit rng = new DistributionKit(4);
        String r = BehaviorChain.next(BehaviorChain.ORDER_CREATED, rng, 1.5, 0);
        assertTrue(BehaviorChain.PAID.equals(r) || BehaviorChain.CANCELLED.equals(r));
        // −0.5 → 截断为 0 → 必取消
        DistributionKit rng2 = new DistributionKit(4);
        assertEquals(BehaviorChain.CANCELLED, BehaviorChain.next(BehaviorChain.ORDER_CREATED, rng2, -0.5, 0));
    }
}