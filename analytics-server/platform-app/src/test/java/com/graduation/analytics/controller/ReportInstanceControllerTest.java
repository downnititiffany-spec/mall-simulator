package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.report.ReportInstanceService;
import com.graduation.analytics.report.ReportInstanceService.CreateCommand;
import com.graduation.analytics.report.ReportInstanceService.Page;
import com.graduation.analytics.report.entity.ReportInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportInstanceControllerTest {
    private final ReportInstanceService service = mock(ReportInstanceService.class);
    private final ReportInstanceController controller = new ReportInstanceController(service);

    @BeforeEach
    void setCurrentUser() {
        CurrentUserHolder.set(new CurrentUser(8L, "analyst1", "analyst"));
    }

    @AfterEach
    void clearCurrentUser() {
        CurrentUserHolder.clear();
    }

    @Test
    void createUsesSessionOwnerAndResponseDoesNotExposeArtifactUri() {
        ReportInstance report = report();
        when(service.create(any(CreateCommand.class), eq("analyst1"))).thenReturn(report);

        var response = controller.create(new ReportInstanceController.CreateReq(
                "OPS_DAILY", "1", "S1", LocalDate.parse("2026-10-10"), false,
                null, null, null));

        verify(service).create(any(CreateCommand.class), eq("analyst1"));
        assertThat(response.data().creatorId()).isEqualTo("analyst1");
        assertThat(ReportInstanceController.ReportSummary.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("artifactUri");
    }

    @Test
    void pageUsesSessionOwnerAndReturnsOwnerScopedSummary() {
        when(service.page("analyst1", 1, 20, "createdAt", false))
                .thenReturn(new Page(List.of(report()), 1, 20, 1, 1, "createdAt,desc"));

        var response = controller.page(1, 20, "createdAt,desc");

        verify(service).page("analyst1", 1, 20, "createdAt", false);
        assertThat(response.data().items()).hasSize(1);
        assertThat(response.data().items().get(0).creatorId()).isEqualTo("analyst1");
        assertThat(response.data().items().get(0).getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("artifactUri");
    }

    @Test
    void detailAndArtifactResolveOnlyReportsOwnedBySessionUser() {
        when(service.findOwn(44L, "analyst1")).thenReturn(null);
        assertThatThrownBy(() -> controller.detail(44L)).isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("报告不存在");

        ReportInstance report = report();
        when(service.findOwn(44L, "analyst1")).thenReturn(report);
        when(service.summary(report)).thenReturn(new ObjectMapper().createObjectNode());
        when(service.artifact(report)).thenReturn(new ObjectMapper().createObjectNode());

        controller.detail(44L);
        controller.artifact(44L);

        verify(service, org.mockito.Mockito.times(3)).findOwn(44L, "analyst1");
        verify(service).summary(report);
        verify(service).artifact(report);
    }

    private static ReportInstance report() {
        ReportInstance report = new ReportInstance();
        report.setId(44L);
        report.setTemplateCode("OPS_DAILY");
        report.setTemplateVersion("1");
        report.setSnapshotId("S1");
        report.setCreatorId("analyst1");
        report.setStatus("READY");
        report.setArtifactUri("db://report-instance/44");
        report.setArtifactChecksum("a".repeat(64));
        report.setCreatedAt(LocalDateTime.parse("2026-10-10T10:00:00"));
        report.setUpdatedAt(report.getCreatedAt());
        return report;
    }
}
