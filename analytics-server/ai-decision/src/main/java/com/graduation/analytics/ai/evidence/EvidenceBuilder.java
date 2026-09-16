package com.graduation.analytics.ai.evidence;

import com.graduation.analytics.analysis.AnalysisService;
import com.graduation.analytics.analysis.AnalysisViewModel;
import com.graduation.analytics.ai.evidence.EvidencePackage.Period;
import com.graduation.analytics.metric.MySqlMetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.warehouse.WarehouseNamespace;
import com.graduation.analytics.warehouse.WarehouseNamespaceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 证据包构建器（§19.2、§24.7）。
 *
 * <p>取数**全部复用 metric-analysis 的只读所有者** {@link AnalysisService}（快照钉住、质量门、
 * ADS 读取都在那里），本类只做「装载 + 对齐 + 打证据引用」，因此不会出现第二套取数口径。</p>
 *
 * <p>反熵要点：</p>
 * <ul>
 *   <li>不查落地区事件明细、不查中间表：只读已发布快照与 ADS（§17.1）；</li>
 *   <li>上期对比**只认「业务日 = 本期起始日-1」的已发布快照**；找不到就留空 + 警告，
 *       既不用 0 冒充基线，也不拿同一快照自我对比（那是假的环比）；</li>
 *   <li>ADS 读取失败 → 降级为空维度 + 警告，不抛异常打断整个证据包（facts 仍可用）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceBuilder {

    /** 维度贡献取前 N（与 /analysis/products 的 topN 语义一致） */
    private static final int DIMENSION_TOP_N = 10;
    /** 找上期快照时回看的快照条数 */
    private static final int SNAPSHOT_LOOKBACK = 20;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AnalysisService analysisService;
    private final MySqlMetricStore metricStore;
    /** 数仓库名空间（唯一所有者）：血缘里的 Hive 库名不写死，随 ACTIVE 档案的 hive_database_prefix 走 */
    private final WarehouseNamespaceProvider namespaceProvider;

    public EvidencePackage build(EvidenceRequest req) {
        String requestedSnapshot = req == null ? null : trim(req.snapshotId());
        boolean explicit = requestedSnapshot != null;
        String snapshotId = explicit ? requestedSnapshot : metricStore.activeSnapshotId();
        if (snapshotId == null) {
            return empty(List.of(EvidencePackage.WARN_NO_ACTIVE_SNAPSHOT));
        }
        MetricSnapshot meta = metricStore.findSnapshot(snapshotId);
        if (meta == null) {
            return empty(List.of(explicit ? EvidencePackage.WARN_UNKNOWN_SNAPSHOT
                    : EvidencePackage.WARN_NO_ACTIVE_SNAPSHOT));
        }

        List<String> warnings = new ArrayList<>();
        LocalDate businessDate = meta.getBusinessTime() == null
                ? null : meta.getBusinessTime().toLocalDate();
        Period currentPeriod = businessDate == null ? null : new Period(iso(businessDate), iso(businessDate));
        Period requestedPeriod = resolveRequestedPeriod(req == null ? null : req.timeRange(), businessDate, warnings);
        Period period = requestedPeriod == null ? currentPeriod : requestedPeriod;
        if (period != null && currentPeriod != null && !period.equals(currentPeriod)) {
            // 快照指标值是按快照业务日聚合的：请求了别的日期也必须说清楚
            warnings.add(EvidencePackage.WARN_REQUESTED_PERIOD_NOT_SNAPSHOT_DATE);
        }
        if (requestedPeriod != null && requestedPeriod.from() != null
                && !requestedPeriod.from().equals(requestedPeriod.to())) {
            // 请求的是多日窗口：metric_value 只有「快照 × 单日」粒度，跨天窗口无法给出等长对比，
            // 这里如实登记不支持，而不是把某一天的值当成多日窗口的值报出去。
            warnings.add(EvidencePackage.WARN_COMPARISON_WINDOW_UNSUPPORTED);
        }
        LocalDate from = businessDate;
        LocalDate to = businessDate;

        AnalysisViewModel<AnalysisService.OverviewData> overview =
                analysisService.overview(snapshotId, from, to);
        if (overview.snapshotId() == null) {
            return empty(merge(overview.warnings(), warnings));
        }
        AnalysisService.OverviewData data = overview.data();
        warnings.addAll(overview.warnings());

        List<EvidencePackage.Fact> facts = facts(snapshotId, data.metrics(), warnings);
        Map<String, BigDecimal> values = values(data.metrics());

        ComparisonResult cmp = compare(snapshotId, from, data.metrics(), warnings);
        Map<String, List<EvidencePackage.DimensionContribution>> dimensions =
                dimensions(snapshotId, from, to, warnings);

        EvidencePackage.DataQuality quality = quality(overview, warnings);
        List<EvidencePackage.AnomalyCandidate> anomalies =
                AnomalyRules.evaluate(values, cmp.deltaRates(), quality, snapshotId);

        EvidencePackage.Lineage lineage = lineage(snapshotId, meta, facts, dimensions);
        return new EvidencePackage(evidenceId(), EvidencePackage.TEMPLATE_VERSION, snapshotId,
                blankToNull(meta.getDefinitionVersion()), LocalDateTime.now().format(ISO_SECONDS),
                period, currentPeriod, cmp.period(), facts, cmp.comparisons(), dimensions, anomalies,
                quality, lineage, List.copyOf(new LinkedHashSet<>(warnings)));
    }

    // ── 事实 ───────────────────────────────────────────────────────────────

    private List<EvidencePackage.Fact> facts(String snapshotId, List<AnalysisService.MetricItem> metrics,
                                             List<String> warnings) {
        List<EvidencePackage.Fact> out = new ArrayList<>();
        Set<String> definitionVersions = new LinkedHashSet<>();
        for (AnalysisService.MetricItem m : metrics == null ? List.<AnalysisService.MetricItem>of() : metrics) {
            out.add(new EvidencePackage.Fact(m.metricCode(), m.metricName(), plain(m.value()),
                    blankToEmpty(m.unit()), m.period(), "metric_value." + m.metricCode() + "@" + snapshotId));
            if (m.definitionVersion() != null && !m.definitionVersion().isBlank()) {
                definitionVersions.add(m.definitionVersion());
            }
        }
        if (definitionVersions.size() > 1) {
            warnings.add(EvidencePackage.WARN_MIXED_DEFINITION_VERSIONS);
        }
        return out;
    }

    private Map<String, BigDecimal> values(List<AnalysisService.MetricItem> metrics) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (AnalysisService.MetricItem m : metrics == null ? List.<AnalysisService.MetricItem>of() : metrics) {
            if (m.value() != null) {
                out.put(m.metricCode(), m.value());
            }
        }
        return out;
    }

    // ── 上期对比 ───────────────────────────────────────────────────────────

    private record ComparisonResult(Period period, List<EvidencePackage.Comparison> comparisons,
                                    Map<String, BigDecimal> deltaRates) {
    }

    /**
     * 上期对比：**只支持单日**。当前实现的诚实边界是 {@code metric_value} 只提供「快照 × 单日聚合」，
     * 所以本方法签名只接受一个业务日（不是区间）——跨天请求在 {@link #build} 里就登记为不支持，
     * 从结构上避免"拿某一天的值冒充多日窗口"。
     */
    private ComparisonResult compare(String snapshotId, LocalDate day,
                                     List<AnalysisService.MetricItem> metrics, List<String> warnings) {
        Map<String, BigDecimal> deltaRates = new LinkedHashMap<>();
        if (day == null) {
            warnings.add(EvidencePackage.WARN_NO_COMPARISON_PERIOD);
            return new ComparisonResult(null, List.of(), deltaRates);
        }
        LocalDate prevDate = day.minusDays(1);
        String baselineSnapshot = previousDaySnapshot(prevDate);
        if (baselineSnapshot == null) {
            warnings.add(EvidencePackage.WARN_NO_COMPARISON_PERIOD);
            return new ComparisonResult(null, List.of(), deltaRates);
        }
        AnalysisViewModel<AnalysisService.OverviewData> prev =
                analysisService.overview(baselineSnapshot, prevDate, prevDate);
        if (prev.snapshotId() == null || prev.data() == null) {
            warnings.add(EvidencePackage.WARN_NO_COMPARISON_PERIOD);
            return new ComparisonResult(null, List.of(), deltaRates);
        }
        Map<String, BigDecimal> baseline = values(prev.data().metrics());
        Map<String, String> baselineNames = new LinkedHashMap<>();
        prev.data().metrics().forEach(m -> baselineNames.put(m.metricCode(), m.metricName()));

        List<EvidencePackage.Comparison> comparisons = new ArrayList<>();
        for (AnalysisService.MetricItem m : metrics == null ? List.<AnalysisService.MetricItem>of() : metrics) {
            BigDecimal current = m.value();
            BigDecimal base = baseline.get(m.metricCode());
            BigDecimal delta = current == null || base == null ? null : current.subtract(base);
            BigDecimal rate = null;
            if (delta != null && base.signum() != 0) {
                rate = delta.divide(base.abs(), 4, RoundingMode.HALF_UP);
                deltaRates.put(m.metricCode(), rate);
            }
            comparisons.add(new EvidencePackage.Comparison(m.metricCode(), m.metricName(),
                    plain(current), plain(base), plain(delta), plain(rate),
                    "metric_value." + m.metricCode() + "@" + snapshotId,
                    "metric_value." + m.metricCode() + "@" + baselineSnapshot));
        }
        return new ComparisonResult(new Period(iso(prevDate), iso(prevDate)), comparisons, deltaRates);
    }

    /** 业务日 = 指定日期的已发布快照（优先 ACTIVE，其次 ARCHIVED；找不到返回 null） */
    private String previousDaySnapshot(LocalDate date) {
        List<MetricSnapshot> snapshots = metricStore.listSnapshots(SNAPSHOT_LOOKBACK);
        if (snapshots == null) {
            return null;
        }
        String archived = null;
        for (MetricSnapshot s : snapshots) {
            if (s.getBusinessTime() == null || !date.equals(s.getBusinessTime().toLocalDate())) {
                continue;
            }
            if (MetricSnapshot.STATUS_ACTIVE.equals(s.getStatus())) {
                return s.getSnapshotId();
            }
            if (MetricSnapshot.STATUS_ARCHIVED.equals(s.getStatus()) && archived == null) {
                archived = s.getSnapshotId();
            }
        }
        return archived;
    }

    // ── 维度贡献 ───────────────────────────────────────────────────────────

    private Map<String, List<EvidencePackage.DimensionContribution>> dimensions(
            String snapshotId, LocalDate from, LocalDate to, List<String> warnings) {
        Map<String, List<EvidencePackage.DimensionContribution>> out = new LinkedHashMap<>();
        out.put("product", productDimension(snapshotId, from, to, warnings));
        out.put("category", rowDimension("category", snapshotId, from, to, warnings));
        out.put("region", rowDimension("region", snapshotId, from, to, warnings));
        // 渠道维度本期没有 Hive 来源表（§24.4 只对有来源的表建服务表）
        out.put("channel", List.of());
        warnings.add(EvidencePackage.WARN_UNKNOWN_DIMENSION_TABLE);
        return out;
    }

    private List<EvidencePackage.DimensionContribution> productDimension(
            String snapshotId, LocalDate from, LocalDate to, List<String> warnings) {
        List<AnalysisService.HotProduct> hot;
        try {
            // S3-18：分页参数按「旧 topN 口径」下传（page=null ⇒ 1、size=null ⇒ 由 topN 决定），行为不变
            hot = analysisService.products(snapshotId, null, null, DIMENSION_TOP_N, from, to).data().hot();
        } catch (RuntimeException e) {
            log.warn("证据包商品维度读取失败（降级为空维度）: {}", e.getMessage());
            warnings.add(EvidencePackage.WARN_ADS_READ_UNAVAILABLE);
            return List.of();
        }
        if (hot == null || hot.isEmpty()) {
            return List.of();
        }
        BigDecimal total = hot.stream().map(AnalysisService.HotProduct::heat)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<EvidencePackage.DimensionContribution> out = new ArrayList<>();
        for (AnalysisService.HotProduct p : hot) {
            out.add(new EvidencePackage.DimensionContribution(
                    String.valueOf(p.productId()), blankToDash(p.productName()), plain(p.heat()),
                    share(p.heat(), total), "product_heat",
                    "ads_hot_product_m.heat_score@" + snapshotId));
        }
        return out;
    }

    private List<EvidencePackage.DimensionContribution> rowDimension(
            String dimension, String snapshotId, LocalDate from, LocalDate to, List<String> warnings) {
        List<Map<String, Object>> rows;
        try {
            AnalysisService.SalesData sales = analysisService.sales(snapshotId, from, to).data();
            rows = "category".equals(dimension) ? sales.byCategory() : sales.byRegion();
        } catch (RuntimeException e) {
            log.warn("证据包 {} 维度读取失败（降级为空维度）: {}", dimension, e.getMessage());
            warnings.add(EvidencePackage.WARN_ADS_READ_UNAVAILABLE);
            return List.of();
        }
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<EvidencePackage.DimensionContribution> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = firstText(row, dimension + "_id", dimension, "key");
            String label = firstText(row, dimension + "_name", "name", "label");
            Map.Entry<String, BigDecimal> measure = firstNumber(row);
            if (key == null || measure == null) {
                warnings.add(EvidencePackage.WARN_ADS_READ_UNAVAILABLE);
                continue;
            }
            out.add(new EvidencePackage.DimensionContribution(key, blankToDash(label),
                    plain(measure.getValue()), null, measure.getKey(),
                    "ads_sale_trend_m." + measure.getKey() + "@" + snapshotId));
        }
        return out;
    }

    // ── 质量与血缘 ─────────────────────────────────────────────────────────

    private EvidencePackage.DataQuality quality(AnalysisViewModel<AnalysisService.OverviewData> overview,
                                                List<String> warnings) {
        AnalysisService.QualitySummary q = overview.data() == null ? null : overview.data().quality();
        if (q == null) {
            warnings.add(EvidencePackage.WARN_QUALITY_STATUS_UNAVAILABLE);
            return EvidencePackage.DataQuality.unknown(EvidencePackage.WARN_QUALITY_STATUS_UNAVAILABLE);
        }
        return new EvidencePackage.DataQuality(
                overview.qualityStatus() == null
                        ? EvidencePackage.DataQuality.GATE_UNKNOWN : overview.qualityStatus(),
                q.ruleCount(), q.passedCount(), q.failedRules(), List.of());
    }

    private EvidencePackage.Lineage lineage(String snapshotId, MetricSnapshot meta,
                                            List<EvidencePackage.Fact> facts,
                                            Map<String, List<EvidencePackage.DimensionContribution>> dimensions) {
        Set<String> mysql = new LinkedHashSet<>(MetricLineage.mysqlTables(
                facts.stream().map(EvidencePackage.Fact::metricCode).toList()));
        mysql.add(MetricLineage.dataQuality().mysqlTable());
        if (!dimensions.getOrDefault("product", List.of()).isEmpty()) {
            mysql.add(MetricLineage.hotProduct().mysqlTable());
        }
        if (!dimensions.getOrDefault("category", List.of()).isEmpty()
                || !dimensions.getOrDefault("region", List.of()).isEmpty()) {
            mysql.add(MetricLineage.saleTrend().mysqlTable());
        }
        return new EvidencePackage.Lineage(
                MetricLineage.hiveTables(mysql, namespaceProvider.current()), List.copyOf(mysql),
                meta == null ? null : meta.getPipelineRunId(), snapshotId);
    }

    // ── 空证据包 ───────────────────────────────────────────────────────────

    /** 无可用快照：事实/对比/维度/异常全空 + 警告，**不返回 null、不造数据** */
    private EvidencePackage empty(List<String> warnings) {
        Map<String, List<EvidencePackage.DimensionContribution>> dimensions = new LinkedHashMap<>();
        EvidencePackage.DIMENSION_KEYS.forEach(k -> dimensions.put(k, List.of()));
        return new EvidencePackage(evidenceId(), EvidencePackage.TEMPLATE_VERSION, null, null,
                LocalDateTime.now().format(ISO_SECONDS), null, null, null,
                List.of(), List.of(), dimensions, List.of(),
                EvidencePackage.DataQuality.unknown(EvidencePackage.WARN_QUALITY_STATUS_UNAVAILABLE),
                new EvidencePackage.Lineage(List.of(), List.of(), null, null),
                List.copyOf(new LinkedHashSet<>(warnings)));
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a == null ? List.of() : a);
        out.addAll(b == null ? List.of() : b);
        return out;
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    /** 请求期间：{@code yyyy-MM-dd} 或 {@code from~to}；解析不了就记警告并退回快照业务日 */
    private Period resolveRequestedPeriod(String timeRange, LocalDate businessDate, List<String> warnings) {
        if (timeRange == null || timeRange.isBlank()) {
            return businessDate == null ? null : new Period(iso(businessDate), iso(businessDate));
        }
        String text = timeRange.trim();
        try {
            if (text.contains("~")) {
                String[] parts = text.split("~", 2);
                return new Period(iso(LocalDate.parse(parts[0].trim())), iso(LocalDate.parse(parts[1].trim())));
            }
            LocalDate single = LocalDate.parse(text);
            return new Period(iso(single), iso(single));
        } catch (RuntimeException e) {
            warnings.add(EvidencePackage.WARN_TIME_RANGE_IGNORED);
            return businessDate == null ? null : new Period(iso(businessDate), iso(businessDate));
        }
    }

    private static String firstText(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object v = row.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /** 行里的第一个数值列（列名即度量码；取不到返回 null，不猜列） */
    private static Map.Entry<String, BigDecimal> firstNumber(Map<String, Object> row) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getValue() instanceof BigDecimal bd) {
                return Map.entry(e.getKey(), bd);
            }
            if (e.getValue() instanceof Number n) {
                return Map.entry(e.getKey(), new BigDecimal(n.toString()));
            }
        }
        return null;
    }

    private static String share(BigDecimal value, BigDecimal total) {
        if (value == null || total == null || total.signum() == 0) {
            return null;
        }
        return value.divide(total, 4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String evidenceId() {
        return "EV-" + LocalDate.now().format(STAMP) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
