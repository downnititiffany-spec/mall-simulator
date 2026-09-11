package com.graduation.analytics.controller;

import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.AuditActor;
import com.graduation.analytics.decision.OperationAuditService;
import com.graduation.analytics.source.SourceAuditActions;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceChangeOutcome;
import com.graduation.analytics.source.dto.SourceCheckResult;
import com.graduation.analytics.source.dto.SourceRegistryCreateReq;
import com.graduation.analytics.source.dto.SourceRegistryUpdateReq;
import com.graduation.analytics.source.dto.SourceRegistryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P1-03 审计（DoD：每次变更都有审计行）。审计在**控制器层**写，因为
 * {@code connection-ingestion} 不依赖 {@code ai-decision}（{@link OperationAuditService} 的属主），
 * 这与既有的 {@code DecisionController}/{@code UserAdminController} 同构。
 *
 * <p>本类钉住三条规则：</p>
 * <ol>
 *   <li>成功的**真实变更** → 恰好 1 行 SUCCESS；</li>
 *   <li>失败（含被拒绝的尝试）→ 恰好 1 行 FAILED，且 reason 里带稳定错误码；</li>
 *   <li>幂等空操作（{@code changed=false}）与只读 {@code test} → **不写任何审计行**
 *       （D-035 裁决 4：不重复写「绑定」审计）。</li>
 * </ol>
 *
 * <p><b>如实登记的边界</b>：控制器层审计在「服务事务提交」与「审计落库」之间有极小窗口，
 * 若进程在两者之间崩溃，变更已生效但审计缺失。这与既有决策/用户控制器完全一致，
 * 若要闭合需要把审计写进服务事务（会引入 connection-ingestion → ai-decision 的新依赖），
 * 属本任务未采纳的方案。</p>
 */
class SourceRegistryControllerAuditTest {

    private SourceRegistryService service;
    private OperationAuditService audit;
    private SourceRegistryController controller;

    @BeforeEach
    void setUp() {
        service = mock(SourceRegistryService.class);
        audit = mock(OperationAuditService.class);
        controller = new SourceRegistryController(service, audit);
        CurrentUserHolder.set(new CurrentUser(1L, "admin", "admin"));
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/sources");
    }

    private static SourceRegistryView view(Long id, String code, String status, boolean current) {
        return new SourceRegistryView(id, code, "参考商城（源 A）", "FILE",
                "analytics-server/source-profiles/" + code + ".v1.json",
                "Asia/Shanghai", "CNY", status, "1.0", current,
                LocalDateTime.of(2026, 9, 11, 10, 0), LocalDateTime.of(2026, 9, 11, 10, 0));
    }

    // ---------------- create ----------------

    @Test
    @DisplayName("create 成功：1 行 SUCCESS 审计，resourceId=新 id，afterDigest 非空、beforeDigest 为 null")
    void createSuccessWritesOneAuditRow() {
        SourceRegistryView created = view(9L, "p1-03-probe-1", "DRAFT", false);
        when(service.create(any())).thenReturn(new SourceChangeOutcome(created, null, true));

        ApiResponse<SourceRegistryView> response = controller.create(
                new SourceRegistryCreateReq("p1-03-probe-1", "探针", "FILE",
                        "analytics-server/source-profiles/p1-03-probe-1.v1.json",
                        "Asia/Shanghai", "CNY", null, "1.0"), request());

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.data()).isSameAs(created);

        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AuditActor> actor = ArgumentCaptor.forClass(AuditActor.class);
        verify(audit).success(actor.capture(), eq(SourceAuditActions.ACTION_SOURCE_CREATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("9"), isNull(), after.capture(), any());
        assertThat(actor.getValue().userId()).isEqualTo("admin");
        assertThat(actor.getValue().traceId()).isEqualTo(response.traceId());
        assertThat(after.getValue()).contains("sourceCode=p1-03-probe-1").contains("status=DRAFT");
    }

    @Test
    @DisplayName("create 失败：1 行 FAILED 审计（resourceId=null，尚无 id）且原异常照抛")
    void createFailureWritesFailedRowAndRethrows() {
        PlatformBizException boom = new PlatformBizException(PlatformBizException.PARAM_INVALID, "source_code 格式非法");
        when(service.create(any())).thenThrow(boom);

        assertThatThrownBy(() -> controller.create(
                new SourceRegistryCreateReq("BAD CODE", "探针", "FILE", "a/b.json",
                        "Asia/Shanghai", "CNY", null, "1.0"), request()))
                .isSameAs(boom);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).failure(any(), eq(SourceAuditActions.ACTION_SOURCE_CREATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), isNull(), isNull(), isNull(), reason.capture());
        assertThat(reason.getValue()).startsWith("PARAM_INVALID").contains("source_code 格式非法");
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("审计自身写失败：只记日志，原始业务异常照抛（不掩盖真问题）")
    void auditFailureDoesNotMaskBusinessError() {
        PlatformBizException boom = new PlatformBizException(PlatformBizException.SOURCE_PROFILE_INVALID, "画像缺失");
        when(service.activate(eq(3L))).thenThrow(boom);
        doThrow(new RuntimeException("audit db down")).when(audit)
                .failure(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> controller.activate(3L, request())).isSameAs(boom);
    }

    // ---------------- update ----------------

    @Test
    @DisplayName("update 成功：审计带 before/after 摘要，resourceId=被改行的 id")
    void updateSuccessCarriesBothDigests() {
        SourceRegistryView before = view(2L, "p1-03-probe-1", "DRAFT", false);
        SourceRegistryView after = view(2L, "p1-03-probe-1", "DRAFT", false);
        when(service.update(eq(2L), any())).thenReturn(new SourceChangeOutcome(after, before, true));

        controller.update(2L, new SourceRegistryUpdateReq(null, "改名", null, null, null, null, null, null),
                request());

        ArgumentCaptor<String> digests = ArgumentCaptor.forClass(String.class);
        verify(audit).success(any(), eq(SourceAuditActions.ACTION_SOURCE_UPDATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("2"), digests.capture(), digests.capture(), any());
        assertThat(digests.getAllValues()).hasSize(2).allSatisfy(d -> assertThat(d).contains("sourceCode=p1-03-probe-1"));
    }

    @Test
    @DisplayName("摘要必须覆盖全部可变更字段：只改 displayName/timezone 时 before/after 不得相同")
    void digestCoversEveryMutableField() {
        // 真机 E3 实测：摘要只含 id/sourceCode/status/current/profileVersion/profilePath 时，
        // 一次「只改显示名与时区」的 PUT 会写出 before==after 的审计行——留了行却看不出改了什么。
        SourceRegistryView before = new SourceRegistryView(2L, "p1-03-probe-1", "旧名", "FILE",
                "analytics-server/source-profiles/p1-03-probe-1.v1.json", "Asia/Shanghai", "CNY",
                "DRAFT", "1.0", false, null, null);
        SourceRegistryView after = new SourceRegistryView(2L, "p1-03-probe-1", "新名", "FILE",
                "analytics-server/source-profiles/p1-03-probe-1.v1.json", "UTC", "CNY",
                "DRAFT", "1.0", false, null, null);
        when(service.update(eq(2L), any())).thenReturn(new SourceChangeOutcome(after, before, true));

        controller.update(2L, new SourceRegistryUpdateReq(null, "新名", null, null, "UTC", null, null, null),
                request());

        ArgumentCaptor<String> digests = ArgumentCaptor.forClass(String.class);
        verify(audit).success(any(), eq(SourceAuditActions.ACTION_SOURCE_UPDATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("2"), digests.capture(), digests.capture(), any());
        assertThat(digests.getAllValues()).hasSize(2);
        assertThat(digests.getAllValues().get(0)).isNotEqualTo(digests.getAllValues().get(1));
        assertThat(digests.getAllValues().get(0)).contains("displayName=旧名", "timezone=Asia/Shanghai");
        assertThat(digests.getAllValues().get(1)).contains("displayName=新名", "timezone=UTC");
        // 摘要里不能出现凭据字样（源登记本来也没有凭据列，这里是防回归）
        assertThat(digests.getAllValues()).allSatisfy(d ->
                assertThat(d).doesNotContainIgnoringCase("password", "secret", "token", "credential"));
    }

    @Test
    @DisplayName("update 改 source_code：审计 reason 带 SOURCE_CODE_IMMUTABLE，且不改任何行")
    void immutableCodeFailureIsAuditedWithStableCode() {
        when(service.update(eq(2L), any())).thenThrow(new PlatformBizException(
                PlatformBizException.SOURCE_CODE_IMMUTABLE, "source_code 创建后不可修改"));

        assertThatThrownBy(() -> controller.update(2L,
                new SourceRegistryUpdateReq("renamed", null, null, null, null, null, null, null), request()))
                .isInstanceOf(PlatformBizException.class);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).failure(any(), eq(SourceAuditActions.ACTION_SOURCE_UPDATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("2"), isNull(), isNull(), reason.capture());
        assertThat(reason.getValue()).startsWith("SOURCE_CODE_IMMUTABLE");
    }

    @Test
    @DisplayName("update 目标不存在：SOURCE_NOT_FOUND 也留 FAILED 行")
    void updateMissingSourceIsAudited() {
        when(service.update(eq(404L), any())).thenThrow(new PlatformBizException(
                PlatformBizException.SOURCE_NOT_FOUND, "源不存在: 404"));

        assertThatThrownBy(() -> controller.update(404L,
                new SourceRegistryUpdateReq(null, "x", null, null, null, null, null, null), request()))
                .isInstanceOf(PlatformBizException.class);

        verify(audit).failure(any(), eq(SourceAuditActions.ACTION_SOURCE_UPDATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("404"), isNull(), isNull(), any());
    }

    // ---------------- activate / pause ----------------

    @Test
    @DisplayName("activate 真实变更：恰好 1 行 SUCCESS，resourceId=源 id")
    void activateChangeWritesOneRow() {
        SourceRegistryView before = view(4L, "p1-03-probe-1", "DRAFT", false);
        SourceRegistryView after = view(4L, "p1-03-probe-1", "ACTIVE", true);
        when(service.activate(4L)).thenReturn(new SourceChangeOutcome(after, before, true));

        ApiResponse<SourceRegistryView> response = controller.activate(4L, request());

        assertThat(response.data().status()).isEqualTo("ACTIVE");
        ArgumentCaptor<String> beforeDigest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> afterDigest = ArgumentCaptor.forClass(String.class);
        verify(audit).success(any(), eq(SourceAuditActions.ACTION_SOURCE_ACTIVATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("4"),
                beforeDigest.capture(), afterDigest.capture(), any());
        assertThat(beforeDigest.getValue()).contains("current=false");
        assertThat(afterDigest.getValue()).contains("current=true");
    }

    @Test
    @DisplayName("activate 幂等（changed=false）：成功返回但不写任何审计行（D-035 裁决 4）")
    void idempotentActivateWritesNoAuditRow() {
        SourceRegistryView already = view(4L, "p1-03-probe-1", "ACTIVE", true);
        when(service.activate(4L)).thenReturn(new SourceChangeOutcome(already, already, false));

        ApiResponse<SourceRegistryView> response = controller.activate(4L, request());

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.data().current()).isTrue();
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("activate 画像非法：FAILED 行 + 原异常照抛（种子源的真实预期）")
    void activateProfileInvalidIsAuditedAsFailed() {
        when(service.activate(eq(1L))).thenThrow(new PlatformBizException(
                PlatformBizException.SOURCE_PROFILE_INVALID, "画像文件不存在: analytics-server/source-profiles/mock-mall.v1.json"));

        assertThatThrownBy(() -> controller.activate(1L, request())).isInstanceOf(PlatformBizException.class);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).failure(any(), eq(SourceAuditActions.ACTION_SOURCE_ACTIVATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("1"), isNull(), isNull(), reason.capture());
        assertThat(reason.getValue()).startsWith("SOURCE_PROFILE_INVALID")
                .contains("analytics-server/source-profiles/mock-mall.v1.json");
    }

    @Test
    @DisplayName("activate 无 ACTIVE 运行环境：PARAM_INVALID 也留 FAILED 行")
    void activateWithoutActiveProfileIsAudited() {
        when(service.activate(eq(5L))).thenThrow(new PlatformBizException(
                PlatformBizException.PARAM_INVALID, "尚无 ACTIVE 运行环境，无法绑定当前源"));

        assertThatThrownBy(() -> controller.activate(5L, request())).isInstanceOf(PlatformBizException.class);

        verify(audit).failure(any(), eq(SourceAuditActions.ACTION_SOURCE_ACTIVATE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("5"), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("pause 当前源：SOURCE_IN_USE 留 FAILED 行，审计 reason 带稳定错误码")
    void pauseInUseIsAuditedAsFailed() {
        when(service.pause(eq(1L))).thenThrow(new PlatformBizException(
                PlatformBizException.SOURCE_IN_USE, "该源是当前激活源，暂停前请先切换当前源"));

        assertThatThrownBy(() -> controller.pause(1L, request())).isInstanceOf(PlatformBizException.class);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).failure(any(), eq(SourceAuditActions.ACTION_SOURCE_PAUSE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("1"), isNull(), isNull(), reason.capture());
        assertThat(reason.getValue()).startsWith("SOURCE_IN_USE");
    }

    @Test
    @DisplayName("pause 非当前源：1 行 SUCCESS 审计")
    void pauseSuccessWritesOneRow() {
        SourceRegistryView before = view(6L, "p1-03-probe-2", "DRAFT", false);
        SourceRegistryView after = view(6L, "p1-03-probe-2", "PAUSED", false);
        when(service.pause(6L)).thenReturn(new SourceChangeOutcome(after, before, true));

        ApiResponse<SourceRegistryView> response = controller.pause(6L, request());

        assertThat(response.data().status()).isEqualTo("PAUSED");
        verify(audit).success(any(), eq(SourceAuditActions.ACTION_SOURCE_PAUSE),
                eq(SourceAuditActions.RESOURCE_SOURCE_REGISTRY), eq("6"), any(), any(), any());
    }

    // ---------------- 只读端点不留痕 ----------------

    @Test
    @DisplayName("test 是只读的：不写任何审计行（断言读操作不污染审计）")
    void testEndpointWritesNoAuditRow() {
        when(service.test(1L)).thenReturn(new SourceCheckResult(1L, "mock-mall", false,
                List.of(new SourceCheckResult.CheckItem("profile_file_exists", false, true, "画像文件不存在"))));

        ApiResponse<SourceCheckResult> response = controller.test(1L);

        assertThat(response.data().ok()).isFalse();
        assertThat(response.data().items()).hasSize(1);
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("list/get 不写审计（读路径无副作用）")
    void readEndpointsWriteNoAuditRow() {
        when(service.list()).thenReturn(List.of(view(1L, "mock-mall", "ACTIVE", true)));
        when(service.get(1L)).thenReturn(view(1L, "mock-mall", "ACTIVE", true));

        assertThat(controller.list().data()).hasSize(1);
        assertThat(controller.get(1L).data().sourceCode()).isEqualTo("mock-mall");

        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("响应信封 traceId 与审计 traceId 同源（一次请求一个 traceId）")
    void responseTraceIdMatchesAuditTraceId() {
        SourceRegistryView created = view(9L, "p1-03-probe-1", "DRAFT", false);
        when(service.create(any())).thenReturn(new SourceChangeOutcome(created, null, true));

        ApiResponse<SourceRegistryView> response = controller.create(
                new SourceRegistryCreateReq("p1-03-probe-1", "探针", "FILE", "a/b.json",
                        "Asia/Shanghai", "CNY", null, "1.0"), request());

        ArgumentCaptor<AuditActor> actor = ArgumentCaptor.forClass(AuditActor.class);
        verify(audit).success(actor.capture(), any(), any(), any(), any(), any(), any());
        assertThat(actor.getValue().traceId()).isEqualTo(response.traceId()).isNotBlank();
        assertThat(actor.getValue().role()).isEqualTo("admin");
    }

    @Test
    @DisplayName("未登录调用：审计动作不落库，直接 401 语义（CallerContext fail-closed，不回退 demo 用户）")
    void anonymousCallerIsRejected() {
        CurrentUserHolder.clear();
        when(service.create(any())).thenReturn(new SourceChangeOutcome(view(9L, "x", "DRAFT", false), null, true));

        assertThatThrownBy(() -> controller.create(
                new SourceRegistryCreateReq("x", "探针", "FILE", "a/b.json", "Asia/Shanghai", "CNY", null, "1.0"),
                request()))
                .isInstanceOf(com.graduation.analytics.auth.AuthenticationRequiredException.class);
        verifyNoInteractions(audit);
    }
}
