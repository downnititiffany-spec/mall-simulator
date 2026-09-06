package com.graduation.mall.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 层鉴权测试（§3.2 权限模块）：
 * 无 token 401、错误 token 401、admin 可访问管理端点、operator 访问管理端点 403、
 * operator 可访问分析端点 200。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthHttpTest {

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders authHeader(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return h;
    }

    private String login(String username, String password) {
        ResponseEntity<Map> resp = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map data = (Map) resp.getBody().get("data");
        return (String) data.get("token");
    }

    @Test
    @DisplayName("无 token 访问受保护接口 → 401")
    void noTokenUnauthorized() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/v1/metrics/snapshots", Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("UNAUTHORIZED", resp.getBody().get("code"));
    }

    @Test
    @DisplayName("错误 token → 401")
    void badTokenUnauthorized() {
        ResponseEntity<Map> resp = rest.exchange("/api/v1/metrics/snapshots",
                HttpMethod.GET, new HttpEntity<>(authHeader("bad-token")), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("admin 可访问管理端点（生成器场景列表）")
    void adminCanAccessAdminEndpoints() {
        String token = login("admin", "admin123");
        ResponseEntity<Map> resp = rest.exchange("/api/v1/generator/scenarios",
                HttpMethod.GET, new HttpEntity<>(authHeader(token)), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("data"));
    }

    @Test
    @DisplayName("operator 访问管理端点 → 403")
    void operatorForbiddenOnAdminEndpoints() {
        String token = login("operator", "operator123");
        ResponseEntity<Map> resp = rest.exchange("/api/v1/generator/scenarios",
                HttpMethod.GET, new HttpEntity<>(authHeader(token)), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    @DisplayName("operator 可访问分析端点")
    void operatorCanAccessAnalysis() {
        String token = login("operator", "operator123");
        ResponseEntity<Map> resp = rest.exchange("/api/v1/analysis/sales?from=2026-09-01&to=2026-09-06",
                HttpMethod.GET, new HttpEntity<>(authHeader(token)), Map.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "分析接口对运营人员应可访问");
    }

    @Test
    @DisplayName("health 无需登录")
    void healthPublic() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/v1/metrics/health", Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    @DisplayName("登录成功返回 user 信息，me 可查询")
    void meAfterLogin() {
        String token = login("analyst", "analyst123");
        ResponseEntity<Map> me = rest.exchange("/api/v1/auth/me",
                HttpMethod.GET, new HttpEntity<>(authHeader(token)), Map.class);
        assertEquals(HttpStatus.OK, me.getStatusCode());
        Map data = (Map) me.getBody().get("data");
        Map user = (Map) data.get("user");
        assertEquals("analyst", user.get("role"));
    }
}