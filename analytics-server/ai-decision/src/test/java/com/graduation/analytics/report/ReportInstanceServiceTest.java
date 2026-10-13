package com.graduation.analytics.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.graduation.analytics.analysis.AnalysisService;
import com.graduation.analytics.analysis.AnalysisViewModel;
import com.graduation.analytics.ai.evidence.EvidencePackage;
import com.graduation.analytics.ai.evidence.EvidenceService;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.entity.DecisionEvaluation;
import com.graduation.analytics.decision.entity.DecisionTask;
import com.graduation.analytics.decision.mapper.DecisionEvaluationMapper;
import com.graduation.analytics.decision.mapper.DecisionTaskMapper;
import com.graduation.analytics.report.entity.ReportInstance;
import com.graduation.analytics.report.mapper.ReportInstanceMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportInstanceServiceTest {
    private final EvidenceService evidenceService = mock(EvidenceService.class);
    private final AnalysisService analysisService = mock(AnalysisService.class);
    private final ReportInstanceMapper mapper = mock(ReportInstanceMapper.class);
    private final DecisionTaskMapper decisionTaskMapper = mock(DecisionTaskMapper.class);
    private final DecisionEvaluationMapper decisionEvaluationMapper = mock(DecisionEvaluationMapper.class);
    private final ReportInstanceService service = new ReportInstanceService(evidenceService, analysisService, mapper,
            decisionTaskMapper, decisionEvaluationMapper, new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void createsOpsDailyFromCompleteServerEvidenceAndStoresChecksum() {
        when(evidenceService.build(any())).thenReturn(evidence(LocalDate.parse("2026-10-10"), 7L));
        doAnswer(invocation -> {
            ReportInstance report = invocation.getArgument(0);
            report.setId(81L);
            return 1;
        }).when(mapper).insert(any(ReportInstance.class));
        when(mapper.updateById(any(ReportInstance.class))).thenReturn(1);

        ReportInstance report = service.create(new ReportInstanceService.CreateCommand(
                "OPS_DAILY", null, null, LocalDate.parse("2026-10-10"), false, null, null, null), "analyst1");

        assertThat(report.getId()).isEqualTo(81L);
        assertThat(report.getStatus()).isEqualTo("READY");
        assertThat(report.getCreatorId()).isEqualTo("analyst1");
        assertThat(report.getSnapshotId()).isEqualTo("S1");
        assertThat(report.getSourceId()).isEqualTo(7L);
        assertThat(report.getArtifactUri()).isEqualTo("db://report-instance/81");
        assertThat(report.getArtifactChecksum()).hasSize(64);
        assertThat(report.getArtifactJson()).contains("EV-20261010-abc123", "OPS_DAILY");
        assertThat(service.artifact(report).path("evidence").path("snapshotId").asText()).isEqualTo("S1");
    }

    @Test
    void orderAnalysisIncludesOnlyPublishedOrderMetricsAndListsMissingMetrics() throws Exception {
        EvidencePackage source = evidence(LocalDate.parse("2026-10-10"), 7L,
                List.of(fact("gmv", "100"), fact("paid_order_cnt", "4"), fact("uv", "90")),
                List.of(new EvidencePackage.AnomalyCandidate("ORDER_GMV_HIGH", "gmv", "HIGH", "100", "80",
                                "20", "异常成交额", "metric@S1"),
                        new EvidencePackage.AnomalyCandidate("USER_UV_LOW", "uv", "LOW", "90", "100",
                                "-10", "低访问", "metric@S1")));
        stubCreate(source);

        ReportInstance report = service.create(new ReportInstanceService.CreateCommand(
                "ORDER_ANALYSIS", null, null, LocalDate.parse("2026-10-10"), false, null, null, null), "analyst1");
        var content = new ObjectMapper().readTree(report.getArtifactJson()).path("templateContent");

        assertThat(content.path("metrics").findValuesAsText("metricCode"))
                .containsExactly("paid_order_cnt", "gmv");
        assertThat(content.path("unavailableMetrics").toString())
                .contains("net_sale", "avg_order_value", "refund_rate", "full_refund_rate");
        assertThat(content.path("relatedCandidates").size()).isEqualTo(1);
        assertThat(content.path("relatedCandidates").get(0).path("metricCode").asText()).isEqualTo("gmv");
    }

    @Test
    void productAnomalyKeepsMissingConversionUnavailableAndRequiresSameEvidenceAnchor() throws Exception {
        stubCreate(evidence(LocalDate.parse("2026-10-10"), 7L));
        AnalysisService.ProductsData products = new AnalysisService.ProductsData(
                List.of(new AnalysisService.HotProduct(101L, "合成商品", new BigDecimal("8.5"),
                        30, 4, 2, 1, 1)), List.of(), 10, 1, 10, 1, false);
        when(analysisService.products("S1", 1, 10, "rank,asc", 10,
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10")))
                .thenReturn(AnalysisViewModel.of("S1", 7L, "spark-ads", "2026-10-10T00:00:00",
                        null, "d3", "PASS", Map.of(), products, List.of()));

        ReportInstance report = service.create(new ReportInstanceService.CreateCommand(
                "PRODUCT_ANOMALY", null, null, LocalDate.parse("2026-10-10"), false, null, null, null), "analyst1");
        var content = new ObjectMapper().readTree(report.getArtifactJson()).path("templateContent");
        var signal = content.path("topHeatProducts").get(0);

        assertThat(content.path("totalProducts").asLong()).isEqualTo(1L);
        assertThat(signal.path("productId").asLong()).isEqualTo(101L);
        assertThat(signal.path("productViewUsers").isNull()).isTrue();
        assertThat(signal.path("conversionRate").isNull()).isTrue();
        assertThat(content.path("limitations").toString()).contains("不推断库存风险");
    }

    @Test
    void userOperationsRejectsCrossSnapshotAggregationBeforePersistence() {
        stubCreate(evidence(LocalDate.parse("2026-10-10"), 7L));
        AnalysisService.UsersData users = new AnalysisService.UsersData(List.of(), List.of(), List.of(),
                "rfm-v1", "2026-10-01", "2026-10-10");
        when(analysisService.users("S1", LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10")))
                .thenReturn(AnalysisViewModel.of("S2", 7L, "spark-ads", "2026-10-10T00:00:00",
                        null, "d3", "PASS", Map.of(), users, List.of()));

        assertThatThrownBy(() -> service.create(new ReportInstanceService.CreateCommand(
                "USER_OPERATIONS", null, null, LocalDate.parse("2026-10-10"), false, null, null, null), "analyst1"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("同一快照和来源");
        verify(mapper, never()).insert(any(ReportInstance.class));
    }

    @Test
    void decisionEffectRejectsClientSnapshotAndDateOverridesBeforeReadingDecision() {
        LocalDate date = LocalDate.parse("2026-10-10");
        List<ReportInstanceService.CreateCommand> unsupportedInputs = List.of(
                new ReportInstanceService.CreateCommand("DECISION_EFFECT", "1", "CLIENT-SNAPSHOT",
                        null, false, null, null, 42L),
                new ReportInstanceService.CreateCommand("DECISION_EFFECT", "1", null,
                        date, false, null, null, 42L),
                new ReportInstanceService.CreateCommand("DECISION_EFFECT", "1", null,
                        null, false, date, null, 42L),
                new ReportInstanceService.CreateCommand("DECISION_EFFECT", "1", null,
                        null, false, null, date, 42L));

        for (ReportInstanceService.CreateCommand command : unsupportedInputs) {
            assertThatThrownBy(() -> service.create(command, "analyst1"))
                    .isInstanceOf(PlatformBizException.class)
                    .hasMessageContaining("只接受 decisionId");
        }

        verify(decisionTaskMapper, never()).selectById(any());
        verify(decisionEvaluationMapper, never()).selectOne(any());
        verify(evidenceService, never()).build(any());
        verify(mapper, never()).insert(any(ReportInstance.class));
    }

    @Test
    void decisionEffectReportReusesTheSavedEvaluationAndItsSnapshotLineage() throws Exception {
        DecisionTask task = decisionTask("EFFECTIVE");
        DecisionEvaluation evaluation = decisionEvaluation("EFFECTIVE");
        when(decisionTaskMapper.selectById(42L)).thenReturn(task);
        when(decisionEvaluationMapper.selectOne(any())).thenReturn(evaluation);
        stubCreate(evidence(LocalDate.parse("2026-09-14"), 7L));

        ReportInstance report = service.create(new ReportInstanceService.CreateCommand(
                "DECISION_EFFECT", null, null, null, false, null, null, 42L), "analyst1");
        var artifact = new ObjectMapper().registerModule(new JavaTimeModule()).readTree(report.getArtifactJson());
        var content = artifact.path("templateContent");

        assertThat(report.getSnapshotId()).isEqualTo("S1");
        assertThat(report.getFromDate()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(report.getToDate()).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(content.path("result").asText()).isEqualTo("EFFECTIVE");
        assertThat(content.path("baselineValue").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(content.path("actualValue").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(content.path("baselineSnapshotRefs").asText()).isEqualTo("baseline-refs");
        assertThat(content.path("actualSnapshotRefs").asText()).isEqualTo("actual-refs");
        assertThat(content.path("metricDefinitionVersion").asText()).isEqualTo("d3");
        assertThat(content.path("evaluationDefinitionVersion").asText()).isEqualTo("decision-eval-v2");
        assertThat(content.path("limitations").toString()).contains("不证明决策行动导致该变化");
        verify(analysisService, never()).overview(any(), any(), any());
    }

    @Test
    void decisionEffectInsufficientDataUsesBaselineSnapshotWithoutInventingActualValues() throws Exception {
        DecisionTask task = decisionTask("INSUFFICIENT_DATA");
        DecisionEvaluation evaluation = decisionEvaluation("INSUFFICIENT_DATA");
        evaluation.setBaselineSnapshotId("S1");
        evaluation.setActualSnapshotId(null);
        evaluation.setActualValue(null);
        evaluation.setActualPeriodValue(new BigDecimal("120.00"));
        evaluation.setActualSampleCount(3);
        when(decisionTaskMapper.selectById(42L)).thenReturn(task);
        when(decisionEvaluationMapper.selectOne(any())).thenReturn(evaluation);
        stubCreate(evidence(LocalDate.parse("2026-09-07"), 7L));

        ReportInstance report = service.create(new ReportInstanceService.CreateCommand(
                "DECISION_EFFECT", null, null, null, false, null, null, 42L), "analyst1");
        var content = new ObjectMapper().registerModule(new JavaTimeModule())
                .readTree(report.getArtifactJson()).path("templateContent");

        assertThat(report.getSnapshotId()).isEqualTo("S1");
        assertThat(report.getFromDate()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(report.getToDate()).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(content.path("result").asText()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(content.path("baselineValue").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(content.path("actualValue").isNull()).isTrue();
        assertThat(content.path("actualSampleCount").asInt()).isEqualTo(3);
    }

    @Test
    void decisionEffectRejectsMismatchedSavedEvaluationWithoutPersistence() {
        DecisionTask task = decisionTask("EFFECTIVE");
        DecisionEvaluation staleEvaluation = decisionEvaluation("PARTIAL");
        when(decisionTaskMapper.selectById(42L)).thenReturn(task);
        when(decisionEvaluationMapper.selectOne(any())).thenReturn(staleEvaluation);

        assertThatThrownBy(() -> service.create(new ReportInstanceService.CreateCommand(
                "DECISION_EFFECT", null, null, null, false, null, null, 42L), "analyst1"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("一致且可追溯");
        verify(evidenceService, never()).build(any());
        verify(mapper, never()).insert(any(ReportInstance.class));
    }

    @Test
    void rejectsWeeklyTemplateWithoutAnExactSevenDayWindow() {
        assertThatThrownBy(() -> service.create(new ReportInstanceService.CreateCommand(
                "OPS_WEEKLY", "1", null, LocalDate.parse("2026-10-10"), false, null, null, null), "analyst1"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("连续七日窗口");
        verify(evidenceService, never()).build(any());
        verify(mapper, never()).insert(any(ReportInstance.class));
    }

    @Test
    void weeklyTotalsStayUnavailableWhenADayIsMissing() throws Exception {
        LocalDate from = LocalDate.parse("2026-10-04");
        LocalDate to = LocalDate.parse("2026-10-10");
        List<AnalysisService.SalesTrendPoint> sales = new ArrayList<>();
        List<AnalysisService.ActiveDay> activity = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            LocalDate date = from.plusDays(day);
            if (day < 6) {
                sales.add(new AnalysisService.SalesTrendPoint(date.toString(), 2,
                        new BigDecimal("20.00"), 1, new BigDecimal("10.00"), new BigDecimal("18.00")));
            }
            activity.add(new AnalysisService.ActiveDay(date.toString(), 3, 4));
        }
        stubWeeklyCreate(from, to, sales, activity);

        ReportInstance report = service.create(new ReportInstanceService.CreateCommand(
                "OPS_WEEKLY", "1", "S1", to, false, from, to, null), "analyst1");
        var content = new ObjectMapper().readTree(report.getArtifactJson()).path("templateContent");

        assertThat(content.path("totalOrders").isNull()).isTrue();
        assertThat(content.path("totalSaleAmount").isNull()).isTrue();
        assertThat(content.path("totalNetSaleAmount").isNull()).isTrue();
        assertThat(content.path("averageOrderValue").isNull()).isTrue();
        assertThat(content.path("totalBehaviorCount").asLong()).isEqualTo(28L);
        assertThat(content.path("warnings").toString())
                .contains("WARN_WEEKLY_SALES_DAYS_MISSING", "WARN_WEEKLY_ORDER_COUNTS_INCOMPLETE",
                        "WARN_WEEKLY_SALES_AMOUNTS_INCOMPLETE", "WARN_WEEKLY_NET_SALES_INCOMPLETE");
    }

    @Test
    void weeklyAmountTotalsStayUnavailableWhenAnAmountIsNull() throws Exception {
        LocalDate from = LocalDate.parse("2026-10-04");
        LocalDate to = LocalDate.parse("2026-10-10");
        List<AnalysisService.SalesTrendPoint> sales = new ArrayList<>();
        List<AnalysisService.ActiveDay> activity = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            LocalDate date = from.plusDays(day);
            sales.add(new AnalysisService.SalesTrendPoint(date.toString(), 2,
                    day == 3 ? null : new BigDecimal("20.00"), 1,
                    new BigDecimal("10.00"), new BigDecimal("18.00")));
            activity.add(new AnalysisService.ActiveDay(date.toString(), 3, 4));
        }
        stubWeeklyCreate(from, to, sales, activity);

        ReportInstance report = service.create(new ReportInstanceService.CreateCommand(
                "OPS_WEEKLY", "1", "S1", to, false, from, to, null), "analyst1");
        var content = new ObjectMapper().readTree(report.getArtifactJson()).path("templateContent");

        assertThat(content.path("totalOrders").asLong()).isEqualTo(14L);
        assertThat(content.path("totalSaleAmount").isNull()).isTrue();
        assertThat(content.path("totalNetSaleAmount").decimalValue()).isEqualByComparingTo("126.00");
        assertThat(content.path("averageOrderValue").isNull()).isTrue();
        assertThat(content.path("warnings").toString()).contains("WARN_WEEKLY_SALES_AMOUNTS_INCOMPLETE");
    }

    @Test
    void blocksOptionalAiUntilD039IsSatisfied() {
        assertThatThrownBy(() -> service.create(new ReportInstanceService.CreateCommand(
                "OPS_DAILY", "1", null, LocalDate.parse("2026-10-10"), true, null, null, null), "analyst1"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("D-039");
        verify(evidenceService, never()).build(any());
    }

    @Test
    void dateMismatchOrMissingSourceFailsClosedBeforePersistence() {
        when(evidenceService.build(any())).thenReturn(evidence(LocalDate.parse("2026-10-09"), 7L));
        assertThatThrownBy(() -> service.create(new ReportInstanceService.CreateCommand(
                "OPS_DAILY", "1", null, LocalDate.parse("2026-10-10"), false, null, null, null), "analyst1"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("业务日不一致");
        verify(mapper, never()).insert(any(ReportInstance.class));
    }

    @Test
    void emptyOwnerPageReturnsValidFirstPageWithoutNegativeOffsetQuery() {
        when(mapper.selectCount(any())).thenReturn(0L);

        ReportInstanceService.Page page = service.page("analyst1", 1, 20, "createdAt", false);

        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.total()).isZero();
        assertThat(page.totalPages()).isZero();
        verify(mapper, never()).selectList(any());
    }

    @Test
    void artifactTemplateContentAcceptsSixValidShapesAndRejectsMalformedShapes() throws Exception {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        Map<String, String> validContentByTemplate = Map.of(
                "OPS_DAILY", "null",
                "OPS_WEEKLY", "{\"daily\":[{\"date\":\"2026-10-04\"},{\"date\":\"2026-10-05\"},"
                        + "{\"date\":\"2026-10-06\"},{\"date\":\"2026-10-07\"},{\"date\":\"2026-10-08\"},"
                        + "{\"date\":\"2026-10-09\"},{\"date\":\"2026-10-10\"}],\"missingSalesDates\":[],"
                        + "\"missingBehaviorDates\":[],\"fromDate\":\"2026-10-04\",\"toDate\":\"2026-10-10\"}",
                "ORDER_ANALYSIS", "{\"metrics\":[],\"unavailableMetrics\":[],\"relatedCandidates\":[]}",
                "PRODUCT_ANOMALY", "{\"totalProducts\":0,\"topHeatProducts\":[],"
                        + "\"snapshotMetricCandidates\":[],\"warnings\":[],\"limitations\":[]}",
                "USER_OPERATIONS", "{\"rfmSegments\":[],\"lifecycle\":[],\"categoryPreference\":[],"
                        + "\"aggregateMetrics\":[],\"unavailableMetrics\":[],\"warnings\":[],\"limitations\":[]}",
                "DECISION_EFFECT", "{\"decisionId\":42,\"result\":\"EFFECTIVE\",\"limitations\":[],"
                        + "\"baselineWindowStart\":\"2026-10-04\",\"actualWindowEnd\":\"2026-10-10\"}");

        for (var entry : validContentByTemplate.entrySet()) {
            ReportInstance report = storedArtifact(json, entry.getKey(), json.readTree(entry.getValue()));
            assertThat(service.artifact(report).path("templateCode").asText()).isEqualTo(entry.getKey());

            var malformed = json.readTree(entry.getValue());
            if (entry.getKey().equals("OPS_DAILY")) {
                malformed = json.createObjectNode();
            } else if (entry.getKey().equals("OPS_WEEKLY")) {
                ((ObjectNode) malformed).remove("daily");
            } else {
                ((ObjectNode) malformed).remove(malformed.fieldNames().next());
            }
            ReportInstance invalidReport = storedArtifact(json, entry.getKey(), malformed);
            assertThatThrownBy(() -> service.artifact(invalidReport))
                    .isInstanceOf(PlatformBizException.class)
                    .hasMessageContaining("报告制品血缘或结构");
        }
    }

    @Test
    void artifactTemplateContentRejectsWrongTypesAndMismatchedWindows() throws Exception {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        Map<String, List<String>> invalidContents = Map.of(
                "OPS_DAILY", List.of("{}"),
                "OPS_WEEKLY", List.of(
                        "{\"daily\":[{},{},{},{},{},{}],\"missingSalesDates\":[],"
                                + "\"missingBehaviorDates\":[],\"fromDate\":\"2026-10-04\",\"toDate\":\"2026-10-10\"}",
                        "{\"daily\":[{\"date\":\"2026-10-04\"},{\"date\":\"2026-10-05\"},"
                                + "{\"date\":\"2026-10-06\"},{\"date\":\"2026-10-07\"},{\"date\":\"2026-10-08\"},"
                                + "{\"date\":\"2026-10-09\"},{\"date\":\"2026-10-10\"}],\"missingSalesDates\":[],"
                                + "\"missingBehaviorDates\":[],\"fromDate\":\"2026-10-04\",\"toDate\":\"2026-10-09\"}",
                        "{\"daily\":[{\"date\":\"2026-10-04\"},{\"date\":\"2026-10-05\"},"
                                + "{\"date\":\"2026-10-06\"},{\"date\":\"2026-10-07\"},{\"date\":\"2026-10-08\"},"
                                + "{\"date\":\"2026-10-09\"},{\"date\":\"2026-10-09\"}],\"missingSalesDates\":[],"
                                + "\"missingBehaviorDates\":[],\"fromDate\":\"2026-10-04\",\"toDate\":\"2026-10-10\"}"),
                "ORDER_ANALYSIS", List.of("{\"metrics\":{},\"unavailableMetrics\":[],\"relatedCandidates\":[]}"),
                "PRODUCT_ANOMALY", List.of("{\"totalProducts\":\"unknown\",\"topHeatProducts\":[],"
                        + "\"snapshotMetricCandidates\":[],\"warnings\":[],\"limitations\":[]}"),
                "USER_OPERATIONS", List.of("{\"rfmSegments\":{},\"lifecycle\":[],\"categoryPreference\":[],"
                        + "\"aggregateMetrics\":[],\"unavailableMetrics\":[],\"warnings\":[],\"limitations\":[]}"),
                "DECISION_EFFECT", List.of(
                        "{\"decisionId\":42,\"result\":\"UNKNOWN\",\"limitations\":[],"
                                + "\"baselineWindowStart\":\"2026-10-04\",\"actualWindowEnd\":\"2026-10-10\"}",
                        "{\"decisionId\":42,\"result\":\"EFFECTIVE\",\"limitations\":[],"
                                + "\"baselineWindowStart\":\"2026-10-03\",\"actualWindowEnd\":\"2026-10-10\"}"));

        for (var entry : invalidContents.entrySet()) {
            for (String content : entry.getValue()) {
                ReportInstance report = storedArtifact(json, entry.getKey(), json.readTree(content));
                assertThatThrownBy(() -> service.artifact(report))
                        .isInstanceOf(PlatformBizException.class)
                        .hasMessageContaining("报告制品血缘或结构");
            }
        }
    }

    private static ReportInstance storedArtifact(ObjectMapper json, String template,
                                                 com.fasterxml.jackson.databind.JsonNode content)
            throws Exception {
        var artifact = json.createObjectNode();
        artifact.put("templateCode", template);
        artifact.put("templateVersion", "1");
        artifact.set("templateContent", json.valueToTree(content));
        var evidence = artifact.putObject("evidence");
        evidence.put("snapshotId", "S1");
        evidence.put("definitionVersion", "d3");
        evidence.putArray("facts");
        evidence.putArray("comparisons");
        evidence.putObject("dimensions");
        evidence.putArray("anomalies");
        evidence.putObject("dataQuality");
        evidence.putArray("warnings");
        var lineage = evidence.putObject("lineage");
        lineage.put("snapshotId", "S1");
        lineage.put("sourceId", 7);

        String body = json.writeValueAsString(artifact);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        ReportInstance report = new ReportInstance();
        report.setTemplateCode(template);
        report.setTemplateVersion("1");
        report.setSnapshotId("S1");
        report.setDefinitionVersion("d3");
        report.setSourceId(7L);
        report.setFromDate(LocalDate.parse("2026-10-04"));
        report.setToDate(LocalDate.parse("2026-10-10"));
        report.setArtifactJson(body);
        report.setArtifactChecksum(checksum);
        return report;
    }

    private static EvidencePackage evidence(LocalDate date, Long sourceId) {
        return evidence(date, sourceId,
                List.of(new EvidencePackage.Fact("gmv", "成交额", "100", "元", "day:" + date, "metric@S1")),
                List.of());
    }

    private static EvidencePackage evidence(LocalDate date, Long sourceId,
                                            List<EvidencePackage.Fact> facts,
                                            List<EvidencePackage.AnomalyCandidate> anomalies) {
        String day = date.toString();
        return new EvidencePackage("EV-20261010-abc123", "evidence_v1", "S1", "d3",
                "2026-10-10T00:00:00", new EvidencePackage.Period(day, day),
                new EvidencePackage.Period(day, day), null,
                facts, List.of(), Map.of(), anomalies, EvidencePackage.DataQuality.unknown("test"),
                new EvidencePackage.Lineage(List.of(), List.of(), 3L, "S1", sourceId), List.of());
    }

    private static EvidencePackage.Fact fact(String code, String value) {
        return new EvidencePackage.Fact(code, code, value, "count", "day:2026-10-10", "metric@S1");
    }

    private static DecisionTask decisionTask(String status) {
        DecisionTask task = new DecisionTask();
        task.setId(42L);
        task.setDecisionNo("D-2026-0042");
        task.setTitle("合成决策评价报告夹具");
        task.setTargetMetricCode("gmv");
        task.setTargetDirection("INCREASE");
        task.setStatus(status);
        task.setCompletedAt(LocalDateTime.parse("2026-09-07T10:00:00"));
        task.setSourceId(7L);
        task.setRuntimeProfileId(3L);
        task.setDefinitionVersion("d3");
        task.setEvalWindowDays(7);
        return task;
    }

    private static DecisionEvaluation decisionEvaluation(String result) {
        DecisionEvaluation evaluation = new DecisionEvaluation();
        evaluation.setId(90L);
        evaluation.setDecisionId(42L);
        evaluation.setResult(result);
        evaluation.setSourceId(7L);
        evaluation.setRuntimeProfileId(3L);
        evaluation.setMetricDefinitionVersion("d3");
        evaluation.setDefinitionVersion("decision-eval-v2");
        evaluation.setEvalWindowDays(7);
        evaluation.setBaselineValue(new BigDecimal("100.00"));
        evaluation.setActualValue(new BigDecimal("120.00"));
        evaluation.setBaselinePeriodValue(new BigDecimal("100.00"));
        evaluation.setActualPeriodValue(new BigDecimal("120.00"));
        evaluation.setImprovementRate(new BigDecimal("0.2000"));
        evaluation.setBaselineSnapshotId("SB");
        evaluation.setBaselineSnapshotRefs("baseline-refs");
        evaluation.setActualSnapshotId("S1");
        evaluation.setActualSnapshotRefs("actual-refs");
        evaluation.setBaselineSampleCount(7);
        evaluation.setActualSampleCount(7);
        evaluation.setBaselineWindowStart(LocalDate.parse("2026-09-01"));
        evaluation.setBaselineWindowEnd(LocalDate.parse("2026-09-07"));
        evaluation.setWindowStart(LocalDate.parse("2026-09-08"));
        evaluation.setWindowEnd(LocalDate.parse("2026-09-14"));
        return evaluation;
    }

    private void stubCreate(EvidencePackage packageEvidence) {
        when(evidenceService.build(any())).thenReturn(packageEvidence);
        doAnswer(invocation -> {
            ReportInstance report = invocation.getArgument(0);
            report.setId(81L);
            return 1;
        }).when(mapper).insert(any(ReportInstance.class));
        when(mapper.updateById(any(ReportInstance.class))).thenReturn(1);
    }

    private void stubWeeklyCreate(LocalDate from, LocalDate to,
                                  List<AnalysisService.SalesTrendPoint> sales,
                                  List<AnalysisService.ActiveDay> activity) {
        AnalysisService.OverviewData data = new AnalysisService.OverviewData(null, sales, activity, null, null);
        AnalysisViewModel<AnalysisService.OverviewData> overview = new AnalysisViewModel<>(
                "S1", 7L, null, null, null, "d3", "PASS", Map.of(), data, List.of());
        when(evidenceService.build(any())).thenReturn(evidence(to, 7L));
        when(analysisService.overview("S1", from, to)).thenReturn(overview);
        doAnswer(invocation -> {
            ReportInstance report = invocation.getArgument(0);
            report.setId(81L);
            return 1;
        }).when(mapper).insert(any(ReportInstance.class));
        when(mapper.updateById(any(ReportInstance.class))).thenReturn(1);
    }
}
