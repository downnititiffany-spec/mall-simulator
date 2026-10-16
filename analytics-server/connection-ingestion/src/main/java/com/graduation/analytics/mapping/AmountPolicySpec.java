package com.graduation.analytics.mapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 金额单位策略（设计 §7.3 规则 7，S2-01A.1 冻结机器形状）：
 * {@code {"bySourceField": {"<源字段路径/名>": "FEN"|"YUAN"}}}。
 *
 * <p>键是**源数据字段路径/名**，不是 canonical 目标字段：单位是源数据的属性（规则 7 是「单位转换」，
 * 同一 canonical 字段在不同源里可能是分也可能是元）。</p>
 *
 * <p><b>没有画像级默认单位，也没有 defaultUnit</b>：任何需要金额转换的源字段都必须在
 * {@code bySourceField} 里显式声明单位；装载期缺声明即 fail-closed（v2 直接 {@code PROFILE_INVALID}，
 * v1 兼容模式记 {@code capabilityGaps} + 禁止激活），运行期 {@link #unitFor(String)} 返回 {@code null}
 * 时执行器照样 {@code PROFILE_INVALID/MISSING_AMOUNT_POLICY} 隔离整事件。绝不默认猜 FEN/YUAN。</p>
 *
 * <p>输出口径固定不可配：canonical 金额一律 {@code scale=2}、{@code HALF_UP} 的十进制字符串（元），
 * FEN ÷ 100，YUAN 精确舍入到分；不接受隐式 double，也不接受任意倍率。</p>
 */
public record AmountPolicySpec(Map<String, Unit> bySourceField) {

    public enum Unit {
        FEN,
        YUAN
    }

    public AmountPolicySpec {
        Objects.requireNonNull(bySourceField, "bySourceField");
        bySourceField = Collections.unmodifiableMap(new LinkedHashMap<>(bySourceField));
    }

    /** 取某个源字段生效的单位；未声明 ⇒ {@code null}（调用方必须 fail-closed，不得回落默认值）。 */
    public Unit unitFor(String sourceField) {
        return bySourceField.get(sourceField);
    }
}
