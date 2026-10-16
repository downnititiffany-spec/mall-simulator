package com.graduation.analytics.ai;

import com.graduation.analytics.ai.sql.AiScope;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>DDL 为唯一事实来源：platform-app/src/main/resources/db/metric/V*.sql（**全部**迁移，
 * 含后续 {@code ALTER TABLE ... ADD COLUMN} 的加性迁移；只解析 {@code CREATE TABLE} 会漏掉加列，
 * 导致"真实列已存在但语义层没写"这类漂移在守卫下静默通过，见 {@code 迁移解析覆盖后续ALTER加列}）。</p>
 */
class AiSqlDriftTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+([a-z_][a-z0-9_]*)\\s*\\((.*?)\\)\\s*ENGINE", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 加性迁移：{@code ALTER TABLE t ... ADD COLUMN c ...;}（列定义细节不解析，只要列名） */
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "ALTER\\s+TABLE\\s+`?([a-z_][a-z0-9_]*)`?\\s+(.*?);", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ADD\\s+COLUMN\\s+`?([a-z_][a-z0-9_]*)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_TABLE = Pattern.compile(
            "(?:FROM|JOIN)\\s+([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_LIST = Pattern.compile(
            "SELECT\\s+(.*?)\\s+FROM\\s", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FIELD_ARG = Pattern.compile(
            "FIELD\\s*\\(\\s*([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");
    /** 迁移文件名 {@code V<n>__描述.sql}：版本号即解析顺序的**判定依据**（非字典序，见 {@code 迁移按版本序解析而非字典序}） */
    private static final Pattern MIGRATION_NAME = Pattern.compile("V(\\d+)__.*\\.sql", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NOT_COLUMNS = Set.of(
            "select", "from", "where", "and", "or", "order", "by", "desc", "asc", "limit", "group",
            "as", "distinct", "max", "min", "sum", "avg", "count", "date_sub", "curdate", "interval",
            "day", "field", "case", "when", "then", "else", "end", "null", "true", "false");

    /**
     * 解析全部 metric 迁移 DDL（V1/V2 大盘/趋势/漏斗 + V3 R7 商品/画像/质量 + 后续加性 ALTER）：
     * 表 → 列集合。
     *
     * <p>顺序无关：先把所有 {@code CREATE TABLE} 建好表集合，再按文件顺序应用
     * {@code ALTER TABLE ... ADD COLUMN}。只解析 CREATE 会让"加性迁移新增的列"对守卫不可见
     * （2026-09 S3-02 实测：V5 已给 {@code ads_sale_trend_m} 加了 {@code net_sale_amount}，
     * 语义层没写该列时本测试**仍然全绿**）。</p>
     */
    private static Map<String, Set<String>> ddlTables() throws IOException {
        Path dir = resolveDdlDir();
        StringBuilder all = new StringBuilder();
        for (Path f : migrationFilesOrdered(dir)) {
            all.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
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
        // 加性迁移：只处理已建表的 ADD COLUMN（表不存在时说明 CREATE 解析漏了表，交由上游用例报错）
        Matcher alter = ALTER_TABLE.matcher(sql);
        while (alter.find()) {
            Set<String> cols = tables.get(alter.group(1).toLowerCase());
            if (cols == null) {
                continue;
            }
            Matcher add = ADD_COLUMN.matcher(alter.group(2));
            while (add.find()) {
                cols.add(add.group(1).toLowerCase());
            }
        }
        return tables;
    }

    /**
     * 守卫牙齿自检：加性 ALTER 迁移的列必须真的被解析进来。
     *
     * <p>没有这一条，"解析器退回只读 CREATE"这种退化不会被任何用例发现 ——
     * 上面那条漂移比对会因为"真实列集合偏小"而变得更宽松，静默通过。</p>
     */
    @Test
    void 迁移解析覆盖后续ALTER加列() throws IOException {
        Map<String, Set<String>> ddl = ddlTables();
        assertTrue(ddl.getOrDefault("ads_sale_trend_m", Set.of()).contains("net_sale_amount"),
                "加性迁移列未被解析：ads_sale_trend_m.net_sale_amount（V5 ALTER）");
        assertTrue(ddl.getOrDefault("ads_user_profile_m", Set.of()).containsAll(
                        List.of("r_days", "f_count", "m_amount", "period_start", "period_end")),
                "加性迁移列未被解析：ads_user_profile_m 的 RFM 原值列（V4 ALTER）");
    }

    /**
     * 守卫牙齿自检（S3-29 补 backlog「迁移文件名按字典序排序」残留）：迁移必须按**版本序**解析，不是字典序。
     *
     * <p>字典序下 {@code V10__…} 会排在 {@code V2__…} 之前，而真实目录里 V10 已存在。当前所有
     * {@code ALTER} 都只往列集合里加列，错序对 Set 语义无害，但一旦后续迁移出现「依赖前序/按序覆盖」
     * 的语义，错序就会**静默**改变判定依据 ⇒ 在此把顺序本身钉住（顺序是判定依据的一部分）。</p>
     */
    @Test
    void 迁移按版本序解析而非字典序() throws IOException {
        // ① 顺序函数本身：V2 必须早于 V10（字典序会判反）
        assertTrue(migrationVersion(Path.of("V2__a.sql")) < migrationVersion(Path.of("V10__b.sql")),
                "版本序错误：V2 应排在 V10 之前（字典序恰好判反）");
        // ② 真实目录：解析顺序的版本号严格升序（字典序在含 V10 的目录上必然违反）
        List<Integer> versions = new ArrayList<>();
        for (Path f : migrationFilesOrdered(resolveDdlDir())) {
            versions.add(migrationVersion(f));
        }
        assertFalse(versions.isEmpty(), "迁移目录未解析到任何 .sql 文件");
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(versions.get(i - 1) < versions.get(i),
                    "迁移解析顺序不是版本序（严格升序被破坏）：" + versions);
        }
        // ③ 反证（牙齿自检）：真实目录上「版本序 ≠ 字典序」确实成立，否则本用例没有牙齿
        List<Path> lexicographic = new ArrayList<>();
        try (var files = Files.list(resolveDdlDir())) {
            files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().forEach(lexicographic::add);
        }
        assertFalse(lexicographic.equals(migrationFilesOrdered(resolveDdlDir())),
                "字典序与版本序在真实目录上已一致 ⇒ 本用例失去牙齿，请确认目录中是否仍有 V10+ 迁移");
    }

    /**
     * 迁移文件名版本号（{@code V<n>__…}）⇒ {@code n}。
     *
     * <p>不符合命名规范的文件给 {@link Integer#MAX_VALUE}：排到最后且**不**静默参与顺序判定，
     * 由 {@code 迁移按版本序解析而非字典序} 的严格升序断言把异常文件暴露出来。</p>
     */
    static int migrationVersion(Path f) {
        Matcher m = MIGRATION_NAME.matcher(f.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /**
     * 迁移目录下的 {@code .sql} 文件，按**版本序**返回（S3-29 补 backlog 残留）。
     *
     * <p>此前用 {@code Path.sorted()}（**字典序**）：{@code V10__…} 会排在 {@code V2__…} 之前，
     * 而真实目录里 V10 已存在。当前 {@code ALTER} 只往列集合加列 ⇒ 对 Set 语义无害，但顺序本身
     * 是判定依据的一部分（后续若出现依赖前序/按序覆盖的迁移，错序会静默改变结论），故收归此处。</p>
     */
    static List<Path> migrationFilesOrdered(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(AiSqlDriftTest::migrationVersion)
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * ADS 物化表迁移目录。
     *
     * <p>DEF-16：此前是"两个候选路径依次试"（{@code ../platform-app/...} 与
     * {@code analytics-server/platform-app/...}），本质是把手写的"我在哪个工作目录"判断
     * 复制了一份，且只在两种已知 CWD 下成立。定位收归 {@link RepoRoot}（唯一所有者），
     * 目录不存在时由它直接失败，不会退化成静默跳过。</p>
     */
    private static Path resolveDdlDir() {
        return RepoRoot.path("analytics-server/platform-app/src/main/resources/db/metric");
    }

    /**
     * R8-2 契约 §2.2：少样本里的 {@code snapshot_id} / {@code dt} 已改为**参数化字面量**，
     * 因此漂移比对必须传一份 scope，模板才会被实例化为真正可执行的 SQL。
     */
    private static final AiScope SCOPE =
            AiScope.of("S20260901_24", "v2", java.time.LocalDate.of(2026, 9, 4));

    private static List<String> allAiSql() {
        List<String> sqls = new ArrayList<>();
        for (String q : List.of("最近一天的销售额和订单数是多少", "转化漏斗各阶段人数", "最近的热销商品排行",
                "今天的大盘 GMV 和退款率", "各商品转化率多少")) {
            RuleBasedSqlFallback.Fallback fb = RuleBasedSqlFallback.resolve(q, List.of(), SCOPE);
            assertTrue(fb.ok(), "规则回退未生成 SQL（R8-2 起必须带 scope 字面量）: " + q);
            sqls.add(fb.sql());
        }
        for (Map.Entry<String, List<String>> e : SemanticCatalog.FEW_SHOTS.entrySet()) {
            for (String shot : e.getValue()) {
                int idx = shot.indexOf("答：");
                assertTrue(idx > 0, "少样本格式错误（缺少 答：）: " + shot);
                sqls.add(SemanticCatalog.parameterize(shot.substring(idx + 2), SCOPE));
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
     *
     * <p>R8-2 契约 §2.2 修订：pin 的方式从 {@code snapshot_id = (SELECT MAX(snapshot_id) ...)}
     * 子查询改为**参数化字面量** {@code snapshot_id = 'S...'}（子查询已整体禁用），
     * 因此这里同时要求「字面量钉住 + 少样本不含任何子查询」。</p>
     */
    @Test
    void AI查询必须锁定单一快照() {
        for (String sql : allAiSql()) {
            assertTrue(sql.contains("snapshot_id = '"),
                    "AI SQL 未用字面量锁定快照，存在跨快照串数风险: " + sql);
            assertTrue(sql.contains("snapshot_id = '" + SCOPE.snapshotId() + "'"),
                    "AI SQL 未锁定 ACTIVE 快照 " + SCOPE.snapshotId() + ": " + sql);
            assertFalse(sql.toLowerCase().contains("select max("),
                    "R8-2 起 AI SQL 禁止 MAX 子查询（日期/快照必须字面量参数化）: " + sql);
            assertFalse(sql.toLowerCase().contains("(select"),
                    "R8-2 起 AI SQL 禁止任何子查询: " + sql);
        }
        for (String table : SemanticCatalog.TABLES.keySet()) {
            assertTrue(SemanticCatalog.TABLES.get(table).containsKey("snapshot_id"),
                    "语义层未向模型暴露 snapshot_id，模型无法自行 pin 快照: " + table);
        }
    }

    /**
     * R8-2 追加：规则回退与少样本必须带真实业务日的 dt 字面量区间（不能只靠 MAX(dt) 子查询）。
     *
     * <p>2026-09-11 真机事故回归：此前断言写的是 ISO {@code yyyy-MM-dd}，与 ADS 落库的紧凑
     * {@code yyyyMMdd} 不一致 → 生成的 SQL 在真库上字符串比较恒 false、**静默 0 行**。
     * 现在起点/终点都从 {@link AiScope#DT_FORMAT} 派生，并显式禁止 ISO 形态字面量。</p>
     */
    @Test
    void AI查询必须带参数化日期区间() {
        String day = AiScope.dt(SCOPE.businessDate());
        for (String sql : allAiSql()) {
            assertTrue(sql.contains("dt >= '" + SCOPE.dtFrom() + "'")
                            || sql.contains("dt >= '" + AiScope.dt(SCOPE.businessDate().minusDays(6)) + "'")
                            || sql.contains("dt >= '" + day + "'"),
                    "AI SQL 缺少 dt 起始字面量（紧凑格式 " + SCOPE.dtFrom() + "）: " + sql);
            assertTrue(sql.contains("dt <= '" + day + "'"),
                    "AI SQL 缺少 dt 结束字面量（必须钉住 ACTIVE 业务日的紧凑格式 " + day + "）: " + sql);
            assertFalse(sql.matches("(?s).*dt\\s*[<>=]+\\s*'\\d{4}-\\d{2}-\\d{2}'.*"),
                    "AI SQL 不得使用 ISO 日期字面量（与 ADS 落库 yyyyMMdd 不符 → 静默 0 行）: " + sql);
            assertFalse(sql.toLowerCase().contains("curdate()") || sql.toLowerCase().contains("date_sub("),
                    "AI SQL 不得使用当前时间函数（会绕过允许日期区间）: " + sql);
        }
    }
}
