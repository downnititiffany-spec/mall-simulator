package com.graduation.generator.adapter;

import com.graduation.generator.contract.ContractEnums;

/**
 * 创建合成用户的指令（§4.1 {@code createSyntheticUser(UserCommand)} 的入参；指导书未定义字段，此处为最小设计）。
 *
 * <p>三个字段直接复用契约枚举（{@code age_group/city_level/member_level}），因为它们就是分析侧
 * 分群口径，生成器不得另造取值；越界值在构造时就抛，而不是让商城去拒绝。</p>
 */
public record UserCommand(String ageGroup, String cityLevel, String memberLevel) {

    public UserCommand {
        ageGroup = ContractEnums.requireIn(ContractEnums.AGE_GROUP, ageGroup, "age_group");
        cityLevel = ContractEnums.requireIn(ContractEnums.CITY_LEVEL, cityLevel, "city_level");
        memberLevel = ContractEnums.requireIn(ContractEnums.MEMBER_LEVEL, memberLevel, "member_level");
    }
}
