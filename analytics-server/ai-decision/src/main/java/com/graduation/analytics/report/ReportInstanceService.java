package com.graduation.analytics.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graduation.analytics.analysis.AnalysisService;
import com.graduation.analytics.analysis.AnalysisViewModel;
import com.graduation.analytics.analysis.RfmService;
import com.graduation.analytics.ai.evidence.EvidencePackage;
import com.graduation.analytics.ai.evidence.EvidenceRequest;
import com.graduation.analytics.ai.evidence.EvidenceService;
import com.graduation.analytics.common.PaginationMath;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.entity.DecisionEvaluation;
import com.graduation.analytics.decision.entity.DecisionTask;
import com.graduation.analytics.decision.mapper.DecisionEvaluationMapper;
import com.graduation.analytics.decision.mapper.DecisionTaskMapper;
import com.graduation.analytics.report.entity.ReportInstance;
import com.graduation.analytics.report.mapper.ReportInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReportInstanceService {
    public static final String TEMPLATE_VERSION = "1";
    public static final List<String> TEMPLATE_CODES = List.of("OPS_DAILY", "OPS_WEEKLY", "ORDER_ANALYSIS",
            "PRODUCT_ANOMALY", "USER_OPERATIONS", "DECISION_EFFECT");
    private static final List<String> ORDER_METRIC_CODES = List.of("paid_order_cnt", "gmv", "net_sale",
            "avg_order_value", "refund_rate", "full_refund_rate");
    private static final List<String> USER_METRIC_CODES = List.of("uv", "dau", "repeat_rate");

    private final EvidenceService evidenceService;
    private final AnalysisService analysisService;
    private final ReportInstanceMapper mapper;
    private final DecisionTaskMapper decisionTaskMapper;
    private final DecisionEvaluationMapper decisionEvaluationMapper;
    private final ObjectMapper objectMapper;

    public record CreateCommand(String templateCode, String templateVersion, String snapshotId,
                                LocalDate businessDate, Boolean includeAiNarrative,
                                LocalDate fromDate, LocalDate toDate, Long decisionId) {
    }

    public record ReportArtifact(String templateCode, String templateVersion, EvidencePackage evidence,
                                 Object templateContent) {
    }

    public record OrderAnalysisContent(List<EvidencePackage.Fact> metrics, List<String> unavailableMetrics,
                                       List<EvidencePackage.AnomalyCandidate> relatedCandidates,
                                       EvidencePackage.DataQuality dataQuality, List<String> limitations) {
    }

    public record UserOperationsContent(String periodStart, String periodEnd, String ruleVersion,
                                        List<RfmService.RfmSegment> rfmSegments,
                                        List<RfmService.LifecycleState> lifecycle,
                                        List<RfmService.CategoryPreference> categoryPreference,
                                        List<EvidencePackage.Fact> aggregateMetrics,
                                        List<String> unavailableMetrics,
                                        EvidencePackage.DataQuality dataQuality, List<String> warnings,
                                        List<String> limitations) {
    }

    public record ProductSignal(long productId, String productName, int heatRank,
                                java.math.BigDecimal heat, long pv, long favorites, long cartAdds, long buyers,
                                Long productViewUsers, Long productBuyerUsers,
                                java.math.BigDecimal conversionRate, String heatEvidenceRef,
                                String conversionEvidenceRef) {
    }

    public record ProductAnomalyContent(long totalProducts, List<ProductSignal> topHeatProducts,
                                        List<EvidencePackage.AnomalyCandidate> snapshotMetricCandidates,
                                        EvidencePackage.DataQuality dataQuality, List<String> warnings,
                                        List<String> limitations) {
    }

    public record WeeklyDay(String date, Long orderCount, java.math.BigDecimal saleAmount,
                            java.math.BigDecimal netSaleAmount, Long dau, Long behaviorCount) {
    }

    public record WeeklyOperationsContent(String fromDate, String toDate, List<WeeklyDay> daily,
                                          List<String> missingSalesDates, List<String> missingBehaviorDates,
                                          Long totalOrders, java.math.BigDecimal totalSaleAmount,
                                          java.math.BigDecimal totalNetSaleAmount,
                                          java.math.BigDecimal averageOrderValue,
                                          Long totalBehaviorCount, EvidencePackage.DataQuality dataQuality,
                                          List<String> warnings, List<String> limitations) {
    }

    public record DecisionEffectContent(Long decisionId, String decisionNo, String title,
                                        String targetMetricCode, String targetDirection,
                                        String result, java.math.BigDecimal baselineValue,
                                        java.math.BigDecimal actualValue, java.math.BigDecimal improvementRate,
                                        LocalDate baselineWindowStart, LocalDate baselineWindowEnd,
                                        LocalDate actualWindowStart, LocalDate actualWindowEnd,
                                        Integer baselineSampleCount, Integer actualSampleCount,
                                        Integer expectedWindowDays, String metricDefinitionVersion,
                                        String evaluationDefinitionVersion, String evaluationNote,
                                        String baselineSnapshotRefs, String actualSnapshotRefs,
                                        List<String> limitations) {
    }

    public record Page(List<ReportInstance> items, long page, int size, long total, long totalPages, String sort) {
    }

    @Transactional
    public ReportInstance create(CreateCommand command, String creatorId) {
        if (command == null || command.templateCode() == null
                || (!"DECISION_EFFECT".equals(command.templateCode()) && command.businessDate() == null)) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "templateCode 与业务日期必填");
        }
        if (!TEMPLATE_CODES.contains(command.templateCode())) {
            throw new PlatformBizException("REPORT_TEMPLATE_INVALID", "templateCode 不受支持");
        }
        if (!Set.of("OPS_DAILY", "OPS_WEEKLY", "ORDER_ANALYSIS", "PRODUCT_ANOMALY", "USER_OPERATIONS", "DECISION_EFFECT")
                .contains(command.templateCode())) {
            throw new PlatformBizException("REPORT_TEMPLATE_NOT_READY", "该报告模板尚未具备完整证据实现");
        }
        if (command.templateVersion() != null && !TEMPLATE_VERSION.equals(command.templateVersion())) {
            throw new PlatformBizException("REPORT_TEMPLATE_VERSION_UNAVAILABLE", "templateVersion 不可用");
        }
        if ("DECISION_EFFECT".equals(command.templateCode())
                && (command.snapshotId() != null || command.businessDate() != null
                || command.fromDate() != null || command.toDate() != null)) {
            throw new PlatformBizException("REPORT_DECISION_INPUT_UNSUPPORTED",
                    "决策效果报告只接受 decisionId，不接受客户端快照或日期字段");
        }
        if (Boolean.TRUE.equals(command.includeAiNarrative())) {
            throw new PlatformBizException("REPORT_AI_D039_REQUIRED",
                    "AI 摘要需先满足 D-039 的 Provider 与数据外发条件");
        }

        DecisionContext decisionContext = "DECISION_EFFECT".equals(command.templateCode())
                ? resolveDecisionContext(command.decisionId()) : null;
        if (decisionContext == null && command.decisionId() != null) {
            throw new PlatformBizException("REPORT_DECISION_ID_UNSUPPORTED", "decisionId 仅适用于决策效果报告");
        }
        LocalDate reportFrom = decisionContext != null ? decisionContext.fromDate()
                : command.fromDate() == null ? command.businessDate() : command.fromDate();
        LocalDate reportTo = decisionContext != null ? decisionContext.toDate()
                : command.toDate() == null ? command.businessDate() : command.toDate();
        LocalDate evidenceDate = decisionContext != null ? decisionContext.evidenceDate() : command.businessDate();
        String evidenceSnapshotId = decisionContext != null ? decisionContext.snapshotId() : command.snapshotId();
        if (decisionContext == null && "OPS_WEEKLY".equals(command.templateCode())) {
            if (!reportFrom.plusDays(6).equals(reportTo) || !reportTo.equals(command.businessDate())) {
                throw new PlatformBizException("REPORT_WEEK_WINDOW_INVALID", "经营周报必须提供连续七日窗口且 toDate 等于 businessDate");
            }
        } else if (decisionContext == null && !reportFrom.equals(reportTo)) {
            throw new PlatformBizException("REPORT_WINDOW_UNSUPPORTED", "该报告模板只支持单个业务日窗口");
        }

        EvidencePackage evidence;
        try {
            evidence = evidenceService.build(new EvidenceRequest(evidenceSnapshotId, null,
                    evidenceDate.toString(), creatorId));
        } catch (PlatformBizException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw new PlatformBizException("REPORT_EVIDENCE_UNAVAILABLE", "无法读取报告所需的快照证据");
        }
        if (evidence == null || evidence.snapshotId() == null || evidence.lineage() == null
                || evidence.lineage().sourceId() == null || evidence.definitionVersion() == null
                || evidence.definitionVersion().isBlank()) {
            throw new PlatformBizException("REPORT_SNAPSHOT_UNAVAILABLE", "无法取得可发布快照的完整证据锚点");
        }
        if (evidenceSnapshotId != null && !Objects.equals(evidenceSnapshotId, evidence.snapshotId())) {
            throw new PlatformBizException("REPORT_SNAPSHOT_MISMATCH", "报告证据快照与请求锚点不一致");
        }
        EvidencePackage.Period current = evidence.currentPeriod();
        if (current == null || !evidenceDate.toString().equals(current.to())) {
            throw new PlatformBizException("REPORT_DATE_NOT_AVAILABLE", "业务日期与快照业务日不一致");
        }
        if (decisionContext != null && (!Objects.equals(decisionContext.task().getSourceId(), evidence.lineage().sourceId())
                || !Objects.equals(decisionContext.evaluation().getMetricDefinitionVersion(), evidence.definitionVersion()))) {
            throw new PlatformBizException("REPORT_DECISION_SCOPE_MISMATCH", "决策评价与证据快照的来源或指标口径不一致");
        }

        Object templateContent = switch (command.templateCode()) {
            case "DECISION_EFFECT" -> decisionEffectContent(decisionContext);
            case "OPS_WEEKLY" -> weeklyOperationsContent(evidence, reportFrom, reportTo);
            case "ORDER_ANALYSIS" -> orderAnalysisContent(evidence);
            case "PRODUCT_ANOMALY" -> productAnomalyContent(evidence, command.businessDate());
            case "USER_OPERATIONS" -> userOperationsContent(evidence, command.businessDate());
            default -> null;
        };
        String artifactJson = serialize(new ReportArtifact(command.templateCode(), TEMPLATE_VERSION,
                evidence, templateContent));
        ReportInstance report = new ReportInstance();
        report.setTemplateCode(command.templateCode());
        report.setTemplateVersion(TEMPLATE_VERSION);
        report.setSourceId(evidence.lineage().sourceId());
        report.setSnapshotId(evidence.snapshotId());
        report.setDefinitionVersion(evidence.definitionVersion());
        report.setFromDate(reportFrom);
        report.setToDate(reportTo);
        report.setPromptVersion(null);
        report.setCreatorId(creatorId);
        report.setStatus("READY");
        report.setArtifactJson(artifactJson);
        report.setArtifactChecksum(sha256(artifactJson));
        report.setCreatedAt(LocalDateTime.now());
        report.setUpdatedAt(report.getCreatedAt());
        mapper.insert(report);
        report.setArtifactUri("db://report-instance/" + report.getId());
        if (mapper.updateById(report) != 1) {
            throw new IllegalStateException("报告实例制品引用未能写入");
        }
        return report;
    }

    private DecisionContext resolveDecisionContext(Long decisionId) {
        if (decisionId == null) {
            throw new PlatformBizException("REPORT_DECISION_REQUIRED", "决策效果报告必须选择决策任务");
        }
        DecisionTask task = decisionTaskMapper.selectById(decisionId);
        if (task == null || task.getStatus() == null || task.getCompletedAt() == null
                || !Set.of("EFFECTIVE", "PARTIAL", "INEFFECTIVE", "INSUFFICIENT_DATA").contains(task.getStatus())) {
            throw new PlatformBizException("REPORT_DECISION_NOT_EVALUATED", "仅可为已完成且已评价的决策生成报告");
        }
        DecisionEvaluation evaluation = decisionEvaluationMapper.selectOne(new LambdaQueryWrapper<DecisionEvaluation>()
                .eq(DecisionEvaluation::getDecisionId, decisionId)
                .orderByDesc(DecisionEvaluation::getId).last("LIMIT 1"));
        if (evaluation == null || !Objects.equals(task.getStatus(), evaluation.getResult())
                || task.getSourceId() == null || !Objects.equals(task.getSourceId(), evaluation.getSourceId())
                || task.getRuntimeProfileId() == null
                || !Objects.equals(task.getRuntimeProfileId(), evaluation.getRuntimeProfileId())
                || task.getDefinitionVersion() == null
                || !Objects.equals(task.getDefinitionVersion(), evaluation.getMetricDefinitionVersion())
                || task.getEvalWindowDays() == null || evaluation.getEvalWindowDays() == null
                || !Objects.equals(task.getEvalWindowDays(), evaluation.getEvalWindowDays())
                || evaluation.getBaselineWindowStart() == null || evaluation.getBaselineWindowEnd() == null) {
            throw new PlatformBizException("REPORT_DECISION_EVIDENCE_UNAVAILABLE", "决策缺少一致且可追溯的已保存评价证据");
        }
        boolean insufficient = "INSUFFICIENT_DATA".equals(evaluation.getResult());
        String snapshotId = insufficient ? evaluation.getBaselineSnapshotId() : evaluation.getActualSnapshotId();
        LocalDate evidenceDate = insufficient ? evaluation.getBaselineWindowEnd() : evaluation.getWindowEnd();
        LocalDate reportEnd = evaluation.getWindowEnd();
        if (snapshotId == null || evidenceDate == null || evaluation.getWindowStart() == null
                || reportEnd == null
                || (!insufficient && (evaluation.getActualSampleCount() == null
                || !evaluation.getActualSampleCount().equals(evaluation.getEvalWindowDays())))) {
            throw new PlatformBizException("REPORT_DECISION_EVIDENCE_UNAVAILABLE", "决策评价缺少报告所需的快照或窗口边界");
        }
        return new DecisionContext(task, evaluation, snapshotId, evidenceDate,
                evaluation.getBaselineWindowStart(), reportEnd);
    }

    private DecisionEffectContent decisionEffectContent(DecisionContext context) {
        DecisionTask task = context.task();
        DecisionEvaluation evaluation = context.evaluation();
        boolean insufficient = "INSUFFICIENT_DATA".equals(evaluation.getResult());
        java.math.BigDecimal actualValue = insufficient ? null
                : evaluation.getActualValue() == null ? evaluation.getActualPeriodValue() : evaluation.getActualValue();
        return new DecisionEffectContent(task.getId(), task.getDecisionNo(), task.getTitle(),
                task.getTargetMetricCode(), task.getTargetDirection(), evaluation.getResult(),
                evaluation.getBaselineValue() == null ? evaluation.getBaselinePeriodValue() : evaluation.getBaselineValue(),
                actualValue,
                evaluation.getImprovementRate(),
                evaluation.getBaselineWindowStart(), evaluation.getBaselineWindowEnd(),
                evaluation.getWindowStart(), evaluation.getWindowEnd(), evaluation.getBaselineSampleCount(),
                evaluation.getActualSampleCount(), evaluation.getEvalWindowDays(),
                evaluation.getMetricDefinitionVersion(), evaluation.getDefinitionVersion(), evaluation.getNote(),
                evaluation.getBaselineSnapshotRefs(), evaluation.getActualSnapshotRefs(),
                List.of("只复述系统已保存的前后窗口评价，不重新计算分级。",
                        "前后变化不证明决策行动导致该变化。",
                        "评价结果保留其保存时的口径、阈值版本、窗口样本数与快照引用。"));
    }

    private WeeklyOperationsContent weeklyOperationsContent(EvidencePackage evidence,
                                                             LocalDate fromDate, LocalDate toDate) {
        AnalysisViewModel<AnalysisService.OverviewData> overview;
        try {
            overview = analysisService.overview(evidence.snapshotId(), fromDate, toDate);
        } catch (RuntimeException unavailable) {
            throw new PlatformBizException("REPORT_WEEK_EVIDENCE_UNAVAILABLE", "无法读取所选快照的周度销售与活跃趋势");
        }
        if (overview == null || !Objects.equals(evidence.snapshotId(), overview.snapshotId())
                || !Objects.equals(evidence.lineage().sourceId(), overview.sourceId()) || overview.data() == null) {
            throw new PlatformBizException("REPORT_WEEK_EVIDENCE_UNAVAILABLE", "无法在所选快照读取周度概览趋势");
        }
        AnalysisService.OverviewData data = overview.data();
        Map<String, AnalysisService.SalesTrendPoint> salesByDate = data.salesTrend() == null ? Map.of()
                : data.salesTrend().stream().filter(point -> point.date() != null)
                .collect(java.util.stream.Collectors.toMap(AnalysisService.SalesTrendPoint::date,
                        point -> point, (first, ignored) -> first));
        Map<String, AnalysisService.ActiveDay> activityByDate = data.activeTrend() == null ? Map.of()
                : data.activeTrend().stream().filter(day -> day.date() != null)
                .collect(java.util.stream.Collectors.toMap(AnalysisService.ActiveDay::date,
                        day -> day, (first, ignored) -> first));
        List<LocalDate> dates = fromDate.datesUntil(toDate.plusDays(1)).toList();
        List<WeeklyDay> daily = new ArrayList<>();
        List<String> missingSales = new ArrayList<>();
        List<String> missingBehavior = new ArrayList<>();
        long orders = 0;
        long behavior = 0;
        boolean completeOrderCounts = true;
        boolean completeBehaviorCounts = true;
        java.math.BigDecimal saleAmount = java.math.BigDecimal.ZERO;
        java.math.BigDecimal netSaleAmount = java.math.BigDecimal.ZERO;
        boolean completeSaleAmounts = true;
        boolean completeNetSaleAmounts = true;
        for (LocalDate date : dates) {
            String key = date.toString();
            AnalysisService.SalesTrendPoint sale = salesByDate.get(key);
            AnalysisService.ActiveDay activity = activityByDate.get(key);
            if (sale == null) missingSales.add(key);
            if (activity == null) missingBehavior.add(key);
            daily.add(new WeeklyDay(key, sale == null ? null : sale.orderCount(),
                    sale == null ? null : sale.saleAmount(), sale == null ? null : sale.netSaleAmount(),
                    activity == null ? null : activity.dau(), activity == null ? null : activity.behaviorCount()));
            if (sale == null) {
                completeOrderCounts = false;
            } else {
                orders = Math.addExact(orders, sale.orderCount());
            }
            if (sale == null || sale.saleAmount() == null) {
                completeSaleAmounts = false;
            } else {
                saleAmount = saleAmount.add(sale.saleAmount());
            }
            if (sale == null || sale.netSaleAmount() == null) {
                completeNetSaleAmounts = false;
            } else {
                netSaleAmount = netSaleAmount.add(sale.netSaleAmount());
            }
            if (activity == null) {
                completeBehaviorCounts = false;
            } else {
                behavior = Math.addExact(behavior, activity.behaviorCount());
            }
        }
        Long totalOrders = completeOrderCounts ? orders : null;
        Long totalBehavior = completeBehaviorCounts ? behavior : null;
        java.math.BigDecimal totalSales = completeSaleAmounts ? saleAmount : null;
        java.math.BigDecimal totalNetSales = completeNetSaleAmounts ? netSaleAmount : null;
        java.math.BigDecimal averageOrderValue = totalOrders != null && totalOrders > 0 && totalSales != null
                ? totalSales.divide(java.math.BigDecimal.valueOf(totalOrders), 2, java.math.RoundingMode.HALF_UP) : null;
        List<String> warnings = new ArrayList<>(evidence.warnings());
        warnings.addAll(overview.warnings());
        if (!missingSales.isEmpty()) warnings.add("WARN_WEEKLY_SALES_DAYS_MISSING");
        if (!missingBehavior.isEmpty()) warnings.add("WARN_WEEKLY_BEHAVIOR_DAYS_MISSING");
        if (!completeOrderCounts) warnings.add("WARN_WEEKLY_ORDER_COUNTS_INCOMPLETE");
        if (!completeSaleAmounts) warnings.add("WARN_WEEKLY_SALES_AMOUNTS_INCOMPLETE");
        if (!completeNetSaleAmounts) warnings.add("WARN_WEEKLY_NET_SALES_INCOMPLETE");
        if (!completeBehaviorCounts) warnings.add("WARN_WEEKLY_BEHAVIOR_COUNTS_INCOMPLETE");
        List<String> limitations = List.of(
                "数据来自同一个已发布快照；逐日缺失日期单独列示，不补零。",
                "周客单价由窗口销售额合计除以支付订单数重算；分母缺失或为零时留空。",
                "DAU 是逐日 distinct 用户数，只逐日展示，不跨日相加；周度 distinct 用户数未提供。",
                "净销售额不等于最终到账收入；行为量为已发布日级计数之和。"
        );
        return new WeeklyOperationsContent(fromDate.toString(), toDate.toString(), List.copyOf(daily),
                List.copyOf(missingSales), List.copyOf(missingBehavior), totalOrders,
                totalSales, totalNetSales, averageOrderValue, totalBehavior,
                evidence.dataQuality(), List.copyOf(new java.util.LinkedHashSet<>(warnings)), limitations);
    }

    private OrderAnalysisContent orderAnalysisContent(EvidencePackage evidence) {
        Map<String, EvidencePackage.Fact> byCode = evidence.facts().stream()
                .filter(fact -> fact.metricCode() != null)
                .collect(java.util.stream.Collectors.toMap(EvidencePackage.Fact::metricCode,
                        fact -> fact, (first, ignored) -> first));
        List<EvidencePackage.Fact> metrics = ORDER_METRIC_CODES.stream()
                .map(byCode::get).filter(java.util.Objects::nonNull).toList();
        List<String> unavailable = ORDER_METRIC_CODES.stream()
                .filter(code -> !byCode.containsKey(code)).toList();
        List<EvidencePackage.AnomalyCandidate> related = evidence.anomalies().stream()
                .filter(candidate -> candidate.metricCode() == null
                        || ORDER_METRIC_CODES.contains(candidate.metricCode()))
                .toList();
        List<String> limitations = new ArrayList<>();
        limitations.add("本报告只反映所选已发布快照内的订单指标，不包含用户级信息。");
        limitations.add("净销售额按已冻结指标定义和归属窗口解释，不代表最终到账收入。");
        limitations.add("未发布的指标列在 unavailableMetrics 中，不以 0 补齐；不从缺失值推断无退款或无订单。");
        return new OrderAnalysisContent(metrics, unavailable, related, evidence.dataQuality(),
                List.copyOf(limitations));
    }

    private UserOperationsContent userOperationsContent(EvidencePackage evidence, LocalDate businessDate) {
        AnalysisViewModel<AnalysisService.UsersData> view;
        try {
            view = analysisService.users(evidence.snapshotId(), businessDate, businessDate);
        } catch (RuntimeException unavailable) {
            throw new PlatformBizException("REPORT_USER_EVIDENCE_UNAVAILABLE", "无法读取所选快照的用户运营聚合证据");
        }
        if (view == null || !Objects.equals(evidence.snapshotId(), view.snapshotId())
                || !Objects.equals(evidence.lineage().sourceId(), view.sourceId()) || view.data() == null) {
            throw new PlatformBizException("REPORT_USER_EVIDENCE_UNAVAILABLE",
                    "无法在同一快照和来源中取得用户聚合证据");
        }
        Map<String, EvidencePackage.Fact> byCode = evidence.facts().stream()
                .filter(fact -> fact.metricCode() != null)
                .collect(java.util.stream.Collectors.toMap(EvidencePackage.Fact::metricCode,
                        fact -> fact, (first, ignored) -> first));
        List<EvidencePackage.Fact> aggregateMetrics = USER_METRIC_CODES.stream()
                .map(byCode::get).filter(Objects::nonNull).toList();
        List<String> unavailable = USER_METRIC_CODES.stream()
                .filter(code -> !byCode.containsKey(code)).toList();
        AnalysisService.UsersData data = view.data();
        List<String> warnings = java.util.stream.Stream.concat(evidence.warnings().stream(),
                        view.warnings().stream()).distinct().toList();
        List<String> limitations = List.of(
                "RFM、生命周期和类目偏好仅展示已发布的聚合分组，不包含用户标识或用户级行。",
                "UV、DAU 与复购率按快照已发布口径原样显示；不跨日累加 UV/DAU，也不重算比例。");
        return new UserOperationsContent(data.periodStart(), data.periodEnd(), data.ruleVersion(),
                data.rfmSegments(), data.lifecycle(), data.preference(), aggregateMetrics, unavailable,
                evidence.dataQuality(), warnings, limitations);
    }

    private ProductAnomalyContent productAnomalyContent(EvidencePackage evidence, LocalDate businessDate) {
        AnalysisViewModel<AnalysisService.ProductsData> view;
        try {
            view = analysisService.products(evidence.snapshotId(), 1, 10, "rank,asc", 10,
                    businessDate, businessDate);
        } catch (RuntimeException unavailable) {
            throw new PlatformBizException("REPORT_PRODUCT_EVIDENCE_UNAVAILABLE",
                    "无法读取所选快照的商品热度与转化证据");
        }
        if (view == null || !Objects.equals(evidence.snapshotId(), view.snapshotId())
                || !Objects.equals(evidence.lineage().sourceId(), view.sourceId()) || view.data() == null) {
            throw new PlatformBizException("REPORT_PRODUCT_EVIDENCE_UNAVAILABLE",
                    "商品证据未绑定到报告所选的快照和来源");
        }
        AnalysisService.ProductsData data = view.data();
        Map<Long, AnalysisService.ProductConversion> conversions = data.conversion() == null ? Map.of()
                : data.conversion().stream().collect(java.util.stream.Collectors.toMap(
                AnalysisService.ProductConversion::productId, conversion -> conversion,
                (first, ignored) -> first));
        List<ProductSignal> signals = (data.hot() == null ? List.<AnalysisService.HotProduct>of() : data.hot())
                .stream().map(hot -> {
                    AnalysisService.ProductConversion conversion = conversions.get(hot.productId());
                    return new ProductSignal(hot.productId(), hot.productName(), hot.rank(), hot.heat(), hot.pv(),
                            hot.fav(), hot.cart(), hot.buy(), conversion == null ? null : conversion.pvUsers(),
                            conversion == null ? null : conversion.buyUsers(),
                            conversion == null ? null : conversion.conversionRate(),
                            "ads_hot_product_m.heat_score@" + evidence.snapshotId(),
                            "ads_product_conversion_m@" + evidence.snapshotId());
                }).toList();
        List<String> warnings = java.util.stream.Stream.concat(evidence.warnings().stream(),
                        view.warnings().stream()).distinct().toList();
        List<String> limitations = List.of(
                "商品信号限所选快照热度榜前 10；rank 为已发布稳定排序。",
                "本模板不新造商品异常阈值；候选项只复述快照已有异常规则。",
                "证据不含库存和成本，不推断库存风险、利润或因果关系。");
        return new ProductAnomalyContent(data.total(), signals, evidence.anomalies(), evidence.dataQuality(),
                warnings, limitations);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(String ownerId, long page, int size, String sortField, boolean ascending) {
        if (page < 1 || size < 1 || size > 100) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "page 必须大于等于 1，size 必须在 1 到 100 之间");
        }
        var filter = new LambdaQueryWrapper<ReportInstance>().eq(ReportInstance::getCreatorId, ownerId);
        long total = mapper.selectCount(filter);
        long totalPages = PaginationMath.totalPages(total, size);
        if (total == 0) {
            return new Page(List.of(), 1, size, 0, 0, sortField + "," + (ascending ? "asc" : "desc"));
        }
        long actualPage = Math.min(page, totalPages);
        var query = new LambdaQueryWrapper<ReportInstance>()
                .select(ReportInstance::getId, ReportInstance::getTemplateCode, ReportInstance::getTemplateVersion,
                        ReportInstance::getSourceId, ReportInstance::getSnapshotId,
                        ReportInstance::getDefinitionVersion, ReportInstance::getFromDate,
                        ReportInstance::getToDate, ReportInstance::getPromptVersion,
                        ReportInstance::getCreatorId, ReportInstance::getStatus,
                        ReportInstance::getArtifactUri, ReportInstance::getArtifactChecksum,
                        ReportInstance::getStableErrorCode, ReportInstance::getCreatedAt,
                        ReportInstance::getUpdatedAt)
                .eq(ReportInstance::getCreatorId, ownerId);
        switch (sortField) {
            case "createdAt" -> query.orderBy(true, ascending, ReportInstance::getCreatedAt);
            case "templateCode" -> query.orderBy(true, ascending, ReportInstance::getTemplateCode);
            case "status" -> query.orderBy(true, ascending, ReportInstance::getStatus);
            default -> query.orderBy(true, ascending, ReportInstance::getId);
        }
        if (!"id".equals(sortField)) query.orderByDesc(ReportInstance::getId);
        long offset = Math.multiplyExact(actualPage - 1, (long) size);
        List<ReportInstance> items = mapper.selectList(query.last("LIMIT " + size + " OFFSET " + offset));
        return new Page(items, actualPage, size, total, totalPages,
                sortField + "," + (ascending ? "asc" : "desc"));
    }

    public ReportInstance findOwn(long id, String ownerId) {
        return mapper.selectOne(new LambdaQueryWrapper<ReportInstance>()
                .eq(ReportInstance::getId, id).eq(ReportInstance::getCreatorId, ownerId));
    }

    public JsonNode artifact(ReportInstance report) {
        if (report == null || report.getArtifactJson() == null) {
            throw new PlatformBizException("REPORT_ARTIFACT_UNAVAILABLE", "报告制品不可用");
        }
        String storedChecksum = report.getArtifactChecksum();
        if (storedChecksum == null || storedChecksum.isBlank()
                || !storedChecksum.equals(sha256(report.getArtifactJson()))) {
            throw new PlatformBizException("REPORT_ARTIFACT_CHECKSUM_MISMATCH", "报告制品完整性校验失败");
        }
        try {
            JsonNode artifact = objectMapper.readTree(report.getArtifactJson());
            validateArtifact(report, artifact);
            return artifact;
        } catch (JsonProcessingException malformed) {
            throw new PlatformBizException("REPORT_ARTIFACT_INVALID", "报告制品格式无效");
        }
    }

    private void validateArtifact(ReportInstance report, JsonNode artifact) {
        JsonNode evidence = artifact.path("evidence");
        JsonNode lineage = evidence.path("lineage");
        boolean valid = artifact.isObject()
                && Objects.equals(report.getTemplateCode(), artifact.path("templateCode").asText(null))
                && Objects.equals(report.getTemplateVersion(), artifact.path("templateVersion").asText(null))
                && evidence.isObject()
                && Objects.equals(report.getSnapshotId(), evidence.path("snapshotId").asText(null))
                && Objects.equals(report.getDefinitionVersion(), evidence.path("definitionVersion").asText(null))
                && lineage.isObject()
                && Objects.equals(report.getSnapshotId(), lineage.path("snapshotId").asText(null))
                && report.getSourceId() != null && lineage.path("sourceId").canConvertToLong()
                && report.getSourceId().longValue() == lineage.path("sourceId").asLong()
                && evidence.path("facts").isArray() && evidence.path("comparisons").isArray()
                && evidence.path("dimensions").isObject() && evidence.path("anomalies").isArray()
                && evidence.path("dataQuality").isObject() && evidence.path("warnings").isArray()
                && isTemplateContentValid(report, artifact.path("templateContent"));
        if (!valid) {
            throw new PlatformBizException("REPORT_ARTIFACT_INVALID", "报告制品血缘或结构与实例元数据不一致");
        }
    }

    private boolean isTemplateContentValid(ReportInstance report, JsonNode content) {
        if (content == null || content.isMissingNode()) return false;
        return switch (report.getTemplateCode()) {
            case "OPS_DAILY" -> content.isNull() || content.isMissingNode();
            case "OPS_WEEKLY" -> isWeeklyContentValid(report, content);
            case "ORDER_ANALYSIS" -> content.isObject()
                    && content.path("metrics").isArray() && content.path("unavailableMetrics").isArray()
                    && content.path("relatedCandidates").isArray();
            case "PRODUCT_ANOMALY" -> content.isObject()
                    && content.path("totalProducts").canConvertToLong()
                    && content.path("topHeatProducts").isArray()
                    && content.path("snapshotMetricCandidates").isArray()
                    && content.path("warnings").isArray() && content.path("limitations").isArray();
            case "USER_OPERATIONS" -> content.isObject()
                    && content.path("rfmSegments").isArray() && content.path("lifecycle").isArray()
                    && content.path("categoryPreference").isArray()
                    && content.path("aggregateMetrics").isArray()
                    && content.path("unavailableMetrics").isArray()
                    && content.path("warnings").isArray() && content.path("limitations").isArray();
            case "DECISION_EFFECT" -> content.isObject()
                    && content.path("decisionId").canConvertToLong()
                    && Set.of("EFFECTIVE", "PARTIAL", "INEFFECTIVE", "INSUFFICIENT_DATA")
                    .contains(content.path("result").asText())
                    && content.path("limitations").isArray()
                    && Objects.equals(report.getFromDate() == null ? null : report.getFromDate().toString(),
                    content.path("baselineWindowStart").asText(null))
                    && Objects.equals(report.getToDate() == null ? null : report.getToDate().toString(),
                    content.path("actualWindowEnd").asText(null));
            default -> false;
        };
    }

    private boolean isWeeklyContentValid(ReportInstance report, JsonNode content) {
        if (!content.isObject() || !content.path("daily").isArray() || content.path("daily").size() != 7
                || !content.path("missingSalesDates").isArray()
                || !content.path("missingBehaviorDates").isArray()
                || report.getFromDate() == null || report.getToDate() == null
                || !report.getFromDate().plusDays(6).equals(report.getToDate())
                || !Objects.equals(report.getFromDate().toString(), content.path("fromDate").asText(null))
                || !Objects.equals(report.getToDate().toString(), content.path("toDate").asText(null))) {
            return false;
        }
        for (int index = 0; index < 7; index++) {
            JsonNode daily = content.path("daily").get(index);
            if (daily == null || !daily.isObject()
                    || !Objects.equals(report.getFromDate().plusDays(index).toString(), daily.path("date").asText(null))) {
                return false;
            }
        }
        return true;
    }

    public JsonNode summary(ReportInstance report) {
        JsonNode artifact = artifact(report);
        JsonNode evidence = artifact.path("evidence");
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("templateCode", report.getTemplateCode());
        summary.put("templateVersion", report.getTemplateVersion());
        summary.put("snapshotId", report.getSnapshotId());
        summary.put("fromDate", report.getFromDate() == null ? null : report.getFromDate().toString());
        summary.put("toDate", report.getToDate() == null ? null : report.getToDate().toString());
        summary.put("status", report.getStatus());
        summary.put("factCount", evidence.path("facts").isArray() ? evidence.path("facts").size() : 0);
        summary.put("comparisonCount", evidence.path("comparisons").isArray()
                ? evidence.path("comparisons").size() : 0);
        JsonNode quality = evidence.path("dataQuality");
        if (quality.isObject()) {
            ObjectNode qualitySummary = objectMapper.createObjectNode();
            copyFields(quality, qualitySummary, "gateStatus", "ruleTotal", "rulePassed");
            qualitySummary.put("failedRuleCount", quality.path("failedRules").isArray()
                    ? quality.path("failedRules").size() : 0);
            qualitySummary.put("warningCount", quality.path("warnings").isArray()
                    ? quality.path("warnings").size() : 0);
            summary.set("dataQuality", qualitySummary);
        }
        JsonNode warnings = evidence.path("warnings");
        summary.put("warningCount", warnings.isArray() ? warnings.size() : 0);

        JsonNode content = artifact.path("templateContent");
        ObjectNode templateSummary = objectMapper.createObjectNode();
        switch (report.getTemplateCode()) {
            case "OPS_WEEKLY" -> copyFields(content, templateSummary, "fromDate", "toDate", "totalOrders",
                    "totalSaleAmount", "totalNetSaleAmount", "averageOrderValue", "totalBehaviorCount");
            case "ORDER_ANALYSIS" -> {
                templateSummary.put("availableMetricCount", content.path("metrics").isArray()
                        ? content.path("metrics").size() : 0);
                copyFields(content, templateSummary, "unavailableMetrics");
            }
            case "PRODUCT_ANOMALY" -> {
                copyFields(content, templateSummary, "totalProducts");
                templateSummary.put("topHeatProductCount", content.path("topHeatProducts").isArray()
                        ? content.path("topHeatProducts").size() : 0);
                templateSummary.put("existingCandidateCount", content.path("snapshotMetricCandidates").isArray()
                        ? content.path("snapshotMetricCandidates").size() : 0);
            }
            case "USER_OPERATIONS" -> {
                copyFields(content, templateSummary, "periodStart", "periodEnd", "ruleVersion",
                        "unavailableMetrics");
                templateSummary.put("rfmSegmentCount", content.path("rfmSegments").isArray()
                        ? content.path("rfmSegments").size() : 0);
                templateSummary.put("lifecycleGroupCount", content.path("lifecycle").isArray()
                        ? content.path("lifecycle").size() : 0);
                templateSummary.put("categoryPreferenceCount", content.path("categoryPreference").isArray()
                        ? content.path("categoryPreference").size() : 0);
            }
            case "DECISION_EFFECT" -> copyFields(content, templateSummary, "decisionId", "decisionNo", "result",
                    "baselineValue", "actualValue", "improvementRate", "baselineWindowStart",
                    "baselineWindowEnd", "actualWindowStart", "actualWindowEnd");
            default -> { }
        }
        summary.set("templateSummary", templateSummary);
        return summary;
    }

    private static void copyFields(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null) target.set(field, value.deepCopy());
        }
    }

    private String serialize(ReportArtifact artifact) {
        try {
            return objectMapper.writeValueAsString(artifact);
        } catch (JsonProcessingException e) {
            throw new PlatformBizException("REPORT_SERIALIZATION_FAILED", "报告制品序列化失败");
        }
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record DecisionContext(DecisionTask task, DecisionEvaluation evaluation, String snapshotId,
                                   LocalDate evidenceDate, LocalDate fromDate, LocalDate toDate) {
    }
}
