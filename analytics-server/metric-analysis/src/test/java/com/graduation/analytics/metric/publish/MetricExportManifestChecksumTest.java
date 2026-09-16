package com.graduation.analytics.metric.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3-06：`mxp` 导出制品内容摘要（checksum）的两条契约 —— **算法口径** 与 **清单字段必填**。
 *
 * <p>设计 §12.5 L528「`mxp` 导出 + manifest/checksum」、L529 的「**内容验证**」，
 * 指导书 §7 阶段 3 L151「导出 ADS 制品核 schema/行数/checksum」。落地前
 * `docs/audit/v2-completeness-audit.md:213` 已登记「**无 checksum（仅路径 + 行数）**」：
 * 行数相同的截断/改写制品在发布侧无任何判据。</p>
 *
 * <p><b>口径</b>：CRC32（IEEE 802.3，多项式 {@code 0xEDB88320}）作用于导出文件**全部原始字节**，
 * 输出 {@code Long.toHexString} 形式的小写十六进制、**无前导零**（{@code ^[0-9a-f]{1,8}$}）——
 * 与 landing 侧 {@code ingestion-manifest.v1} 的 checksum 写法同口径，避免同一制品在两条链路上
 * 出现两套摘要写法。</p>
 *
 * <p><b>oracle 的独立性</b>：期望常量不是用被测的同一个 {@code java.util.zip.CRC32} 现算的，
 * 而是独立实现（.NET {@code GZipStream} 的 gzip trailer = CRC32 小端）实测值，并用 CRC-32 经典
 * 校验值 {@code "123456789" -> cbf43926} 交叉验证过。</p>
 *
 * <p><b>属主边界</b>：本类只验「算法正确」与「字段缺失即拒绝解析」。**内容是否对得上**由发布侧
 * 唯一所有者 {@link MetricPublishValidator} 判定（{@code MP_EXPORT_CHECKSUM}），
 * 见 {@code MetricPublishValidatorTest}。</p>
 */
class MetricExportManifestChecksumTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("crc32 对已知字节给出实测摘要（十六进制小写、无前导零、空文件为 0）")
    void crc32MatchesIndependentVectors(@TempDir Path dir) throws Exception {
        assertThat(MetricExportManifest.crc32(file(dir, "hello.jsonl", "hello"))).isEqualTo("3610a686");
        assertThat(MetricExportManifest.crc32(file(dir, "digits.jsonl", "123456789"))).isEqualTo("cbf43926");
        // 空文件摘要 "0" 是**合法摘要**（文件真实存在、内容为空），与"清单里根本没有 checksum 键"不同
        assertThat(MetricExportManifest.crc32(file(dir, "empty.jsonl", ""))).isEqualTo("0");
    }

    @Test
    @DisplayName("crc32 读满整个文件（远大于单次缓冲区）且输出恒匹配 ^[0-9a-f]{1,8}$")
    void crc32ReadsWholeFile(@TempDir Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("{\"product_id\":").append(i).append(",\"name\":\"商品-").append(i).append("\"}\n");
        }
        Path file = file(dir, "big.jsonl", sb.toString());
        String digest = MetricExportManifest.crc32(file);

        java.util.zip.CRC32 reference = new java.util.zip.CRC32();
        reference.update(Files.readAllBytes(file));
        assertThat(digest)
                .as("摘要必须覆盖全部字节，不能只算第一个缓冲区")
                .isEqualTo(Long.toHexString(reference.getValue()));
        assertThat(digest).matches("^[0-9a-f]{1,8}$");
    }

    @Test
    @DisplayName("crc32 对内容变化敏感：改一个字符摘要即变化")
    void crc32IsContentSensitive(@TempDir Path dir) throws Exception {
        assertThat(MetricExportManifest.crc32(file(dir, "a.jsonl", "{\"a\":1}\n{\"a\":2}\n")))
                .isEqualTo("195ac066");
        assertThat(MetricExportManifest.crc32(file(dir, "b.jsonl", "{\"a\":1}\n{\"a\":3}\n")))
                .isEqualTo("1898aa51");
    }

    @Test
    @DisplayName("清单缺 checksum 键 → 解析即失败（不静默放行未校验的制品）")
    void manifestWithoutChecksumIsRejected(@TempDir Path dir) throws Exception {
        Path manifestFile = dir.resolve("_export.json");
        Files.writeString(manifestFile, manifestJson(false), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> MetricExportManifest.read(manifestFile, mapper))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    @DisplayName("清单带 checksum 键 → 正常解析，且摘要原样保留（校验属主是发布侧，不是解析器）")
    void manifestWithChecksumIsParsed(@TempDir Path dir) throws Exception {
        Path manifestFile = dir.resolve("_export.json");
        Files.writeString(manifestFile, manifestJson(true), StandardCharsets.UTF_8);

        MetricExportManifest manifest = MetricExportManifest.read(manifestFile, mapper);
        assertThat(manifest.tables()).hasSize(1);
        assertThat(manifest.tables().get(0).checksum()).isEqualTo("195ac066");
    }

    private Path file(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** 一份最小可用清单；{@code withChecksum=false} 时**不写** checksum 键 */
    private String manifestJson(boolean withChecksum) {
        String checksumField = withChecksum ? ",\"checksum\":\"195ac066\"" : "";
        return "{\"snapshotId\":\"S20260901_21\",\"businessDate\":\"20260901\",\"dt\":\"20260901\","
                + "\"source\":\"spark-ads\",\"totalRows\":1,\"tables\":["
                + "{\"hiveTable\":\"dw_ads.ads_operation_overview\",\"mysqlTable\":\"ads_operation_overview_m\","
                + "\"rowCount\":1,\"columns\":[\"pv\"],\"hivePath\":\"file:/D:/wh/x/snapshot_id=S20260901_21\","
                + "\"exportFile\":\"D:/staging/S20260901_21/ads_operation_overview_m.jsonl\""
                + checksumField + "}]}";
    }
}
