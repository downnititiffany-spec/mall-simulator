package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.ingestion.entity.FileCheckpoint;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.QuarantineRecord;
import com.graduation.analytics.ingestion.mapper.FileCheckpointMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.analytics.mapping.MappingHash;
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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2-02：**真实链路**上的映射接入（L0，不连库；采集器与映射器都是真对象，只替身数据库访问）。
 *
 * <p>这一组用例是"接入真的通了"的证据，而不是"接口存在"：raw 行 → 映射 → canonical 落盘
 * （含平台生成的 {@code ingest_time}）→ 批次清单写出本次生效的画像版本与哈希。
 * 只替身三个 Mapper + 登记读口，映射器/采集器/契约校验器全部是真对象——
 * 用替身替掉它们就等于把要验证的东西自己写了一遍。</p>
 *
 * <p>钉住的三件事：</p>
 * <ol>
 *   <li><b>v2 画像生效</b>：落盘 canonical 带平台 ingest_time，清单 {@code mappingVersion} /
 *       {@code mappingProfileHash} 写的是**本次真的用过**的画像；</li>
 *   <li><b>v1 兼容画像不映射</b>：既有行逐字节直通，清单两个键保持 null（不得写占位值冒充已映射）；</li>
 *   <li><b>画像不可用即拒绝本轮</b>：在任何写入之前（连批次行都不 insert）。</li>
 * </ol>
 */
class IngestionServiceMappingTest {

    private static final String CONTRACT_PATH = "contract-specs/schemas/canonical-event.v1.schema.json";
    private static final String SOURCE_CODE = "s2-02-raw-a";
    private static final String PROFILE_PATH = "profiles/raw-a.v2.json";
    private static final String EVENT_FILE = "pay-001.jsonl";

    /** 固定业务时间：2026-09-21T02:20:30Z = Asia/Shanghai 10:20:30。 */
    private static final EventClock CLOCK = new EventClock(
            Clock.fixed(Instant.parse("2026-09-21T02:20:30Z"), ZoneId.of("Asia/Shanghai")));

    private static final String RAW_LINE = "{\"id\":\"evt-1\",\"kind\":\"paid_se\","
            + "\"at\":\"2026-09-21T09:30:00+08:00\",\"sys\":\"" + SOURCE_CODE + "\",\"rev\":\"1.0\","
            + "\"tr\":\"trace-1\",\"data\":{\"ord\":\"o-1\",\"buyer\":\"u-1\",\"pay\":\"p-1\","
            + "\"paid_fen\":\"12345\",\"paidAt\":\"2026-09-21T09:30:01+08:00\"}}";

    /** 源侧字段名与 canonical 完全不同、金额以分给的 v2 画像。 */
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

    /**
     * 断点查询要经过 MyBatis-Plus 的 lambda 列名缓存（平时由 SqlSessionFactory 建），L0 测试手工建一次。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                FileCheckpoint.class);
    }

    // ---------------------------------------------------------------- 装配

    private IngestionService service(Path profileRoot) {
        SourceMapper sourceMapper = new SourceMapper(profileRoot.toString(),
                repoFile(CONTRACT_PATH).toString(), CLOCK, objectMapper);
        LocalFileIngestor ingestor = new LocalFileIngestor(checkpointMapper, quarantineRecordMapper,
                new EventContractValidator(objectMapper), objectMapper, sourceMapper);
        return new IngestionService(batchMapper, batchFileMapper, ingestor, CLOCK,
                runtimeProfileService, sourceRegistryService, objectMapper, sourceMapper);
    }

    private void stubRun(Path landingRoot, String sourceCode, String profileVersion, String profilePath,
                         String rawLine) throws IOException {
        Path events = Files.createDirectories(landingRoot.resolve("events"));
        Files.writeString(events.resolve(EVENT_FILE), rawLine + "\n", StandardCharsets.UTF_8);

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
        when(batchMapper.insert(any(IngestionBatch.class))).thenAnswer(inv -> {
            inv.<IngestionBatch>getArgument(0).setId(101L);
            return 1;
        });
        when(batchMapper.updateById(any(IngestionBatch.class))).thenReturn(1);
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

    // ---------------------------------------------------------------- ① v2 画像：真映射

    @Test
    @DisplayName("v2 画像生效：raw → canonical 落盘（带平台 ingest_time），清单写出本次生效的画像版本与哈希")
    void appliedMappingLandsCanonicalAndIsRecordedInManifest(@TempDir Path landingRoot,
                                                            @TempDir Path profileRoot) throws IOException {
        writeProfile(profileRoot, PROFILE_PATH, V2_PROFILE);
        stubRun(landingRoot, SOURCE_CODE, "2.0", PROFILE_PATH, RAW_LINE);

        IngestionService.RunResult result = service(profileRoot).runOne(TraceContext.create());

        assertThat(result.recordCount()).as("映射成功的行算采集成功").isEqualTo(1);
        assertThat(result.quarantineCount()).isZero();

        String accepted = Files.readString(
                Path.of(result.acceptedDir()).resolve(EVENT_FILE), StandardCharsets.UTF_8);
        JsonNode canonical = objectMapper.readTree(accepted.trim());
        assertThat(canonical.path("event_type").asText()).isEqualTo("order_paid");
        assertThat(canonical.path("ingest_time").asText())
                .as("ingest_time 是平台采集时间（注入的业务时间源、秒级、+08:00），不是源侧 at 的值")
                .isEqualTo("2026-09-21T10:20:30+08:00");
        assertThat(canonical.path("event_time").asText()).isEqualTo("2026-09-21T09:30:00+08:00");
        assertThat(canonical.path("payload").path("amount").asText())
                .as("分 → 元 在真实链路上生效（金额单位是映射期的职责）")
                .isEqualTo("123.45");
        assertThat(Files.readString(Path.of(result.quarantineDir()).resolve(EVENT_FILE), StandardCharsets.UTF_8))
                .as("没有任何行该被隔离")
                .isEmpty();

        JsonNode manifest = manifestOf(result);
        assertThat(manifest.path("mappingVersion").asText())
                .as("本次真的用了 2.0 画像 ⇒ 不得再写 null（null 的语义是「未应用映射」）")
                .isEqualTo("2.0");
        assertThat(manifest.path("mappingProfileHash").asText())
                .as("画像哈希与 dry-run 的 profileChecksum 同算法：预览与实际可对账")
                .isEqualTo(MappingHash.sha256Hex(V2_PROFILE));
        assertThat(manifest.path("profileVersion").asText())
                .as("登记列语义不变（裁决 8），仍是 registry 的 profile_version")
                .isEqualTo("2.0");
        assertThat(manifest.path("acceptedRecords").asLong()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- ② v1 兼容画像：不映射

    @Test
    @DisplayName("v1 兼容画像（种子源）：不映射、行逐字节直通，清单两个映射键保持 null")
    void v1ProfileKeepsByteIdenticalPassthrough(@TempDir Path landingRoot) throws IOException {
        stubRun(landingRoot, "mock-mall", "1.0",
                "analytics-server/source-profiles/mock-mall.v1.json", LEGACY_LINE);

        IngestionService.RunResult result = service(repoRoot()).runOne(TraceContext.create());

        assertThat(Files.readString(Path.of(result.acceptedDir()).resolve(EVENT_FILE), StandardCharsets.UTF_8))
                .as("直通不得改写既有 canonical 行（含它自带的 ingest_time）")
                .isEqualTo(LEGACY_LINE + "\n");
        assertThat(result.recordCount()).isEqualTo(1);

        JsonNode manifest = manifestOf(result);
        assertThat(manifest.has("mappingVersion")).isTrue();
        assertThat(manifest.get("mappingVersion").isNull())
                .as("未应用映射 ⇒ null（裁决 6：禁止 '0'/''/'v1' 占位值冒充已映射）")
                .isTrue();
        assertThat(manifest.has("mappingProfileHash"))
                .as("键必须存在：读侧要能区分「未应用映射」与「字段缺失」")
                .isTrue();
        assertThat(manifest.get("mappingProfileHash").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- ③ 画像不可用：拒绝本轮

    @Test
    @DisplayName("画像文件缺失：任何写入之前 fail-closed（批次行都不产生），错误码 MAPPING_PROFILE_INVALID")
    void unusableProfileRejectsRunBeforeAnyWrite(@TempDir Path landingRoot,
                                                @TempDir Path profileRoot) throws IOException {
        stubRun(landingRoot, SOURCE_CODE, "2.0", "profiles/absent.v2.json", RAW_LINE);

        assertThatThrownBy(() -> service(profileRoot).runOne(TraceContext.create()))
                .isInstanceOf(PlatformBizException.class)
                .satisfies(e -> assertThat(((PlatformBizException) e).getCode())
                        .isEqualTo(PlatformBizException.MAPPING_PROFILE_INVALID));

        verify(batchMapper, never()).insert(any(IngestionBatch.class));
        verify(quarantineRecordMapper, never()).insert(any(QuarantineRecord.class));
        assertThat(Files.exists(landingRoot.resolve("accepted")))
                .as("拒绝要发生在建目录之前：否则会留下空批次目录冒充「本轮跑过」")
                .isFalse();
    }

    // ---------------------------------------------------------------- 仓根定位

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
