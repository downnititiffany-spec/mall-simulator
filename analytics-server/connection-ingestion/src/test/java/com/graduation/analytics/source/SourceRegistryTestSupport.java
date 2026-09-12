package com.graduation.analytics.source;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.graduation.analytics.source.entity.SourceRegistry;
import com.graduation.analytics.source.mapper.ActiveSourceBindingMapper;
import com.graduation.analytics.source.mapper.SourceRegistryMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-03 E2 测试替身（test-only，无生产代码）：内存版 {@code source_registry} 表 + 内存版
 * {@code runtime_profile} 的「唯一 ACTIVE 行 + source_id 绑定列」。
 *
 * <p><b>如实登记的保真度边界</b>：本替身只服务 E2（surefire，不连库）的**逻辑**断言。
 * 真库语义——InnoDB `SELECT ... FOR UPDATE` 的行锁、`@Transactional` 回滚——由 E3 的
 * 真 HTTP + 真 MySQL（副本库 analytics_meta_p103）取证，本替身不做任何「我猜数据库会这样」的假设：
 * </p>
 * <ul>
 *   <li>{@link #lockActive()} 用 {@link ReentrantLock} 模拟 {@code FOR UPDATE}：**由测试线程在
 *       「事务边界」处显式释放**（{@link #endTransaction()}），因为 E2 里没有 Spring 事务管理器可以
 *       代劳。这样替身持有锁的窗口与服务方法调用的窗口一致（真库里是 lock → … → commit）。</li>
 *   <li>服务实现刻意把「可能抛错」的判断全部放在**任何写操作之前**（见
 *       {@code SourceRegistryServiceImpl} 的步骤顺序注释），因此 E2 不需要模拟回滚也能与真库同构。</li>
 * </ul>
 */
final class SourceRegistryTestSupport {

    private SourceRegistryTestSupport() {
    }

    /** 内存表：source_registry + runtime_profile(ACTIVE 单行绑定列) */
    static final class InMemoryStore {

        private final Map<Long, SourceRegistry> sources = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        /** 模拟 runtime_profile：只保留「唯一 ACTIVE 行」的 id 与它的 source_id 绑定列 */
        private Long activeProfileId = 1L;
        private Long activeSourceId;

        /** 模拟 {@code SELECT ... FOR UPDATE} 的行锁（跨线程互斥，直到 endTransaction） */
        private final ReentrantLock rowLock = new ReentrantLock();

        /** 写操作计数（用于「没有静默丢失的写」断言，见 SourceRegistryConcurrencyTest） */
        private int inserts;
        private int statusUpdates;
        private int bindingWrites;

        synchronized int writeCount() {
            return inserts + statusUpdates + bindingWrites;
        }

        synchronized int bindingWriteCount() {
            return bindingWrites;
        }

        synchronized SourceRegistry insert(SourceRegistry entity) {
            inserts++;
            long id = sequence.incrementAndGet();
            SourceRegistry stored = copy(entity);
            stored.setId(id);
            if (stored.getCreatedAt() == null) {
                stored.setCreatedAt(LocalDateTime.now());
            }
            if (stored.getUpdatedAt() == null) {
                stored.setUpdatedAt(LocalDateTime.now());
            }
            sources.put(id, stored);
            return copy(stored);
        }

        synchronized SourceRegistry selectById(Long id) {
            SourceRegistry stored = sources.get(id);
            return stored == null ? null : copy(stored);
        }

        /**
         * 模拟 {@code SELECT * FROM source_registry WHERE id=? FOR UPDATE}。
         *
         * <p>替身里**不再单独加锁**：本替身只有 {@link #lockActive()} 一个串行化点，
         * 而服务层的固定顺序是"先锁 ACTIVE 行、再读源行"，所以走到这里时同源的所有
         * activate/pause 已经被串行化了，再加一把锁既不会提高保真度，
         * 还会因为 {@link ReentrantLock} 的持有计数与 {@link #endTransaction()} 的单次 unlock 对不上而漏放锁。
         * 真库上 source_registry 行级 {@code FOR UPDATE} 的语义由 E3 真 HTTP + 真 MySQL 取证。</p>
         */
        synchronized SourceRegistry lockById(Long id) {
            SourceRegistry stored = sources.get(id);
            return stored == null ? null : copy(stored);
        }

        synchronized List<SourceRegistry> selectAllOrderedByIdDesc() {
            List<SourceRegistry> all = new ArrayList<>();
            sources.values().stream()
                    .sorted(Comparator.comparing(SourceRegistry::getId).reversed())
                    .forEach(row -> all.add(copy(row)));
            return all;
        }

        synchronized long countBySourceCode(String sourceCode) {
            return sources.values().stream()
                    .filter(row -> row.getSourceCode().equals(sourceCode))
                    .count();
        }

        synchronized void updateById(SourceRegistry entity) {
            SourceRegistry stored = sources.get(entity.getId());
            if (stored == null) {
                return;
            }
            statusUpdates++;
            if (entity.getSourceCode() != null) {
                stored.setSourceCode(entity.getSourceCode());
            }
            if (entity.getDisplayName() != null) {
                stored.setDisplayName(entity.getDisplayName());
            }
            if (entity.getIngestMode() != null) {
                stored.setIngestMode(entity.getIngestMode());
            }
            if (entity.getProfilePath() != null) {
                stored.setProfilePath(entity.getProfilePath());
            }
            if (entity.getTimezone() != null) {
                stored.setTimezone(entity.getTimezone());
            }
            if (entity.getCurrency() != null) {
                stored.setCurrency(entity.getCurrency());
            }
            if (entity.getStatus() != null) {
                stored.setStatus(entity.getStatus());
            }
            if (entity.getProfileVersion() != null) {
                stored.setProfileVersion(entity.getProfileVersion());
            }
            // P2-07：warehouse_prefix 与其余字段同语义（partial update 由服务层保证性）
            if (entity.getWarehousePrefix() != null) {
                stored.setWarehousePrefix(entity.getWarehousePrefix());
            }
            stored.setUpdatedAt(LocalDateTime.now());
        }

        // ---- runtime_profile 侧（当前激活源的唯一所有者：ACTIVE 行的 source_id） ----

        /**
         * 模拟 {@code SELECT id, source_id FROM runtime_profile WHERE status='ACTIVE' ... FOR UPDATE}。
         *
         * <p>刻意**不**在持有对象监视器时去抢行锁（否则 T1 持行锁等监视器、T2 持监视器等行锁 → 自造死锁）。
         * 绑定值在**取得行锁之后**读取——与真库一致：{@code FOR UPDATE} 的锁定读看到的是最新已提交值。</p>
         */
        ActiveSourceBindingMapper.ActiveProfileBinding lockActive() {
            synchronized (this) {
                if (activeProfileId == null) {
                    return null;
                }
            }
            rowLock.lock();
            synchronized (this) {
                return activeProfileId == null
                        ? null
                        : new ActiveSourceBindingMapper.ActiveProfileBinding(activeProfileId, activeSourceId);
            }
        }

        /** 模拟 {@code UPDATE runtime_profile SET source_id=? WHERE id=?} */
        synchronized void bind(Long profileId, Long sourceId) {
            if (profileId.equals(activeProfileId)) {
                bindingWrites++;
                this.activeSourceId = sourceId;
            }
        }

        /** 模拟**不加锁**的只读绑定查询（读路径用；与 {@link #lockActive()} 分开是刻意的） */
        synchronized ActiveSourceBindingMapper.ActiveProfileBinding findActive() {
            return activeProfileId == null
                    ? null
                    : new ActiveSourceBindingMapper.ActiveProfileBinding(activeProfileId, activeSourceId);
        }

        /** 释放「事务」持有的行锁（真库里由 commit/rollback 释放） */
        void endTransaction() {
            if (rowLock.isHeldByCurrentThread()) {
                rowLock.unlock();
            }
        }

        /**
         * 模拟 Spring {@code @Transactional} 的边界：服务方法返回或抛出时释放行锁。
         * E2（无 Spring 事务管理器的纯单元测试）必须由测试显式声明这个边界，否则替身持有的锁不会被释放。
         * 真库上的同一语义由 E3 真 HTTP + 真 MySQL 取证。
         */
        <T> T inTransaction(java.util.function.Supplier<T> body) {
            try {
                return body.get();
            } finally {
                endTransaction();
            }
        }

        /** 当前绑定的源（无绑定 → null）；读路径用它派生 DTO 的 current 字段 */
        synchronized Long activeSourceId() {
            return activeSourceId;
        }

        /** 模拟「唯一 ACTIVE runtime_profile 行数」——本替身固定 1（有 ACTIVE 行时） */
        synchronized int activeRuntimeProfileCount() {
            return activeProfileId == null ? 0 : 1;
        }

        synchronized void clearActiveProfile() {
            activeProfileId = null;
            activeSourceId = null;
        }

        synchronized SourceRegistry rowByCode(String sourceCode) {
            return sources.values().stream()
                    .filter(row -> row.getSourceCode().equals(sourceCode))
                    .findFirst()
                    .map(SourceRegistryTestSupport::copy)
                    .orElse(null);
        }

        /** 已登记的源编码（断言用） */
        synchronized List<String> sourceCodes() {
            return sources.values().stream().map(SourceRegistry::getSourceCode).toList();
        }

        private static SourceRegistry copy(SourceRegistry row) {
            return SourceRegistryTestSupport.copy(row);
        }
    }

    /** {@link SourceRegistryMapper} 的替身：BaseMapper 的四个方法 + 自定 SQL 接到内存表上 */
    static SourceRegistryMapper mapper(InMemoryStore store) {
        SourceRegistryMapper mapper = mock(SourceRegistryMapper.class);
        when(mapper.insert(any(SourceRegistry.class)))
                .thenAnswer(invocation -> {
                    SourceRegistry entity = invocation.getArgument(0);
                    // 与 MyBatis-Plus + useGeneratedKeys 一致：自增主键要**回写**到入参实体上，
                    // 否则服务层 insert 后拿不到 id、后续 selectById(null) 会得到 null。
                    entity.setId(store.insert(entity).getId());
                    return 1;
                });
        when(mapper.selectById(any()))
                .thenAnswer(invocation -> store.selectById(invocation.getArgument(0)));
        when(mapper.selectList(any()))
                .thenAnswer(invocation -> store.selectAllOrderedByIdDesc());
        when(mapper.countBySourceCode(any()))
                .thenAnswer(invocation -> store.countBySourceCode(invocation.getArgument(0)));
        when(mapper.lockById(any()))
                .thenAnswer(invocation -> store.lockById(invocation.getArgument(0)));
        when(mapper.updateById(any(SourceRegistry.class)))
                .thenAnswer(invocation -> {
                    store.updateById(invocation.getArgument(0));
                    return 1;
                });
        return mapper;
    }

    /** {@link ActiveSourceBindingMapper} 的替身：直接用内存表（无需 Mockito） */
    static ActiveSourceBindingMapper bindingMapper(InMemoryStore store) {
        return new ActiveSourceBindingMapper() {
            @Override
            public ActiveProfileBinding lockActive() {
                return store.lockActive();
            }

            @Override
            public ActiveProfileBinding findActive() {
                return store.findActive();
            }

            @Override
            public int bind(Long profileId, Long sourceId) {
                store.bind(profileId, sourceId);
                return 1;
            }
        };
    }

    private static SourceRegistry copy(SourceRegistry source) {
        if (source == null) {
            return null;
        }
        SourceRegistry copy = new SourceRegistry();
        copy.setId(source.getId());
        copy.setSourceCode(source.getSourceCode());
        copy.setDisplayName(source.getDisplayName());
        copy.setIngestMode(source.getIngestMode());
        copy.setProfilePath(source.getProfilePath());
        copy.setTimezone(source.getTimezone());
        copy.setCurrency(source.getCurrency());
        copy.setStatus(source.getStatus());
        copy.setProfileVersion(source.getProfileVersion());
        copy.setWarehousePrefix(source.getWarehousePrefix());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}
