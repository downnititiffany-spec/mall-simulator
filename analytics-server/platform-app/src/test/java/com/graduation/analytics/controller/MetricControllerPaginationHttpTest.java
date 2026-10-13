package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class MetricControllerPaginationHttpTest {

    private final MetricStore store = mock(MetricStore.class);
    private final DataQualityResultMapper qualityMapper = mock(DataQualityResultMapper.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthService auth = mock(AuthService.class);
        when(auth.validate("admin-token")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        mvc = MockMvcBuilders.standaloneSetup(new MetricController(store, qualityMapper))
                .addInterceptors(new AuthInterceptor(auth, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .build();
    }

    @Test
    void pageRouteReturnsMetadataAndRequiresSessionWhileLegacyListStaysAnArray() throws Exception {
        MetricSnapshot item = new MetricSnapshot();
        item.setSnapshotId("S21");
        when(store.listSnapshotsPage(2, 10, "createdAt,desc"))
                .thenReturn(new MetricStore.SnapshotPage(List.of(item), 2, 10, 21, 3, "createdat,desc"));
        when(store.listSnapshots(5)).thenReturn(List.of(item));

        MvcResult page = mvc.perform(get("/api/v1/metrics/snapshots/page")
                        .param("page", "2").param("size", "10").param("sort", "createdAt,desc")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        String json = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"page\":2", "\"size\":10", "\"total\":21",
                "\"totalPages\":3", "\"sort\":\"createdat,desc\"", "\"items\":[");

        MvcResult legacy = mvc.perform(get("/api/v1/metrics/snapshots").param("limit", "5")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn();
        assertThat(legacy.getResponse().getStatus()).isEqualTo(200);
        JsonNode legacyData = new ObjectMapper()
                .readTree(legacy.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        assertThat(legacyData.isArray()).isTrue();
        assertThat(legacyData.get(0).get("snapshotId").asText()).isEqualTo("S21");

        MvcResult anonymous = mvc.perform(get("/api/v1/metrics/snapshots/page")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void explicitHistoricalSnapshotOverviewPassesExactIdAndRequiresDashboardSession() throws Exception {
        when(store.query(new MetricStore.MetricQuery("S_ARCHIVED_OTHER_PROFILE", true)))
                .thenReturn(List.of());

        MvcResult authorized = mvc.perform(get("/api/v1/metrics/overview")
                        .param("snapshotId", "S_ARCHIVED_OTHER_PROFILE")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn();

        assertThat(authorized.getResponse().getStatus()).isEqualTo(200);
        verify(store).query(new MetricStore.MetricQuery("S_ARCHIVED_OTHER_PROFILE", true));

        MvcResult anonymous = mvc.perform(get("/api/v1/metrics/overview")
                        .param("snapshotId", "S_ARCHIVED_OTHER_PROFILE"))
                .andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void capabilitiesRouteReturnsStoreLimitsAndRequiresSession() throws Exception {
        when(store.capabilities()).thenReturn(new com.graduation.analytics.metric.MetricStore.Capabilities(
                "mysql", true, false, null, 30, true));

        MvcResult response = mvc.perform(get("/api/v1/metrics/capabilities")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        String json = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"type\":\"mysql\"", "\"supportsSnapshot\":true",
                "\"supportsDimensions\":false", "\"maxRows\":null",
                "\"queryTimeoutSeconds\":30", "\"available\":true");
        assertThat(json).doesNotContain("private connection diagnostic");

        MvcResult anonymous = mvc.perform(get("/api/v1/metrics/capabilities")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void qualityPageReturnsStablePaginationMetadataAndRequiresOpsPermission() throws Exception {
        DataQualityResult item = new DataQualityResult();
        item.setId(21L);
        item.setRuleCode("AMOUNT_RECONCILE");
        when(qualityMapper.selectCount(any())).thenReturn(21L);
        when(qualityMapper.selectList(any())).thenReturn(List.of(item));

        MvcResult page = mvc.perform(get("/api/v1/metrics/quality/page")
                        .param("page", "3").param("size", "10").param("sort", "ruleCode,asc")
                        .param("runId", "7").header("Authorization", "Bearer admin-token"))
                .andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        String json = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"page\":3", "\"size\":10", "\"total\":21",
                "\"totalPages\":3", "\"sort\":\"rulecode,asc\"", "\"items\":[",
                "\"ruleCode\":\"AMOUNT_RECONCILE\"");

        MvcResult anonymous = mvc.perform(get("/api/v1/metrics/quality/page")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);

        MvcResult invalid = mvc.perform(get("/api/v1/metrics/quality/page")
                        .param("size", "101").header("Authorization", "Bearer admin-token"))
                .andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
    }
}
