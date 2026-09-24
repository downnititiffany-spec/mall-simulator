package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * RuntimeProfile HTTP contract tests without Spring Boot context or database.
 * Service state transitions and connector probes are deliberately outside this test's scope.
 */
class RuntimeProfileControllerHttpTest {

    private static final Map<String, CurrentUser> USERS = Map.of(
            "admin-token", new CurrentUser(1L, "admin", "admin"),
            "dev-token", new CurrentUser(5L, "dev1", "data_dev"),
            "analyst-token", new CurrentUser(3L, "analyst1", "analyst"));

    private final RuntimeProfileService service = mock(RuntimeProfileService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthService auth = mock(AuthService.class);
        USERS.forEach((token, user) -> when(auth.validate(token)).thenReturn(user));
        mvc = MockMvcBuilders.standaloneSetup(new RuntimeProfileController(service))
                .addInterceptors(new AuthInterceptor(auth, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .build();
    }

    @Test
    @DisplayName("LOCAL DRAFT create/list/read/update：HTTP JSON 路由到 service，update path id 覆盖 body id")
    void createReadListAndUpdateForwardOverHttp() throws Exception {
        RuntimeProfile draft = profile(7L, RuntimeProfile.STATUS_DRAFT);
        when(service.create(any(RuntimeProfile.class))).thenReturn(draft);
        when(service.list()).thenReturn(List.of(draft));
        when(service.get(7L)).thenReturn(draft);
        when(service.update(any(RuntimeProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String createBody = "{\"profileCode\":\"local-dev\",\"profileName\":\"本地\","
                + "\"type\":\"LOCAL\",\"status\":\"DRAFT\",\"sourceId\":11,"
                + "\"landingUri\":\"file:///tmp/landing\",\"sparkMaster\":\"local[1]\"}";
        MvcResult created = request(post("/api/v1/runtime-profiles"), createBody);
        assertOk(created);
        assertThat(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"profileCode\":\"local-dev\"").contains("\"status\":\"DRAFT\"");

        assertOk(request(get("/api/v1/runtime-profiles"), null));
        assertOk(request(get("/api/v1/runtime-profiles/7"), null));
        String updateBody = "{\"id\":999,\"profileCode\":\"local-dev\",\"type\":\"LOCAL\",\"status\":\"DRAFT\"}";
        MvcResult updated = request(put("/api/v1/runtime-profiles/7"), updateBody);
        assertOk(updated);
        assertThat(updated.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"id\":7");
        verify(service).create(any(RuntimeProfile.class));
        verify(service).list();
        verify(service).get(7L);
        verify(service).update(org.mockito.ArgumentMatchers.argThat(p -> Long.valueOf(7L).equals(p.getId())));
    }

    @Test
    @DisplayName("test/activate：HTTP 路由保留服务返回的适用项与激活状态")
    void testAndActivateForwardOverHttp() throws Exception {
        RuntimeProfile active = profile(7L, RuntimeProfile.STATUS_ACTIVE);
        when(service.test(7L)).thenReturn(new RuntimeProfileService.TestResult(7L, true,
                List.of(new RuntimeProfileService.CheckDetail("spark", true, true, "version ok"),
                        new RuntimeProfileService.CheckDetail("hive", false, false, "LOCAL 不适用")),
                "PASS_WITH_SKIPPED"));
        when(service.activate(7L)).thenReturn(active);

        MvcResult tested = request(post("/api/v1/runtime-profiles/7/test"), null);
        assertOk(tested);
        String testJson = tested.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(testJson).contains("\"allPassed\":true").contains("\"applicable\":false")
                .contains("PASS_WITH_SKIPPED");

        MvcResult activated = request(post("/api/v1/runtime-profiles/7/activate"), null);
        assertOk(activated);
        assertThat(activated.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"status\":\"ACTIVE\"");
        verify(service).test(7L);
        verify(service).activate(7L);
    }

    @Test
    @DisplayName("权限边界：匿名 401、analyst 403；admin/data_dev 可访问运行环境读接口")
    void enforcesRuntimeManagePermissionOverHttp() throws Exception {
        when(service.list()).thenReturn(List.of());
        assertThat(request(get("/api/v1/runtime-profiles"), null, null).getResponse().getStatus()).isEqualTo(401);

        MvcResult analyst = request(get("/api/v1/runtime-profiles"), null, "analyst-token");
        assertThat(analyst.getResponse().getStatus()).isEqualTo(403);
        assertThat(analyst.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("FORBIDDEN_PERMISSION").contains("runtime:manage");

        String draftJson = "{\"profileCode\":\"x\",\"type\":\"LOCAL\",\"status\":\"DRAFT\"}";
        List<MvcResult> deniedMutations = List.of(
                request(post("/api/v1/runtime-profiles"), draftJson, "analyst-token"),
                request(put("/api/v1/runtime-profiles/7"), draftJson, "analyst-token"),
                request(post("/api/v1/runtime-profiles/7/test"), null, "analyst-token"),
                request(post("/api/v1/runtime-profiles/7/activate"), null, "analyst-token"));
        assertThat(deniedMutations).allSatisfy(result -> {
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .contains("FORBIDDEN_PERMISSION").contains("runtime:manage");
        });

        assertThat(request(get("/api/v1/runtime-profiles"), null, "admin-token").getResponse().getStatus()).isEqualTo(200);
        assertThat(request(get("/api/v1/runtime-profiles"), null, "dev-token").getResponse().getStatus()).isEqualTo(200);
        verify(service, org.mockito.Mockito.times(2)).list();
    }

    private MvcResult request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                              String body) throws Exception {
        return request(builder, body, "dev-token");
    }

    private MvcResult request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                              String body, String token) throws Exception {
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.contentType("application/json").content(body);
        return mvc.perform(builder).andReturn();
    }

    private static void assertOk(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":\"OK\"");
    }

    private static RuntimeProfile profile(Long id, String status) {
        RuntimeProfile p = new RuntimeProfile();
        p.setId(id);
        p.setProfileCode("local-dev");
        p.setProfileName("本地");
        p.setType(RuntimeProfile.TYPE_LOCAL);
        p.setStatus(status);
        p.setSourceId(11L);
        p.setLandingUri("file:///tmp/landing");
        p.setSparkMaster("local[1]");
        return p;
    }
}
