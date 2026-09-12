package com.graduation.analytics.source.dto;

import com.graduation.analytics.source.entity.SourceRegistry;

import java.time.LocalDateTime;

/**
 * 源登记对外视图（P1-03）。
 *
 * <p><b>不含凭据值，也不含绝对本机路径</b>（任务书 §6 硬约束）：{@code profilePath} 是**仓库相对路径**，
 * 由 {@link com.graduation.analytics.source.SourcePathPolicy} 在写入前强制；本记录里没有
 * credential/密码/token 字段，因为 {@code source_registry} 表本来就没有这些列
 * （凭据属运行环境 {@code runtime_profile.credential_ref}）。</p>
 *
 * @param current 是否当前激活源（派生自唯一 ACTIVE {@code runtime_profile.source_id}，不是本表列）
 * @param warehousePrefix 数仓命名空间前缀（P2-07 新增，V18 列 {@code warehouse_prefix}）：
 *     本源的层库名由它派生；解析链见 {@code WarehouseNamespaceProvider.forSource(id)}
 */
public record SourceRegistryView(
        Long id,
        String sourceCode,
        String displayName,
        String ingestMode,
        String profilePath,
        String timezone,
        String currency,
        String status,
        String profileVersion,
        boolean current,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String warehousePrefix) {

    /** 由实体行 + 当前源 id 组装（{@code currentSourceId} 为 null 表示尚无当前源） */
    public static SourceRegistryView of(SourceRegistry row, Long currentSourceId) {
        if (row == null) {
            return null;
        }
        boolean current = row.getId() != null && row.getId().equals(currentSourceId);
        return new SourceRegistryView(row.getId(), row.getSourceCode(), row.getDisplayName(),
                row.getIngestMode(), row.getProfilePath(), row.getTimezone(), row.getCurrency(),
                row.getStatus(), row.getProfileVersion(), current, row.getCreatedAt(), row.getUpdatedAt(),
                row.getWarehousePrefix());
    }
}
