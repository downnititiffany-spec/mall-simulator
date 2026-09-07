package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.IngestionBatchFile;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.common.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 采集编排服务（§5.2.7 批次状态机）：
 * GENERATED → COLLECTING → LANDED → VALIDATING → SUCCESS / QUARANTINED
 * 一轮 = events 目录一次全量增量扫描；文件级 offset 落 ingestion_batch_file 与 file_checkpoint。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final IngestionBatchMapper batchMapper;
    private final IngestionBatchFileMapper batchFileMapper;
    private final FileCheckpointMapper checkpointMapper;
    private final LocalFileIngestor ingestor;
    private final EventClock eventClock;
    private final Environment environment;

    public record RunResult(Long batchId, String batchNo, String status,
                            long recordCount, long quarantineCount, long errorCount,
                            int fileCount, String landedDir, String quarantineFile) {
    }

    /**
     * 执行一轮采集（手动触发/演示控制台；定时触发在平台阶段）。
     */
    public RunResult runOne(TraceContext trace) {
        Path landingRoot = Path.of(environment.getProperty("mall.landing.path", "./landing"));
        Path eventsDir = landingRoot.resolve("events");
        // 批次号带随机后缀，避免同秒多次运行撞唯一键
        String batchNo = "ing-" + BATCH_NO.format(LocalDateTime.now())
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        IngestionBatch batch = new IngestionBatch();
        batch.setBatchNo(batchNo);
        batch.setSource("local-file");
        batch.setStatus(IngestionBatch.STATUS_COLLECTING);
        batch.setRecordCount(0L);
        batch.setErrorCount(0L);
        batch.setQuarantineCount(0L);
        batch.setStartTime(eventClock.nowLdt());
        batchMapper.insert(batch);

        Path landedDir = landingRoot.resolve("landed").resolve(batchNo);
        Path quarantineFile = landingRoot.resolve("quarantine").resolve(batchNo + ".jsonl");
        batch.setLandingDir(landedDir.toString());
        batchMapper.updateById(batch);

        long recordCount = 0;
        long quarantineCount = 0;
        long errorCount = 0;
        int fileCount = 0;
        try {
            Files.createDirectories(landedDir);
            Files.createDirectories(quarantineFile.getParent());
            Map<String, Path> hourFiles = new TreeMap<>(); // 文件名排序 → 确定性批次顺序
            if (Files.isDirectory(eventsDir)) {
                try (Stream<Path> files = Files.list(eventsDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                            .forEach(p -> hourFiles.put(p.getFileName().toString(), p));
                }
            }
            for (var entry : hourFiles.entrySet()) {
                try {
                    var res = ingestor.ingestFile(entry.getValue(), batch.getId(), landedDir,
                            quarantineFile, trace);
                    recordCount += res.collected();
                    quarantineCount += res.quarantined();
                    fileCount++;
                    if (res.collected() > 0 || res.quarantined() > 0) {
                        IngestionBatchFile bf = new IngestionBatchFile();
                        bf.setBatchId(batch.getId());
                        bf.setFilePath(res.filePath());
                        bf.setStartOffset(res.startOffset());
                        bf.setEndOffset(res.endOffset());
                        bf.setRecordCount(res.collected());
                        bf.setStatus("LANDED");
                        batchFileMapper.insert(bf);
                    }
                } catch (Exception e) {
                    errorCount++;
                    log.warn("ingest file failed: {} ({})", entry.getKey(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("采集目录准备失败", e);
        }

        batch.setRecordCount(recordCount);
        batch.setQuarantineCount(quarantineCount);
        batch.setErrorCount(errorCount);
        batch.setStatus(errorCount > 0 ? IngestionBatch.STATUS_FAILED
                : (quarantineCount > 0 ? IngestionBatch.STATUS_QUARANTINED : IngestionBatch.STATUS_SUCCESS));
        batch.setEndTime(eventClock.nowLdt());
        batchMapper.updateById(batch);

        log.info("ingestion run {}: status={} records={} quarantine={} errors={} files={}",
                batchNo, batch.getStatus(), recordCount, quarantineCount, errorCount, fileCount);
        return new RunResult(batch.getId(), batchNo, batch.getStatus(), recordCount,
                quarantineCount, errorCount, fileCount, landedDir.toString(), quarantineFile.toString());
    }

    /** 采集状态总览：events 待采文件、最近批次、断点数 */
    public Map<String, Object> status() {
        Path landingRoot = Path.of(environment.getProperty("mall.landing.path", "./landing"));
        Path eventsDir = landingRoot.resolve("events");
        long pendingFiles = 0;
        long pendingBytes = 0;
        if (Files.isDirectory(eventsDir)) {
            try (Stream<Path> files = Files.list(eventsDir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                    pendingFiles++;
                    pendingBytes += Files.size(f);
                }
            } catch (IOException e) {
                log.warn("events dir scan failed: {}", e.getMessage());
            }
        }
        IngestionBatch latest = batchMapper.selectOne(new LambdaQueryWrapper<IngestionBatch>()
                .orderByDesc(IngestionBatch::getId).last("LIMIT 1"));
        return Map.of(
                "eventsDir", eventsDir.toString(),
                "pendingFiles", pendingFiles,
                "pendingBytes", pendingBytes,
                "latestBatch", latest == null ? null : Map.of(
                        "batchNo", latest.getBatchNo(), "status", latest.getStatus(),
                        "recordCount", latest.getRecordCount(),
                        "quarantineCount", latest.getQuarantineCount()),
                "checkpointFiles", checkpointMapper.selectCount(null));
    }

    public List<IngestionBatch> recentBatches(int limit) {
        return batchMapper.selectList(new LambdaQueryWrapper<IngestionBatch>()
                .orderByDesc(IngestionBatch::getId)
                .last("LIMIT " + Math.max(1, Math.min(100, limit))));
    }
}