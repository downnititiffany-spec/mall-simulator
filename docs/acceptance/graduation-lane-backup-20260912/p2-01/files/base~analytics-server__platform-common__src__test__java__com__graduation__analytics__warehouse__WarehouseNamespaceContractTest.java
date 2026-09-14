package com.graduation.analytics.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数仓库名规格对账测试（P1-04，Java 侧）。
 *
 * <p>权威是机器可读规格 {@code contract-specs/specs/warehouse-namespace.v1.json}：
 * 本测试既断言「Java 常量与规格 rule 段逐字一致」，又把规格里每个向量跑一遍。
 * Scala 侧 {@code com.graduation.analytics.WarehouseNamespaceSpec} 读同一份文件做同样的断言，
 * 因此任一侧实现或任一侧规则漂移都会红。</p>
 *
 * <p>纪律：本测试**不自己编造期望值**——期望全部来自规格文件；
 * 唯一由测试自己推导的是「前缀 + '_' + 层」这个派生式，它同时用于校验规格里的 {@code names} 字段自洽。</p>
 */
class WarehouseNamespaceContractTest {

    private static final Path SPEC_PATH =
            RepoRoot.path("contract-specs/specs/warehouse-namespace.v1.json");
    private static final JsonNode SPEC = read(SPEC_PATH);
    private static final JsonNode RULE = SPEC.get("rule");
    private static final String DEFAULT = "DEFAULT";
    private static final String OK = "OK";

    // ── 规格常量对账 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Java 常量与规格 rule 段逐字一致（含层顺序与检查顺序）")
    void constantsMatchSpec() {
        assertThat(WarehouseNamespace.DEFAULT_PREFIX).isEqualTo(RULE.get("defaultPrefix").asText());
        assertThat(WarehouseNamespace.SEPARATOR).isEqualTo(RULE.get("separator").asText());
        assertThat(WarehouseNamespace.PREFIX_PATTERN).isEqualTo(RULE.get("prefixPattern").asText());

        List<String> specLayers = new ArrayList<>();
        RULE.get("layerSuffixes").forEach(node -> specLayers.add(node.asText()));
        // 层顺序也必须是规格顺序：它决定页面展示与 layers() 的稳定顺序
        assertThat(WarehouseNamespace.LAYER_SUFFIXES).isEqualTo(specLayers);

        List<String> specOrder = new ArrayList<>();
        RULE.get("checkOrder").forEach(node -> specOrder.add(node.asText()));
        assertThat(specOrder).containsExactly(
                WarehouseNamespace.ERR_PATTERN,
                WarehouseNamespace.ERR_UNDERSCORE,
                WarehouseNamespace.ERR_RESERVED,
                WarehouseNamespace.ERR_LAYER_SUFFIX);

        TreeSet<String> specReserved = new TreeSet<>();
        RULE.get("reserved").forEach(node -> specReserved.add(node.asText()));
        assertThat(new TreeSet<>(WarehouseNamespace.RESERVED)).isEqualTo(specReserved);

        assertThat(RULE.get("blankIsDefault").asBoolean()).isTrue();
        assertThat(RULE.get("errorCodes").size()).isEqualTo(4);
        RULE.get("errorCodes").fieldNames().forEachRemaining(code ->
                assertThat(RULE.get("checkOrder").toString()).contains(code));

        // 规格里登记的两侧测试类名必须就是本类与 Scala 侧那个：改名即失联，必须一起改
        assertThat(SPEC.at("/parity/javaTest").asText()).isEqualTo(getClass().getName());
        assertThat(SPEC.at("/parity/scalaTest").asText())
                .isEqualTo("com.graduation.analytics.WarehouseNamespaceSpec");
    }

    // ── 逐向量对账 ─────────────────────────────────────────────────────────

    @TestFactory
    @DisplayName("规格 vectors 逐向量对账（Java 侧）")
    Stream<DynamicTest> vectors() {
        JsonNode vectors = SPEC.get("vectors");
        assertThat(vectors.isArray()).as("规格必须有 vectors 数组").isTrue();
        assertThat(vectors.size()).as("向量数量过少说明规格被削弱").isGreaterThanOrEqualTo(15);

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : vectors) {
            String input = vector.get("input").isNull() ? null : vector.get("input").asText();
            String expect = vector.get("expect").asText();
            tests.add(DynamicTest.dynamicTest(
                    "input=" + (input == null ? "null" : "\"" + input + "\"") + " → " + expect,
                    () -> assertVector(vector, input, expect)));
        }
        return tests.stream();
    }

    private void assertVector(JsonNode vector, String input, String expect) {
        String why = vector.hasNonNull("why") ? vector.get("why").asText() : "";
        assertThat(classify(input)).as("向量判定（%s）", why).isEqualTo(expect);

        if (DEFAULT.equals(expect) || OK.equals(expect)) {
            WarehouseNamespace ns = WarehouseNamespace.ofNullable(input);
            String prefix = DEFAULT.equals(expect) ? WarehouseNamespace.DEFAULT_PREFIX : input;
            Map<String, String> derived = new LinkedHashMap<>();
            for (String layer : WarehouseNamespace.LAYER_SUFFIXES) {
                derived.put(layer, prefix + WarehouseNamespace.SEPARATOR + layer);
            }
            assertThat(ns.layers()).as("五个层库名（%s）", why).isEqualTo(derived);
            assertThat(ns.prefix()).isEqualTo(prefix);
            assertThat(ns.ods()).isEqualTo(derived.get("ods"));
            assertThat(ns.dwd()).isEqualTo(derived.get("dwd"));
            assertThat(ns.dim()).isEqualTo(derived.get("dim"));
            assertThat(ns.dws()).isEqualTo(derived.get("dws"));
            assertThat(ns.ads()).isEqualTo(derived.get("ads"));
            assertThat(ns.table("ads", "ads_operation_overview"))
                    .as("限定表名拼接（%s）", why)
                    .isEqualTo(derived.get("ads") + ".ads_operation_overview");
            assertThat(ns.isDefault()).isEqualTo(WarehouseNamespace.DEFAULT_PREFIX.equals(prefix));

            if (vector.has("names")) {
                Map<String, String> specNames = new LinkedHashMap<>();
                Iterator<String> fields = vector.get("names").fieldNames();
                while (fields.hasNext()) {
                    String layer = fields.next();
                    specNames.put(layer, vector.get("names").get(layer).asText());
                }
                assertThat(specNames).as("规格 names 字段必须与派生式自洽（%s）", why).isEqualTo(derived);
            }
        } else {
            // 非法取值：原样失败、带错误码、不静默兜底
            assertThatThrownBy(() -> WarehouseNamespace.of(input))
                    .as("非法前缀必须抛出（%s）", why)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(expect)
                    .hasMessageContaining(String.valueOf(input));
            assertThat(WarehouseNamespace.validationError(input)).contains(expect);
            assertThat(WarehouseNamespace.validationError(input).orElseThrow()).isEqualTo(expect);
        }
    }

    // ── 行为护栏（规格之外仍必须成立的性质） ─────────────────────────────────

    @Test
    @DisplayName("行为护栏：缺省/null、未知层、非法表名、值相等性")
    void behaviourGuards() {
        assertThat(WarehouseNamespace.defaultNamespace()).isEqualTo(WarehouseNamespace.ofNullable(null));
        assertThat(WarehouseNamespace.ofNullable(null)).isEqualTo(WarehouseNamespace.ofNullable(""));
        assertThat(WarehouseNamespace.validationError(null)).isEmpty();
        assertThat(WarehouseNamespace.validationError("")).isEmpty();
        // 空白串不是缺省：不 trim
        assertThat(WarehouseNamespace.validationError(" ")).contains(WarehouseNamespace.ERR_PATTERN);

        WarehouseNamespace ns = WarehouseNamespace.defaultNamespace();
        assertThat(ns.toString()).contains("dw").contains("dw_ods").contains("dw_ads");
        assertThatThrownBy(() -> ns.layerDb("ODS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ns.layerDb(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ns.table("ods", "a.b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ns.table("ods", "")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ns.table("ods", "ods_user_event")).isEqualTo("dw_ods.ods_user_event");
        assertThat(ns).isEqualTo(WarehouseNamespace.of("dw")).hasSameHashCodeAs(WarehouseNamespace.of("dw"));
        assertThat(ns).isNotEqualTo(WarehouseNamespace.of("a"));
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    /** 与实现同源的判定：仅 null 与空串是缺省，其余交给白名单校验 */
    private static String classify(String input) {
        if (input == null || input.isEmpty()) {
            return DEFAULT;
        }
        return WarehouseNamespace.validationError(input).orElse(OK);
    }

    private static JsonNode read(Path path) {
        // 静态初始化里不用断言库（否则失败会变成 ExceptionInInitializerError，掩盖真实原因）
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("规格文件缺失: " + path);
        }
        try {
            return new ObjectMapper().readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("读取规格失败: " + path, e);
        }
    }
}
