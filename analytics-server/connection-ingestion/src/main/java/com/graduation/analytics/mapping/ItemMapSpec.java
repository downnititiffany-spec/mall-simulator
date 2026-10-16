package com.graduation.analytics.mapping;

import java.util.Map;
import java.util.Objects;

/**
 * 订单项映射（设计 §7.3 规则 9：「ITEM_MAP 保持顺序和数量」，只允许固定数组下标，不支持任意递归/通配/脚本）。
 *
 * <p>画像形状（S2-01A 内部，未在设计中冻结）：</p>
 * <pre>
 * "lines": { "target": "items[]", "itemFields": { "sku": "product_id", "qty": "quantity" } }
 * </pre>
 *
 * <p>{@code targetPath} 只允许 {@code <数组字段>[]}（当前契约里唯一数组字段是 order_created.items）；
 * 源值是数组时逐项按 {@code itemFields} 映射，任一项必填失败 ⇒ 整事件隔离（不产出半截 items）。
 * 源值是字符串时按 D-063 直通（解析归一属 DWD），不做逐项金额转换。</p>
 */
public record ItemMapSpec(String targetPath, String arrayField, Map<String, String> itemFields) {

    public ItemMapSpec {
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(arrayField, "arrayField");
        itemFields = Map.copyOf(itemFields);
    }

    /** 目标取值形式 {@code items[]} ⇒ 数组字段名 {@code items}。 */
    public static String arrayFieldOf(String targetPath) {
        return targetPath != null && targetPath.endsWith("[]")
                ? targetPath.substring(0, targetPath.length() - 2)
                : null;
    }
}
