package com.graduation.analytics.ai.llm;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台出站客户端「连接/请求超时」结构守卫（S3-47，设计 L85「每个客户端有连接/请求超时」覆盖面收口）。
 *
 * <p><b>被守性质（改前实测，HEAD {@code dfef8a7}）</b>：{@code analytics-server} main 树
 * **只有 1 个**出站 HTTP 客户端构造点 —— {@code OpenAiCompatLlmProvider} 的
 * {@code RestClient.builder()}（L54），该站点**同语句**挂了
 * {@code .requestFactory(requestFactory(timeoutMs))}（L56），工厂内
 * {@code setConnectTimeout}/{@code setReadTimeout} **同源**设置（L87-88）；超时值来自唯一注入点
 * {@code @Value("${llm.timeout-ms:30000}")}（L50，键在 {@code application.yml} **未声明** ⇒
 * 取值＝内联默认 30000）。</p>
 *
 * <p><b>为什么需要本守卫</b>：S3-44 已用**行为**用例钉住**这一个**客户端自己的超时；但没有任何用例
 * 钉住「平台里**每个**出站客户端都带超时」这一**形态** ⇒ 将来**新加**一个不带超时的出站客户端
 * （或把 {@code .requestFactory(...)} 摘掉而行为用例恰好不覆盖的形态）**不会**让任何用例变红
 * （S3-46 同款缺口：改前 analytics-server 测试树对 {@code setConnectTimeout}/{@code setReadTimeout}/
 * {@code SimpleClientHttpRequestFactory} **零断言**）。</p>
 *
 * <p><b>判据是源码文本 + 语句边界（到下一个 {@code ;}）+ 注释剥离，不是语义证明</b>；本守卫只钉
 * 「超时**被接上**」，**不证明** 30000ms 合适（无真实供应商可测）。</p>
 *
 * <p><b>覆盖边界</b>：只扫 {@code analytics-server/<模块>/src/main/java}（实测 **203** 个 Java 文件），
 * **不含** 各模块测试树、{@code spark-jobs}（Scala/JDK8）、{@code mall-simulator}、
 * {@code synthetic-data-generator}（生成器侧商城客户端由 S3-46 的
 * {@code MallHttpClientTimeoutGuardTest} 覆盖）。</p>
 */
class PlatformOutboundClientTimeoutGuardTest {

    /** 站点形态 → 该形态**必须**出现在同一语句里的超时接线标记（新增形态必须显式登记）。 */
    private static final Map<String, String> CLIENT_KINDS = new LinkedHashMap<>();

    static {
        CLIENT_KINDS.put("RestClient.builder()", ".requestFactory(");
        CLIENT_KINDS.put("RestClient.create(", ".requestFactory(");
        CLIENT_KINDS.put("HttpClient.newBuilder()", ".connectTimeout(");
        CLIENT_KINDS.put("WebClient.builder()", ".clientConnector(");
    }

    /** 改前实测（HEAD dfef8a7）：平台侧出站客户端站点**恰好 1 个**。 */
    private static final int REGISTERED_CLIENT_SITES = 1;

    /** 改前实测：请求工厂构造点**恰好 1 个**。 */
    private static final int REGISTERED_FACTORY_SITES = 1;

    /** 改前实测：{@code analytics-server} main 树 Java 文件 **203** 个（扫描规模下限防「扫不到也能过」）。 */
    private static final int SCAN_FILE_FLOOR = 150;

    private static final String LLM_PACKAGE_DIR =
            "analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/llm/";
    private static final String PROVIDER_FILE = LLM_PACKAGE_DIR + "OpenAiCompatLlmProvider.java";

    private static final String FACTORY_SITE = "new SimpleClientHttpRequestFactory()";
    private static final String CONNECT_SETTER = "setConnectTimeout(";
    private static final String READ_SETTER = "setReadTimeout(";
    private static final String TIMEOUT_KEY = "llm.timeout-ms";
    private static final String DEFAULT_CONSTANT = "DEFAULT_TIMEOUT_MS";

    private static final Pattern KEY_ANNOTATION = Pattern.compile(
            "@Value\\(\"\\$\\{" + Pattern.quote(TIMEOUT_KEY) + ":(\\d+)\\}\"\\)\\s+long\\s+(\\w+)");
    private static final Pattern DEFAULT_DECLARATION = Pattern.compile(
            "(?:static\\s+final\\s+long|final\\s+static\\s+long)\\s+" + DEFAULT_CONSTANT + "\\s*=\\s*([\\d_]+)L");

    // ── 扫描工具 ────────────────────────────────────────────────────────────

    private static List<Path> mainJavaFiles() {
        Path root = RepoRoot.path("analytics-server");
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(PlatformOutboundClientTimeoutGuardTest::isMainJava)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isMainJava(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.endsWith(".java") && s.contains("/src/main/java/");
    }

    private static String relative(Path p) {
        return RepoRoot.path().relativize(p).toString().replace('\\', '/');
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Integer> indexesOf(String haystack, String needle) {
        List<Integer> out = new ArrayList<>();
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            out.add(i);
        }
        return out;
    }

    private static int countOf(String haystack, String needle) {
        return indexesOf(haystack, needle).size();
    }

    /** 站点 → 该站点所在**语句**（到下一个 {@code ;} 为止，含分号）。 */
    private static String statementAt(String text, int siteIndex) {
        int end = text.indexOf(';', siteIndex);
        assertThat(end)
                .as("站点后必须能找到语句结束符 ';'（站点偏移 %d）", siteIndex)
                .isGreaterThan(siteIndex);
        return text.substring(siteIndex, end + 1);
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 剥离注释：按字符扫描处理 {@code //}、{@code /* *}{@code /}，并识别字符串/字符/文本块，
     * 用**等长空白**替换注释内容（行号与偏移仍与原文件对齐）。
     */
    static String stripComments(String src) {
        char[] out = src.toCharArray();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' && src.startsWith("\"\"\"", i)) {
                int j = i + 3;
                while (j < n && !src.startsWith("\"\"\"", j)) {
                    j++;
                }
                i = Math.min(n, j + 3);
            } else if (c == '"' || c == '\'') {
                char quote = c;
                int j = i + 1;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == quote || d == '\n') {
                        break;
                    }
                    j++;
                }
                i = j + 1;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && src.charAt(j) != '\n') {
                    if (out[j] != '\n') {
                        out[j] = ' ';
                    }
                    j++;
                }
                i = j;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int j = i;
                while (j < n && !(src.charAt(j) == '*' && j + 1 < n && src.charAt(j + 1) == '/')) {
                    if (out[j] != '\n') {
                        out[j] = ' ';
                    }
                    j++;
                }
                int stop = Math.min(n, j + 2);
                for (int k = j; k < stop; k++) {
                    if (out[k] != '\n') {
                        out[k] = ' ';
                    }
                }
                i = stop;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /**
     * 站点所在的**外层方法**（文本判据）：向上找最近的 4 空格缩进方法签名行，向下到第一个 4 空格
     * 缩进的 {@code }} 收尾。方法体换了缩进风格即红 —— 这是**文本判据的已知代价**（防恒真的必要代价）。
     */
    static String enclosingMethod(String stripped, int siteIndex) {
        List<String> lines = List.of(stripped.split("\n", -1));
        int siteLine = lineOf(stripped, siteIndex);
        int start = -1;
        for (int i = siteLine - 2; i >= 0; i--) {
            if (lines.get(i).stripTrailing()
                    .matches("^    (?:public |private |protected )?(?:static )?(?:final )?"
                            + "[A-Za-z_][\\w<>,.\\[\\] ]*\\s+[A-Za-z_]\\w*\\(.*\\)\\s*\\{$")) {
                start = i;
                break;
            }
        }
        assertThat(start)
                .as("工厂站点前必须能找到 4 空格缩进的方法签名（站点在第 %d 行）", siteLine)
                .isNotNegative();
        StringBuilder body = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i).stripTrailing();
            body.append(line).append('\n');
            if (i > start && line.equals("    }")) {
                break;
            }
        }
        return body.toString();
    }

    // ── 断言 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("①每个出站客户端站点：同语句必须挂上该形态的超时接线（新形态必须显式登记）")
    void everyOutboundClientSiteCarriesItsTimeoutWiring() {
        List<Path> files = mainJavaFiles();
        assertThat(files.size())
                .as("扫描规模下限（改前实测 203 个 main Java 文件）——「扫不到也能过」不成立")
                .isGreaterThan(SCAN_FILE_FLOOR);

        Map<String, List<String>> sitesByKind = new TreeMap<>();
        List<String> violations = new ArrayList<>();
        int totalSites = 0;

        for (Path file : files) {
            String text = stripComments(read(file));
            for (Map.Entry<String, String> kind : CLIENT_KINDS.entrySet()) {
                for (int idx : indexesOf(text, kind.getKey())) {
                    totalSites++;
                    String where = relative(file) + ":" + lineOf(text, idx);
                    sitesByKind.computeIfAbsent(kind.getKey(), k -> new ArrayList<>()).add(where);
                    String statement = statementAt(text, idx);
                    if (!statement.contains(kind.getValue())) {
                        violations.add(where + " 缺少 " + kind.getValue() + " ⇒ " + statement.strip());
                    }
                }
            }
        }

        assertThat(violations)
                .as("每个出站客户端站点都必须在同一语句里接上超时（实测站点：%s）", sitesByKind)
                .isEmpty();
        assertThat(totalSites)
                .as("改前实测平台侧出站客户端站点恰好 %d 个（登记于本文件常量）；新增/删除必须显式登记",
                        REGISTERED_CLIENT_SITES)
                .isEqualTo(REGISTERED_CLIENT_SITES);
    }

    @Test
    @DisplayName("②站点所有者集合必须恰好等于已登记集合（集合相等，多一处即红、少一处亦红）")
    void clientSitesMatchRegisteredOwnerSet() {
        Map<String, Integer> actual = new TreeMap<>();
        for (Path file : mainJavaFiles()) {
            String text = stripComments(read(file));
            int count = 0;
            for (String marker : CLIENT_KINDS.keySet()) {
                count += countOf(text, marker);
            }
            if (count > 0) {
                actual.put(relative(file), count);
            }
        }

        Map<String, Integer> expected = new TreeMap<>();
        expected.put(PROVIDER_FILE, REGISTERED_CLIENT_SITES);

        assertThat(actual)
                .as("平台侧出站客户端构造点集合（改前实测：唯一一处 %s）", PROVIDER_FILE)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("③请求工厂必须连接与读取同源设置（只设一个仍可能在另一阶段无限等待）")
    void requestFactorySetsConnectAndReadTimeoutTogether() {
        List<String> factorySites = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        for (Path file : mainJavaFiles()) {
            String text = stripComments(read(file));
            for (int idx : indexesOf(text, FACTORY_SITE)) {
                String where = relative(file) + ":" + lineOf(text, idx);
                factorySites.add(where);
                String method = enclosingMethod(text, idx);
                if (!method.contains(CONNECT_SETTER)) {
                    violations.add(where + " 所在方法缺少连接超时设置 " + CONNECT_SETTER);
                }
                if (!method.contains(READ_SETTER)) {
                    violations.add(where + " 所在方法缺少读取超时设置 " + READ_SETTER);
                }
            }
        }

        assertThat(violations)
                .as("工厂站点：%s", factorySites)
                .isEmpty();
        assertThat(factorySites)
                .as("改前实测请求工厂构造点恰好 %d 个", REGISTERED_FACTORY_SITES)
                .hasSize(REGISTERED_FACTORY_SITES);
        assertThat(factorySites.get(0))
                .as("唯一工厂所有者（登记；只 pin 所有者**文件**，不 pin 行号）")
                .startsWith(PROVIDER_FILE + ":");
    }

    @Test
    @DisplayName("④超时值必须来自唯一注入键、且内联默认与 DEFAULT_TIMEOUT_MS 同值（客户端不得写字面量）")
    void timeoutComesFromASingleConfiguredKeyWithAPinnedDefault() {
        List<Path> files = mainJavaFiles();

        List<String> annotations = new ArrayList<>();
        String boundParameter = null;
        long inlineDefault = -1;
        long constantDefault = -1;
        String constantOwner = null;

        for (Path file : files) {
            String text = stripComments(read(file));
            Matcher key = KEY_ANNOTATION.matcher(text);
            while (key.find()) {
                annotations.add(relative(file));
                inlineDefault = Long.parseLong(key.group(1));
                boundParameter = key.group(2);
            }
            Matcher decl = DEFAULT_DECLARATION.matcher(text);
            while (decl.find()) {
                constantDefault = Long.parseLong(decl.group(1).replace("_", ""));
                constantOwner = relative(file);
            }
        }

        assertThat(annotations)
                .as("超时键 %s 的注入点必须唯一（改前实测 1 处：%s）", TIMEOUT_KEY, PROVIDER_FILE)
                .containsExactly(PROVIDER_FILE);
        assertThat(boundParameter).as("注解必须绑定一个 long 参数").isNotNull();
        assertThat(constantOwner)
                .as("%s 的声明必须唯一", DEFAULT_CONSTANT)
                .isEqualTo(PROVIDER_FILE);
        assertThat(inlineDefault)
                .as("内联默认（%s:%d）与 %s（%d）必须同值 —— 同一个数值不得有两个互相漂移的声明",
                        TIMEOUT_KEY, inlineDefault, DEFAULT_CONSTANT, constantDefault)
                .isEqualTo(constantDefault)
                .isEqualTo(30_000L);

        Path provider = RepoRoot.path(PROVIDER_FILE);
        String providerText = stripComments(read(provider));
        String clientStatement = statementAt(providerText, providerText.indexOf("RestClient.builder()"));
        assertThat(clientStatement)
                .as("客户端语句必须把**注入的配置值**交给工厂，而不是字面量")
                .contains("requestFactory(" + boundParameter + ")");
    }
}
