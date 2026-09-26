package com.graduation.analytics.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-04：Landing → ODS 的**输入清单选择**必须按源归属（采集侧写下的 sourceId 说了算）；
 * G31-11（D-049b）：扫描叠加**消费台账**——已消费批次不作候选，未消费批次按 FIFO（batchId
 * 最小）取最老，「多待处理批次逐批处理不遗漏」；钉住批次**即使已消费也返回**（是否 no-op
 * 由调用方按台账决定，选择器不替调用方拍板，D-049d）。
 *
 * <p>实测缺陷（recon gap 1，2026-09-16）：{@code PipelineService.findReadyManifest} 只按
 * "status=READY 且 accepted+quarantined&gt;0 的最新 batchId"挑清单，**没有任何源条件**；
 * 而 ODS 库名与 {@code --sourceSystem} 来自 {@code profile.source_id → source_registry}。
 * 于是当两个源共用一份 {@code landing_uri}（本地实验环境换源复采时就是这么用的）时，
 * B 源新写的清单会被 A 源的那次 run 选中：B 的 accepted 文件被装进 A 的 {@code <prefix>_ods}，
 * 且每一行的 {@code source_system} 被 OdsLoadSql 按 A 的 source_code **注入为常量字面量**
 * （{@code OdsLoadSql.sourceSystemLiteral}），行内没有任何字段能与它比对——
 * 这类错账事后无法从 ODS 里察觉。故此处 fail-closed：宁可这一轮空跑（RUN_EMPTY_LANDING），
 * 也不装载无法归属的批次字节（与 P1-05「拒绝发生在任何写入之前」同口径）。</p>
 *
 * <p>本测试只碰文件系统与 JSON，不需要库、不需要 Spark。规则的所有者从 {@code PipelineService}
 * 里收口到本类（原实现把选择规则散在两个私有方法里，且与"钉住批次"的重试语义混在一起）。</p>
 */
class LandingManifestSelectorTest {

    /** 本次 run 归属的源 */
    private static final long SOURCE_A = 7L;
    /** 另一个源（共用同一份 landing 根） */
    private static final long SOURCE_B = 9L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final LandingManifestSelector selector = new LandingManifestSelector(mapper);

    @Test
    @DisplayName("首跑（无钉住批次）：取本源 READY 非空**未消费**批次中 batchId 最小者（FIFO，D-049b）")
    void picksOldestUnconsumedReadyBatchOfTheRunSource(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_A, 5, "READY");

        LandingManifestSelector.Selection selection = select(landing, null, SOURCE_A);
        assertThat(selection.manifest())
                .as("本源有两个待处理批次 → 取最老（FIFO：每轮吃掉最老的待处理批次，逐批不遗漏）")
                .containsEntry("batchId", 1);
        assertThat(selection.readyButConsumedCount()).as("尚无消费事实").isZero();
    }

    @Test
    @DisplayName("已消费批次不作候选：最老批次已在台账 → 跳过它取下一个未消费批次")
    void consumedBatchesAreSkippedInFavorOfOlderPending(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_A, 5, "READY");
        write(landing, 3, SOURCE_A, 7, "READY");

        LandingManifestSelector.Selection selection = select(landing, null, SOURCE_A, Set.of(1L));
        assertThat(selection.manifest())
                .as("批次 1 已消费（进了 ACTIVE），本轮吃批次 2：新输入确实能发布")
                .containsEntry("batchId", 2);
        assertThat(selection.readyButConsumedCount()).as("已消费的批次 1 计数").isEqualTo(1);
    }

    @Test
    @DisplayName("全部 READY 已消费 ⇒ manifest=null + readyButConsumedCount>0（M3 no-op 判据）")
    void allReadyConsumedYieldsNullManifestWithConsumedCount(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_A, 5, "READY");

        LandingManifestSelector.Selection selection = select(landing, null, SOURCE_A, Set.of(1L, 2L));
        assertThat(selection.manifest())
                .as("没有未消费批次 ⇒ 无新输入（是否 no-op 由调用方判定）")
                .isNull();
        assertThat(selection.readyButConsumedCount())
                .as("「有清单但全吃完了」必须与「没清单」（count=0）可区分")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("他源清单永远不被选中：即使它的 batchId 更大")
    void foreignSourceManifestIsNeverSelected(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_B, 9, "READY");

        assertThat(select(landing, null, SOURCE_A).manifest())
                .as("B 源批次更新也不能顶掉 A 源批次，否则 B 的字节会落进 A 的 ODS 库")
                .containsEntry("batchId", 1);

        assertThat(select(landing, null, SOURCE_B).manifest())
                .as("同一份 landing 根下，B 源自己仍选得到自己的清单")
                .containsEntry("batchId", 2);
    }

    @Test
    @DisplayName("只有他源清单时如实返回 null（fail-closed：宁可空跑，不装载他源字节）")
    void onlyForeignManifestsYieldsNull(@TempDir Path landing) throws IOException {
        write(landing, 5, SOURCE_B, 4, "READY");

        LandingManifestSelector.Selection selection = select(landing, null, SOURCE_A);
        assertThat(selection.manifest())
                .as("没有可归属的清单 ⇒ 调用方按 RUN_EMPTY_LANDING 拒绝，不回落到他源批次")
                .isNull();
        assertThat(selection.readyButConsumedCount()).as("他源批次不计入本源已消费").isZero();
    }

    @Test
    @DisplayName("缺 sourceId 的清单不可归属：既不参与扫描，也不接受被钉住")
    void manifestWithoutSourceIdIsNotAttributable(@TempDir Path landing) throws IOException {
        Map<String, Object> legacy = manifest(3, 4, "READY");
        legacy.remove("sourceId");
        Files.writeString(manifestsDir(landing).resolve("3.json"),
                mapper.writeValueAsString(legacy), StandardCharsets.UTF_8);

        assertThat(select(landing, null, SOURCE_A).manifest())
                .as("P1-05 之前的清单没有源身份：无法证明它属于本源的库，按不可归属处理（不猜）")
                .isNull();
        assertThat(select(landing, 3L, SOURCE_A).manifest())
                .as("被钉住也同样拒绝：钉住只保证'批次不换'，不能保证'批次属于本源的库'")
                .isNull();
    }

    @Test
    @DisplayName("钉住的是他源批次：丢弃该清单并回落到本源扫描结果（绝不返回他源清单）")
    void pinnedForeignManifestFallsBackToOwnSource(@TempDir Path landing) throws IOException {
        write(landing, 9, SOURCE_B, 6, "READY");
        write(landing, 1, SOURCE_A, 3, "READY");

        assertThat(select(landing, 9L, SOURCE_A).manifest())
                .as("钉住批次换了源（landing 根被复用时会发生）→ 不能拿它当本轮输入")
                .containsEntry("batchId", 1);
    }

    @Test
    @DisplayName("钉住本源批次：重试/恢复/重算不换输入（即使后来出现了更新的 READY 批次）")
    void pinnedOwnBatchWinsOverNewer(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_A, 7, "READY");

        assertThat(select(landing, 1L, SOURCE_A).manifest())
                .as("R6-13：同一 run 重试必须复用原批次，否则判定不可复现")
                .containsEntry("batchId", 1);
    }

    @Test
    @DisplayName("钉住本源**已消费**批次：照常返回该清单（D-049d：no-op 与重算由调用方按台账判定）")
    void pinnedConsumedBatchIsStillReturned(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_A, 5, "READY");

        LandingManifestSelector.Selection selection = select(landing, 1L, SOURCE_A, Set.of(1L));
        assertThat(selection.manifest())
                .as("重试绑定原批：普通重试的 no-op 判定与显式重算的再发布都需要这条清单")
                .containsEntry("batchId", 1);
        assertThat(selection.readyButConsumedCount())
                .as("钉住批次已消费的事实进入计数，调用方据此查台账")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("钉住未消费批次时其余已消费批次照常计数（重算路径的计数支撑）")
    void pinnedUnconsumedBatchStillCountsOtherConsumed(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_A, 5, "READY");

        LandingManifestSelector.Selection selection = select(landing, 1L, SOURCE_A, Set.of(2L));
        assertThat(selection.manifest())
                .as("钉住优先于 FIFO 扫描")
                .containsEntry("batchId", 1);
        assertThat(selection.readyButConsumedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("既有过滤一条不少：非 READY、空批次（accepted+quarantined=0）、字符串计数（R6-13）")
    void keepsExistingFilters(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "FAILED");
        Map<String, Object> empty = manifest(2, 0, "READY");
        empty.put("sourceId", SOURCE_A);
        Files.writeString(manifestsDir(landing).resolve("2.json"),
                mapper.writeValueAsString(empty), StandardCharsets.UTF_8);

        assertThat(select(landing, null, SOURCE_A).manifest())
                .as("非 READY 与空批次都不作为输入（§9.3）")
                .isNull();

        // R6-13：老批次清单里计数是**字符串**（实测 2.json/4.json/5.json 报
        // "class java.lang.String cannot be cast to class java.lang.Number"），
        // 双兼容解析必须保留，否则整条清单会被当作损坏而跳过。
        Map<String, Object> stringCounts = manifest(4, 0, "READY");
        stringCounts.put("sourceId", SOURCE_A);
        stringCounts.put("acceptedRecords", "3");
        stringCounts.put("quarantinedRecords", "1");
        Files.writeString(manifestsDir(landing).resolve("4.json"),
                mapper.writeValueAsString(stringCounts), StandardCharsets.UTF_8);

        assertThat(select(landing, null, SOURCE_A).manifest())
                .as("字符串计数按数字解析：accepted=3 + quarantined=1 > 0 ⇒ 可用")
                .containsEntry("batchId", 4);
    }

    @Test
    @DisplayName("manifests 目录不存在 ⇒ null + count=0（不抛异常，交给阶段报 RUN_EMPTY_LANDING）")
    void missingManifestsDirYieldsNull(@TempDir Path landing) {
        assertThat(select(landing, null, SOURCE_A).manifest()).isNull();
        assertThat(select(landing, 42L, SOURCE_A).manifest()).isNull();
        assertThat(select(landing, null, SOURCE_A).readyButConsumedCount()).isZero();
    }

    @Test
    @DisplayName("损坏的清单 JSON 不使整次选择失败：跳过坏文件，其余照常比较")
    void brokenManifestDoesNotBreakSelection(@TempDir Path landing) throws IOException {
        Files.writeString(manifestsDir(landing).resolve("6.json"), "{ not json", StandardCharsets.UTF_8);
        write(landing, 1, SOURCE_A, 3, "READY");

        assertThat(select(landing, null, SOURCE_A).manifest()).containsEntry("batchId", 1);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    /** 无消费事实的选择（等价于 V33 之前的行为） */
    private LandingManifestSelector.Selection select(Path landing, Long pinned, long sourceId) {
        return select(landing, pinned, sourceId, Set.of());
    }

    private LandingManifestSelector.Selection select(Path landing, Long pinned, long sourceId,
                                                     Set<Long> consumedBatchIds) {
        return selector.select(landing, pinned, sourceId, consumedBatchIds);
    }

    private void write(Path landing, int batchId, long sourceId, long acceptedRecords, String status)
            throws IOException {
        Map<String, Object> m = manifest(batchId, acceptedRecords, status);
        m.put("sourceId", sourceId);
        Files.writeString(manifestsDir(landing).resolve(batchId + ".json"),
                mapper.writeValueAsString(m), StandardCharsets.UTF_8);
    }

    /** 与 IngestionService.buildManifest 同形（只保留选择规则用得到的字段 + 源身份） */
    private Map<String, Object> manifest(int batchId, long acceptedRecords, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("batchNo", "ing-20260916000000-deadbeef");
        m.put("status", status);
        m.put("acceptedUri", "accepted/" + batchId);
        m.put("quarantineUri", "quarantine/" + batchId);
        m.put("acceptedRecords", acceptedRecords);
        m.put("quarantinedRecords", 0);
        m.put("acceptedBytes", 128);
        m.put("checksum", "crc32-" + batchId);
        m.put("schemaVersions", java.util.List.of("1.0"));
        return m;
    }

    private static Path manifestsDir(Path landing) throws IOException {
        return Files.createDirectories(landing.resolve("manifests"));
    }
}
