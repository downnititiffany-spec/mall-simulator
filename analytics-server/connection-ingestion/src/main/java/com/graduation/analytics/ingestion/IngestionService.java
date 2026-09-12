package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.entity.IngestionBatchFile;
import com.graduation.analytics.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.common.LandingUri;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.common.TraceContext;
import com.graduation.analytics.runtime.RuntimeProfileService;
import com.graduation.analytics.runtime.entity.RuntimeProfile;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * 采集编排服务（§5.2.7 批次状态机 + 整改书 §9）：
 * GENERATED → COLLECTING → LANDED → VALIDATING → SUCCESS / QUARANTINED
 * - 一轮采集归属当前 ACTIVE RuntimeProfile：checkpoint 键含 runtime_profile_id **与 source_id**（§9.2；P1-05 / D-037 裁决 1）；
 * - 本轮采集归属的源 = 「当前激活源」读口（D-035 裁决 ②：{@code runtime_profile(ACTIVE).source_id}），
 *   由 {@link SourceRegistryService#currentSourceId()} 单点解析；**未绑定源即 fail-closed**
 *   （{@link PlatformBizException#SOURCE_NOT_BOUND}，绝不回落到某个固定源）；
 * - 输出目录职责（§9.1）：landing/accepted/{batchId} 校验通过、landing/quarantine/{batchId} 坏行、
 *   landing/manifests/{batchId}.json 批次清单（状态 READY，§9.3）；
 * - ODS 只能读取 accepted，禁止直接读 source/events（§9.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final IngestionBatchMapper batchMapper;
    private final IngestionBatchFileMapper batchFileMapper;
    private final LocalFileIngestor ingestor;
    private final EventClock eventClock;
    private final RuntimeProfileService runtimeProfileService;
    /**
     * 「当前激活源」的读口（D-035 裁决 ②的单一读写者）。P1-05 的源维度只从这里取，
     * **不另写第二个 {@code SELECT ... FROM runtime_profile}**——否则"当前源"就有了两个所有者，
     * 二者在切换的瞬间必然不一致。
     */
    private final SourceRegistryService sourceRegistryService;
    private final ObjectMapper objectMapper;

    /**
     * 一轮采集的结果。
     *
     * <p>B-08 / D-022 候选①（最小明示）：{@code noNewData=true} 表示本次**没有读到任何新字节**
     * ——数据源停机时平台据此明示"本次无新数据（数据源未产出）"，而不是只回一个 {@code SUCCESS + 0 条}。
     * 判定口径不变（批次状态仍按错误/隔离行决定），不探活生产者、不新增外部依赖。</p>
     */
    public record RunResult(Long batchId, String batchNo, String status,
                            long recordCount, long quarantineCount, long errorCount,
                            int fileCount, long acceptedBytes, String acceptedDir,
                            String quarantineDir, String manifestPath, boolean noNewData) {
    }

    /**
     * 执行一轮采集（手动触发/演示控制台；定时触发在平台阶段）。
     * 采集归属当前 ACTIVE 运行环境（§8.3）；landing 根取 profile.landingUri。
     *
     * <p>P1-05：先解析「本轮归属的源」，解析不到就**直接拒绝**（{@code SOURCE_NOT_BOUND}），
     * 且拒绝发生在**任何写入之前**——批次行、accepted/quarantine 目录、清单都不产生。
     * 这样库里不会出现 source_id 为空的新行（与 V17 回填过的历史行混在一起就再也分不清
     * "历史未标注"和"新代码没写"）。</p>
     */
    public RunResult runOne(TraceContext trace) {
        RuntimeProfile active = runtimeProfileService.getActive();
        long runtimeProfileId = active.getId();
        long sourceId = requireBoundSourceId(runtimeProfileId);
        // 本轮 manifest 的源身份三字段一次性从登记读口取好（同一行 profile 只读一次）：
        //   sourceCode / profileVersion 取 source_registry 的**列**（D-037 裁决 8：不解析画像 JSON 反推版本，
        //   那是第二个所有者，且画像文件在本轮尚未交付）；sourceId 取上面解析出的激活源。
        SourceRegistryView source = requireSourceView(sourceId);
        Path landingRoot = LandingUri.resolve(active.getLandingUri());
        Path eventsDir = landingRoot.resolve("events");
        // 批次号带随机后缀，避免同秒多次运行撞唯一键；时间取**注入的业务时间源**（§20.3 不把系统当前时间
        // 当业务时间），与批次 createdAt（startedAt，同一时间源）保持一致，冻结契约的 ^ing-\d{14}-[0-9a-f]{8}$ 不变
        String batchNo = "ing-" + BATCH_NO.format(eventClock.nowLdt())
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        IngestionBatch batch = new IngestionBatch();
        batch.setBatchNo(batchNo);
        batch.setRuntimeProfileId(runtimeProfileId);
        batch.setSourceId(sourceId);
        batch.setSource("local-file");
        batch.setStatus(IngestionBatch.STATUS_COLLECTING);
        batch.setRecordCount(0L);
        batch.setErrorCount(0L);
        batch.setQuarantineCount(0L);
        batch.setStartTime(eventClock.nowLdt());
        batchMapper.insert(batch);
        String batchId = String.valueOf(batch.getId());

        Path acceptedDir = landingRoot.resolve("accepted").resolve(batchId);
        Path quarantineDir = landingRoot.resolve("quarantine").resolve(batchId);
        batch.setLandingDir(acceptedDir.toString());
        batchMapper.updateById(batch);

        long recordCount = 0;
        long quarantineCount = 0;
        long errorCount = 0;
        int fileCount = 0;
        long acceptedBytes = 0;
        CRC32 checksum = new CRC32();
        var schemaVersions = new TreeMap<String, Boolean>();
        var files = new ArrayList<Map<String, Object>>();
        boolean anyNewBytes = false;   // B-08：本次是否读到过新字节（含新增/重建/追加三种情形）
        LocalDateTime startedAt = eventClock.nowLdt();
        try {
            Files.createDirectories(acceptedDir);
            Files.createDirectories(quarantineDir);
            Map<String, Path> hourFiles = new TreeMap<>(); // 文件名排序 → 确定性批次顺序
            if (Files.isDirectory(eventsDir)) {
                try (Stream<Path> s = Files.list(eventsDir)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                            .forEach(p -> hourFiles.put(p.getFileName().toString(), p));
                }
            }
            for (var entry : hourFiles.entrySet()) {
                try {
                    // P1-05：逐文件复核"本轮归属的源"。批次行的 source_id 在开头就定了，
                    // 期间若有人切换激活源（D-035 只允许一个 ACTIVE 源），继续写就会把 B 源的
                    // 字节记进 A 源的批次、并把断点写到 A 源名下 —— 本次记 FAILED 并中断，
                    // 宁可这一轮失败，也不产出**归属错误**的批次（那是事后无法察觉的错账）。
                    requireSourceUnchanged(runtimeProfileId, sourceId, entry.getKey().toString());
                    var res = ingestor.ingestFile(entry.getValue(), batch.getId(), runtimeProfileId, sourceId,
                            acceptedDir, quarantineDir, trace, checksum);
                    // endOffset > startOffset ⇒ 真实推进了断点（有新内容可读），与 fileCount 的
                    // "产出了记录"是两件事：全是坏行的文件同样说明数据源在产出（B-08 / D-022）
                    if (res.endOffset() > res.startOffset()) {
                        anyNewBytes = true;
                    }
                    if (res.collected() > 0 || res.quarantined() > 0) {
                        recordCount += res.collected();
                        quarantineCount += res.quarantined();
                        acceptedBytes += res.acceptedBytes();
                        fileCount++;
                        res.schemaVersions().forEach(v -> schemaVersions.put(v, true));
                        IngestionBatchFile bf = new IngestionBatchFile();
                        bf.setBatchId(batch.getId());
                        bf.setFilePath(res.filePath());
                        bf.setStartOffset(res.startOffset());
                        bf.setEndOffset(res.endOffset());
                        bf.setRecordCount(res.collected());
                        bf.setStatus("LANDED");
                        batchFileMapper.insert(bf);
                        files.add(Map.of(
                                "file", res.filePath(),
                                "acceptedRecords", res.collected(),
                                "quarantinedRecords", res.quarantined(),
                                "startOffset", res.startOffset(),
                                "endOffset", res.endOffset()));
                    }
                } catch (Exception e) {
                    errorCount++;
                    log.warn("ingest file failed: {} ({})", entry.getKey(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("采集目录准备失败", e);
        }

        batch.setRecordCount(recordCount);
        batch.setQuarantineCount(quarantineCount);
        batch.setErrorCount(errorCount);
        batch.setStatus(errorCount > 0 ? IngestionBatch.STATUS_FAILED
                : (quarantineCount > 0 ? IngestionBatch.STATUS_QUARANTINED : IngestionBatch.STATUS_SUCCESS));
        batch.setEndTime(eventClock.nowLdt());
        batchMapper.updateById(batch);

        // 批次清单（§9.3）：status=READY 表示落地完成可供 ODS 读取
        String manifestJson = buildManifest(batchId, runtimeProfileId, batchNo, startedAt,
                recordCount, quarantineCount, fileCount, acceptedBytes, checksum, schemaVersions, files,
                source);
        String manifestUri = writeManifestQuietly(landingRoot, batchId, manifestJson);

        log.info("ingestion run {}: status={} records={} quarantine={} errors={} files={} bytes={} noNewData={}",
                batchNo, batch.getStatus(), recordCount, quarantineCount, errorCount, fileCount, acceptedBytes,
                !anyNewBytes);
        return new RunResult(batch.getId(), batchNo, batch.getStatus(), recordCount,
                quarantineCount, errorCount, fileCount, acceptedBytes,
                acceptedDir.toString(), quarantineDir.toString(), manifestUri, !anyNewBytes);
    }

    /**
     * 解析「本轮采集归属的源」，解析不到即 fail-closed。
     *
     * <p>读口是 {@link SourceRegistryService#currentSourceId()}（D-035 裁决 ② 的"当前激活源"唯一读写者），
     * 它返回 {@code Optional.empty()} 恰好表达"运行环境未绑定源"——比让本类自己去查
     * {@code runtime_profile.source_id} 更不容易退化成兜底（那种写法一遇到 null 就想填个默认值）。
     * 因此本方法**故意不提供** {@code orElse(1L)} 之类的写法。</p>
     */
    private long requireBoundSourceId(long runtimeProfileId) {
        return sourceRegistryService.currentSourceId().orElseThrow(() -> new PlatformBizException(
                PlatformBizException.SOURCE_NOT_BOUND,
                "运行环境未绑定源，先激活源再采集（runtime_profile_id=" + runtimeProfileId
                        + "，source_id 为空）"));
    }

    /**
     * 取源登记行（manifest 三字段的唯一来源）。
     *
     * <p>为什么不吞异常：{@code SourceRegistryService.get} 在 id 不存在时抛
     * {@code SOURCE_NOT_FOUND}（404 语义，登记口自己的口径，不在此处改写）。能走到这一步说明
     * "当前激活源"已经指向了 {@code sourceId}，正常状态下 {@code file_checkpoint}/{@code runtime_profile}
     * 的外键保证该行存在，因此这属于**绑定状态已损坏**。两种情况都**不静默降级**成"没有 sourceCode"——
     * 那会写出一份缺字段却仍然 READY 的清单，下游无从察觉。</p>
     */
    private SourceRegistryView requireSourceView(long sourceId) {
        try {
            return sourceRegistryService.get(sourceId);
        } catch (PlatformBizException e) {
            throw new PlatformBizException(PlatformBizException.SOURCE_NOT_BOUND,
                    "运行环境绑定的源在登记中不可用，先修复源绑定再采集（source_id=" + sourceId
                            + "，原因：" + e.getMessage() + "）");
        }
    }

    /** 逐文件复核源未变（见 {@code runOne} 内注释：切换源发生在批次中途时，继续写就是错账）。 */
    private void requireSourceUnchanged(long runtimeProfileId, long sourceId, String file) {
        long now = sourceRegistryService.currentSourceId().orElseThrow(() -> new PlatformBizException(
                PlatformBizException.SOURCE_NOT_BOUND,
                "运行环境未绑定源，先激活源再采集（runtime_profile_id=" + runtimeProfileId
                        + "，source_id 为空）"));
        if (now != sourceId) {
            throw new PlatformBizException(PlatformBizException.SOURCE_NOT_BOUND,
                    "采集过程中激活源被切换（本轮归属 source_id=" + sourceId + "，当前=" + now
                            + "），本轮已按失败中止以免把文件 " + file + " 归到错误的源名下");
        }
    }

    /**
     * 批次清单（§9.3 的 15 个键 + P1-05 / D-037 裁决 6 新增的 4 个源身份键）。
     *
     * <p>四个新键的定位：**可选的来源标注**，全部不进 schema 的 {@code required}
     * （裁决 6 的硬约束）——一旦进 required，V17 之前落盘的 39 个清单会立刻判非法。
     * 各字段口径：</p>
     * <ul>
     *   <li>{@code sourceCode} ← {@code source_registry.source_code}（不是 {@code display_name}）；</li>
     *   <li>{@code sourceId} ← 本轮解析出的激活源 id；</li>
     *   <li>{@code profileVersion} ← {@code source_registry.profile_version} **列**
     *       （裁决 8：画像文件里的同名值是"文件自述版本"，两者一致性属 P3-01，不在此处校验）；</li>
     *   <li>{@code mappingVersion} ← {@code null}：语义映射尚未实施，恒为 null 直到 P2。
     *       写 {@code ""}/{@code "0"}/{@code "v1"} 之类占位值会让下游以为映射已生效，故显式放 null 键。</li>
     * </ul>
     *
     * <p>{@code source}（连接器类型，恒为 {@code local-file}）语义不变，**不是**源身份
     * （裁决 5：不得改名、不得复用为源标识）。</p>
     */
    private String buildManifest(String batchId, long runtimeProfileId, String batchNo,
                                 LocalDateTime startedAt, long acceptedRecords, long quarantinedRecords,
                                 int files, long acceptedBytes, CRC32 checksum,
                                 Map<String, Boolean> schemaVersions, List<Map<String, Object>> fileList,
                                 SourceRegistryView source) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("batchId", Long.parseLong(batchId));
        manifest.put("batchNo", batchNo);
        manifest.put("runtimeProfileId", runtimeProfileId);
        manifest.put("source", "local-file");
        manifest.put("status", "READY");             // §9.3：WAIT_LANDING 认 READY manifest
        manifest.put("startedAt", startedAt.toString());
        manifest.put("finishedAt", eventClock.nowLdt().toString());
        manifest.put("files", fileList);
        manifest.put("acceptedRecords", acceptedRecords);
        manifest.put("quarantinedRecords", quarantinedRecords);
        manifest.put("acceptedBytes", acceptedBytes);
        manifest.put("schemaVersions", new ArrayList<>(schemaVersions.keySet()));
        manifest.put("acceptedUri", "accepted/" + batchId);
        manifest.put("quarantineUri", "quarantine/" + batchId);
        manifest.put("checksum", Long.toHexString(checksum.getValue()));
        // P1-05 / D-037 裁决 6：四个源身份键（可选，不进 required）。放在最后，保持既有键顺序不变，
        // 便于与历史清单逐键对账（历史清单只少了这四个键）。
        manifest.put("sourceCode", source.sourceCode());
        manifest.put("sourceId", source.id());
        manifest.put("profileVersion", source.profileVersion());
        manifest.put("mappingVersion", null);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        } catch (Exception e) {
            throw new IllegalStateException("manifest 序列化失败", e);
        }
    }

    private String writeManifestQuietly(Path landingRoot, String batchId, String manifestJson) {
        try {
            Path dir = landingRoot.resolve("manifests");
            Files.createDirectories(dir);
            Path file = dir.resolve(batchId + ".json");
            Files.writeString(file, manifestJson, StandardCharsets.UTF_8);
            return file.toUri().toString();
        } catch (IOException e) {
            log.error("manifest 写入失败 batchId={}", batchId, e);
            return null;
        }
    }

    /** 采集状态总览：events 待采文件、自上次采集以来的新增文件、最近到达时间、最近批次、断点数 */
    public Map<String, Object> status() {
        // Landing 根与 runOne 同源：只来自 ACTIVE RuntimeProfile.landingUri（§8.3 / V2.1 §3.4-4：
        // 平台不得引用 mall.* 配置键）。无 ACTIVE 环境时如实报告 NO_ACTIVE，不读取任何其它路径。
        RuntimeProfile active = runtimeProfileService.findActive().orElse(null);
        Path landingRoot = null;
        String landingError = null;
        if (active != null) {
            try {
                landingRoot = LandingUri.resolve(active.getLandingUri());
            } catch (PlatformBizException e) {
                // 显式报告错误：landingUri 不可解析时如实说明，绝不回退到任何默认目录（D-003）
                landingError = e.getMessage();
                log.warn("landingUri 不可解析（profile {}）：{}", active.getId(), e.getMessage());
            }
        }
        Path eventsDir = landingRoot == null ? null : landingRoot.resolve("events");
        long pendingFiles = 0;
        long pendingBytes = 0;
        // B-08 / D-022 候选①（最小明示）：D-016 的 pendingFiles/pendingBytes 是**整目录累计值**，
        // 说不清"数据源还在不在产出"。这里补两个可与采集端对齐的观测字段：
        //   newFileCount = 自上次采集以来**还有可采集完整行**的文件数（判定唯一所有者为
        //                  LocalFileIngestor.hasConsumableData，含尾部残行不算，DEF-12）；
        //   lastArrivalAt = landing 目录内最新文件的到达（最后修改）时间，无文件时如实为 null。
        // 只补观测，不改判定、不加表、不探活生产者（平台依旧不知道数据源进程的死活，只知道自己多久没收到数据）。
        long newFileCount = 0;
        // DEF-13：checkpointFiles 原先取 `checkpointMapper.selectCount(null)`——全表行数，
        // 既含其他运行环境的行，也含 M1-1 之前另一种路径写法留下的历史行（同一物理文件两行），
        // 于是出现 checkpointFiles=101 对 pendingFiles=51 的怪数。它要回答的问题其实是
        // "当前环境的 events 目录里有多少文件已经建立断点"，因此改为与 pendingFiles 同一次目录扫描内计数，
        // 断点命中由唯一所有者 LocalFileIngestor.checkpointKeys（读写共用规范键）回答，不删任何历史行。
        long checkpointFiles = 0;
        // P1-05 / D-037 裁决 1（读数端）：状态总览的断点口径必须与采集端同源，即**同时按 profile 与源**。
        // 未绑定源时如实报告 sourceId=null 且不查断点（checkpointFiles 保持 0），**不抛异常**：
        // 这是只读总览接口，抛异常会让运维在"还没来得及激活源"时连环境状态都看不到；
        // 也不回落到某个固定源——那正是 runOne 拒绝掉的兜底。
        Long sourceId = sourceRegistryService.currentSourceId().orElse(null);
        Set<String> checkpointKeys = (active == null || sourceId == null)
                ? Set.of()
                : ingestor.checkpointKeys(active.getId(), sourceId);
        LocalDateTime lastArrivalAt = null;
        if (eventsDir != null && Files.isDirectory(eventsDir)) {
            try (Stream<Path> files = Files.list(eventsDir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                    pendingFiles++;
                    long size = Files.size(f);
                    pendingBytes += size;
                    LocalDateTime arrivedAt = LocalDateTime.ofInstant(
                            Files.getLastModifiedTime(f).toInstant(), java.time.ZoneId.systemDefault());
                    if (lastArrivalAt == null || arrivedAt.isAfter(lastArrivalAt)) {
                        lastArrivalAt = arrivedAt;
                    }
                    if (checkpointKeys.contains(LocalFileIngestor.checkpointKey(f))) {
                        checkpointFiles++;
                    }
                    if (ingestor.hasConsumableData(f, active.getId(), sourceId)) {
                        newFileCount++;
                    }
                }
            } catch (IOException e) {
                log.warn("events dir scan failed: {}", e.getMessage());
            }
        }
        IngestionBatch latest = batchMapper.selectOne(new LambdaQueryWrapper<IngestionBatch>()
                .orderByDesc(IngestionBatch::getId).last("LIMIT 1"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profileState", active == null ? "NO_ACTIVE" : "ACTIVE");
        result.put("runtimeProfileId", active == null ? null : active.getId());
        result.put("sourceId", sourceId);
        result.put("landingUri", active == null ? null : active.getLandingUri());
        result.put("landingError", landingError);
        result.put("eventsDir", eventsDir == null ? null : eventsDir.toString());
        result.put("pendingFiles", pendingFiles);
        result.put("pendingBytes", pendingBytes);
        result.put("checkpointFiles", checkpointFiles);
        result.put("newFileCount", newFileCount);
        result.put("lastArrivalAt", lastArrivalAt == null ? null : lastArrivalAt.toString());
        if (latest != null) {
            Map<String, Object> latestInfo = new LinkedHashMap<>();
            latestInfo.put("batchId", latest.getId());
            latestInfo.put("runtimeProfileId", latest.getRuntimeProfileId());
            latestInfo.put("sourceId", latest.getSourceId());
            latestInfo.put("batchNo", latest.getBatchNo());
            latestInfo.put("status", latest.getStatus());
            latestInfo.put("recordCount", latest.getRecordCount());
            latestInfo.put("quarantineCount", latest.getQuarantineCount());
            result.put("latestBatch", latestInfo);
        } else {
            result.put("latestBatch", null);
        }
        return result;
    }

    public List<IngestionBatch> recentBatches(int limit) {
        return batchMapper.selectList(new LambdaQueryWrapper<IngestionBatch>()
                .orderByDesc(IngestionBatch::getId)
                .last("LIMIT " + Math.max(1, Math.min(100, limit))));
    }
}