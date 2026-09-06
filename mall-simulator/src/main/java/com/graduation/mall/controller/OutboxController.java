package com.graduation.mall.controller;

import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.outbox.OutboxPublisher;
import com.graduation.mall.outbox.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Outbox 管理接口：查看未发布积压与最近滚动文件、手动触发发布（演示控制台用）。
 */
@RestController
@RequestMapping("/api/v1/mall/outbox")
@RequiredArgsConstructor
public class OutboxController {

    private final OutboxPublisher publisher;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        TraceContext trace = TraceContext.create();
        Optional<Path> latest = publisher.latestFile();
        Map<String, Object> data = Map.of(
                "pendingCount", publisher.pendingCount(),
                "latestFile", latest.map(Path::toString).orElse(null));
        return ApiResponse.ok(data, trace.traceId());
    }

    @PostMapping("/publish")
    public ApiResponse<Map<String, Object>> publish() {
        TraceContext trace = TraceContext.create();
        OutboxPublisher.PublishResult result = publisher.publishOnce();
        Map<String, Object> data = Map.of(
                "publishedCount", result.publishedCount(),
                "failedEventIds", result.failedEventIds(),
                "pendingCount", publisher.pendingCount());
        return ApiResponse.ok(data, trace.traceId());
    }
}