package com.graduation.analytics.metric.publish;

import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.metric.publish.MetricPublisherPort.Check;
import com.graduation.analytics.metric.publish.MetricPublisherPort.DefinitionRef;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7-3：发布对账校验的纯单测（不连库、不起 Spark）。
 *
 * <p>覆盖"必须拦下"的场景，避免出现"看起来发布成功但数据没到"（§16.4）：</p>
 * <ul>
 *   <li>清单快照与请求不一致 / 表缺失 / 清单列与白名单不一致 → BLOCKING；</li>
 *   <li>Hive 分区路径未指向本次快照（可能读到上一版数据）→ BLOCKING；</li>
 *   <li>概览核心字段为空 / 指标码不在字典 / 版本不一致 / 指标值与 ADS 不一致 → BLOCKING；</li>
 *   <li>正常输入 → 无 BLOCKING 失败，且激活后检查通过。</li>
 * </ul>
 */
class MetricPublishValidatorTest {

    private static final String SID = "S20260901_21";
    private static final String DT = "20260901";

    private final MetricPublishValidator validator = new MetricPublishValidator();

    @Test
    @DisplayName("正常输入：清单检查与写库前对账全部通过")
    void happyPathPasses(@TempDir Path dir) throws Exception {
        MetricExportManifest manifest = manifest(dir, SID, DT);
        PublishRequest request = request(dir, SID, DT);

        List<Check> checks = new ArrayList<>(validator.manifestChecks(request, manifest));
        assertThat(MetricPublishValidator.blocked(checks)).isFalse();
        // S3-06：内容摘要逐表核对必须真的跑过并给出通过结论（不是"没这条 check 也算过"）
        assertThat(checks)
                .as("清单阶段必须产出 MP_EXPORT_CHECKSUM，否则'内容验证'只是文档里的一句话")
                .anySatisfy(c -> {
                    assertThat(c.ruleCode()).isEqualTo("MP_EXPORT_CHECKSUM");
                    assertThat(c.passed()).isTrue();
                    assertThat(c.severity()).isEqualTo("BLOCKING");
                });

        List<MetricValue> values = values(SID, DT);
        checks.addAll(validator.prePublishChecks(request, manifest, rows(), written(manifest), values));
        assertThat(MetricPublishValidator.blocked(checks))
                .as("失败规则: %s", MetricPublishValidator.failedRules(checks))
                .isFalse();

        Map<String, Long> dbCounts = new LinkedHashMap<>();
        manifest.tables().forEach(t -> dbCounts.put(t.mysqlTable(), t.rowCount()));
        Map<String, BigDecimal> dbValues = new LinkedHashMap<>();
        values.forEach(v -> dbValues.put(v.getMetricCode(), v.getMetricValue()));
        List<Check> post = validator.postActivationChecks(request, manifest, dbCounts, values.size(), dbValues,
                values, SID, "S20260901_20");
        assertThat(MetricPublishValidator.blocked(post))
                .as("失败规则: %s", MetricPublishValidator.failedRules(post))
                .isFalse();
    }

    @Test
    @DisplayName("清单快照与请求不一致 → 拦下（防把上一版数据当本次发布）")
    void manifestSnapshotMismatchBlocked(@TempDir Path dir) throws Exception {
        MetricExportManifest manifest = manifest(dir, "S20260901_20", DT);
        List<Check> checks = validator.manifestChecks(request(dir, SID, DT), manifest);
        assertThat(MetricPublishValidator.failedRules(checks)).contains("MP_MANIFEST_SNAPSHOT");
    }

    @Test
    @DisplayName("Hive 分区路径未指向本次快照 → 拦下")
    void unpinnedHivePathBlocked(@TempDir Path dir) throws Exception {
        MetricExportManifest manifest = manifest(dir, SID, DT, "snapshot_id=S20260901_20");
        List<Check> checks = validator.manifestChecks(request(dir, SID, DT), manifest);
        assertThat(MetricPublishValidator.failedRules(checks)).contains("MP_HIVE_PATH_PINNED");
    }

    @Test
    @DisplayName("清单缺表 → 拦下")
    void missingTableBlocked(@TempDir Path dir) throws Exception {
        MetricExportManifest full = manifest(dir, SID, DT);
        MetricExportManifest partial = new MetricExportManifest(full.snapshotId(), full.businessDate(), full.dt(),
                full.source(), full.totalRows(), full.tables().subList(0, full.tables().size() - 1));
        List<Check> checks = validator.manifestChecks(request(dir, SID, DT), partial);
        assertThat(MetricPublishValidator.failedRules(checks)).contains("MP_MANIFEST_TABLES");
    }

    @Test
    @DisplayName("导出制品内容被改写（行数不变）→ MP_EXPORT_CHECKSUM 拦下（§12.5 L529 内容验证）")
    void exportChecksumMismatchBlocked(@TempDir Path dir) throws Exception {
        MetricExportManifest manifest = manifest(dir, SID, DT);
        PublishRequest request = request(dir, SID, DT);

        // 先确认这份清单本身是干净的（否则下面的断言可能因为别的原因变红）
        assertThat(MetricPublishValidator.failedRules(validator.manifestChecks(request, manifest))).isEmpty();

        // 篡改一张表的导出文件内容：**行数保持不变**（把 stub 值改掉），
        // 于是 MP_EXPORT_FILES（文件在）与 MP_ADS_ROWS_MATCH（行数对）都不会发现，
        // 只有内容摘要能发现 —— 这正是本规则存在的理由。
        Path tampered = dir.resolve("ads_operation_overview_m.jsonl");
        String before = Files.readString(tampered);
        Files.writeString(tampered, before.replace("{\"stub\":1}", "{\"stub\":2}"));
        assertThat(Files.readString(tampered))
                .as("前置条件：内容确实变了，而字节数不变（同样长度），行数也不变")
                .isNotEqualTo(before);

        List<Check> checks = validator.manifestChecks(request, manifest);
        assertThat(MetricPublishValidator.failedRules(checks))
                .as("内容摘要不匹配必须点名，且不得牵连 MP_EXPORT_FILES")
                .isEqualTo("MP_EXPORT_CHECKSUM");
        assertThat(checks.stream().filter(c -> "MP_EXPORT_CHECKSUM".equals(c.ruleCode())).findFirst()
                .orElseThrow().detail())
                .as("证据里必须给出可定位的表名与两侧摘要")
                .contains("ads_operation_overview_m")
                .contains("清单=")
                .contains("实算=");
        assertThat(MetricPublishValidator.blocked(checks))
                .as("BLOCKING ⇒ 未通过即阻断发布（不得降级为观察项）")
                .isTrue();
    }

    @Test
    @DisplayName("导出制品缺失 → MP_EXPORT_FILES 与 MP_EXPORT_CHECKSUM 各自点名（不互相掩盖）")
    void missingExportFileReportedByBothRules(@TempDir Path dir) throws Exception {
        MetricExportManifest manifest = manifest(dir, SID, DT);
        PublishRequest request = request(dir, SID, DT);
        Files.delete(dir.resolve("ads_hot_product_m.jsonl"));

        List<Check> checks = validator.manifestChecks(request, manifest);
        assertThat(MetricPublishValidator.failedRules(checks))
                .as("文件缺失两个口径都应点名：一个说'不在'，一个说'无法核对内容'")
                .contains("MP_EXPORT_FILES", "MP_EXPORT_CHECKSUM");
    }

    @Test
    @DisplayName("写入行数与清单不一致 / 概览核心为空 / 指标码不在字典 → 拦下")
    void prePublishMismatchBlocked(@TempDir Path dir) throws Exception {
        MetricExportManifest manifest = manifest(dir, SID, DT);
        PublishRequest request = request(dir, SID, DT);

        Map<String, Integer> wrongCounts = new LinkedHashMap<>(written(manifest));
        wrongCounts.put("ads_sale_trend_m", 0);
        List<Check> c1 = validator.prePublishChecks(request, manifest, rows(), wrongCounts, values(SID, DT));
        assertThat(MetricPublishValidator.failedRules(c1)).contains("MP_ADS_ROWS_MATCH");

        Map<String, List<Map<String, Object>>> nullCore = rows();
        Map<String, Object> overview = new LinkedHashMap<>(nullCore.get("ads_operation_overview_m").get(0));
        overview.put("net_sale_amount", null);
        nullCore.put("ads_operation_overview_m", List.of(overview));
        List<Check> c2 = validator.prePublishChecks(request, manifest, nullCore, written(manifest), values(SID, DT));
        assertThat(MetricPublishValidator.failedRules(c2)).contains("MP_OVERVIEW_CORE_NOT_NULL");

        PublishRequest noDict = new PublishRequest(request.runtimeProfileId(), request.runtimeProfileVersion(),
                request.snapshotId(), request.businessDate(), request.businessTime(), request.pipelineRunId(),
                request.exportDir(), Map.of());
        List<Check> c3 = validator.prePublishChecks(noDict, manifest, rows(), written(manifest), values(SID, DT));
        assertThat(MetricPublishValidator.failedRules(c3)).contains("MP_METRIC_DICT_VERSION");
    }

    @Test
    @DisplayName("指标值与 ADS 不一致 → 拦下（发布器不得改口径/重算）")
    void valueMismatchBlocked(@TempDir Path dir) throws Exception {
        MetricExportManifest manifest = manifest(dir, SID, DT);
        List<MetricValue> tampered = values(SID, DT);
        tampered.stream().filter(v -> "gmv".equals(v.getMetricCode()))
                .forEach(v -> v.setMetricValue(new BigDecimal("9999.00")));
        List<Check> checks = validator.prePublishChecks(request(dir, SID, DT), manifest, rows(),
                written(manifest), tampered);
        assertThat(MetricPublishValidator.failedRules(checks)).contains("MP_VALUE_MATCH_ADS");
    }

    @Test
    @DisplayName("激活后 ACTIVE 不是本次快照 / 宽表行数不符 → 拦下")
    void postActivationBlocked(@TempDir Path dir) throws Exception {
        MetricExportManifest manifest = manifest(dir, SID, DT);
        List<MetricValue> values = values(SID, DT);
        Map<String, Long> dbCounts = new LinkedHashMap<>();
        manifest.tables().forEach(t -> dbCounts.put(t.mysqlTable(), t.rowCount()));
        dbCounts.put("ads_sale_trend_m", 0L);
        Map<String, BigDecimal> dbValues = new LinkedHashMap<>();
        values.forEach(v -> dbValues.put(v.getMetricCode(), v.getMetricValue()));

        List<Check> checks = validator.postActivationChecks(request(dir, SID, DT), manifest, dbCounts,
                values.size(), dbValues, values, "S20260901_20", "S20260901_20");
        assertThat(MetricPublishValidator.failedRules(checks))
                .contains("MP_ACTIVE_SNAPSHOT", "MP_ADS_ROWS_DB_MATCH");
    }

    @Test
    @DisplayName("AdsExportReader：声明列齐备的行 + null 保留")
    void exportReaderReadsRows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ads_active_trend_m.jsonl");
        Files.writeString(file, "{\"dau\":3,\"behavior_count\":22}\n{\"dau\":null,\"behavior_count\":0}\n");

        List<Map<String, Object>> rows = new AdsExportReader().readTable(file, "ads_active_trend_m",
                List.of("dau", "behavior_count"));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("dau", 3L).containsEntry("behavior_count", 22L);
        assertThat(rows.get(1)).containsEntry("dau", null);
    }

    @Test
    @DisplayName("AdsExportReader：null 值保留（有键无值）→ 仍可读")
    void exportReaderKeepsExplicitNullValue(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ads_active_trend_m.jsonl");
        Files.writeString(file, "{\"dau\":null,\"behavior_count\":null}\n");

        List<Map<String, Object>> rows = new AdsExportReader().readTable(file, "ads_active_trend_m",
                List.of("dau", "behavior_count"));
        assertThat(rows).hasSize(1);
        // 显式 null（键在、值为空）是真实语义"当日没有该指标值"，必须保留
        assertThat(rows.get(0)).containsEntry("dau", null).containsEntry("behavior_count", null);
    }

    @Test
    @DisplayName("AdsExportReader：导出缺声明列 → 拒绝（不得补 null 冒充\"值为空\"）")
    void exportReaderRejectsMissingDeclaredColumn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ads_active_trend_m.jsonl");
        // 只写了 dau：behavior_count 整列缺失（例如导出侧版本落后于声明列集）
        Files.writeString(file, "{\"dau\":3}\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AdsExportReader()
                        .readTable(file, "ads_active_trend_m", List.of("dau", "behavior_count")))
                .as("缺列若被补成 null，发布侧会写出\"值为空\"的假数据，且没有任何 check 会点名它")
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("缺列")
                .hasMessageContaining("behavior_count");
    }

    @Test
    @DisplayName("AdsExportReader：出现非白名单列 → 拒绝（不静默丢弃）")
    void exportReaderRejectsUnknownColumn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ads_active_trend_m.jsonl");
        Files.writeString(file, "{\"dau\":3,\"behavior_count\":22,\"hacked\":1}\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AdsExportReader()
                        .readTable(file, "ads_active_trend_m", List.of("dau", "behavior_count")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("非白名单列");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private PublishRequest request(Path dir, String sid, String dt) {
        return new PublishRequest(1L, 7, sid, dt, "2026-09-01T00:00", 99L, dir, dictionary());
    }

    private Map<String, DefinitionRef> dictionary() {
        Map<String, DefinitionRef> dict = new LinkedHashMap<>();
        dict.put("pv", new DefinitionRef("v1", "次"));
        dict.put("uv", new DefinitionRef("v1", "人"));
        dict.put("dau", new DefinitionRef("v1", "人"));
        dict.put("paid_order_cnt", new DefinitionRef("v1", "单"));
        dict.put("gmv", new DefinitionRef("v1", "元"));
        dict.put("net_sale", new DefinitionRef("v1", "元"));
        dict.put("avg_order_value", new DefinitionRef("v1", "元"));
        dict.put("refund_rate", new DefinitionRef("v2", ""));
        dict.put("full_refund_rate", new DefinitionRef("v1", ""));
        dict.put("buy_rate", new DefinitionRef("v1", ""));
        return dict;
    }

    private List<MetricValue> values(String sid, String dt) {
        Map<String, BigDecimal> raw = new LinkedHashMap<>();
        raw.put("pv", new BigDecimal("7"));
        raw.put("uv", new BigDecimal("3"));
        raw.put("dau", new BigDecimal("3"));
        raw.put("paid_order_cnt", new BigDecimal("5"));
        raw.put("gmv", new BigDecimal("2042.00"));
        raw.put("net_sale", new BigDecimal("1493.00"));
        raw.put("avg_order_value", new BigDecimal("408.40"));
        raw.put("refund_rate", new BigDecimal("0.6000"));
        raw.put("full_refund_rate", new BigDecimal("0.2000"));
        raw.put("buy_rate", new BigDecimal("1.0000"));
        List<MetricValue> values = new ArrayList<>();
        raw.forEach((code, value) -> {
            MetricValue v = new MetricValue();
            v.setSnapshotId(sid);
            v.setMetricCode(code);
            v.setMetricValue(value);
            v.setUnit(dictionary().get(code).unit());
            v.setPeriod("day:2026-09-01");
            v.setDefinitionVersion(dictionary().get(code).definitionVersion());
            values.add(v);
        });
        return values;
    }

    private Map<String, List<Map<String, Object>>> rows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("ads_operation_overview_m", List.of(overview()));
        rows.put("ads_sale_trend_m", List.of(new LinkedHashMap<>(Map.of("order_count", 5L, "buyer_count", 3L,
                "sale_amount", new BigDecimal("2042.00"), "avg_order_value", new BigDecimal("408.40"),
                "net_sale_amount", new BigDecimal("1493.00")))));
        rows.put("ads_behavior_funnel_m", List.of(funnel("view"), funnel("pay")));
        rows.put("ads_active_trend_m", List.of(new LinkedHashMap<>(Map.of("dau", 3L, "behavior_count", 22L))));
        for (String table : List.of("ads_hot_product_m", "ads_product_conversion_m", "ads_user_profile_m",
                "ads_data_quality_m")) {
            rows.put(table, List.of());
        }
        return rows;
    }

    private Map<String, Object> overview() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pv", 7L);
        row.put("uv", 3L);
        row.put("dau", 3L);
        row.put("order_count", 5L);
        row.put("sale_amount", new BigDecimal("2042.00"));
        row.put("net_sale_amount", new BigDecimal("1493.00"));
        row.put("avg_order_value", new BigDecimal("408.40"));
        row.put("refund_rate", new BigDecimal("0.6000"));
        row.put("full_refund_rate", new BigDecimal("0.2000"));
        return row;
    }

    private Map<String, Object> funnel(String stage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("user_count", 3L);
        row.put("conversion_rate", new BigDecimal("1.0000"));
        row.put("overall_buy_rate", new BigDecimal("1.0000"));
        return row;
    }

    private Map<String, Integer> written(MetricExportManifest manifest) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        manifest.tables().forEach(t -> counts.put(t.mysqlTable(), (int) t.rowCount()));
        return counts;
    }

    private MetricExportManifest manifest(Path dir, String sid, String dt) throws Exception {
        return manifest(dir, sid, dt, "snapshot_id=" + sid);
    }

    /** 构造与 MetricAdsCatalog 完全一致的清单（含真实导出文件，供 MP_EXPORT_FILES 检查） */
    private MetricExportManifest manifest(Path dir, String sid, String dt, String hivePathMarker) throws Exception {
        List<MetricExportManifest.TableExport> tables = new ArrayList<>();
        Map<String, List<Map<String, Object>>> rows = rows();
        for (com.graduation.analytics.metric.MetricAdsCatalog spec
                : com.graduation.analytics.metric.MetricAdsCatalog.ALL) {
            Path file = dir.resolve(spec.name() + ".jsonl");
            if (!Files.exists(file)) {
                Files.writeString(file, "{\"stub\":1}\n");
            }
            tables.add(new MetricExportManifest.TableExport("dw_ads." + spec.name().replace("_m", ""),
                    spec.name(), rows.getOrDefault(spec.name(), List.of()).size(), spec.columns(),
                    "file:/D:/Develop_code/GraduationProject/spark-warehouse/dw_ads.db/" + spec.name()
                            + "/" + hivePathMarker + "/dt=" + dt,
                    file.toString(),
                    MetricExportManifest.crc32(file)));
        }
        return new MetricExportManifest(sid, dt, dt, "spark-ads",
                tables.stream().mapToLong(MetricExportManifest.TableExport::rowCount).sum(), tables);
    }
}
