package com.graduation.analytics.mapping.activation;

import com.graduation.analytics.mapping.MappingHash;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 进程内激活指针存储，**仅测试作用域**。
 *
 * <p><b>它不是"正式 active pointer"</b>（总控口径：禁止把 InMemoryMap 当成正式激活状态）。
 * S2-03.1 起正式存储是表 {@code source_mapping_active}（V21 迁移），生产装配由
 * {@code MappingActivationPersistenceConfig} 装 {@link JdbcActiveMappingPointerStore}；
 * 本类只在 L0 单测里替代它（无 DB 的 JUnit 环境）。真库往返与并发由 MySqlIT 取证。</p>
 *
 * <p>它存在的理由：被测逻辑（{@code MappingActivationService}、{@code SourceMapper} 的激活门）
 * 只依赖 {@link ActiveMappingPointerStore} 的 {@code find}/{@code lockBySourceId}/{@code save} 语义，
 * 替身如实实现这三个语义，并**记录每次写入**，使"幂等时不写、换 checksum 时写、未激活时不写"
 * 这些断言可被机械观测。</p>
 *
 * <p>{@code lockBySourceId} 在这里与 {@code find} 同义：单线程测试里不存在"快照读看到旧版本"
 * 的窗口，锁语义没有可观测差异。**它不能证明真库的锁行为**——那由 MySqlIT 取证
 * （两事务并发 activate，断言只产生一次写入）。</p>
 */
public class TestActiveMappingPointerStore implements ActiveMappingPointerStore {

    private final Map<Long, ActiveMappingPointer> pointers = new LinkedHashMap<>();
    private final List<ActiveMappingPointer> writes = new ArrayList<>();

    /**
     * 造一个"某源已激活某一份画像"的指针。除判据相关的字段（源、路径、版本、画像哈希）外，
     * 其余字段填与判据无关的固定值——采集侧只比对 {@code profilePath} 与 {@code profileChecksum}，
     * 契约侧的一致性在激活时已判过（该不对称性在 {@code SourceMapper} 类注释里明确登记）。
     */
    public static TestActiveMappingPointerStore withActive(long sourceId, String sourceCode,
                                                           String profilePath, String profileVersion,
                                                           String profileChecksum) {
        TestActiveMappingPointerStore store = new TestActiveMappingPointerStore();
        store.seed(new ActiveMappingPointer(sourceId, sourceCode, profilePath, profileVersion, profileChecksum,
                "1.0", MappingHash.sha256Hex("test-contract-bytes"), "dr-test-0001",
                LocalDateTime.parse("2026-01-01T00:00:00"), "test"));
        return store;
    }

    public void seed(ActiveMappingPointer pointer) {
        pointers.put(pointer.sourceId(), pointer);
    }

    /** 所有发生过的写入（按顺序）：用于断言"该写时写了、不该写时一次都没写"。 */
    public List<ActiveMappingPointer> writes() {
        return List.copyOf(writes);
    }

    /** 当前指针（断言可读；与 {@link #find(long)} 同源）。 */
    public Optional<ActiveMappingPointer> current(long sourceId) {
        return Optional.ofNullable(pointers.get(sourceId));
    }

    @Override
    public Optional<ActiveMappingPointer> find(long sourceId) {
        return Optional.ofNullable(pointers.get(sourceId));
    }

    @Override
    public Optional<ActiveMappingPointer> lockBySourceId(long sourceId) {
        return find(sourceId);
    }

    @Override
    public void save(ActiveMappingPointer pointer) {
        writes.add(pointer);
        pointers.put(pointer.sourceId(), pointer);
    }
}
