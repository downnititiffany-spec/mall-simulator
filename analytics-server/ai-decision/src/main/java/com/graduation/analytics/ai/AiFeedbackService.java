package com.graduation.analytics.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.ai.entity.AiFeedback;
import com.graduation.analytics.ai.entity.AiQueryHistory;
import com.graduation.analytics.ai.mapper.AiFeedbackMapper;
import com.graduation.analytics.ai.mapper.AiQueryHistoryMapper;
import com.graduation.analytics.common.PlatformBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiFeedbackService {
    public static final String REASON_CODE_VERSION = "v1";
    public static final Set<String> REASON_CODES = Set.of("ANSWER_MATCHED_EVIDENCE", "MISSING_EVIDENCE",
            "FACT_OR_SCOPE_INCORRECT", "EXPLANATION_UNCLEAR", "OTHER");

    private final AiQueryHistoryMapper historyMapper;
    private final AiFeedbackMapper feedbackMapper;

    @Transactional
    public AiFeedback submit(long historyId, String userId, String rating, String reasonCode) {
        String normalizedRating = rating == null ? "" : rating.trim().toUpperCase(Locale.ROOT);
        String normalizedReason = reasonCode == null ? "" : reasonCode.trim().toUpperCase(Locale.ROOT);
        if (!("GOOD".equals(normalizedRating) || "BAD".equals(normalizedRating))
                || !REASON_CODES.contains(normalizedReason)) {
            throw new PlatformBizException("AI_FEEDBACK_INVALID", "rating 或 reasonCode 不受支持");
        }

        AiQueryHistory history = historyMapper.selectOne(new LambdaQueryWrapper<AiQueryHistory>()
                .eq(AiQueryHistory::getId, historyId)
                .eq(AiQueryHistory::getUserId, userId)
                .eq(AiQueryHistory::getStatus, "EXECUTED")
                .isNotNull(AiQueryHistory::getSourceId)
                .isNotNull(AiQueryHistory::getSnapshotId)
                .isNotNull(AiQueryHistory::getDefinitionVersion)
                .isNotNull(AiQueryHistory::getEvidenceId));
        if (history == null) {
            throw new PlatformBizException("AI_FEEDBACK_HISTORY_UNAVAILABLE",
                    "该历史记录不可反馈或缺少有效证据锚点");
        }

        AiFeedback feedback = new AiFeedback();
        feedback.setHistoryId(history.getId());
        feedback.setUserId(userId);
        feedback.setRating(normalizedRating);
        feedback.setReasonCode(normalizedReason);
        feedback.setReasonCodeVersion(REASON_CODE_VERSION);
        feedback.setSourceId(history.getSourceId());
        feedback.setSnapshotId(history.getSnapshotId());
        feedback.setDefinitionVersion(history.getDefinitionVersion());
        feedback.setEvidenceId(history.getEvidenceId());
        feedback.setCreatedAt(LocalDateTime.now());
        try {
            feedbackMapper.insert(feedback);
        } catch (DuplicateKeyException duplicate) {
            throw new AiFeedbackConflictException();
        }
        return feedback;
    }

    public Set<Long> submittedHistoryIds(String userId, List<Long> historyIds) {
        if (historyIds == null || historyIds.isEmpty()) return Set.of();
        return feedbackMapper.selectList(new LambdaQueryWrapper<AiFeedback>()
                        .select(AiFeedback::getHistoryId)
                        .eq(AiFeedback::getUserId, userId)
                        .in(AiFeedback::getHistoryId, historyIds))
                .stream().map(AiFeedback::getHistoryId).collect(java.util.stream.Collectors.toSet());
    }
}
