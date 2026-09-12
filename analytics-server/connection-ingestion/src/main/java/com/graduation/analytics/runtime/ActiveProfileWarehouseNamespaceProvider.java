package com.graduation.analytics.runtime;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.warehouse.RunSourceIdentity;
import com.graduation.analytics.warehouse.WarehouseNamespace;
import com.graduation.analytics.warehouse.WarehouseNamespaceProvider;
import org.springframework.stereotype.Component;

/**
 * 按**源**解析数仓命名空间（P1-04 建立，P2-07 换链）。
 *
 * <p>P2-07 前：{@code runtime_profile.hive_database_prefix}（ACTIVE 档案）→ 库名。
 * P2-07 后：{@code source_registry.warehouse_prefix}（运行所用的源）→ 库名。
 * 本类**不再读** {@code runtime_profile.hive_database_prefix}（D-073：该列的生产读取点清零，
 * 物理列保留、写入侧由 {@code RuntimeProfileServiceImpl} 拒绝非空值）。</p>
 *
 * <p>「当前源」的定义复用唯一所有者 {@link SourceRegistryService#currentSourceId()}
 * （= 唯一 ACTIVE {@code runtime_profile} 行的 {@code source_id}，见 ActiveSourceBindingMapper），
 * 本类不自造第二种"当前源"口径。</p>
 *
 * <p>失败面（全部 fail-closed，无缺省兜底）：未绑定源 → {@code SOURCE_NOT_BOUND}；
 * 源不存在 → {@code SOURCE_NOT_FOUND}；源编码为空 → {@code PARAM_INVALID}；
 * 前缀为空 → {@code PARAM_INVALID}；
 * 前缀形状非法 → {@code WAREHOUSE_PREFIX_*}（判据唯一在
 * {@link WarehouseNamespaceProvider#requireSourcePrefix(String)}）。</p>
 *
 * <p>A12（P2-01 对齐）：同一行读出的 {@code source_code} 一并装进 {@link RunSourceIdentity}，
 * 供 {@code JobCommandBuilder} 下发 {@code --sourceSystem}——平台侧不再有"只解析库名"的窄口径。</p>
 */
@Component
public class ActiveProfileWarehouseNamespaceProvider implements WarehouseNamespaceProvider {

    private final SourceRegistryService sources;

    public ActiveProfileWarehouseNamespaceProvider(SourceRegistryService sources) {
        this.sources = sources;
    }

    @Override
    public RunSourceIdentity currentRunSource() {
        return runSource(sources.currentSourceId().orElse(null));
    }

    @Override
    public RunSourceIdentity runSource(Long sourceId) {
        if (sourceId == null) {
            // 不回落到"缺省 dw"：未绑定源时库名不可知，猜一个库名等于把数据写进别的源的库。
            throw new PlatformBizException(PlatformBizException.SOURCE_NOT_BOUND,
                    "运行环境未绑定源（唯一 ACTIVE runtime_profile 行的 source_id 为空），无法确定数仓命名空间；"
                            + "请先激活一个源（POST /api/v1/sources/{id}/activate）");
        }
        SourceRegistryView source = sources.get(sourceId); // 行不存在 → SOURCE_NOT_FOUND
        // 同一行的两个字段一次读完：库名（P2-07）与源编码（A12/--sourceSystem）必然同源，
        // 各自过自己的存在性判据（形状判据仍唯一在 WarehouseNamespace，本类不复制规则）。
        return new RunSourceIdentity(
                WarehouseNamespaceProvider.requireSourceCode(source.sourceCode()),
                WarehouseNamespaceProvider.requireSourcePrefix(source.warehousePrefix()));
    }
}
