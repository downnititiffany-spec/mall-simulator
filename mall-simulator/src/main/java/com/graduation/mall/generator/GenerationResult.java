package com.graduation.mall.generator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 一次生成运行的摘要（§20.6 验收依据：同种子同摘要；场景方向可识别）。
 */
public record GenerationResult(
        String configKey,
        String scenario,
        ExpectedEffect expectedEffect,
        long usersCreated,
        long productsCreated,
        Map<String, Long> behaviorsByType,
        long ordersCreated,
        long ordersPaid,
        long ordersCancelled,
        long ordersCompleted,
        long refundsApplied,
        long refundsCompleted,
        BigDecimal gmv,
        BigDecimal netSale,
        BigDecimal avgOrderValue,
        long stockShortageHits,
        long totalEvents,
        List<String> sampleEventIds,
        List<DirtyDataInjector.DirtySample> dirtySamples) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String configKey;
        private String scenario;
        private ExpectedEffect expectedEffect;
        private long usersCreated;
        private long productsCreated;
        private Map<String, Long> behaviorsByType = Map.of();
        private long ordersCreated;
        private long ordersPaid;
        private long ordersCancelled;
        private long ordersCompleted;
        private long refundsApplied;
        private long refundsCompleted;
        private BigDecimal gmv = BigDecimal.ZERO;
        private BigDecimal netSale = BigDecimal.ZERO;
        private BigDecimal avgOrderValue = BigDecimal.ZERO;
        private long stockShortageHits;
        private long totalEvents;
        private List<String> sampleEventIds = List.of();
        private List<DirtyDataInjector.DirtySample> dirtySamples = List.of();

        public Builder configKey(String v) {
            this.configKey = v;
            return this;
        }

        public Builder scenario(String v) {
            this.scenario = v;
            return this;
        }

        public Builder expectedEffect(ExpectedEffect v) {
            this.expectedEffect = v;
            return this;
        }

        public Builder usersCreated(long v) {
            this.usersCreated = v;
            return this;
        }

        public Builder productsCreated(long v) {
            this.productsCreated = v;
            return this;
        }

        public Builder behaviorsByType(Map<String, Long> v) {
            this.behaviorsByType = v;
            return this;
        }

        public Builder ordersCreated(long v) {
            this.ordersCreated = v;
            return this;
        }

        public Builder ordersPaid(long v) {
            this.ordersPaid = v;
            return this;
        }

        public Builder ordersCancelled(long v) {
            this.ordersCancelled = v;
            return this;
        }

        public Builder ordersCompleted(long v) {
            this.ordersCompleted = v;
            return this;
        }

        public Builder refundsApplied(long v) {
            this.refundsApplied = v;
            return this;
        }

        public Builder refundsCompleted(long v) {
            this.refundsCompleted = v;
            return this;
        }

        public Builder gmv(BigDecimal v) {
            this.gmv = v;
            return this;
        }

        public Builder netSale(BigDecimal v) {
            this.netSale = v;
            return this;
        }

        public Builder avgOrderValue(BigDecimal v) {
            this.avgOrderValue = v;
            return this;
        }

        public Builder stockShortageHits(long v) {
            this.stockShortageHits = v;
            return this;
        }

        public Builder totalEvents(long v) {
            this.totalEvents = v;
            return this;
        }

        public Builder sampleEventIds(List<String> v) {
            this.sampleEventIds = v;
            return this;
        }

        public Builder dirtySamples(List<DirtyDataInjector.DirtySample> v) {
            this.dirtySamples = v;
            return this;
        }

        public GenerationResult build() {
            return new GenerationResult(configKey, scenario, expectedEffect, usersCreated, productsCreated,
                    behaviorsByType, ordersCreated, ordersPaid, ordersCancelled, ordersCompleted,
                    refundsApplied, refundsCompleted, gmv, netSale, avgOrderValue,
                    stockShortageHits, totalEvents, sampleEventIds, dirtySamples);
        }
    }
}