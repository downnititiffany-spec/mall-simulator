package com.graduation.mall.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子随机工具箱单元测试：确定性 + 分布边界（§20.6 可复现要求）。
 */
class DistributionKitTest {

    @Test
    void 同种子同调用序列结果一致() {
        int[] a = sample(42);
        int[] b = sample(42);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], "第 " + i + " 次抽样不一致");
        }
    }

    @Test
    void 不同种子序列不同() {
        int[] a = sample(1);
        int[] b = sample(2);
        boolean anyDiff = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                anyDiff = true;
                break;
            }
        }
        assertTrue(anyDiff, "不同种子应当产生不同序列");
    }

    @Test
    void 对数正态价格被截断到区间内() {
        DistributionKit kit = new DistributionKit(7);
        for (int i = 0; i < 2000; i++) {
            double v = kit.boundedLogNormal(9.9, 120, 999);
            assertTrue(v >= 9.9 && v <= 999, "越界: " + v);
        }
    }

    @Test
    void 加权抽样只返回合法索引() {
        DistributionKit kit = new DistributionKit(3);
        double[] w = {1, 2, 3};
        for (int i = 0; i < 500; i++) {
            int idx = kit.weightedPick(w);
            assertTrue(idx >= 0 && idx < 3);
        }
    }

    private int[] sample(long seed) {
        DistributionKit kit = new DistributionKit(seed);
        int[] out = new int[20];
        for (int i = 0; i < 20; i++) {
            out[i] = kit.nextInt(100);
        }
        return out;
    }
}