package com.graduation.analytics.metric.publish;

import com.graduation.analytics.metric.MetricAdsCatalog;
import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.RuleSeverity;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.metric.publish.MetricPublisherPort.Check;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R7-3（V2.0 §17.5 第 6 步）：发布对账校验。
 *
 * <p>纯函数：输入是"清单 + 真实写出的行 + 待写指标值 + 激活后实读计数"，输出是 check 列表。
 * 不做任何 IO 之外的计算，业务指标**一律不在这里算**（发布器不算业务指标，只搬运 ADS 结果）。</p>
 *
 * <p>凡是**阻断级**（{@code BLOCKING}/{@code ERROR}，D-142 §1）不通过：不得写指标值、不得切 ACTIVE 指针，
 * 旧 ACTIVE 必须保持可用。严重度不在这里各写各的 —— 一律由 {@link RuleSeverity}（唯一所有者）按规则码给出，
 * 判定走 {@link RuleSeverity#blocks(String, String, Integer)}（大小写不敏感，未知/null 按阻断的保守默认）。</p>
 */
@Component
public class MetricPublishValidator {

    /** 发布必须有数据的表（其它表允许 0 行，例如当日无退款/无品类数据） */    private static final List<String> REQUIRED_NON_EMPTY = List.of(
            "ads_operation_overview_m", "ads_sale_trend_m", "ads_behavior_funnel_m", "ads_active_trend_m");

    /** 概览表必须非空的字段（核心指标来源） */
    private static final List<String> OVERVIEW_REQUIRED = List.of(
            "pv", "uv", "dau", "order_count", "sale_amount", "net_sale_amount");

    /** ── 阶段一：清单自检（还没写任何行） ───────────────────────────────── */
    public List<Check> manifestChecks(PublishRequest request, MetricExportManifest manifest) {
        return manifestChecks(request, manifest, QualityRuleCatalog.DEFAULT.freeze(null));
    }

    /**
     * 版本化入口（§7.3.1 line 520）：由调用方传入**该 run 冻结的规则集**，
     * check 的 severity 一律由 {@link RuleSeverity#resolve} 按该冻结集解析，不在本类各写各的。
     */
    public List<Check> manifestChecks(PublishRequest request, MetricExportManifest manifest,
                                      QualityRuleCatalog.FrozenRules rules) {
        List<Check> checks = new ArrayList<>();

        boolean snapshotOk = request.snapshotId().equals(manifest.snapshotId())
                && request.businessDate().equals(manifest.businessDate());
        checks.add(check("MP_MANIFEST_SNAPSHOT", "", 2, snapshotOk ? 0 : 1, snapshotOk,
                "清单快照/业务日与本次发布一致",
                "request=" + request.snapshotId() + "/" + request.businessDate()
                        + " manifest=" + manifest.snapshotId() + "/" + manifest.businessDate(),
                rules));

        List<String> missing = new ArrayList<>();
        List<String> columnMismatch = new ArrayList<>();
        for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
            MetricExportManifest.TableExport t = manifest.table(spec.name());
            if (t == null) {
                missing.add(spec.name());
                continue;
            }
            if (!new LinkedHashSet<>(spec.columns()).equals(new LinkedHashSet<>(t.columns()))) {
                columnMismatch.add(spec.name() + " 清单=" + t.columns() + " 白名单=" + spec.columns());
            }
        }
        checks.add(check("MP_MANIFEST_TABLES", MetricAdsCatalog.ALL.size(),
                missing.size() + columnMismatch.size(), missing.isEmpty() && columnMismatch.isEmpty(),
                "8 张宽表齐备且列与白名单一致",
                "缺失=" + missing + " 列不一致=" + columnMismatch, rules));

        long unpinned = manifest.tables().stream()
                .filter(t -> t.hivePath() == null || !t.hivePath().contains("snapshot_id=" + request.snapshotId()))
                .count();
        checks.add(check("MP_HIVE_PATH_PINNED", manifest.tables().size(), unpinned, unpinned == 0,
                "每张表来源分区指向本次快照（防读到上一版数据）",
                manifest.tables().stream().map(t -> t.mysqlTable() + "<-" + t.hivePath()).reduce((a, b) -> a + "; " + b)
                        .orElse("无"), rules));

        long missingFiles = manifest.tables().stream()
                .filter(t -> t.exportFile() == null || !Files.isRegularFile(java.nio.file.Path.of(t.exportFile())))
                .count();
        checks.add(check("MP_EXPORT_FILES", manifest.tables().size(), missingFiles, missingFiles == 0,
                "导出文件全部存在", "exportDir=" + request.exportDir(), rules));

        return checks;
    }

    /** ── 阶段二：写库前对账（ADS 行已在库，指标值待写） ─────────────────── */
    public List<Check> prePublishChecks(PublishRequest request, MetricExportManifest manifest,
                                        Map<String, List<Map<String, Object>>> rowsByTable,
                                        Map<String, Integer> writtenCounts, List<MetricValue> values) {
        return prePublishChecks(request, manifest, rowsByTable, writtenCounts, values,
                QualityRuleCatalog.DEFAULT.freeze(null));
    }

    /** 版本化入口：check 的 severity 由该 run 冻结的规则集解析（§7.3.1 line 520）。 */
    public List<Check> prePublishChecks(PublishRequest request, MetricExportManifest manifest,
                                        Map<String, List<Map<String, Object>>> rowsByTable,
                                        Map<String, Integer> writtenCounts, List<MetricValue> values,
                                        QualityRuleCatalog.FrozenRules rules) {
        List<Check> checks = new ArrayList<>();

        long mismatch = 0;
        long total = 0;
        StringBuilder detail = new StringBuilder();
        for (MetricExportManifest.TableExport t : manifest.tables()) {
            int written = writtenCounts.getOrDefault(t.mysqlTable(), -1);
            total += t.rowCount();
            if (written != t.rowCount()) {
                mismatch++;
            }
            detail.append(t.mysqlTable()).append("=manifest:").append(t.rowCount())
                    .append("/written:").append(written).append(" ");
        }
        checks.add(check("MP_ADS_ROWS_MATCH", total, mismatch, mismatch == 0,
                "写入行数 = 导出清单行数", detail.toString().trim(), rules));

        long emptyRequired = REQUIRED_NON_EMPTY.stream()
                .filter(table -> {
                    List<Map<String, Object>> rows = rowsByTable.get(table);
                    return rows == null || rows.isEmpty();
                }).count();
        checks.add(check("MP_REQUIRED_TABLES_NONEMPTY", REQUIRED_NON_EMPTY.size(), emptyRequired,
                emptyRequired == 0, "概览/趋势/漏斗/活跃表必须有数据", REQUIRED_NON_EMPTY.toString(), rules));

        long badKeys = 0;
        for (Map.Entry<String, List<Map<String, Object>>> e : rowsByTable.entrySet()) {
            Set<Integer> keySizes = new LinkedHashSet<>();
            e.getValue().forEach(r -> keySizes.add(r.size()));
            if (keySizes.size() > 1) {
                badKeys++;
            }
        }
        checks.add(check("MP_ROW_SHAPE_CONSISTENT", rowsByTable.size(), badKeys, badKeys == 0,
                "同表各行列集一致（否则批量插入会产生错位）", "表数=" + rowsByTable.size(), rules));

        List<Map<String, Object>> overview = rowsByTable.getOrDefault("ads_operation_overview_m", List.of());
        long nullCore = overview.isEmpty() ? OVERVIEW_REQUIRED.size()
                : OVERVIEW_REQUIRED.stream().filter(c -> overview.get(0).get(c) == null).count();
        checks.add(check("MP_OVERVIEW_CORE_NOT_NULL", OVERVIEW_REQUIRED.size(), nullCore, nullCore == 0,
                "概览核心字段非空", "overview=" + (overview.isEmpty() ? "无行" : String.valueOf(overview.get(0))), rules));

        // 指标字典对账（§17.5 版本对账）：code 必须在字典内，版本必须与字典一致
        List<String> unknown = new ArrayList<>();
        List<String> versionMismatch = new ArrayList<>();
        for (MetricValue v : values) {
            MetricPublisherPort.DefinitionRef ref = request.definitionVersions().get(v.getMetricCode());
            if (ref == null) {
                unknown.add(v.getMetricCode());
            } else if (!ref.definitionVersion().equals(v.getDefinitionVersion())) {
                versionMismatch.add(v.getMetricCode() + " 值=" + v.getDefinitionVersion()
                        + " 字典=" + ref.definitionVersion());
            }
        }
        checks.add(check("MP_METRIC_DICT_VERSION", values.size(), unknown.size() + versionMismatch.size(),
                unknown.isEmpty() && versionMismatch.isEmpty(),
                "指标码在字典内且版本与字典一致", "未知=" + unknown + " 版本不一致=" + versionMismatch, rules));

        // 指标值必须与 ADS 概览同值（发布器不重算，只搬运）
        Map<String, BigDecimal> byCode = new java.util.LinkedHashMap<>();
        values.forEach(v -> byCode.put(v.getMetricCode(), v.getMetricValue()));
        int valueMismatch = 0;
        StringBuilder valueDetail = new StringBuilder();
        if (!overview.isEmpty()) {
            Map<String, Object> row = overview.get(0);
            valueMismatch += compare(valueDetail, byCode, "gmv", row.get("sale_amount"));
            valueMismatch += compare(valueDetail, byCode, "net_sale", row.get("net_sale_amount"));
            valueMismatch += compare(valueDetail, byCode, "paid_order_cnt", row.get("order_count"));
        }
        checks.add(check("MP_VALUE_MATCH_ADS", 3, valueMismatch, valueMismatch == 0,
                "metric_value 与 ADS 概览同值", valueDetail.length() == 0 ? "gmv/net_sale/paid_order_cnt 一致"
                        : valueDetail.toString().trim(), rules));

        checks.add(check("MP_METRIC_VALUE_COUNT", 8, Math.max(0, 8 - values.size()), values.size() >= 8,
                "至少写入 8 条核心指标值", "实际=" + values.size() + " 码=" + byCode.keySet(), rules));

        return checks;
    }

    /** ── 阶段三：激活后实读对账（读账号 metric_read 必须能看到） ────────── */
    public List<Check> postActivationChecks(PublishRequest request, MetricExportManifest manifest,
                                            Map<String, Long> dbCounts, long dbValueCount,
                                            Map<String, BigDecimal> dbValues, List<MetricValue> expectedValues,
                                            String activeSnapshotId, String previousActiveSnapshotId) {
        return postActivationChecks(request, manifest, dbCounts, dbValueCount, dbValues, expectedValues,
                activeSnapshotId, previousActiveSnapshotId, QualityRuleCatalog.DEFAULT.freeze(null));
    }

    /** 版本化入口：check 的 severity 由该 run 冻结的规则集解析（§7.3.1 line 520）。 */
    public List<Check> postActivationChecks(PublishRequest request, MetricExportManifest manifest,
                                            Map<String, Long> dbCounts, long dbValueCount,
                                            Map<String, BigDecimal> dbValues, List<MetricValue> expectedValues,
                                            String activeSnapshotId, String previousActiveSnapshotId,
                                            QualityRuleCatalog.FrozenRules rules) {
        List<Check> checks = new ArrayList<>();

        checks.add(check("MP_ACTIVE_SNAPSHOT", 1, request.snapshotId().equals(activeSnapshotId) ? 0 : 1,
                request.snapshotId().equals(activeSnapshotId), "ACTIVE 指针指向本次快照",
                "active=" + activeSnapshotId + " 期望=" + request.snapshotId(), rules));

        long countMismatch = 0;
        StringBuilder detail = new StringBuilder();
        for (MetricExportManifest.TableExport t : manifest.tables()) {
            long db = dbCounts.getOrDefault(t.mysqlTable(), -1L);
            if (db != t.rowCount()) {
                countMismatch++;
            }
            detail.append(t.mysqlTable()).append("=").append(db).append("/").append(t.rowCount()).append(" ");
        }
        checks.add(check("MP_ADS_ROWS_DB_MATCH", manifest.tables().size(), countMismatch, countMismatch == 0,
                "只读账号实读宽表行数 = 清单行数", detail.toString().trim(), rules));

        long valueMismatch = expectedValues.stream().filter(v -> {
            BigDecimal db = dbValues.get(v.getMetricCode());
            return db == null || db.compareTo(v.getMetricValue() == null ? BigDecimal.ZERO : v.getMetricValue()) != 0;
        }).count();
        checks.add(check("MP_METRIC_VALUE_DB_MATCH", expectedValues.size(),
                valueMismatch + (dbValueCount == expectedValues.size() ? 0 : 1),
                valueMismatch == 0 && dbValueCount == expectedValues.size(),
                "只读账号实读指标值 = 发布值", "行数=" + dbValueCount + "/" + expectedValues.size()
                        + " 值不一致=" + valueMismatch, rules));

        boolean archived = previousActiveSnapshotId == null || !previousActiveSnapshotId.equals(request.snapshotId());
        checks.add(new Check("MP_OLD_ACTIVE_ARCHIVED", "PUBLISH", "metric_snapshot", 1, 0,
                severityOf(rules, "MP_OLD_ACTIVE_ARCHIVED", archived), archived,
                "上一个 ACTIVE=" + previousActiveSnapshotId + "（本次=" + request.snapshotId() + "）"));

        return checks;
    }

    private int compare(StringBuilder detail, Map<String, BigDecimal> byCode, String code, Object adsValue) {
        BigDecimal expected = byCode.get(code);
        BigDecimal actual = adsValue == null ? null : new BigDecimal(String.valueOf(adsValue));
        if (expected == null || actual == null || expected.compareTo(actual) != 0) {
            detail.append(code).append(": 值=").append(expected).append(" ADS=").append(actual).append(" ");
            return 1;
        }
        return 0;
    }

    private Check check(String ruleCode, long total, long errors, boolean passed, String message, String detail,
                        QualityRuleCatalog.FrozenRules rules) {
        return new Check(ruleCode, "PUBLISH", "analytics_metric", total, errors,
                severityOf(rules, ruleCode, passed), passed, message + " | " + detail);
    }

    private Check check(String ruleCode, String targetTable, long total, long errors, boolean passed,
                        String message, String detail, QualityRuleCatalog.FrozenRules rules) {
        return new Check(ruleCode, "PUBLISH", targetTable, total, errors,
                severityOf(rules, ruleCode, passed), passed, message + " | " + detail);
    }

    /**
     * 有效严重度一律经 {@link RuleSeverity#resolve} 解析（唯一所有者），本类不再各写各的。
     *
     * <p>未登记码得到 {@code effectiveSeverity=BLOCKING} 且 {@code registered=false}，
     * 因此 {@link #blocked} 判为阻断 ⇒ 停止发布，符合 §7.3.1 line 524
     * 「未知规则码不可盲信传来的 WARN，应停止发布并报未登记规则」。
     * 这同时消除了 R01 实测的**同码两路径分叉**：本类与 {@code DataQualityGate} 现在是同一个 {@code resolve}。</p>
     */
    private static String severityOf(QualityRuleCatalog.FrozenRules rules, String ruleCode, boolean passed) {
        return RuleSeverity.resolve(rules, ruleCode, RuleSeverity.failedFlag(passed)).effectiveSeverity();
    }

    /**
     * 是否有阻断失败的 check（F-88 裁决 4）。
     *
     * <p>判据走 {@link RuleSeverity#blocks(QualityRuleCatalog.FrozenRules, String, Integer)}：
     * 按**该 run 冻结的规则集**解析有效严重度，再做 {@code passed != 1} 判定。旧实现写死
     * {@code "BLOCKING".equals(c.severity())}，既漏检 {@code "error"}（大小写）／{@code ""}／
     * {@code null}／未登记码，又会把目录判为 {@code WARN}/{@code INFO} 的观察项误当阻断。</p>
     *
     * <p><b>注意</b>：{@code Check.severity()} 是本类按同一 {@code resolve} 写进去的产物，
     * 这里**不再回读它**做第二次判定 —— 否则「按库中字面 severity 判」会重新引入第二个所有者
     * （R01 实测的分叉形态）。</p>
     */
    public static boolean blocked(List<Check> checks, QualityRuleCatalog.FrozenRules rules) {
        return checks.stream().anyMatch(c -> RuleSeverity.blocks(rules, c.ruleCode(),
                RuleSeverity.failedFlag(c.passed())));
    }

    /** @deprecated 过渡兼容：请传该 run 的冻结规则集，见 {@link #blocked(List, QualityRuleCatalog.FrozenRules)}。 */
    @Deprecated
    public static boolean blocked(List<Check> checks) {
        return blocked(checks, QualityRuleCatalog.DEFAULT.freeze(null));
    }

    /** 阻断失败的规则码（逗号连接；无则为空串） */
    public static String failedRules(List<Check> checks, QualityRuleCatalog.FrozenRules rules) {
        return checks.stream()
                .filter(c -> RuleSeverity.blocks(rules, c.ruleCode(), RuleSeverity.failedFlag(c.passed())))
                .map(Check::ruleCode).reduce((a, b) -> a + "," + b).orElse("");
    }

    /** @deprecated 过渡兼容：请传该 run 的冻结规则集，见 {@link #failedRules(List, QualityRuleCatalog.FrozenRules)}。 */
    @Deprecated
    public static String failedRules(List<Check> checks) {
        return failedRules(checks, QualityRuleCatalog.DEFAULT.freeze(null));
    }
}
