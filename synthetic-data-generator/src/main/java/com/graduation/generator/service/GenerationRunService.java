package com.graduation.generator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.generator.contract.Artifact;
import com.graduation.generator.contract.ArtifactManifest;
import com.graduation.generator.contract.ContractFormat;
import com.graduation.generator.contract.JsonlEventSink;
import com.graduation.generator.core.DirtySample;
import com.graduation.generator.engine.EngineOutcome;
import com.graduation.generator.engine.EventTypeStat;
import com.graduation.generator.engine.GenerationEngine;
import com.graduation.generator.engine.GenerationRequest;
import com.graduation.generator.meta.GeneratorMetaStore;
import com.graduation.generator.meta.GeneratorMetaStore.ArtifactRow;
import com.graduation.generator.meta.GeneratorMetaStore.PlanRow;
import com.graduation.generator.meta.GeneratorMetaStore.RunRow;
import com.graduation.generator.meta.RunStatus;
import com.graduation.generator.report.RunReport;
import com.graduation.generator.web.dto.GeneratorApiDtos.ArtifactView;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunStarted;
import com.graduation.generator.web.dto.GeneratorApiDtos.RunView;
import com.graduation.generator.web.dto.GeneratorApiDtos.StartRunRequest;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * 生成运行的编排：§4.4 的启动/查询/取消/制品四个端点背后的服务。
 *
 * <p>职责边界（刻意划清，避免出现第二个所有者）：</p>
 * <ul>
 *   <li><b>只引用不可变计划版本</b>：启动时按 {@code (plan_id, version)} 读出计划行，之后所有参数都取自这一行；
 *       运行期间再改计划不会影响已启动的运行（§4.2）。</li>
 *   <li><b>状态只走 {@link RunStatus} 的合法迁移</b>：{@code PENDING → RUNNING → SUCCESS/FAILED/CANCELLED}，
 *       入库由 {@link GeneratorMetaStore} 的条件 UPDATE 保证（终态无法被覆盖）。</li>
 *   <li><b>落库不算产出</b>：制品、逐类型计数、运行报告三者都要落地，缺一项这次运行就不算可对账。</li>
 *   <li><b>取消是"请求"不是"保证"</b>：引擎在循环中轮询取消意图；取消只保证运行最终进 CANCELLED，
 *       不保证立刻停（已在写的文件会被正常收口）。</li>
 * </ul>
 *
 * <p><b>本实现的已知局限（如实登记，不掩盖）</b>：① 运行没有"进度"字段与端点——契约明确说进度口径未冻结、
 * 不建模；② 进程重启时停在 RUNNING 的运行不会自动收口（没有恢复扫描），需要人工按运行报告判断；
 * ③ {@code MALL_API} 模式尚未实现（S4 的 {@code ReferenceMallHttpAdapter}），本服务对它明确报"未实现"而不是
 * 走文件模式冒充。</p>
 */
@Service
public class GenerationRunService {

    private static final Logger log = LoggerFactory.getLogger(GenerationRunService.class);

    /** 支持的模式（§3.3）：本切片只有文件模式；MALL_API 由 S4 补 */
    public static final String MODE_CANONICAL_EVENT_FILE = "CANONICAL_EVENT_FILE";
    public static final String MODE_MALL_API = "MALL_API";

    /** 失败码（写入 generation_run.error_code；契约只冻结了 error 的语义，未冻结取值） */
    static final String ERROR_RUN_FAILED = "RUN_FAILED";

    /** 异常样本制品文件名（与主事件流分离，见 D-018） */
    static final String DIRTY_SAMPLE_FILE = "dirty-samples.jsonl";

    /** 取消意图的内存标志（DB 里的 cancel_requested 是权威、进程内标志用于快速轮询） */
    private static final int DB_CANCEL_POLL_EVERY = 500;

    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GeneratorMetaStore store;
    private final GenerationEngine engine;
    private final ObjectMapper mapper;
    private final Path outputRoot;
    private final int maxRecordsPerFile;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "generator-run");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public GenerationRunService(GeneratorMetaStore store,
                                GenerationEngine engine,
                                ObjectMapper mapper,
                                @Value("${generator.output.root:./generator-output}") String outputRoot,
                                @Value("${generator.output.max-records-per-file:100000}") int maxRecordsPerFile) {
        this.store = store;
        this.engine = engine;
        this.mapper = mapper;
        this.outputRoot = Path.of(outputRoot).toAbsolutePath().normalize();
        this.maxRecordsPerFile = maxRecordsPerFile;
    }

    // ---------- §4.4 L153：异步启动 ----------

    public RunStarted start(StartRunRequest request) {
        if (request == null || request.planId() == null || request.planId().isBlank() || request.version() == null) {
            throw new IllegalArgumentException("plan_id 与 version 均为必填（契约 GenerationRunStartRequest）");
        }
        PlanRow plan = store.findPlan(request.planId(), request.version())
                .orElseThrow(() -> new RunNotFoundException(
                        "计划版本不存在：plan_id=%s version=%d".formatted(request.planId(), request.version())));
        if (!MODE_CANONICAL_EVENT_FILE.equals(plan.mode())) {
            throw new UnsupportedModeException("模式 %s 尚未实现：%s 需要 ReferenceMallHttpAdapter（S4，且依赖 B-04 裁决）；"
                    .formatted(plan.mode(), MODE_MALL_API)
                    + "本服务当前只支持 " + MODE_CANONICAL_EVENT_FILE);
        }
        String runId = newRunId(plan);
        store.insertRun(new RunRow(null, runId, plan.planId(), plan.version(), plan.targetId(),
                plan.targetId() == null ? null : targetConfigVersion(plan.targetId()),
                RunStatus.PENDING, null, null, 0, 0, null, null, null, false));
        cancelFlags.put(runId, new AtomicBoolean(false));
        executor.submit(() -> execute(runId, plan));
        log.info("生成运行已入队：runId={} plan={} v{} scenario={} eventCount={}", runId, plan.planId(),
                plan.version(), plan.scenario(), plan.eventCount());
        return new RunStarted(runId);
    }

    // ---------- §4.4 L154：查询 ----------

    public RunView find(String runId) {
        RunRow row = requireRun(runId);
        return toView(row);
    }

    // ---------- §4.4 L155：幂等取消 ----------

    /**
     * 幂等取消：首次调用写库并置进程内标志，之后每次调用结果相同（返回值都是"取消后的运行详情"）。
     *
     * <p>已终态的运行调用取消是**合法且无副作用**的（返回终态本身），不抛错——幂等语义要求如此。</p>
     */
    public RunView cancel(String runId) {
        RunRow before = requireRun(runId);
        if (before.status().isTerminal()) {
            return toView(before);
        }
        cancelFlags.computeIfAbsent(runId, key -> new AtomicBoolean(false)).set(true);
        store.requestCancel(runId);
        return toView(requireRun(runId));
    }

    // ---------- §4.4 L156：制品 ----------

    public List<ArtifactView> artifacts(String runId) {
        requireRun(runId);
        return store.listArtifacts(runId).stream()
                .map(row -> new ArtifactView(runId, row.uri(), row.checksum(), row.bytes(), row.recordCount(),
                        time(row.minEventTime()), time(row.maxEventTime()), row.schemaVersion(), Boolean.TRUE))
                .toList();
    }

    // ---------- 运行主体 ----------

    private void execute(String runId, PlanRow plan) {
        if (!store.markRunning(runId)) {
            // 计划入队后被取消（PENDING → CANCELLED）或已被别的线程接手：不重复执行
            log.info("运行未取得 RUNNING 迁移，跳过执行：runId={}", runId);
            return;
        }
        Path runDir = outputRoot.resolve(runId);
        JsonlEventSink sink = new JsonlEventSink(runId, outputRoot, ContractFormat.SCHEMA_VERSION, maxRecordsPerFile);
        EngineOutcome outcome = null;
        ArtifactManifest manifest = null;
        RunStatus terminal;
        String errorCode = null;
        String errorMessage = null;
        try {
            GenerationRequest request = new GenerationRequest(runId, plan.scenario(), plan.seed(),
                    plan.startTime(), plan.endTime(), plan.eventCount(), plan.ratePerSecond(), plan.dirtyProfile());
            outcome = engine.run(request, sink, cancelSupplier(runId));
            manifest = sink.closeAndBuildManifest();
            terminal = isCancelled(runId) ? RunStatus.CANCELLED : RunStatus.SUCCESS;
        } catch (RuntimeException e) {
            errorCode = ERROR_RUN_FAILED;
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            terminal = isCancelled(runId) ? RunStatus.CANCELLED : RunStatus.FAILED;
            log.error("生成运行失败：runId={} code={}", runId, errorCode, e);
            manifest = closeQuietly(sink);
        }
        List<Artifact> artifacts = new ArrayList<>(sink.artifacts());
        Artifact dirtyArtifact = writeDirtySamples(runDir, outcome);
        if (dirtyArtifact != null) {
            artifacts.add(dirtyArtifact);
        }
        String checksum = aggregateChecksum(artifacts);
        long successCount = outcome == null ? artifacts.stream().mapToLong(Artifact::recordCount).sum()
                : outcome.successCount();
        long failedCount = outcome == null ? 0 : outcome.failedCount();
        try {
            persistArtifacts(runId, artifacts);
            persistEventStats(runId, outcome);
            writeReport(runDir, runId, plan, terminal, successCount, failedCount, checksum, outcome, artifacts,
                    errorCode, errorMessage);
        } catch (RuntimeException e) {
            // 落库/报告失败比"运行失败"更严重：状态必须收口成 FAILED，且原始失败信息不能被覆盖掉
            log.error("运行对账落库失败：runId={}", runId, e);
            if (errorCode == null) {
                errorCode = "PERSIST_FAILED";
                errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
                terminal = RunStatus.FAILED;
            }
        }
        store.finishRun(runId, terminal, successCount, failedCount, checksum, errorCode, errorMessage);
        cancelFlags.remove(runId);
        log.info("生成运行收口：runId={} status={} success={} failed={} artifacts={} manifestRecords={}",
                runId, terminal, successCount, failedCount, artifacts.size(),
                manifest == null ? 0 : manifest.recordCount());
    }

    /**
     * 异常样本落成独立制品 {@code <runDir>/dirty-samples.jsonl}（每行一条故意违约的 JSON）。
     *
     * <p>为什么不写进主事件流：主流必须保持契约有效才能被独立验证，而异常样本按定义就不合规。
     * 期望隔离数逐类型写在运行报告里，供采集/校验侧对账（§4.3 + §3.3 B）。</p>
     */
    private Artifact writeDirtySamples(Path runDir, EngineOutcome outcome) {
        if (outcome == null || outcome.dirtySamples().isEmpty()) {
            return null;
        }
        Path file = runDir.resolve(DIRTY_SAMPLE_FILE);
        StringBuilder content = new StringBuilder();
        for (DirtySample sample : outcome.dirtySamples()) {
            content.append(sample.json()).append('\n');
        }
        byte[] bytes = content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.createDirectories(runDir);
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("异常样本制品写入失败：" + file, e);
        }
        return new Artifact(file.toUri().toString(), Artifact.KIND_DIRTY_SAMPLE, sha256(bytes), bytes.length,
                outcome.dirtySamples().size(), null, null);
    }

    private void persistArtifacts(String runId, List<Artifact> artifacts) {
        for (Artifact artifact : artifacts) {
            store.insertArtifact(runId, new ArtifactRow(artifact.uri(), artifact.kind(), artifact.checksum(),
                    artifact.bytes(), artifact.recordCount(), artifact.minEventTime(), artifact.maxEventTime(),
                    ContractFormat.SCHEMA_VERSION));
        }
        for (Artifact jsonl : artifacts) {
            Artifact manifestArtifact = manifestArtifactOf(jsonl);
            if (manifestArtifact != null) {
                store.insertArtifact(runId, new ArtifactRow(manifestArtifact.uri(), manifestArtifact.kind(),
                        manifestArtifact.checksum(), manifestArtifact.bytes(), 0, null, null,
                        ContractFormat.SCHEMA_VERSION));
            }
        }
    }

    /**
     * 清单制品：由 JSONL 制品路径推出同名 {@code .manifest.json}，真实读文件算校验和与字节数。
     *
     * <p>刻意"读盘再算"而不是复制 JSONL 的值：清单的 checksum 必须能独立核实，否则它作为对账凭据没有意义。</p>
     */
    private Artifact manifestArtifactOf(Artifact jsonl) {
        if (!Artifact.KIND_EVENT_JSONL.equals(jsonl.kind())) {
            return null;
        }
        Path jsonlFile = Path.of(java.net.URI.create(jsonl.uri()));
        Path manifestFile = jsonlFile.resolveSibling(
                jsonlFile.getFileName().toString().replace(".jsonl", ".manifest.json"));
        if (!Files.isRegularFile(manifestFile)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(manifestFile);
            return new Artifact(manifestFile.toUri().toString(), Artifact.KIND_MANIFEST,
                    sha256(bytes), bytes.length, 0, null, null);
        } catch (IOException e) {
            throw new UncheckedIOException("清单制品读取失败：" + manifestFile, e);
        }
    }

    private void persistEventStats(String runId, EngineOutcome outcome) {
        if (outcome == null) {
            return;
        }
        for (Map.Entry<String, EventTypeStat> entry : outcome.eventStats().entrySet()) {
            store.upsertEventStat(runId, entry.getKey(), entry.getValue().count(), entry.getValue().amount());
        }
    }

    private void writeReport(Path runDir, String runId, PlanRow plan, RunStatus terminal, long successCount,
                             long failedCount, String checksum, EngineOutcome outcome, List<Artifact> artifacts,
                             String errorCode, String errorMessage) {
        List<RunReport.ArtifactLine> artifactLines = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            artifactLines.add(new RunReport.ArtifactLine(artifact.uri(), artifact.kind(), artifact.checksum(),
                    artifact.bytes(), artifact.recordCount(), timeOf(artifact.minEventTime()),
                    timeOf(artifact.maxEventTime())));
        }
        List<RunReport.StatLine> stats = outcome == null ? List.of()
                : outcome.eventStats().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> new RunReport.StatLine(entry.getKey(), entry.getValue().count(), entry.getValue().amount()))
                .toList();
        List<String> notes = new ArrayList<>();
        if (outcome != null) {
            notes.addAll(outcome.notes());
        }
        notes.add("事件统计与制品已分别落 generation_event_stat / generation_artifact；清单为每个制品文件同名一份");
        RunReport report = new RunReport(runId, plan.planId(), plan.version(), plan.mode(), plan.scenario(),
                plan.seed(), timeOf(plan.startTime()), timeOf(plan.endTime()), plan.eventCount(), terminal.name(),
                successCount, failedCount, checksum,
                outcome == null ? Map.of() : outcome.expectedQuarantineCounts(),
                outcome == null ? List.of() : dirtyTypes(outcome),
                stats, artifactLines, notes, errorCode, errorMessage);
        report.write(runDir, mapper);
    }

    private static List<String> dirtyTypes(EngineOutcome outcome) {
        return outcome.dirtySamples().stream().map(DirtySample::type).distinct().sorted().toList();
    }

    // ---------- 工具 ----------

    private RunRow requireRun(String runId) {
        return store.findRun(runId)
                .orElseThrow(() -> new RunNotFoundException("运行不存在：runId=" + runId));
    }

    private Integer targetConfigVersion(Long targetId) {
        return store.findTarget(targetId).map(GeneratorMetaStore.TargetRow::configVersion).orElse(null);
    }

    private BooleanSupplier cancelSupplier(String runId) {
        AtomicBoolean flag = cancelFlags.computeIfAbsent(runId, key -> new AtomicBoolean(false));
        AtomicInteger polls = new AtomicInteger();
        return () -> flag.get()
                || (polls.incrementAndGet() % DB_CANCEL_POLL_EVERY == 0 && store.isCancelRequested(runId));
    }

    private boolean isCancelled(String runId) {
        AtomicBoolean flag = cancelFlags.get(runId);
        return (flag != null && flag.get()) || store.isCancelRequested(runId);
    }

    private static ArtifactManifest closeQuietly(JsonlEventSink sink) {
        try {
            return sink.closeAndBuildManifest();
        } catch (RuntimeException e) {
            log.warn("失败运行收口清单时二次异常（忽略，运行报告仍会写）：{}", e.toString());
            return null;
        }
    }

    /**
     * 运行级校验和：单制品时**原样等于**该制品的 checksum（保证"运行 checksum 与制品 checksum 对账"能直接比对）；
     * 多制品时取 {@code sha256(按 uri 排序的 "uri:checksum" 行 + "\n")}。
     *
     * <p>契约只说"运行级校验和，算法与覆盖范围未冻结"，所以这里把规则写在代码与报告里，不假装它是契约算法。</p>
     */
    static String aggregateChecksum(List<Artifact> artifacts) {
        List<Artifact> jsonl = artifacts.stream()
                .filter(artifact -> Artifact.KIND_EVENT_JSONL.equals(artifact.kind()))
                .sorted(Comparator.comparing(Artifact::uri))
                .toList();
        if (jsonl.isEmpty()) {
            return null;
        }
        if (jsonl.size() == 1) {
            return jsonl.get(0).checksum();
        }
        String joined = jsonl.stream().map(a -> a.uri() + ":" + a.checksum()).collect(Collectors.joining("\n")) + "\n";
        return sha256(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(JsonlEventSink.CHECKSUM_ALGORITHM).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(JsonlEventSink.CHECKSUM_ALGORITHM + " 不可用", e);
        }
    }

    private String newRunId(PlanRow plan) {
        String suffix = HexFormat.of().formatHex(randomBytes(2));
        return "%s-v%d-%s-%s".formatted(plan.planId(), plan.version(),
                ZonedDateTime.now(ContractFormat.BUSINESS_ZONE).format(RUN_ID_TIME), suffix);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static RunView toView(RunRow row) {
        return new RunView(row.runId(), row.planVersion(), row.targetVersion(), row.status().name(),
                time(row.startedAt()), time(row.finishedAt()), row.successCount(), row.failedCount(),
                row.checksum(), error(row), row.cancelRequested());
    }

    private static String error(RunRow row) {
        if (row.errorCode() == null && row.errorMessage() == null) {
            return null;
        }
        return "%s: %s".formatted(Optional.ofNullable(row.errorCode()).orElse("ERROR"),
                Optional.ofNullable(row.errorMessage()).orElse(""));
    }

    private static String time(Instant instant) {
        return timeOf(instant);
    }

    private static String timeOf(Instant instant) {
        return instant == null ? null : ContractFormat.time(instant);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 计划版本不存在（映射 404） */
    public static class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(String message) {
            super(message);
        }
    }

    /** 模式尚未实现（映射 501，绝不降级成另一种模式冒充） */
    public static class UnsupportedModeException extends RuntimeException {
        public UnsupportedModeException(String message) {
            super(message);
        }
    }
}
