package com.graduation.analytics.metric.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * R7-3：Spark 侧 {@code mxp} 作业导出的发布清单（{@code _export.json}）解析。
 *
 * <p>用 {@code readTree} 显式取值而不是反射绑定 record：清单字段增删会在发布前明确报错，
 * 不会因为"字段没绑上"静默变成 null（§16.4：缺证据=假成功）。</p>
 *
 * <p>S3-06 起每张表另带 {@code checksum}（导出制品**内容摘要**，设计 §12.5 L528/L529、
 * 指导书 §7 阶段 3 L151）。字段**必填**：清单里没有摘要说明制品未经内容验证，
 * 解析阶段就拒绝，而不是等到发布侧拿一个 null 当"没要求"。</p>
 */
public record MetricExportManifest(String snapshotId, String businessDate, String dt, String source,
                                   long totalRows, List<TableExport> tables) {

    /** 单表导出项（{@code checksum} = 该 JSONL 全部原始字节的 CRC32，见 {@link #crc32(Path)}） */
    public record TableExport(String hiveTable, String mysqlTable, long rowCount,
                              List<String> columns, String hivePath, String exportFile, String checksum) {
    }

    public TableExport table(String mysqlTable) {
        return tables.stream().filter(t -> t.mysqlTable().equals(mysqlTable)).findFirst().orElse(null);
    }

    /** 读取并校验必备字段；缺失即 {@link IOException}（不返回半成品清单） */
    public static MetricExportManifest read(Path manifestFile, ObjectMapper mapper) throws IOException {
        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("发布清单不存在: " + manifestFile);
        }
        JsonNode root = mapper.readTree(Files.readString(manifestFile));
        String snapshotId = required(root, "snapshotId");
        String businessDate = required(root, "businessDate");
        String dt = root.path("dt").asText(businessDate);
        String source = root.path("source").asText("");
        long totalRows = root.path("totalRows").asLong(-1L);
        JsonNode tablesNode = root.path("tables");
        if (!tablesNode.isArray() || tablesNode.isEmpty()) {
            throw new IOException("发布清单缺少 tables: " + manifestFile);
        }
        List<TableExport> tables = new ArrayList<>();
        for (JsonNode t : tablesNode) {
            List<String> columns = new ArrayList<>();
            for (JsonNode c : t.path("columns")) {
                columns.add(c.asText());
            }
            tables.add(new TableExport(
                    required(t, "hiveTable"),
                    required(t, "mysqlTable"),
                    t.path("rowCount").asLong(-1L),
                    List.copyOf(columns),
                    required(t, "hivePath"),
                    required(t, "exportFile"),
                    required(t, "checksum")));
        }
        return new MetricExportManifest(snapshotId, businessDate, dt, source, totalRows, List.copyOf(tables));
    }

    /**
     * 导出制品的**内容摘要**：CRC32 作用于文件全部原始字节，输出 {@code Long.toHexString} 形式
     * （小写十六进制、无前导零；空文件为 {@code "0"}）。
     *
     * <p>两侧必须逐字节同口径：Spark `mxp` 侧 {@code MetricExportJob.crc32} 与本方法各自读同一份
     * 文件，写法与 landing 侧 {@code ingestion-manifest.v1}（{@code IngestionService} 的
     * {@code Long.toHexString(CRC32.getValue())}）一致，避免同一制品出现两套摘要写法。</p>
     *
     * <p>本方法只回答"这份文件的摘要是什么"，**不**回答"它是否与清单一致"——后者是发布侧唯一
     * 所有者 {@link MetricPublishValidator} 的判定（{@code MP_EXPORT_CHECKSUM}）。</p>
     *
     * @throws IOException 文件不存在或不可读（缺失制品不该得到一个"合法摘要"）
     */
    public static String crc32(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("导出制品不存在，无法计算摘要: " + file);
        }
        CRC32 crc = new CRC32();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n = in.read(buf);
            while (n > 0) {
                crc.update(buf, 0, n);
                n = in.read(buf);
            }
        }
        return Long.toHexString(crc.getValue());
    }

    private static String required(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new IOException("发布清单缺少字段 " + field);
        }
        return value.asText();
    }
}
