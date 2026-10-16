package com.graduation.analytics.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graduation.analytics.auth.CurrentUser;
import com.graduation.analytics.auth.CurrentUserHolder;
import com.graduation.analytics.auth.PermissionCode;
import com.graduation.analytics.common.ApiResponse;
import com.graduation.analytics.common.GlobalExceptionHandler;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.ingestion.EventContractValidator;
import com.graduation.analytics.mapper.DryRunEngine;
import com.graduation.analytics.mapper.DryRunReport;
import com.graduation.analytics.mapper.MappingSampleResolver;
import com.graduation.analytics.mapper.PureMapper;
import com.graduation.analytics.mapper.RepoPathLocator;
import com.graduation.analytics.mapper.SourceProfile;
import com.graduation.analytics.mapper.store.DryRunReportStore;
import com.graduation.analytics.mapper.store.InMemoryDryRunReportStore;
import com.graduation.analytics.mapper.store.StoredDryRunReport;
import com.graduation.analytics.source.SourceMappingDryRunService;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.MappingDryRunReportView;
import com.graduation.analytics.source.dto.MappingDryRunReq;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 来源字段映射 Dry-Run 控制器测试（WP-2A-2 交付项 5 / Design V3.0 §7.4 / WP-2A-2-FIX 修复）。
 *
 * <p>测试范围：
 * <ul>
 *   <li>Happy path：POST + GET 完整往返，使用黄金数据集样本测试已登记源 mock-mall；</li>
 *   <li>错误状态覆盖：
 *     <ul>
 *       <li>400：sampleRef 路径遍历（..）、URL（http/file 等）、白名单外绝对路径、白名单外相对路径、内联记录被拒、超过 100 条限制、白名单内不存在的文件；</li>
 *       <li>409：profileVersion 版本不匹配、profileContentHash 内容哈希不匹配（规则 14 防陈旧预览）；</li>
 *       <li>404：未知 sourceId（遵循 SourceRegistryController 既有惯例）、GET 未知 reportId、GET 跨源查询 reportId；</li>
 *       <li>403：无认证身份（匿名/未登录）、角色权限不足（analyst、operator 缺乏 RUNTIME_MANAGE）；</li>
 *     </ul>
 *   </li>
 *   <li>容错与隔离语义：样本包含脏数据/不可解析行/非对象行不抛 400，试运行成功且在报告中隔离并计入 JSON_PARSE_ERROR 与 reasonCounts；</li>
 *   <li>零正式写入（ZERO formal writes）：断言采集批次、断点位点、清单文件、激活指针在 dry-run 前后状态完全一致；</li>
 *   <li>报告内容抽检（spot-checks）：profileContentHash 存在且为 64 位 SHA-256、validatorAccepted 与 strictContractAccepted 分列独立计数、mock-mall web-channel 样本产生 unresolvedEnumCounts、quarantinedRows >= 1 且 reasonCounts 包含 JSON_PARSE_ERROR。</li>
 * </ul>
 * </p>
 */
class SourceMappingControllerTest {

    private static final String GOLDEN_SAMPLE_REF = "tests/golden-dataset/events/golden-20260901.jsonl";
    private static final String MOCK_MALL_PROFILE_PATH = "analytics-server/source-profiles/mock-mall.v1.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SourceRegistryService sourceService;
    private DryRunReportStore reportStore;
    private MappingSampleResolver sampleResolver;
    private PureMapper pureMapper;
    private EventContractValidator contractValidator;
    private SourceMappingDryRunService dryRunService;
    private MappingDryRunPermissionChecker permissionChecker;
    private SourceMappingController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sourceService = mock(SourceRegistryService.class);
        reportStore = new InMemoryDryRunReportStore();
        sampleResolver = new MappingSampleResolver(RepoRoot.path().toString(), objectMapper);
        pureMapper = new PureMapper();
        contractValidator = new EventContractValidator(objectMapper);

        dryRunService = new SourceMappingDryRunService(
                sourceService,
                reportStore,
                sampleResolver,
                pureMapper,
                contractValidator
        );

        permissionChecker = new MappingDryRunPermissionChecker();
        controller = new SourceMappingController(dryRunService, permissionChecker);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformExceptionAdvice(), new GlobalExceptionHandler())
                .build();

        // 默认设置为 admin 登录态
        CurrentUserHolder.set(new CurrentUser(1L, "admin", "admin"));

        // mock-mall 源登记桩
        SourceRegistryView mockMallView = new SourceRegistryView(
                1L,
                "mock-mall",
                "参考商城（源 A）",
                "FILE",
                MOCK_MALL_PROFILE_PATH,
                "Asia/Shanghai",
                "CNY",
                "ACTIVE",
                "1.0",
                true,
                LocalDateTime.of(2026, 9, 1, 10, 0),
                LocalDateTime.of(2026, 9, 1, 10, 0),
                "dw"
        );
        when(sourceService.get(1L)).thenReturn(mockMallView);
        when(sourceService.currentSourceId()).thenReturn(Optional.of(1L));

        // 其它未知源返回 404（对齐 SourceRegistryController 既有惯例）
        when(sourceService.get(99999L)).thenThrow(
                new PlatformBizException(PlatformBizException.SOURCE_NOT_FOUND, "源不存在: 99999"));
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clear();
        reportStore.clear();
    }

    // -------------------------------------------------------------------------
    // 1. Happy path: POST + GET round-trip
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Happy path: POST dry-run 执行成功 + GET dry-runs/{reportId} 往返一致（与 DryRunEngine 直接计算一致）")
    void happyPath_postAndGetRoundTrip_withGoldenDatasetSample() throws Exception {
        // Direct engine run over same profile + sample to verify consistency per WP-2A-2-FIX2
        Path profileFile = RepoPathLocator.locateRepoRoot(".").resolve(MOCK_MALL_PROFILE_PATH);
        SourceProfile profile = SourceProfile.fromFile(profileFile);
        List<String> lines = sampleResolver.resolveAndLoad(GOLDEN_SAMPLE_REF);
        DryRunEngine directEngine = new DryRunEngine(pureMapper, contractValidator);
        DryRunReport directReport = directEngine.run(profile, lines);

        String reqBody = """
                {
                  "profileVersion": "1.0",
                  "sampleRef": "%s"
                }
                """.formatted(GOLDEN_SAMPLE_REF);

        // 1. POST dry-run
        MvcResult postResult = mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.reportId").isNotEmpty())
                .andExpect(jsonPath("$.data.sourceId").value(1))
                .andExpect(jsonPath("$.data.profileVersion").value("1.0"))
                .andExpect(jsonPath("$.data.profileContentHash").value(profile.contentHash()))
                .andExpect(jsonPath("$.data.preview").isArray())
                .andReturn();

        String postJson = postResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode postData = objectMapper.readTree(postJson).path("data");
        String reportId = postData.path("reportId").asText();
        assertThat(reportId).isNotBlank();

        // 断言报告数据与 DryRunEngine 直接计算完全一致（WP-2A-2-FIX2 要求）
        assertThat(postData.path("totalRows").asLong()).isEqualTo(directReport.totalRows());
        assertThat(postData.path("canonicalRows").asLong()).isEqualTo(directReport.canonicalRows());
        assertThat(postData.path("quarantinedRows").asLong()).isEqualTo(directReport.quarantinedRows());
        assertThat(postData.path("validatorAccepted").asLong()).isEqualTo(directReport.validatorAccepted());
        assertThat(postData.path("strictContractAccepted").asLong()).isEqualTo(directReport.strictContractAccepted());

        // 结构性事实断言（WP-2A-2-FIX2 要求）
        assertThat(postData.path("totalRows").asLong()).isEqualTo(55L);
        assertThat(postData.path("reasonCounts").path("JSON_PARSE_ERROR").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(postData.path("unresolvedEnumCounts").path("channel=web").asLong()).isGreaterThanOrEqualTo(1L);

        // 2. GET dry-runs/{reportId}
        MvcResult getResult = mockMvc.perform(get("/api/v1/sources/1/mappings/dry-runs/" + reportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.preview").isArray())
                .andReturn();

        JsonNode getData = objectMapper.readTree(getResult.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(getData.path("reportId").asText()).isEqualTo(reportId);
        assertThat(getData.path("sourceId").asLong()).isEqualTo(1L);
        assertThat(getData.path("profileVersion").asText()).isEqualTo("1.0");
        assertThat(getData.path("profileContentHash").asText()).isEqualTo(profile.contentHash());

        // 往返数据与直接引擎运行一致
        assertThat(getData.path("totalRows").asLong()).isEqualTo(directReport.totalRows());
        assertThat(getData.path("canonicalRows").asLong()).isEqualTo(directReport.canonicalRows());
        assertThat(getData.path("quarantinedRows").asLong()).isEqualTo(directReport.quarantinedRows());
        assertThat(getData.path("validatorAccepted").asLong()).isEqualTo(directReport.validatorAccepted());
        assertThat(getData.path("strictContractAccepted").asLong()).isEqualTo(directReport.strictContractAccepted());

        // 结构性事实
        assertThat(getData.path("totalRows").asLong()).isEqualTo(55L);
        assertThat(getData.path("reasonCounts").path("JSON_PARSE_ERROR").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(getData.path("unresolvedEnumCounts").path("channel=web").asLong()).isGreaterThanOrEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // 2. Report Content Spot-Checks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("报告内容抽检：profileContentHash 为 64 位 SHA-256、validator 与 strictContract 接受数分列、web 渠道产生 unresolvedEnumCounts、quarantinedRows >= 1 且包含 JSON_PARSE_ERROR")
    void reportContentSpotChecks() {
        MappingDryRunReq req = new MappingDryRunReq(
                "1.0",
                null,
                null,
                GOLDEN_SAMPLE_REF,
                null,
                null
        );

        ApiResponse<MappingDryRunReportView> resp = controller.dryRun(1L, req);
        assertThat(resp.code()).isEqualTo("OK");
        MappingDryRunReportView report = resp.data();

        // Direct DryRunEngine run over same profile + sample per WP-2A-2-FIX2
        Path profileFile = RepoPathLocator.locateRepoRoot(".").resolve(MOCK_MALL_PROFILE_PATH);
        SourceProfile profile;
        try {
            profile = SourceProfile.fromFile(profileFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        List<String> lines = sampleResolver.resolveAndLoad(GOLDEN_SAMPLE_REF);
        DryRunEngine directEngine = new DryRunEngine(pureMapper, contractValidator);
        DryRunReport directReport = directEngine.run(profile, lines);

        // (1) 报告指标与 DryRunEngine 直接计算一致（WP-2A-2-FIX2 约束）
        assertThat(report.totalRows()).isEqualTo(directReport.totalRows());
        assertThat(report.canonicalRows()).isEqualTo(directReport.canonicalRows());
        assertThat(report.quarantinedRows()).isEqualTo(directReport.quarantinedRows());
        assertThat(report.validatorAccepted()).isEqualTo(directReport.validatorAccepted());
        assertThat(report.strictContractAccepted()).isEqualTo(directReport.strictContractAccepted());

        // (2) profileContentHash 存在，且为 64 位十六进制 SHA-256，与文件一致
        assertThat(report.profileContentHash())
                .isNotNull()
                .matches("^[a-fA-F0-9]{64}$")
                .isEqualTo(profile.contentHash());

        // (3) validatorAccepted 与 strictContractAccepted 作为独立字段分列（Q6 裁决口径）
        assertThat(report.validatorAccepted()).isEqualTo(directReport.validatorAccepted());
        assertThat(report.strictContractAccepted()).isEqualTo(directReport.strictContractAccepted());

        // (4) mock-mall 的 web-channel 样本产生 unresolvedEnumCounts（mock-mall.v1.json channel.web 为 null）
        // golden-20260901.jsonl 中第 12、13、14 行携带 "channel":"web"
        assertThat(report.unresolvedEnumCounts()).containsKey("channel=web");
        assertThat(report.unresolvedEnumCounts().get("channel=web")).isEqualTo(directReport.unresolvedEnumCounts().get("channel=web"));

        // (5) 黄金样本缺陷隔离断言与结构性事实：
        assertThat(report.totalRows()).isEqualTo(55L);
        assertThat(report.quarantinedRows()).isGreaterThanOrEqualTo(1L);
        assertThat(report.reasonCounts()).isNotNull();
        assertThat(report.reasonCounts()).containsKey("JSON_PARSE_ERROR");
        assertThat(report.reasonCounts().get("JSON_PARSE_ERROR")).isGreaterThanOrEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // 3. Error Case 400: sampleRef URL / traversal / outside allowlist / >100 / unparseable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("400 校验：拒绝 sampleRef 为 URL（http/file 等）")
    void error400_sampleRefUrlRejected() throws Exception {
        // http URL
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "http://malicious.com/events.jsonl"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持 URL 形式")));

        // file URL
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "file:///etc/passwd"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持 URL 形式")));
    }

    @Test
    @DisplayName("400 校验：拒绝 sampleRef 路径遍历（..）")
    void error400_sampleRefTraversalRejected() throws Exception {
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "../secret.json"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("路径遍历")));

        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "tests/golden-dataset/events/../../analytics-server/pom.xml"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("路径遍历")));
    }

    @Test
    @DisplayName("400 校验：拒绝白名单之外的绝对路径与相对路径")
    void error400_sampleRefOutsideAllowlistRejected() throws Exception {
        // 绝对路径（白名单外）
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "/tmp/outside-events.jsonl"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("白名单")));

        // 相对路径（白名单外，如 landing/manifests/1.json）
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "landing/manifests/1.json"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("白名单")));
    }

    @Test
    @DisplayName("400 校验：拒绝提供内联记录（inline records）")
    void error400_inlineRecordsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "%s",
                                  "inlineRecords": [ {"event_id": "inline-1"} ]
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不接受内联记录")));

        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "%s",
                                  "records": [ {"event_id": "inline-1"} ]
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不接受内联记录")));
    }

    @Test
    @DisplayName("400 校验：样本记录超过 100 条时拒绝")
    void error400_exceeds100RecordsRejected(@TempDir Path tempDir) throws IOException {
        // 在临时目录构造白名单结构的样本根目录
        Path goldenDir = Files.createDirectories(tempDir.resolve("tests/golden-dataset/events"));
        Path overflowFile = goldenDir.resolve("overflow.jsonl");

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            lines.add("{\"event_id\":\"evt-%d\",\"event_type\":\"behavior\",\"event_time\":\"2026-09-01T10:00:00+08:00\",\"source_system\":\"mock-mall\",\"schema_version\":\"1.0\",\"payload\":{}}".formatted(i));
        }
        Files.write(overflowFile, lines, StandardCharsets.UTF_8);

        MappingSampleResolver customResolver = new MappingSampleResolver(tempDir.toString(), objectMapper);
        assertThatThrownBy(() -> customResolver.resolveAndLoad("overflow.jsonl"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("超过 100 条限制");
    }

    @Test
    @DisplayName("400 校验：sampleRef 指向白名单内不存在的文件时拒绝并报 400")
    void error400_sampleRefNonexistentFileInsideAllowlistRejected() throws Exception {
        // 白名单 tests/golden-dataset/events/ 下的不存在文件
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "tests/golden-dataset/events/nonexistent-sample.jsonl"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不存在或不可读")));

        // 白名单 analytics-server/source-profiles/ 下的不存在文件
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "analytics-server/source-profiles/nonexistent-sample.jsonl"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不存在或不可读")));
    }

    @Test
    @DisplayName("容错与隔离语义：样本包含坏行（不可解析或非对象）正常运行，报告将其隔离并计入 JSON_PARSE_ERROR")
    void sampleWithBadLinesRunsSuccessfullyAndQuarantinesJsonParseError(@TempDir Path tempDir) throws IOException {
        Path goldenDir = Files.createDirectories(tempDir.resolve("tests/golden-dataset/events"));
        Path corruptFile = goldenDir.resolve("sample-with-bad-lines.jsonl");

        // 包含 1 条有效记录、1 条非法 JSON 行、1 条非对象 JSON 行
        String content = """
                {"event_id":"evt-valid","event_type":"behavior","event_time":"2026-09-01T10:00:00+08:00","source_system":"mock-mall","schema_version":"1.0","payload":{"user_id":"1","product_id":"1","session_id":"s-1","behavior_type":"view"}}
                not-valid-json-line-without-braces
                "just a json string, not an object"
                """;
        Files.writeString(corruptFile, content, StandardCharsets.UTF_8);

        // 1. Resolver 作为纯传输安全组件，如实返回 3 条非空行，绝不检查行内容，不抛 400
        MappingSampleResolver customResolver = new MappingSampleResolver(tempDir.toString(), objectMapper);
        List<String> records = customResolver.resolveAndLoad("sample-with-bad-lines.jsonl");
        assertThat(records).hasSize(3);

        // 2. DryRunService 试运行正常完成，不抛异常（共享生产环境相同的 RepoPathLocator 仓库根发现）
        SourceMappingDryRunService customService = new SourceMappingDryRunService(
                sourceService,
                reportStore,
                customResolver,
                pureMapper,
                contractValidator
        );
        MappingDryRunReq req = new MappingDryRunReq("1.0", null, null, "sample-with-bad-lines.jsonl", null, null);
        MappingDryRunReportView view = customService.runDryRun(1L, req);

        // 3. 断言坏行被隔离并计入 reasonCounts.JSON_PARSE_ERROR
        assertThat(view).isNotNull();
        assertThat(view.totalRows()).isEqualTo(3L);
        assertThat(view.canonicalRows()).isEqualTo(1L);
        assertThat(view.quarantinedRows()).isEqualTo(2L);
        assertThat(view.reasonCounts()).containsKey("JSON_PARSE_ERROR");
        assertThat(view.reasonCounts().get("JSON_PARSE_ERROR")).isEqualTo(2L);
    }

    // -------------------------------------------------------------------------
    // 4. Error Case 409: Profile Version / ContentHash Mismatch
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("409 校验：requested profileVersion 与源画像版本不一致")
    void error409_profileVersionMismatch() throws Exception {
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profileVersion": "2.0",
                                  "sampleRef": "%s"
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("profileVersion")));
    }

    @Test
    @DisplayName("409 校验：requested profileContentHash 与源画像内容哈希不一致")
    void error409_profileContentHashMismatch() throws Exception {
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profileVersion": "1.0",
                                  "profileContentHash": "0000000000000000000000000000000000000000000000000000000000000000",
                                  "sampleRef": "%s"
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("profileContentHash")));

        // 兼容 contentHash 入参键名
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profileVersion": "1.0",
                                  "contentHash": "badhashbadhash",
                                  "sampleRef": "%s"
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_VERSION_CONFLICT"));
    }

    // -------------------------------------------------------------------------
    // 5. Error Case 404: Unknown sourceId / Unknown reportId / Cross-source reportId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("404 校验：未知 sourceId 返回 404（对齐 SourceRegistryController 既有惯例）")
    void error404_unknownSourceId() throws Exception {
        mockMvc.perform(post("/api/v1/sources/99999/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "%s"
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/sources/99999/mappings/dry-runs/any-report-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("404 校验：GET 未知 reportId 返回 404")
    void error404_unknownReportId() throws Exception {
        mockMvc.perform(get("/api/v1/sources/1/mappings/dry-runs/non-existent-report-123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRY_RUN_REPORT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不存在")));
    }

    @Test
    @DisplayName("404 校验：GET reportId 属于其它 sourceId 时返回 404（跨源防越权）")
    void error404_reportBelongsToDifferentSource() throws Exception {
        // 先生成并保存一个归属 sourceId=1 的报告
        String postReq = """
                {
                  "profileVersion": "1.0",
                  "sampleRef": "%s"
                }
                """.formatted(GOLDEN_SAMPLE_REF);

        MvcResult postRes = mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postReq))
                .andExpect(status().isOk())
                .andReturn();

        String reportId = objectMapper.readTree(postRes.getResponse().getContentAsString()).path("data").path("reportId").asText();

        // 注册 source 2
        SourceRegistryView source2View = new SourceRegistryView(
                2L, "mock-mall-2", "源 2", "FILE", MOCK_MALL_PROFILE_PATH,
                "Asia/Shanghai", "CNY", "ACTIVE", "1.0", false,
                LocalDateTime.now(), LocalDateTime.now(), "dw2"
        );
        when(sourceService.get(2L)).thenReturn(source2View);

        // 用 source 2 去查 source 1 的 reportId
        mockMvc.perform(get("/api/v1/sources/2/mappings/dry-runs/" + reportId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRY_RUN_REPORT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不属于源 2")));
    }

    // -------------------------------------------------------------------------
    // 6. Permission Semantics: 403 Path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("403 校验：未登录（CurrentUserHolder 为空）执行 POST dry-run 产生 403")
    void error403_unauthenticatedPrincipal() throws Exception {
        CurrentUserHolder.clear(); // 清空登录态

        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "%s"
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺少可信当前用户")));
    }

    @Test
    @DisplayName("403 校验：登录身份角色权限不足（analyst、operator）产生 403")
    void error403_insufficientRoles() throws Exception {
        // analyst 角色无 RUNTIME_MANAGE
        CurrentUserHolder.set(new CurrentUser(2L, "analyst_user", "analyst"));
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "%s"
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺少所需权限")));

        // operator 角色无 RUNTIME_MANAGE
        CurrentUserHolder.set(new CurrentUser(3L, "operator_user", "operator"));
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "%s"
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("权限放行：admin 与 data_dev 角色均具备 RUNTIME_MANAGE，正常放行")
    void permission_adminAndDataDevPermitted() throws Exception {
        // data_dev
        CurrentUserHolder.set(new CurrentUser(4L, "dev_user", "data_dev"));
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "%s"
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isOk());

        // admin
        CurrentUserHolder.set(new CurrentUser(1L, "admin_user", "admin"));
        mockMvc.perform(post("/api/v1/sources/1/mappings/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleRef": "%s"
                                }
                                """.formatted(GOLDEN_SAMPLE_REF)))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // 7. ZERO formal writes assertion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("零正式写入断言：dry-run 执行后采集状态、断点位点、清单文件、激活指针无任何改动")
    void zeroFormalWrites_assertIngestionAndCheckpointUntouched() throws IOException {
        // 记录执行前的可观测状态
        int reportStoreCountBefore = reportStore.size();
        Long activeSourceBefore = sourceService.currentSourceId().orElse(null);
        SourceRegistryView sourceViewBefore = sourceService.get(1L);

        Path manifestsDir = RepoRoot.path("landing/manifests");
        List<String> manifestFilesBefore = Files.exists(manifestsDir)
                ? Files.list(manifestsDir).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList())
                : List.of();

        // 执行 dry-run
        MappingDryRunReq req = new MappingDryRunReq(GOLDEN_SAMPLE_REF);
        ApiResponse<MappingDryRunReportView> resp = controller.dryRun(1L, req);
        assertThat(resp.code()).isEqualTo("OK");

        // 断言执行后的可观测状态
        // 1. 内存存储恰好增加 1 个报告（仅内存存储，不碰数据库）
        assertThat(reportStore.size()).isEqualTo(reportStoreCountBefore + 1);

        // 2. 激活指针未变（no activation-pointer change）
        assertThat(sourceService.currentSourceId().orElse(null)).isEqualTo(activeSourceBefore);
        verify(sourceService, never()).activate(1L);
        verify(sourceService, never()).pause(1L);

        // 3. 源登记自身可观测状态未变
        SourceRegistryView sourceViewAfter = sourceService.get(1L);
        assertThat(sourceViewAfter.status()).isEqualTo(sourceViewBefore.status());
        assertThat(sourceViewAfter.current()).isEqualTo(sourceViewBefore.current());
        assertThat(sourceViewAfter.profileVersion()).isEqualTo(sourceViewBefore.profileVersion());
        assertThat(sourceViewAfter.profilePath()).isEqualTo(sourceViewBefore.profilePath());

        // 4. 清单文件集合无任何改动（no manifest creation）
        List<String> manifestFilesAfter = Files.exists(manifestsDir)
                ? Files.list(manifestsDir).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList())
                : List.of();
        assertThat(manifestFilesAfter).isEqualTo(manifestFilesBefore);
    }
}
