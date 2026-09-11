package com.graduation.analytics.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.graduation.analytics.ai.ExplanationService;
import com.graduation.analytics.ai.TextToSqlService;
import com.graduation.analytics.ai.TextToSqlService.QueryResult;
import com.graduation.analytics.ai.entity.AiQueryHistory;
import com.graduation.analytics.ai.evidence.EvidenceService;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.auth.AuthenticationRequiredException;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.OperationAuditService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 接口身份与审计（R8-3 §3.1/§21.4）：问数必须归属登录用户，审计只落哈希不落原文。
 */
class AiControllerIdentityTest {

    private TextToSqlService textToSqlService;
    private ExplanationService explanationService;
    private EvidenceService evidenceService;
    private AiQueryHistoryMapper queryHistoryMapper;
    private AiCallLogMapper callLogMapper;
    private OperationAuditService audit;
    private AiController controller;

    /**
     * MyBatis-Plus 的 {@code LambdaQueryWrapper} 是惰性的：参数值只有在生成 SQL 片段时才写入
     * {@code paramNameValuePairs}，而列名解析依赖实体的 TableInfo 缓存。这里离线初始化缓存，
     * 使「过滤条件里到底装的是哪个用户名」可以被真实断言（而不是靠 mock 的宽松匹配）。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiQueryHistory.class);
    }

    @BeforeEach
    void setUp() {
        textToSqlService = mock(TextToSqlService.class);
        explanationService = mock(ExplanationService.class);
        evidenceService = mock(EvidenceService.class);
        queryHistoryMapper = mock(AiQueryHistoryMapper.class);
        callLogMapper = mock(AiCallLogMapper.class);
        audit = mock(OperationAuditService.class);
        controller = new AiController(textToSqlService, explanationService, evidenceService,
                queryHistoryMapper, callLogMapper, audit);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    @Test
    @DisplayName("未登录问数 → 401 且不触达 Text2SQL/解释/审计（AI 查询不能匿名）")
    void queryRequiresLogin() {
        assertThrows(AuthenticationRequiredException.class, () -> controller.query(
                new AiController.AiQueryReq("上月支付金额", "近30天"), request()));
        assertThrows(AuthenticationRequiredException.class, () -> controller.analyze(
                new AiController.AiQueryReq("上月支付金额", null), request()));
        assertThrows(AuthenticationRequiredException.class, () -> controller.myHistory(10));
        verifyNoInteractions(textToSqlService, explanationService, evidenceService, audit);
    }

    @Test
    @DisplayName("带 X-User-Id 头但无会话 → 依然 401")
    void headerCannotForgeActor() {
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "admin");
        assertThrows(AuthenticationRequiredException.class, () -> controller.query(
                new AiController.AiQueryReq("问题", null), request));
        verifyNoInteractions(textToSqlService, evidenceService, audit);
    }

    @Test
    @DisplayName("问数身份取会话：query(question, analyst1)，审计 action=AI_QUERY 且摘要无问题原文/SQL 原文")
    void queryUsesSessionIdentityAndHashesContent() {
        CurrentUserHolder.set(new CurrentUser(3L, "analyst1", "analyst"));
        when(textToSqlService.query(anyString(), anyString())).thenReturn(success());
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "admin");

        controller.query(new AiController.AiQueryReq("上月支付金额是多少", "近30天"), request);

        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(textToSqlService).query(questionCaptor.capture(), userIdCaptor.capture());
        assertEquals("analyst1", userIdCaptor.getValue(), "问数归属必须是登录用户");
        assertEquals("上月支付金额是多少", questionCaptor.getValue());

        ArgumentCaptor<AuditActor> actorCaptor = ArgumentCaptor.forClass(AuditActor.class);
        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).success(actorCaptor.capture(), eq(OperationAuditService.ACTION_AI_QUERY),
                eq(OperationAuditService.RESOURCE_AI_QUERY), resourceCaptor.capture(), eq(null),
                digestCaptor.capture(), anyString());
        assertEquals("analyst1", actorCaptor.getValue().userId());
        assertTrue(resourceCaptor.getValue().startsWith("q-"), resourceCaptor.getValue());

        String digest = digestCaptor.getValue();
        assertTrue(digest.contains("snapshot=S1"), digest);
        assertTrue(digest.contains("rows=2"), digest);
        assertFalse(digest.contains("上月支付金额"), "审计摘要不得含问题原文");
        assertFalse(digest.contains("select"), "审计摘要不得含 SQL 原文");
    }

    @Test
    @DisplayName("被治理拒绝的问数记 FAILED 审计行（§21.4 失败也要留痕）")
    void rejectedQueryIsAuditedAsFailure() {
        CurrentUserHolder.set(new CurrentUser(3L, "analyst1", "analyst"));
        QueryResult rejected = new QueryResult("REJECTED", null, List.of("dws_trade_daily"), 0, 3L,
                List.of(), List.of(), "SQL 含多表 JOIN", "SQL_JOIN", "rule-based");
        when(textToSqlService.query(anyString(), anyString())).thenReturn(rejected);

        controller.query(new AiController.AiQueryReq("把所有表 join 一下", null), request());

        verify(audit).failure(any(), eq(OperationAuditService.ACTION_AI_QUERY),
                eq(OperationAuditService.RESOURCE_AI_QUERY), anyString(), eq(null), anyString(), anyString());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("/history/my 只用会话用户名过滤（伪造的 X-User-Id 不进查询条件）")
    void myHistoryFiltersBySessionUser() {
        CurrentUserHolder.set(new CurrentUser(3L, "analyst1", "analyst"));
        when(queryHistoryMapper.selectList(any())).thenReturn(List.of());
        MockHttpServletRequest request = request();
        request.addHeader("X-User-Id", "admin");

        assertEquals("OK", controller.myHistory(10).code());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AiQueryHistory>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(queryHistoryMapper).selectList(captor.capture());
        // MP 的条件值是惰性写入的：先产出一次 SQL 片段，参数表才会被填充
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"), "过滤列应为 user_id：" + sql);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("analyst1"),
                "过滤条件应包含登录用户名：" + captor.getValue().getParamNameValuePairs());
        assertFalse(captor.getValue().getParamNameValuePairs().containsValue("admin"),
                "伪造的用户名不得进入过滤条件");
    }

    @Test
    @DisplayName("问数快照锚点取证据包（不写 \"unknown\" 占位值）")
    void snapshotAnchorComesFromEvidencePackage() {
        CurrentUserHolder.set(new CurrentUser(3L, "analyst1", "analyst"));
        when(textToSqlService.query(anyString(), anyString())).thenReturn(success());
        when(evidenceService.build(any())).thenReturn(EvidenceTestFixtures.packageOf("S20260901_24"));

        AiController.AiQueryResp resp = controller.query(
                new AiController.AiQueryReq("上月支付金额是多少", "2026-09-01"), request()).data();

        // 证据包钉住的快照优先于结果行里的 snapshot_id（成功结果行里是 S1）
        verify(explanationService).explain(any(), eq("S20260901_24"), anyString(), anyString());
        assertEquals("EV-20260901-abc123", resp.evidenceId());
        assertTrue(resp.evidenceSummary().contains("快照 S20260901_24"), resp.evidenceSummary());
        assertTrue(resp.evidenceSummary().contains("2 个指标"), resp.evidenceSummary());
        assertFalse(resp.evidenceSummary().contains("unknown"), "不得出现 unknown 占位值");
    }

    @Test
    @DisplayName("无 ACTIVE 快照且结果行无 snapshot_id → 锚点为 null + 告警，不写 unknown")
    void missingSnapshotStaysNull() {
        CurrentUserHolder.set(new CurrentUser(3L, "analyst1", "analyst"));
        QueryResult noSnapshot = new QueryResult("OK", "select 1", List.of("ads_trade_overview"), 1, 5L,
                List.of(Map.of("value", 1)), List.of(), null, null, "rule-based");
        when(textToSqlService.query(anyString(), anyString())).thenReturn(noSnapshot);
        when(evidenceService.build(any())).thenReturn(null);

        AiController.AiQueryResp resp = controller.query(
                new AiController.AiQueryReq("问题", null), request()).data();

        verify(explanationService).explain(any(), isNull(), anyString(), anyString());
        assertNull(resp.evidenceId());
        assertNull(resp.evidenceSummary());
        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        verify(audit).success(any(), eq(OperationAuditService.ACTION_AI_QUERY),
                eq(OperationAuditService.RESOURCE_AI_QUERY), anyString(), eq(null),
                digestCaptor.capture(), anyString());
        assertTrue(digestCaptor.getValue().contains("snapshot=null"), digestCaptor.getValue());
        assertFalse(digestCaptor.getValue().contains("unknown"), digestCaptor.getValue());
    }

    /** 手搓证据包（真实 record）见 {@link EvidenceTestFixtures} */
    private QueryResult success() {
        return new QueryResult("OK", "select snapshot_id from ads_trade_overview limit 10",
                List.of("ads_trade_overview"), 2, 12L,
                List.of(Map.of("snapshot_id", "S1", "value", 123)), List.of(), null, null, "rule-based");
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/ai/queries");
    }
}
