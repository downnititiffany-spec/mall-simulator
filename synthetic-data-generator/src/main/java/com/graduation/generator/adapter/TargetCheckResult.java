package com.graduation.generator.adapter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 一次目标检查的<b>能力判定</b>（适配器侧）。类名与指导书 §4.1 的返回类型 {@code TargetCheckResult} 一致。
 *
 * <p><b>措辞更正（M4）：判定不都来自实测</b>。{@code capabilities} 里的每一项都是本端点的能力判定，
 * 但其中一部分是适配器按公开接口清单给出的<b>静态声明</b>——例如第二家商城的
 * {@code admin}/{@code refund}/{@code reset_state} 没有代表路由，{@code test()} 连探测请求都不发
 * （见 {@code SecondMallHttpAdapter#test}）。把静态声明说成"实测"，会让读的人以为发过请求、
 * 甚至以为它在商城侧失败过；判定与声明是哪一类，目前只在 {@code detail} 的自由文本里写明，
 * 结构化标记属契约变更（D-068：排到 CT 之后）。</p>
 *
 * <p>{@code capabilities} 与 {@code generator_target.capabilities} 那列"运营方声明"是两件事：
 * 前者是本端点的判定（探测结论 + 适配器静态声明），后者是运营方的意图。两者不一致时应以本端点的
 * 判定为准并让人看见差异，因此这里<b>不</b>把判定写回目标配置行。</p>
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
