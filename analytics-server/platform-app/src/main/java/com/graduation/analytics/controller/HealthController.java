package com.graduation.analytics.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** R1 骨架健康检查：验证分析平台独立启动（整改书 §7.4 完成标准：停止商城平台仍可访问） */
@RestController
public class HealthController {

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        return Map.of("code", "OK", "app", "analytics-server", "stage", "R1-skleton");
    }
}