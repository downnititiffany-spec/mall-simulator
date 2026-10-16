package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.ingestion.IngestionBatchQueryService;
import com.graduation.analytics.ingestion.IngestionService;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class IngestionControllerPaginationHttpTest {

    private final IngestionService ingestionService = mock(IngestionService.class);
    private final IngestionBatchQueryService queryService = mock(IngestionBatchQueryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthService auth = mock(AuthService.class);
        when(auth.validate("admin-token")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        mvc = MockMvcBuilders.standaloneSetup(new IngestionController(ingestionService, queryService))
                .addInterceptors(new AuthInterceptor(auth, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .build();
    }

    @Test
    void pageRouteSerializesPageContractAndRequiresSession() throws Exception {
        IngestionBatch batch = new IngestionBatch();
        batch.setId(11L);
        batch.setStatus(IngestionBatch.STATUS_SUCCESS);
        when(queryService.listPage(2, 10, "status,asc"))
                .thenReturn(new IngestionBatchQueryService.BatchPage(List.of(batch), 2, 10, 21, 3,
                        "status,asc"));

        MvcResult authenticated = mvc.perform(get("/api/v1/ingestion/batches/page")
                        .param("page", "2").param("size", "10").param("sort", "status,asc")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn();

        assertThat(authenticated.getResponse().getStatus()).isEqualTo(200);
        String json = authenticated.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"page\":2", "\"size\":10", "\"total\":21",
                "\"totalPages\":3", "\"sort\":\"status,asc\"", "\"items\":[",
                "\"id\":11", "\"status\":\"SUCCESS\"");

        MvcResult anonymous = mvc.perform(get("/api/v1/ingestion/batches/page")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void invalidPageSizeIsRejectedAndLegacyRecentBatchRouteStaysAnArray() throws Exception {
        when(queryService.listPage(1, 101, "id,desc"))
                .thenThrow(new PlatformBizException(PlatformBizException.PARAM_INVALID, "invalid page size"));
        MvcResult invalid = mvc.perform(get("/api/v1/ingestion/batches/page")
                        .param("size", "101").header("Authorization", "Bearer admin-token"))
                .andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);

        IngestionBatch batch = new IngestionBatch();
        batch.setId(3L);
        when(ingestionService.recentBatches(5)).thenReturn(List.of(batch));
        MvcResult legacy = mvc.perform(get("/api/v1/ingestion/batches").param("limit", "5")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn();
        assertThat(legacy.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = new ObjectMapper().readTree(legacy.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.get(0).get("id").asLong()).isEqualTo(3L);
    }
}
