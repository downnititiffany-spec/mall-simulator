package com.graduation.generator.adapter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 一次目标检查的实测结论（适配器侧）。类名与指导书 §4.1 的返回类型 {@code TargetCheckResult} 一致。
 *
 * <p>{@code capabilities} 里的每一项都是<b>本次探测的实测判定</b>，与 {@code generator_target.capabilities}
 * 那列"运营方声明"是两件事：声明是意图，实测是事实。两者不一致时应以实测为准并让人看见差异，
 * 因此这里<b>不</b>把探测结果写回目标配置行。</p>
 */
public record TargetCheckResult(
        long targetId,
        boolean reachable,
        String detail,
        Map<MallCapability, CapabilityVerdict> capabilities) {

    private static final Map<MallCapability, CapabilityVerdict> NONE = Map.of();

    public TargetCheckResult {
        capabilities = capabilities == null || capabilities.isEmpty()
                ? NONE
                : Collections.unmodifiableMap(new EnumMap<>(capabilities));
    }

    /** 能力判定（缺项按"没测出来"处理，绝不默认成"支持"） */
    public CapabilityVerdict verdict(MallCapability capability) {
        return capabilities.getOrDefault(capability, CapabilityVerdict.UNDETERMINED);
    }
}
