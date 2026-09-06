package com.graduation.mall.ingestion;

import com.graduation.mall.ingestion.entity.IngestionBatchFile;
import com.graduation.mall.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.mall.outbox.TraceContext;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 黄金数据采集对账（§27.1/§28.4）：
 * golden-20260901.jsonl（30 条）经采集链路 → landed 30 行 SUCCESS；两轮不重复。
 * 这是阶段 5 ODS 装载前的采集侧对账基线。
 */
class GoldenIngestionTest extends MallTestSupport {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private IngestionBatchFileMapper batchFileMapper;

    private void copyGoldenIntoEvents() throws IOException {
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path src = repoRoot.resolve("tests/golden-dataset/events/golden-20260901.jsonl");
        Path dir = LANDING.get().resolve("events");
        Files.createDirectories(dir);
        Files.copy(src, dir.resolve("golden-20260901.jsonl"), StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    @DisplayName("黄金数据 30 条全部通过采集校验并落地，断点恢复零重复")
    void goldenDatasetCollectsCleanly() throws IOException {
        copyGoldenIntoEvents();
        IngestionService.RunResult first = ingestionService.runOne(TraceContext.create());
        assertEquals("SUCCESS", first.status());
        assertEquals(30, first.recordCount(), "黄金数据 30 行必须全部合法落地");
        assertEquals(0, first.quarantineCount());

        // 第二轮（断点恢复）零新增
        IngestionService.RunResult second = ingestionService.runOne(TraceContext.create());
        assertEquals(0, second.recordCount(), "断点恢复后不得重复采集");
    }

    @Test
    @DisplayName("文件级 offset 与行数落库可查（start/end_offset 对账依据）")
    void fileOffsetsPersisted() throws IOException {
        copyGoldenIntoEvents();
        ingestionService.runOne(TraceContext.create());

        List<IngestionBatchFile> rows = batchFileMapper.selectList(null);
        assertEquals(1, rows.size());
        assertEquals("golden-20260901.jsonl", rows.get(0).getFilePath());
        assertEquals(30, rows.get(0).getRecordCount());
        assertEquals(0, rows.get(0).getStartOffset());
        // end_offset 是字节偏移（30 行 JSON ≈ 10KB），只断言 >0 且文件大小一致
        long fileSize = Files.size(LANDING.get().resolve("events").resolve("golden-20260901.jsonl"));
        assertEquals(fileSize, rows.get(0).getEndOffset());
    }
}