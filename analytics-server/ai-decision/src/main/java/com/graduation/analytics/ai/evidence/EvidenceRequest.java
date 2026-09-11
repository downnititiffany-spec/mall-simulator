package com.graduation.analytics.ai.evidence;

/**
 * 证据包构建请求（R8-1 契约 §1）。
 *
 * @param snapshotId  指定快照；null → 取 ACTIVE（不指定归档快照，避免无权限的历史读取）
 * @param question    触发本次分析的自然语言问题（可空；仅用于解释措辞，不影响取数）
 * @param timeRange   期间：{@code yyyy-MM-dd} 或 {@code from~to}；null → 快照业务日
 * @param requestedBy 请求人（审计归属，来自登录态，不接受请求头伪造）
 */
public record EvidenceRequest(String snapshotId, String question, String timeRange, String requestedBy) {

    /** 无参请求：用于“当前经营状况”类通用解释（ACTIVE 快照 + 快照业务日） */
    public static EvidenceRequest latest(String requestedBy) {
        return new EvidenceRequest(null, null, null, requestedBy);
    }
}
