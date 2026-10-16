package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.testsupport.IsolationProfileCondition;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-N1A 交付物 3：来源 A (mock-mall) 真实连续性与增量追加集成测试套件。
 *
 * <p>按 R-N1-2 / R-N1-4 约束：
 * <ul>
 *   <li>来源 A 画像为 v1 扁平语法（{@code mock-mall.v1.json}），按设计直通不映射（{@code SourceMapping.legacy()}）；</li>
 *   <li>清单中 {@code mappingVersion} 与 {@code mappingProfileHash} 恒为 {@code null}；</li>
 *   <li>黄金数据 55 行入采，断言 51 接受 / 4 隔离（与 manifest-30 冻结口径一致）；</li>
 *   <li>检查点基于真实 MySQL 3307 {@code file_checkpoint} 表持久化并按文件大小落库；</li>
 *   <li>向临时副本追加 3 行 canonical 数据，第二轮仅消费 3 行，检查点精确推进追加字节数。</li>
 * </ul>
 * </p>
 */
@Tag("it")
@ExtendWith(IsolationProfileCondition.class)
@DisplayName("WP-N1A 交付物 3: 来源 A (mock-mall) 真实连续性与增量追加集成测试")
class SourceARealIngestionIT {

    private static final RealIngestionTestSupport support = new RealIngestionTestSupport();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    static Path sharedLandingRoot;

    @BeforeAll
    static void setUp() {
        support.setUp(sharedLandingRoot);
    }

    @AfterAll
    static void tearDown() {
        support.cleanup();
    }

    @Test
    @DisplayName("来源 A 连续性：51 接受 / 4 隔离契约对账，无映射标记，检查点落地；增量追加 3 行精确推进断言")
    void realIngestion_sourceA_continuityAndIncrementalAppend(@TempDir Path landingRoot) throws IOException {
        // 1. 切换激活源至来源 A (mock-mall)
        support.switchToSourceA(landingRoot);
        assertThat(support.getSourceRegistryService().currentSourceId())
                .as("当前激活源必须为来源 A")
                .contains(support.getSourceA().getId());

        // 2. 拷贝 golden 数据集到 <landing>/events，使用副本避免修改仓库文件
        Path eventsDir = Files.createDirectories(landingRoot.resolve("events"));
        Path goldenRepoFile = RepoRoot.path("tests/golden-dataset/events/golden-20260901.jsonl");
        Path goldenInputCopy = eventsDir.resolve("golden-20260901.jsonl");
        Files.copy(goldenRepoFile, goldenInputCopy);

        long initialFileSize = Files.size(goldenInputCopy);
        assertThat(initialFileSize).as("golden-20260901.jsonl 文件大小必须大于 0").isGreaterThan(0L);

        // 3. 第一轮采集 (IngestionService.runOne)
        IngestionService.RunResult result1 = support.getIngestionService().runOne(TraceContext.create());

        // 4. 第一轮结果与 manifest-30 连续性核对
        // 状态口径（IngestionService 定版）：黄金集含 4 行隔离 ⇒ QUARANTINED；SUCCESS 仅当零隔离。
        assertThat(result1.status()).isEqualTo(IngestionBatch.STATUS_QUARANTINED);
        assertThat(result1.recordCount())
                .as("manifest-30 冻结口径：首轮接受 51 行")
                .isEqualTo(51L);
        assertThat(result1.quarantineCount())
                .as("manifest-30 冻结口径：首轮隔离 4 行")
                .isEqualTo(4L);
        assertThat(result1.errorCount()).isZero();
        assertThat(result1.fileCount()).isEqualTo(1);
        assertThat(result1.noNewData()).isFalse();

        // 5. 第一轮清单断言
        assertThat(result1.manifestPath()).isNotNull();
        Path manifestFile1 = Path.of(URI.create(result1.manifestPath()));
        assertThat(Files.isRegularFile(manifestFile1)).isTrue();
        JsonNode manifest1 = objectMapper.readTree(manifestFile1.toFile());

        assertThat(manifest1.get("acceptedRecords").asLong()).isEqualTo(51L);
        assertThat(manifest1.get("quarantinedRecords").asLong()).isEqualTo(4L);
        assertThat(manifest1.get("sourceCode").asText()).isEqualTo(RealIngestionTestSupport.SOURCE_A_CODE);
        assertThat(manifest1.get("sourceId").asLong()).isEqualTo(support.getSourceA().getId());
        assertThat(manifest1.get("profileVersion").asText()).isEqualTo("1.0");

        // R-N1-2 / S2-02 契约：来源 A 为 v1 画像直通不映射，清单 mappingVersion 与 mappingProfileHash 必须为 null
        assertThat(manifest1.has("mappingVersion")).as("mappingVersion 键必须存在").isTrue();
        assertThat(manifest1.get("mappingVersion").isNull())
                .as("未应用映射时 mappingVersion 必须为 null，禁止占位符")
                .isTrue();
        assertThat(manifest1.has("mappingProfileHash")).as("mappingProfileHash 键必须存在").isTrue();
        assertThat(manifest1.get("mappingProfileHash").isNull())
                .as("未应用映射时 mappingProfileHash 必须为 null")
                .isTrue();

        // 6. 第一轮检查点断言 (file_checkpoint)
        FileCheckpoint ckpt1 = support.getFileCheckpointMapper().selectOne(
                new LambdaQueryWrapper<FileCheckpoint>()
                        .eq(FileCheckpoint::getSourceId, support.getSourceA().getId())
                        .like(FileCheckpoint::getFilePath, "golden-20260901.jsonl"));
        assertThat(ckpt1).as("首轮检查点必须真实落库").isNotNull();
        assertThat(ckpt1.getNextOffset())
                .as("首轮下一个待读偏移必须等于首轮文件大小")
                .isEqualTo(initialFileSize);

        // 7. 增量追加：从 golden-20260901-positive.jsonl 提取前 3 行规范化数据追加到输入副本
        Path positiveRepoFile = RepoRoot.path("tests/golden-dataset/events/golden-20260901-positive.jsonl");
        List<String> positiveLines = Files.readAllLines(positiveRepoFile, StandardCharsets.UTF_8);
        assertThat(positiveLines.size()).as("positive 夹具必须至少包含 3 行").isGreaterThanOrEqualTo(3);

        List<String> append3 = positiveLines.subList(0, 3);
        String appendContent = String.join("\n", append3) + "\n";
        byte[] appendBytes = appendContent.getBytes(StandardCharsets.UTF_8);
        long appendedByteCount = appendBytes.length;

        Files.write(goldenInputCopy, appendBytes, StandardOpenOption.APPEND);
        long newFileSize = Files.size(goldenInputCopy);
        assertThat(newFileSize - initialFileSize)
                .as("追加后文件增长量必须严格等于追加字节数")
                .isEqualTo(appendedByteCount);

        // 8. 第二轮采集 (增量消费)
        IngestionService.RunResult result2 = support.getIngestionService().runOne(TraceContext.create());

        // 9. 第二轮结果断言：恰好消费新增 3 行
        assertThat(result2.status()).isEqualTo(IngestionBatch.STATUS_SUCCESS);
        assertThat(result2.recordCount())
                .as("增量轮必须恰好接受追加的 3 行")
                .isEqualTo(3L);
        assertThat(result2.quarantineCount())
                .as("增量轮隔离计数必须为 0")
                .isZero();
        assertThat(result2.errorCount()).isZero();
        assertThat(result2.noNewData()).isFalse();

        // 10. 跨轮累计断言
        long totalAcceptedAcrossRuns = result1.recordCount() + result2.recordCount();
        long totalQuarantinedAcrossRuns = result1.quarantineCount() + result2.quarantineCount();
        assertThat(totalAcceptedAcrossRuns).as("两轮累计接受行数 51 + 3 = 54").isEqualTo(54L);
        assertThat(totalQuarantinedAcrossRuns).as("两轮累计隔离行数 4 + 0 = 4").isEqualTo(4L);

        // 11. 第二轮检查点推进断言
        FileCheckpoint ckpt2 = support.getFileCheckpointMapper().selectById(ckpt1.getId());
        assertThat(ckpt2).as("检查点记录必须存在").isNotNull();
        assertThat(ckpt2.getNextOffset() - ckpt1.getNextOffset())
                .as("检查点推进量必须精确等于追加的字节数")
                .isEqualTo(appendedByteCount);
        assertThat(ckpt2.getNextOffset())
                .as("新检查点偏移必须严格等于追加后文件总大小")
                .isEqualTo(newFileSize);

        // 12. 第三轮重放断言 (无新数据追加)
        IngestionService.RunResult result3 = support.getIngestionService().runOne(TraceContext.create());
        assertThat(result3.noNewData()).as("无新数据追加时重放必须标记 noNewData=true").isTrue();
        assertThat(result3.recordCount()).isZero();
        assertThat(result3.quarantineCount()).isZero();

        FileCheckpoint ckpt3 = support.getFileCheckpointMapper().selectById(ckpt1.getId());
        assertThat(ckpt3.getNextOffset())
                .as("重放后检查点偏移保持不变")
                .isEqualTo(newFileSize);
    }
}
