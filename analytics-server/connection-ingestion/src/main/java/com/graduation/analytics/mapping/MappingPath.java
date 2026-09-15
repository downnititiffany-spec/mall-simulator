package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 源侧路径解析（设计 §7.3 规则 9）：只允许对象路径 {@code a.b.c} 与固定数组下标 {@code a[0].b}，
 * **不支持**任意递归、通配、脚本。缺失即返回 {@code null}（由调用方按规则 5 判 MISSING）。
 */
public final class MappingPath {

    private MappingPath() {
    }

    /** 按路径取值；任一段缺失、类型不符或下标越界都返回 {@code null}。 */
    public static JsonNode resolve(JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return null;
        }
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (segment.isEmpty()) {
                return null;
            }
            String name = segment;
            List<Integer> indices = new ArrayList<>();
            int bracket = segment.indexOf('[');
            if (bracket >= 0) {
                name = segment.substring(0, bracket);
                int cursor = bracket;
                while (cursor < segment.length()) {
                    if (segment.charAt(cursor) != '[') {
                        return null;
                    }
                    int close = segment.indexOf(']', cursor);
                    if (close < 0) {
                        return null;
                    }
                    try {
                        indices.add(Integer.parseInt(segment.substring(cursor + 1, close)));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    cursor = close + 1;
                }
            }
            if (!name.isEmpty()) {
                if (!current.isObject()) {
                    return null;
                }
                current = current.get(name);
                if (current == null) {
                    return null;
                }
            }
            for (int index : indices) {
                if (!current.isArray() || index < 0 || index >= current.size()) {
                    return null;
                }
                current = current.get(index);
            }
        }
        return current;
    }

    /** 取路径最后一段的叶子名（数组下标不算在名字里），用于规则 3 的 @keep 保留名。 */
    public static String leafName(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        String leaf = dot < 0 ? path : path.substring(dot + 1);
        int bracket = leaf.indexOf('[');
        return bracket < 0 ? leaf : leaf.substring(0, bracket);
    }
}
