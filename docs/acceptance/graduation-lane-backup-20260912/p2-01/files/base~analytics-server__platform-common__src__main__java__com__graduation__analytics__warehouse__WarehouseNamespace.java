package com.graduation.analytics.warehouse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数仓库名空间的**唯一所有者**（Java 侧，P1-04）。
 *
 * <p>机器可读权威：{@code contract-specs/specs/warehouse-namespace.v1.json}。
 * 本类是它的薄适配（规则常量 + 白名单校验 + 派生名），Scala 侧
 * {@code com.graduation.analytics.warehouse.WarehouseNamespace} 是同名镜像；
 * 两侧契约测试加载同一份规格逐向量对账，任一实现漂移即失败。</p>
 *
 * <p>规则：库名 = {@code <prefix>_<layer>}，layer ∈ {ods, dwd, dim, dws, ads}；
 * prefix 白名单 {@code ^[a-z][a-z0-9_]{0,23}$}，不得以 {@code _} 结尾、不得含 {@code __}、
 * 不得命中保留字、不得已带层后缀。{@code hive_database_prefix} 为 null/空串 →
 * 缺省 {@code dw}（与改造前库名逐字一致，零数据迁移）。**不做任何归一化**：
 * 不 trim、不转小写、不静默兜底；非法取值原样失败并带错误码。</p>
 *
 * <p>纪律：库名字面量只允许出现在本文件与规格文件里。</p>
 */
public final class WarehouseNamespace {

    /** 缺省前缀：源 A 既有库名（dw_ods …） */
    public static final String DEFAULT_PREFIX = "dw";

    public static final String SEPARATOR = "_";

    /** 前缀白名单形状（与规格 rule.prefixPattern 逐字一致） */
    public static final String PREFIX_PATTERN = "^[a-z][a-z0-9_]{0,23}$";

    /** 层后缀（顺序即规格 order） */
    public static final List<String> LAYER_SUFFIXES = List.of("ods", "dwd", "dim", "dws", "ads");

    /** 保留前缀（Hive/元数据库名） */
    public static final Set<String> RESERVED =
            Set.of("default", "sys", "system", "information_schema", "hive_metastore");

    public static final String ERR_PATTERN = "WAREHOUSE_PREFIX_PATTERN";
    public static final String ERR_UNDERSCORE = "WAREHOUSE_PREFIX_UNDERSCORE";
    public static final String ERR_RESERVED = "WAREHOUSE_PREFIX_RESERVED";
    public static final String ERR_LAYER_SUFFIX = "WAREHOUSE_PREFIX_LAYER_SUFFIX";

    /** spark-submit 透传键名（Scala 侧 {@code WarehouseNamespace.ArgKey} 同名同值） */
    public static final String ARG_KEY = "hiveDatabasePrefix";

    private static final Pattern COMPILED = Pattern.compile(PREFIX_PATTERN);

    private final String prefix;

    private WarehouseNamespace(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /** 某层的库名（层名必须在 {@link #LAYER_SUFFIXES} 内，防调用方自造层名） */
    public String layerDb(String layer) {
        if (layer == null || !LAYER_SUFFIXES.contains(layer)) {
            throw new IllegalArgumentException(
                    "未知数仓层: " + layer + "（允许: " + String.join(",", LAYER_SUFFIXES) + "）");
        }
        return prefix + SEPARATOR + layer;
    }

    public String ods() {
        return layerDb("ods");
    }

    public String dwd() {
        return layerDb("dwd");
    }

    public String dim() {
        return layerDb("dim");
    }

    public String dws() {
        return layerDb("dws");
    }

    public String ads() {
        return layerDb("ads");
    }

    /** 层 → 库名（顺序稳定，供页面/证据展示；不参与 SQL 拼接） */
    public Map<String, String> layers() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String layer : LAYER_SUFFIXES) {
            out.put(layer, layerDb(layer));
        }
        return Collections.unmodifiableMap(out);
    }

    /** {@code 库.表} 限定名（表名不得为空或含 '.'，避免跨库注入） */
    public String table(String layer, String table) {
        if (table == null || table.isEmpty() || table.contains(".")) {
            throw new IllegalArgumentException("表名非法: " + table + "（不得为空或含 '.'）");
        }
        return layerDb(layer) + "." + table;
    }

    public boolean isDefault() {
        return DEFAULT_PREFIX.equals(prefix);
    }

    /**
     * 白名单校验：{@code Optional.empty()} = 合法（含缺省）；否则为错误码。
     * 检查顺序与规格 {@code rule.checkOrder} 一致。
     */
    public static Optional<String> validationError(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        if (!COMPILED.matcher(raw).matches()) {
            return Optional.of(ERR_PATTERN);
        }
        if (raw.endsWith(SEPARATOR) || raw.contains(SEPARATOR + SEPARATOR)) {
            return Optional.of(ERR_UNDERSCORE);
        }
        if (RESERVED.contains(raw)) {
            return Optional.of(ERR_RESERVED);
        }
        for (String suffix : LAYER_SUFFIXES) {
            if (raw.endsWith(SEPARATOR + suffix)) {
                return Optional.of(ERR_LAYER_SUFFIX);
            }
        }
        return Optional.empty();
    }

    /** 解析（缺省也合法）；非法 → {@code IllegalArgumentException("<错误码>: <原值>")} */
    public static WarehouseNamespace of(String raw) {
        Optional<String> error = validationError(raw);
        if (error.isPresent()) {
            throw new IllegalArgumentException(error.get() + ": " + raw);
        }
        return new WarehouseNamespace(raw == null || raw.isEmpty() ? DEFAULT_PREFIX : raw);
    }

    /** null/空串 → 缺省命名空间；其余同 {@link #of(String)}（源 A 的 NULL 前缀走这里） */
    public static WarehouseNamespace ofNullable(String raw) {
        return of(raw);
    }

    /** 缺省命名空间（源 A，dw_*） */
    public static WarehouseNamespace defaultNamespace() {
        return new WarehouseNamespace(DEFAULT_PREFIX);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WarehouseNamespace other && prefix.equals(other.prefix);
    }

    @Override
    public int hashCode() {
        return prefix.hashCode();
    }

    @Override
    public String toString() {
        return "WarehouseNamespace(" + prefix + " → " + String.join("/", layers().values()) + ")";
    }
}
