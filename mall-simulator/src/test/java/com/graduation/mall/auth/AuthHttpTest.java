package com.graduation.mall.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.graduation.mall.support.MallIsolationTestConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HTTP 层鉴权测试（§3.2 权限模块）：
 * 无 token 401、错误 token 401、admin 可访问管理端点、operator 访问管理端点 403。
 *
 * 边界说明（§5.2 / M1-7）：原用例中的 /api/v1/metrics/snapshots（401 探针）、
 * /api/v1/metrics/health（匿名可访问）、/api/v1/analysis/sales（operator 可访问）
 * 都是分析平台端点，对应控制器已随平台复制代码移出本模块；原管理端点探针
 * /api/v1/generator/scenarios 随演示生成器下线（M1-7：生成器整体归 synthetic-data-generator），故：
 * - 401 探针改用仍保留的受保护商城端点 /api/v1/mall/products；
 * - admin/403 探针改用仍保留的商城管理端点 /api/v1/mall/outbox/status；
 * - 平台专属断言整体删除（其覆盖由 analytics-server 自己的测试承担）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MallIsolationTestConfig.class)
// 分类标记（V25-S02 / K-02）：本类需要真实数据库隔离实例（3307）。
//   * 默认纯测试套件（mvn test）按 pom 的 <excludedGroups>it</excludedGroups> 不选中本类；
//   * 显式集成套件（mvn test -Pisolated-tests）选中本类，缺隔离档案时**硬拒（红）而非 skip**。
@Tag("it")
class AuthHttpTest {

    /** 仍保留的受保护商城端点：仅用于验证拦截器对无/错 token 的 401 行为 */
    private static final String PROTECTED_MALL_PATH = "/api/v1/mall/products";

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
        ResponseEntity<Map> resp = rest.getForEntity(PROTECTED_MALL_PATH, Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("UNAUTHORIZED", resp.getBody().get("code"));
    }

    @Test
    @DisplayName("错误 token → 401")
    void badTokenUnauthorized() {
        ResponseEntity<Map> resp = rest.exchange(PROTECTED_MALL_PATH,
                HttpMethod.GET, new HttpEntity<>(authHeader("bad-token")), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    @DisplayName("admin 可访问管理端点（Outbox 运维）")
    void adminCanAccessAdminEndpoints() {
        String token = login("admin", "admin123");
        ResponseEntity<Map> resp = rest.exchange("/api/v1/mall/outbox/status",
                HttpMethod.GET, new HttpEntity<>(authHeader(token)), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("data"));
    }

    @Test
    @DisplayName("operator 访问管理端点 → 403")
    void operatorForbiddenOnAdminEndpoints() {
        String token = login("operator", "operator123");
        ResponseEntity<Map> resp = rest.exchange("/api/v1/mall/outbox/status",
                HttpMethod.GET, new HttpEntity<>(authHeader(token)), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
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
