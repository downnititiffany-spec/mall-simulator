package com.graduation.analytics.sql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Spark 生产源码的「字面量 ＋ 反斜杠形态」提取器（S3-52 静态守卫的扫描面）。
 *
 * <h2>为什么需要它</h2>
 * <p>backlog 行（{@code PROJECT_STATUS.md} L512）记的是：<b>Spark SQL 字面量里「反斜杠转义被解析器吃掉」
 * 这一类缺陷目前只靠人工审计兜底</b>。S3-01 就是该缺陷的一次真实落地：{@code AdsSql} 里
 * {@code regexp_replace('(\d{4})…')} 的 SQL 文本经 Spark 解析后变成 {@code (d{4})}，
 * 结果是**静默**输出错值（正则不匹配 ⇒ 原样返回），靠人看出来。本类只做**提取**：
 * 「哪些字面量在源码形态上带着反斜杠」「哪些字面量文本看起来是 SQL」；
 * 「这些组合是否违规」由 {@code SparkSqlBackslashGateTest} 用判据与声明表判定。</p>
 *
 * <h2>扫描面</h2>
 * <p>{@code spark-jobs/src/main/scala/**} 下的 {@code .scala}（跳过 {@code target}）；
 * 与 S3-49/S3-50 的规则码门禁同一口径（生产树 ＝ 缺陷面，测试树不在判据面内）。</p>
 *
 * <h2>判据用的形态口径（源码形态，不是求值语义）</h2>
 * <p>本类<b>不</b>求值 Scala 转义（那需要编译器，也需要先实测语言层语义 —— 本轮未做），
 * 只判源码文本形态，取「在两种语言层假设下值里都可能带反斜杠」的**并集**（方向偏严）：</p>
 * <ul>
 *   <li>单行字符串（{@code "…"}）：判 <b>连续两个反斜杠</b>（{@code \\}）。单个 {@code \}
 *       在这里必然是 Scala 转义序列的一部分（{@code \n}／{@code \t}／{@code \"}），不携带字面反斜杠。</li>
 *   <li>三引号字符串（{@code """…"""}）与 {@code raw"…"}：判 <b>任一反斜杠</b>。
 *       前者是 Scala 不处理转义的形态，后者本身就是「不求值转义」的插值器。</li>
 *   <li>字符字面量（{@code '…'}）：判 <b>连续两个反斜杠</b>（字符字面量没有不求值形态）。</li>
 * </ul>
 *
 * <h2>词法（本类自己的一遍扫描，不做前置剥离）</h2>
 * <p>要判「字面量里有没有反斜杠」，必须先知道**哪里是字面量**，而本仓真实代码里存在两种朴素词法会踩空的形态：</p>
 * <ul>
 *   <li><b>引号串 {@code """"…""""}</b>：Scala 的规则是「连续 N≥3 个引号 ⇒ 内容取 N−3 个引号并闭合」。
 *       {@code WarehouseNameLiteralScanner.CommentSyntax.strip} 的三引号闭合只认**第一个** {@code """}
 *       （它的 {@code skipString} 就是这么写的），与本类所需口径不同，故本类**不**拿它做前置剥离
 *       —— 两者的关系是并存、各有其扫描面，本类不声称二者等价。</li>
 *   <li><b>插值洞 {@code ${…}} 里的嵌套字面量</b>：{@code MetricExportJob.scala:91} 的
 *       {@code s""""columns":[${spec.columns.map(c => s""""$c"""").mkString(",")}],"""}
 *       在洞内又有三引号字面量。本类对洞内**按代码递归扫描**，洞内字面量单独登记，
 *       且**不计入**外层字面量的内容（洞是代码，不是字面量内容）。</li>
 * </ul>
 * <p>注释识别与引号／洞识别在**同一遍**词法里（{@code skipComments} 开关）：
 * 只有同源才知道「这个引号在不在注释里」。注释内出现引号会造出**幻影字面量**，
 * 这正是守卫里 raw／code 双跑的负对照所钉住的载重性。</p>
 *
 * <h2>「像 SQL」怎么判（口径，不是求值）</h2>
 * <p>字面量源码文本命中 {@link #SQL_KEYWORD}（关键字表见 {@link #SQL_KEYWORD_TOKENS}）即记
 * {@code sqlText=true}。这是**启发式**：它不证明这个字面量真的会被交给 Spark 解析，
 * 只用于把判据面收窄到「像 SQL 的文本」。表被掏空（命中面塌缩）由守卫的下限断言挡住。</p>
 *
 * <h2>已知边界（不夸大）</h2>
 * <ol>
 *   <li>不判 Scala 转义求值语义 ⇒ 判据是「源码形态」，见上；三引号下单个 {@code \} 也记。</li>
 *   <li>块注释不处理嵌套（与 {@code CommentSyntax} 同口径）；嵌套块注释会提前结束注释区。</li>
 *   <li>不扫 {@code warehouse/**} 下的 {@code .sql}（那是另一种词法：SQL 单引号里反斜杠不参与
 *       Scala 层转义；实测 6 个文件 0 反斜杠）；不扫 {@code spark-jobs/src/test/scala}
 *       （判据面之外的既有决定，残余面见登记册）。</li>
 *   <li>它证明的是**源码形态的缺席**，不是「Spark SQL 运行时行为已实测」。</li>
 * </ol>
 */
final class SparkSqlBackslashScan {

    /** 扫描面（仓库相对路径，正斜杠） */
    static final String SPARK_MAIN = "spark-jobs/src/main/scala";

    /**
     * SQL 关键字表（口径本体）。取值来自 S3-52 的开工前实测分布
     * （主树 19 个文件、135 条命中），只用于把「像 SQL 的文本」从 1300+ 条字面量里分出来。
     */
    static final List<String> SQL_KEYWORD_TOKENS = List.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "OVERWRITE",
            "FROM", "WHERE", "JOIN", "UNION ALL", "MERGE", "PARTITION", "LATERAL VIEW",
            "GROUP BY", "ORDER BY", "CLUSTER BY", "DISTRIBUTED BY", "SORT BY",
            "REGEXP_REPLACE", "REGEXP_EXTRACT", "GET_JSON_OBJECT", "CASE WHEN",
            "CAST", "COALESCE", "SUBSTR", "SUM", "COUNT", "AVG", "MAX", "MIN");

    /** {@link #SQL_KEYWORD_TOKENS} 的匹配式（大小写不敏感、词边界；长 token 优先以免被短 token 截断） */
    static final Pattern SQL_KEYWORD = compileKeywords();

    private SparkSqlBackslashScan() {
    }

    /** 字面量种类 */
    enum Kind {
        STRING, CHAR
    }

    /** 前缀形态（口径用）：普通／{@code s}／{@code f}／{@code raw}；三引号另由 {@code triple} 标注 */
    enum Prefix {
        PLAIN, S, F, RAW
    }

    /**
     * 一处字面量。
     *
     * @param file           仓库相对路径（正斜杠）
     * @param line           1 起始行号
     * @param offset         文件内起始偏移（仅用于稳定排序与定位，不参与判据）
     * @param kind           字符串／字符
     * @param prefix         插值器前缀
     * @param triple         是否三引号形态
     * @param raw            源码形态内容（**不含**插值洞；洞内作为独立代码单独扫描）
     * @param valueBackslash 按类口径判定的「值里可能带反斜杠」（见类注释）
     * @param sqlText        源码文本命中 {@link #SQL_KEYWORD}
     */
    record Lit(String file, int line, int offset, Kind kind, Prefix prefix, boolean triple,
               String raw, boolean valueBackslash, boolean sqlText) {

        /** 失败信息里的一句快照（换行转义、截断） */
        String snippet() {
            String s = raw.replace("\n", "\\n");
            return s.length() > 72 ? s.substring(0, 72) + "…" : s;
        }

        @Override
        public String toString() {
            return "%s:%d [%s%s%s] %s%s%s".formatted(file, line, prefix, triple ? " \"\"\"" : "",
                    kind == Kind.CHAR ? " char" : "", snippet(),
                    valueBackslash ? "  ⟨反斜杠⟩" : "", sqlText ? "  ⟨像 SQL⟩" : "");
        }
    }

    /** 一次扫描：进范围的文件 ＋ 全部字面量（按文件／偏移排序） */
    record Result(List<Path> scanned, List<Lit> literals) {

        /** 源码形态带反斜杠的字面量 */
        List<Lit> backslashLiterals() {
            return literals.stream().filter(Lit::valueBackslash).toList();
        }

        /** 判据① 的命中面：带反斜杠 **且** 像 SQL 的字符串字面量 */
        List<Lit> sqlBackslashStrings() {
            return literals.stream()
                    .filter(l -> l.valueBackslash() && l.sqlText() && l.kind() == Kind.STRING)
                    .toList();
        }

        /** 像 SQL 的字符串字面量（下限断言用） */
        List<Lit> sqlTextStrings() {
            return literals.stream().filter(l -> l.sqlText() && l.kind() == Kind.STRING).toList();
        }

        /** 文件 → [字符串条数, 字符条数]（判据② 闭集的比对口径，按文件名字典序） */
        Map<String, int[]> backslashByFile() {
            Map<String, int[]> out = new LinkedHashMap<>();
            backslashLiterals().stream().map(Lit::file).distinct().sorted()
                    .forEach(f -> out.put(f, new int[] { 0, 0 }));
            for (Lit l : backslashLiterals()) {
                int[] c = out.get(l.file());
                c[l.kind() == Kind.STRING ? 0 : 1]++;
            }
            return out;
        }

        int size() {
            return literals.size();
        }
    }

    // ── 入口 ───────────────────────────────────────────────────────────────

    /** 扫仓根下的 Spark 生产树（注释按代码处理 ⇒ 这是判据口径） */
    static Result scan(Path root) {
        return scan(root, true);
    }

    /**
     * 扫仓根下的 Spark 生产树。
     *
     * @param skipComments {@code true} ＝ 判据口径（注释不是代码）；
     *                     {@code false} ＝ 负对照口径（注释按代码扫，注释里的引号会造出幻影字面量）
     */
    static Result scan(Path root, boolean skipComments) {
        Path dir = root.resolve(SPARK_MAIN);
        List<Path> files = scalaFiles(dir);
        List<Lit> lits = new ArrayList<>();
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            lits.addAll(new Lexer(rel, read(file), skipComments).parse());
        }
        lits.sort(Comparator.comparing(Lit::file).thenComparingInt(Lit::offset));
        return new Result(List.copyOf(files), List.copyOf(lits));
    }

    /** {@code src/main/scala} 下的 {@code .scala}（跳过 {@code target}；排序 ⇒ 结果稳定） */
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

    // ── 词法 ───────────────────────────────────────────────────────────────

    /**
     * Scala 字面量词法（单遍）。注释识别、引号串形态、插值洞三件事**同源**：
     * 分开做就不知道「这个引号在不在注释里」。
     */
    private static final class Lexer {

        private final String file;
        private final String text;
        private final int n;
        private final boolean skipComments;
        private final List<Lit> out = new ArrayList<>();
        private int pos;
        private int line = 1;

        Lexer(String file, String text, boolean skipComments) {
            this.file = file;
            this.text = text;
            this.n = text.length();
            this.skipComments = skipComments;
        }

        List<Lit> parse() {
            code(false);
            return out;
        }

        /** 代码区扫描；{@code inHole} 时在深度 0 的 {@code '}'} 处返回（插值洞尽头） */
        private void code(boolean inHole) {
            int depth = 0;
            while (pos < n) {
                char c = text.charAt(pos);
                if (inHole) {
                    if (c == '{') {
                        depth++;
                        pos++;
                        continue;
                    }
                    if (c == '}') {
                        pos++;
                        if (depth == 0) {
                            return;
                        }
                        depth--;
                        continue;
                    }
                }
                if (c == '\n') {
                    line++;
                    pos++;
                    continue;
                }
                if (skipComments && c == '/' && peek(1) == '/') {
                    while (pos < n && text.charAt(pos) != '\n') {
                        pos++;
                    }
                    continue;
                }
                if (skipComments && c == '/' && peek(1) == '*') {
                    pos += 2;
                    while (pos + 1 < n && !(text.charAt(pos) == '*' && text.charAt(pos + 1) == '/')) {
                        if (text.charAt(pos) == '\n') {
                            line++;
                        }
                        pos++;
                    }
                    pos = Math.min(pos + 2, n);
                    continue;
                }
                if (c == '\'') {
                    charLiteral();
                    continue;
                }
                if (c == '"') {
                    stringLiteral();
                    continue;
                }
                pos++;
            }
        }

        /** 字符字面量：单行内闭合才算（未闭合按普通字符处理，偏严） */
        private void charLiteral() {
            int start = pos;
            int startLine = line;
            pos++;
            StringBuilder raw = new StringBuilder();
            boolean closed = false;
            while (pos < n) {
                char c = text.charAt(pos);
                if (c == '\\') {
                    raw.append(c);
                    pos++;
                    if (pos < n) {
                        raw.append(text.charAt(pos));
                        pos++;
                    }
                    continue;
                }
                if (c == '\'') {
                    pos++;
                    closed = true;
                    break;
                }
                if (c == '\n') {
                    break;
                }
                raw.append(c);
                pos++;
            }
            if (closed) {
                String r = raw.toString();
                add(startLine, start, Kind.CHAR, Prefix.PLAIN, false, r, doubledBackslash(r));
            }
        }

        /** 字符串字面量：普通／三引号；插值洞按代码递归扫描（洞内字面量单独登记） */
        private void stringLiteral() {
            int start = pos;
            int startLine = line;
            Prefix prefix = prefixBefore(start);
            boolean interpolated = prefix != Prefix.PLAIN;
            boolean triple = startsWith(pos, "\"\"\"");
            StringBuilder raw = new StringBuilder();
            boolean closed = false;
            if (triple) {
                pos += 3;
                while (pos < n) {
                    char c = text.charAt(pos);
                    if (c == '"') {
                        int run = runLength('"');
                        if (run >= 3) {
                            raw.append("\"".repeat(run - 3));
                            pos += run;
                            closed = true;
                            break;
                        }
                        raw.append("\"".repeat(run));
                        pos += run;
                        continue;
                    }
                    if (c == '\n') {
                        line++;
                        raw.append(c);
                        pos++;
                        continue;
                    }
                    if (c == '$' && interpolated && interpolation()) {
                        continue;
                    }
                    raw.append(c);
                    pos++;
                }
            } else {
                pos++;
                while (pos < n) {
                    char c = text.charAt(pos);
                    if (c == '\\') {
                        raw.append(c);
                        pos++;
                        if (pos < n) {
                            raw.append(text.charAt(pos));
                            pos++;
                        }
                        continue;
                    }
                    if (c == '"') {
                        pos++;
                        closed = true;
                        break;
                    }
                    if (c == '\n') {
                        break;
                    }
                    if (c == '$' && interpolated && interpolation()) {
                        continue;
                    }
                    raw.append(c);
                    pos++;
                }
            }
            if (!closed) {
                return; // 未闭合：按普通字符处理（偏严，不吞后续文本）
            }
            String r = raw.toString();
            boolean bs = triple || prefix == Prefix.RAW ? r.indexOf('\\') >= 0 : doubledBackslash(r);
            add(startLine, start, Kind.STRING, prefix, triple, r, bs);
        }

        /**
         * 处理字面量里的 {@code $}。
         *
         * @return {@code true} ＝ 已消费（{@code ${…}} 洞／{@code $id}／{@code $$}）；
         *         {@code false} ＝ 这个 {@code $} 只是普通字符
         */
        private boolean interpolation() {
            char next = peek(1);
            if (next == '{') {
                pos += 2;
                code(true); // 洞内按代码扫描；code() 自己维护 line
                return true;
            }
            if (next == '$') {
                pos += 2;
                return true;
            }
            if (Character.isJavaIdentifierStart(next)) {
                pos++;
                while (pos < n && Character.isJavaIdentifierPart(text.charAt(pos))) {
                    pos++;
                }
                return true;
            }
            return false;
        }

        private void add(int atLine, int offset, Kind kind, Prefix prefix, boolean triple, String raw,
                         boolean valueBackslash) {
            out.add(new Lit(file, atLine, offset, kind, prefix, triple, raw, valueBackslash,
                    SQL_KEYWORD.matcher(raw).find()));
        }

        /** 紧贴引号之前的插值器前缀（只认 {@code s}/{@code f}/{@code raw}，其余当普通字符串） */
        private Prefix prefixBefore(int quoteAt) {
            int j = quoteAt;
            while (j > 0 && isIdentPart(text.charAt(j - 1))) {
                j--;
            }
            String p = text.substring(j, quoteAt);
            return switch (p) {
                case "s" -> Prefix.S;
                case "f" -> Prefix.F;
                case "raw" -> Prefix.RAW;
                default -> Prefix.PLAIN;
            };
        }

        private int runLength(char ch) {
            int k = 0;
            while (pos + k < n && text.charAt(pos + k) == ch) {
                k++;
            }
            return k;
        }

        private char peek(int ahead) {
            return pos + ahead < n ? text.charAt(pos + ahead) : '\0';
        }

        private boolean startsWith(int at, String token) {
            return text.startsWith(token, at);
        }
    }

    /** 「源码形态里有连续两个反斜杠」（单行字符串／字符字面量的口径） */
    private static boolean doubledBackslash(String raw) {
        return raw.contains("\\\\");
    }

    private static boolean isIdentPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    private static Pattern compileKeywords() {
        List<String> sorted = SQL_KEYWORD_TOKENS.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .toList();
        return Pattern.compile("(?i)\\b(?:" + String.join("|", sorted) + ")\\b");
    }
}
