package com.graduation.mall.controller;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbox 运维接口的 HTTP 层回归（DEF-11）。
 *
 * 现场：`GET /api/v1/mall/outbox/status` 用 `Map.of(pendingCount, latestFile)` 组装响应，
 * 而 **`Map.of` 是 null 敌对的**——只要 `latestFile` 为 null（干净的库/首次启动/测试环境必然如此）
 * 就抛 NPE → HTTP 500。实测 2026-09-11 在 `AuthHttpTest` 里以 admin 身份访问得到 500，
 * 而 operator 的 403 断言先被拦截所以看不出来。
 *
 * 本用例固定住两件事：① 无滚动文件时接口仍返回 200；② `latestFile` 键**存在**且值为 null
 * （即"如实回答还没有文件"，不是把键删掉、也不是拿空串冒充路径）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OutboxControllerTest {

    @Autowired
    private TestRestTemplate rest;

    private String adminToken() {
        ResponseEntity<Map> resp = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return (String) ((Map) resp.getBody().get("data")).get("token");
    }

    @Test
    @DisplayName("无滚动文件时 outbox 状态返回 200 且 latestFile 为 null（不得 500）")
    void statusToleratesMissingRollingFile() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken());

        ResponseEntity<Map> resp = rest.exchange("/api/v1/mall/outbox/status",
                HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), "干净环境（无滚动文件）也必须 200");
        Map data = (Map) resp.getBody().get("data");
        assertNotNull(data);
        assertTrue(data.containsKey("latestFile"), "latestFile 键必须存在（值为 null 表示尚无文件）");
        assertTrue(data.containsKey("pendingCount"));
    }
}
