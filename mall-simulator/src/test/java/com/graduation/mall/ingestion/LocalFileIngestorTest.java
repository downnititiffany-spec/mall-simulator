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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集链路集成测试（§5.2.5/§5.2.9 核心验收）：
 * 暂停→继续→恢复：断点采集不丢不重。
 */
class LocalFileIngestorTest extends MallTestSupport {

    @Autowired
    private IngestionService ingestionService;

    private Path writeEventsFile(String name, int lines) throws IOException {
        Path dir = LANDING.get().resolve("events");
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        List<String> content = new ArrayList<>();
        for (int i = 1; i <= lines; i++) {
            content.add(com.graduation.mall.support.EventLines.userRegistered(i));
        }
        Files.write(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("第一轮采集：2 个文件全部进入 landed，批次 SUCCESS")
    void firstRoundCollectsAll() throws IOException {
        writeEventsFile("2026090108.jsonl", 3);
        writeEventsFile("2026090109.jsonl", 2);

        IngestionService.RunResult result = ingestionService.runOne(TraceContext.create());
        assertEquals("SUCCESS", result.status());
        assertEquals(5, result.recordCount());
        assertEquals(0, result.quarantineCount());
        assertEquals(2, result.fileCount());

        Path landedDir = Path.of(result.landedDir());
        try (var files = Files.list(landedDir)) {
            long lines = 0;
            for (Path f : files.toList()) {
                try (var ls = Files.lines(f)) {
                    lines += ls.count();
                }
            }
            assertEquals(5, lines, "landed 干净行总数必须与源一致");
        }
    }

    @Test
    @DisplayName("暂停恢复：文件追加新行后第二轮只采新增，不重复计数（Taildir 语义）")
    void resumeContinuesFromCheckpoint() throws IOException {
        Path file = writeEventsFile("2026090110.jsonl", 2);
        IngestionService.RunResult first = ingestionService.runOne(TraceContext.create());
        assertEquals(2, first.recordCount());

        // 模拟生成器继续写入（同一小时文件追加）
        Files.writeString(file,
                com.graduation.mall.support.EventLines.userRegistered(3) + "\n"
                        + com.graduation.mall.support.EventLines.userRegistered(4) + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        IngestionService.RunResult second = ingestionService.runOne(TraceContext.create());
        assertEquals("SUCCESS", second.status());
        assertEquals(2, second.recordCount(), "第二轮必须只采新增 2 行，不得重复");

        // 累计 landed = 4（不丢不重）
        long total = 0;
        try (var files = Files.list(LANDING.get().resolve("landed"))) {
            for (Path batchDir : files.toList()) {
                try (var batchFiles = Files.list(batchDir)) {
                    for (Path f : batchFiles.toList()) {
                        try (var ls = Files.lines(f)) {
                            total += ls.count();
                        }
                    }
                }
            }
        }
        assertEquals(4, total, "累计落地行数必须等于源写入总数（不丢不重）");
    }

    @Test
    @DisplayName("无新内容时第二轮零新增，批次仍 SUCCESS")
    void idleRoundAddsNothing() throws IOException {
        writeEventsFile("2026090111.jsonl", 2);
        ingestionService.runOne(TraceContext.create());

        IngestionService.RunResult idle = ingestionService.runOne(TraceContext.create());
        assertEquals("SUCCESS", idle.status());
        assertEquals(0, idle.recordCount(), "没有新内容时不得重复采集");
        assertTrue(idle.fileCount() >= 0);
    }
}