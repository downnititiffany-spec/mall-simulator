package com.graduation.analytics.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * S2-04：一轮 run 的 **Landing 输入清单选择**（唯一所有者）。
 *
 * <p>规则（设计 §9.3 + §13.4/R6-13）：</p>
 * <ol>
 *   <li><b>按源归属</b>：清单里的 {@code sourceId} 必须等于本次 run 所用源
 *       （{@code runtime_profile.source_id}）。缺 {@code sourceId} 的清单视为**不可归属**
 *       （P1-05 之前的清单），既不参与扫描也不接受被钉住。</li>
 *   <li><b>重试钉住原批次</b>：有钉住 batchId 时优先取它，且同样必须同源；
 *       钉住的是他源批次时**丢弃**并回落到本源扫描（绝不返回他源清单）。</li>
 *   <li>扫描取"status=READY 且 accepted+quarantined&gt;0 的 batchId 最大者"（空批次视为无新数据）。</li>
 * </ol>
 *
 * <p><b>为什么必须按源过滤</b>：ODS 库名与 {@code --sourceSystem} 来自
 * {@code profile.source_id → source_registry}，而作业把 {@code source_system} 按 source_code
 * **注入为常量字面量**（{@code OdsLoadSql.sourceSystemLiteral}），行内没有字段可与它比对。
 * 两个源共用一份 {@code landing_uri} 时（本地换源复采的常见用法），选中他源清单会产出
 * "被标成本源、实际是他源"的 ODS 行——事后无法从 ODS 里察觉。故此处 fail-closed：
 * 没有可归属清单就返回 {@code null}，由调用方按 {@code RUN_EMPTY_LANDING} 拒绝这一轮。</p>
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
     * 选出本轮输入清单。
     *
     * @param landingRoot   落地根（{@code runtime_profile.landing_uri} 解析结果）
     * @param pinnedBatchId 本 run 原批次号（WAIT_LANDING 证据里钉住的 batchId）；首跑传 null
     * @param sourceId      本次 run 所用源（{@code source_registry.id}，由调用方 fail-closed 保证非空）
     * @return 可归属的清单；没有则返回 null（调用方报 RUN_EMPTY_LANDING，不回落、不猜）
     */
    public Map<String, Object> select(Path landingRoot, Long pinnedBatchId, long sourceId) {
        if (pinnedBatchId != null) {
            Map<String, Object> pinned = read(
                    landingRoot.resolve(MANIFESTS_DIR).resolve(pinnedBatchId + ".json"));
            if (pinned != null) {
                if (belongsTo(pinned, sourceId)) {
                    log.info("pipeline: 复用本 run 原批次 batchId={}（重试/恢复不切换输入，源 {}）",
                            pinnedBatchId, sourceId);
                    return pinned;
                }
                log.warn("pipeline: 钉住批次 {} 不属于本次运行的源 {}（清单 {}={}）→ 丢弃该清单，"
                                + "改用本源可归属批次；绝不把他源字节当作本轮输入",
                        pinnedBatchId, sourceId, KEY_SOURCE_ID, pinned.get(KEY_SOURCE_ID));
            }
        }
        return newestReadyOfSource(landingRoot, sourceId);
    }

    /** 扫描 {@code manifests/*.json}：只有本源的 READY 非空批次参与比较，取 batchId 最大者 */
    private Map<String, Object> newestReadyOfSource(Path landingRoot, long sourceId) {
        Path manifestsDir = landingRoot.resolve(MANIFESTS_DIR);
        if (!Files.isDirectory(manifestsDir)) {
            return null;
        }
        AtomicLong maxBatchId = new AtomicLong(Long.MIN_VALUE);
        AtomicReference<Map<String, Object>> best = new AtomicReference<>(null);
        AtomicLong foreignSource = new AtomicLong(0);
        AtomicLong unattributable = new AtomicLong(0);
        AtomicLong scanned = new AtomicLong(0);
        try (Stream<Path> list = Files.list(manifestsDir)) {
            for (Path m : list.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Map<String, Object> manifest = read(m);
                if (manifest == null) {
                    continue; // 读取失败已在 read 内告警
                }
                scanned.incrementAndGet();
                Long manifestSourceId = manifestSourceId(manifest);
                if (manifestSourceId == null) {
                    unattributable.incrementAndGet();
                    continue;
                }
                if (manifestSourceId != sourceId) {
                    foreignSource.incrementAndGet();
                    continue;
                }
                if (!STATUS_READY.equals(manifest.get("status"))) {
                    continue;
                }
                // R6-13 修正：老批次清单里计数是字符串（实测 2.json/4.json/5.json 报
                // "class java.lang.String cannot be cast to class java.lang.Number"），
                // 按数字/字符串双兼容解析，避免整条清单被当作损坏而跳过。
                long accepted = longOf(manifest.get("acceptedRecords"));
                long quarantined = longOf(manifest.get("quarantinedRecords"));
                if (accepted + quarantined <= 0) {
                    continue; // 空批次：无新数据，不阻塞也不作为输入（§9.3）
                }
                long batchId = longOf(manifest.get("batchId"));
                if (best.get() == null || batchId > maxBatchId.get()) {
                    maxBatchId.set(batchId);
                    best.set(manifest);
                }
            }
        } catch (IOException e) {
            log.warn("manifests 扫描失败: {}", e.getMessage());
            return null;
        }
        if (best.get() == null && (foreignSource.get() > 0 || unattributable.get() > 0)) {
            // 这种情况最需要留痕：库里的清单不是没有，而是**都不能归到本源名下**。
            // 静默返回 null 会让运维只看到 RUN_EMPTY_LANDING，误以为"没采集"。
            log.warn("manifests 目录共 {} 条清单，其中他源 {} 条、缺 sourceId（不可归属）{} 条，"
                            + "本源 {} 无可用 READY 批次 → 本轮按无输入处理（fail-closed，不装载他源字节）",
                    scanned.get(), foreignSource.get(), unattributable.get(), sourceId);
        }
        return best.get();
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
