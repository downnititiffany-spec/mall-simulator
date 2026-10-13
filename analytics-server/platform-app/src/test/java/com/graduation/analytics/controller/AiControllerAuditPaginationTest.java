package com.graduation.analytics.controller;

import com.graduation.analytics.ai.mapper.AiCallLogMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.ai.ExplanationService;
import com.graduation.analytics.ai.AiFeedbackService;
import com.graduation.analytics.ai.TextToSqlService;
import com.graduation.analytics.ai.evidence.EvidenceService;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiControllerAuditPaginationTest {
    private AiQueryHistoryMapper historyMapper;
    private AiCallLogMapper callMapper;
    private AiController controller;

    @BeforeEach
    void setUp() {
        historyMapper = mock(AiQueryHistoryMapper.class);
        callMapper = mock(AiCallLogMapper.class);
        controller = new AiController(mock(TextToSqlService.class), mock(ExplanationService.class),
                mock(EvidenceService.class), historyMapper, callMapper, mock(OperationAuditService.class),
                mock(AiFeedbackService.class));
    }

    @Test
    void bothAuditPagesReturnMetadataAndUseBoundedQueries() {
        when(historyMapper.selectCount(any())).thenReturn(21L);
        when(callMapper.selectCount(any())).thenReturn(0L);

        var history = controller.auditHistoryPage(2, 10, "createdAt,desc").data();
        var calls = controller.auditCallsPage(1, 20, "id,desc").data();

        assertEquals(2, history.page());
        assertEquals(10, history.size());
        assertEquals(21, history.total());
        assertEquals(3, history.totalPages());
        assertEquals("createdat,desc", history.sort());
        assertEquals(0, calls.total());
        assertEquals(1, calls.totalPages());
        verify(historyMapper).selectList(any());
        verify(callMapper).selectList(any());
    }

    @Test
    void rejectsUnsupportedSortAndUnboundedPageSize() {
        assertThrows(PlatformBizException.class, () -> controller.auditHistoryPage(1, 101, "id,desc"));
        assertThrows(PlatformBizException.class, () -> controller.auditCallsPage(1, 10, "createdAt desc;drop table"));
    }
}
