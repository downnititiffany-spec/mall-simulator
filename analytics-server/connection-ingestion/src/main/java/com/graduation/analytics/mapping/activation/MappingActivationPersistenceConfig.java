package com.graduation.analytics.mapping.activation;

import com.graduation.analytics.mapping.activation.mapper.SourceMappingActiveMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 激活指针存储的装配（S2-03.1）：**整个应用里只有一个 {@link ActiveMappingPointerStore} bean**，
 * 由本类显式决定它是落库实现还是 fail-closed 兜底。
 *
 * <p><b>为什么不用 {@code @Component} + {@code @ConditionalOnMissingBean} 二选一</b>：
 * 组件扫描下"谁先被注册"取决于扫描顺序，{@code @ConditionalOnMissingBean} 在普通
 * {@code @Configuration} 上不是确定性的（它本是给自动配置用的）；一旦判定反了，
 * 生产就会拿到"永远说没激活"的兜底实现而没人报错——正是最该避免的静默降级。
 * 这里改成一次性、可读的顺序判断：mapper 在就装落库实现，不在才退兜底并 WARN 一句。</p>
 *
 * <p><b>兜底不是等价实现</b>：{@link UnavailableActiveMappingPointerStore} 读回空、写报 501，
 * 它存在的意义只是"装配里确实没有持久化能力时不要假装成功"，见该类注释。</p>
 */
@Configuration
public class MappingActivationPersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(MappingActivationPersistenceConfig.class);

    @Bean
    public ActiveMappingPointerStore activeMappingPointerStore(
            ObjectProvider<SourceMappingActiveMapper> mapperProvider) {
        if (mapperProvider.getIfAvailable() == null) {
            log.warn("未装配 SourceMappingActiveMapper：激活指针退化为 fail-closed 兜底实现"
                    + "（activate 将返回 MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE 501，采集侧将判 MAPPING_NOT_ACTIVE）。"
                    + "正常装配下必须出现落库实现，出现本行说明元数据 mapper 扫描缺包。");
            return new UnavailableActiveMappingPointerStore();
        }
        return new JdbcActiveMappingPointerStore(mapperProvider.getObject());
    }
}
