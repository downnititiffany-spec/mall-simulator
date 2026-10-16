package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 映射执行器的 JSON 入口：**唯一**一个 ObjectMapper 工厂，避免各处自建 mapper 导致金额/精度口径漂移。
 *
 * <p>关键开关：{@code USE_BIG_DECIMAL_FOR_FLOATS}——JSON 小数一律以 BigDecimal 原词保留，
 * 规则 7 明确禁止隐式 double；整数仍是 Int/Long/BigInteger，便于「FEN 必须为整数」判定。</p>
 */
public final class MappingJson {

    private MappingJson() {
    }

    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return mapper;
    }
}
