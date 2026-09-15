package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-02B 边界矩阵：指导书 L143 点名的「重复、缺失、金额单位、时间格式、退款、迟到、重试、跨源同 ID」
 * 在**真实链路**上各自走到哪一步（L0，不连库；采集器 / 映射器 / 契约校验器全为真对象）。
 *
 * <p>每一个边界都断言"去哪了"而不是"没报错"：要么落 accepted（并断言落盘后的 canonical 值），
 * 要么落 quarantine（并断言 {@code reason} 的具体码与路径）。矩阵见 {@link #boundaryMatrix()} 注释。</p>
 *
 * <p><b>本类明确不实现、也不声称的两件事</b>（否则就是把别人的职责记在自己账上）：</p>
 * <ul>
 *   <li><b>批次内重复 event_id 不去重</b>：唯一写入者是 DWD 拒绝记录 {@code DUPLICATE_EVENT}
 *       （{@code warehouse/ddl/01-dwd.sql:74/90}、{@code DwdSql.scala:64}）。采集层两条都收，
 *       本类把这一事实钉成断言；{@code MappingReason.DUPLICATE_EVENT} 的 javadoc 说它属 S2-02，
 *       与 D-107 / D-117「重复与未来时间归 DWD（P2-06）」冲突，以裁决为准，只记录不实现。</li>
 *   <li><b>迟到 / 未来时间不做窗口判定</b>：同理属 DWD 清洗（{@code FUTURE_TIME} → P2-06）。
 *       迟到行在采集层是合法输入，本类断言它**照收**。</li>
 * </ul>
 */
class IngestionBoundaryMatrixTest {

    private static final String CONTRACT_PATH = "contract-specs/schemas/canonical-event.v1.schema.json";
    private static final String SOURCE_CODE = "s2-02b-raw-a";
    private static final String PROFILE_PATH = "profiles/boundary.v2.json";
    private static final String EVENT_FILE = "boundary.jsonl";
    /** 合法的事件时间（自带 +08:00 偏移，第一声明的格式）。 */
    private static final String OK_AT = "2026-09-21T09:30:00+08:00";
    private static final String LEGACY_PROFILE_PATH = "analytics-server/source-profiles/mock-mall.v1.json";

    /** 固定业务时间：2026-09-21T02:20:30Z = Asia/Shanghai 10:20:30（平台 ingest_time 的唯一来源）。 */
    private static final EventClock CLOCK = new EventClock(
            Clock.fixed(Instant.parse("2026-09-21T02:20:30Z"), ZoneId.of("Asia/Shanghai")));

    private static final String PLATFORM_INGEST_TIME = "2026-09-21T10:20:30+08:00";

    /**
     * 一份画像覆盖三类边界：两个事件类型（含退款）、两种金额单位（源字段不同）、两种时间格式
     * （自带偏移 + 本地时间按 zone 归一）。{@code amountPolicy} 的键是**源字段名**，
     * 而一个 canonical 目标字段只能由一个源字段供给（{@code MappingExecutor.buildPayload} 的
     * {@code sourceByTarget} 是"目标 → 源"的单值映射），因此 FEN 与 YUAN 分别挂在 order_paid
     * 与 refund_created 两个事件类型上，不做同类型双源字段。
     */
    private static final String V2_PROFILE = """
            {
              "profileVersion": "2.0",
              "sourceCode": "%s",
              "contractVersion": "1.0",
              "eventTypeMappings": {
                "sourceField": "kind",
                "values": { "paid_se": "order_paid", "refund_se": "refund_created" }
              },
              "fieldMappings": {
                "envelope": {
                  "id": "event_id", "kind": "event_type", "at": "event_time",
                  "sys": "source_system", "rev": "schema_version",
                  "tr": "trace_id", "data": "payload"
                },
                "payload": {
                  "order_paid": {
                    "ord": "order_id", "buyer": "user_id", "pay": "payment_id",
                    "paid_fen": "amount", "paidAt": "paid_at" },
                  "refund_created": {
                    "rfd": "refund_id", "ord": "order_id", "buyer": "user_id",
                    "rfd_yuan": "amount", "why": "reason", "rfdAt": "created_at" }
                }
              },
              "enumSemantics": {},
              "timePolicy": {
                "field": "at",
                "formats": ["ISO_OFFSET_DATE_TIME", "yyyy-MM-dd HH:mm:ss"],
                "zone": "Asia/Shanghai"
              },
              "amountPolicy": { "bySourceField": { "paid_fen": "FEN", "rfd_yuan": "YUAN" } }
            }
            """.formatted(SOURCE_CODE);

    private final IngestionBatchMapper batchMapper = mock(IngestionBatchMapper.class);
    private final IngestionBatchFileMapper batchFileMapper = mock(IngestionBatchFileMapper.class);
    private final FileCheckpointMapper checkpointMapper = mock(FileCheckpointMapper.class);
    private final QuarantineRecordMapper quarantineRecordMapper = mock(QuarantineRecordMapper.class);
    private final RuntimeProfileService runtimeProfileService = mock(RuntimeProfileService.class);
    private final SourceRegistryService sourceRegistryService = mock(SourceRegistryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicLong nextBatchId = new AtomicLong(101L);
    /** 当前激活源（D-035：任何时候只有一个 ACTIVE 源）。 */
    private final AtomicLong currentSourceId = new AtomicLong(3L);
    /**
     * 内存断点表：**按源分开存**（真实查询条件带 {@code source_id} 由
     * {@code LocalFileIngestorSourceIsolationTest} 的 10 个用例钉住；本类另有一条
     * {@code selectOne} 查询形状断言，见 {@link #twoSourcesKeepTheirOwnBatchesAndTheSameBusinessId()}）。
     */
    private final Map<Long, FileCheckpoint> checkpointBySource = new HashMap<>();

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                IngestionBatchFile.class);
    }

    // ---------------------------------------------------------------- ① 边界矩阵

    /**
     * 矩阵（一份输入 7 行，按落点分两组）：
     * <pre>
     * 行 边界                             落点      断言
     * 1  金额单位 FEN（分）                accepted  amount=123.45、event_time 归一为 +08:00
     * 2  退款 + 金额单位 YUAN + 本地时间    accepted  event_type=refund_created、amount=99.90、created_at 原样
     * 3  金额单位 FEN 非整数               quarantine BAD_AMOUNT@payload.amount
     * 4  缺失必填（order_id）              quarantine EMPTY_FIELD@payload.order_id
     * 5  时间格式两种都不匹配              quarantine BAD_TIME_FORMAT@event_time
     * 6  迟到（event_time 早 3 天）        accepted  无窗口判定（FUTURE_TIME 归 DWD/P2-06）
     * 7  与行 1 同 event_id（批次内重复）   accepted  采集层不去重（DUPLICATE_EVENT 归 DWD）
     * </pre>
     */
    @Test
    @DisplayName("边界矩阵：每条边界要么落 accepted（断言 canonical 值）要么落 quarantine（断言原因码）")
    void boundaryMatrixRoutesEachBoundaryToAcceptOrQuarantine(@TempDir Path landingRoot,
                                                              @TempDir Path profileRoot) throws IOException {
        writeProfile(profileRoot, PROFILE_PATH, V2_PROFILE);
        stubRun(landingRoot, SOURCE_CODE, "2.0", PROFILE_PATH, List.of(
                paid("evt-1", "2026-09-21T09:30:00+08:00", "12345", "o-1"),
                refund("evt-2", "2026-09-21 09:30:00", "99.9"),
                paid("evt-3", "2026-09-21T09:31:00+08:00", "12.345", "o-3"),
                paid("evt-4", "2026-09-21T09:32:00+08:00", "500", null),
                paid("evt-5", "2026/09/21 09:33:00", "600", "o-5"),
                paid("evt-6", "2026-09-18T09:30:00+08:00", "700", "o-6"),
                paid("evt-1", "2026-09-21T09:34:00+08:00", "800", "o-7")));

        IngestionService.RunResult result = service(profileRoot).runOne(TraceContext.create());

        assertThat(result.errorCount()).as("矩阵里没有系统异常").isZero();
        assertThat(result.recordCount()).as("受理 4 行（1/2/6/7）").isEqualTo(4);
        assertThat(result.quarantineCount()).as("隔离 3 行（3/4/5）").isEqualTo(3);
        assertThat(result.status()).as("有坏行 ⇒ QUARANTINED（不是 FAILED）").isEqualTo("QUARANTINED");

        List<JsonNode> accepted = acceptedLines(result);
        assertThat(accepted).hasSize(4);

        // 行 1：FEN → 元（除 100、两位小数）；event_time 保留自带偏移
        assertThat(accepted.get(0).path("event_id").asText()).isEqualTo("evt-1");
        assertThat(accepted.get(0).path("event_type").asText()).isEqualTo("order_paid");
        assertThat(accepted.get(0).path("payload").path("amount").asText())
                .as("FEN ÷ 100").isEqualTo("123.45");
        assertThat(accepted.get(0).path("event_time").asText()).isEqualTo("2026-09-21T09:30:00+08:00");
        assertThat(accepted.get(0).path("ingest_time").asText())
                .as("平台生成，不是源侧 at").isEqualTo(PLATFORM_INGEST_TIME);

        // 行 2：退款 + YUAN（精确到分）+ 本地时间按 zone 归一
        assertThat(accepted.get(1).path("event_type").asText()).isEqualTo("refund_created");
        assertThat(accepted.get(1).path("payload").path("amount").asText())
                .as("YUAN 只做精确舍入，不乘 100").isEqualTo("99.90");
        assertThat(accepted.get(1).path("payload").path("reason").asText()).isEqualTo("质量问题");
        assertThat(accepted.get(1).path("payload").path("created_at").asText())
                .isEqualTo("2026-09-21T09:20:00+08:00");
        assertThat(accepted.get(1).path("event_time").asText())
                .as("第二种声明格式（本地时间）按 timePolicy.zone 归一成 +08:00")
                .isEqualTo("2026-09-21T09:30:00+08:00");

        // 行 6：迟到照收（采集层没有时间窗口）
        assertThat(accepted.get(2).path("event_id").asText()).isEqualTo("evt-6");
        assertThat(accepted.get(2).path("event_time").asText())
                .as("迟到不是采集层的拒收理由（FUTURE_TIME/迟到窗口归 DWD 清洗，P2-06）")
                .isEqualTo("2026-09-18T09:30:00+08:00");

        // 行 7：同 event_id 重复照收（去重归 DWD）
        assertThat(accepted.get(3).path("event_id").asText()).isEqualTo("evt-1");
        assertThat(accepted.stream().filter(n -> "evt-1".equals(n.path("event_id").asText())).count())
                .as("批次内重复 event_id 采集层不去重：唯一写入者是 DWD 的 DUPLICATE_EVENT（DwdSql.scala:64）")
                .isEqualTo(2);

        // 隔离行：原文照抄 + 原因码逐个可对账
        assertThat(Files.readAllLines(Path.of(result.quarantineDir()).resolve(EVENT_FILE),
                StandardCharsets.UTF_8))
                .as("隔离区保留原文，不只是原因")
                .hasSize(3);
        ArgumentCaptor<QuarantineRecord> captor = ArgumentCaptor.forClass(QuarantineRecord.class);
        verify(quarantineRecordMapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues().stream().map(QuarantineRecord::getReason).toList())
                .as("三类边界各自给出码 + 路径，且顺序等于文件顺序")
                .containsExactly(
                        "MAPPING:BAD_AMOUNT@payload.amount",
                        "MAPPING:EMPTY_FIELD@payload.order_id",
                        "MAPPING:BAD_TIME_FORMAT@event_time");

        JsonNode manifest = manifestOf(result);
        assertThat(manifest.path("acceptedRecords").asLong()).isEqualTo(4);
        assertThat(manifest.path("quarantinedRecords").asLong()).isEqualTo(3);
        assertThat(manifest.path("mappingVersion").asText()).isEqualTo("2.0");
        assertThat(manifest.path("sourceCode").asText()).isEqualTo(SOURCE_CODE);
    }

    // ---------------------------------------------------------------- ② 缺失 / NULL / BLANK

    /**
     * 必填三态在**真实采集链路**上的可观测结果：三者都判 {@code EMPTY_FIELD}，原因文本完全相同
     * （{@code MappingIssue} 只写 {@code MAPPING:<CODE>@<path>}，detail 不进原因）。
     *
     * <p>三态的**细分**（MISSING / NULL / BLANK）由执行器自身的用例钉住：
     * {@code MappingExecutorTest} 规则 5 三条（缺键 / 显式 null / trim 后空白，detail 分别为
     * MISSING / NULL / BLANK）。本用例只证明"到了采集链路上，三种写法都进隔离、原文一字不改"。</p>
     */
    @Test
    @DisplayName("缺失 / NULL / BLANK：真实链路上都判 EMPTY_FIELD 进隔离，且隔离区保留原文")
    void missingNullAndBlankAllQuarantineAsEmptyField(@TempDir Path landingRoot,
                                                     @TempDir Path profileRoot) throws IOException {
        writeProfile(profileRoot, PROFILE_PATH, V2_PROFILE);
        String missing = paid("evt-missing", OK_AT, "100", null);
        String nullValue = paidWith("evt-null", OK_AT, "100", "\"ord\":null,");
        String blank = paidWith("evt-blank", OK_AT, "100", "\"ord\":\"\",");
        stubRun(landingRoot, SOURCE_CODE, "2.0", PROFILE_PATH, List.of(missing, nullValue, blank));

        IngestionService.RunResult result = service(profileRoot).runOne(TraceContext.create());

        assertThat(result.errorCount()).isZero();
        assertThat(result.recordCount()).as("三态都不产出 canonical").isZero();
        assertThat(result.quarantineCount()).isEqualTo(3);
        assertThat(result.status()).isEqualTo("QUARANTINED");

        ArgumentCaptor<QuarantineRecord> captor = ArgumentCaptor.forClass(QuarantineRecord.class);
        verify(quarantineRecordMapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues().stream().map(QuarantineRecord::getReason).toList())
                .as("原因文本不含三态细分（细分归 MappingExecutorTest 规则 5）")
                .containsExactly("MAPPING:EMPTY_FIELD@payload.order_id",
                        "MAPPING:EMPTY_FIELD@payload.order_id",
                        "MAPPING:EMPTY_FIELD@payload.order_id");
        assertThat(Files.readAllLines(Path.of(result.quarantineDir()).resolve(EVENT_FILE),
                StandardCharsets.UTF_8))
                .as("NULL / BLANK 也是「读到就读到」，隔离区保留原文一字不改")
                .containsExactly(missing, nullValue, blank);

        JsonNode manifest = manifestOf(result);
        assertThat(manifest.path("acceptedRecords").asLong()).isZero();
        assertThat(manifest.path("quarantinedRecords").asLong()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- ③ 跨源同 ID

    @Test
    @DisplayName("跨源同 ID：两个源各自成批次、各自留一份原文，第二个源不被第一个源的断点跳过")
    void twoSourcesKeepTheirOwnBatchesAndTheSameBusinessId(@TempDir Path landingRoot,
                                                          @TempDir Path profileRoot) throws IOException {
        writeProfile(profileRoot, PROFILE_PATH, V2_PROFILE);

        // 源 A（3）：v2 画像，源侧字段名与 canonical 不同 ⇒ 逐行映射
        stubRun(landingRoot, SOURCE_CODE, "2.0", PROFILE_PATH,
                List.of(paid("evt-shared", "2026-09-21T09:30:00+08:00", "12345", "o-1")));
        IngestionService.RunResult a = service(profileRoot).runOne(TraceContext.create());

        // 源 B（4）：同一个 landing 根、同一个文件名、同一个业务 event_id，但走 v1 兼容直通
        String legacy = "{\"event_id\":\"evt-shared\",\"event_type\":\"order_paid\","
                + "\"event_time\":\"2026-09-21T09:30:00+08:00\","
                + "\"ingest_time\":\"2026-09-21T09:30:02+08:00\",\"source_system\":\"mock-mall\","
                + "\"schema_version\":\"1.0\",\"trace_id\":\"legacy-trace\","
                + "\"payload\":{\"order_id\":\"o-legacy\",\"user_id\":\"u-legacy\","
                + "\"payment_id\":\"p-legacy\",\"amount\":\"10.00\","
                + "\"paid_at\":\"2026-09-21T09:30:00+08:00\"}}";
        switchSource(4L);
        stubRun(landingRoot, "mock-mall", "1.0", LEGACY_PROFILE_PATH, List.of(legacy));
        IngestionService.RunResult b = service(repoRoot()).runOne(TraceContext.create());

        assertThat(a.batchId()).isEqualTo(101L);
        assertThat(b.batchId()).as("每个源各自成批次").isEqualTo(102L);
        assertThat(a.recordCount()).isEqualTo(1);
        assertThat(b.recordCount()).as("第二个源没有被第一个源的断点静默跳过").isEqualTo(1);

        JsonNode manifestA = manifestOf(a);
        JsonNode manifestB = manifestOf(b);
        assertThat(manifestA.path("sourceId").asLong()).isEqualTo(3L);
        assertThat(manifestA.path("sourceCode").asText()).isEqualTo(SOURCE_CODE);
        assertThat(manifestA.path("mappingVersion").asText()).isEqualTo("2.0");
        assertThat(manifestB.path("sourceId").asLong()).isEqualTo(4L);
        assertThat(manifestB.path("sourceCode").asText()).isEqualTo("mock-mall");
        assertThat(manifestB.path("mappingVersion").isNull())
                .as("v1 兼容源不映射：null 的语义是「未应用映射」").isTrue();
        assertThat(manifestB.path("files").get(0).path("startOffset").asLong())
                .as("第二个源按自己的断点读（本源的断点表里没有该文件 ⇒ 从 0 开始）")
                .isZero();

        // 同一个业务 ID 在两个源下各自留了一份：跨源同 ID 既不互相覆盖，也不被采集层合并
        assertThat(acceptedLines(a)).extracting(n -> n.path("event_id").asText()).containsExactly("evt-shared");
        assertThat(acceptedLines(b)).extracting(n -> n.path("event_id").asText()).containsExactly("evt-shared");
        assertThat(Files.readString(Path.of(b.acceptedDir()).resolve(EVENT_FILE), StandardCharsets.UTF_8))
                .as("v1 兼容源逐字节直通").isEqualTo(legacy + "\n");

        // 断点查询形状：唯一键前两列必须都在条件里，否则"按源隔离"只是注释里的话
        verify(checkpointMapper, atLeastOnce()).selectOne(argThat(w ->
                w instanceof LambdaQueryWrapper<?> lw
                        && lw.getTargetSql().contains("runtime_profile_id = ?")
                        && lw.getTargetSql().contains("source_id = ?")));
        verify(quarantineRecordMapper, never()).insert(any(QuarantineRecord.class));
    }

    // ---------------------------------------------------------------- 装配

    private IngestionService service(Path profileRoot) {
        // S2-03：正式采集只使用"已激活"的画像。本用例的源 3 是 v2（需要激活记录），
        // 源 4 走 v1 只读兼容（在激活门之前就返回 legacy），因此只需要源 3 的激活记录。
        SourceMapper sourceMapper = new SourceMapper(profileRoot.toString(),
                repoFile(CONTRACT_PATH).toString(), CLOCK, objectMapper,
                TestActiveMappingPointerStore.withActive(3L, SOURCE_CODE, PROFILE_PATH, "2.0",
                        MappingHash.sha256Hex(V2_PROFILE)));
        LocalFileIngestor ingestor = new LocalFileIngestor(checkpointMapper, quarantineRecordMapper,
                new EventContractValidator(objectMapper), objectMapper, sourceMapper);
        return new IngestionService(batchMapper, batchFileMapper, ingestor, CLOCK,
                runtimeProfileService, sourceRegistryService, objectMapper, sourceMapper);
    }

    private void stubRun(Path landingRoot, String sourceCode, String profileVersion, String profilePath,
                         List<String> rawLines) throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Files.writeString(events.resolve(EVENT_FILE), String.join("\n", rawLines) + "\n",
                StandardCharsets.UTF_8);

        RuntimeProfile profile = new RuntimeProfile();
        profile.setId(1L);
        profile.setLandingUri(landingRoot.toUri().toString());
        when(runtimeProfileService.getActive()).thenReturn(profile);

        // 源身份由 currentSourceId 驱动：本用例的两个源共用同一个 runtime_profile_id（D-037 触发场景）
        long sourceId = currentSourceId.get();
        when(sourceRegistryService.currentSourceId())
                .thenAnswer(inv -> Optional.of(currentSourceId.get()));
        SourceRegistry row = new SourceRegistry();
        row.setId(sourceId);
        row.setSourceCode(sourceCode);
        row.setDisplayName(sourceCode);
        row.setIngestMode("FILE");
        row.setProfilePath(profilePath);
        row.setTimezone("Asia/Shanghai");
        row.setCurrency("CNY");
        row.setStatus("ACTIVE");
        row.setProfileVersion(profileVersion);
        when(sourceRegistryService.get(anyLong())).thenReturn(SourceRegistryView.of(row, sourceId));

        when(checkpointMapper.selectOne(any()))
                .thenAnswer(inv -> checkpointBySource.get(currentSourceId.get()));
        when(checkpointMapper.insert(any(FileCheckpoint.class))).thenAnswer(inv -> {
            FileCheckpoint ckpt = inv.getArgument(0);
            checkpointBySource.put(ckpt.getSourceId(), ckpt);
            return 1;
        });
        when(batchFileMapper.selectCount(any())).thenReturn(0L);
        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            inv.<IngestionBatch>getArgument(0).setId(nextBatchId.getAndIncrement());
            return 1;
        });
        when(batchMapper.updateById(any(IngestionBatch.class))).thenReturn(1);
    }

    /** 测试内切换"当前激活源"：下一次 {@code stubRun} 与采集读口都按新源走。 */
    private void switchSource(long sourceId) {
        currentSourceId.set(sourceId);
    }

    private static String paid(String id, String at, String fen, String ord) {
        return paidWith(id, at, fen, ord == null ? "" : "\"ord\":\"" + ord + "\",");
    }

    /**
     * @param ordFragment 直接注入 payload 里的 order_id 片段（含尾逗号），用来表达三态：
     *                    缺键 {@code ""} / 显式 null {@code "ord":null,} / 空白 {@code "ord":"",}
     */
    private static String paidWith(String id, String at, String fen, String ordFragment) {
        return "{\"id\":\"" + id + "\",\"kind\":\"paid_se\",\"at\":\"" + at + "\",\"sys\":\"" + SOURCE_CODE
                + "\",\"rev\":\"1.0\",\"tr\":\"trace-" + id + "\",\"data\":{"
                + ordFragment
                + "\"buyer\":\"u-1\",\"pay\":\"p-1\",\"paid_fen\":\"" + fen + "\",\"paidAt\":\"" + at + "\"}}";
    }

    private static String refund(String id, String at, String yuan) {
        return "{\"id\":\"" + id + "\",\"kind\":\"refund_se\",\"at\":\"" + at + "\",\"sys\":\"" + SOURCE_CODE
                + "\",\"rev\":\"1.0\",\"tr\":\"trace-" + id + "\",\"data\":{"
                + "\"rfd\":\"r-1\",\"ord\":\"o-refund\",\"buyer\":\"u-1\",\"rfd_yuan\":\"" + yuan + "\","
                + "\"why\":\"质量问题\",\"rfdAt\":\"2026-09-21T09:20:00+08:00\"}}";
    }

    private List<JsonNode> acceptedLines(IngestionService.RunResult result) throws IOException {
        Path file = Path.of(result.acceptedDir()).resolve(EVENT_FILE);
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                nodes.add(objectMapper.readTree(line));
            }
        }
        return nodes;
    }

    private JsonNode manifestOf(IngestionService.RunResult result) throws IOException {
        assertThat(result.manifestPath()).as("清单 URI 必须存在").isNotNull();
        Path manifest = Path.of(URI.create(result.manifestPath()));
        assertThat(Files.isRegularFile(manifest)).as("清单必须真的落盘：%s", manifest).isTrue();
        return objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
    }

    private static void writeProfile(Path root, String repoRelative, String text) throws IOException {
        Path file = root.resolve(repoRelative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** 契约文件是仓库里的中立真相（只读）；已登记的仓库根查找重复项，见 S2-02A 用例同款注释。 */
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
