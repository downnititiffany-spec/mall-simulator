package com.graduation.analytics.mapping.activation;

import com.graduation.analytics.mapping.activation.mapper.SourceMappingActiveMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配门禁（S2-03.1）：正常装配必须是**落库实现**，只有拿不到 mapper 时才退 fail-closed 兜底。
 *
 * <p><b>为什么这条比它看起来重要</b>：S2-03 的"activate 永远 501"不是因为逻辑写错，而是因为
 * 生产装配里装的就是兜底实现。**这类缺陷没有任何单测会红**——服务层测试自己注入替身，
 * HTTP 层测试也自己注入替身，兜底被装错的地方恰好是唯一没人测的一格。所以这里用最小 Spring 上下文
 * 直接问"容器里那个 bean 到底是哪个类"。</p>
 *
 * <p>本类不连库（没有 DataSource，也不启动 web 环境）：它只证装配决策，
 * 真库往返与并发由 MySqlIT 取证。</p>
 */
class MappingActivationPersistenceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MappingActivationPersistenceConfig.class);

    @Test
    @DisplayName("有 mapper ⇒ 装落库实现（activate 才会真正持久化，不再 501）")
    void jdbcStoreIsWiredWhenMapperIsAvailable() {
        runner.withBean(SourceMappingActiveMapper.class, MappingActivationPersistenceConfigTest::mapperStub)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ActiveMappingPointerStore.class);
                    assertThat(context.getBean(ActiveMappingPointerStore.class))
                            .as("mapper 在场时装配落库实现；装上兜底就等于 activate 永久 501")
                            .isInstanceOf(JdbcActiveMappingPointerStore.class);
                });
    }

    @Test
    @DisplayName("没有 mapper ⇒ 装 fail-closed 兜底，且**全程只有一个** ActiveMappingPointerStore bean")
    void unavailableStoreIsWiredOnlyAsFallback() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .as("端口必须只有一个实现被装配：两个 owner 会随扫描顺序决定谁生效")
                    .hasSingleBean(ActiveMappingPointerStore.class);
            assertThat(context.getBean(ActiveMappingPointerStore.class))
                    .isInstanceOf(UnavailableActiveMappingPointerStore.class);
        });
    }

    @Test
    @DisplayName("兜底被选中时读不假装有、写不假装成功：读空、写与锁定读都抛 501")
    void fallbackStaysFailClosed() {
        runner.run(context -> {
            ActiveMappingPointerStore store = context.getBean(ActiveMappingPointerStore.class);
            assertThat(store.find(7L)).isEmpty();
            assertThatThrownByCode(() -> store.lockBySourceId(7L));
            assertThatThrownByCode(() -> store.save(new ActiveMappingPointer(
                    7L, "s2-031-fallback", "profiles/raw-a.v2.json", "2.0", "a".repeat(64), "1.0",
                    "b".repeat(64), "dr-s2031-0001",
                    java.time.LocalDateTime.parse("2026-09-21T10:20:30"), "dev1")));
        });
    }

    private static void assertThatThrownByCode(Runnable call) {
        try {
            call.run();
            throw new AssertionError("兜底实现必须显式失败，不允许静默返回");
        } catch (com.graduation.analytics.common.PlatformBizException e) {
            assertThat(e.getCode())
                    .as("能力缺口用 501 专属错误码，与 500 服务故障可区分")
                    .isEqualTo(com.graduation.analytics.common.PlatformBizException
                            .MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE);
        }
    }

    /** mapper 替身：只用于"bean 存在"这一事实，方法不会被调用。 */
    private static SourceMappingActiveMapper mapperStub() {
        return (SourceMappingActiveMapper) Proxy.newProxyInstance(
                SourceMappingActiveMapper.class.getClassLoader(),
                new Class<?>[]{SourceMappingActiveMapper.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("装配门禁不该调用 mapper." + method.getName());
                });
    }
}
