package com.graduation.mall.ingestion;

import com.graduation.mall.outbox.TraceContext;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坏行隔离测试（§5.2.5/§5.2.9）：非法枚举/未知版本/缺字段 → 不进入 landed，
 * 记录 quarantine_record，批次状态 QUARANTINED，正常行不受影响。
 */
class IngestionServiceTest extends MallTestSupport {

    @Autowired
    private IngestionService ingestionService;

    @Test
    @DisplayName("含 1 条非法行为枚举 → 批次 QUARANTINED，干净行 2 条正常落地")
    void badLineQuarantinedNotLanded() throws IOException {
        Path dir = LANDING.get().resolve("events");
        Files.createDirectories(dir);
        Files.write(dir.resolve("2026090112.jsonl"), List.of(
                com.graduation.mall.support.EventLines.userRegistered(1),
                com.graduation.mall.support.EventLines.badBehaviorType(),
                com.graduation.mall.support.EventLines.userRegistered(2)), StandardCharsets.UTF_8);

        IngestionService.RunResult result = ingestionService.runOne(TraceContext.create());
        assertEquals("QUARANTINED", result.status());
        assertEquals(2, result.recordCount(), "干净行必须全部落地");
        assertEquals(1, result.quarantineCount(), "坏行必须隔离");

        // landed 目录只有 2 行
        Path landed = Path.of(result.landedDir());
        try (var files = Files.list(landed)) {
            long lines = 0;
            for (Path f : files.toList()) {
                try (var ls = Files.lines(f)) {
                    lines += ls.count();
                }
            }
            assertEquals(2, lines);
        }

        // 隔离文件存在且包含坏行
        Path quarantineFile = Path.of(result.quarantineFile());
        assertTrue(Files.exists(quarantineFile), "隔离文件应存在");
        try (var ls = Files.lines(quarantineFile)) {
            assertEquals(1, ls.count());
        }
    }

    @Test
    @DisplayName("未知版本与缺字段同样被隔离")
    void unknownSchemaAndMissingFieldQuarantined() throws IOException {
        Path dir = LANDING.get().resolve("events");
        Files.createDirectories(dir);
        Files.write(dir.resolve("2026090113.jsonl"), List.of(
                com.graduation.mall.support.EventLines.unknownSchemaVersion(),
                com.graduation.mall.support.EventLines.missingUserId(),
                com.graduation.mall.support.EventLines.userRegistered(9)), StandardCharsets.UTF_8);

        IngestionService.RunResult result = ingestionService.runOne(TraceContext.create());
        assertEquals("QUARANTINED", result.status());
        assertEquals(1, result.recordCount());
        assertEquals(2, result.quarantineCount());
    }
}