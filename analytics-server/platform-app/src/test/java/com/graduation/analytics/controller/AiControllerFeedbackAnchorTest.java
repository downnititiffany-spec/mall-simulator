package com.graduation.analytics.controller;

import com.graduation.analytics.ai.AiFeedbackService;
import com.graduation.analytics.ai.ExplanationService;
import com.graduation.analytics.ai.TextToSqlService;
import com.graduation.analytics.ai.TextToSqlService.QueryResult;
import com.graduation.analytics.ai.entity.AiQueryHistory;
import com.graduation.analytics.ai.evidence.EvidencePackage;
import com.graduation.analytics.ai.evidence.EvidenceService;
import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.decision.OperationAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiControllerFeedbackAnchorTest {
    @AfterEach
    void clearUser() {
        CurrentUserHolder.clear();
    }

    @Test
    void executedQueryPersistsOnlyMatchingEvidenceAnchorForSessionOwner() {
        CurrentUserHolder.set(new CurrentUser(3L, "analyst1", "analyst"));
        TextToSqlService queryService = mock(TextToSqlService.class);
        ExplanationService explanationService = mock(ExplanationService.class);
        EvidenceService evidenceService = mock(EvidenceService.class);
        AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
        QueryResult result = new QueryResult("EXECUTED", "select value from ads limit 1", List.of("ads"),
                1, 10L, List.of(Map.of("value", 1)), List.of(), null, null, "rule-based",
                null, "S1", 22L, 77L);
        when(queryService.query("问题", "analyst1", null)).thenReturn(result);
        when(evidenceService.build(any())).thenReturn(evidence("S1", 22L));

        AiController controller = new AiController(queryService, explanationService, evidenceService,
                historyMapper, mock(AiCallLogMapper.class), mock(OperationAuditService.class),
                mock(AiFeedbackService.class));
        AiController.AiQueryResp response = controller.query(
                new AiController.AiQueryReq("问题", null), new MockHttpServletRequest()).data();
        assertThat(response.query().historyId()).isEqualTo(77L);
        assertThat(response.evidenceId()).isEqualTo("EV-20261010-abc123");

        ArgumentCaptor<AiQueryHistory> captor = ArgumentCaptor.forClass(AiQueryHistory.class);
        verify(historyMapper).updateById(captor.capture());
        AiQueryHistory update = captor.getValue();
        assertThat(update.getId()).isEqualTo(77L);
        assertThat(update.getUserId()).isEqualTo("analyst1");
        assertThat(update.getSourceId()).isEqualTo(22L);
        assertThat(update.getSnapshotId()).isEqualTo("S1");
        assertThat(update.getDefinitionVersion()).isEqualTo("d3");
        assertThat(update.getEvidenceId()).isEqualTo("EV-20261010-abc123");
    }

    private static EvidencePackage evidence(String snapshotId, Long sourceId) {
        return new EvidencePackage("EV-20261010-abc123", "evidence_v1", snapshotId, "d3",
                "2026-10-10T00:00:00", new EvidencePackage.Period("2026-10-10", "2026-10-10"),
                new EvidencePackage.Period("2026-10-10", "2026-10-10"), null,
                List.of(), List.of(), Map.of(), List.of(), EvidencePackage.DataQuality.unknown("test"),
                new EvidencePackage.Lineage(List.of(), List.of(), 1L, snapshotId, sourceId), List.of());
    }
}
