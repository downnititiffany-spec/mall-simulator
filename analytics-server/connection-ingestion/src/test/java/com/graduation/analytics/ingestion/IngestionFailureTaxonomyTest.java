package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.IngestionBatchFile;
import com.graduation.analytics.ingestion.entity.QuarantineRecord;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.mapping.MappingHash;
import com.graduation.analytics.mapping.activation.TestActiveMappingPointerStore;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.source.entity.SourceRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-02B：真实采集链路上的**失败三分类**与批次重放口径（L0，不连库；采集器 / 映射器 / 契约校验器
 * 全是真对象，只替身数据库访问）。
 *
 * <p>指导书 L68 要的是「采集断点、可重放输入、质量阻断、失败保旧快照、受控重试与错误定位」，
 * L143 要的是「重复、缺失、金额单位、时间格式、退款、迟到、重试和跨源同 ID」。
 * 这一组用例钉的是其中最容易被混为一谈的一件：**三种失败不能互相伪装**。</p>
 *
 * <ol>
 *   <li><b>系统自身异常</b>（库/IO 挂了）⇒ 该文件记 {@code errorCount}、批次 {@code FAILED}、
 *       <b>断点不推进</b>、<b>不产出 READY 清单</b>。绝不允许伪装成"隔离了一行"——那会让批次
 *       显示 {@code QUARANTINED}（一个业务态）而 checkpoint 照常推进，数据在无人察觉的情况下丢失；</li>
 *   <li><b>映射 / 业务数据问题</b> ⇒ 隔离行 + {@code MAPPING:<CODE>@<路径>} 原因，批次
 *       {@code QUARANTINED}，清单照写（本轮输入本身可交付，只是有坏行）；</li>
 *   <li><b>canonical 契约不满足</b> ⇒ 同样隔离，但原因来自契约校验器（**没有** {@code MAPPING:} 前缀），
 *       批次 {@code QUARANTINED}。三类原因在 {@code quarantine_record.reason} 一列里可区分。</li>
 * </ol>
 *
 * <p>再加一条重放口径：同一批次不得二次消费同一输入。判据是既有唯一键
 * {@code uk_batch_file(batch_id, file_path)} 已经记下的账：已 LANDED + 无新内容 = 幂等重放（下游读到 0 条），
 * 已 LANDED + 有新内容 = 显式冲突（批次 FAILED）。</p>
 *
 * <p><b>取证边界</b>：本类是 L0 内存替身，证明的是「服务层的分支与落盘后果」这一代码事实；
 * 真库上的唯一键、断点行与清单的真实读写属于真机步骤，两者不可互相替代。</p>
 */
class IngestionFailureTaxonomyTest {

    private static final String CONTRACT_PATH = "contract-specs/schemas/canonical-event.v1.schema.json";
    private static final String SOURCE_CODE = "s2-02b-raw-a";
    private static final String PROFILE_PATH = "profiles/raw-a.v2.json";
    private static final String EVENT_FILE = "pay-001.jsonl";
    private static final String OK_AT = "2026-09-21T09:30:00+08:00";

    /** 固定业务时间：2026-09-21T02:20:30Z = Asia/Shanghai 10:20:30。 */
    private static final EventClock CLOCK = new EventClock(
            Clock.fixed(Instant.parse("2026-09-21T02:20:30Z"), ZoneId.of("Asia/Shanghai")));

    /** 源侧字段名与 canonical 完全不同、金额以分给的 v2 画像（与 S2-02A 用例同款，便于对账）。 */
    private static final String V2_PROFILE = """
            {
              "profileVersion": "2.0",
              "sourceCode": "%s",
              "contractVersion": "1.0",
              "eventTypeMappings": {
                "sourceField": "kind",
                "values": { "paid_se": "order_paid" }
              },
              "fieldMappings": {
                "envelope": {
                  "id": "event_id", "kind": "event_type", "at": "event_time",
                  "sys": "source_system", "rev": "schema_version",
                  "tr": "trace_id", "data": "payload"
                },
                "payload": { "order_paid": {
                  "ord": "order_id", "buyer": "user_id", "pay": "payment_id",
                  "paid_fen": "amount", "paidAt": "paid_at" } }
              },
              "enumSemantics": {},
              "timePolicy": { "field": "at", "formats": ["ISO_OFFSET_DATE_TIME"], "zone": "Asia/Shanghai" },
              "amountPolicy": { "bySourceField": { "paid_fen": "FEN" } }
            }
            """.formatted(SOURCE_CODE);

    /** 既有 v1 兼容源的 canonical 形状原始行（种子源 mock-mall 的日常输入形态）。 */
    private static final String LEGACY_LINE = "{\"event_id\":\"legacy-1\",\"event_type\":\"order_paid\","
            + "\"event_time\":\"2026-09-21T09:30:00+08:00\","
            + "\"ingest_time\":\"2026-09-21T09:30:02+08:00\",\"source_system\":\"mock-mall\","
            + "\"schema_version\":\"1.0\",\"trace_id\":\"legacy-trace\","
            + "\"payload\":{\"order_id\":\"o-legacy\",\"user_id\":\"u-legacy\",\"payment_id\":\"p-legacy\","
            + "\"amount\":\"10.00\",\"paid_at\":\"2026-09-21T09:30:00+08:00\"}}";

    private final IngestionBatchMapper batchMapper = mock(IngestionBatchMapper.class);
    private final IngestionBatchFileMapper batchFileMapper = mock(IngestionBatchFileMapper.class);
    private final FileCheckpointMapper checkpointMapper = mock(FileCheckpointMapper.class);
    private final QuarantineRecordMapper quarantineRecordMapper = mock(QuarantineRecordMapper.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);
    private final SourceRegistryService sourceRegistryService = mock(SourceRegistryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 批次 id 递增：排除"两份清单其实是同一份被覆盖"这种解释。 */
    private final AtomicLong nextBatchId = new AtomicLong(101L);

    /**
     * 断点查询与批次文件账都要经过 MyBatis-Plus 的 lambda 列名缓存（平时由 SqlSessionFactory 建），
     * L0 测试手工建一次。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IngestionBatchFile.class);
    }

    // ---------------------------------------------------------------- ① 系统自身异常

    @Test
    @DisplayName("系统异常：批次 FAILED、不产出清单、断点不推进，且不伪装成隔离")
    void systemFailureFailsTheBatchWithoutPublishingOrAdvancing(@TempDir Path landingRoot,
                                                               @TempDir Path profileRoot) throws IOException {
        writeProfile(profileRoot, PROFILE_PATH, V2_PROFILE);
        stubRun(landingRoot, SOURCE_CODE, "2.0", PROFILE_PATH);
        // 第 1 行正常受理（异常前已落盘的"半成品"）；第 2 行映射缺失 ⇒ 走隔离写入 ⇒ 让隔离表 insert 抛异常
        writeEvents(landingRoot, paidLine("evt-1", OK_AT, "12345", "o-1"),
                paidLine("evt-2", OK_AT, "999", null));
        when(quarantineRecordMapper.insert(any(QuarantineRecord.class)))
                .thenThrow(new RuntimeException("模拟系统自身异常：隔离登记写库失败"));

        IngestionService.RunResult result = service(profileRoot).runOne(TraceContext.create());

        assertThat(result.status()).as("系统异常 ⇒ FAILED（不是 QUARANTINED）").isEqualTo("FAILED");
        assertThat(result.errorCount()).isEqualTo(1);
        // 计数口径 = **已完整走完的文件**的 FileResult 之和（IngestionService 第 200-213 行）。异常文件在
        // 映射/隔离写到一半时抛出 ⇒ 没有 FileResult ⇒ 半成品行不计入批次计数。批次是 FAILED、没有清单，
        // 计数只用于观测；**物理数据不隐藏**（下面两条断言盯的就是磁盘真值）。
        // 同 batchId 重跑时这也是"重读起点"的来源：失败文件没有 batch_file 行 ⇒ 重放守卫不拦它
        // （守卫按 uk_batch_file 判），已登记限制见 F-27。
        assertThat(result.recordCount()).as("异常文件没有 FileResult ⇒ 不计入批次计数").isZero();
        assertThat(result.quarantineCount()).as("系统异常不得记成隔离成功").isZero();
        verify(batchFileMapper, never()).insert(any(IngestionBatchFile.class));

        assertThat(result.manifestPath()).as("失败批次不产出清单").isNull();
        assertThat(manifestNames(landingRoot)).as("manifests/ 下不得出现本批次清单").isEmpty();

        verify(quarantineRecordMapper, times(1)).insert(any(QuarantineRecord.class));
        assertThat(Files.readString(Path.of(result.quarantineDir()).resolve(EVENT_FILE), StandardCharsets.UTF_8))
                .as("隔离原文先落盘、登记行失败 ⇒ 磁盘有原文而库里没有登记（重投会再隔离一次，at-least-once）")
                .isEqualTo(paidLine("evt-2", OK_AT, "999", null) + "\n");
        assertThat(Files.readString(Path.of(result.acceptedDir()).resolve(EVENT_FILE), StandardCharsets.UTF_8))
                .as("至少一次：异常前已受理的行留在原地，不静默丢弃")
                .isEqualTo(readCanonical(result.acceptedDir()) + "\n");

        verify(checkpointMapper, never()).insert(any(FileCheckpoint.class));
        verify(checkpointMapper, never()).updateById(any(FileCheckpoint.class));
    }

    // ---------------------------------------------------------------- ② 受控重试

    @Test
    @DisplayName("系统异常后重试：断点未推进 ⇒ 从头重读不丢数据；失败批次无清单 ⇒ 正式结果恰好一份")
    void retryAfterSystemFailureRelandsFromZeroAndDeliversExactlyOneManifest(@TempDir Path landingRoot,
                                                                            @TempDir Path profileRoot)
            throws IOException {
        writeProfile(profileRoot, PROFILE_PATH, V2_PROFILE);
        stubRun(landingRoot, SOURCE_CODE, "2.0", PROFILE_PATH);
        writeEvents(landingRoot, paidLine("evt-1", OK_AT, "12345", "o-1"),
                paidLine("evt-2", OK_AT, "999", null));
        when(quarantineRecordMapper.insert(any(QuarantineRecord.class)))
                .thenThrow(new RuntimeException("模拟系统自身异常：隔离登记写库失败"));

        IngestionService service = service(profileRoot);
        IngestionService.RunResult first = service.runOne(TraceContext.create());
        assertThat(first.status()).isEqualTo("FAILED");
        assertThat(first.manifestPath()).isNull();

        // 受控重试：同一个 landing 根、同一份输入，第二次不再异常（批次 id 递增为 102）
        when(quarantineRecordMapper.insert(any(QuarantineRecord.class))).thenReturn(1);
        IngestionService.RunResult second = service.runOne(TraceContext.create());

        assertThat(second.batchId()).as("重试是新批次（不是复用失败批次）").isEqualTo(102L);
        assertThat(second.status()).as("重试后只剩业务隔离行 ⇒ QUARANTINED").isEqualTo("QUARANTINED");
        assertThat(second.errorCount()).isZero();
        assertThat(second.recordCount()).as("断点未推进 ⇒ 第 1 行重新被受理，不丢数据").isEqualTo(1);
        assertThat(second.quarantineCount()).isEqualTo(1);

        List<String> manifests = manifestNames(landingRoot);
        assertThat(manifests).as("正式结果恰好一份，且属于成功那一轮").containsExactly("102.json");
        String manifest = Files.readString(landingRoot.resolve("manifests").resolve("102.json"),
                StandardCharsets.UTF_8);
        assertThat(manifest).as("从头重读：startOffset 必须是 0（失败轮没有推进断点）")
                .contains("\"startOffset\" : 0");
        assertThat(manifest).contains("\"acceptedRecords\" : 1").contains("\"quarantinedRecords\" : 1");

        verify(checkpointMapper, times(1)).insert(any(FileCheckpoint.class));
        assertThat(Files.readAllLines(Path.of(second.quarantineDir()).resolve(EVENT_FILE), StandardCharsets.UTF_8))
                .as("重试把第 2 行重新隔离了一次（at-least-once，库侧唯一键负责去重）")
                .hasSize(1);
    }

    // ---------------------------------------------------------------- ③ 业务隔离 vs 系统失败

    @Test
    @DisplayName("映射判死是业务隔离：批次 QUARANTINED、清单照写、原因带 MAPPING: 前缀")
    void mappingViolationQuarantinesTheLineButKeepsTheBatchDeliverable(@TempDir Path landingRoot,
                                                                      @TempDir Path profileRoot)
            throws IOException {
        writeProfile(profileRoot, PROFILE_PATH, V2_PROFILE);
        stubRun(landingRoot, SOURCE_CODE, "2.0", PROFILE_PATH);
        writeEvents(landingRoot, paidLine("evt-1", OK_AT, "12345", null));

        IngestionService.RunResult result = service(profileRoot).runOne(TraceContext.create());

        assertThat(result.status()).as("坏行 ⇒ QUARANTINED，不是 FAILED").isEqualTo("QUARANTINED");
        assertThat(result.errorCount()).isZero();
        assertThat(result.recordCount()).isZero();
        assertThat(result.quarantineCount()).isEqualTo(1);
        assertThat(manifestNames(landingRoot)).as("本轮输入本身可交付 ⇒ 清单照写").containsExactly("101.json");

        ArgumentCaptor<QuarantineRecord> captor = ArgumentCaptor.forClass(QuarantineRecord.class);
        verify(quarantineRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getReason())
                .as("映射原因必须能在 reason 一列里与系统失败、契约违规区分开")
                .startsWith("MAPPING:")
                .contains("EMPTY_FIELD@payload.order_id");
    }

    @Test
    @DisplayName("canonical 契约不满足也是隔离（原因无 MAPPING: 前缀），批次不记失败")
    void contractViolationOnLegacyPassthroughQuarantinesWithoutMappingPrefix(@TempDir Path landingRoot)
            throws IOException {
        String noTrace = LEGACY_LINE.replace(",\"trace_id\":\"legacy-trace\"", "");
        stubRun(landingRoot, "mock-mall", "1.0", "analytics-server/source-profiles/mock-mall.v1.json");
        writeEvents(landingRoot, noTrace);

        IngestionService.RunResult result = service(repoRoot()).runOne(TraceContext.create());

        assertThat(result.status()).isEqualTo("QUARANTINED");
        assertThat(result.errorCount()).isZero();
        assertThat(result.quarantineCount()).isEqualTo(1);
        assertThat(manifestNames(landingRoot)).containsExactly("101.json");

        ArgumentCaptor<QuarantineRecord> captor = ArgumentCaptor.forClass(QuarantineRecord.class);
        verify(quarantineRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getReason())
                .as("契约违规的原因来自契约校验器，不能被映射前缀吞掉")
                .contains("trace_id")
                .doesNotContain("MAPPING:");
    }

    // ---------------------------------------------------------------- ④ 批次重放口径

    @Test
    @DisplayName("同批次 + 同输入的幂等重放：不产出重复正式结果，也不必报冲突")
    void idempotentReplayOfTheSameBatchAndInputAddsNothing(@TempDir Path landingRoot,
                                                          @TempDir Path profileRoot) throws IOException {
        writeProfile(profileRoot, PROFILE_PATH, V2_PROFILE);
        stubRun(landingRoot, SOURCE_CODE, "2.0", PROFILE_PATH);
        Path file = writeEvents(landingRoot, paidLine("evt-1", OK_AT, "12345", "o-1"));
        // 本批次已经消费过该文件（唯一键 uk_batch_file 的那笔账），且断点已到文件尾 ⇒ 无新内容
        when(batchFileMapper.selectCount(any())).thenReturn(1L);
        when(checkpointMapper.selectOne(any())).thenReturn(checkpointAtEof(file));

        IngestionService.RunResult result = service(profileRoot).runOne(TraceContext.create());

        assertThat(result.status()).as("无新内容不是错误").isEqualTo("SUCCESS");
        assertThat(result.errorCount()).isZero();
        assertThat(result.recordCount()).isZero();
        assertThat(result.quarantineCount()).isZero();
        assertThat(result.noNewData()).isTrue();
        assertThat(manifestNames(landingRoot)).as("无新数据的批次仍写出清单（既有语义不变）")
                .containsExactly("101.json");
        assertThat(Files.readString(landingRoot.resolve("manifests").resolve("101.json"),
                StandardCharsets.UTF_8))
                .as("幂等重放不得产出重复记录")
                .contains("\"acceptedRecords\" : 0")
                .contains("\"quarantinedRecords\" : 0");

        verify(batchFileMapper, never()).insert(any(IngestionBatchFile.class));
        verify(checkpointMapper, never()).insert(any(FileCheckpoint.class));
        verify(quarantineRecordMapper, never()).insert(any(QuarantineRecord.class));
    }

    @Test
    @DisplayName("同批次 + 新内容：显式冲突（批次 FAILED、不产出清单、本轮字节一个都不进批次）")
    void sameBatchWithNewContentIsAnExplicitConflict(@TempDir Path landingRoot, @TempDir Path profileRoot)
            throws IOException {
        writeProfile(profileRoot, PROFILE_PATH, V2_PROFILE);
        stubRun(landingRoot, SOURCE_CODE, "2.0", PROFILE_PATH);
        writeEvents(landingRoot, paidLine("evt-1", OK_AT, "12345", "o-1"));
        // 本批次已 LANDED 该文件，而断点仍在文件头 ⇒ 该文件又有新内容可采
        when(batchFileMapper.selectCount(any())).thenReturn(1L);

        IngestionService.RunResult result = service(profileRoot).runOne(TraceContext.create());

        assertThat(result.status()).as("换了输入却复用批次 ⇒ 本轮不得继续").isEqualTo("FAILED");
        assertThat(result.errorCount()).isEqualTo(1);
        assertThat(result.recordCount()).isZero();
        assertThat(result.quarantineCount()).as("冲突不是数据问题，不得记成隔离").isZero();
        assertThat(result.manifestPath()).isNull();
        assertThat(manifestNames(landingRoot)).isEmpty();
        assertThat(Files.notExists(Path.of(result.acceptedDir()).resolve(EVENT_FILE)))
                .as("拒绝发生在写入之前：本轮字节一个都不进批次")
                .isTrue();

        verify(quarantineRecordMapper, never()).insert(any(QuarantineRecord.class));
        verify(batchFileMapper, never()).insert(any(IngestionBatchFile.class));
        verify(checkpointMapper, never()).insert(any(FileCheckpoint.class));
    }

    // ---------------------------------------------------------------- 装配

    private IngestionService service(Path profileRoot) {
        // S2-03：正式采集只使用"已激活"的画像，故装配时给源 3 一份激活记录（内容 = 写盘的 V2_PROFILE 字节）
        SourceMapper sourceMapper = new SourceMapper(profileRoot.toString(),
                repoFile(CONTRACT_PATH).toString(), CLOCK, objectMapper,
                TestActiveMappingPointerStore.withActive(3L, SOURCE_CODE, PROFILE_PATH, "2.0",
                        MappingHash.sha256Hex(V2_PROFILE)));
        LocalFileIngestor ingestor = new LocalFileIngestor(checkpointMapper, quarantineRecordMapper,
                new EventContractValidator(objectMapper), objectMapper, sourceMapper);
        return new IngestionService(batchMapper, batchFileMapper, ingestor, CLOCK,
                runtimeProfileService, sourceRegistryService, objectMapper, sourceMapper);
    }

    /**
     * 正常路径的桩：一个新批次（id 递增）从未消费过任何文件。
     *
     * <p>{@code batchFileMapper.selectCount} 显式桩成 0 而不是靠 Mockito 的默认 null——
     * 「本批次没有这笔账」要写成断言一样的事实，否则读的人分不清是真的没有，还是忘了桩。</p>
     */
    private void stubRun(Path landingRoot, String sourceCode, String profileVersion, String profilePath)
            throws IOException {
        Files.createDirectories(landingRoot.resolve("events"));

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.getActive()).thenReturn(profile);

        when(sourceRegistryService.currentSourceId()).thenReturn(Optional.of(3L));
        SourceRegistry row = new SourceRegistry();
        row.setId(3L);
        row.setSourceCode(sourceCode);
        row.setDisplayName(sourceCode);
        row.setIngestMode("FILE");
        row.setProfilePath(profilePath);
        row.setTimezone("Asia/Shanghai");
        row.setCurrency("CNY");
        row.setStatus("ACTIVE");
        row.setProfileVersion(profileVersion);
        when(sourceRegistryService.get(3L)).thenReturn(SourceRegistryView.of(row, 3L));

        when(checkpointMapper.selectOne(any())).thenReturn(null);
        when(batchFileMapper.selectCount(any())).thenReturn(0L);
        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            inv.<IngestionBatch>getArgument(0).setId(nextBatchId.getAndIncrement());
            return 1;
        });
        when(batchMapper.updateById(any(IngestionBatch.class))).thenReturn(1);
    }

    /** 一行 order_paid 原始行；{@code ord == null} 表示省略该源字段（用于"缺失必填"边界）。 */
    private static String paidLine(String id, String at, String fen, String ord) {
        return "{\"id\":\"" + id + "\",\"kind\":\"paid_se\",\"at\":\"" + at + "\",\"sys\":\"" + SOURCE_CODE
                + "\",\"rev\":\"1.0\",\"tr\":\"trace-" + id + "\",\"data\":{"
                + (ord == null ? "" : "\"ord\":\"" + ord + "\",")
                + "\"buyer\":\"u-1\",\"pay\":\"p-1\",\"paid_fen\":\"" + fen + "\",\"paidAt\":\"" + at + "\"}}";
    }

    private static Path writeEvents(Path landingRoot, String... lines) throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Path file = events.resolve(EVENT_FILE);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** 已消费到文件尾的断点行（键与采集端同一套：profile + 源 + 绝对路径）。 */
    private static FileCheckpoint checkpointAtEof(Path file) throws IOException {
        FileCheckpoint ckpt = new FileCheckpoint();
        ckpt.setRuntimeProfileId(1L);
        ckpt.setSourceId(3L);
        ckpt.setFilePath(LocalFileIngestor.checkpointKey(file));
        ckpt.setFileIdentity(LocalFileIngestor.fileIdentity(file));
        ckpt.setNextOffset(Files.size(file));
        return ckpt;
    }

    private static List<String> manifestNames(Path landingRoot) throws IOException {
        Path dir = landingRoot.resolve("manifests");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private String readCanonical(String acceptedDir) throws IOException {
        String text = Files.readString(Path.of(acceptedDir).resolve(EVENT_FILE), StandardCharsets.UTF_8);
        assertThat(text).as("异常前已受理的行必须真的落盘").isNotEmpty();
        return text.trim();
    }

    private static void writeProfile(Path root, String repoRelative, String text) throws IOException {
        Path file = root.resolve(repoRelative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /**
     * 契约文件是仓库里的中立真相（只读）。{@code com.graduation.analytics.testsupport.RepoRoot} 在
     * platform-common 的 **test** 作用域，本模块（未依赖 test-jar）看不到，故这里保留一个最小向上查找
     * （与 {@code MappingTestSupport} 同款；已登记的重复项，等 RepoRoot 提到 test-jar 后一并删除）。
     */
    private static Path repoRoot() {
        Path start = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(CONTRACT_PATH))) {
                return dir;
            }
        }
        throw new IllegalStateException("找不到仓库根：从 " + start + " 向上未发现 " + CONTRACT_PATH);
    }

    private static Path repoFile(String repoRelative) {
        return repoRoot().resolve(repoRelative);
    }
}
