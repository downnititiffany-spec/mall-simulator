package com.graduation.analytics.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7-4 反熵守卫：AI 语义层 / 规则回退 SQL 的表列必须与已发布 ADS 物化表 DDL 完全对齐。
 *
 * <p>背景：规则回退模板曾长期引用 {@code ads_sale_trend_m.net_sale_amount} 与
 * {@code ads_operation_overview_m.gmv} 两个不存在的列，运行时表现为
 * {@code Unknown column 'net_sale_amount' in 'field list'}（query status=FAILED、0 行）。
 * 这类漂移不会让编译或普通单测变红，只有真库查询才暴露，故在此常驻比对 DDL。</p>
 *
 * <p>DDL 为唯一事实来源：platform-app/src/main/resources/db/metric/V2__metric_ads_materialized.sql。</p>
 */
class AiSqlDriftTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+([a-z_][a-z0-9_]*)\\s*\\((.*?)\\)\\s*ENGINE", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FROM_TABLE = Pattern.compile(
            "(?:FROM|JOIN)\\s+([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_LIST = Pattern.compile(
            "SELECT\\s+(.*?)\\s+FROM\\s", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FIELD_ARG = Pattern.compile(
            "FIELD\\s*\\(\\s*([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Set<String> NOT_COLUMNS = Set.of(
            "select", "from", "where", "and", "or", "order", "by", "desc", "asc", "limit", "group",
            "as", "distinct", "max", "min", "sum", "avg", "count", "date_sub", "curdate", "interval",
            "day", "field", "case", "when", "then", "else", "end", "null", "true", "false");

    /** 解析全部 metric 迁移 DDL（V2 大盘/趋势/漏斗 + V3 R7 商品/画像/质量）：表 → 列集合 */
    private static Map<String, Set<String>> ddlTables() throws IOException {
        Path dir = resolveDdlDir();
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList()) {
                all.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
            }
        }
        String sql = all.toString();
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        Matcher m = CREATE_TABLE.matcher(sql);
        while (m.find()) {
            Set<String> cols = new LinkedHashSet<>();
            for (String rawLine : m.group(2).split("\n")) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("--")) {
                    continue;
                }
                String upper = line.toUpperCase();
                if (upper.startsWith("PRIMARY KEY") || upper.startsWith("KEY") || upper.startsWith("UNIQUE")
                        || upper.startsWith("CONSTRAINT") || upper.startsWith("INDEX")) {
                    continue;
                }
                Matcher col = Pattern.compile("^([a-z_][a-z0-9_]*)").matcher(line);
                if (col.find()) {
                    cols.add(col.group(1).toLowerCase());
                }
            }
            tables.put(m.group(1).toLowerCase(), cols);
        }
        return tables;
    }

    private static Path resolveDdlDir() {
        List<Path> candidates = List.of(
                Path.of("..", "platform-app", "src", "main", "resources", "db", "metric"),
                Path.of("analytics-server", "platform-app", "src", "main", "resources", "db", "metric"));
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("找不到 ADS 物化表迁移目录（db/metric），无法执行漂移守卫");
    }

    private static List<String> allAiSql() {
        List<String> sqls = new ArrayList<>();
        for (String q : List.of("最近一天的销售额和订单数是多少", "转化漏斗各阶段人数", "最近的热销商品排行",
                "今天的大盘 GMV 和退款率", "各商品转化率多少")) {
            sqls.add(RuleBasedSqlFallback.resolve(q, List.of()).sql());
        }
        for (Map.Entry<String, List<String>> e : SemanticCatalog.FEW_SHOTS.entrySet()) {
            for (String shot : e.getValue()) {
                int idx = shot.indexOf("答：");
                assertTrue(idx > 0, "少样本格式错误（缺少 答：）: " + shot);
                sqls.add(shot.substring(idx + 2));
            }
        }
        return sqls;
    }

    private static String stripLiterals(String sql) {
        return sql.replaceAll("'[^']*'", "''");
    }

    @Test
    void 语义层字段与DDL完全一致() throws IOException {
        Map<String, Set<String>> ddl = ddlTables();
        assertFalse(ddl.isEmpty(), "DDL 未解析出任何表");
        for (Map.Entry<String, Map<String, String>> e : SemanticCatalog.TABLES.entrySet()) {
            String table = e.getKey().toLowerCase();
            Set<String> realCols = ddl.get(table);
            assertTrue(realCols != null, "语义层声明了 DDL 中不存在的表: " + table);
            for (String col : e.getValue().keySet()) {
                assertTrue(realCols.contains(col.toLowerCase()),
                        "语义层字段漂移：" + table + "." + col + " 在真实物化表中不存在（真实列=" + realCols + "）");
            }
            // 白名单表必须覆盖除发布审计列 snapshot_id 之外的全部真实列，避免 Prompt 缺列导致模型编列
            for (String real : realCols) {
                if ("snapshot_id".equals(real)) {
                    continue;
                }
                assertTrue(e.getValue().keySet().stream().anyMatch(c -> c.equalsIgnoreCase(real)),
                        "语义层缺少真实列：" + table + "." + real + "（Prompt 不完整会诱发模型编造列名）");
            }
        }
    }

    @Test
    void 规则回退与少样本SQL只引用真实表和真实列() throws IOException {
        Map<String, Set<String>> ddl = ddlTables();
        for (String sql : allAiSql()) {
            String clean = stripLiterals(sql);
            Set<String> usedTables = new LinkedHashSet<>();
            Matcher t = FROM_TABLE.matcher(clean);
            while (t.find()) {
                usedTables.add(t.group(1).toLowerCase());
            }
            assertFalse(usedTables.isEmpty(), "SQL 未解析出表: " + sql);
            Set<String> allowedCols = new LinkedHashSet<>();
            for (String table : usedTables) {
                Set<String> cols = ddl.get(table);
                assertTrue(cols != null, "SQL 引用了不存在的表 " + table + " : " + sql);
                allowedCols.addAll(cols);
            }
            Matcher s = SELECT_LIST.matcher(clean);
            while (s.find()) {
                for (String item : s.group(1).split(",")) {
                    List<String> idents = new ArrayList<>();
                    Matcher id = IDENT.matcher(item.toLowerCase());
                    while (id.find()) {
                        idents.add(id.group());
                    }
                    if (idents.isEmpty()) {
                        continue;
                    }
                    String col = idents.get(idents.size() - 1);
                    if (NOT_COLUMNS.contains(col)) {
                        continue;
                    }
                    assertTrue(allowedCols.contains(col),
                            "SQL 选择列漂移：列 " + col + " 不属于 " + usedTables + "（真实列=" + allowedCols + "）: " + sql);
                }
            }
            Matcher f = FIELD_ARG.matcher(clean);
            while (f.find()) {
                String col = f.group(1).toLowerCase();
                assertTrue(allowedCols.contains(col), "FIELD() 参数列漂移: " + col + " : " + sql);
            }
        }
    }

    @Test
    void 语义层不声明非ADS表() throws IOException {
        Map<String, Set<String>> ddl = ddlTables();
        for (String table : SemanticCatalog.TABLES.keySet()) {
            assertTrue(table.startsWith("ads_"), "AI 白名单只允许 ads_* 已发布表，出现: " + table);
            assertTrue(ddl.containsKey(table.toLowerCase()), "AI 白名单表未在物化 DDL 中: " + table);
        }
    }

    @Test
    void 少样本落在白名单表上() {
        for (Map.Entry<String, List<String>> e : SemanticCatalog.FEW_SHOTS.entrySet()) {
            assertTrue(SemanticCatalog.TABLES.containsKey(e.getKey()),
                    "少样本挂在非白名单表上: " + e.getKey());
            for (String shot : e.getValue()) {
                assertTrue(shot.contains(e.getKey()), "少样本未查询所属表 " + e.getKey() + " : " + shot);
            }
        }
    }

    /**
     * 跨快照串数守卫：ADS 物化表按快照保留历史，同一 dt 会同时存在归档快照与生效快照的行
     * （实测 ads_hot_product_m 等 8 张表在 dt=20260901 上各有 S20260901_23 与 S20260901_24 两套）。
     * AI 查询（尤其带 LIMIT 1 的）若不 pin 快照，就会把两个快照混在一起，甚至返回归档快照的旧口径值。
     */
    @Test
    void AI查询必须锁定单一快照() {
        for (String sql : allAiSql()) {
            assertTrue(sql.contains("snapshot_id = (SELECT MAX(snapshot_id) FROM "),
                    "AI SQL 未锁定最新已发布快照，存在跨快照串数风险: " + sql);
        }
        for (String table : SemanticCatalog.TABLES.keySet()) {
            assertTrue(SemanticCatalog.TABLES.get(table).containsKey("snapshot_id"),
                    "语义层未向模型暴露 snapshot_id，模型无法自行 pin 快照: " + table);
        }
    }
}
