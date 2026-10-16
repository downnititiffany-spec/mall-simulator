package com.graduation.analytics.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.controller.PlatformExceptionAdvice;
import com.graduation.analytics.decision.OperationAuditService;
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

class UserAdminPaginationHttpTest {
    private final AuthService authService = mock(AuthService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthService sessions = mock(AuthService.class);
        when(sessions.validate("admin-token")).thenReturn(new CurrentUser(1L, "admin", "admin"));
        mvc = MockMvcBuilders.standaloneSetup(new UserAdminController(authService, mock(OperationAuditService.class)))
                .addInterceptors(new AuthInterceptor(sessions, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .build();
    }

    @Test
    void pageRouteReturnsMetadataLegacyRemainsArrayAndAnonymousIsRejected() throws Exception {
        var user = new AuthService.UserView(12L, "analyst", "数据分析师", "analyst");
        when(authService.listUsersPage(2, 10, "username,asc"))
                .thenReturn(new AuthService.UserPage(List.of(user), 2, 10, 21, 3, "username,asc"));
        when(authService.listUsers()).thenReturn(List.of(user));

        MvcResult page = mvc.perform(get("/api/v1/admin/users/page").param("page", "2").param("size", "10")
                        .param("sort", "username,asc").header("Authorization", "Bearer admin-token"))
                .andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        String json = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"page\":2", "\"total\":21", "\"totalPages\":3", "\"items\":[");
        assertThat(json).doesNotContain("passwordHash", "must-not-be-exposed");

        MvcResult legacy = mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer admin-token"))
                .andReturn();
        JsonNode data = new ObjectMapper().readTree(legacy.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(mvc.perform(get("/api/v1/admin/users/page")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
