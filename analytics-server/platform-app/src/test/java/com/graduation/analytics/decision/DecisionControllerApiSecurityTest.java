package com.graduation.analytics.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.controller.DecisionController;
import com.graduation.analytics.controller.PlatformExceptionAdvice;
import com.graduation.analytics.decision.entity.DecisionTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * DB-free HTTP security slice for the decision API. Uses the real AuthInterceptor and controller;
 * only authentication lookup and decision/audit services are test doubles.
 */
class DecisionControllerApiSecurityTest {

    private static final String ANALYST_TOKEN = "analyst-token";
    private static final String OPERATOR_TOKEN = "operator-token";

    private DecisionService decisionService;
    private OperationAuditService audit;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthService authService = mock(AuthService.class);
        when(authService.validate(ANALYST_TOKEN)).thenReturn(new CurrentUser(3L, "analyst1", "analyst"));
        when(authService.validate(OPERATOR_TOKEN)).thenReturn(new CurrentUser(4L, "operator1", "operator"));

        decisionService = mock(DecisionService.class);
        audit = mock(OperationAuditService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DecisionController(decisionService, audit))
                .addInterceptors(new AuthInterceptor(authService, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    @DisplayName("analyst cannot approve or reject: HTTP 403 and no service/audit side effects")
    void analystCannotApproveOrReject() throws Exception {
        MvcResult approve = mockMvc.perform(post("/api/v1/decisions/42/approve")
                        .header("Authorization", "Bearer " + ANALYST_TOKEN)
                        .contentType("application/json")
                        .content("{\"owner\":\"alice\",\"dueDate\":\"2026-10-01\"}"))
                .andReturn();
        MvcResult reject = mockMvc.perform(post("/api/v1/decisions/42/reject")
                        .header("Authorization", "Bearer " + ANALYST_TOKEN)
                        .contentType("application/json")
                        .content("{\"reason\":\"not approved\"}"))
                .andReturn();

        assertForbiddenFor(approve, "decision:approve");
        assertForbiddenFor(reject, "decision:approve");
        verifyNoInteractions(decisionService, audit);
    }

    @Test
    @DisplayName("creator/approver identity comes from authenticated session, not payload or spoofed identity headers")
    void payloadCannotForgeCreatorOrApprover() throws Exception {
        DecisionTask created = new DecisionTask();
        created.setId(11L);
        when(decisionService.createDraft(any(), any(), eq("ai"))).thenReturn(created);
        when(decisionService.approve(eq(11L), any(), any())).thenReturn(new DecisionTask());

        MvcResult create = mockMvc.perform(post("/api/v1/decisions")
                        .header("Authorization", "Bearer " + ANALYST_TOKEN)
                        .header("X-User-Id", "admin")
                        .header("X-Username", "admin")
                        .contentType("application/json")
                        .content("""
                                {"title":"test","action":"adjust","targetMetricCode":"gmv",
                                 "targetDirection":"UP","owner":"alice","createdBy":"mallory",
                                 "approvedBy":"mallory","actor":"mallory","userId":"mallory"}
                                """))
                .andReturn();
        MvcResult approve = mockMvc.perform(post("/api/v1/decisions/11/approve")
                        .header("Authorization", "Bearer " + OPERATOR_TOKEN)
                        .header("X-User-Id", "mallory")
                        .contentType("application/json")
                        .content("""
                                {"owner":"alice","dueDate":"2026-10-01","targetValue":100,
                                 "evalWindowDays":3,"approvedBy":"mallory","actor":"mallory"}
                                """))
                .andReturn();

        assertThat(create.getResponse().getStatus()).isEqualTo(200);
        assertThat(approve.getResponse().getStatus()).isEqualTo(200);

        org.mockito.ArgumentCaptor<AuditActor> actor = org.mockito.ArgumentCaptor.forClass(AuditActor.class);
        verify(decisionService).createDraft(any(), actor.capture(), eq("ai"));
        assertThat(actor.getValue().userId()).isEqualTo("analyst1");
        assertThat(actor.getValue().role()).isEqualTo("analyst");

        verify(decisionService).approve(eq(11L), any(), actor.capture());
        assertThat(actor.getValue().userId()).isEqualTo("operator1");
        assertThat(actor.getValue().role()).isEqualTo("operator");
    }

    @Test
    @DisplayName("invalid decision state is a stable HTTP 400 error, not an internal 500")
    void illegalStateIsMappedToBadRequest() throws Exception {
        when(decisionService.start(eq(42L), any()))
                .thenThrow(new DecisionStateMachine.IllegalDecisionStateException("DRAFT", "IN_PROGRESS"));

        MvcResult result = mockMvc.perform(post("/api/v1/decisions/42/start")
                        .header("Authorization", "Bearer " + ANALYST_TOKEN))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("DECISION_STATE_ILLEGAL");
        verify(audit).failure(any(), eq(OperationAuditService.ACTION_DECISION_START),
                eq(OperationAuditService.RESOURCE_DECISION_TASK), eq("42"), eq(null), eq(null), any());
    }

    @Test
    @DisplayName("cross-source evidence on draft creation maps to HTTP 409 and failure audit")
    void sourceMismatchOnCreateIsConflictAndAudited() throws Exception {
        when(decisionService.createDraft(any(), any(), eq("ai")))
                .thenThrow(new PlatformBizException(PlatformBizException.SOURCE_MISMATCH,
                        "evidence source does not match active source"));

        MvcResult result = mockMvc.perform(post("/api/v1/decisions")
                        .header("Authorization", "Bearer " + ANALYST_TOKEN)
                        .contentType("application/json")
                        .content("""
                                {"title":"cross-source","action":"adjust","targetMetricCode":"gmv",
                                 "targetDirection":"UP","owner":"alice"}
                                """))
                .andReturn();

        assertConflict(result);
        verify(decisionService).createDraft(any(), any(), eq("ai"));
        verify(audit).failure(any(), eq(OperationAuditService.ACTION_DECISION_CREATE),
                eq(OperationAuditService.RESOURCE_DECISION_TASK), eq(null), eq(null), eq(null),
                eq("evidence source does not match active source"));
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(audit);
    }

    @Test
    @DisplayName("cross-source evidence on submit maps to HTTP 409 and failure audit")
    void sourceMismatchOnSubmitIsConflictAndAudited() throws Exception {
        when(decisionService.submit(eq(42L), any(), any()))
                .thenThrow(new PlatformBizException(PlatformBizException.SOURCE_MISMATCH,
                        "evidence source does not match active source"));

        MvcResult result = mockMvc.perform(post("/api/v1/decisions/42/submit")
                        .header("Authorization", "Bearer " + ANALYST_TOKEN)
                        .contentType("application/json")
                        .content("{}"))
                .andReturn();

        assertConflict(result);
        verify(decisionService).submit(eq(42L), any(), any());
        verify(audit).failure(any(), eq(OperationAuditService.ACTION_DECISION_SUBMIT),
                eq(OperationAuditService.RESOURCE_DECISION_TASK), eq("42"), eq(null), eq(null),
                eq("evidence source does not match active source"));
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(audit);
    }

    private static void assertConflict(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains(PlatformBizException.SOURCE_MISMATCH);
    }

    private static void assertForbiddenFor(MvcResult result, String permission) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("FORBIDDEN_PERMISSION", permission);
    }
}
