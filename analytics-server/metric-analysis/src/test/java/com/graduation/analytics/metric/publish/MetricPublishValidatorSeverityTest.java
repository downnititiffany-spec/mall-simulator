package com.graduation.analytics.metric.publish;

import com.graduation.analytics.metric.MetricAdsCatalog;
import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.RuleSeverity;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-88（D-142 §1）：{@link MetricPublishValidator#blocked} / {@link MetricPublishValidator#failedRules}
 * 的**严重度判据**测试。
 *
 * <p>为什么单独一个测试类：这两个静态方法是「阻断发布」的最终判据（
 * {@code MetricPublisher} 在写指标值前、写指标值后、切 ACTIVE 后各调一次）。旧实现写死
 * {@code "BLOCKING".equals(c.severity())}，既不认 {@code ERROR}（D-142 §1 明示与 BLOCKING 同等阻断），
 * 也不认大小写/空串/null/未登记码（这些按保守默认都应阻断），同时会把目录判为
 * {@code WARN}/{@code INFO} 的观察项误当阻断。本类把这四条边界逐一钉住。</p>
 *
 * <p>唯一的**生产行为变化**在最后一个用例：{@code MP_OLD_ACTIVE_ARCHIVED}（目录口径 {@code INFO}）
 * 未通过时，旧实现会误拦发布，新实现不拦 —— 这正是「WARN/INFO 只记录不阻断」的正确落地。</p>
 */
class MetricPublishValidatorSeverityTest {

    private static final String SID = "S20260901_88";
    private static final String DT = "20260901";

    private final MetricPublishValidator validator = new MetricPublishValidator();

    @Test
    @DisplayName("小写 blocking 同样阻断（旧实现按字面 equals 比较会漏检）")
    void lowercaseBlockingIsTreatedAsBlocking() {
        List<Check> checks = List.of(check("MP_MANIFEST_TABLES", "blocking", false));

        assertThat(MetricPublishValidator.blocked(checks)).isTrue();
        assertThat(MetricPublishValidator.failedRules(checks)).isEqualTo("MP_MANIFEST_TABLES");
    }

    @Test
    @DisplayName("ERROR 级未通过同样阻断（D-142 §1：BLOCKING 与 ERROR 失败即阻断发布）")
    void errorSeverityIsTreatedAsBlocking() {
        List<Check> checks = List.of(check("MP_ACTIVE_SNAPSHOT", "error", false));

        assertThat(MetricPublishValidator.blocked(checks)).isTrue();
        assertThat(MetricPublishValidator.failedRules(checks)).contains("MP_ACTIVE_SNAPSHOT");
    }

    @Test
    @DisplayName("WARN 未通过：记录规则码但不阻断（记录并展示，不阻断发布）")
    void warnSeverityDoesNotBlock() {
        List<Check> checks = List.of(check("MP_OLD_ACTIVE_ARCHIVED", "warn", false));

        assertThat(MetricPublishValidator.blocked(checks)).isFalse();
        assertThat(MetricPublishValidator.failedRules(checks)).isEmpty();
    }

    @Test
    @DisplayName("INFO 未通过：记录但不阻断")
    void infoSeverityDoesNotBlock() {
        List<Check> checks = List.of(check("MP_OLD_ACTIVE_ARCHIVED", "info", false));

        assertThat(MetricPublishValidator.blocked(checks)).isFalse();
        assertThat(MetricPublishValidator.failedRules(checks)).isEmpty();
    }

    @Test
    @DisplayName("空串 severity：按阻断的保守默认（旧实现会漏检）")
    void emptySeverityIsTreatedAsBlocking() {
        List<Check> checks = List.of(check("MP_MANIFEST_TABLES", "", false));

        assertThat(MetricPublishValidator.blocked(checks)).isTrue();
        assertThat(MetricPublishValidator.failedRules(checks)).contains("MP_MANIFEST_TABLES");
    }

    @Test
    @DisplayName("null severity：按阻断的保守默认（旧实现会漏检）")
    void nullSeverityIsTreatedAsBlocking() {
        List<Check> checks = List.of(check("MP_MANIFEST_TABLES", null, false));

        assertThat(MetricPublishValidator.blocked(checks)).isTrue();
        assertThat(MetricPublishValidator.failedRules(checks)).contains("MP_MANIFEST_TABLES");
    }

    @Test
    @DisplayName("未登记规则码：不再看字面 severity，一律按未登记阻断（旧实现会漏检）")
    void unknownRuleCodeSeverityIsTreatedAsBlocking() {
        List<Check> checks = List.of(check("BRAND_NEW_MP_RULE", null, false));

        // 旧写法 RuleSeverity.of("BRAND_NEW_MP_RULE") 已被版本化 resolve 取代；
        // 需给一个**明确**的冻结集才谈得上「哪一版」（§7.3.1 line 520）。
        RuleSeverity.RuleVerdict verdict =
                RuleSeverity.resolve(QualityRuleCatalog.DEFAULT.freeze(null), "BRAND_NEW_MP_RULE", 0);
        assertThat(verdict.registered()).isFalse();
        assertThat(verdict.ruleVersion()).isZero();
        assertThat(verdict.blocks()).isTrue();
        assertThat(MetricPublishValidator.blocked(checks)).isTrue();
        assertThat(MetricPublishValidator.failedRules(checks)).contains("BRAND_NEW_MP_RULE");
    }

    /**
     * R01 审计 #1 的发布侧护栏（跨模块版见 platform-app 的
     * {@code guard/RuleSeverityPathConsistencyTest}，那里能同时看到质量门与发布校验）。
     *
     * <p>此处能独立钉住的是：{@code blocked()} 的判据在**任意字面 severity** 与**任意登记/未登记码**
     * 下都等于唯一所有者 {@code RuleSeverity.resolve} 的结论。换句话说 —— 字面 severity 不再是
     * 判据的一部分，这正是 R01 缺口（同一码在两条路径上得到不同严重度）在发布侧被消除的证据。</p>
     */
    @Test
    @DisplayName("R01#1 发布侧护栏：blocked/failedRules 的判据在任意字面 severity 下都等于 resolve")
    void decisionAlwaysEqualsRuleSeverityOwnerWhateverLiteralIsGiven() {
        QualityRuleCatalog.FrozenRules rules = QualityRuleCatalog.DEFAULT.freeze("ODS_TO_ADS");

        // 覆盖 4 个档位 + 条件观察项 + 未登记码；字面 severity 取大小写/空白/空串/null/乱填
        List<String> codes = List.of(
                "MP_MANIFEST_TABLES", "MP_ACTIVE_SNAPSHOT", "MP_OLD_ACTIVE_ARCHIVED",
                "EVENT_ID_UNIQUE", "ADS_STAGING_SNAPSHOT_ISOLATION", "BRAND_NEW_MP_RULE");
        // 注意必须用 Arrays.asList：List.of 不接受 null，而 null 是本用例要覆盖的边界之一
        List<String> literals = Arrays.asList("BLOCKING", "blocking", "ERROR", "WARN", "INFO",
                "", "  ", null, "UNKNOWN_LEVEL");

        for (String code : codes) {
            for (Integer passedFlag : new Integer[] {0, 1, null}) {
                boolean passed = passedFlag != null && passedFlag == 1;
                RuleSeverity.RuleVerdict verdict = RuleSeverity.resolve(rules, code, passedFlag);

                for (String literal : literals) {
                    List<Check> checks = List.of(check(code, literal, passed));
                    assertThat(MetricPublishValidator.blocked(checks, rules))
                            .as("blocked(%s, 字面 severity=%s, passed=%s) 必须等于 resolve", code, literal, passedFlag)
                            .isEqualTo(verdict.blocks());
                    assertThat(MetricPublishValidator.failedRules(checks, rules).contains(code))
                            .as("failedRules(%s, 字面 severity=%s, passed=%s) 必须等于 resolve",
                                    code, literal, passedFlag)
                            .isEqualTo(verdict.blocks());
                }
            }
        }
    }

    @Test
    @DisplayName("passed=true 的阻断级 check 不算阻断（判据含 passed，不只看严重度）")
    void blockingCheckThatPassedDoesNotBlock() {
        List<Check> checks = List.of(check("MP_MANIFEST_TABLES", "BLOCKING", true));

        assertThat(MetricPublishValidator.blocked(checks)).isFalse();
        assertThat(MetricPublishValidator.failedRules(checks)).isEmpty();
    }

    @Test
    @DisplayName("MP_OLD_ACTIVE_ARCHIVED（目录口径 INFO）未通过时不阻断 —— 旧实现会误拦发布")
    void failedInfoOldActiveArchivedDoesNotBlock(@TempDir Path dir) throws Exception {
        MetricExportManifest manifest = manifest(dir);
        PublishRequest request = request(dir);
        List<MetricValue> values = values();

        // previousActiveSnapshotId == request.snapshotId() ⇒ archived=false ⇒ 该条 INFO check 未通过
        List<Check> post = validator.postActivationChecks(request, manifest, dbCounts(manifest), values.size(),
                dbValues(values), values, SID, SID);

        Check archived = post.stream().filter(c -> "MP_OLD_ACTIVE_ARCHIVED".equals(c.ruleCode()))
                .findFirst().orElseThrow();
        assertThat(archived.severity()).isEqualTo(RuleSeverity.INFO);
        assertThat(archived.passed()).isFalse();

        assertThat(MetricPublishValidator.failedRules(post)).doesNotContain("MP_OLD_ACTIVE_ARCHIVED");
        assertThat(MetricPublishValidator.blocked(post))
                .as("失败规则: %s", MetricPublishValidator.failedRules(post))
                .isFalse();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private Check check(String ruleCode, String severity, boolean passed) {
        return new Check(ruleCode, "PUBLISH", "analytics_metric", 1, passed ? 0 : 1, severity, passed,
                "F-88 严重度测试夹具");
    }

    private PublishRequest request(Path dir) {
        return new PublishRequest(1L, 7, SID, DT, "2026-09-01T00:00", 99L, dir, dictionary());
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
        dict.put("refund_rate", new DefinitionRef("v1", ""));
        return dict;
    }

    private List<MetricValue> values() {
        Map<String, BigDecimal> raw = new LinkedHashMap<>();
        raw.put("pv", new BigDecimal("7"));
        raw.put("uv", new BigDecimal("3"));
        raw.put("dau", new BigDecimal("3"));
        raw.put("paid_order_cnt", new BigDecimal("5"));
        raw.put("gmv", new BigDecimal("2042.00"));
        raw.put("net_sale", new BigDecimal("1493.00"));
        raw.put("avg_order_value", new BigDecimal("408.40"));
        raw.put("refund_rate", new BigDecimal("0.6000"));
        List<MetricValue> values = new ArrayList<>();
        raw.forEach((code, value) -> {
            MetricValue v = new MetricValue();
            v.setSnapshotId(SID);
            v.setMetricCode(code);
            v.setMetricValue(value);
            v.setUnit(dictionary().get(code).unit());
            v.setPeriod("day:2026-09-01");
            v.setDefinitionVersion(dictionary().get(code).definitionVersion());
            values.add(v);
        });
        return values;
    }

    private Map<String, BigDecimal> dbValues(List<MetricValue> values) {
        Map<String, BigDecimal> db = new LinkedHashMap<>();
        values.forEach(v -> db.put(v.getMetricCode(), v.getMetricValue()));
        return db;
    }

    private Map<String, Long> dbCounts(MetricExportManifest manifest) {
        Map<String, Long> counts = new LinkedHashMap<>();
        manifest.tables().forEach(t -> counts.put(t.mysqlTable(), t.rowCount()));
        return counts;
    }

    private Map<String, List<Map<String, Object>>> rows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("pv", 7L);
        overview.put("uv", 3L);
        overview.put("dau", 3L);
        overview.put("order_count", 5L);
        overview.put("sale_amount", new BigDecimal("2042.00"));
        overview.put("net_sale_amount", new BigDecimal("1493.00"));
        rows.put("ads_operation_overview_m", List.of(overview));
        for (String table : List.of("ads_sale_trend_m", "ads_behavior_funnel_m", "ads_active_trend_m")) {
            rows.put(table, List.of(new LinkedHashMap<>(Map.of("stub", 1L))));
        }
        for (String table : List.of("ads_hot_product_m", "ads_product_conversion_m", "ads_user_profile_m",
                "ads_data_quality_m")) {
            rows.put(table, List.of());
        }
        return rows;
    }

    private MetricExportManifest manifest(Path dir) throws Exception {
        Map<String, List<Map<String, Object>>> rows = rows();
        List<MetricExportManifest.TableExport> tables = new ArrayList<>();
        for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
            Path file = dir.resolve(spec.name() + ".jsonl");
            if (!Files.exists(file)) {
                Files.writeString(file, "{\"stub\":1}\n");
            }
            tables.add(new MetricExportManifest.TableExport("dw_ads." + spec.name().replace("_m", ""),
                    spec.name(), rows.getOrDefault(spec.name(), List.of()).size(), spec.columns(),
                    "file:/D:/Develop_code/GraduationProject/spark-warehouse/dw_ads.db/" + spec.name()
                            + "/snapshot_id=" + SID + "/dt=" + DT,
                    file.toString()));
        }
        return new MetricExportManifest(SID, DT, DT, "spark-ads",
                tables.stream().mapToLong(MetricExportManifest.TableExport::rowCount).sum(), tables);
    }
}
