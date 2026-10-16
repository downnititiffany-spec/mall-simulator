package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.QuarantineRecord;
import com.graduation.analytics.mapping.MappingHash;
import com.graduation.analytics.mapping.activation.ActiveMappingPointer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-N1A 交付物 2：来源 B (fixture-shop-b) 真实映射与持久化采集集成测试套件。
 *
 * <p>按 R-N1-4 硬约束：
 * <ul>
 *   <li>真实 MySQL 3307 隔离实例；</li>
 *   <li>真实 {@link com.graduation.analytics.mapping.ingest.SourceMapper}；</li>
 *   <li>真实 {@link com.graduation.analytics.mapping.activation.MappingActivationService} 与
 *       {@link com.graduation.analytics.mapping.activation.JdbcActiveMappingPointerStore}；</li>
 *   <li>真实 {@link com.graduation.analytics.ingestion.mapper.FileCheckpointMapper}、
 *       {@link com.graduation.analytics.ingestion.mapper.IngestionBatchMapper}、
 *       {@link com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper}、
 *       {@link com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper}；</li>
 *   <li>测试仅在 isolated 档选中（{@code @Tag("it")}），默认套件不受影响。</li>
 * </ul>
 * </p>
 */
@Tag("it")
@ExtendWith(IsolationProfileCondition.class)
@DisplayName("WP-N1A 交付物 2: 来源 B (fixture-shop-b) 真实映射与持久化采集集成测试")
class SourceBRealIngestionIT {

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
    @DisplayName("交付物 1.3 负向控制：指针缺失时整轮拒绝(MAPPING_NOT_ACTIVE)，零写入")
    void negativeControl_pointerAbsent_rejectsWithMappingNotActiveAndWritesNothing(@TempDir Path tempLanding) {
        support.assertNegativeControlRejection(tempLanding);
    }

    @Test
    @DisplayName("交付物 2 正向全链：真实映射 normal+fault，断言清单、规范化行、ORACLE 字段、隔离分类与重放")
    void realIngestion_sourceB_fullAssertions(@TempDir Path landingRoot) throws IOException {
        // 1. 执行交付物 1.2 激活自动化流水线，确保真实行写入 source_mapping_active
        support.activateSourceB();
        support.switchToSourceB(landingRoot);

        // 2. 准备输入夹具到 <landing>/events
        Path eventsDir = Files.createDirectories(landingRoot.resolve("events"));
        Path normalSource = RepoRoot.path("fixtures/source-b/fixture-shop-b/normal.jsonl");
        Path faultSource = RepoRoot.path("fixtures/source-b/fixture-shop-b/fault.jsonl");

        Path normalInput = eventsDir.resolve("normal.jsonl");
        Path faultInput = eventsDir.resolve("fault.jsonl");
        Files.copy(normalSource, normalInput);
        Files.copy(faultSource, faultInput);

        long normalFileSize = Files.size(normalInput);
        long faultFileSize = Files.size(faultInput);
        assertThat(normalFileSize).as("normal.jsonl 必须有真实字节").isGreaterThan(0L);
        assertThat(faultFileSize).as("fault.jsonl 必须有真实字节").isGreaterThan(0L);

        // 3. 执行生产采集入口 IngestionService.runOne
        IngestionService.RunResult result = support.getIngestionService().runOne(TraceContext.create());

        // 4. 基本指标与状态校验
        // 状态口径（IngestionService 定版）：本批含隔离行 ⇒ QUARANTINED；SUCCESS 仅当零隔离。
        assertThat(result.status()).isEqualTo(IngestionBatch.STATUS_QUARANTINED);
        assertThat(result.recordCount()).as("normal.jsonl 38 行映射成功").isEqualTo(38L);
        assertThat(result.quarantineCount()).as("fault.jsonl 5 行全部隔离").isEqualTo(5L);
        assertThat(result.errorCount()).as("整轮无未捕获异常").isZero();
        assertThat(result.fileCount()).as("处理 2 个输入文件").isEqualTo(2);
        assertThat(result.noNewData()).as("首轮读到新数据").isFalse();

        // 5. 批次清单断言 (landing/manifests/{batchId}.json)
        assertThat(result.manifestPath()).as("批次清单 URI 必填").isNotNull();
        Path manifestFile = Path.of(URI.create(result.manifestPath()));
        assertThat(Files.isRegularFile(manifestFile)).as("清单必须真实落盘").isTrue();
        JsonNode manifest = objectMapper.readTree(manifestFile.toFile());

        assertThat(manifest.get("acceptedRecords").asLong()).isEqualTo(38L);
        assertThat(manifest.get("quarantinedRecords").asLong()).isEqualTo(5L);
        assertThat(manifest.get("mappingVersion").asText()).isEqualTo("2.0");

        // 独立计算画像文件字节 SHA-256 并比对
        byte[] profileBytes = Files.readAllBytes(RepoRoot.path(RealIngestionTestSupport.SOURCE_B_PROFILE_PATH));
        String expectedProfileHash = MappingHash.sha256Hex(profileBytes);
        assertThat(manifest.get("mappingProfileHash").asText())
                .as("manifest.mappingProfileHash 必须与画像字节独立 SHA-256 完全一致")
                .isEqualTo(expectedProfileHash);
        assertThat(manifest.get("sourceCode").asText()).isEqualTo(RealIngestionTestSupport.SOURCE_B_CODE);
        assertThat(manifest.get("sourceId").asLong()).isEqualTo(support.getSourceB().getId());
        assertThat(manifest.get("profileVersion").asText()).isEqualTo("2.0");

        // 6. 接受区规范化输出校验 (accepted/{batchId}/normal.jsonl)
        Path acceptedNormal = landingRoot.resolve(result.acceptedDir()).resolve("normal.jsonl");
        assertThat(Files.isRegularFile(acceptedNormal)).as("accepted 目录下必须存在 normal.jsonl 输出").isTrue();
        List<String> acceptedLines = Files.readAllLines(acceptedNormal, StandardCharsets.UTF_8);
        assertThat(acceptedLines).as("accepted normal.jsonl 必须恰好 38 行").hasSize(38);

        Map<String, JsonNode> acceptedNodes = new LinkedHashMap<>();
        for (String line : acceptedLines) {
            JsonNode node = objectMapper.readTree(line);
            // 校验规范化信封
            assertThat(node.has("event_id")).as("缺少 event_id").isTrue();
            assertThat(node.has("event_type")).as("缺少 event_type").isTrue();
            assertThat(node.has("event_time")).as("缺少 event_time").isTrue();
            assertThat(node.has("source_system")).as("缺少 source_system").isTrue();
            assertThat(node.has("schema_version")).as("缺少 schema_version").isTrue();
            assertThat(node.has("ingest_time")).as("缺少平台生成的 ingest_time").isTrue();
            assertThat(node.has("trace_id")).as("缺少 trace_id").isTrue();
            assertThat(node.has("payload")).as("缺少 payload").isTrue();

            assertThat(node.get("source_system").asText()).isEqualTo(RealIngestionTestSupport.SOURCE_B_CODE);
            assertThat(node.get("schema_version").asText()).isEqualTo("1.0");

            acceptedNodes.put(node.get("event_id").asText(), node);
        }

        // ORACLE.md §3 字段级深度校验
        // [ORACLE.md §3 行 36: 0001 MEMBER_JOINED -> user_registered]
        JsonNode evt0001 = acceptedNodes.get("B-EVT-0001");
        assertThat(evt0001).as("B-EVT-0001 必须存在").isNotNull();
        assertThat(evt0001.get("event_type").asText()).isEqualTo("user_registered");
        JsonNode payload0001 = evt0001.get("payload");
        assertThat(payload0001.get("user_id").asText()).isEqualTo("2100917055761973250");
        assertThat(payload0001.get("age_group").asText()).isEqualTo("35-44");
        assertThat(payload0001.get("city_level").asText()).isEqualTo("tier3");
        assertThat(payload0001.get("member_level").asText()).isEqualTo("silver");
        assertThat(payload0001.get("register_time").asText()).isEqualTo("2026-09-18T09:00:00+08:00");

        // [ORACLE.md §3 行 37: 0005 ITEM_LISTED -> product_created]
        JsonNode evt0005 = acceptedNodes.get("B-EVT-0005");
        assertThat(evt0005).as("B-EVT-0005 必须存在").isNotNull();
        assertThat(evt0005.get("event_type").asText()).isEqualTo("product_created");
        JsonNode payload0005 = evt0005.get("payload");
        assertThat(payload0005.get("product_id").asText()).isEqualTo("1001");
        assertThat(payload0005.get("product_name").asText()).isEqualTo("B-轻量保温杯");
        assertThat(payload0005.get("category_id").asText()).isEqualTo("11");
        assertThat(payload0005.get("brand_id").asText()).isEqualTo("901");
        assertThat(payload0005.get("price").asText()).as("ORACLE.md §3: price 2990 FEN -> 29.90 YUAN").isEqualTo("29.90");
        assertThat(payload0005.get("cost").asText()).as("ORACLE.md §3: cost 1800 FEN -> 18.00 YUAN").isEqualTo("18.00");
        assertThat(payload0005.get("status").asText()).isEqualTo("on_sale");

        // [ORACLE.md §3 行 38: 0008 PAGE_VIEW -> behavior]
        JsonNode evt0008 = acceptedNodes.get("B-EVT-0008");
        assertThat(evt0008).as("B-EVT-0008 必须存在").isNotNull();
        assertThat(evt0008.get("event_type").asText()).isEqualTo("behavior");
        JsonNode payload0008 = evt0008.get("payload");
        assertThat(payload0008.get("user_id").asText()).isEqualTo("2100917055761973250");
        assertThat(payload0008.get("product_id").asText()).isEqualTo("1001");
        assertThat(payload0008.get("behavior_type").asText()).isEqualTo("view");
        assertThat(payload0008.get("channel").asText()).isEqualTo("app");
        assertThat(payload0008.get("session_id").asText()).isEqualTo("B-SESS-1");

        // [ORACLE.md §3 行 39: 0016 ORDER_PLACED -> order_created]
        JsonNode evt0016 = acceptedNodes.get("B-EVT-0016");
        assertThat(evt0016).as("B-EVT-0016 必须存在").isNotNull();
        assertThat(evt0016.get("event_type").asText()).isEqualTo("order_created");
        JsonNode payload0016 = evt0016.get("payload");
        assertThat(payload0016.get("order_id").asText()).isEqualTo("2100917058081423362");
        assertThat(payload0016.get("user_id").asText()).isEqualTo("2100917055761973250");
        assertThat(payload0016.get("total_amount").asText()).as("画像映射 grand_minor -> total_amount；ORACLE.md §3: 5980 FEN -> 59.80 YUAN").isEqualTo("59.80");
        assertThat(payload0016.get("status").asText()).isEqualTo("CREATED");
        JsonNode items0016 = payload0016.get("items");
        assertThat(items0016.isArray() && items0016.size() == 1).isTrue();
        assertThat(items0016.get(0).get("product_id").asText()).isEqualTo("1001");
        assertThat(items0016.get(0).get("amount").asText()).as("ORACLE.md §3: line_minor 5980 FEN -> 59.80 YUAN").isEqualTo("59.80");

        // [ORACLE.md §3 行 40: 0019 PAID_OK -> order_paid, 时间转换与金额转换]
        JsonNode evt0019 = acceptedNodes.get("B-EVT-0019");
        assertThat(evt0019).as("B-EVT-0019 必须存在").isNotNull();
        assertThat(evt0019.get("event_type").asText()).isEqualTo("order_paid");
        // occur_at 2026-09-18T03:12:00Z 经 timePolicy (Asia/Shanghai) 转换为 2026-09-18T11:12:00+08:00
        assertThat(evt0019.get("event_time").asText())
                .as("ORACLE.md §3: occur_at 03:12:00Z 归一为东八区 2026-09-18T11:12:00+08:00")
                .isEqualTo("2026-09-18T11:12:00+08:00");
        JsonNode payload0019 = evt0019.get("payload");
        assertThat(payload0019.get("order_id").asText()).isEqualTo("2100917058517630978");
        assertThat(payload0019.get("user_id").asText()).isEqualTo("2100917057120927745");
        assertThat(payload0019.get("payment_id").asText()).isEqualTo("B-PAY-0002");
        assertThat(payload0019.get("amount").asText()).as("ORACLE.md §3: paid_minor 5980 FEN -> 59.80 YUAN").isEqualTo("59.80");
        assertThat(payload0019.get("paid_at").asText()).isEqualTo("2026-09-18T03:12:00Z");

        // 7. 隔离区输出校验 (quarantine/{batchId}/fault.jsonl 与 quarantine_record 表)
        Path quarantineFault = landingRoot.resolve(result.quarantineDir()).resolve("fault.jsonl");
        assertThat(Files.isRegularFile(quarantineFault)).as("quarantine 目录下必须存在 fault.jsonl 输出").isTrue();
        List<String> quarantineLines = Files.readAllLines(quarantineFault, StandardCharsets.UTF_8);
        assertThat(quarantineLines).as("quarantine fault.jsonl 必须恰好 5 行").hasSize(5);

        List<QuarantineRecord> qRecords = support.getQuarantineRecordMapper().selectList(
                new LambdaQueryWrapper<QuarantineRecord>().eq(QuarantineRecord::getBatchId, result.batchId()));
        assertThat(qRecords).as("quarantine_record 表中必须恰好 5 条记录").hasSize(5);

        // 全部具有 MAPPING 溯源标识（由 SourceMapper 产生，非 validator）
        assertThat(qRecords).allSatisfy(record -> {
            assertThat(record.getReason())
                    .as("隔离原因必须带 MAPPING: 溯源前缀")
                    .startsWith("MAPPING:");
        });

        // 提取 5 个原因码，断言两两互异并与 ORACLE.md §5 的五类违例精确对齐
        Set<String> reasonCodes = qRecords.stream()
                .map(r -> extractReasonCode(r.getReason()))
                .collect(Collectors.toSet());
        assertThat(reasonCodes).as("5 个隔离原因码必须两两互异").hasSize(5);
        assertThat(reasonCodes).containsExactlyInAnyOrder(
                "UNKNOWN_EVENT_TYPE",          // F001: state=LEAVE_REVIEW 未登记
                "BAD_AMOUNT",                  // F002: paid_minor=5980.5 (FEN 非整数)
                "BAD_TIME_FORMAT",             // F003: occur_at=2026/09/18 15:02:00 (格式不匹配)
                "EMPTY_FIELD",                  // F004: 缺少 evt_no (必填字段缺失)
                "UNSUPPORTED_SCHEMA_VERSION"   // F005: rev=2.0 (版本不支持)
        );

        // 8. 检查点落库断言 (file_checkpoint)
        FileCheckpoint ckptNormal = support.getFileCheckpointMapper().selectOne(
                new LambdaQueryWrapper<FileCheckpoint>()
                        .eq(FileCheckpoint::getSourceId, support.getSourceB().getId())
                        .like(FileCheckpoint::getFilePath, "normal.jsonl"));
        assertThat(ckptNormal).as("normal.jsonl 检查点必须落库").isNotNull();
        assertThat(ckptNormal.getNextOffset())
                .as("normal.jsonl 下一个偏移必须等于该文件大小")
                .isEqualTo(normalFileSize);

        FileCheckpoint ckptFault = support.getFileCheckpointMapper().selectOne(
                new LambdaQueryWrapper<FileCheckpoint>()
                        .eq(FileCheckpoint::getSourceId, support.getSourceB().getId())
                        .like(FileCheckpoint::getFilePath, "fault.jsonl"));
        assertThat(ckptFault).as("fault.jsonl 检查点必须落库").isNotNull();
        assertThat(ckptFault.getNextOffset())
                .as("fault.jsonl 下一个偏移必须等于该文件大小")
                .isEqualTo(faultFileSize);

        // 9. 重放校验 (同输入二次 runOne => noNewData)
        IngestionService.RunResult replayResult = support.getIngestionService().runOne(TraceContext.create());
        assertThat(replayResult.noNewData()).as("重放时没有新字节，noNewData 必须为 true").isTrue();
        assertThat(replayResult.recordCount()).as("重放时接受计数为 0").isZero();
        assertThat(replayResult.quarantineCount()).as("重放时隔离计数为 0").isZero();

        // 验证文件总行数未被重复追加
        List<String> acceptedLinesAfterReplay = Files.readAllLines(acceptedNormal, StandardCharsets.UTF_8);
        assertThat(acceptedLinesAfterReplay).hasSize(38);
    }

    private static String extractReasonCode(String reason) {
        // 原因格式: MAPPING:<CODE>@<path>...
        String withoutPrefix = reason.substring("MAPPING:".length());
        int atIdx = withoutPrefix.indexOf('@');
        return atIdx >= 0 ? withoutPrefix.substring(0, atIdx) : withoutPrefix;
    }
}
