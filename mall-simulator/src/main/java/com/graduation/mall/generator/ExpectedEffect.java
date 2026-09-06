package com.graduation.mall.generator;

import java.util.Map;

/**
 * 场景预期效果（§20.4）：只用于生成测试标准答案与评价 AI 是否识别方向，
 * 绝不传给 AI 作为作答提示（防止实验数据泄漏）。
 */
public record ExpectedEffect(String code, String label, Map<String, String> directions) {

    public static ExpectedEffect of(String code, String label, Map<String, String> directions) {
        return new ExpectedEffect(code, label, directions);
    }
}