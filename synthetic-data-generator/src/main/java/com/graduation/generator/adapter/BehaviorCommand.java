package com.graduation.generator.adapter;

import com.graduation.generator.contract.ContractEnums;

/**
 * 行为埋点指令（§4.1 {@code emitBehavior(BehaviorCommand)} 的入参；指导书未定义字段，此处为最小设计）。
 *
 * <p><b>B-04 判定的落地</b>：行为数据只能经"正常、幂等、公开的行为埋点 API"进入商城
 * （§5.1 B-04 裁决）。因此本类型刻意只有一条事件，没有批量、没有回放、没有绕过校验的开关——
 * 任何"批量写入/直写"的口子都不该在这个 SPI 上出现。</p>
 *
 * <p>{@code sessionId} 必填：分析侧的会话口径依赖它，生成器不能事后拼。</p>
 */
public record BehaviorCommand(String userId, String productId, String sessionId,
                              String behaviorType, String channel) {

    public BehaviorCommand {
        requireId(userId, "userId");
        requireId(productId, "productId");
        requireId(sessionId, "sessionId");
        behaviorType = ContractEnums.requireIn(ContractEnums.BEHAVIOR_TYPE, behaviorType, "behavior_type");
        channel = ContractEnums.requireIn(ContractEnums.CHANNEL, channel, "channel");
    }

    private static void requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 必填（行为事件必须能归到具体主体与商品）");
        }
    }
}
