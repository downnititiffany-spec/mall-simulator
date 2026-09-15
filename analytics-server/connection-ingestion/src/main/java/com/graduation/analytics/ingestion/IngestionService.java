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
import com.graduation.analytics.landing.LandingInputScanner;
import com.graduation.analytics.landing.LandingLayout;
import com.graduation.analytics.mapping.ingest.SourceMapper;
import com.graduation.analytics.mapping.ingest.SourceMapping;
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
 *   landing/manifests/{batchId}.json 批次清单（状态 READY，§9.3；**只有 errorCount=0 的批次才产出**——
 *   见 {@code runOne} 清单段：清单存在即"可交付"，失败批次不得发布半成品，S2-02B）；
 * - S2-02B 失败三分类（互不伪装）：① 映射/业务数据问题 ⇒ 隔离行 + {@code reason}，批次 QUARANTINED；
 *   ② canonical 契约不满足 ⇒ 隔离行 + 契约校验器原因（无 {@code MAPPING:} 前缀），批次 QUARANTINED；
 *   ③ 系统自身异常 ⇒ 该文件记 errorCount、批次 FAILED、**断点不推进**、不产出清单；
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
     * 真实采集的映射适配器（S2-02）。装载**必须在任何写入之前**完成：画像不可用就整轮拒绝，
     * 而不是写一半再发现"这一批根本没有可用的映射"。
     */
    private final SourceMapper sourceMapper;

    /**
     * 一轮采集的结果。
     *
     * <p>B-08 / D-022 候选①（最小明示）：{@code noNewData=true} 表示本次**没有读到任何新字节**
     * ——数据源停机时平台据此明示"本次无新数据（数据源未产出）"，而不是只回一个 {@code SUCCESS + 0 条}。
     * 判定口径不变（批次状态仍按错误/隔离行决定），不探活生产者、不新增外部依赖。</p>
     *
     * <p>S2-02B：{@code manifestPath} 在 {@code status=FAILED} 时为 {@code null}——失败批次不产出清单
     * （清单 schema 的 {@code status} 是 {@code const "READY"}，清单存在即断言可交付）。
     * 调用方不得把 null 当成"清单写失败"，它是"本轮不可交付"的正式表达。</p>
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
     *
     * <p>S2-02：映射绑定与源解析同样是"拒绝发生在任何写入之前"的一环——画像缺失/不可读/与登记源
     * 不一致（{@code MAPPING_PROFILE_INVALID}）或可装载但禁止激活（{@code MAPPING_PROFILE_BLOCKED}）
     * 都在批次行 insert 之前抛出，因此库里不会出现"批次已建但一行都没映射"的残批。</p>
     */
    public RunResult runOne(TraceContext trace) {
        RuntimeProfile active = runtimeProfileService.getActive();
        long runtimeProfileId = active.getId();
        long sourceId = requireBoundSourceId(runtimeProfileId);
        // 本轮 manifest 的源身份三字段一次性从登记读口取好（同一行 profile 只读一次）：
        //   sourceCode / profileVersion 取 source_registry 的**列**（D-037 裁决 8：不解析画像 JSON 反推版本，
        //   那是第二个所有者，且画像文件在本轮尚未交付）；sourceId 取上面解析出的激活源。
        SourceRegistryView source = requireSourceView(sourceId);
        // S2-02：本轮映射绑定在**批次行 insert 之前**定妥（fail-closed 三态见 SourceMapper.prepare）：
        //   画像非法/缺失/与登记源不一致 → MAPPING_PROFILE_INVALID；可装载但禁止激活 → MAPPING_PROFILE_BLOCKED；
        //   v1 只读兼容画像 → 不映射（SourceMapping.legacy()），既有采集链路行为不变。
        // 一轮只装载一次：批内文件必须用同一份映射，中途改画像会让同一批次里的行语义不一致。
        SourceMapping mapping = sourceMapper.prepare(source);
        Path landingRoot = LandingUri.resolve(active.getLandingUri());
        // S2-04B（§8.2）：输入根由**布局**决定：滚动日志＝<landing>/events（一层、*.jsonl，
        // V2 起的既有语义）；Flume 目标区＝<landing>/raw（递归，dt=/hour= 是目录层级）。
        // 布局未配置（V22 之前的存量行）等价于滚动日志——不得因为新增一列而改变存量环境的读法。
        LandingLayout layout = LandingLayout.effective(active.getLandingLayout());
        Path inputRoot = layout.inputRoot(landingRoot);
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
            // S2-04B：枚举"哪些文件算本轮输入"的唯一所有者是 LandingInputScanner（§8.2 规则 4：
            // in-use 临时文件不进清单，只枚举已完成文件）。它按 inputKey 升序返回 → 确定性批次顺序。
            // 滚动日志布局下 inputKey 就是文件名，因此这里的顺序与既有 TreeMap<文件名> 完全一致。
            for (LandingInputScanner.ScannedFile entry : LandingInputScanner.scan(inputRoot, layout)) {
                String inputKey = entry.inputKey();
                try {
                    // P1-05：逐文件复核"本轮归属的源"。批次行的 source_id 在开头就定了，
                    // 期间若有人切换激活源（D-035 只允许一个 ACTIVE 源），继续写就会把 B 源的
                    // 字节记进 A 源的批次、并把断点写到 A 源名下 —— 本次记 FAILED 并中断，
                    // 宁可这一轮失败，也不产出**归属错误**的批次（那是事后无法察觉的错账）。
                    requireSourceUnchanged(runtimeProfileId, sourceId, inputKey);
                    // S2-02B（批次重放口径）：**同一批次不得二次消费同一输入**。
                    // 判据不是新造的指纹，而是既有唯一键 uk_batch_file(batch_id, file_path) 已经写下的
                    // 「本批次已 LANDED 该文件」这笔账（{@link IngestionBatchFile}，L186 就是它的写入点）：
                    //   ① 已 LANDED + 无新内容 = 同批次、同输入的幂等重放 ⇒ 照常走，下游读到 0 条新记录；
                    //   ② 已 LANDED + 有新内容（追加 / 重建）= 同批次换了输入 ⇒ **显式冲突**，
                    //      这批字节不能再进本批次（那会产出既无法归属、也无法对账的重复行）。
                    // 冲突按"本轮不得继续"处理：记 errorCount ⇒ 批次 FAILED ⇒（见下方清单段）不产出 READY 清单，
                    // 因此半成品不会被下游当成本轮输入；重放须开新批次。
                    // S2-04B：这笔账的 file_path 用**输入根相对键**（而非文件名）：嵌套布局下
                    // "同名不同分区"必须是两个输入，否则第二个会撞唯一键并把整批拖成 FAILED。
                    String fileName = inputKey;
                    Long landed = batchFileMapper.selectCount(new LambdaQueryWrapper<IngestionBatchFile>()
                            .eq(IngestionBatchFile::getBatchId, batch.getId())
                            .eq(IngestionBatchFile::getFilePath, fileName));
                    if (landed != null && landed > 0
                            && ingestor.hasConsumableData(entry.file(), runtimeProfileId, sourceId)) {
                        throw new PlatformBizException(PlatformBizException.INGEST_BATCH_INPUT_CONFLICT,
                                PlatformBizException.INGEST_BATCH_INPUT_CONFLICT + ": 批次 " + batchId
                                        + " 已消费文件 " + fileName + "，本轮该文件又有新内容；"
                                        + "同一批次不得二次消费同一输入（重放须开新批次）");
                    }
                    var res = ingestor.ingestFile(entry.file(), batch.getId(), runtimeProfileId, sourceId,
                            acceptedDir, quarantineDir, trace, checksum, mapping);
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
                        // S2-04B：**账本**的 file_path 记输入根相对键（见上方 fileName 注释）——
                        // ingestion_batch_file 不在 contract-specs 的既有契约里（README 列为
                        // "V2.1 §5.2 待做"），因此这里的取值形状由本侧决定，嵌套布局才认得清分区。
                        bf.setFilePath(inputKey);
                        bf.setStartOffset(res.startOffset());
                        bf.setEndOffset(res.endOffset());
                        bf.setRecordCount(res.collected());
                        bf.setStatus("LANDED");
                        batchFileMapper.insert(bf);
                        // 清单的 files[].file 仍是**文件名**（res.filePath()＝FileResult 的既有语义）：
                        // ingestion-manifest.v1.schema.json 明确写着「文件名（非绝对路径）」并引用
                        // LocalFileIngestor 的 getFileName()，改它的取值域属于契约语义变更（真决策门）。
                        // 代价是 **FLUME_RAW 布局下清单里可能出现同名条目**（不同分区同一天同一小时文件名
                        // 相同）——这是**已登记的待裁决契约问题**（F-31），不是无声的取舍：
                        // 账本已用相对键消歧，清单侧等裁决后再改一个表达式即可。
                        files.add(Map.of(
                                "file", res.filePath(),
                                "acceptedRecords", res.collected(),
                                "quarantinedRecords", res.quarantined(),
                                "startOffset", res.startOffset(),
                                "endOffset", res.endOffset()));
                    }
                } catch (Exception e) {
                    errorCount++;
                    log.warn("ingest file failed: {} ({})", inputKey, e.getMessage());
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
        // S2-02B：**失败批次不产出清单**。清单 schema 的 status 是 const "READY"
        // （contract-specs/schemas/ingestion-manifest.v1.schema.json：清单文档只能断言"可交付"），
        // 而流水线侧（S2-04 起为 LandingManifestSelector）只扫 manifests/*.json 中 status=READY
        // 且计数>0、并且 sourceId 与本轮运行源一致的清单。
        // 系统异常时若照写一份 READY 清单，异常前已落盘的**半成品**（recordCount>0）就会被流水线
        // 当成本轮输入 —— 那是把系统异常伪装成"采集成功"。不写清单还顺带满足"失败保旧快照"：
        // 失败轮不占清单位，上一份好批次仍是流水线能取到的输入。
        // 失败证据不丢：ingestion_batch 行（status=FAILED / error_count）、accepted 与 quarantine 目录、
        // 每个文件的 warn 日志都在原地；下一轮以新批次重读（断点未推进 ⇒ 不丢数据，重复投递由
        // DWD 侧 event_id 去重兜底，见 D-107/D-117 的 DUPLICATE_EVENT 归属）。
        String manifestUri = null;
        if (errorCount == 0) {
            String manifestJson = buildManifest(batchId, runtimeProfileId, batchNo, startedAt,
                    recordCount, quarantineCount, fileCount, acceptedBytes, checksum, schemaVersions, files,
                    source, mapping);
            manifestUri = writeManifestQuietly(landingRoot, batchId, manifestJson);
        } else {
            log.warn("批次 {} 有 {} 个文件采集失败（status=FAILED）⇒ 不产出批次清单；"
                            + "accepted/quarantine 目录保留为失败证据，重放须开新批次",
                    batchId, errorCount);
        }

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
     * 批次清单（§9.3 的 15 个键 + P1-05 / D-037 裁决 6 新增的 4 个源身份键 + S2-02 新增的 {@code mappingProfileHash}）。
     *
     * <p>新增键的定位：**可选的来源标注**，全部不进 schema 的 {@code required}
     * （裁决 6 的硬约束）——一旦进 required，V17 之前落盘的 39 个清单会立刻判非法。
     * 各字段口径：</p>
     * <ul>
     *   <li>{@code sourceCode} ← {@code source_registry.source_code}（不是 {@code display_name}）；</li>
     *   <li>{@code sourceId} ← 本轮解析出的激活源 id；</li>
     *   <li>{@code profileVersion} ← {@code source_registry.profile_version} **列**
     *       （裁决 8：画像文件里的同名值是"文件自述版本"，两者一致性属 P3-01，不在此处校验）；</li>
     *   <li>{@code mappingVersion} ← 本轮**生效画像**的 {@code profileVersion}（未应用映射时为 {@code null}；S2-02 起不再是"恒为 null"）。
     *       写 {@code ""}/{@code "0"}/{@code "v1"} 之类占位值会让下游以为映射已生效，故未映射时显式放 null 键。</li>
 *   <li>{@code mappingProfileHash} ← 生效画像**原文**的 sha256（与 dry-run 报告 {@code profileChecksum} 同算法）；
 *       未映射时为 {@code null}。版本号会被人为复用，哈希不会，因此"预览合格"与"这批真用了它"可逐字节对账。</li>
     * </ul>
     *
     * <p>{@code source}（连接器类型，恒为 {@code local-file}）语义不变，**不是**源身份
     * （裁决 5：不得改名、不得复用为源标识）。</p>
     */
    private String buildManifest(String batchId, long runtimeProfileId, String batchNo,
                                 LocalDateTime startedAt, long acceptedRecords, long quarantinedRecords,
                                 int files, long acceptedBytes, CRC32 checksum,
                                 Map<String, Boolean> schemaVersions, List<Map<String, Object>> fileList,
                                 SourceRegistryView source, SourceMapping mapping) {
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
        manifest.put("mappingVersion", mapping.profileVersion());
        // S2-02 加法新增（第 20 个键，可选）：本次生效画像**原文**的 sha256。
        // 与 dry-run 报告的 profileChecksum 同算法，因此"预览某画像合格"与"这批数据用了该画像"
        // 可以逐字节对账——版本号会被人为复用，哈希不会。
        // mappingVersion != null ⟺ mappingProfileHash != null ⟺ 本轮真的逐行映射过，
        // 因此不再另加 mappingApplied 布尔键（同一事实两个表示必然漂移）。
        manifest.put("mappingProfileHash", mapping.profileChecksum());
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
        // S2-04B：状态口与采集端**同源**取输入根——布局决定目录（滚动日志＝events/，Flume＝raw/）。
        // 状态口按 events/ 扫而采集读 raw/ 会让运维看到 pendingFiles=0 却看着数据被采走。
        LandingLayout layout = active == null ? LandingLayout.ROLLING_LOG
                : LandingLayout.effective(active.getLandingLayout());
        Path inputDir = landingRoot == null ? null : layout.inputRoot(landingRoot);
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
        if (inputDir != null) {
            // S2-04B：枚举口径与采集端同源（LandingInputScanner），但**取的是候选集不是完成集**。
            // 这里回答的是"源还在不在产出"（pendingFiles/pendingBytes/lastArrivalAt/断点覆盖），
            // 不是"这一轮采哪些"：零字节文件确实是到达的文件（旧口径也算它），把它从观测里剔掉
            // 会得到「目录里有文件、lastArrivalAt=null」这种自相矛盾的总览。
            // 采集端用 scan()（只认完成文件，规则 4），两者共用同一份排除规则。
            try {
                for (LandingInputScanner.Candidate observed : LandingInputScanner.inspect(inputDir, layout)) {
                    Path f = observed.file();
                    pendingFiles++;
                    pendingBytes += observed.size();
                    LocalDateTime arrivedAt = LocalDateTime.ofInstant(
                            Files.getLastModifiedTime(f).toInstant(), java.time.ZoneId.systemDefault());
                    if (lastArrivalAt == null || arrivedAt.isAfter(lastArrivalAt)) {
                        lastArrivalAt = arrivedAt;
                    }
                    if (checkpointKeys.contains(LocalFileIngestor.checkpointKey(f))) {
                        checkpointFiles++;
                    }
                    if (observed.completed() && ingestor.hasConsumableData(f, active.getId(), sourceId)) {
                        newFileCount++;
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                log.warn("landing input dir scan failed: {}", e.getMessage());
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
        result.put("landingLayout", layout.name());
        // eventsDir 这个名字是 V2 起的既有对外键（前端/运维脚本在读）。S2-04B 后它的值＝
        // **本轮真实的输入根**：滚动日志布局下就是 events/（与 V2 完全一致），Flume 布局下是 raw/。
        // 保留旧键名而不是换名（换名会静默打断既有消费者），但值必须与采集端同源、不得指向没读的目录。
        result.put("eventsDir", inputDir == null ? null : inputDir.toString());
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