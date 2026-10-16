package com.graduation.analytics.ai;

import com.graduation.analytics.ai.entity.AiFeedback;
import com.graduation.analytics.ai.entity.AiQueryHistory;
import com.graduation.analytics.ai.mapper.AiFeedbackMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.common.PlatformBizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiFeedbackServiceTest {
    private final AiQueryHistoryMapper historyMapper = mock(AiQueryHistoryMapper.class);
    private final AiFeedbackMapper feedbackMapper = mock(AiFeedbackMapper.class);
    private final AiFeedbackService service = new AiFeedbackService(historyMapper, feedbackMapper);

    @Test
    void submitPersistsOnlyServerDerivedOwnerAndEvidenceLineage() {
        AiQueryHistory history = history(42L, "user-7");
        when(historyMapper.selectOne(any())).thenReturn(history);
        doAnswer(invocation -> {
            AiFeedback row = invocation.getArgument(0);
            row.setId(91L);
            return 1;
        }).when(feedbackMapper).insert(any(AiFeedback.class));

        AiFeedback result = service.submit(42L, "user-7", "GOOD", "ANSWER_MATCHED_EVIDENCE");

        ArgumentCaptor<AiFeedback> captor = ArgumentCaptor.forClass(AiFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        AiFeedback saved = captor.getValue();
        assertThat(result.getId()).isEqualTo(91L);
        assertThat(saved.getHistoryId()).isEqualTo(42L);
        assertThat(saved.getUserId()).isEqualTo("user-7");
        assertThat(saved.getReasonCodeVersion()).isEqualTo("v1");
        assertThat(saved.getSourceId()).isEqualTo(12L);
        assertThat(saved.getSnapshotId()).isEqualTo("S20261010_01");
        assertThat(saved.getDefinitionVersion()).isEqualTo("d3");
        assertThat(saved.getEvidenceId()).isEqualTo("EV-20261010-abc123");
    }

    @Test
    void unavailableOrUnownedHistoryFailsClosed() {
        when(historyMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.submit(42L, "other-user", "BAD", "MISSING_EVIDENCE"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("不可反馈");

        verifyNoInteractions(feedbackMapper);
    }

    @Test
    void unsupportedRatingOrReasonCodeIsRejectedBeforeReadingHistory() {
        assertThatThrownBy(() -> service.submit(42L, "user-7", "NEUTRAL", "OTHER"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("不受支持");
        verifyNoInteractions(historyMapper, feedbackMapper);
    }

    @Test
    void duplicateFeedbackIsMappedToStableConflict() {
        when(historyMapper.selectOne(any())).thenReturn(history(42L, "user-7"));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("private database text"))
                .when(feedbackMapper).insert(any(AiFeedback.class));

        assertThatThrownBy(() -> service.submit(42L, "user-7", "BAD", "FACT_OR_SCOPE_INCORRECT"))
                .isInstanceOf(AiFeedbackConflictException.class)
                .hasMessage("该问答历史已经提交过反馈");
    }

    private static AiQueryHistory history(long id, String owner) {
        AiQueryHistory row = new AiQueryHistory();
        row.setId(id);
        row.setUserId(owner);
        row.setStatus("EXECUTED");
        row.setSourceId(12L);
        row.setSnapshotId("S20261010_01");
        row.setDefinitionVersion("d3");
        row.setEvidenceId("EV-20261010-abc123");
        return row;
    }
}
