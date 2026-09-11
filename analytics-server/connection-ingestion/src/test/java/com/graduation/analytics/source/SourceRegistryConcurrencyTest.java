package com.graduation.analytics.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.source.dto.SourceChangeOutcome;
import com.graduation.analytics.source.entity.SourceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-03 并发正确性（E2）。DoD：「并发激活只有一个当前源」。
 *
 * <p><b>取证边界（如实登记）</b>：本类的行锁是 {@link SourceRegistryTestSupport.InMemoryStore} 里的
 * {@code ReentrantLock} 替身，事务边界由测试线程显式声明（{@code store.inTransaction(...)}）。
 * 因此这里证明的是**服务逻辑在互斥下的不变量**，不是 InnoDB 的行锁行为。
 * 真库语义（{@code SELECT ... FOR UPDATE} + {@code @Transactional} + 真并发 HTTP）由 E3 取证。</p>
 *
 * <p>「当前源」结构上唯一（{@code runtime_profile.source_id} 单列），因此断言的是：
 * ① 任何时刻只有 1 行 ACTIVE runtime_profile；② 当前源状态恒为 ACTIVE（不会被并发 pause 掉）；
 * ③ 每个请求都有可判定结果（成功或具名业务异常，无挂起/无未定义异常）；
 * ④ 没有静默丢失的写（每次 changed=true 恰对应一次落库写）。</p>
 */
class SourceRegistryConcurrencyTest {

    private static final String CODE_A = "p1-03-probe-1";
    private static final String CODE_B = "p1-03-probe-2";
    private static final String PATH_A = "analytics-server/source-profiles/p1-03-probe-1.v1.json";
    private static final String PATH_B = "analytics-server/source-profiles/p1-03-probe-2.v1.json";

    private static final int THREADS = 8;
    private static final int ROUNDS = 12;

    private final SourceRegistryTestSupport.InMemoryStore store = new SourceRegistryTestSupport.InMemoryStore();

    @TempDir
    Path work;

    private SourceRegistryService newService(Path profileRoot) {
        return new SourceRegistryServiceImpl(
                SourceRegistryTestSupport.mapper(store),
                SourceRegistryTestSupport.bindingMapper(store),
                new SourceProfileValidator(profileRoot.toString(), new ObjectMapper()));
    }

    /** 建两个「画像合法」的探针源 + 一份与真库 id=1 ACTIVE 等价的 runtime_profile 绑定 */
    private SourceRegistryService fixture() throws IOException {
        Path profileRoot = work.resolve("profiles");
        writeProfile(profileRoot, PATH_A, CODE_A);
        writeProfile(profileRoot, PATH_B, CODE_B);

        SourceRegistryService service = newService(profileRoot);
        service.create(new com.graduation.analytics.source.dto.SourceRegistryCreateReq(
                CODE_A, "P1-03 探针源 1", "FILE", PATH_A, "Asia/Shanghai", "CNY", null, "1.0"));
        service.create(new com.graduation.analytics.source.dto.SourceRegistryCreateReq(
                CODE_B, "P1-03 探针源 2", "FILE", PATH_B, "Asia/Shanghai", "CNY", null, "1.0"));
        // 与真库一致：存在 1 行 ACTIVE runtime_profile（id=1），初始绑定到 A
        store.bind(1L, idOf(CODE_A));
        return service;
    }

    private Long idOf(String code) {
        SourceRegistry row = store.rowByCode(code);
        if (row == null) {
            throw new IllegalStateException("未找到源 " + code);
        }
        return row.getId();
    }

    private static void writeProfile(Path root, String relative, String code) throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, """
                {
                  "profileVersion": "1.0",
                  "sourceCode": "%s",
                  "canonical": {},
                  "eventTypeMapping": {},
                  "fieldMapping": {},
                  "enumSemantics": {},
                  "identityPolicy": {},
                  "timePolicy": {},
                  "quarantinePolicy": {}
                }
                """.formatted(code), StandardCharsets.UTF_8);
    }

    /**
     * 单线程调用也必须显式声明「事务」边界，否则替身行锁会被测试线程一直持有，
     * 后续 {@link #storm} 的工作线程全部阻塞在 {@code rowLock.lock()} 上直到超时。
     * 真库里这个边界由 {@code @Transactional} 提交/回滚自动释放。
     */
    private SourceChangeOutcome inTx(Supplier<SourceChangeOutcome> call) {
        return store.inTransaction(call);
    }

    /** 并发跑一批调用：每个调用都在「事务」边界内，结果要么成功要么具名业务异常 */
    private List<Outcome> storm(List<Supplier<SourceChangeOutcome>> calls) throws Exception {        ExecutorService pool = Executors.newFixedThreadPool(Math.min(THREADS, calls.size()));
        CyclicBarrier gate = new CyclicBarrier(calls.size());
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Supplier<SourceChangeOutcome> call : calls) {
            futures.add(pool.submit(() -> {
                gate.await(30, TimeUnit.SECONDS);
                try {
                    return Outcome.success(store.inTransaction(call));
                } catch (PlatformBizException e) {
                    return Outcome.bizFailure(e.getCode());
                } catch (RuntimeException e) {
                    return Outcome.undefined(e);
                }
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return outcomes;
    }

    @Test
    @DisplayName("@Transactional 必须挂在 activate/pause 上（E3 真库原子性的唯一机制，此处钉住不丢）")
    void activateAndPauseAreTransactional() throws Exception {
        Method activate = SourceRegistryServiceImpl.class.getMethod("activate", Long.class);
        Method pause = SourceRegistryServiceImpl.class.getMethod("pause", Long.class);
        assertThat(activate.isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(pause.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    @DisplayName("8 线程并发 activate 两个源 × 12 轮：恒有唯一当前源，且当前源必为 ACTIVE")
    void concurrentActivateKeepsSingleCurrentSource() throws Exception {
        SourceRegistryService service = fixture();
        Long idA = idOf(CODE_A);
        Long idB = idOf(CODE_B);

        for (int round = 0; round < ROUNDS; round++) {
            List<Supplier<SourceChangeOutcome>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Long target = (i % 2 == 0) ? idA : idB;
                calls.add(() -> service.activate(target));
            }

            List<Outcome> outcomes = storm(calls);

            assertThat(outcomes).allSatisfy(o -> assertThat(o.undefinedError)
                    .as("并发 activate 不得抛出未定义异常：%s", o).isNull());
            assertThat(outcomes).allSatisfy(o ->
                    assertThat(o.success || o.bizCode != null).as("每个请求都要有可判定结果：%s", o).isTrue());

            assertThat(store.activeRuntimeProfileCount())
                    .as("第 %d 轮：ACTIVE runtime_profile 行数必须恒为 1", round).isEqualTo(1);
            Long current = store.activeSourceId();
            assertThat(current).as("第 %d 轮：当前源必须存在", round).isIn(idA, idB);
            assertThat(store.selectById(current).getStatus())
                    .as("第 %d 轮：当前源状态必须为 ACTIVE（不得被并发改坏）", round)
                    .isEqualTo(SourceRegistry.STATUS_ACTIVE);
            assertThat(store.selectById(idA).getStatus())
                    .isIn(SourceRegistry.STATUS_ACTIVE, SourceRegistry.STATUS_DRAFT);
            assertThat(store.selectById(idB).getStatus())
                    .isIn(SourceRegistry.STATUS_ACTIVE, SourceRegistry.STATUS_DRAFT);

            long changed = outcomes.stream().filter(o -> o.success && o.changed).count();
            long unchanged = outcomes.stream().filter(o -> o.success && !o.changed).count();
            assertThat(changed + unchanged).isEqualTo(THREADS);
            assertThat(changed).as("至少有一次真实变更").isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("并发 activate 与 pause 混跑：不变量「当前源不被 pause」在每一轮结束后都成立")
    void concurrentActivateAndPauseNeverLeavesCurrentSourcePaused() throws Exception {
        SourceRegistryService service = fixture();
        Long idA = idOf(CODE_A);
        Long idB = idOf(CODE_B);

        for (int round = 0; round < ROUNDS; round++) {
            List<Supplier<SourceChangeOutcome>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                if (i % 3 == 0) {
                    Long target = (i % 2 == 0) ? idA : idB;
                    calls.add(() -> service.pause(target));
                } else {
                    Long target = (i % 2 == 0) ? idA : idB;
                    calls.add(() -> service.activate(target));
                }
            }

            List<Outcome> outcomes = storm(calls);

            assertThat(outcomes).allSatisfy(o -> assertThat(o.undefinedError)
                    .as("并发混跑不得抛出未定义异常：%s", o).isNull());
            assertThat(outcomes).allSatisfy(o ->
                    assertThat(o.success || o.bizCode != null).as("每个请求都要有可判定结果：%s", o).isTrue());
            assertThat(outcomes).filteredOn(o -> !o.success).allSatisfy(o ->
                    assertThat(o.bizCode).isEqualTo(PlatformBizException.SOURCE_IN_USE));

            assertThat(store.activeRuntimeProfileCount())
                    .as("第 %d 轮：ACTIVE runtime_profile 行数必须恒为 1", round).isEqualTo(1);
            Long current = store.activeSourceId();
            assertThat(current).as("第 %d 轮：当前源必须存在", round).isIn(idA, idB);
            assertThat(store.selectById(current).getStatus())
                    .as("第 %d 轮：不变量被破坏——当前源被 pause 了", round)
                    .isEqualTo(SourceRegistry.STATUS_ACTIVE);
        }
    }

    @Test
    @DisplayName("没有静默丢失的写：changed=true 恰对应一次落库写，changed=false 零写")
    void noSilentlyLostWrites() throws Exception {
        SourceRegistryService service = fixture();
        Long idA = idOf(CODE_A);

        // 计数一律用**增量**：fixture 里"种入 ACTIVE 行绑定到 A"本身就是一次绑定位写入
        int bindsBeforeFirst = store.bindingWriteCount();
        SourceChangeOutcome first = inTx(() -> service.activate(idA));
        assertThat(first.changed()).isTrue();
        assertThat(store.bindingWriteCount() - bindsBeforeFirst)
                .as("一次真实 activate 必须恰好落一次绑定写").isEqualTo(1);
        int afterFirst = store.writeCount();

        SourceChangeOutcome second = inTx(() -> service.activate(idA));
        assertThat(second.changed()).isFalse();
        assertThat(store.writeCount())
                .as("幂等 activate 不得写任何行（D-035 裁决 4）").isEqualTo(afterFirst);

        int bindsBefore = store.bindingWriteCount();
        List<Supplier<SourceChangeOutcome>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Long target = (i % 2 == 0) ? idA : idOf(CODE_B);
            calls.add(() -> service.activate(target));
        }
        List<Outcome> outcomes = storm(calls);

        long changed = outcomes.stream().filter(o -> o.success && o.changed).count();
        assertThat(store.bindingWriteCount() - bindsBefore)
                .as("每次真实 activate 变更必须恰好落一次绑定写，一次都不能丢")
                .isEqualTo(changed);
    }

    @Test
    @DisplayName("并发 activate 同一源（起点 DRAFT）：8 次调用只产生 1 次真实变更、1 次绑定写")
    void concurrentActivateSameSourceFromDraftCollapsesToOneChange() throws Exception {
        SourceRegistryService service = fixture();
        Long idA = idOf(CODE_A);
        // fixture 只把 A 绑成当前源，A 的状态仍是 create 缺省的 DRAFT →
        // 8 个线程里恰有 1 个该看到"需要变更"，其余 7 个必须看到别人已经做完而幂等返回。
        // 这条用例专门钉住"锁内重读"：若在抢锁前读状态，7 个线程会拿着旧快照各报一次 changed=true。
        int bindsBefore = store.bindingWriteCount();
        int writesBefore = store.writeCount();

        List<Supplier<SourceChangeOutcome>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> service.activate(idA));
        }
        List<Outcome> outcomes = storm(calls);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.undefinedError)
                .as("并发不得抛出未定义异常：%s", o).isNull());
        assertThat(outcomes).allSatisfy(o -> assertThat(o.success)
                .as("同一源并发 activate 必须全部成功（幂等），不得报错：%s", o).isTrue());
        long changed = outcomes.stream().filter(o -> o.changed).count();
        assertThat(changed).as("8 次并发同源激活只允许 1 次真实变更").isEqualTo(1);
        assertThat(store.bindingWriteCount() - bindsBefore).isEqualTo(1);
        assertThat(store.selectById(idA).getStatus()).isEqualTo(SourceRegistry.STATUS_ACTIVE);
        assertThat(store.activeSourceId()).isEqualTo(idA);
        assertThat(store.writeCount() - writesBefore)
                .as("其余 7 次调用必须零写入（不许刷 updated_at、不许重复绑定）").isEqualTo(2);
    }

    @Test
    @DisplayName("并发 activate 已是当前且已 ACTIVE 的源：零真实变更、零写入")
    void concurrentActivateAlreadyActiveDoesZeroWrites() throws Exception {
        SourceRegistryService service = fixture();
        Long idA = idOf(CODE_A);
        // 先串行激活一次，把 A 做成"已是当前源 + 已是 ACTIVE"的完全幂等起点
        inTx(() -> service.activate(idA));
        int writesBefore = store.writeCount();
        int bindsBefore = store.bindingWriteCount();

        List<Supplier<SourceChangeOutcome>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            calls.add(() -> service.activate(idA));
        }
        List<Outcome> outcomes = storm(calls);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.undefinedError)
                .as("并发不得抛出未定义异常：%s", o).isNull());
        assertThat(outcomes).allSatisfy(o -> assertThat(o.changed)
                .as("已是当前源且已 ACTIVE：任何一次调用都不该报 changed=true：%s", o).isFalse());
        assertThat(store.writeCount()).isEqualTo(writesBefore);
        assertThat(store.bindingWriteCount()).isEqualTo(bindsBefore);
    }

    /** 单次调用的可判定结果 */
    private static final class Outcome {
        private final boolean success;
        private final boolean changed;
        private final String bizCode;
        private final RuntimeException undefinedError;

        private Outcome(boolean success, boolean changed, String bizCode, RuntimeException undefinedError) {
            this.success = success;
            this.changed = changed;
            this.bizCode = bizCode;
            this.undefinedError = undefinedError;
        }

        static Outcome success(SourceChangeOutcome outcome) {
            return new Outcome(true, outcome.changed(), null, null);
        }

        static Outcome bizFailure(String code) {
            return new Outcome(false, false, code, null);
        }

        static Outcome undefined(RuntimeException e) {
            return new Outcome(false, false, null, e);
        }

        @Override
        public String toString() {
            return "Outcome{success=" + success + ", changed=" + changed
                    + ", bizCode=" + bizCode + ", undefinedError=" + undefinedError + '}';
        }
    }

    /** 显式断言「并发计数用到的计数器没有被优化掉」——防止断言变成恒真 */
    @Test
    @DisplayName("自检：计数器随写操作真实增长（防止并发断言恒真）")
    void countersActuallyTrackWrites() {
        assertThat(store.writeCount()).isZero();

        store.insert(new SourceRegistry());
        assertThat(store.writeCount()).isEqualTo(1);

        store.updateById(store.selectById(1L));
        assertThat(store.writeCount()).isEqualTo(2);

        store.bind(1L, 7L);
        assertThat(store.bindingWriteCount()).isEqualTo(1);
        assertThat(store.writeCount()).isEqualTo(3);
        assertThat(store.activeSourceId()).isEqualTo(7L);
    }
}
