package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.pipeline.PipelineService;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class PipelineControllerPaginationHttpTest {

    private final PipelineRunMapper mapper = mock(PipelineRunMapper.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthService auth = mock(AuthService.class);
        when(auth.validate("admin-token")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        mvc = MockMvcBuilders.standaloneSetup(
                        new PipelineController(mock(PipelineService.class), mapper))
                .addInterceptors(new AuthInterceptor(auth, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .build();
    }

    @Test
    void pageRouteSerializesPageResultAndRequiresPermission() throws Exception {
        when(mapper.selectCount(any())).thenReturn(41L);
        when(mapper.selectList(any())).thenReturn(List.of(new PipelineRun()));

        MvcResult result = mvc.perform(get("/api/v1/pipeline-runs/page")
                        .param("page", "2").param("size", "10").param("sort", "createdAt,asc")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"page\":2", "\"size\":10", "\"total\":41",
                "\"totalPages\":5", "\"sort\":\"createdat,asc\"", "\"items\":[");

        MvcResult anonymous = mvc.perform(get("/api/v1/pipeline-runs/page")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void invalidSortIsMappedToBadRequest() throws Exception {
        when(mapper.selectCount(any())).thenReturn(0L);
        MvcResult result = mvc.perform(get("/api/v1/pipeline-runs/page")
                        .param("page", "1").param("size", "20").param("sort", "secretColumn,desc")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("PARAM_INVALID");
    }
}
