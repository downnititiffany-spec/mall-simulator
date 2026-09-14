package com.graduation.analytics.warehouse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 库名字面量门禁的**扫描器本体**（V25-T01）。
 *
 * <p>从 {@code WarehouseNameLiteralGateTest} 里抽出来的原因：把「收集哪些文件」「怎么判违规」
 * 与「断言」分开，负例就能走**同一条**端到端路径（收集 → 定范围 → 词法剥离 → 逐行匹配），
 * 而不是另写一段正则说明来冒充证据。</p>
 *
 * <h2>为什么要有词法注释剥离（V25-T01 的真实缺陷）</h2>
 * <p>原实现逐行跑 {@link #BARE_LITERAL}／{@link #DYNAMIC_PREFIX}，**不区分注释与代码**。于是
 * {@code spark-jobs/.../job/TradeDwdJob.scala:145} 与 {@code spark-jobs/.../sql/SurrogateKey.scala:160}
 * 这两行 scaladoc 里引用的历史报错原文（含 {@code dw_dwd.dwd_order_detail}）被判成「第二处所有者」，
 * 门禁自 2026-09-12 21:46（{@code 959626e}）起即红。这是**误命中**：注释不派生库名、不执行 SQL。</p>
 *
 * <p>修法按指导书 §9.5：**以词法方式排除注释**，而不是删掉那两行注释（删数据变绿会同时毁掉
 * 「注释里的历史原文」与「门禁对注释仍然敏感」两件事）。剥离只把**注释区间**的字符换成空格：
 * 行数与其余内容逐字不动 ⇒ 行号仍可直接引用；字符串字面量**内容一律保留** ⇒ 写在
 * SQL 字符串里的裸库名照旧报红。</p>
 *
 * <p><b>不跳过任何文件</b>：扫描范围、范围外的所有者清单、文件数下限断言都原样保留；
 * 词法表对未知扩展名是 **fail-closed**（抛异常），新加一种文本扩展名必须同时声明注释语法，
 * 否则门禁直接红，不会静默放宽。</p>
 *
 * <p><b>已知边界（不夸大）</b>：单行字符串只在本行内闭合，未闭合就按普通字符处理（宁可多报）；
 * 不处理 here-string/heredoc（PS1 的 {@code @"..."@}、shell 的 {@code <<EOF}）；{@code txt}/{@code json}
 * 声明为无注释语法（一律当代码扫）。这些边界的共同方向都是**偏严**（不隐藏），不是偏松。</p>
 */
final class WarehouseNameLiteralScanner {

    /** 允许出现库名字面量的文件（唯一所有者本体，只此两处） */
    static final Set<String> OWNER_FILES = Set.of(
            "analytics-server/platform-common/src/main/java/com/graduation/analytics/warehouse/WarehouseNamespace.java",
            "spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala");

    /** 裸库名：dw_ods / dw_dwd / dw_dim / dw_dws / dw_ads */
    static final Pattern BARE_LITERAL = Pattern.compile("dw_(?:ods|dwd|dim|dws|ads)\\b");
    /** 在代码里拼前缀：dw_$layer（Scala/PS 插值）或 "dw_" + x（Java/Scala 拼接） */
    static final Pattern DYNAMIC_PREFIX = Pattern.compile("dw_\\$|\"dw_\"\\s*\\+");

    /** 扫描范围（相对仓根，正斜杠结尾表示目录前缀） */
    static final List<String> SCOPES = List.of("spark-jobs/src/main/", "warehouse/ddl/", "scripts/");
    static final Pattern SERVER_MAIN = Pattern.compile("analytics-server/[^/]+/src/main/.*");

    /** 需要逐行检查的文本类文件（未列出的扩展名（如 .js）按原样不进扫描范围，本类不改这个口径） */
    static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "scala", "sql", "yml", "yaml", "properties", "json", "xml", "conf", "ps1", "sh", "txt", "md");

    /** 构建产物与已忽略目录：不参与扫描（static 是前端构建产物，landing 是数据） */
    static final Set<String> SKIP_DIRS = Set.of("target", "node_modules", ".git", "landing", "static");

    /** 扩展名 → 注释语法（只影响「什么算注释」，不影响「哪些文件被扫」） */
    private static final Map<String, CommentSyntax> SYNTAX = new LinkedHashMap<>();

    static {
        for (String e : List.of("java", "scala", "js")) {
            SYNTAX.put(e, CommentSyntax.SLASH);
        }
        SYNTAX.put("sql", CommentSyntax.DASH);
        for (String e : List.of("yml", "yaml", "properties", "conf", "sh")) {
            SYNTAX.put(e, CommentSyntax.HASH);
        }
        SYNTAX.put("ps1", CommentSyntax.POWERSHELL);
        SYNTAX.put("xml", CommentSyntax.XML);
        SYNTAX.put("md", CommentSyntax.XML);
        SYNTAX.put("json", CommentSyntax.NONE);
        SYNTAX.put("txt", CommentSyntax.NONE);
    }

    private WarehouseNameLiteralScanner() {
    }

    /** 一条命中：文件（相对仓根，正斜杠）、行号、该行原文。 */
    record Hit(String file, int line, String text) {
        @Override
        public String toString() {
            return file + ":" + line + " → " + text.trim();
        }
    }

    /**
     * 扫描结果。
     *
     * @param scanned     进扫描范围且是文本类的文件（逐个都读了，未被词法剥离跳过）
     * @param rawHits     逐行匹配**原文**的命中（= 旧门禁的口径，用于见证「不是靠删数据变绿」）
     * @param codeHits    逐行匹配**词法剥离注释后**的命中（= 现行门禁口径，必须为空）
     * @param commentOnly rawHits 里被词法剥离掉的（即仅出现在注释里的命中）
     */
    record Result(Set<Path> scanned, List<Hit> rawHits, List<Hit> codeHits, List<Hit> commentOnly) {
    }

    /** 端到端扫描 {root}（真实仓根与临时夹具目录走同一条代码路径）。 */
    static Result scan(Path root) {
        Set<Path> scanned = collect(root);
        List<Hit> raw = new ArrayList<>();
        List<Hit> code = new ArrayList<>();
        List<Hit> commentOnly = new ArrayList<>();

        for (Path file : scanned) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            if (OWNER_FILES.contains(rel)) {
                continue;
            }
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException("读取失败: " + rel, e);
            }
            CommentSyntax syntax = syntaxOf(rel);
            List<String> rawLines = lines(text);
            List<String> codeLines = lines(CommentSyntax.strip(text, syntax));
            for (int i = 0; i < rawLines.size(); i++) {
                String rawLine = rawLines.get(i);
                String codeLine = i < codeLines.size() ? codeLines.get(i) : "";
                if (matches(rawLine)) {
                    Hit hit = new Hit(rel, i + 1, rawLine);
                    raw.add(hit);
                    if (!matches(codeLine)) {
                        commentOnly.add(hit);
                    }
                }
                if (matches(codeLine)) {
                    code.add(new Hit(rel, i + 1, rawLine));
                }
            }
        }
        return new Result(scanned, raw, code, commentOnly);
    }

    /** 门禁判据本身：一行里出现裸库名或前缀拼接即违规。 */
    static boolean matches(String line) {
        return BARE_LITERAL.matcher(line).find() || DYNAMIC_PREFIX.matcher(line).find();
    }

    /** 扩展名 → 注释语法；未声明即抛（fail-closed：新增文本扩展名必须显式登记语法）。 */
    static CommentSyntax syntaxOf(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return Optional.ofNullable(SYNTAX.get(ext)).orElseThrow(() -> new IllegalStateException(
                "扫描器未声明扩展名 ." + ext + " 的注释语法（fail-closed）：" + relativePath
                        + " —— 请在 WarehouseNameLiteralScanner.SYNTAX 里登记（无注释语法写 NONE）"));
    }

    private static List<String> lines(String text) {
        // 不 trim、不去空行：行号必须与源文件一致（词法剥离保留了所有 '\n'）
        return List.of(text.split("\n", -1));
    }

    // ── 文件收集（口径与原门禁逐字一致） ───────────────────────────────────

    static Set<Path> collect(Path root) {
        Set<Path> out = new LinkedHashSet<>();
        walk(root.resolve("spark-jobs"), root, out);
        walk(root.resolve("analytics-server"), root, out);
        walk(root.resolve("warehouse"), root, out);
        walk(root.resolve("scripts"), root, out);
        return out;
    }

    private static void walk(Path start, Path root, Set<Path> out) {
        if (!Files.isDirectory(start)) {
            return;
        }
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(start) && SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (inScope(root.relativize(file)) && isText(file)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("遍历失败: " + start, e);
        }
    }

    private static boolean inScope(Path relative) {
        String rel = relative.toString().replace('\\', '/');
        for (String scope : SCOPES) {
            if (rel.startsWith(scope)) {
                return true;
            }
        }
        return SERVER_MAIN.matcher(rel).matches();
    }

    private static boolean isText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 供测试断言：扫描范围声明与注释语法表的覆盖关系。 */
    static Set<String> declaredSyntaxExtensions() {
        return Set.copyOf(SYNTAX.keySet());
    }

    // ── 词法剥离 ───────────────────────────────────────────────────────────

    /** 注释语法族。只声明「什么算注释」；字符串内容一律保留。 */
    enum CommentSyntax {
        /** 无注释语法（json/text）：原样，一律按代码扫。 */
        NONE,
        /** {@code //} 与 {@code /* *}{@code /}（Java/Scala/JS）。 */
        SLASH,
        /** {@code --} 与 {@code /* *}{@code /}（SQL/Hive）。 */
        DASH,
        /** {@code #} 行注释（yml/properties/conf/sh）。 */
        HASH,
        /** PS1：{@code #} 行注释 ＋ {@code <# #>} 块注释。 */
        POWERSHELL,
        /** {@code <!-- -->}（xml/md）。 */
        XML;

        /** 把注释区间的字符替换成空格；行结构（{@code \n}）与字符串内容逐字不动。 */
        static String strip(String text, CommentSyntax syntax) {
            if (syntax == CommentSyntax.NONE || text.isEmpty()) {
                return text;
            }
            char[] c = text.toCharArray();
            int n = c.length;
            int i = 0;
            while (i < n) {
                char ch = c[i];
                // 1) 字符串字面量：整段保留（内容不剥离 ⇒ SQL 里的裸库名照旧报红）
                if (ch == '"' || ch == '\'') {
                    int next = skipString(c, i, syntax);
                    if (next > i) {
                        i = next;
                        continue;
                    }
                }
                // 2) 注释起止（先判行注释，再判块注释；都在字符串分支之后，故字符串里的记号不误判）
                if (startsLineComment(c, i, syntax)) {
                    i = blankToLineEnd(c, i);
                    continue;
                }
                if ((syntax == CommentSyntax.SLASH || syntax == CommentSyntax.DASH) && startsWith(c, i, "/*")) {
                    i = blankTo(c, i, "*/");
                    continue;
                }
                if (syntax == CommentSyntax.XML && startsWith(c, i, "<!--")) {
                    i = blankTo(c, i, "-->");
                    continue;
                }
                if (syntax == CommentSyntax.POWERSHELL && startsWith(c, i, "<#")) {
                    i = blankTo(c, i, "#>");
                    continue;
                }
                i++;
            }
            return new String(c);
        }

        /** @return 该位置是否是一条行注释的开头 */
        private static boolean startsLineComment(char[] c, int at, CommentSyntax syntax) {
            return switch (syntax) {
                case SLASH -> startsWith(c, at, "//");
                case DASH -> startsWith(c, at, "--");
                case HASH, POWERSHELL -> c[at] == '#';
                default -> false;
            };
        }

        /** @return 字符串结束后的下标；未能（在本行内）闭合则返回 {@code start}（按普通字符处理，偏严） */
        private static int skipString(char[] c, int start, CommentSyntax syntax) {
            int n = c.length;
            char quote = c[start];
            // Java/Scala 文本块与 Scala 三引号字符串：可跨行，逐字保留到闭合三引号
            if (syntax == CommentSyntax.SLASH && quote == '"' && startsWith(c, start, "\"\"\"")) {
                int j = start + 3;
                while (j + 2 < n) {
                    if (c[j] == '"' && c[j + 1] == '"' && c[j + 2] == '"') {
                        return j + 3;
                    }
                    j++;
                }
                return start + 3;   // 未闭合：不吞掉后面，按代码继续扫（偏严）
            }
            int j = start + 1;
            while (j < n) {
                char x = c[j];
                if (x == '\\') {
                    j += 2;
                    continue;
                }
                if (x == '\n') {
                    return start;   // 单行字符串必须本行闭合，否则按普通字符
                }
                if (x == quote) {
                    return j + 1;
                }
                j++;
            }
            return start;
        }

        private static int blankToLineEnd(char[] c, int start) {
            int i = start;
            while (i < c.length && c[i] != '\n') {
                c[i] = ' ';
                i++;
            }
            return i;
        }

        private static int blankTo(char[] c, int start, String end) {
            int i = start;
            while (i < c.length) {
                if (startsWith(c, i, end)) {
                    for (int k = i; k < i + end.length(); k++) {
                        c[k] = ' ';
                    }
                    return i + end.length();
                }
                if (c[i] != '\n') {
                    c[i] = ' ';
                }
                i++;
            }
            return i;
        }

        private static boolean startsWith(char[] c, int at, String token) {
            if (at + token.length() > c.length) {
                return false;
            }
            for (int k = 0; k < token.length(); k++) {
                if (c[at + k] != token.charAt(k)) {
                    return false;
                }
            }
            return true;
        }
    }
}
