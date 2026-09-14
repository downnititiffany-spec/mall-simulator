package com.graduation.generator.core;

import java.util.Random;

/**
 * 种子抽样工具箱（§20.3）：全部随机数来自同一个 Random(seed)，
 * 按固定顺序消费 → 相同种子 + 相同调用序列 = 完全相同的数据。
 */
public final class DistributionKit {

    private final Random random;

    public DistributionKit(long seed) {
        this.random = new Random(seed);
    }

    /** 按权重数组抽样，返回索引 */
    public int weightedPick(double[] weights) {
        double total = 0;
        for (double w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("权重不能为负");
            }
            total += w;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("权重和必须 > 0");
        }
        double r = random.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (r <= acc) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /**
     * Zipf 权重（§20.2）：w_i = 1 / rank^s，s ∈ (0.8, 1.2]。
     * 造成少量头部商品获得大量曝光的“长尾”结构。
     */
    public static double zipfWeight(int rank, double s) {
        return 1.0 / Math.pow(rank, s);
    }

    /**
     * 截断对数正态价格（§20.2）：
     * rawPrice = exp(N(μ, σ))，μ 取 log(median)，σ 由中位数与上下限推导；
     * price = clamp(raw, min, max)。
     */
    public double boundedLogNormal(double min, double median, double max) {
        double mu = Math.log(median);
        // 让约 95% 样本落在 [median/4, median*4]，继续被 clamp 到 [min,max]
        double sigma = Math.log(4) / 1.96;
        double raw = Math.exp(mu + sigma * random.nextGaussian());
        return Math.max(min, Math.min(max, raw));
    }

    public double uniform(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public boolean chance(double p) {
        return random.nextDouble() < p;
    }
}
