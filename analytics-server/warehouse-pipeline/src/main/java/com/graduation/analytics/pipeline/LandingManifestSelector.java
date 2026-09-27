package com.graduation.analytics.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.runtime.storage.LandingStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * S2-04：一轮 run 的 **Landing 输入清单选择**（唯一所有者）。
 *
 * <p>规则（设计 §9.3 + §13.4/R6-13 + G31-11/D-049b M3 发布语义）：</p>
 * <ol>
 *   <li><b>按源归属</b>：清单里的 {@code sourceId} 必须等于本次 run 所用源
 *       （{@code runtime_profile.source_id}）。缺 {@code sourceId} 的清单视为**不可归属**
 *       （P1-05 之前的清单），既不参与扫描也不接受被钉住。</li>
 *   <li><b>重试钉住原批次</b>：有钉住 batchId 时优先取它，且同样必须同源；
 *       钉住的是他源批次时**丢弃**并回落到本源扫描（绝不返回他源清单）。
 *       G31-11：钉住的批次**即使已被消费也照常返回**——是否当作 no-op（普通重试）
 *       还是照常重发布（显式重算）由调用方按消费台账决定，选择器不替调用方拍板。</li>
 *   <li><b>扫描取最老的未消费批次（FIFO，D-049b）</b>：status=READY 且
 *       accepted+quarantined&gt;0 的本源批次中取 batchId **最小**且不在已消费集合者。
 *       M3 语义：旧的「取最新」会跳过未消费批次造成**漏处理**（D-048 ③「多待处理批次
 *       逐批处理不遗漏」），改为 FIFO 后每轮吃掉最老的待处理批次。已消费批次不作为
 *       输入候选（否则重复发布同一批次），空批次仍视为无新数据跳过。</li>
 * </ol>
 *
 * <p><b>为什么必须按源过滤</b>：ODS 库名与 {@code --sourceSystem} 来自
 * {@code profile.source_id → source_registry}，而 {@code source_system} 是作业按 source_code
 * **注入为常量字面量**（{@code OdsLoadSql.sourceSystemLiteral}），行内没有字段可与它比对。
 * 两个源共用一份 {@code landing_uri} 时（本地换源复采的常见用法），选中他源清单会产出
 * "被标成本源、实际是他源"的 ODS 行——事后无法从 ODS 里察觉。故此处 fail-closed：
 * 没有可归属清单就返回 {@code manifest=null}，由调用方区分「确实没有清单」
 * （RUN_EMPTY_LANDING）与「有清单但全部已消费」（M3 no-op）。</p>
 */
@Slf4j
@Component
public class LandingManifestSelector {

    static final String MANIFESTS_DIR = "manifests";
    static final String STATUS_READY = "READY";
    private static final String KEY_SOURCE_ID = "sourceId";

    private final ObjectMapper objectMapper;

    public LandingManifestSelector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 选择结果（G31-11/D-049b）：清单本体 + 「已消费但 READY 可用」的计数。
     *
     * @param manifest              选中的输入清单；null = 本源没有可作输入的未消费清单
     *                              （是 RUN_EMPTY_LANDING 还是 M3 no-op，由
     *                              {@code readyButConsumedCount} 区分，选择器不判定）
     * @param readyButConsumedCount 本源 READY 非空但**已在消费台账**中的批次数
     *                              （含被钉住且已消费的批次）：no-op 证据的支撑计数
     */
    public record Selection(Map<String, Object> manifest, long readyButConsumedCount) {
    }

    /**
     * 选出本轮输入清单。
     *
     * @param landingRoot      落地根（{@code runtime_profile.landing_uri} 解析结果）
     * @param pinnedBatchId    本 run 原批次号（WAIT_LANDING 证据里钉住的 batchId）；首跑传 null
     * @param sourceId         本次 run 所用源（{@code source_registry.id}，由调用方 fail-closed 保证非空）
     * @param consumedBatchIds 本源**已消费**批次集合（来自 pipeline_batch_consumption 台账；
     *                         空集 = 尚无消费事实，行为与 V33 之前一致）
     * @return 选择结果；{@code manifest} 为 null 时调用方按计数区分空跑与 no-op（不回落、不猜）
     */
    public Selection select(Path landingRoot, Long pinnedBatchId, long sourceId, Set<Long> consumedBatchIds) {
        ScanState s = scanOfSource(landingRoot, sourceId, consumedBatchIds);
        if (pinnedBatchId != null) {
            Map<String, Object> pinned = read(
                    landingRoot.resolve(MANIFESTS_DIR).resolve(pinnedBatchId + ".json"));
            if (pinned != null && belongsTo(pinned, sourceId)) {
                // 钉住批次胜过 FIFO 扫描——即使它已消费：普通重试的 no-op 判定与
                // 显式重算的再发布判定都需要"拿到这条清单"才能继续（D-049d/e）。
                log.info("pipeline: 复用本 run 原批次 batchId={}（重试/恢复/重算不切换输入，源 {}）",
                        pinnedBatchId, sourceId);
                return new Selection(pinned, s.consumedCount.get());
            }
            if (pinned != null) {
                log.warn("pipeline: 钉住批次 {} 不属于本次运行的源 {}（清单 {}={}）→ 丢弃该清单，"
                                + "改用本源可归属批次；绝不把他源字节当作本轮输入",
                        pinnedBatchId, sourceId, KEY_SOURCE_ID, pinned.get(KEY_SOURCE_ID));
            }
        }
        return new Selection(s.best.get(), s.consumedCount.get());
    }

    /**
     * 存储抽象变体（N31-02 腿①，D-055）：landing 根在 HDFS（profile.landing_uri=hdfs://）时
     * 没有本地 Path 可给，清单扫描与钉住读取一律经 {@link LandingStorage}。选择语义与
     * {@link #select(Path, Long, long, Set)} 完全一致（同源归属、READY 非空、FIFO、
     * 钉住优先、已消费留痕），仅数据面不同。
     */
    public Selection select(LandingStorage storage, Long pinnedBatchId, long sourceId, Set<Long> consumedBatchIds) {
        ScanState s = scanOfSource(storage, sourceId, consumedBatchIds);
        if (pinnedBatchId != null) {
            Map<String, Object> pinned = read(storage, MANIFESTS_DIR + "/" + pinnedBatchId + ".json");
            if (pinned != null && belongsTo(pinned, sourceId)) {
                // 钉住批次胜过 FIFO 扫描——即使它已消费：普通重试的 no-op 判定与
                // 显式重算的再发布判定都需要"拿到这条清单"才能继续（D-049d/e）。
                log.info("pipeline: 复用本 run 原批次 batchId={}（重试/恢复/重算不切换输入，源 {}）",
                        pinnedBatchId, sourceId);
                return new Selection(pinned, s.consumedCount.get());
            }
            if (pinned != null) {
                log.warn("pipeline: 钉住批次 {} 不属于本次运行的源 {}（清单 {}={}）→ 丢弃该清单，"
                                + "改用本源可归属批次；绝不把他源字节当作本轮输入",
                        pinnedBatchId, sourceId, KEY_SOURCE_ID, pinned.get(KEY_SOURCE_ID));
            }
        }
        return new Selection(s.best.get(), s.consumedCount.get());
    }

    /** 一次扫描的中间结果：最老未消费候选 + 已消费计数（含他源/不可归属的诊断计数） */
    private record ScanState(AtomicReference<Map<String, Object>> best,
                             AtomicLong bestBatchId,
                             AtomicLong consumedCount,
                             AtomicLong foreignSource,
                             AtomicLong unattributable,
                             AtomicLong scanned) {
    }

    private static ScanState newScanState() {
        return new ScanState(new AtomicReference<>(null), new AtomicLong(Long.MAX_VALUE),
                new AtomicLong(0), new AtomicLong(0), new AtomicLong(0), new AtomicLong(0));
    }

    /**
     * 扫描 {@code manifests/*.json}：只有本源的 READY 非空批次参与比较，
     * 已消费的计入 {@code consumedCount} 不作候选，未消费取 batchId **最小**者（FIFO）。
     */
    private ScanState scanOfSource(Path landingRoot, long sourceId, Set<Long> consumedBatchIds) {
        Path manifestsDir = landingRoot.resolve(MANIFESTS_DIR);
        ScanState s = newScanState();
        if (!Files.isDirectory(manifestsDir)) {
            return s;
        }
        try (Stream<Path> list = Files.list(manifestsDir)) {
            for (Path m : list.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Map<String, Object> manifest = read(m);
                if (manifest == null) {
                    continue; // 读取失败已在 read 内告警
                }
                consider(s, manifest, sourceId, consumedBatchIds);
            }
        } catch (IOException e) {
            log.warn("manifests 扫描失败: {}", e.getMessage());
            return s;
        }
        return warnIfNothingAttributable(s, sourceId);
    }

    /**
     * 存储抽象变体（N31-02 腿①，D-055）：同一扫描语义，数据面经 {@link LandingStorage}。
     * 与本地版的唯一差异是清单如何读出来——{@code list} 对缺失目录返回空表、对存储 I/O
     * 故障必须抛出（§8.2 契约），这里把故障降级为「无清单」告警，不炸整次选择。
     */
    private ScanState scanOfSource(LandingStorage storage, long sourceId, Set<Long> consumedBatchIds) {
        ScanState s = newScanState();
        List<String> names;
        try {
            names = storage.list(MANIFESTS_DIR);
        } catch (RuntimeException e) {
            log.warn("manifests 扫描失败: {}", e.getMessage());
            return s;
        }
        for (String name : names) {
            if (!name.endsWith(".json")) {
                continue;
            }
            Map<String, Object> manifest = read(storage, MANIFESTS_DIR + "/" + name);
            if (manifest == null) {
                continue; // 读取失败已在 read 内告警
            }
            consider(s, manifest, sourceId, consumedBatchIds);
        }
        return warnIfNothingAttributable(s, sourceId);
    }

    /** 单条清单参与比较的共享判定（本地/存储两版唯一差异只在清单如何读出来） */
    private void consider(ScanState s, Map<String, Object> manifest, long sourceId, Set<Long> consumedBatchIds) {
        s.scanned.incrementAndGet();
        Long manifestSourceId = manifestSourceId(manifest);
        if (manifestSourceId == null) {
            s.unattributable.incrementAndGet();
            return;
        }
        if (manifestSourceId != sourceId) {
            s.foreignSource.incrementAndGet();
            return;
        }
        if (!STATUS_READY.equals(manifest.get("status"))) {
            return;
        }
        // R6-13 修正：老批次清单里计数是字符串（实测 2.json/4.json/5.json 报
        // "class java.lang.String cannot be cast to class java.lang.Number"），
        // 按数字/字符串双兼容解析，避免整条清单被当作损坏而跳过。
        long accepted = longOf(manifest.get("acceptedRecords"));
        long quarantined = longOf(manifest.get("quarantinedRecords"));
        if (accepted + quarantined <= 0) {
            return; // 空批次：无新数据，不阻塞也不作为输入（§9.3）
        }
        long batchId = longOf(manifest.get("batchId"));
        if (consumedBatchIds.contains(batchId)) {
            // M3（D-049b）：已消费批次留痕计数、不作候选——重复发布同一批次
            // 违反「无新输入不发布」，但计数让调用方能区分"没清单"与"全吃完了"。
            s.consumedCount.incrementAndGet();
            return;
        }
        if (s.best.get() == null || batchId < s.bestBatchId.get()) {
            s.bestBatchId.set(batchId);
            s.best.set(manifest);
        }
    }

    private ScanState warnIfNothingAttributable(ScanState s, long sourceId) {
        if (s.best.get() == null && (s.foreignSource.get() > 0 || s.unattributable.get() > 0)) {
            // 这种情况最需要留痕：库里的清单不是没有，而是**都不能归到本源名下**。
            // 静默返回 null 会让运维只看到 RUN_EMPTY_LANDING，误以为"没采集"。
            log.warn("manifests 目录共 {} 条清单，其中他源 {} 条、缺 sourceId（不可归属）{} 条，"
                            + "本源 {} 无未消费的 READY 批次（已消费 {} 条）→ 本轮按无输入处理（fail-closed，不装载他源字节）",
                    s.scanned.get(), s.foreignSource.get(), s.unattributable.get(),
                    sourceId, s.consumedCount.get());
        }
        return s;
    }

    /** 清单是否可归到该源：缺 sourceId 或值非法 ⇒ 不可归属（不猜） */
    private static boolean belongsTo(Map<String, Object> manifest, long sourceId) {
        Long manifestSourceId = manifestSourceId(manifest);
        return manifestSourceId != null && manifestSourceId == sourceId;
    }

    private static Long manifestSourceId(Map<String, Object> manifest) {
        Object raw = manifest.get(KEY_SOURCE_ID);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 读取单个 manifest JSON（失败返回 null，不抛异常：坏文件不应让整次选择失败） */
    private Map<String, Object> read(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("manifest 解析失败 {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    /** 存储变体（D-055）：同一读取语义——缺失返回 null，坏内容/读取失败告警后返回 null（不炸整次选择） */
    private Map<String, Object> read(LandingStorage storage, String relativePath) {
        try {
            if (!storage.exists(relativePath)) {
                return null;
            }
            try (InputStream in = storage.open(relativePath)) {
                return objectMapper.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
            }
        } catch (Exception e) {
            log.warn("manifest 解析失败 {}: {}", relativePath, e.getMessage());
            return null;
        }
    }

    /**
     * 宽松读取清单里的数字字段（R6-13 老清单兼容）：Number 直接用，字符串按下标解析，空/非法按 0。
     *
     * <p>公开给 {@code PipelineService}：既然选择器接纳了"计数写成字符串"的老清单，
     * 读取侧就必须用同一口径，否则会在 {@code (Number)} 强转处炸成 RUN_INTERNAL。</p>
     */
    public static long longOf(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
