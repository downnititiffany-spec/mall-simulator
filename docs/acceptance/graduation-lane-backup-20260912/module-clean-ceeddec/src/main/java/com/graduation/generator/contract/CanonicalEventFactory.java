package com.graduation.generator.contract;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 信封工厂：把"载荷 + 时间"组装成契约完备的 {@link CanonicalEvent}。
 *
 * <p>信封常量（{@code source_system} / {@code schema_version}）只在这里取，调用方无法各写各的。
 * 文件模式（{@code CANONICAL_EVENT_FILE}）下 {@code ingest_time} 即产出时刻——采集侧还没有参与进来，
 * 因此"链路延迟"在这个模式里恒为产出延迟，这一点不得被读成商城端到端延迟（§3.3 B）。</p>
 *
 * <p><b>可复现</b>（§4.2）：ID 生成器可通过构造参数注入。批量生成必须注入确定性 ID 生成器
 * （如"前缀 + 序号"），否则同一 seed 两次运行的事件 ID 不同、checksum 对不上。</p>
 */
public final class CanonicalEventFactory {

    /** 默认 ID 生成器：UUID（不可复现，仅用于单条手工验证） */
    private static final Supplier<String> RANDOM_IDS = () -> UUID.randomUUID().toString();

    private final Supplier<String> eventIds;
    private final Supplier<String> traceIds;

    public CanonicalEventFactory(Supplier<String> eventIds, Supplier<String> traceIds) {
        this.eventIds = eventIds;
        this.traceIds = traceIds;
    }

    public static CanonicalEventFactory random() {
        return new CanonicalEventFactory(RANDOM_IDS, RANDOM_IDS);
    }

    /** 单条事件（ID 随机；测试与手工验证用） */
    public static CanonicalEvent of(String eventType, Map<String, Object> payload, String eventTime,
                                    Instant ingestTime) {
        return random().create(eventType, payload, eventTime, ingestTime);
    }

    public CanonicalEvent create(String eventType, Map<String, Object> payload, String eventTime,
                                 Instant ingestTime) {
        return new CanonicalEvent(eventIds.get(), eventType, ContractFormat.requireTime(eventTime),
                ContractFormat.time(ingestTime), ContractFormat.SOURCE_SYSTEM,
                ContractFormat.SCHEMA_VERSION, traceIds.get(), payload);
    }

    // ---------- 常用事件的便捷装配（载荷校验一律委托给 CanonicalPayloads） ----------

    public CanonicalEvent behavior(String userId, String productId, String sessionId,
                                   String behaviorType, String channel, String eventTime, Instant ingestTime) {
        return create(EventTypes.BEHAVIOR,
                CanonicalPayloads.behavior(userId, productId, sessionId, behaviorType, channel),
                eventTime, ingestTime);
    }

    public CanonicalEvent userRegistered(String userId, String ageGroup, String cityLevel,
                                         String memberLevel, String registerTime, Instant ingestTime) {
        return create(EventTypes.USER_REGISTERED,
                CanonicalPayloads.userRegistered(userId, ageGroup, cityLevel, memberLevel, registerTime),
                registerTime, ingestTime);
    }
}
