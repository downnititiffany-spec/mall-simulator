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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 运维端点权限测试：质量规则结果与 AI 审计仅管理员可见（§3.2）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpsApiTest {

    @Autowired
    private TestRestTemplate rest;

    private String login(String user) {
        ResponseEntity<Map> resp = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", user, "password", user + "123"), Map.class);
        Map data = (Map) resp.getBody().get("data");
        return (String) data.get("token");
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @Test
    @DisplayName("admin 可访问质量规则与审计端点")
    void adminCanAccessOpsEndpoints() {
        String token = login("admin");
        assertEquals(HttpStatus.OK, get("/api/v1/metrics/quality", token).getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/v1/ai/audit/history", token).getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/v1/ai/audit/calls", token).getStatusCode());
    }

    @Test
    @DisplayName("non-admin 访问运维端点 → 403")
    void operatorForbiddenOnOpsEndpoints() {
        String token = login("operator");
        assertEquals(HttpStatus.FORBIDDEN, get("/api/v1/metrics/quality", token).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get("/api/v1/ai/audit/history", token).getStatusCode());
    }
}