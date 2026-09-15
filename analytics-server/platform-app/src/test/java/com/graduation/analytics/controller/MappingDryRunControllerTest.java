package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.graduation.analytics.auth.AuthInterceptor;
import com.graduation.analytics.auth.AuthService;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.mapping.MappingHash;
import com.graduation.analytics.mapping.dryrun.InMemoryMappingDryRunReportRepository;
import com.graduation.analytics.mapping.dryrun.MappingDryRunService;
import com.graduation.analytics.testsupport.RepoRoot;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S2-01B HTTP 层实测：真实控制器 + 真实 dry-run 服务 + 真实拦截器 + 真实异常映射（standaloneSetup）。
 *
 * <p>为什么要把**真服务**装进来而不是打桩：这一层唯一想量清楚的恰恰是"打桩会掩盖掉的东西"——
 * 36 字段报告经 Spring 自己的 mapper 序列化（含 {@code LocalDateTime} 的 jsr310、字段名、
 * 枚举/Map 形状），以及四个错误码到 HTTP 状态的真实落点。桩掉服务就等于把这三点重新变成推断。</p>
 *
 * <p>权限不是靠反射注释断言的（那由 {@code ControllerPermissionCoverageTest} 守护），
 * 这里实测三种身份的真实状态码：无会话 401、analyst 403 且报文带所需权限码、data_dev 200。</p>
 */
class MappingDryRunControllerTest {

    private static final String CONTRACT = "contract-specs/schemas/canonical-event.v1.schema.json";
    private static final String B3A_JSONL =
            "docs/acceptance/p5-heterogeneous-source-20260912/fixtures/b3a-missing-required.jsonl";

    /** 与 S2-01A 的身份画像同形；多声明一个引用不到的金额单位，用于未使用声明口径 */
    private static final String PROFILE_V1 = """
            {
              "profileVersion": "1.0",
              "sourceCode": "s2-01b-http",
              "canonical": { "schemaVersion": "1.0" },
              "eventTypeMapping": { "order_paid": "order_paid" },
              "fieldMapping": {
                "order_id": "order_id", "user_id": "user_id", "payment_id": "payment_id",
                "amount": "amount", "paid_at": "paid_at"
              },
              "enumSemantics": {},
              "timePolicy": { "field": "event_time", "formats": ["ISO_OFFSET_DATE_TIME"] },
              "amountPolicy": { "bySourceField": { "amount": "YUAN", "legacy_fen": "FEN" } }
            }
            """;

    private static final String ORDER_PAID_OK =
            "{\"event_id\":\"s2-01b-http-0001\",\"event_type\":\"order_paid\","
                    + "\"event_time\":\"2026-09-20T15:00:47+08:00\",\"ingest_time\":\"2026-09-20T15:00:48+08:00\","
                    + "\"source_system\":\"fixture-b\",\"schema_version\":\"1.0\",\"trace_id\":\"s2-01b-http-trc\","
                    + "\"payload\":{\"order_id\":\"1001\",\"user_id\":\"1\",\"payment_id\":\"P-1001\","
                    + "\"amount\":\"199.00\",\"paid_at\":\"2026-09-20T15:00:47+08:00\"}}";

    private static final Map<String, CurrentUser> TOKENS = Map.of(
            "admin-token", new CurrentUser(1L, "admin", "admin"),
            "dev-token", new CurrentUser(5L, "dev1", "data_dev"),
            "analyst-token", new CurrentUser(3L, "analyst1", "analyst"));

    private static final String SAMPLE_REF = "order-paid-mix.jsonl";

    @TempDir
    Path samples;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws IOException {
        String missingRequiredLine = Files.readString(RepoRoot.path(B3A_JSONL), StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank()).findFirst().orElseThrow();
        Files.writeString(samples.resolve(SAMPLE_REF), ORDER_PAID_OK + "\n" + missingRequiredLine + "\n",
                StandardCharsets.UTF_8);

        AuthService authService = mock(AuthService.class);
        TOKENS.forEach((token, user) -> when(authService.validate(token)).thenReturn(user));

        MappingDryRunService service = new MappingDryRunService(
                new InMemoryMappingDryRunReportRepository(),
                new EventClock(Clock.fixed(Instant.parse("2026-09-21T02:20:30Z"), ZoneId.of("Asia/Shanghai"))),
                RepoRoot.path(CONTRACT).toString(),
                samples.toString());

        mockMvc = MockMvcBuilders.standaloneSetup(new MappingDryRunController(service))
                .addInterceptors(new AuthInterceptor(authService, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler(), new PlatformExceptionAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(productionShapedMapper()))
                .build();
    }

    /**
     * 与运行期同形的 HTTP mapper：Spring Boot 默认关闭 {@code WRITE_DATES_AS_TIMESTAMPS}
     * （本仓另在 {@code application.yml} 显式声明），并由 builder 装配 classpath 上的 jsr310。
     *
     * <p>为什么不用 {@code @SpringBootTest}/{@code @JsonTest} 取"真"mapper：那会拉起含 3306 的完整上下文，
     * 属制度禁止范围。因此这里用与 Boot 相同构造链的 builder，并在
     * {@link #dateShapeIsIso8601OverHttp()} 里同时守护配置文件里的那条声明——两处一起才是「ISO 有唯一所有者」。
     * 实测结论：{@code Jackson2ObjectMapperBuilder} 默认即关闭 {@code WRITE_DATES_AS_TIMESTAMPS}
     * （ISO 串），而 {@code standaloneSetup} 的隐式默认转换器会输出时间戳数组——所以这里必须显式钉住转换器。</p>
     */
    private static ObjectMapper productionShapedMapper() {
        return Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Test
    @DisplayName("POST /dry-run（admin）→ 200：报告经 Spring mapper 完整序列化，校验和等于提交字节的 sha256")
    void dryRunReturnsSerializedReport() throws Exception {
        MvcResult result = postDryRun("admin-token", PROFILE_V1, SAMPLE_REF, 100);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("OK");
        assertThat((String) JsonPath.read(body, "$.data.reportId")).startsWith("dr-20260921102030-");
        assertThat((String) JsonPath.read(body, "$.data.sourceId")).isEqualTo("src-1");
        assertThat((int) JsonPath.read(body, "$.data.processedCount")).isEqualTo(2);
        assertThat((int) JsonPath.read(body, "$.data.acceptedCount")).isEqualTo(1);
        assertThat((int) JsonPath.read(body, "$.data.quarantinedCount")).isEqualTo(1);
        assertThat((String) JsonPath.read(body, "$.data.profileChecksum"))
                .isEqualTo(MappingHash.sha256Hex(PROFILE_V1));
        assertThat((String) JsonPath.read(body, "$.data.sampleRef")).isEqualTo(SAMPLE_REF);
        // 真实 canonical 预览（不是桩）：信封字段来自身份映射，平台字段 ingest_time 仍未落
        assertThat((String) JsonPath.read(body, "$.data.preview[0].canonical.event_id"))
                .isEqualTo("s2-01b-http-0001");
        Map<String, Object> canonical = JsonPath.read(body, "$.data.preview[0].canonical");
        assertThat(canonical).hasSize(7).doesNotContainKey("ingest_time")
                .containsEntry("schema_version", "1.0");
        // createdAt 由注入时钟决定（2026-09-21T02:20:30Z ⇒ 上海 10:20:30），正式输出为 ISO-8601 字符串
        assertThat((String) JsonPath.read(body, "$.data.createdAt")).isEqualTo("2026-09-21T10:20:30");
        assertThat((boolean) JsonPath.read(body, "$.data.activationEligible")).isFalse();
        assertThat((String) JsonPath.read(body, "$.data.violations[0].reason")).isEqualTo("EMPTY_FIELD");
        assertThat((int) JsonPath.read(body, "$.data.violations[0].lineNo")).isEqualTo(2);
        assertThat((String) JsonPath.read(body, "$.data.unusedDeclarations[0]")).isEqualTo("legacy_fen");
    }

    @Test
    @DisplayName("日期形状：application.yml 显式声明 ISO-8601，且 HTTP 输出实测为 ISO-8601 字符串（非时间戳数组）")
    void dateShapeIsIso8601OverHttp() throws Exception {
        String yml = Files.readString(
                RepoRoot.path("analytics-server/platform-app/src/main/resources/application.yml"),
                StandardCharsets.UTF_8);
        assertThat(yml).contains("write-dates-as-timestamps: false");

        String body = postDryRun("admin-token", PROFILE_V1, SAMPLE_REF, 10)
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(body, "$.data.createdAt"))
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")
                .isEqualTo("2026-09-21T10:20:30");
        // ObjectMapper 层直接实测（不经 HTTP）：同一构造链输出的就是 ISO 串
        assertThat(productionShapedMapper().writeValueAsString(Map.of("createdAt", LocalDateTime.parse("2026-09-21T10:20:30"))))
                .isEqualTo("{\"createdAt\":\"2026-09-21T10:20:30\"}");
        // 反例不写成断言：本轮实测发现 standaloneSetup 的隐式默认转换器给时间戳数组
        // （"createdAt":[2026,9,21,10,20,30]），而 Jackson2ObjectMapperBuilder 构造链给 ISO 串——
        // 两者形状不同，所以本测试显式钉住转换器，而不是依赖 standaloneSetup 的隐式默认。
    }

    @Test
    @DisplayName("权限实测：无会话 401 / analyst 403（报文带 runtime:manage）/ data_dev 200")
    void permissionIsEnforcedOverHttp() throws Exception {
        MvcResult anonymous = postDryRun(null, PROFILE_V1, SAMPLE_REF, 10);
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        assertThat(anonymous.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"UNAUTHORIZED\"");

        MvcResult analyst = postDryRun("analyst-token", PROFILE_V1, SAMPLE_REF, 10);
        assertThat(analyst.getResponse().getStatus()).isEqualTo(403);
        String analystBody = analyst.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(analystBody).contains("\"code\":\"FORBIDDEN_PERMISSION\"").contains("runtime:manage");

        assertThat(postDryRun("dev-token", PROFILE_V1, SAMPLE_REF, 10).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("入参非法 → 400 PARAM_INVALID（不做隐式默认）：limit 越界/缺失、sampleRef 空白/绝对路径、body 非 JSON")
    void invalidInputIs400() throws Exception {
        assertBadRequest(postDryRun("admin-token", PROFILE_V1, SAMPLE_REF, 101));
        assertBadRequest(postDryRun("admin-token", PROFILE_V1, SAMPLE_REF, 0));
        assertBadRequest(postDryRun("admin-token", PROFILE_V1, SAMPLE_REF, null));
        assertBadRequest(postDryRun("admin-token", PROFILE_V1, "   ", 10));
        assertBadRequest(postDryRun("admin-token", PROFILE_V1, "C:/Windows/win.ini", 10));
        assertBadRequest(postDryRun("admin-token", PROFILE_V1, "../outside.jsonl", 10));
        assertBadRequest(postDryRun("admin-token", PROFILE_V1, "file:///D:/x.jsonl", 10));
        assertBadRequest(postDryRun("admin-token", "  ", SAMPLE_REF, 10));
        // limit 传非数字字符串 ⇒ 反序列化失败 ⇒ 400（注意：Jackson 会把 "10" 这种数字字符串强转成 10，
        // 这是实测结果，不是设计选择；转换后仍要过 @Min/@Max）
        assertBadRequest(postDryRun("admin-token", PROFILE_V1, SAMPLE_REF, "abc"));
        assertBadRequest(mockMvc.perform(post("/api/v1/sources/src-1/mappings/dry-run")
                .header("Authorization", "Bearer admin-token")
                .contentType("application/json").content("{not json")).andReturn());
    }

    @Test
    @DisplayName("资源不存在 → 404：样本不存在 / 报告不存在（与 400 入参非法严格区分）")
    void missingResourcesAre404() throws Exception {
        MvcResult noSample = postDryRun("admin-token", PROFILE_V1, "not-there.jsonl", 10);
        assertThat(noSample.getResponse().getStatus()).isEqualTo(404);
        assertThat(noSample.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"DRY_RUN_SAMPLE_NOT_FOUND\"");

        MvcResult noReport = mockMvc.perform(get("/api/v1/sources/src-1/mappings/dry-runs/dr-20260921000000-deadbeef")
                .header("Authorization", "Bearer admin-token")).andReturn();
        assertThat(noReport.getResponse().getStatus()).isEqualTo(404);
        assertThat(noReport.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"DRY_RUN_REPORT_NOT_FOUND\"");
    }

    @Test
    @DisplayName("POST 后可按 reportId 取回同一张报告；换 sourceId 取回落 404（归属不泄漏）")
    void reportIsRetrievableAndScopedToSource() throws Exception {
        String reportId = JsonPath.read(
                postDryRun("admin-token", PROFILE_V1, SAMPLE_REF, 100)
                        .getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.reportId");

        MvcResult fetched = mockMvc.perform(get("/api/v1/sources/src-1/mappings/dry-runs/" + reportId)
                .header("Authorization", "Bearer admin-token")).andReturn();
        assertThat(fetched.getResponse().getStatus()).isEqualTo(200);
        String body = fetched.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(body, "$.data.reportId")).isEqualTo(reportId);
        assertThat((String) JsonPath.read(body, "$.data.sampleRef")).isEqualTo(SAMPLE_REF);

        MvcResult otherSource = mockMvc.perform(get("/api/v1/sources/src-2/mappings/dry-runs/" + reportId)
                .header("Authorization", "Bearer admin-token")).andReturn();
        assertThat(otherSource.getResponse().getStatus()).isEqualTo(404);
        assertThat(otherSource.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"DRY_RUN_REPORT_NOT_FOUND\"");
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult postDryRun(String token, String profileText, String sampleRef, Object limit) throws Exception {
        String body = "{\"profileText\":" + quote(profileText)
                + ",\"sampleRef\":" + quote(sampleRef)
                + ",\"limit\":" + (limit instanceof String s ? quote(s) : String.valueOf(limit)) + "}";
        var builder = post("/api/v1/sources/src-1/mappings/dry-run")
                .contentType("application/json").content(body);
        return mockMvc.perform(token == null ? builder : builder.header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static void assertBadRequest(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
        assertThat(body).contains("\"code\":\"PARAM_INVALID\"");
    }
}
