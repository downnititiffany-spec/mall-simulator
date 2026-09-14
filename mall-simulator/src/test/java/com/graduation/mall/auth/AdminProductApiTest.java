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
import com.graduation.mall.support.MallIsolationTestConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 商品管理端点权限与可用性（§2.2）：admin 可访问、operator 403。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MallIsolationTestConfig.class)
class AdminProductApiTest {

    @Autowired
    private TestRestTemplate rest;

    private String login(String user) {
        ResponseEntity<Map> resp = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", user, "password", user + "123"), Map.class);
        Map data = (Map) resp.getBody().get("data");
        return (String) data.get("token");
    }

    private HttpEntity<?> authed(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return new HttpEntity<>(h);
    }

    @Test
    @DisplayName("admin 可访问商品管理列表并新建商品")
    void adminCanManageProducts() {
        String token = login("admin");
        ResponseEntity<Map> list = rest.exchange("/api/v1/admin/products",
                HttpMethod.GET, authed(token), Map.class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        // 新建一个商品（分类 1 为种子分类）
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        ResponseEntity<Map> create = rest.postForEntity("/api/v1/admin/products",
                new HttpEntity<>(Map.of("name", "测试商品A", "categoryId", 1, "brandId", 1,
                        "price", 99.9, "cost", 50.0), h), Map.class);
        assertEquals(HttpStatus.OK, create.getStatusCode());
        assertEquals("OK", create.getBody().get("code"));
    }

    @Test
    @DisplayName("operator 访问商品管理 → 403")
    void operatorForbiddenOnProductAdmin() {
        String token = login("operator");
        ResponseEntity<Map> resp = rest.exchange("/api/v1/admin/products",
                HttpMethod.GET, authed(token), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }
}