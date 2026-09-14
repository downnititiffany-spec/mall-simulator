package com.graduation.generator.contract;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 契约枚举常量（{@code canonical-event.v1.schema.json} 的 {@code $defs} 中每个 enum 一份）。
 *
 * <p>生成器侧**主动校验**这些取值：真实数据里出现过 {@code channel="web"}、{@code change_type="restock"}、
 * {@code behavior_type="purchase"}——都是越界值，其中前两者被采集层放行、污染了下游指标口径。
 * 生成器不制造这类行，也就不需要平台"宽容"。</p>
 */
public final class ContractEnums {

    public static final Set<String> AGE_GROUP = ordered("under18", "18-24", "25-34", "35-44", "45+");
    public static final Set<String> CITY_LEVEL = ordered("tier1", "tier2", "tier3", "other");
    public static final Set<String> MEMBER_LEVEL = ordered("normal", "silver", "gold", "platinum");
    public static final Set<String> PRODUCT_STATUS = ordered("on_sale", "off_sale", "pending");
    public static final Set<String> BEHAVIOR_TYPE = ordered("view", "favorite", "cart_add", "cart_remove", "search");
    public static final Set<String> CHANNEL = ordered("app", "pc", "h5");
    public static final Set<String> STOCK_CHANGE_TYPE = ordered("inbound", "outbound", "adjust");

    private ContractEnums() {
    }

    /** 取值必须落在集合内，否则抛（不静默产出越界值） */
    public static String requireIn(Set<String> allowed, String value, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(
                    "%s=%s 不在契约 enum %s 内（越界值会被隔离或污染下游口径）".formatted(field, value, allowed));
        }
        return value;
    }

    private static Set<String> ordered(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(java.util.List.of(values)));
    }
}
