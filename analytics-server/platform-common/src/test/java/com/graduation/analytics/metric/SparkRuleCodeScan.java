package com.graduation.analytics.metric;

import com.graduation.analytics.warehouse.WarehouseNameLiteralScanner.CommentSyntax;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Spark 生产源码的「大写字面量」提取器（S3-49 规则码漂移门禁的扫描面）。
 *
 * <p><b>为什么需要它</b>：规则码的唯一所有者是 Java 侧 {@code RuleSeverity.REGISTERED}
 * （{@code platform-common}），而**真正执行**这些规则的代码在 {@code spark-jobs}（Scala/JDK8）。
 * 两侧只靠字面量对齐 —— 此前**只在一侧改码没有任何自动守卫**（{@code PROJECT_STATUS.md}
 * backlog 行原文：「「Spark 规则码字面量 ↔ 登记集」没有自动守卫」，S3-10 是靠人工发现的；
 * 读侧按 §7.3.1 判「未登记规则」直接拒发）。本类只做**提取**，不判类；
 * 「哪些码算已登记、哪些 token 不是规则码」由
 * {@code SparkRuleCodeRegistryGuardTest} 用登记表与显式非规则 token 表判定。</p>
 *
 * <p><b>扫描面</b>：{@code spark-jobs/src/main/scala/**} 下的 {@code .scala}
 * （**不含测试树**：测试里的码是断言期望值，不是第二处所有者 —— 与库名门禁同一口径）。
 * 生产文本文件数、三个规则码承载文件都另有下限/在范围断言（见守卫），防止范围失效伪装成通过。</p>
 *
 * <p><b>注释剥离复用唯一实现</b>：{@link CommentSyntax#strip}(text, {@code SLASH}) 来自
 * {@code WarehouseNameLiteralScanner}（该类的行为由 {@code WarehouseNameLiteralGateTest.lexerFamilies}
 * 钉住）。本类**不另写**第二份注释剥离器 —— 两份剥离口径就是新的第二所有者。
 * 剥离只把注释区间换成空格，行数与行号不变，因此命中位置可直接引用。</p>
 *
 * <p><b>提取口径（宽口径，不断言语义）</b>：字符串字面量内容匹配 {@code ^[A-Z][A-Z0-9_]{3,}$}
 * 即记一处（{@code file} 为仓库相对路径、正斜杠；{@code line} 为 1 起始行号）。
 * 之所以用「宽口径 ＋ 显式非规则 token 表」而不是「只抓 {@code QualityCheck(} 首参」：
 * 后者看不见 {@code AdsQualityJob.scala:94} 的 {@code Set("AMOUNT_RECONCILE", …)} 策略表与
 * {@code :107} 的 {@code byRule.get("EVENT_ID_UNIQUE")}（实测存在的另两类站点），
 * 而这两种写法的码同样会进库、同样会被读侧按登记表判定。</p>
 *
 * <p><b>已知边界（不夸大）</b>：①只认双引号里的**整串**大写 token（{@code s"$prefix_CODE"} 这类
 * 拼接码抓不到 —— 实测 Spark 生产树里没有这种写法）；②不解析 Scala 语法（纯文本 ＋ 词法剥注释），
 * 因此「这个字面量是不是规则码」的判断权在守卫的非规则 token 表里，本类不做猜测；
 * ③只扫 {@code src/main/scala}，不含 {@code src/main/resources}。</p>
 */
final class SparkRuleCodeScan {

    /** 扫描面（仓库相对路径） */
    static final String SPARK_MAIN = "spark-jobs/src/main/scala";

    /** 大写字面量形态：至少 4 字符，避免把 {@code "ADS"}/{@code "DWS"} 这类层名短 token 混进来 */
    static final Pattern UPPER_LITERAL = Pattern.compile("\"([A-Z][A-Z0-9_]{3,})\"");

    // ── S3-50 逃逸面：槽位形态清点 ─────────────────────────────────────────────

    /** 槽位形态①：{@code QualityCheck("CODE", …)} 首参（实测 20 处） */
    static final String FORM_QUALITY_CHECK_ARG = "QUALITY_CHECK_ARG";

    /** 槽位形态②：策略表 {@code Set("CODE", …)}（实测 3 处，同一 `Set` 内） */
    static final String FORM_STRATEGY_SET = "STRATEGY_SET";

    /** 槽位形态③：按码查表 {@code map.get("CODE")}（实测 1 处） */
    static final String FORM_MAP_GET = "MAP_GET";

    /** 未知形态：三类之外 —— 守卫必须红（新增形态须显式登记） */
    static final String FORM_UNKNOWN = "UNKNOWN";

    /** 宿主被调名①：{@code QualityCheck}（精确匹配） */
    static final String FORM_HOST_QUALITY_CHECK = "QualityCheck";

    /** 宿主被调名②：{@code Set}（**精确匹配**：{@code ruleSet(}／{@code Set.apply(} 都不算策略表） */
    static final String FORM_HOST_SET = "Set";

    /** 宿主被调名后缀③：{@code .get}（{@code byRule.get("CODE")} 这类按码查表） */
    static final String FORM_HOST_MAP_GET_SUFFIX = ".get";

    /**
     * 宿主调用判定（**平衡括号回扫**，不是「窗口内最近关键词」）。
     *
     * <p>从这个字面量向左回扫，遇到 {@code )} 记深度 ＋1、遇到深度 0 的 {@code (} 即为**宿主调用的左括号**，
     * 取它前面的被调名（可带限定名，如 {@code byRule.get}）。</p>
     *
     * <p><b>为什么不用「固定窗口内最近锚点」</b>：S3-50 的 P3 变异探针实测暴露该口径不可靠 ——
     * {@code AdsQualityJob.scala:107} 的 {@code byRule.get("EVENT_ID_UNIQUE")} 之前约 95 字符处
     * 还有一个**无关的** {@code byRule.get(r)}（L106 的消息拼接里），窗口口径会把那个 {@code .get(}
     * 当作宿主，于是「把查表键改成动态构码」这条变异**不红**（探针值为绿）。平衡回扫取的是**真正的宿主**，
     * 该变异随即变红。</p>
     */
    private static final int HOST_LOOKBACK = 24;

    /** 宿主调用标识符（取紧贴宿主 {@code (} 之前的标识符/限定名尾段） */
    private static final Pattern HOST_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$.]*$");

    /** {@code QualityCheck(} 出现处（既可能是调用点，也可能是类型声明） */
    private static final Pattern QUALITY_CHECK_CALL = Pattern.compile("QualityCheck\\s*\\(");

    /** 类型声明前缀：{@code case class QualityCheck(} / {@code class QualityCheck(} */
    private static final Pattern TYPE_DECLARATION = Pattern.compile("(?:case\\s+)?class\\s+$");

    /** 首参快照取多长（仅供失败信息定位，不参与判据） */
    private static final int ARG_SNAPSHOT = 40;

    private SparkRuleCodeScan() {
    }

    /** 一处字面量命中（仓库相对路径、1 起始行号、token 原文） */
    record Lit(String file, int line, String code) {
        @Override
        public String toString() {
            return file + ":" + line + " -> " + code;
        }
    }

    /** 一次扫描的结果：进范围的文件 ＋ 全部命中（按文件/行号排序） */
    record Result(List<Path> scanned, List<Lit> literals) {

        /** 去重后的 token（保持字典序，便于稳定报错） */
        List<String> codes() {
            Set<String> distinct = new LinkedHashSet<>();
            literals.forEach(l -> distinct.add(l.code()));
            return distinct.stream().sorted().toList();
        }

        /** 某 token 的命中数 */
        long countOf(String code) {
            return literals.stream().filter(l -> l.code().equals(code)).count();
        }
    }

    /** 一处规则码槽位：**宿主调用**（如 {@code QualityCheck}/{@code Set}/{@code byRule.get}）与由此判定的形态 */
    record Slot(String file, int line, String code, String host, String form) {
        @Override
        public String toString() {
            return file + ":" + line + " -> " + code + " [宿主 " + host + " ⇒ " + form + "]";
        }
    }

    /** 一处 {@code QualityCheck(} 出现点：**调用点** 或 **类型声明**（S3-50 首参静态性判据） */
    record CallSite(String file, int line, String firstArg, boolean staticLiteral, boolean typeDeclaration) {
        @Override
        public String toString() {
            String kind = typeDeclaration ? "类型声明" : (staticLiteral ? "静态字面量首参" : "非字面量首参");
            return file + ":" + line + " [" + kind + "] " + firstArg;
        }
    }

    /** 扫描仓根下的 Spark 生产树 */
    static Result scan(Path root) {
        Path dir = root.resolve(SPARK_MAIN);
        List<Path> files = scalaFiles(dir);
        List<Lit> hits = new ArrayList<>();
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String stripped = CommentSyntax.strip(read(file), CommentSyntax.SLASH);
            String[] lines = stripped.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = UPPER_LITERAL.matcher(lines[i]);
                while (m.find()) {
                    hits.add(new Lit(rel, i + 1, m.group(1)));
                }
            }
        }
        hits.sort(Comparator.comparing(Lit::file).thenComparingInt(Lit::line).thenComparing(Lit::code));
        return new Result(List.copyOf(files), List.copyOf(hits));
    }

    /**
     * 槽位清点（S3-50）：Spark 生产树里**每个**大写字面量按其**宿主调用**归类
     * —— {@code QualityCheck(}／{@code Set(}／{@code …get(}，三者之外一律 {@link #FORM_UNKNOWN}。
     *
     * <p>本方法**仍是纯提取**：它不判断「这个 token 是不是规则码」（那是守卫的非规则 token 表与
     * {@link RuleSeverity#registeredCodes()} 的职责），只给出「若要当规则码用，它的宿主形态是哪一类」。
     * 守卫据此对**已登记码**的槽位做形态闭集判定。</p>
     */
    static List<Slot> slots(Path root) {
        Path dir = root.resolve(SPARK_MAIN);
        List<Slot> slots = new ArrayList<>();
        for (Path file : scalaFiles(dir)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String stripped = CommentSyntax.strip(read(file), CommentSyntax.SLASH);
            Matcher m = UPPER_LITERAL.matcher(stripped);
            while (m.find()) {
                String host = hostOf(stripped, m.start());
                slots.add(new Slot(rel, lineAt(stripped, m.start()), m.group(1), host, formOfHost(host)));
            }
        }
        slots.sort(Comparator.comparing(Slot::file).thenComparingInt(Slot::line).thenComparing(Slot::code));
        return List.copyOf(slots);
    }

    /** 宿主调用：从这个字面量向左找第一个**未闭合**的 {@code (}，返回它前面的被调名（找不到 ⇒ 空串） */
    private static String hostOf(String text, int literalStart) {
        int depth = 0;
        for (int i = literalStart - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    String head = text.substring(Math.max(0, i - HOST_LOOKBACK), i);
                    Matcher m = HOST_IDENTIFIER.matcher(head);
                    return m.find() ? m.group() : "";
                }
                depth--;
            }
        }
        return "";
    }

    /**
     * 宿主被调名 → 槽位形态（**只认精确被调名**）。
     *
     * <p>精确匹配是必要的：{@code Set(} 是策略表，但 {@code ruleSet(}/{@code Set.apply(} 不是；
     * {@code …get(} 只认 {@code x.get(} 这种带限定名的查表调用。</p>
     */
    private static String formOfHost(String host) {
        if (FORM_HOST_QUALITY_CHECK.equals(host)) {
            return FORM_QUALITY_CHECK_ARG;
        }
        if (FORM_HOST_SET.equals(host)) {
            return FORM_STRATEGY_SET;
        }
        if (host.endsWith(FORM_HOST_MAP_GET_SUFFIX)) {
            return FORM_MAP_GET;
        }
        return FORM_UNKNOWN;
    }

    /**
     * 全部 {@code QualityCheck(} 出现点（**调用点**与**类型声明**分开标注，按文件/行号排序）。
     *
     * <p>{@code staticLiteral} ＝ 首参（跳过空白/换行后）以 {@code "} 开头。这样「规则码能不能被
     * 动态拼出来」就从「靠人看」变成可判定：非字面量首参在守卫里必然红。</p>
     */
    static List<CallSite> callSites(Path root) {
        Path dir = root.resolve(SPARK_MAIN);
        List<CallSite> sites = new ArrayList<>();
        for (Path file : scalaFiles(dir)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String stripped = CommentSyntax.strip(read(file), CommentSyntax.SLASH);
            Matcher m = QUALITY_CHECK_CALL.matcher(stripped);
            while (m.find()) {
                String before = stripped.substring(Math.max(0, m.start() - 20), m.start());
                boolean declaration = TYPE_DECLARATION.matcher(before).find();
                String after = stripped.substring(m.end());
                String arg = after.length() > ARG_SNAPSHOT ? after.substring(0, ARG_SNAPSHOT) : after;
                boolean literal = after.stripLeading().startsWith("\"");
                sites.add(new CallSite(rel, lineAt(stripped, m.start()),
                        arg.replace('\n', ' ').strip(), literal, declaration));
            }
        }
        sites.sort(Comparator.comparing(CallSite::file).thenComparingInt(CallSite::line));
        return List.copyOf(sites);
    }

    /** 1 起始行号 */
    private static int lineAt(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** {@code src/main/scala} 下的 {@code .scala} 文件（跳过 {@code target}，按路径排序 ⇒ 结果稳定） */
    static List<Path> scalaFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("扫描面不存在（门禁不得空跑）: " + dir);
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".scala"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历失败: " + dir, e);
        }
    }

    /** 读文件（UTF-8）；失败直接抛，不静默当空文件 */
    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + file, e);
        }
    }
}
