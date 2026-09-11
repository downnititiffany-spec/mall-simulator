package com.graduation.generator.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code adapter_type} → 适配器实现的唯一登记处。
 *
 * <p>没有它，"支持哪些商城"就会散落在探测服务、运行服务、页面各处，各写一份 {@code if}。
 * 有了它，未知类型的处理只有一种：<b>响亮失败并列出已注册类型</b>——
 * 旧实现那种"连一下 TCP 端口就算检查过"的兜底已经删除：对着任意端口连通用什么也证明不了，
 * 反而会让配置写错的目标看起来是"可用"的。</p>
 */
public final class MallTargetAdapterRegistry {

    private final Map<String, MallTargetAdapter> byType;

    public MallTargetAdapterRegistry(List<MallTargetAdapter> adapters) {
        Map<String, MallTargetAdapter> registered = new LinkedHashMap<>();
        for (MallTargetAdapter adapter : adapters) {
            String type = normalize(adapter.adapterType());
            if (type.isEmpty()) {
                throw new IllegalStateException("适配器没有声明 adapter_type：" + adapter.getClass().getName());
            }
            MallTargetAdapter previous = registered.putIfAbsent(type, adapter);
            if (previous != null) {
                throw new IllegalStateException("adapter_type 重复注册：" + type + "（"
                        + previous.getClass().getName() + " 与 " + adapter.getClass().getName()
                        + "）——一个类型只允许一个所有者");
            }
        }
        this.byType = Collections.unmodifiableMap(registered);
    }

    public Optional<MallTargetAdapter> find(String adapterType) {
        return adapterType == null ? Optional.empty() : Optional.ofNullable(byType.get(normalize(adapterType)));
    }

    /** 找不到就抛 {@link IllegalArgumentException}（经 ApiExceptionHandler 映射为 400） */
    public MallTargetAdapter require(String adapterType) {
        return find(adapterType).orElseThrow(() -> new IllegalArgumentException(
                "未注册的 adapter_type：" + adapterType + "（已注册：" + String.join(", ", knownTypes()) + "）"));
    }

    /** 已注册的类型（大写原文，保持注册顺序，便于报错信息稳定可读） */
    public Set<String> knownTypes() {
        return byType.keySet();
    }

    private static String normalize(String adapterType) {
        return adapterType == null ? "" : adapterType.trim().toUpperCase(Locale.ROOT);
    }
}
