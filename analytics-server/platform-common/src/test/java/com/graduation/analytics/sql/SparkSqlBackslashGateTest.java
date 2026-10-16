package com.graduation.analytics.sql;

import com.graduation.analytics.sql.SparkSqlBackslashScan.Kind;
import com.graduation.analytics.sql.SparkSqlBackslashScan.Lit;
import com.graduation.analytics.sql.SparkSqlBackslashScan.Result;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3-52 静态守卫：Spark 生产树 SQL 字面量里的**源码形态反斜杠**。
 *
 * <h2>缺陷面（backlog L512 原文的落地）</h2>
 * <p>「Spark SQL 字面量反斜杠转义被解析器吃掉这一类缺陷目前只靠人工审计兜底」。
 * S3-01 的真实事故就是它：{@code AdsSql} 里 {@code regexp_replace(…, '(\d{4})', …)} 的文本
 * 经 Spark 解析后 {@code \d} 变成 {@code d}，**静默**输出错值。此后只有人工复核，
 * 本守卫把这一类形态变成可判定。</p>
 *
 * <h2>四条判据</h2>
 * <ol>
 *   <li><b>① SQL 文本零反斜杠</b>：主树里「像 SQL」的字符串字面量中，源码形态带反斜杠的必须为空
 *       （白名单见 {@link #SQL_TEXT_WHITELIST}，当前为空 —— 登记即等于宣布「这里要靠解析器转义」）。</li>
 *   <li><b>② 闭集</b>：主树里**全部**源码形态带反斜杠的字面量，按文件的（字符串／字符）条数
 *       必须与 {@link #declaredBackslashFiles()} 精确相等。方向有两个：未登记 ⇒ 红
 *       （新写下的反斜杠必须先想清楚）；已登记但实际没有 ⇒ 也红（声明表不许留死条目）。</li>
 *   <li><b>③ 判据面非空跑</b>：文件数／字面量总数／像 SQL 的字面量数／带反斜杠字面量数各有下限。</li>
 *   <li><b>④ 注释载重</b>：raw（注释按代码扫）与 code 两跑的字面量数必须不同 ——
 *       证明注释内引号确实会造出幻影字面量，跳过注释不是可有可无的装饰。</li>
 * </ol>
 *
 * <h2>为什么把机制钉在合成夹具上</h2>
 * <p>真树只能证明「当前没有」；「换一种写法会不会被抓到」必须由夹具钉住：
 * 单行双反斜杠／三引号单反斜杠／字符字面量／{@code """"…""""} 引号串／插值洞内嵌套字面量／
 * 注释里的 SQL 文本。夹具走的是**同一条** {@code scan(root)} 路径（不是另写一段正则冒充证据）。</p>
 *
 * <h2>已知边界（不夸大）</h2>
 * <ul>
 *   <li>它证明的是**源码形态的缺席**，不是「Spark SQL 运行时对反斜杠的处理已实测」。</li>
 *   <li>判据面只覆盖 {@code spark-jobs/src/main/scala}；测试树（{@code src/test/scala}）与
 *       {@code warehouse/**} 的 {@code .sql} 不在本轮判据面内（理由与实测见登记册）。</li>
 *   <li>「像 SQL」是关键字启发式（{@code SparkSqlBackslashScan.SQL_KEYWORD_TOKENS}），
 *       不证明该字面量真的被交给 Spark；它只用来收窄判据面，表被掏空由判据③的下限挡住。</li>
 * </ul>
 */
class SparkSqlBackslashGateTest {

    private static final String BASE = "spark-jobs/src/main/scala/com/graduation/analytics/";

    /** 判据① 的显式白名单（当前为空：SQL 文本里不该有源码形态反斜杠） */
    private static final Set<String> SQL_TEXT_WHITELIST = Set.of();

    /**
     * 判据③ 的下限：**只防「扫描面塌缩」**，不防小幅漂移 —— 精确口径由判据② 的声明表独占
     * （塌缩到 0 时判据② 会全表报「声明失效」，这里只是让塌缩的原因一眼可辨）。
     * 读数见每次运行打印的「S3-52 扫描面读数」行（S3-52 实测 36／1406／132／9）。
     */
    private static final int MIN_SCALA_FILES = 36;
    private static final int MIN_LITERALS = 1300;
    private static final int MIN_SQL_TEXT_STRINGS = 100;
    private static final int MIN_BACKSLASH_LITERALS = 5;

    /**
     * 判据② 的声明表：主树里源码形态带反斜杠的字面量，按文件登记的 {@code [字符串, 字符]} 条数。
     *
     * <p><b>这张表是实测结果</b>：先用空表跑红（RED-1，逐条打印实测清单），再按实测登记 ——
     * 不是先写下再让实现去凑。登记表按**文件计数**而不是 {@code 文件:行号}：行号会随无关改动漂移，
     * 计数只在「这个文件里多／少了一处反斜杠」时才变，那正是要人过目的时刻。</p>
     *
     * <p><b>为什么每一处都值得留下</b>（逐条理由，后续改动者只需看这里）：</p>
     * <ul>
     *   <li>{@code algorithm/Cleaners.scala} 1 条：清洗侧的数值/正则形态（{@code .r} 宿主），不交 Spark。</li>
     *   <li>{@code job/JobArgs.scala} 1 条：日期参数形态校验（{@code .r} 宿主）。</li>
     *   <li>{@code job/LocalSchemaInitJob.scala} 2 条：本地 schema 初始化时按空白切分/替换
     *       （{@code split}/{@code replaceAll} 宿主）—— 宿主是 Scala 正则 API，不是 Spark SQL。</li>
     *   <li>{@code job/MetricExportJob.scala} 1 条**字符**：{@code posix()} 里把 Windows 反斜杠换成
     *       {@code /}（路径归一，与 SQL 无关）。<b>注意该文件的清单字面量不含反斜杠</b>：
     *       {@code mkString(",\n")} 写在插值洞里，按口径「洞是代码，不是字面量内容」不计入外层
     *       （RED-1 实测：Java 仪器 str=0，另一套按整段文本统计的 PowerShell 仪器给 str=1，
     *       差异即此一处口径，不是漏报）。</li>
     *   <li>{@code sql/JsonObjectSlicer.scala} 1 字符串（{@code \\\"}，JSON 转义处理）＋ 3 字符
     *       （词法状态机里 {@code c == '\\'} 的比较）—— 都不是 SQL 文本。</li>
     * </ul>
     */
    private static Map<String, int[]> declaredBackslashFiles() {
        Map<String, int[]> m = new LinkedHashMap<>();
        m.put(BASE + "algorithm/Cleaners.scala", new int[] { 1, 0 });
        m.put(BASE + "job/JobArgs.scala", new int[] { 1, 0 });
        m.put(BASE + "job/LocalSchemaInitJob.scala", new int[] { 2, 0 });
        m.put(BASE + "job/MetricExportJob.scala", new int[] { 0, 1 });
        m.put(BASE + "sql/JsonObjectSlicer.scala", new int[] { 1, 3 });
        return m;
    }

    // ── 判据① ──────────────────────────────────────────────────────────────

    @Test
    void sqlTextCarriesNoSourceFormBackslash() {
        Result r = SparkSqlBackslashScan.scan(RepoRoot.path());
        List<Lit> hits = r.sqlBackslashStrings().stream()
                .filter(l -> !SQL_TEXT_WHITELIST.contains(l.file() + " :: " + l.raw()))
                .toList();
        assertTrue(hits.isEmpty(), () -> """
                像 SQL 的字符串字面量里出现了源码形态反斜杠（Spark SQL 会吃掉未识别的转义 \
                ⇒ 静默错值，S3-01 事故形态）。修法：改成不含反斜杠的写法（如 substr/concat），\
                或在 SQL_TEXT_WHITELIST 里登记并说明为什么必须依赖转义层。
                """ + render(hits));
    }

    // ── 判据② ──────────────────────────────────────────────────────────────

    @Test
    void backslashLiteralsAreAClosedSet() {
        Result r = SparkSqlBackslashScan.scan(RepoRoot.path());
        Map<String, int[]> actual = r.backslashByFile();
        Map<String, int[]> declared = declaredBackslashFiles();

        Set<String> undeclared = new TreeSet<>(actual.keySet());
        undeclared.removeAll(declared.keySet());
        Set<String> stale = new TreeSet<>(declared.keySet());
        stale.removeAll(actual.keySet());

        List<String> drifted = new ArrayList<>();
        for (String f : new TreeSet<>(actual.keySet())) {
            if (!declared.containsKey(f)) {
                continue;
            }
            int[] a = actual.get(f);
            int[] d = declared.get(f);
            if (a[0] != d[0] || a[1] != d[1]) {
                drifted.add("  %s 实际 %s ≠ 声明 %s".formatted(shortName(f), fmt(a), fmt(d)));
            }
        }

        List<String> problems = new ArrayList<>();
        undeclared.forEach(f -> problems.add("  未登记: " + shortName(f) + " " + fmt(actual.get(f))));
        stale.forEach(f -> problems.add("  声明失效（实际已无）: " + shortName(f)));
        problems.addAll(drifted);

        assertTrue(problems.isEmpty(), () -> """
                源码形态带反斜杠的字面量集合与声明表不一致（新增反斜杠必须先想清楚并登记；\
                声明表也不许留死条目）。实测全表：
                """ + renderTable(actual) + String.join("\n", problems));
    }

    // ── 判据③ ──────────────────────────────────────────────────────────────

    @Test
    void scanSurfaceIsNonVacuous() {
        Result r = SparkSqlBackslashScan.scan(RepoRoot.path());
        assertTrue(r.scanned().size() >= MIN_SCALA_FILES,
                () -> "扫描面文件数塌缩：" + r.scanned().size() + " < " + MIN_SCALA_FILES);
        assertTrue(r.size() >= MIN_LITERALS,
                () -> "字面量总数塌缩：" + r.size() + " < " + MIN_LITERALS);
        assertTrue(r.sqlTextStrings().size() >= MIN_SQL_TEXT_STRINGS,
                () -> "「像 SQL」的字面量塌缩：" + r.sqlTextStrings().size() + " < " + MIN_SQL_TEXT_STRINGS);
        assertTrue(r.backslashLiterals().size() >= MIN_BACKSLASH_LITERALS,
                () -> "带反斜杠的字面量塌缩：" + r.backslashLiterals().size() + " < " + MIN_BACKSLASH_LITERALS);
        assertTrue(r.scanned().stream().noneMatch(p -> p.toString().replace('\\', '/').contains("/target/")),
                () -> "扫描面混进了构建产物：" + r.scanned());
        // 读数行：门禁日志里留一行可观测量，方便日后判「漂移」还是「口径变了」
        System.out.printf("[S3-52 扫描面读数] 文件=%d 字面量=%d 字符串=%d 字符=%d 像SQL=%d 带反斜杠=%d%n",
                r.scanned().size(), r.size(),
                r.literals().stream().filter(l -> l.kind() == Kind.STRING).count(),
                r.literals().stream().filter(l -> l.kind() == Kind.CHAR).count(),
                r.sqlTextStrings().size(), r.backslashLiterals().size());
    }

    // ── 判据④（真树：raw/code 双跑） ─────────────────────────────────────────

    @Test
    void commentStrippingIsLoadBearingOnTheRealTree() {
        Path root = RepoRoot.path();
        Result code = SparkSqlBackslashScan.scan(root, true);
        Result raw = SparkSqlBackslashScan.scan(root, false);
        assertTrue(raw.size() > code.size(), () -> """
                注释按代码扫（raw）与剥离注释后（code）的字面量数**没有**差别，说明注释内引号\
                造不出幻影字面量、判据④ 失去意义，或者注释跳过根本没生效。
                code=%d raw=%d""".formatted(code.size(), raw.size()));
    }

    // ── 夹具：词法家族 ──────────────────────────────────────────────────────

    @Test
    void lexerFamiliesAreHandledAsMeasured(@TempDir Path tmp) {
        Path file = fixture(tmp, "Fixture.scala", """
                object Fixture {
                  val a = "\\\\d"
                  val b = "\\n"
                  val c = \"\"\"SELECT '\\d' FROM t\"\"\"
                  val d = '\\\\'
                  val e = s\"\"\"{"columns":[${list.map(x => s\"\"\"\"$x\"\"\"\").mkString(",")}]}\"\"\"
                  val f = "^\\\\d+$".r
                }
                """);
        Result r = SparkSqlBackslashScan.scan(tmp);
        assertEquals(1, r.scanned().size(), () -> "夹具树应恰好 1 个 scala 文件：" + r.scanned());
        assertEquals(8, r.size(), () -> "夹具字面量数应为 8（含洞内 2 条）。实测：\n" + render(r.literals()));

        // 单行单反斜杠（"\n"）不算；单行双反斜杠（"\\d"）算
        assertEquals(List.of(2, 4, 5, 7), lines(r.backslashLiterals()),
                () -> "带反斜杠的字面量行号应为 [2,4,5,7]（a=2 / c=4 / d=5 / f=7）。实测：\n"
                        + render(r.literals()));
        assertEquals(Kind.CHAR, litAt(r, 5).kind(), "第 5 行应为字符字面量");
        assertTrue(litAt(r, 4).sqlText(), "三引号里的 SELECT 文本应被判为「像 SQL」");
        assertEquals(1, r.sqlBackslashStrings().size(),
                () -> "夹具里应恰好 1 条「像 SQL ＋ 反斜杠」的字符串（第 4 行）。实测：\n"
                        + render(r.sqlBackslashStrings()));

        // 引号串与插值洞：外层 raw 不含洞内容，洞内字面量单独登记
        Lit outer = litAt(r, 6);
        assertEquals("{\"columns\":[]}", outer.raw(),
                () -> "插值洞内容不得计入外层字面量内容，实测 raw=" + outer.snippet());
        assertEquals("\"\"", holeNested(r).raw(),
                () -> "洞内 s\"\"\"\"$x\"\"\"\" 的引号串应按「N≥3 取 N−3」解析出 raw=\"\"，实测："
                        + holeNested(r).snippet());
    }

    // ── 夹具：注释载重（机制） ──────────────────────────────────────────────

    @Test
    void commentOnlySqlIsIgnoredWhileRealSqlIsFlagged(@TempDir Path tmp) {
        fixture(tmp, "Commented.scala", """
                object Commented {
                  // val sql = \"\"\"SELECT regexp_replace(dt, '\\d{4}', '') FROM t\"\"\"
                  val ok = "SELECT 1"
                }
                """);
        Result code = SparkSqlBackslashScan.scan(tmp, true);
        Result raw = SparkSqlBackslashScan.scan(tmp, false);
        assertTrue(code.sqlBackslashStrings().isEmpty(),
                () -> "注释里的 SQL 文本必须不算命中。实测：\n" + render(code.sqlBackslashStrings()));
        assertTrue(raw.size() > code.size(),
                () -> "raw 模式应当把注释里的引号当成字面量（幻影），实测 code=" + code.size()
                        + " raw=" + raw.size());

        // 同样的文本放在代码里 ⇒ 必红（正对照；另起一棵夹具树，免得两条夹具互相计数）
        fixture(tmp.resolve("live"), "Live.scala", """
                object Live {
                  val sql = \"\"\"SELECT regexp_replace(dt, '\\d{4}', '') FROM t\"\"\"
                }
                """);
        Result live = SparkSqlBackslashScan.scan(tmp.resolve("live"), true);
        assertEquals(1, live.sqlBackslashStrings().size(),
                () -> "同一段文本移到代码里必须被抓到（正对照）。实测：\n" + render(live.sqlBackslashStrings()));
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    /** 在临时目录里写一个 {@code spark-jobs/src/main/scala/...} 夹具（可选子目录名用于隔离多棵树） */
    private static Path fixture(Path tmp, String name, String body) {
        return fixture(tmp, "", name, body);
    }

    private static Path fixture(Path tmp, String sub, String name, String body) {
        Path root = sub.isEmpty() ? tmp : tmp.resolve(sub);
        Path file = root.resolve(SparkSqlBackslashScan.SPARK_MAIN)
                .resolve("com/graduation/analytics/" + name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("夹具写入失败: " + file, e);
        }
        return file;
    }

    private static List<Integer> lines(List<Lit> lits) {
        return lits.stream().map(Lit::line).sorted().toList();
    }

    private static Lit litAt(Result r, int line) {
        return r.literals().stream().filter(l -> l.line() == line).findFirst()
                .orElseThrow(() -> new AssertionError("第 " + line + " 行没有字面量。实测：\n" + render(r.literals())));
    }

    private static Lit holeNested(Result r) {
        return r.literals().stream().filter(l -> l.line() == 6 && l.raw().equals("\"\"")).findFirst()
                .orElseThrow(() -> new AssertionError("洞内嵌套字面量未按引号串规则解析。实测：\n"
                        + render(r.literals())));
    }

    private static String render(List<Lit> lits) {
        if (lits.isEmpty()) {
            return "  （空）";
        }
        return lits.stream().map(l -> "  " + l).collect(Collectors.joining("\n"));
    }

    private static String renderTable(Map<String, int[]> table) {
        if (table.isEmpty()) {
            return "  （空）\n";
        }
        Set<String> files = new LinkedHashSet<>(table.keySet());
        return files.stream().map(f -> "  " + shortName(f) + " " + fmt(table.get(f)))
                .collect(Collectors.joining("\n")) + "\n";
    }

    private static String shortName(String file) {
        return file.startsWith(BASE) ? file.substring(BASE.length()) : file;
    }

    private static String fmt(int[] counts) {
        return "str=" + counts[0] + " char=" + counts[1];
    }
}
