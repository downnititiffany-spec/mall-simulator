package com.graduation.analytics.metric.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * R7-3：Spark 侧 {@code mxp} 作业导出的发布清单（{@code _export.json}）解析。
 *
 * <p>用 {@code readTree} 显式取值而不是反射绑定 record：清单字段增删会在发布前明确报错，
 * 不会因为"字段没绑上"静默变成 null（§16.4：缺证据=假成功）。</p>
 */
public record MetricExportManifest(String snapshotId, String businessDate, String dt, String source,
                                   long totalRows, List<TableExport> tables) {

    /** 单表导出项 */
    public record TableExport(String hiveTable, String mysqlTable, long rowCount,
                              List<String> columns, String hivePath, String exportFile) {
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
                    required(t, "exportFile")));
        }
        return new MetricExportManifest(snapshotId, businessDate, dt, source, totalRows, List.copyOf(tables));
    }

    private static String required(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new IOException("发布清单缺少字段 " + field);
        }
        return value.asText();
    }
}
