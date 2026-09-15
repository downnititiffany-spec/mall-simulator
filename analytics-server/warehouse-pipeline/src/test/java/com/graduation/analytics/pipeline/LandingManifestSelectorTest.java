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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-04：Landing → ODS 的**输入清单选择**必须按源归属（采集侧写下的 sourceId 说了算）。
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
    @DisplayName("首跑（无钉住批次）：取本源的 READY 非空批次中 batchId 最大者")
    void picksNewestReadyBatchOfTheRunSource(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_A, 5, "READY");

        assertThat(selector.select(landing, null, SOURCE_A))
                .as("本源有两个可用批次 → 取最新（既有行为不变）")
                .containsEntry("batchId", 2);
    }

    @Test
    @DisplayName("他源清单永远不被选中：即使它的 batchId 更大")
    void foreignSourceManifestIsNeverSelected(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_B, 9, "READY");

        assertThat(selector.select(landing, null, SOURCE_A))
                .as("B 源批次更新也不能顶掉 A 源批次，否则 B 的字节会落进 A 的 ODS 库")
                .containsEntry("batchId", 1);

        assertThat(selector.select(landing, null, SOURCE_B))
                .as("同一份 landing 根下，B 源自己仍选得到自己的清单")
                .containsEntry("batchId", 2);
    }

    @Test
    @DisplayName("只有他源清单时如实返回 null（fail-closed：宁可空跑，不装载他源字节）")
    void onlyForeignManifestsYieldsNull(@TempDir Path landing) throws IOException {
        write(landing, 5, SOURCE_B, 4, "READY");

        assertThat(selector.select(landing, null, SOURCE_A))
                .as("没有可归属的清单 ⇒ 调用方按 RUN_EMPTY_LANDING 拒绝，不回落到他源批次")
                .isNull();
    }

    @Test
    @DisplayName("缺 sourceId 的清单不可归属：既不参与扫描，也不接受被钉住")
    void manifestWithoutSourceIdIsNotAttributable(@TempDir Path landing) throws IOException {
        Map<String, Object> legacy = manifest(3, 4, "READY");
        legacy.remove("sourceId");
        Files.writeString(manifestsDir(landing).resolve("3.json"),
                mapper.writeValueAsString(legacy), StandardCharsets.UTF_8);

        assertThat(selector.select(landing, null, SOURCE_A))
                .as("P1-05 之前的清单没有源身份：无法证明它属于本源的库，按不可归属处理（不猜）")
                .isNull();
        assertThat(selector.select(landing, 3L, SOURCE_A))
                .as("被钉住也同样拒绝：钉住只保证'批次不换'，不能保证'批次属于本源的库'")
                .isNull();
    }

    @Test
    @DisplayName("钉住的是他源批次：丢弃该清单并回落到本源扫描结果（绝不返回他源清单）")
    void pinnedForeignManifestFallsBackToOwnSource(@TempDir Path landing) throws IOException {
        write(landing, 9, SOURCE_B, 6, "READY");
        write(landing, 1, SOURCE_A, 3, "READY");

        assertThat(selector.select(landing, 9L, SOURCE_A))
                .as("钉住批次换了源（landing 根被复用时会发生）→ 不能拿它当本轮输入")
                .containsEntry("batchId", 1);
    }

    @Test
    @DisplayName("钉住本源批次：重试/恢复不换输入（即使后来出现了更新的 READY 批次）")
    void pinnedOwnBatchWinsOverNewer(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "READY");
        write(landing, 2, SOURCE_A, 7, "READY");

        assertThat(selector.select(landing, 1L, SOURCE_A))
                .as("R6-13：同一 run 重试必须复用原批次，否则判定不可复现")
                .containsEntry("batchId", 1);
    }

    @Test
    @DisplayName("既有过滤一条不少：非 READY、空批次（accepted+quarantined=0）、字符串计数（R6-13）")
    void keepsExistingFilters(@TempDir Path landing) throws IOException {
        write(landing, 1, SOURCE_A, 3, "FAILED");
        Map<String, Object> empty = manifest(2, 0, "READY");
        empty.put("sourceId", SOURCE_A);
        Files.writeString(manifestsDir(landing).resolve("2.json"),
                mapper.writeValueAsString(empty), StandardCharsets.UTF_8);

        assertThat(selector.select(landing, null, SOURCE_A))
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

        assertThat(selector.select(landing, null, SOURCE_A))
                .as("字符串计数按数字解析：accepted=3 + quarantined=1 > 0 ⇒ 可用")
                .containsEntry("batchId", 4);
    }

    @Test
    @DisplayName("manifests 目录不存在 ⇒ null（不抛异常，交给阶段报 RUN_EMPTY_LANDING）")
    void missingManifestsDirYieldsNull(@TempDir Path landing) {
        assertThat(selector.select(landing, null, SOURCE_A)).isNull();
        assertThat(selector.select(landing, 42L, SOURCE_A)).isNull();
    }

    @Test
    @DisplayName("损坏的清单 JSON 不使整次选择失败：跳过坏文件，其余照常比较")
    void brokenManifestDoesNotBreakSelection(@TempDir Path landing) throws IOException {
        Files.writeString(manifestsDir(landing).resolve("6.json"), "{ not json", StandardCharsets.UTF_8);
        write(landing, 1, SOURCE_A, 3, "READY");

        assertThat(selector.select(landing, null, SOURCE_A)).containsEntry("batchId", 1);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

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
