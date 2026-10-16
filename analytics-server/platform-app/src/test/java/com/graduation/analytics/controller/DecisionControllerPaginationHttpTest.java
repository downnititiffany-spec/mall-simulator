package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.decision.DecisionService;
import com.graduation.analytics.decision.OperationAuditService;
import com.graduation.analytics.decision.entity.DecisionTask;
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

class DecisionControllerPaginationHttpTest {

    private final DecisionService service = mock(DecisionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthService auth = mock(AuthService.class);
        when(auth.validate("admin-token")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        mvc = MockMvcBuilders.standaloneSetup(new DecisionController(service, mock(OperationAuditService.class)))
                .addInterceptors(new AuthInterceptor(auth, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .build();
    }

    @Test
    void pageRouteSerializesMetadataAndRequiresSession() throws Exception {
        when(service.listPage(2, 10, "createdAt,desc"))
                .thenReturn(new DecisionService.DecisionPage(List.of(new DecisionTask()), 2, 10, 21, 3,
                        "createdat,desc"));

        MvcResult result = mvc.perform(get("/api/v1/decisions/page")
                        .param("page", "2").param("size", "10").param("sort", "createdAt,desc")
                        .header("Authorization", "Bearer admin-token"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"page\":2", "\"size\":10", "\"total\":21",
                "\"totalPages\":3", "\"sort\":\"createdat,desc\"", "\"items\":[");

        MvcResult anonymous = mvc.perform(get("/api/v1/decisions/page")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    }
}
