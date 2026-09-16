package com.graduation.analytics.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.graduation.analytics.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * S3-08 反熵守卫：**收藏/加购「次数」口径在四处属主之间必须一致**。
 *
 * <p>设计标尺（逐字）：设计 §11.2 L425「收藏/加购 | <b>对应行为事件数</b>，用户转化时另算去重用户数 | 行为」
 * ——要求的是**事件条数**，并把「去重用户数」明确列为**另一件事**；字典
 * {@code docs/contracts/metric-dictionary.md:21/:22} 登记 {@code fav_cnt = count(favorite 事件)}、
 * {@code cart_add_cnt = count(cart_add 事件)}，源表都是 {@code dwd_user_behavior_detail}。</p>
 *
 * <p>四处属主（任一漂移都不会被编译或普通单测抓到）：</p>
 * <ol>
 *   <li><b>口径字典</b> {@code docs/contracts/metric-dictionary.md}：公式文本 + 粒度 + 源表；</li>
 *   <li><b>指标字典配置表</b> {@code analytics_meta.metric_definition} 种子行（{@code db/meta/V*.sql}）
 *       —— 发布侧 {@code MP_METRIC_DICT_VERSION} 只认这里的码与 {@code definition_version}；
 *       码不在字典里 ⇒ 发布整链失败（不是"少一个指标"而是"发布不出去"）；</li>
 *   <li><b>发布映射</b> {@code MetricPublisher.OVERVIEW_TO_METRIC}：概览列 → 指标码搬运；</li>
 *   <li><b>ADS 载体</b>：{@code MetricAdsSpec}（列真源）+ {@code MetricAdsCatalog}（写入白名单）+
 *       {@code db/metric/V*.sql}（加性迁移）三处列名与列序。</li>
 * </ol>
 *
 * <p><b>本守卫不修改任何一处取值</b>：只做只读比对；收拢属主属反熵治理动作，须显式裁决后另行执行。</p>
 *
 * <p>判定顺序：①字典两行齐备且源表 = {@code dwd_user_behavior_detail} → ②字典种子恰一行、版本 {@code v1}
 * → ③发布映射同名搬运 → ④ADS 三处列序一致且加性迁移唯一 → ⑤Spark 公式是**事件条数**（{@code COUNT(CASE …)}
 * 而非 {@code COUNT(DISTINCT …)}）。</p>
 */
class AdsFavCartCountDriftTest {

    private static final Path DICTIONARY = RepoRoot.path("docs/contracts/metric-dictionary.md");
    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");
    private static final Path METRIC_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/metric");
    private static final Path ADS_SQL =
            RepoRoot.path("spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala");
    private static final Path ADS_SPEC =
            RepoRoot.path("spark-jobs/src/main/scala/com/graduation/analytics/metric/MetricAdsSpec.scala");
    private static final Path ADS_CATALOG = RepoRoot.path(
            "analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/MetricAdsCatalog.java");
    private static final Path PUBLISHER = RepoRoot.path("analytics-server/metric-analysis/src/main/java/"
            + "com/graduation/analytics/metric/publish/MetricPublisher.java");

    /** 待守护的两枚「次数」指标码（设计 §11.2 L425 同一条目，字典相邻两行） */
    private static final List<String> CODES = List.of("fav_cnt", "cart_add_cnt");

    /** 事件条数公式：`COUNT(CASE WHEN behavior_type = '<type>' THEN 1 END) AS <code>` */
    private static final Pattern EVENT_COUNT_FORMULA = Pattern.compile(
            "COUNT\\(\\s*CASE WHEN behavior_type = '([a-z_]+)' THEN 1 END\\s*\\)\\s+AS\\s+([a-z_]+)",
            Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------ 解析

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 字典里某个指标码的整行（markdown 表格行） */
    private static String dictionaryRow(String code) throws IOException {
        List<String> hits = new ArrayList<>();
        for (String line : Files.readAllLines(DICTIONARY, StandardCharsets.UTF_8)) {
            String stripped = line.strip();
            if (stripped.startsWith("| " + code + " ")) {
                hits.add(stripped);
            }
        }
        assertThat(hits)
                .as("口径字典 %s 里 %s 应恰有 1 行（多于一行说明字典里有两个属主）",
                        DICTIONARY.getFileName(), code)
                .hasSize(1);
        return hits.get(0);
    }

    /** markdown 表格行的单元格（跳过空的边框单元格） */
    private static List<String> cells(String row) {
        List<String> out = new ArrayList<>();
        for (String part : row.split("\\|")) {
            String cell = part.strip();
            if (!cell.isEmpty()) {
                out.add(cell);
            }
        }
        return out;
    }

    private static List<Path> sqlMigrations(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /** 指标字典种子行（跨 db/meta 全部迁移，**必须恰有一行**：多于一行即两个属主） */
    private record Seed(Path file, String line) {
    }

    private static boolean looksLikeSeedRow(String line, String code) {
        String stripped = line.strip();
        return stripped.contains("SELECT '" + code + "'") || stripped.startsWith("('" + code + "'");
    }

    private static Seed seedOf(String code) throws IOException {
        List<Seed> hits = new ArrayList<>();
        for (Path file : sqlMigrations(META_DIR)) {
            String text = read(file);
            if (!text.contains("INSERT INTO metric_definition")) {
                continue;
            }
            for (String line : text.split("\\R")) {
                if (looksLikeSeedRow(line, code)) {
                    hits.add(new Seed(file, line.strip()));
                }
            }
        }
        assertThat(hits)
                .as("指标字典（analytics_meta.metric_definition）里 %s 应恰有 1 行种子，实际 %s", code, hits)
                .hasSize(1);
        return hits.get(0);
    }

    /** 承载 fav_cnt/cart_add_cnt 加性补列的 metric 迁移（**必须恰有一个文件**） */
    private static Path carrierMigration() throws IOException {
        List<Path> hits = new ArrayList<>();
        for (Path file : sqlMigrations(METRIC_DIR)) {
            if (read(file).contains("fav_cnt")) {
                hits.add(file);
            }
        }
        assertThat(hits)
                .as("db/metric 下应恰有 1 个迁移为 ads_operation_overview_m 补 fav_cnt/cart_add_cnt，实际 %s", hits)
                .hasSize(1);
        return hits.get(0);
    }

    /** 只取 `def operationOverview` 这一段（避免误抓文件里其它行为计数片段） */
    private static String operationOverviewBlock() throws IOException {
        String text = read(ADS_SQL);
        int start = text.indexOf("def operationOverview(");
        assertThat(start).as("AdsSql 未找到 def operationOverview").isGreaterThanOrEqualTo(0);
        int next = text.indexOf("\n  def ", start + 1);
        return next < 0 ? text.substring(start) : text.substring(start, next);
    }

    /** Java 侧某个清单里 `ads_operation_overview_m` 之后的 `List.of(...)` 列名 */
    private static List<String> javaOverviewColumns(Path file) throws IOException {
        String text = read(file);
        int at = text.indexOf("\"ads_operation_overview_m\"");
        assertThat(at).as("%s 未找到 ads_operation_overview_m", file.getFileName()).isGreaterThanOrEqualTo(0);
        int open = text.indexOf("List.of(", at);
        int close = text.indexOf(")", open);
        assertThat(open).as("%s 未找到 List.of(", file.getFileName()).isGreaterThanOrEqualTo(0);
        assertThat(close).as("%s 的 List.of( 未闭合", file.getFileName()).isGreaterThan(open);
        return quotedLowerNames(text.substring(open, close));
    }

    /** Scala 侧概览列（MetricAdsSpec 的 `Seq(...)`） */
    private static List<String> specOverviewColumns() throws IOException {
        String text = read(ADS_SPEC);
        int at = text.indexOf("\"ads_operation_overview_m\"");
        assertThat(at).isGreaterThanOrEqualTo(0);
        int open = text.indexOf("Seq(", at);
        int close = text.indexOf("))", open);
        assertThat(open).isGreaterThanOrEqualTo(0);
        assertThat(close).isGreaterThan(open);
        return quotedLowerNames(text.substring(open, close));
    }

    private static List<String> quotedLowerNames(String body) {
        Matcher m = Pattern.compile("\"([a-z_]+)\"").matcher(body);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        assertThat(out).as("列名解析结果为空：%s", body).isNotEmpty();
        return out;
    }

    // ------------------------------------------------------------ 断言

    @Test
    void 字典登记两枚次数码且源表为DWD行为明细() throws IOException {
        for (String code : CODES) {
            List<String> cells = cells(dictionaryRow(code));
            assertThat(cells).as("%s 字典行列数不足：%s", code, cells).hasSizeGreaterThanOrEqualTo(6);
            assertThat(cells.get(1)).as("%s 的名称列", code).contains("次数");
            assertThat(cells.get(2).toLowerCase(Locale.ROOT))
                    .as("%s 的公式必须是对应行为的事件数（设计 §11.2 L425「对应行为事件数」）", code)
                    .contains("count(");
            assertThat(cells.get(3)).as("%s 的粒度", code).isEqualTo("日");
            assertThat(cells.get(5))
                    .as("%s 的源表必须是字典登记的 dwd_user_behavior_detail（本层按字典源表直取）", code)
                    .isEqualTo("dwd_user_behavior_detail");
        }
    }

    @Test
    void 指标字典种子恰一行且版本为v1() throws IOException {
        for (String code : CODES) {
            Seed seed = seedOf(code);
            assertThat(seed.line())
                    .as("%s 的字典种子行末尾应是定义版本 'v1'（发布侧按它写 metric_value.definition_version）：%s",
                            code, seed.line())
                    .containsPattern("'v1'\\s*(FROM DUAL)?\\s*$");
        }
    }

    @Test
    void 发布映射把两列搬成同名指标码() throws IOException {
        String text = read(PUBLISHER);
        for (String code : CODES) {
            long refs = Pattern.compile("OVERVIEW_TO_METRIC\\.put\\(\"" + code + "\",\\s*\"" + code + "\"\\)")
                    .matcher(text).results().count();
            assertThat(refs)
                    .as("MetricPublisher 应恰有一处把概览列 %s 映射成同名指标码（码不在字典里会直接让发布失败）", code)
                    .isEqualTo(1);
        }
    }

    @Test
    void ADS三处列名与列序一致且加性迁移唯一() throws IOException {
        List<String> spec = specOverviewColumns();
        List<String> catalog = javaOverviewColumns(ADS_CATALOG);

        assertThat(spec).as("MetricAdsSpec 概览列应与 MetricAdsCatalog 逐列同序").isEqualTo(catalog);
        assertThat(spec.subList(spec.size() - CODES.size(), spec.size()))
                .as("两枚码必须是概览表的**末尾两列**（历史行按位置读出 null，追加列才能兼容存量数据）")
                .isEqualTo(CODES);

        Path carrier = carrierMigration();
        String migration = read(carrier);
        int fav = migration.indexOf("ADD COLUMN fav_cnt");
        int cart = migration.indexOf("ADD COLUMN cart_add_cnt");
        assertThat(fav).as("%s 应含 `ADD COLUMN fav_cnt`", carrier.getFileName()).isGreaterThanOrEqualTo(0);
        assertThat(cart).as("%s 应含 `ADD COLUMN cart_add_cnt`", carrier.getFileName()).isGreaterThanOrEqualTo(0);
        assertThat(fav).as("MySQL 宽表补列顺序必须与 ADS 列序一致（fav_cnt 在 cart_add_cnt 之前）")
                .isLessThan(cart);
        assertThat(migration.toUpperCase(Locale.ROOT))
                .as("必须是加性 ALTER（不得改写已发布迁移 V2/V6，破坏 Flyway checksum）")
                .contains("ALTER TABLE ADS_OPERATION_OVERVIEW_M");
    }

    @Test
    void Spark公式按事件条数计数而不是去重人数() throws IOException {
        String block = operationOverviewBlock();
        Matcher m = EVENT_COUNT_FORMULA.matcher(block);
        List<String> aliases = new ArrayList<>();
        List<String> types = new ArrayList<>();
        while (m.find()) {
            types.add(m.group(1).toLowerCase(Locale.ROOT));
            aliases.add(m.group(2).toLowerCase(Locale.ROOT));
        }
        assertThat(types)
                .as("def operationOverview 必须对 favorite/cart_add 各有一处 `COUNT(CASE WHEN behavior_type = …)`")
                .contains("favorite", "cart_add");
        assertThat(aliases)
                .as("事件条数公式必须落在 fav_cnt / cart_add_cnt 两个别名上（次数口径）")
                .contains("fav_cnt", "cart_add_cnt");

        for (String type : List.of("favorite", "cart_add")) {
            assertThat(block)
                    .as("次数列不得改用去重人数口径（设计 §11.2 L425 明确「用户转化时另算去重用户数」是另一件事）")
                    .doesNotContainIgnoringCase("COUNT(DISTINCT CASE WHEN behavior_type = '" + type + "'");
        }
    }
}
