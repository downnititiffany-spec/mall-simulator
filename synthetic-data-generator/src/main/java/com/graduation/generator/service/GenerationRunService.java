package com.graduation.generator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.generator.adapter.MallTargetAdapter;
import com.graduation.generator.adapter.MallTargetAdapterRegistry;
import com.graduation.generator.adapter.TargetConfig;
import com.graduation.generator.contract.Artifact;
import com.graduation.generator.contract.ArtifactManifest;
import com.graduation.generator.contract.ContractFormat;
import com.graduation.generator.contract.JsonlEventSink;
import com.graduation.generator.core.DirtySample;
import com.graduation.generator.engine.EngineOutcome;
import com.graduation.generator.engine.EventStatsRecorder;
import com.graduation.generator.engine.EventTypeStat;
import com.graduation.generator.engine.GenerationEngine;
import com.graduation.generator.engine.GenerationRequest;
import com.graduation.generator.engine.MallApiGenerationEngine;
import com.graduation.generator.meta.GeneratorMetaStore;
import com.graduation.generator.meta.GeneratorMetaStore.ArtifactRow;
import com.graduation.generator.meta.GeneratorMetaStore.PlanRow;
import com.graduation.generator.meta.GeneratorMetaStore.RunRow;
import com.graduation.generator.meta.GeneratorMetaStore.TargetRow;
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
import java.util.concurrent.atomic.AtomicReference;
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
 * ③ {@code MALL_API} 模式<b>不做同 seed 逐字节复现</b>的承诺：商城侧会分配雪花 ID、库存会真实扣减，
 * 第二次运行面对的是变了的世界；本模式保证的是"可追溯的场景分布"（B-04），逐字节复现只属于文件模式。</p>
 */
@Service
public class GenerationRunService {

    private static final Logger log = LoggerFactory.getLogger(GenerationRunService.class);

    /** 支持的模式（§3.3）：文件模式与 MALL_API；其它模式一律响亮拒绝 */
    public static final String MODE_CANONICAL_EVENT_FILE = "CANONICAL_EVENT_FILE";
    public static final String MODE_MALL_API = "MALL_API";

    /** MALL_API 运行的操作流水制品文件名（本实现的增量制品，见 {@link Artifact#KIND_OPERATION_JOURNAL}） */
    static final String OPERATION_JOURNAL_FILE = "operation-journal.jsonl";

    /** 失败码（写入 generation_run.error_code；契约只冻结了 error 的语义，未冻结取值） */
    static final String ERROR_RUN_FAILED = "RUN_FAILED";

    /** 异常样本制品文件名（与主事件流分离，见 D-018） */
    static final String DIRTY_SAMPLE_FILE = "dirty-samples.jsonl";

    /** 取消意图的内存标志（DB 里的 cancel_requested 是权威、进程内标志用于快速轮询） */
    private static final int DB_CANCEL_POLL_EVERY = 500;

    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GeneratorMetaStore store;
    private final GenerationEngine engine;
    private final MallApiGenerationEngine mallEngine;
    private final MallTargetAdapterRegistry adapterRegistry;
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
                                @org.springframework.beans.factory.annotation.Qualifier("generationEngine")
                                GenerationEngine engine,
                                ObjectMapper mapper,
                                @Value("${generator.output.root:./generator-output}") String outputRoot,
                                @Value("${generator.output.max-records-per-file:100000}") int maxRecordsPerFile,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                MallApiGenerationEngine mallEngine,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                MallTargetAdapterRegistry adapterRegistry) {
        this.store = store;
        this.engine = engine;
        this.mallEngine = mallEngine;
        this.adapterRegistry = adapterRegistry;
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
        MallRunContext mallContext = mallContextFor(plan);
        String runId = newRunId(plan);
        if (mallContext != null) {
            // 预检结果在这里就被求值：能力不足/凭据不对/目录不足 → 直接抛给 HTTP 层，不留下半截运行记录
            mallContext.preflight();
        }
        store.insertRun(new RunRow(null, runId, plan.planId(), plan.version(), plan.targetId(),
                plan.targetId() == null ? null : targetConfigVersion(plan.targetId()),
                RunStatus.PENDING, null, null, 0, 0, null, null, null, false));
        cancelFlags.put(runId, new AtomicBoolean(false));
        executor.submit(() -> execute(runId, plan, mallContext));
        log.info("生成运行已入队：runId={} plan={} v{} mode={} scenario={} eventCount={}", runId, plan.planId(),
                plan.version(), plan.mode(), plan.scenario(), plan.eventCount());
        return new RunStarted(runId);
    }

    /**
     * 模式解析 + MALL_API 运行前预检，<b>全部发生在 {@code insertRun} 之前</b>。
     *
     * <p>顺序是刻意的：未知模式、未知 {@code adapter_type}、能力不足、凭据不可用、脏数据档位不支持——
     * 这些一律在<b>任何状态落库、任何写操作发生之前</b>响亮失败。否则会留下"一行 PENDING/FAILED 的运行记录 +
     * 商城侧半截数据"，排查时谁也说不清到底做了什么。</p>
     *
     * <p>预检里唯一一次真实调用是只读的 {@code listProducts}，它同时充当凭据校验
     * （D-033：每一次 MALL_API 调用都必须带凭据，凭据不对会当场 401）。</p>
     */
    private MallRunContext mallContextFor(PlanRow plan) {
        if (MODE_CANONICAL_EVENT_FILE.equals(plan.mode())) {
            return null;
        }
        if (!MODE_MALL_API.equals(plan.mode())) {
            throw new UnsupportedModeException("模式 %s 未实现：本服务只支持 %s 与 %s"
                    .formatted(plan.mode(), MODE_CANONICAL_EVENT_FILE, MODE_MALL_API));
        }
        if (mallEngine == null || adapterRegistry == null) {
            throw new UnsupportedModeException("MALL_API 模式未装配：缺少 MallApiGenerationEngine 或 "
                    + "MallTargetAdapterRegistry（见 GeneratorBeans）；不降级成 " + MODE_CANONICAL_EVENT_FILE + " 冒充");
        }
        if (plan.targetId() == null) {
            throw new IllegalArgumentException("MALL_API 计划必须绑定 generator_target（target_id 必填），"
                    + "否则不知道要打哪台商城：" + plan.planId() + " v" + plan.version());
        }
        TargetRow target = store.findTarget(plan.targetId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "计划绑定的目标不存在：target_id=" + plan.targetId()));
        if (MODE_CANONICAL_EVENT_FILE.equals(target.adapterType())) {
            throw new IllegalArgumentException("目标 %s 的 adapter_type 是 %s，它没有商城能力，不能用于 MALL_API 计划"
                    .formatted(target.name(), target.adapterType()));
        }
        if (plan.dirtyProfile() != null && !GenerationRequest.DIRTY_NONE.equalsIgnoreCase(plan.dirtyProfile())) {
            // 文件模式里脏样本只是"多写几行 JSONL"；MALL_API 里它会变成真实的非法请求打到商城。
            // 因此在这里、在任何插入运行记录/任何商城调用之前就拒绝，不做任何"过滤掉脏样本"的降级。
            throw new IllegalArgumentException("MALL_API 计划不支持脏数据档位 %s（只支持 %s）："
                    .formatted(plan.dirtyProfile(), GenerationRequest.DIRTY_NONE)
                    + "脏样本会真实写进商城，这种计划要么改档位、要么走 " + MODE_CANONICAL_EVENT_FILE);
        }
        MallTargetAdapter adapter = adapterRegistry.require(target.adapterType());
        TargetConfig config = new TargetConfig(target.id(), target.adapterType(), target.baseUrl(),
                target.credentialRef(), target.configJson());
        GenerationRequest probe = new GenerationRequest("preflight", plan.scenario(), plan.seed(),
                plan.startTime(), plan.endTime(), plan.eventCount(), plan.ratePerSecond(), plan.dirtyProfile());
        return new MallRunContext(config, adapter, engineFor(plan), target, probe);
    }

    /** 计划模式 → 引擎。文件模式用宿主注入的引擎（便于测试替身），MALL_API 用商城引擎 */
    private GenerationEngine engineFor(PlanRow plan) {
        return MODE_MALL_API.equals(plan.mode()) ? mallEngine : engine;
    }

    /**
     * MALL_API 运行上下文：承载"打哪台商城、用哪个适配器"，并把<b>运行前预检</b>做成一次性的懒加载。
     *
     * <p>为什么懒加载：预检要真实调商城（只读 {@code listProducts}），它必须发生在 {@code start()} 返回之前，
     * 让"能力不足/凭据不对"当场变成 HTTP 错误码而不是一个异步失败的运行；但预检结果又要给运行线程用。
     * 因此这里只做一次并缓存——不会出现"启动时查一次、运行时又查一次"的口径漂移。</p>
     */
    private final class MallRunContext {

        private final TargetConfig config;
        private final MallTargetAdapter adapter;
        private final GenerationEngine runEngine;
        private final TargetRow target;
        private final GenerationRequest probe;
        private final AtomicReference<MallApiGenerationEngine.Preflight> preflight = new AtomicReference<>();

        MallRunContext(TargetConfig config, MallTargetAdapter adapter, GenerationEngine runEngine,
                       TargetRow target, GenerationRequest probe) {
            this.config = config;
            this.adapter = adapter;
            this.runEngine = runEngine;
            this.target = target;
            this.probe = probe;
        }

        MallApiGenerationEngine.Preflight preflight() {
            MallApiGenerationEngine.Preflight cached = preflight.get();
            if (cached != null) {
                return cached;
            }
            synchronized (preflight) {
                MallApiGenerationEngine.Preflight current = preflight.get();
                if (current == null) {
                    current = mallEngine.preflight(probe, config, adapter);
                    preflight.set(current);
                }
                return current;
            }
        }
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

    private void execute(String runId, PlanRow plan, MallRunContext mallContext) {
        if (!store.markRunning(runId)) {
            // 计划入队后被取消（PENDING → CANCELLED）或已被别的线程接手：不重复执行
            log.info("运行未取得 RUNNING 迁移，跳过执行：runId={}", runId);
            return;
        }
        Path runDir = outputRoot.resolve(runId);
        JsonlEventSink sink = new JsonlEventSink(runId, outputRoot, ContractFormat.SCHEMA_VERSION, maxRecordsPerFile);
        // 逐类事件账本由本方法持有、交给引擎往里记（D10）：引擎在"事件已进流之后"抛异常时，
        // 失败路径读到的仍是抛之前真的写进规范流的那些事件，不必为失败另开一条统计路径。
        EventStatsRecorder eventStats = new EventStatsRecorder();
        EngineOutcome outcome = null;
        MallApiGenerationEngine.MallRunOutcome mallOutcome = null;
        ArtifactManifest manifest = null;
        RunStatus terminal;
        String errorCode = null;
        String errorMessage = null;
        try {
            GenerationRequest request = new GenerationRequest(runId, plan.scenario(), plan.seed(),
                    plan.startTime(), plan.endTime(), plan.eventCount(), plan.ratePerSecond(), plan.dirtyProfile());
            if (mallContext == null) {
                outcome = engine.run(request, sink, cancelSupplier(runId), eventStats);
            } else {
                // 复用落库前那一次预检：预检里的目录读取是真实 HTTP 调用，重复做会让
                // "目录读了几次"对不上账，也会让商城侧凭空多一次调用。
                mallOutcome = mallEngine.runForTarget(request, mallContext.config, mallContext.adapter, sink,
                        cancelSupplier(runId), mallContext.preflight(), eventStats);
                outcome = mallOutcome.outcome();
            }
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
        Artifact journalArtifact = writeOperationJournal(runDir, mallContext == null ? null
                : (mallOutcome == null ? mallContext.preflight().journal() : mallOutcome.preflight().journal()));
        if (journalArtifact != null) {
            artifacts.add(journalArtifact);
        }
        String checksum = aggregateChecksum(artifacts);
        // 失败路径没有 EngineOutcome，但 success_count 的语义不能跟着变：它一直是"写进规范流的事件条数"。
        // 早先这里把 generation_artifact 的 record_count 直接求和，等于把清单和操作流水也算成了事件——
        // 2026-09-11 真机 E3 因此报出 success_count=632（实际入流 231 条，event_count 才 400）。
        long successCount = outcome == null ? sink.eventRecords() : outcome.successCount();
        long failedCount = outcome == null ? 0 : outcome.failedCount();
        try {
            persistArtifacts(runId, artifacts);
            persistEventStats(runId, eventStats);
            writeReport(runDir, runId, plan, terminal, successCount, failedCount, checksum, outcome, eventStats,
                    artifacts, errorCode, errorMessage, mallOutcome);
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
     * MALL_API 操作流水落成独立制品 {@code <runDir>/operation-journal.jsonl}。
     *
     * <p>为什么非落不可：本模式的数据长在商城里，运行报告里的几个计数没有说服力——逐条记录
     * "哪个规范 ID 变成了商城的哪个外部 ID、走的是哪条路径、成功还是被能力门挡下"才是可核对的凭据。
     * 文件模式不产生该制品（{@code journal == null} → 返回 {@code null}）。</p>
     */
    private Artifact writeOperationJournal(Path runDir, com.graduation.generator.engine.OperationJournal journal) {
        if (journal == null || journal.size() == 0) {
            return null;
        }
        byte[] bytes = journal.toJsonl();
        Path file = runDir.resolve(OPERATION_JOURNAL_FILE);
        try {
            Files.createDirectories(runDir);
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("操作流水制品写入失败：" + file, e);
        }
        return new Artifact(file.toUri().toString(), Artifact.KIND_OPERATION_JOURNAL, sha256(bytes), bytes.length,
                journal.size(), null, null);
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
                    schemaVersionOf(artifact)));
        }
        for (Artifact jsonl : artifacts) {
            Artifact manifestArtifact = manifestArtifactOf(jsonl);
            if (manifestArtifact != null) {
                store.insertArtifact(runId, new ArtifactRow(manifestArtifact.uri(), manifestArtifact.kind(),
                        manifestArtifact.checksum(), manifestArtifact.bytes(), 0, null, null,
                        schemaVersionOf(manifestArtifact)));
            }
        }
    }

    /**
     * 制品落库时记的 {@code schema_version}：事件流/清单用事件契约版本，操作流水用它自己的格式版本。
     *
     * <p>为什么必须分开：流水不是事件契约的产物，它有自己的列集合（D12 起 1.1 多了
     * {@code real_http}/{@code local_accounting}）。一律盖 {@code 1.0} 会让"这份流水有没有那两个字段"
     * 在库里查不出来——总要打开文件才敢下结论。</p>
     */
    private static String schemaVersionOf(Artifact artifact) {
        return Artifact.KIND_OPERATION_JOURNAL.equals(artifact.kind())
                ? com.graduation.generator.engine.OperationJournal.SCHEMA_VERSION
                : ContractFormat.SCHEMA_VERSION;
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

    /**
     * 落库逐类事件统计。
     *
     * <p><b>失败路径也照落（D10）</b>：账本由调用方（{@link #execute}）持有，引擎抛异常时它已经记着
     * "抛之前真的写进规范流的事件"。过去这里在 {@code outcome == null} 时直接 return，等于让失败样本
     * 的逐类分布凭空消失（{@code event_stats: []}）——而失败样本恰恰是最需要"失败前发生了什么"的样本。
     * 现在成功与失败读同一本账，这里没有分支。</p>
     */
    private void persistEventStats(String runId, EventStatsRecorder eventStats) {
        for (Map.Entry<String, EventTypeStat> entry : eventStats.snapshot().entrySet()) {
            store.upsertEventStat(runId, entry.getKey(), entry.getValue().count(), entry.getValue().amount());
        }
    }

    private void writeReport(Path runDir, String runId, PlanRow plan, RunStatus terminal, long successCount,
                             long failedCount, String checksum, EngineOutcome outcome, EventStatsRecorder eventStats,
                             List<Artifact> artifacts,
                             String errorCode, String errorMessage,
                             MallApiGenerationEngine.MallRunOutcome mallOutcome) {
        List<RunReport.ArtifactLine> artifactLines = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            artifactLines.add(new RunReport.ArtifactLine(artifact.uri(), artifact.kind(), artifact.checksum(),
                    artifact.bytes(), artifact.recordCount(), timeOf(artifact.minEventTime()),
                    timeOf(artifact.maxEventTime())));
        }
        // 逐类统计取自运行账本（成功/失败同一本账，见 persistEventStats 的说明）：失败运行时它记的是
        // "失败前已经进流的事件"，也就是与 success_count 同源的那批事件。
        List<RunReport.StatLine> stats = eventStats.snapshot().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> new RunReport.StatLine(entry.getKey(), entry.getValue().count(), entry.getValue().amount()))
                .toList();
        List<String> notes = new ArrayList<>();
        if (outcome != null) {
            notes.addAll(outcome.notes());
        } else {
            // 空分布与"统计缺失"必须能被区分开：一个说"真的一条都没写进去"，另一个说"我们没记"。
            notes.add(eventStats.isEmpty()
                    ? "运行在写入任何事件之前就失败了：本次 event_stats 为空是事实（规范流里一条都没有），不是统计缺失"
                    : ("运行失败：event_stats 取自失败前已进入规范流的逐类账本（部分真相，%d 条 = success_count）；"
                            + "被商城拒绝、未进规范流的事件不在其中").formatted(eventStats.totalCount()));
        }
        if (mallOutcome != null) {
            MallApiGenerationEngine.Preflight preflight = mallOutcome.preflight();
            notes.add("MALL_API 目标：adapter_type=%s，能力声明 %s（不联网；实测结果见 operation-journal 与 target probe）"
                    .formatted(preflight.adapterType(), preflight.capabilities()));
            notes.add("MALL_API 对账：写规范流 %d 条；按事件计成功 %d / 失败 %d / 因能力缺口跳过 %d"
                    .formatted(mallOutcome.forwardedCount(), mallOutcome.dispatch().succeeded(),
                            mallOutcome.dispatch().failed(), mallOutcome.dispatch().skipped()));
            // D12：流水里"真发出去的请求"与"本地对齐记账"必须分开报，否则真实请求量会被系统性高估。
            // 口径说明：真实调用按流水行数计，而退款申请一条流水内含"申请 + 完成"两次 HTTP，
            // 因此商城侧实际收到的请求数 = 真实调用条数 + 退款申请条数（不写死数字，逐条见流水）。
            long realHttpRows = mallOutcome.dispatch().entries().stream()
                    .filter(com.graduation.generator.engine.OperationJournalEntry::realHttp).count();
            long localRows = mallOutcome.dispatch().entries().stream()
                    .filter(com.graduation.generator.engine.OperationJournalEntry::localAccounting).count();
            notes.add("MALL_API 真实调用 %d 条（real_http=true，含失败的尝试）；本地对齐记账 %d 条"
                    .formatted(realHttpRows, localRows)
                    + "（未向商城发请求：商品目录对齐/复用已创建用户/复用已完成退款）。"
                    + "注意退款申请一条流水含「申请 + 完成」两次 HTTP，故商城侧请求数 = 真实调用条数 + 退款申请条数；"
                    + "逐条见 " + OPERATION_JOURNAL_FILE);
            notes.add("规范 ID → 商城外部 ID 映射 " + mallOutcome.dispatch().traceability().size()
                    + " 条，逐条见 " + OPERATION_JOURNAL_FILE);
            notes.add("MALL_API 不承诺同 seed 逐字节复现（商城会分配雪花 ID 并真实扣减库存）："
                    + "本模式保证可追溯的场景分布，见 B-04");
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
