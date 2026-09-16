package com.graduation.analytics.metric.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.metric.MetricAdsCatalog;
import com.graduation.analytics.metric.MetricAdsWriter;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.metric.publish.MetricPublisherPort.Check;
import com.graduation.analytics.metric.publish.MetricPublisherPort.DefinitionRef;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishReport;
import com.graduation.analytics.metric.publish.MetricPublisherPort.PublishRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R7-3（V2.0 §17.4/§17.5）：指标快照发布器 —— 快照发布的**唯一编排者**。
 *
 * <p>职责边界（严格照 §17.4）：</p>
 * <ul>
 *   <li>读 Hive 正式 ADS 的结果（由 Spark {@code mxp} 作业导出为文件），**不计算任何业务指标**；</li>
 *   <li>把 ADS 行批量写入 analytics_metric 宽表（{@link MetricAdsWriter}）；</li>
 *   <li>把 ADS 已算好的核心指标映射进 metric_value（指标码只允许来自 metric_definition）；</li>
 *   <li>对账（{@link MetricPublishValidator}）：清单/行数/字典版本/ADS 与指标值一致；</li>
 *   <li>激活：{@link MetricStore#publish} 在同一事务内写指标值 + 旧 ACTIVE→ARCHIVED + 新快照→ACTIVE；</li>
 *   <li>失败：标 FAILED、清掉本次已写 ADS 行，**旧 ACTIVE 指针保持不变**（看板继续读上一版）。</li>
 * </ul>
 *
 * <p>已退役的旧路径：{@code AdsMaterializer}（应用侧"现算指标"）与"表不存在即吞错"的兼容分支，
 * 见 R7-1/R7-2（V2.0 §17.6）；本类不保留任何回退分支。</p>
 */
@Slf4j
@Service
public class MetricPublisher implements MetricPublisherPort {

    /** 概览表列 → 指标码（发布器只做搬运映射，公式在 Spark 侧口径 SQL 里） */
    private static final Map<String, String> OVERVIEW_TO_METRIC = new LinkedHashMap<>();

    static {
        OVERVIEW_TO_METRIC.put("pv", "pv");
        OVERVIEW_TO_METRIC.put("uv", "uv");
        OVERVIEW_TO_METRIC.put("dau", "dau");
        OVERVIEW_TO_METRIC.put("order_count", "paid_order_cnt");
        OVERVIEW_TO_METRIC.put("sale_amount", "gmv");
        OVERVIEW_TO_METRIC.put("net_sale_amount", "net_sale");
        OVERVIEW_TO_METRIC.put("avg_order_value", "avg_order_value");
        OVERVIEW_TO_METRIC.put("refund_rate", "refund_rate");
        OVERVIEW_TO_METRIC.put("full_refund_rate", "full_refund_rate");
        // S3-03：复购率（观察期窗口型指标，period 声明见 periodOf）
        OVERVIEW_TO_METRIC.put("repeat_rate", "repeat_rate");
    }

    /** 观察期窗口型指标码：其 `metric_value.period` 必须声明 `window:<起>..<止>`，不得谎报单日 */
    private static final String WINDOWED_REPEAT_RATE = "repeat_rate";

    /** ADS 概览行里声明观察期的列（由 Spark 侧从 DWS 行的 period_start/period_end 展开） */
    private static final String PERIOD_START_COLUMN = "repeat_period_start";
    private static final String PERIOD_END_COLUMN = "repeat_period_end";

    private final MetricPublishRepository repository;
    private final MetricAdsWriter adsWriter;
    private final AdsExportReader exportReader;
    private final MetricStore metricStore;
    private final MetricPublishValidator validator;
    private final ObjectMapper objectMapper;

    /** 清单文件名（与 Spark 侧 MetricAdsSpec.EXPORT_MANIFEST 一致） */
    static final String MANIFEST_FILE = "_export.json";

    public MetricPublisher(MetricPublishRepository repository, MetricAdsWriter adsWriter,
                           AdsExportReader exportReader, MetricStore metricStore,
                           MetricPublishValidator validator, ObjectMapper objectMapper) {
        this.repository = repository;
        this.adsWriter = adsWriter;
        this.exportReader = exportReader;
        this.metricStore = metricStore;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    public PublishReport publish(PublishRequest request) {
        long start = System.currentTimeMillis();
        List<Check> checks = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("snapshotId", request.snapshotId());
        evidence.put("businessDate", request.businessDate());
        evidence.put("exportDir", String.valueOf(request.exportDir()));

        if (request.snapshotId() == null || request.snapshotId().isBlank()
                || request.exportDir() == null || request.businessDate() == null) {
            return fail(request, checks, evidence, start, "MP_REQUEST_INVALID",
                    "发布请求缺少 snapshotId/exportDir/businessDate");
        }

        // ── 0. 读清单（读不到 = 没有发布依据，直接失败，不写任何行） ──
        MetricExportManifest manifest;
        try {
            manifest = MetricExportManifest.read(request.exportDir().resolve(MANIFEST_FILE), objectMapper);
        } catch (IOException e) {
            return fail(request, checks, evidence, start, "MP_MANIFEST_READ", e.getMessage());
        }
        checks.addAll(validator.manifestChecks(request, manifest));
        if (MetricPublishValidator.blocked(checks)) {
            return fail(request, checks, evidence, start, "MP_MANIFEST_INVALID",
                    "发布清单校验失败: " + MetricPublishValidator.failedRules(checks));
        }
        evidence.put("manifestTotalRows", manifest.totalRows());
        evidence.put("manifestSource", manifest.source());

        String previousActive = repository.activeSnapshotId(request.runtimeProfileId());
        evidence.put("previousActiveSnapshotId", previousActive);

        // ── 1. 登记 BUILDING（幂等：重试同快照不产生第二行） ──
        try {
            repository.createBuilding(request.runtimeProfileId(), request.runtimeProfileVersion(),
                    request.snapshotId(), request.businessDate(), request.businessTime(), request.pipelineRunId(),
                    definitionVersionOf(request, "refund_rate"));
        } catch (Exception e) {
            return fail(request, checks, evidence, start, "MP_SNAPSHOT_REGISTER", e.getMessage());
        }

        // ── 2. 幂等清理 + 批量写 8 张 ADS 宽表 ──
        Map<String, List<Map<String, Object>>> rowsByTable = new LinkedHashMap<>();
        Map<String, Integer> writtenCounts = new LinkedHashMap<>();
        try {
            int cleaned = adsWriter.deleteSnapshot(request.snapshotId());
            if (cleaned > 0) {
                log.info("metric publish: 快照 {} 重试，先清理已写 ADS 行 {} 条", request.snapshotId(), cleaned);
            }
            for (MetricAdsCatalog spec : MetricAdsCatalog.ALL) {
                MetricExportManifest.TableExport t = manifest.table(spec.name());
                List<Map<String, Object>> rows =
                        exportReader.readTable(Path.of(t.exportFile()), spec.name(), t.columns());
                rowsByTable.put(spec.name(), rows);
                writtenCounts.put(spec.name(), adsWriter.insertRows(spec.name(), request.snapshotId(),
                        request.businessDate(), rows));
            }
        } catch (Exception e) {
            log.error("metric publish: 快照 {} 写 ADS 宽表失败", request.snapshotId(), e);
            markFailed(request, "MP_ADS_WRITE_FAILED: " + e.getMessage());
            // 写入中途失败会留下"半张表"的行（如第 7 张表炸在第 3 行）：
            // ACTIVE 指针未切换，这些行没有任何读者，必须清掉，否则下次同快照重试会撞唯一键。
            compensate(request, evidence);
            return fail(request, checks, evidence, start, "MP_ADS_WRITE", e.getMessage());
        }
        long adsRows = writtenCounts.values().stream().mapToLong(Integer::longValue).sum();
        evidence.put("adsRows", adsRows);
        evidence.put("adsRowsByTable", writtenCounts);

        // ── 3. 核心指标值（只映射 ADS 结果）+ 写库前对账 ──
        List<MetricValue> values;
        try {
            values = buildCoreMetricValues(request, rowsByTable);
        } catch (Exception e) {
            markFailed(request, "MP_VALUE_BUILD_FAILED: " + e.getMessage());
            return fail(request, checks, evidence, start, "MP_VALUE_BUILD", e.getMessage());
        }
        evidence.put("metricCodes", values.stream().map(MetricValue::getMetricCode).toList());

        checks.addAll(validator.prePublishChecks(request, manifest, rowsByTable, writtenCounts, values));
        if (MetricPublishValidator.blocked(checks)) {
            markFailed(request, "MP_VERIFY_FAILED: " + MetricPublishValidator.failedRules(checks));
            compensate(request, evidence);
            return fail(request, checks, evidence, start, "MP_VERIFY_FAILED",
                    "写库前对账未通过: " + MetricPublishValidator.failedRules(checks));
        }

        // ── 4. VERIFYING → 同一事务写指标值 + 归档旧 ACTIVE + 激活本次快照 ──
        repository.markStatus(request.snapshotId(), MetricSnapshot.STATUS_VERIFYING, null);
        int switched;
        try {
            switched = metricStore.publish(
                    new MetricStore.SnapshotRef(request.snapshotId(), request.runtimeProfileId(),
                            "day:" + isoDate(request.businessDate())), values);
        } catch (Exception e) {
            log.error("metric publish: 快照 {} 指标值写入/激活失败", request.snapshotId(), e);
            markFailed(request, "MP_ACTIVATE_FAILED: " + e.getMessage());
            compensate(request, evidence);
            return fail(request, checks, evidence, start, "MP_ACTIVATE", e.getMessage());
        }
        if (switched == 0) {
            markFailed(request, "MP_ACTIVATE_NOOP: 快照未处于 VERIFYING");
            return fail(request, checks, evidence, start, "MP_ACTIVATE_NOOP",
                    "指标值已写入但 ACTIVE 指针未切换（快照非 VERIFYING）");
        }
        evidence.put("metricValues", values.size());

        // ── 5. 激活后实读对账（用看板同源只读账号） ──
        Map<String, Long> dbCounts = new LinkedHashMap<>();
        manifest.tables().forEach(t -> dbCounts.put(t.mysqlTable(),
                repository.countAdsRows(t.mysqlTable(), request.snapshotId())));
        long dbValueCount = repository.countMetricValues(request.snapshotId());
        Map<String, BigDecimal> dbValues = repository.metricValues(request.snapshotId());
        String active = repository.activeSnapshotId(request.runtimeProfileId());

        checks.addAll(validator.postActivationChecks(request, manifest, dbCounts, dbValueCount, dbValues, values,
                active, previousActive));
        boolean ok = !MetricPublishValidator.blocked(checks);
        if (!ok) {
            // 指针已切换却对账失败：属于真实不一致，必须留 FAILED 证据（不静默"看起来成功"）
            markFailed(request, "MP_POST_VERIFY_FAILED: " + MetricPublishValidator.failedRules(checks));
            return new PublishReport(false, "MP_POST_VERIFY_FAILED",
                    "激活后对账失败: " + MetricPublishValidator.failedRules(checks),
                    request.snapshotId(), adsRows, values.size(), List.copyOf(checks),
                    immutable(evidence));
        }

        evidence.put("activeSnapshotId", active);
        evidence.put("elapsedMs", System.currentTimeMillis() - start);
        log.info("metric publish: 快照 {} 发布成功（ADS {} 行，指标值 {} 条，旧 ACTIVE={}）",
                request.snapshotId(), adsRows, values.size(), previousActive);
        return new PublishReport(true, null, "发布成功", request.snapshotId(), adsRows, values.size(),
                List.copyOf(checks), immutable(evidence));
    }

    /**
     * 从 ADS 概览/漏斗行映射核心指标值（**不重算业务指标**）。
     * {@code buy_rate} 取漏斗行的 overall_buy_rate、{@code cart_rate} 取 overall_cart_rate
     * （两者都是整体率，四行同值，故「取首个非空行」与行序无关；与 ADS 同值）。
     */
    List<MetricValue> buildCoreMetricValues(PublishRequest request,
                                           Map<String, List<Map<String, Object>>> rowsByTable) {
        List<Map<String, Object>> overview = rowsByTable.getOrDefault("ads_operation_overview_m", List.of());
        if (overview.isEmpty()) {
            throw new IllegalStateException("概览表无数据，无法映射核心指标值");
        }
        Map<String, Object> row = overview.get(0);
        List<MetricValue> values = new ArrayList<>();
        for (Map.Entry<String, String> e : OVERVIEW_TO_METRIC.entrySet()) {
            Object raw = row.get(e.getKey());
            if (raw == null) {
                // 空值不允许写进 metric_value（列 NOT NULL）：宁缺勿造
                log.warn("metric publish: 概览列 {} 为空，跳过指标 {}", e.getKey(), e.getValue());
                continue;
            }
            values.add(valueOf(request, e.getValue(), new BigDecimal(String.valueOf(raw)),
                    periodOf(request, e.getValue(), row)));
        }

        // 漏斗整体率：buy_rate ← overall_buy_rate、cart_rate ← overall_cart_rate。
        // 两列都在四行上重复携带（行序无关）；某一列为空（如当日浏览用户为 0）时各自独立跳过，
        // 不让一个空值带走另一个指标。
        List<Map<String, Object>> funnel = rowsByTable.getOrDefault("ads_behavior_funnel_m", List.of());
        Object buyRate = null;
        Object cartRate = null;
        for (Map<String, Object> funnelRow : funnel) {
            if (buyRate == null) {
                buyRate = funnelRow.get("overall_buy_rate");
            }
            if (cartRate == null) {
                cartRate = funnelRow.get("overall_cart_rate");
            }
            if (buyRate != null && cartRate != null) {
                break;
            }
        }
        if (buyRate != null) {
            values.add(valueOf(request, "buy_rate", new BigDecimal(String.valueOf(buyRate))));
        }
        if (cartRate != null) {
            values.add(valueOf(request, "cart_rate", new BigDecimal(String.valueOf(cartRate))));
        }
        return values;
    }

    private MetricValue valueOf(PublishRequest request, String metricCode, BigDecimal value) {
        return valueOf(request, metricCode, value, "day:" + isoDate(request.businessDate()));
    }

    private MetricValue valueOf(PublishRequest request, String metricCode, BigDecimal value, String period) {
        RefHolder ref = new RefHolder(request.definitionVersions().get(metricCode));
        MetricValue v = new MetricValue();
        v.setSnapshotId(request.snapshotId());
        v.setMetricCode(metricCode);
        v.setMetricValue(value);
        v.setUnit(ref.unit());
        v.setPeriod(period);
        v.setDefinitionVersion(ref.version());
        return v;
    }

    /**
     * 指标值的观察期声明（设计 §11.2 L433「必须声明观察期和变体」）。
     *
     * <p>单日指标 = {@code day:<ISO 业务日>}；复购率这类**窗口指标**必须写
     * {@code window:<ISO 起>..<ISO 止>}，窗口取自 ADS 行里由上游 DWS 行自己声明的观察期
     * （唯一所有者 = Spark 侧作业入参），发布器不自行推断、也不拿业务日冒充窗口。</p>
     *
     * <p>窗口声明缺失（老快照镜像行没有该列 / 值为空）时退回 {@code day:} 并告警 —— 失败保旧、
     * 宁缺勿造：宁可声明得保守，也不编造一个没参与计算的窗口。</p>
     */
    private String periodOf(PublishRequest request, String metricCode, Map<String, Object> row) {
        if (!WINDOWED_REPEAT_RATE.equals(metricCode)) {
            return "day:" + isoDate(request.businessDate());
        }
        String start = isoDay(row.get(PERIOD_START_COLUMN));
        String end = isoDay(row.get(PERIOD_END_COLUMN));
        if (start.isEmpty() || end.isEmpty()) {
            log.warn("metric publish: 指标 {} 的观察期声明缺失（{}={}, {}={}），period 退回 day:{}",
                    metricCode, PERIOD_START_COLUMN, row.get(PERIOD_START_COLUMN),
                    PERIOD_END_COLUMN, row.get(PERIOD_END_COLUMN), isoDate(request.businessDate()));
            return "day:" + isoDate(request.businessDate());
        }
        return "window:" + start + ".." + end;
    }

    /** 观察期声明列的 ISO 化（20260831 与 2026-08-31 都归一成后者；空值 → 空串） */
    private static String isoDay(Object raw) {
        return raw == null ? "" : isoDate(String.valueOf(raw).trim());
    }

    /** 字典单位/版本的取值包装（字典缺该码时留空，由校验阶段以 BLOCKING 拦下） */
    private record RefHolder(String version, String unit) {
        RefHolder(DefinitionRef ref) {
            this(ref == null || ref.definitionVersion() == null ? "" : ref.definitionVersion(),
                    ref == null || ref.unit() == null ? "" : ref.unit());
        }
    }

    private String definitionVersionOf(PublishRequest request, String metricCode) {
        DefinitionRef ref = request.definitionVersions().get(metricCode);
        return ref == null || ref.definitionVersion() == null ? "" : ref.definitionVersion();
    }

    /** 20260901 → 2026-09-01（metric_value.period 形如 day:2026-09-01） */
    static String isoDate(String businessDate) {
        if (businessDate == null) {
            return "";
        }
        String digits = businessDate.replace("-", "");
        if (digits.length() != 8) {
            return businessDate;
        }
        return digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8);
    }

    private void markFailed(PublishRequest request, String reason) {
        try {
            repository.markStatus(request.snapshotId(), MetricSnapshot.STATUS_FAILED, truncate(reason));
        } catch (Exception e) {
            log.error("metric publish: 标记 FAILED 失败（快照 {}）", request.snapshotId(), e);
        }
    }

    /** 失败补偿：清掉本次写入的 ADS 行（ACTIVE 指针未切换，旧快照数据不受影响） */
    private void compensate(PublishRequest request, Map<String, Object> evidence) {
        try {
            int deleted = adsWriter.deleteSnapshot(request.snapshotId());
            evidence.put("compensatedAdsRows", deleted);
        } catch (Exception e) {
            log.error("metric publish: 失败补偿清理 ADS 行失败（快照 {}）", request.snapshotId(), e);
            evidence.put("compensateError", e.getMessage());
        }
    }

    private PublishReport fail(PublishRequest request, List<Check> checks, Map<String, Object> evidence,
                               long start, String errorCode, String message) {
        evidence.put("errorCode", errorCode);
        evidence.put("elapsedMs", System.currentTimeMillis() - start);
        log.warn("metric publish 失败[{}] 快照 {}：{}", errorCode, request.snapshotId(), message);
        return new PublishReport(false, errorCode, message, request.snapshotId(),
                longOf(evidence.get("adsRows")), intOf(evidence.get("metricValues")),
                List.copyOf(checks), immutable(evidence));
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    /** 证据 Map 允许 null 值（例如"没有上一版 ACTIVE"），因此不能直接用 Map.copyOf */
    private static Map<String, Object> immutable(Map<String, Object> evidence) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}
