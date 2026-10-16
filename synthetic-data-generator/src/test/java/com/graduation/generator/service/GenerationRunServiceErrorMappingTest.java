package com.graduation.generator.service;

import com.graduation.generator.adapter.MallFailureCode;
import com.graduation.generator.adapter.MallOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationRunServiceErrorMappingTest {

    @Test
    @DisplayName("商城适配器分类进入运行错误码，非商城异常维持通用运行失败码")
    void runErrorCodeUsesStableMallCategory() {
        assertEquals(MallFailureCode.MALL_STOCK_UNAVAILABLE.name(),
                GenerationRunService.errorCodeOf(new MallOperationException(
                        "createOrder", MallFailureCode.MALL_STOCK_UNAVAILABLE, "商品库存不足")));
        assertEquals(MallFailureCode.MALL_OUTCOME_UNKNOWN.name(),
                GenerationRunService.errorCodeOf(new MallOperationException(
                        "createOrder", MallFailureCode.MALL_OUTCOME_UNKNOWN, "写请求结果不明")));
        assertEquals("RUN_FAILED", GenerationRunService.errorCodeOf(new IllegalStateException("boom")));
    }
}
