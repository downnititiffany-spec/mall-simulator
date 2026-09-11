package com.graduation.generator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.generator.meta.GeneratorMetaStore;
import com.graduation.generator.meta.GeneratorMetaStore.PlanRow;
import com.graduation.generator.web.dto.GeneratorApiDtos.ArtifactView;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunStarted;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunView;
import com.graduation.generator.web.dto.GeneratorApiDtos.ScenarioView;
import com.graduation.generator.web.dto.GeneratorApiDtos.StartRunRequest;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetCheckView;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetRequest;
import com.graduation.generator.web.dto.GeneratorApiDtos.TargetView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * §4.4 端点的真实端到端验收：真 HTTP（随机端口）→ 真服务 → 真引擎 → 真文件 → 真 MySQL。
 *
 * <p>**没有一处打桩**：运行真的落盘、清单真的按文件重算 SHA-256、状态真的写进 `generator_meta`。
 * 这正是 §3.4 独立验收要的形态——如果这里绿了，"API 能跑通"就不再是口头结论。</p>
 *
 * <p>产物根指向模块内 {@code target/it-generator-output}，同时验证了 §3.3 B 的"绝不写平台 landing"：
 * 断言里检查产物路径确实落在模块 target 下。</p>
 *
 * <p><b>一处如实记录的口径</b>：契约的 {@code GenerationArtifact} 没有 {@code kind} 字段，因此清单文件
 * 也会作为独立制品出现在 {@code /artifacts} 里（§4.4 L156 本就把"文件、checksum、清单"并列）。
 * 结果是按 {@code uri} 后缀区分：{@code .jsonl} 是事件流，{@code .manifest.json} 是清单，
 * **求和 record_count 时必须先按后缀过滤**，否则会把清单的描述数重复计入。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"generator.output.root=target/it-generator-output", "generator.cli="})
class GeneratorApiSmokeTest {

    private static final long EVENT_COUNT = 200L;
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-08T00:00:00Z");
    private static final String MODE_FILE = "CANONICAL_EVENT_FILE";
    private static final String MODE_MALL = "MALL_API";
    private static final long RUN_TIMEOUT_MS = 120_000;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private GeneratorMetaStore store;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------- 1. 主链路：计划 → 运行 → 制品 → 报告 ----------

    @Test
    void fileModeRunProducesContractArtifactsAndReport() {
        Plan plan = appendPlan(MODE_FILE, "light");
        RunView view = runToTerminal(plan);

        assertEquals("SUCCESS", view.status(), "运行必须以 SUCCESS 结束：" + view.error());
        assertEquals(EVENT_COUNT, view.successCount(), "事件预算必须被足额写入");
        assertEquals(0L, view.failedCount(), "正常路径不应有失败事件");
        assertNotNull(view.checksum(), "契约 GenerationRun.checksum 必须返回");
        assertFalse(view.checksum().isBlank());
        assertNotNull(view.started());
        assertNotNull(view.finished());
        assertFalse(view.cancelRequested());

        // 取消幂等：已终态的运行再取消，返回值仍是终态本身，不抛错、不改状态（§4.4 L155）
        RunView afterCancel = rest.postForObject("/api/v1/generation-runs/" + view.runId() + "/cancel",
                null, RunView.class);
        assertNotNull(afterCancel);
        assertEquals("SUCCESS", afterCancel.status());
        assertEquals(view.checksum(), afterCancel.checksum());

        List<ArtifactView> artifacts = listArtifacts(view.runId());
        assertFalse(artifacts.isEmpty());

        List<ArtifactView> eventFiles = artifacts.stream()
                .filter(a -> a.uri().endsWith(".jsonl") && !a.uri().endsWith("dirty-samples.jsonl")).toList();
        assertFalse(eventFiles.isEmpty(), "必须至少有一个事件流制品");

        long lines = 0;
        for (ArtifactView artifact : eventFiles) {
            Path file = localPath(artifact.uri());
            assertTrue(Files.isRegularFile(file), "制品必须真实存在：" + file);
            assertTrue(artifact.uri().contains("it-generator-output"), "产物必须落在本程序自己的目录下：" + artifact.uri());
            assertTrue(artifact.synthetic(), "契约要求制品显式标注 synthetic=true");
            assertNotNull(artifact.schemaVersion());

            byte[] bytes = readBytes(file);
            assertEquals(bytes.length, artifact.bytes(), "清单记录的字节数必须等于文件实际大小：" + file);
            assertEquals(sha256(bytes), artifact.checksum(), "checksum 必须等于文件真实 SHA-256：" + file);

            List<String> fileLines = readLines(file);
            assertEquals(fileLines.size(), artifact.recordCount(), "record_count 必须等于实际行数：" + file);
            lines += fileLines.size();
            for (String line : fileLines) {
                assertEnvelope(line);
            }
        }
        assertEquals(EVENT_COUNT, lines, "所有事件流制品的行数之和必须等于事件预算");

        // 清单：与事件流同名成对，且 synthetic=true、checksum 指向被描述的文件
        List<ArtifactView> manifests = artifacts.stream().filter(a -> a.uri().endsWith(".manifest.json")).toList();
        assertEquals(eventFiles.size(), manifests.size(), "每个事件流制品必须有同名清单");
        for (ArtifactView manifestArtifact : manifests) {
            JsonNode manifest = readTree(localPath(manifestArtifact.uri()));
            assertTrue(manifest.path("synthetic").asBoolean(), "清单必须标注 synthetic=true");
            assertEquals(view.runId(), manifest.path("run_id").asText());
            String describedUri = manifest.path("uri").asText();
            assertTrue(describedUri.endsWith(".jsonl"), "清单的 uri 必须指向事件流制品：" + describedUri);
            Path described = localPath(describedUri);
            assertTrue(Files.isRegularFile(described), "清单描述的制品必须存在：" + described);
            assertEquals(sha256(readBytes(described)), manifest.path("checksum").asText(),
                    "清单 checksum 必须等于被描述文件的 SHA-256");
            assertEquals(readLines(described).size(), manifest.path("record_count").asInt(),
                    "清单 record_count 必须等于被描述文件的行数");
        }

        // 脏样本：独立制品，不进主事件流（D-018）
        List<ArtifactView> dirty = artifacts.stream().filter(a -> a.uri().endsWith("dirty-samples.jsonl")).toList();
        assertEquals(1, dirty.size(), "light 档位必须产出异常样本制品");
        List<String> dirtyLines = readLines(localPath(dirty.get(0).uri()));
        assertEquals(dirty.get(0).recordCount(), dirtyLines.size());
        assertTrue(dirtyLines.size() >= 1, "light 档位至少一条异常样本");
        assertTrue(dirty.get(0).synthetic());

        // 运行报告：期望隔离数可逐类型对账，且运行缺口被如实写出（D-016）
        Path report = Path.of("target", "it-generator-output", view.runId(), "run-report.json");
        assertTrue(Files.isRegularFile(report), "必须写出运行报告：" + report.toAbsolutePath());
        JsonNode json = readTree(report);
        assertEquals("SUCCESS", json.path("status").asText());
        assertEquals(EVENT_COUNT, json.path("event_count").asLong());
        assertEquals(EVENT_COUNT, json.path("success_count").asLong());
        assertEquals(view.checksum(), json.path("checksum").asText());
        JsonNode quarantine = json.path("expected_quarantine_counts");
        assertTrue(quarantine.isObject() && !quarantine.isEmpty(), "期望隔离数必须逐类型给出");
        int quarantineTotal = 0;
        for (JsonNode value : quarantine) {
            quarantineTotal += value.asInt();
        }
        assertEquals(dirtyLines.size(), quarantineTotal, "期望隔离数之和必须等于异常样本条数");
        assertTrue(json.path("notes").isArray() && json.path("notes").size() >= 1,
                "运行缺口必须如实写进 notes，而不是留空");
    }

    // ---------- 2. 未实现模式显式失败 ----------

    @Test
    void unimplementedModeFailsLoudlyInsteadOfSwitchingMode() {
        Plan plan = appendPlan(MODE_MALL, "none");
        // 注意：TestRestTemplate 默认不因 4xx/5xx 抛异常（它返回响应本身），所以这里直接断言状态码，
        // 而不是 assertThrows——踩过一次才发现，记在测试里省得下次再猜。
        ResponseEntity<String> response = rest.postForEntity("/api/v1/generation-runs",
                new StartRunRequest(plan.planId(), plan.version()), String.class);
        assertEquals(501, response.getStatusCode().value(),
                "MALL_API 未实现必须显式 501，绝不用文件模式顶替");
        assertTrue(Objects.requireNonNull(response.getBody()).contains(MODE_FILE),
                "错误信息必须说清当前只支持哪种模式");
    }

    // ---------- 3. 场景端点 ----------

    @Test
    void scenariosExposeLabelsAndExpectedDirections() {
        ScenarioView[] views = rest.getForObject("/api/v1/scenarios", ScenarioView[].class);
        assertNotNull(views);
        assertTrue(views.length >= 6, "§4.3 的场景数量应至少与内核登记一致，实际 " + views.length);
        List<String> codes = new ArrayList<>();
        for (ScenarioView view : views) {
            assertNotNull(view.code());
            assertFalse(view.code().isBlank());
            assertNotNull(view.label(), "场景必须带中文标签，页面/CLI 直接展示");
            assertFalse(view.label().isBlank());
            assertNotNull(view.expectedDirections(), "场景必须给出预期影响方向，用于验收'效果是否符合预期'");
            codes.add(view.code());
        }
        assertEquals(codes.size(), codes.stream().distinct().count(), "场景代码不得重复");
    }

    // ---------- 4. 目标端点：增、查、改（保留未提供字段）、真实连通性检查 ----------

    @Test
    void targetsCrudAndProbeAreReal() {
        String probeDir = "target/it-target-probe";
        TargetView created = rest.postForObject("/api/v1/targets",
                new TargetRequest(null, "it-file-target-" + UUID.randomUUID().toString().substring(0, 6),
                        MODE_FILE, probeDir, "GENERATOR_TARGET_TOKEN", null, null, null, "behavior=false"),
                TargetView.class);
        assertNotNull(created);
        assertNotNull(created.id(), "创建后必须返回可定位的 id");
        assertEquals(Integer.valueOf(1), created.configVersion(), "新建目标从 config_version=1 起");
        assertEquals(Boolean.TRUE, created.testEnvironment(), "未指定时默认测试环境（契约要求显式标注）");

        TargetView[] all = rest.getForObject("/api/v1/targets", TargetView[].class);
        assertNotNull(all);
        List<Long> ids = new ArrayList<>();
        for (TargetView view : all) {
            ids.add(view.id());
        }
        assertTrue(ids.contains(created.id()), "列表必须包含刚创建的目标");

        // 重名目标：库上有唯一键，必须是 409 而不是 500（实测踩到过 500，故补此断言）
        ResponseEntity<String> duplicate = rest.postForEntity("/api/v1/targets",
                new TargetRequest(null, created.name(), MODE_FILE, "target/it-dup", null, null, null, null, null),
                String.class);
        assertEquals(409, duplicate.getStatusCode().value(), "重名目标必须报 409 冲突");

        // 检查是真实探测：文件模式真写一个探针文件再删掉
        TargetCheckView check = rest.postForObject("/api/v1/targets/" + created.id() + "/test", null,
                TargetCheckView.class);
        assertNotNull(check);
        assertEquals(created.id(), check.targetId());
        assertTrue(check.reachable(), "可写目录必须判定为可达：" + check.detail());
        assertFalse(Files.exists(Path.of(probeDir, ".generator-probe")), "探针文件必须被清理");

        // PUT 用契约的集合路径；未提供的字段沿用现值，不得被悄悄清空。
        // 改后的名字必须每次运行都不同：generator_target.name 上有唯一键，库是真实且跨运行保留的。
        String renamed = "改名后的目标-" + UUID.randomUUID().toString().substring(0, 6);
        TargetView updated = rest.exchange("/api/v1/targets", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(new TargetRequest(created.id(), renamed, MODE_FILE,
                        null, null, null, null, null, null)),
                TargetView.class).getBody();
        assertNotNull(updated);
        assertEquals(renamed, updated.name());
        assertEquals(probeDir, updated.baseUrl(), "未提供的 base_url 必须保留");
        assertEquals("behavior=false", updated.capabilities(), "未提供的 capabilities 必须保留");
        assertEquals("GENERATOR_TARGET_TOKEN", updated.credentialRef(), "凭据引用必须保留");
        assertEquals(Integer.valueOf(2), updated.configVersion(), "每次更新必须递增 config_version（§4.2）");

        // landing 禁写：文件模式不得写分析平台的 landing 目录（§3.3 B）
        TargetView landing = rest.postForObject("/api/v1/targets",
                new TargetRequest(null, "it-landing-" + UUID.randomUUID().toString().substring(0, 6), MODE_FILE,
                        "target/landing/events", null, null, null, null, null), TargetView.class);
        assertNotNull(landing);
        TargetCheckView landingCheck = rest.postForObject("/api/v1/targets/" + landing.id() + "/test", null,
                TargetCheckView.class);
        assertNotNull(landingCheck);
        assertFalse(landingCheck.reachable(), "landing 目录必须判定为不可写");
        assertTrue(landingCheck.detail().contains("landing"), "拒绝原因必须写清是 landing 禁写");

        // 明文凭据哨兵：credential_ref 只接受引用
        ResponseEntity<String> rejected = rest.postForEntity("/api/v1/targets",
                new TargetRequest(null, "bad-cred", MODE_FILE, "target/x", "root:123456", null, null, null, null),
                String.class);
        assertEquals(400, rejected.getStatusCode().value(), "疑似明文凭据必须被拒绝");

        // 不存在的目标：404 而不是空对象
        ResponseEntity<String> missing = rest.postForEntity("/api/v1/targets/99999999/test", null, String.class);
        assertEquals(404, missing.getStatusCode().value());
    }

    // ---------- 辅助 ----------

    private record Plan(String planId, int version) {
    }

    private Plan appendPlan(String mode, String dirtyProfile) {
        String planId = "it-s3b-" + UUID.randomUUID().toString().substring(0, 8);
        int version = store.appendPlanVersion(new PlanRow(null, planId, 0, mode, null, "normal", 20260911L,
                START, END, EVENT_COUNT, 0, dirtyProfile, null));
        assertTrue(version >= 1, "计划版本必须从 1 起");
        return new Plan(planId, version);
    }

    private RunView runToTerminal(Plan plan) {
        ResponseEntity<RunStarted> started = rest.postForEntity("/api/v1/generation-runs",
                new StartRunRequest(plan.planId(), plan.version()), RunStarted.class);
        assertEquals(200, started.getStatusCode().value(), "契约把成功码记录为 200");
        String runId = Objects.requireNonNull(started.getBody()).runId();
        assertNotNull(runId);

        long deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS;
        RunView view = rest.getForObject("/api/v1/generation-runs/" + runId, RunView.class);
        while (view != null && !isTerminal(view.status())) {
            if (System.currentTimeMillis() > deadline) {
                fail("运行在 " + RUN_TIMEOUT_MS + "ms 内未达终态，当前状态：" + view.status());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待运行终态被中断", e);
            }
            view = rest.getForObject("/api/v1/generation-runs/" + runId, RunView.class);
        }
        assertNotNull(view);
        assertEquals(runId, view.runId());
        return view;
    }

    private static boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private List<ArtifactView> listArtifacts(String runId) {
        ArtifactView[] views = rest.getForObject("/api/v1/generation-runs/" + runId + "/artifacts",
                ArtifactView[].class);
        assertNotNull(views);
        return List.of(views);
    }

    /** 逐行核对信封：8 个必填字段齐全、12 类取值域内的常量正确（§4.2 / canonical-event.v1） */
    private void assertEnvelope(String line) {
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (IOException e) {
            throw new AssertionError("事件行不是合法 JSON：" + line, e);
        }
        for (String field : List.of("event_id", "event_type", "event_time", "ingest_time", "source_system",
                "schema_version", "trace_id", "payload")) {
            assertTrue(node.has(field), "信封缺字段 " + field + "：" + line);
        }
        assertEquals("mock-mall", node.path("source_system").asText());
        assertEquals("1.0", node.path("schema_version").asText());
        assertTrue(node.path("payload").isObject(), "payload 必须是对象");
        assertTrue(node.path("event_time").asText().matches("\\d{4}-\\d{2}-\\d{2}T.*"), "event_time 必须是 ISO8601");
    }

    private Path localPath(String uri) {
        return uri.startsWith("file:") ? Path.of(URI.create(uri)) : Path.of(uri);
    }

    private JsonNode readTree(Path path) {
        try {
            return mapper.readTree(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException("读取 JSON 失败：" + path, e);
        }
    }

    private static List<String> readLines(Path path) {
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败：" + path, e);
        }
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败：" + path, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
