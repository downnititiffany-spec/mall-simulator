package com.graduation.analytics.runtime;

import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.warehouse.WarehouseNamespace;
import com.graduation.analytics.warehouse.WarehouseNamespaceProvider;
import org.springframework.stereotype.Component;

/**
 * 用 ACTIVE 运行档案解析当前数仓命名空间（P1-04）。
 *
 * <p>取值链：{@code runtime_profile.hive_database_prefix}
 * （NULL/空串 → 缺省 {@code dw}，即源 A 既有库名，零数据迁移）→
 * {@link WarehouseNamespace}。非法前缀不静默兜底，直接抛出带错误码的
 * {@code IllegalArgumentException}（提交流水线时更早一步已由 {@code JobCommandBuilder} 拦下）。</p>
 */
@Component
public class ActiveProfileWarehouseNamespaceProvider implements WarehouseNamespaceProvider {

    private final RuntimeProfileService profiles;

    public ActiveProfileWarehouseNamespaceProvider(RuntimeProfileService profiles) {
        this.profiles = profiles;
    }

    @Override
    public WarehouseNamespace current() {
        return profiles.findActive()
                .map(RuntimeProfile::getHiveDatabasePrefix)
                .map(WarehouseNamespace::ofNullable)
                .orElseGet(WarehouseNamespace::defaultNamespace);
    }
}
