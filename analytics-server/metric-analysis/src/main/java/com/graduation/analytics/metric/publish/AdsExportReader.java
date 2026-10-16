package com.graduation.analytics.metric.publish;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.metric.MetricAdsCatalog;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R7-3：读取 Spark `mxp` 导出的 JSONL 行（HiveAdsReader 的落地形态）。
 *
 * <p>为什么不在 Java 里直连 Hive：本地/单机环境的 Hive 只有 Derby 元数据库与 parquet 文件，
 * 没有 HiveServer2 可连；因此"读 Hive 正式 ADS"由 Spark 作业完成（它读的就是发布后的正式分区），
 * Java 侧只读导出文件。**读到的行数全部来自文件真实回读**，不做估算。</p>
 *
 * <p>数值一律按文本精确转 {@link BigDecimal}（金额/比率不允许经 double 丢精度），
 * 整型转 {@link Long}；列名必须落在 {@link MetricAdsCatalog} 白名单内，否则拒绝该行。</p>
 *
 * <p><b>表形（schema）双向严格</b>（S3-09，设计 §12.5 L529「表形…验证」）：
 * 非白名单列 → 拒绝；声明列里缺任何一列 → 也拒绝。**不补 null**：
 * 「键缺失」与「键在、值为 null」是两件事 —— 前者说明导出的不是声明的表形（例如导出侧版本落后
 * 一列），后者是真实的"当日没有该指标值"。旧实现把缺列补齐成 null，会让残缺导出静默发布成功。</p>
 */
@Component
public class AdsExportReader {

    /** 只用于 JSONL：浮点走 BigDecimal（精确）、整数走 Long */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);

    /**
     * 读取一张表导出的全部行。
     *
     * @param exportFile  清单里记录的导出文件（JSONL 目录）
     * @param mysqlTable  目标宽表名（白名单校验）
     * @param columns     清单里记录的列（必须与白名单一致，防止"清单被改过"）
     */
    public List<Map<String, Object>> readTable(Path exportFile, String mysqlTable, List<String> columns)
            throws IOException {
        MetricAdsCatalog spec = MetricAdsCatalog.require(mysqlTable);
        if (!new LinkedHashSet<>(spec.columns()).equals(new LinkedHashSet<>(columns))) {
            throw new IOException("导出清单列与指标库白名单不一致: " + mysqlTable
                    + " 清单=" + columns + " 白名单=" + spec.columns());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.exists(exportFile)) {
            throw new IOException("导出文件不存在: " + exportFile);
        }
        try (BufferedReader reader = Files.newBufferedReader(exportFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = toRow(line, spec, exportFile, lineNo);
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, Object> toRow(String json, MetricAdsCatalog spec, Path file, int lineNo) throws IOException {
        Map<?, ?> parsed;
        try {
            parsed = MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IOException("导出文件第 " + lineNo + " 行不是合法 JSON（" + file + "）: " + e.getMessage(), e);
        }
        Set<String> unknown = new LinkedHashSet<>();
        Map<String, Object> row = new LinkedHashMap<>();
        for (Object key : parsed.keySet()) {
            String column = String.valueOf(key);
            if (!spec.columns().contains(column)) {
                unknown.add(column);
                continue;
            }
            row.put(column, normalize(parsed.get(key)));
        }
        if (!unknown.isEmpty()) {
            throw new IOException("导出文件第 " + lineNo + " 行含非白名单列（" + file + "）: " + unknown);
        }
        // 表形校验（设计 §12.5 L529「表形…验证」、指导书 §7 阶段三第 4 条「核 schema」）：
        // 声明列必须**逐列出现在该行**。注意与"值为 null"的区别 —— 键在、值为 null 是真实语义
        // （当日没有该指标值），键不在则说明导出侧写的不是声明的那张表形。
        // S3-09 修复：旧实现把缺列 putIfAbsent(null) 补齐，于是版本落后的导出（少一列）会被
        // 静默当成"值全为空"写进指标库，且没有任何 check 会点名它（列集被补齐后各行完全一致，
        // MP_ROW_SHAPE_CONSISTENT 看不出差别）——"看起来发布成功"的假数据。
        Set<String> missing = new LinkedHashSet<>(spec.columns());
        missing.removeAll(row.keySet());
        if (!missing.isEmpty()) {
            throw new IOException("导出文件第 " + lineNo + " 行缺列（" + file + "）: " + missing
                    + "；该表声明列集 = " + spec.columns());
        }
        return row;
    }

    private Object normalize(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal || value instanceof Long || value instanceof String
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof Map || value instanceof List) {
            throw new IOException("数值列出现嵌套结构，导出内容非法: " + value.getClass().getSimpleName());
        }
        return String.valueOf(value);
    }
}
