package com.graduation.generator.adapter;

/**
 * 商城侧的合成用户（§4.1 {@code createSyntheticUser} 的返回类型；指导书未定义字段，此处为最小设计）。
 *
 * @param userId      商城侧用户 ID
 * @param memberLevel 商城侧最终等级原文（可能与请求不同——商城可以按自己的规则定级，生成器以商城回传为准）
 */
public record ExternalUser(String userId, String memberLevel) {

    public ExternalUser {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 必填（商城侧唯一键）");
        }
    }
}
