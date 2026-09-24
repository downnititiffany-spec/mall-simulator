package com.graduation.analytics.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.controller.DecisionController;
import com.graduation.analytics.controller.PlatformExceptionAdvice;
import com.graduation.analytics.decision.entity.DecisionEvaluation;
import com.graduation.analytics.decision.entity.DecisionTask;
import com.graduation.analytics.decision.mapper.DecisionEvaluationMapper;
import com.graduation.analytics.decision.mapper.DecisionTaskMapper;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * DB-free composition test for the human decision approval path.
 * The HTTP controller, authentication interceptor, advice, and DecisionService are real;
 * only mapper persistence and runtime metric/profile ports are test doubles.
 */
class DecisionHttpLifecycleOfflineTest {

    private static final String ANALYST_TOKEN = "offline-analyst";
    private static final String OPERATOR_TOKEN = "offline-operator";
    private static final String ACTIVE_SNAPSHOT = "S-OFFLINE-ACTIVE";
    private static final Long PROFILE_ID = 7L;
    private static final Long SOURCE_ID = 1L;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final Map<Long, DecisionTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(40L);

    private DecisionTaskMapper taskMapper;
    private DecisionEvaluationMapper evaluationMapper;
    private MetricStore metricStore;
    private OperationAuditService audit;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tasks.clear();
        ids.set(40L);

        taskMapper = mock(DecisionTaskMapper.class);
        when(taskMapper.insert(any(DecisionTask.class))).thenAnswer(invocation -> {
            DecisionTask task = invocation.getArgument(0);
            task.setId(ids.incrementAndGet());
            tasks.put(task.getId(), task);
            return 1;
        });
        when(taskMapper.selectById(any())).thenAnswer(invocation -> tasks.get(invocation.getArgument(0)));
        when(taskMapper.updateById(any(DecisionTask.class))).thenAnswer(invocation -> {
            DecisionTask task = invocation.getArgument(0);
            tasks.put(task.getId(), task);
            return 1;
        });

        evaluationMapper = mock(DecisionEvaluationMapper.class);
        metricStore = mock(MetricStore.class);
        RuntimeProfileService runtimeProfiles = mock(RuntimeProfileService.class);
        audit = mock(OperationAuditService.class);

        MetricValue activeValue = new MetricValue();
        activeValue.setSnapshotId(ACTIVE_SNAPSHOT);
        activeValue.setMetricCode("gmv");
        activeValue.setMetricValue(new BigDecimal("100"));
        activeValue.setPeriod("day:" + LocalDate.now());
        activeValue.setDefinitionVersion("metric-v1");
        when(metricStore.query(any(MetricStore.MetricQuery.class))).thenReturn(List.of(activeValue));

        MetricSnapshot activeSnapshot = new MetricSnapshot();
        activeSnapshot.setSnapshotId(ACTIVE_SNAPSHOT);
        activeSnapshot.setRuntimeProfileId(PROFILE_ID);
        activeSnapshot.setSourceId(SOURCE_ID);
        activeSnapshot.setStatus(MetricSnapshot.STATUS_ACTIVE);
        when(metricStore.findSnapshot(ACTIVE_SNAPSHOT)).thenReturn(activeSnapshot);
        when(metricStore.queryWindow(any(MetricStore.WindowMetricQuery.class))).thenAnswer(invocation -> {
            MetricStore.WindowMetricQuery query = invocation.getArgument(0);
            return List.of(new MetricStore.WindowMetricValue("S-OFFLINE-BASELINE", query.runtimeProfileId(),
                    query.sourceId(), query.metricCode(), new BigDecimal("100"), query.from(),
                    query.definitionVersion(), query.from().atTime(23, 0)));
        });

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(PROFILE_ID);
        profile.setSourceId(SOURCE_ID);
        profile.setStatus(RuntimeProfile.STATUS_ACTIVE);
        when(runtimeProfiles.get(PROFILE_ID)).thenReturn(profile);
        when(runtimeProfiles.findActive()).thenReturn(Optional.of(profile));

        DecisionService decisionService = new DecisionService(taskMapper, evaluationMapper, metricStore,
                runtimeProfiles, audit);
        ReflectionTestUtils.setField(decisionService, "defaultEvalWindowDays", 1);
        ReflectionTestUtils.setField(decisionService, "effectiveThreshold", new BigDecimal("0.05"));
        ReflectionTestUtils.setField(decisionService, "partialThreshold", BigDecimal.ZERO);
        ReflectionTestUtils.setField(decisionService, "evalDefinitionVersion", "offline-window-v1");

        AuthService authService = mock(AuthService.class);
        when(authService.validate(ANALYST_TOKEN)).thenReturn(new CurrentUser(3L, "analyst1", "analyst"));
        when(authService.validate(OPERATOR_TOKEN)).thenReturn(new CurrentUser(4L, "operator1", "operator"));
        mockMvc = MockMvcBuilders.standaloneSetup(new DecisionController(decisionService, audit))
                .addInterceptors(new AuthInterceptor(authService, objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("真实 HTTP + 服务主路径：analyst 建立并提交草稿，operator 批准并冻结基线")
    void analystDraftSubmittedAndApprovedByOperator() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/decisions")
                        .header("Authorization", "Bearer " + ANALYST_TOKEN)
                        .contentType("application/json")
                        .content("""
                                {"title":"提升商城 GMV","action":"优化重点商品详情页",
                                 "targetMetricCode":"gmv","targetDirection":"UP",
                                 "owner":"operator1","evidencePackageId":"EV-OFFLINE-1"}
                                """))
                .andReturn();
        assertStatusAndData(created, 200, DecisionStateMachine.DRAFT);
        JsonNode createdData = responseData(created);
        long decisionId = createdData.path("id").asLong();
        assertThat(decisionId).isEqualTo(41L);
        DecisionTask stored = tasks.get(decisionId);
        assertThat(stored.getCreatedBy()).isEqualTo("analyst1");
        assertThat(stored.getSource()).isEqualTo("ai");

        MvcResult submitted = mockMvc.perform(post("/api/v1/decisions/{id}/submit", decisionId)
                        .header("Authorization", "Bearer " + ANALYST_TOKEN)
                        .contentType("application/json").content("{}"))
                .andReturn();
        assertStatusAndData(submitted, 200, DecisionStateMachine.PENDING_REVIEW);

        MvcResult analystApprove = mockMvc.perform(post("/api/v1/decisions/{id}/approve", decisionId)
                        .header("Authorization", "Bearer " + ANALYST_TOKEN)
                        .contentType("application/json")
                        .content("{\"owner\":\"operator1\",\"dueDate\":\"" + LocalDate.now().plusDays(7)
                                + "\",\"evalWindowDays\":1}"))
                .andReturn();
        assertThat(analystApprove.getResponse().getStatus()).isEqualTo(403);
        assertThat(stored.getStatus()).isEqualTo(DecisionStateMachine.PENDING_REVIEW);

        MvcResult approved = mockMvc.perform(post("/api/v1/decisions/{id}/approve", decisionId)
                        .header("Authorization", "Bearer " + OPERATOR_TOKEN)
                        .contentType("application/json")
                        .content("{\"owner\":\"operator1\",\"dueDate\":\"" + LocalDate.now().plusDays(7)
                                + "\",\"evalWindowDays\":1,\"note\":\"批准试点\"}"))
                .andReturn();
        assertStatusAndData(approved, 200, DecisionStateMachine.APPROVED);
        assertThat(stored.getApprovedBy()).isEqualTo("operator1");
        assertThat(stored.getOwner()).isEqualTo("operator1");
        assertThat(stored.getBaselineValue()).isEqualByComparingTo("100");
        assertThat(stored.getRuntimeProfileId()).isEqualTo(PROFILE_ID);
        assertThat(stored.getSourceId()).isEqualTo(SOURCE_ID);
        assertThat(stored.getDefinitionVersion()).isEqualTo("metric-v1");
        assertThat(stored.getBaselineSnapshotRefs()).isNotBlank();

        ArgumentCaptor<AuditActor> actors = ArgumentCaptor.forClass(AuditActor.class);
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.times(3)).success(
                actors.capture(), any(), any(), any(), any(), any(), any());
        assertThat(actors.getAllValues()).extracting(AuditActor::userId)
                .containsExactly("analyst1", "analyst1", "operator1");
    }

    private void assertStatusAndData(MvcResult result, int status, String expectedState) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(responseData(result).path("status").asText()).isEqualTo(expectedState);
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
