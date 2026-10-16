package com.graduation.analytics.mapping.activation;

import java.time.LocalDateTime;

/**
 * 一次激活请求的**结果事实**（S2-03，设计 §7.4：输出"激活已验证不可变版本，留审计"）。
 *
 * <p>字段都是"事后可对账"的：{@link #profileChecksum} 是本次生效的画像字节哈希，
 * {@link #previousProfileChecksum} 是被替换掉的旧哈希（首次激活为 {@code null}）——
 * 有这两个值，"当前 active 到底是哪个 checksum、刚才是从哪个换过来的"都不需要再猜。
 * 完整历史版本审计库不在本轮范围（只保留最近一次替换的前后事实 + 审计表的一行）。</p>
 *
 * @param changed 本次是否真的改了指针。{@code false} = 同源同画像同契约的重复激活（幂等成功，未写任何行）
 */
public record MappingActivationOutcome(
        long sourceId,
        String sourceCode,
        String profilePath,
        String profileVersion,
        String profileChecksum,
        String contractVersion,
        String contractChecksum,
        String reportId,
        LocalDateTime activatedAt,
        String activatedBy,
        boolean changed,
        String previousProfileChecksum) {

    static MappingActivationOutcome of(ActiveMappingPointer pointer, ActiveMappingPointer previous) {
        return new MappingActivationOutcome(pointer.sourceId(), pointer.sourceCode(), pointer.profilePath(),
                pointer.profileVersion(), pointer.profileChecksum(), pointer.contractVersion(),
                pointer.contractChecksum(), pointer.reportId(), pointer.activatedAt(), pointer.activatedBy(),
                previous == null || !pointer.sameContentAs(previous),
                previous == null ? null : previous.profileChecksum());
    }

    /** 幂等重复激活：沿用**既有**指针的时间与操作者，不把"重复请求的此刻"写成新的激活时间。 */
    static MappingActivationOutcome idempotent(ActiveMappingPointer existing) {
        return of(existing, existing);
    }
}
