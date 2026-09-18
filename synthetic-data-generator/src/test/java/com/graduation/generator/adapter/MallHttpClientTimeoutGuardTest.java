package com.graduation.generator.adapter;

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
 * 设计 §3.3 <b>L85</b>「每个客户端有连接/请求超时、有限重试、幂等与错误映射」的<b>结构守卫</b>
 * （源码级，零连库、零外网、零生产改动）。
 *
 * <p><b>为什么需要它</b>：生成器到商城的两个出站客户端（{@link ReferenceMallHttpAdapter} 与
 * {@link SecondMallHttpAdapter}）<b>已经</b>在建连与单条请求上设了超时，但此前<b>没有任何测试钉住这件事</b>
 * （S3-46 实测：全仓测试树里对 {@code connectTimeout}/{@code timeout} 零断言）。于是"新加一个客户端忘了设超时"
 * 不会让任何用例变红 —— 而无超时的出站调用会无界等待，把一次对端故障放大成生成器挂死。
 * 本守卫把已实现的事实常驻钉住，并强制新客户端<b>显式登记</b>。</p>
 *
 * <p><b>判据是源码文本 ＋ 语句边界，不是语义证明</b>：每个站点取"从站点起到下一个 {@code ;}"的片段，
 * 片段里必须出现对应超时设置；注释先按字符扫描剥离（注释里的 {@code .timeout(} 不算数）。
 * 因此"把超时删掉/注释掉/换个写法"都会红，而<b>它并不证明超时值合适</b> —— 值是否合适需要真实商城（未测）。</p>
 *
 * <p><b>本守卫没有经典 RED</b>：被守的性质在写守卫之前就已成立（超时是既有实现）。非恒真性由 S3-46 的
 * 变异探针证明：删一处 {@code .timeout(} ⇒ ②红；新增一个无超时客户端 ⇒ ①③红；
 * 新增一个已带超时的新客户端 ⇒ 仅③红（集合漂移，证明③不是恒真）；把 {@code .timeout(} 注释掉 ⇒ ②红
 * （证明注释剥离生效）。</p>
 *
 * <p><b>覆盖边界（不得越界表述）</b>：只覆盖本模块 {@code src/main/java}；不含 {@code spark-jobs}、
 * {@code analytics-server}（平台侧 AI Provider 的请求级超时由 S3-44 的行为测试钉住）。
 * 本守卫<b>只</b>钉 L85 的"连接/请求超时"一项；L85 的「有限重试」「幂等」「错误映射」在商城客户端上
 * <b>尚未实现</b>（{@code MallOperationException} 只带操作名与文本，无分类码），属 S3-46 实测登记、
 * 由台账跟踪，<b>不得</b>因为本守卫变绿就声称 L85 已全项满足。</p>
 */
class MallHttpClientTimeoutGuardTest {

    /** Maven surefire 的工作目录是本模块根目录（同 {@code GeneratorBoundarySourcePolicyTest}） */
    private static final Path MODULE_ROOT = Path.of("").toAbsolutePath().normalize();

    /** 出站客户端构造站点：建连超时必须与它同语句 */
    private static final String CLIENT_SITE = "HttpClient.newBuilder()";

    /** 出站请求构造站点：单条请求超时必须与它同语句 */
    private static final String REQUEST_SITE = "HttpRequest.newBuilder(";

    private static final String CONNECT_TIMEOUT = ".connectTimeout(";
    private static final String REQUEST_TIMEOUT = ".timeout(";

    /** 超时值的唯一配置属主（S3-46 实测：main 树里恰好一处声明） */
    private static final String TIMEOUT_KEY = "generator.target.probe-timeout-ms";

    /** 超时值唯一属主所在文件（相对模块根） */
    private static final String TIMEOUT_OWNER_FILE = "src/main/java/com/graduation/generator/config/GeneratorBeans.java";

    /**
     * 已登记的商城客户端所有者：文件 ⇒ 站点数。ReferenceMall 在 Stage 7 Batch S 后将
     * 两个 HttpClient build 点合并成一个实例级客户端，因此当前共 7 个站点。
     *
     * <p>集合相等式判据：新增/删除任何客户端文件、或某文件里的站点数变化，都必须先在这里登记，
     * 否则守卫变红 —— 这就是"新客户端不得静默逃过超时纪律"的强制点。</p>
     */
    private static final Map<String, Integer> REGISTERED_SITES = registeredSites();

    /** 当前实测：ReferenceMall 1 个 + SecondMall 2 个 HttpClient build 点，共 3 个 */
    private static final int REGISTERED_CLIENT_SITES = 3;

    /** S3-46 实测：两家适配器里 HttpRequest.newBuilder( 站点共 4 个（各 2 个） */
    private static final int REGISTERED_REQUEST_SITES = 4;

    private static Map<String, Integer> registeredSites() {
        Map<String, Integer> sites = new LinkedHashMap<>();
        sites.put("src/main/java/com/graduation/generator/adapter/ReferenceMallHttpAdapter.java", 3);
        sites.put("src/main/java/com/graduation/generator/adapter/SecondMallHttpAdapter.java", 4);
        return Map.copyOf(sites);
    }

    @Test
    @DisplayName("每个 HttpClient.newBuilder() 站点都在同一语句里设了建连超时")
    void everyHttpClientIsBuiltWithConnectTimeout() {
        List<Path> sources = scanMainJava();
        assertThat(sources).as("生成器生产源码必须存在（守卫不可空跑）").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        int sites = 0;
        for (Path source : sources) {
            String text = stripComments(read(source));
            for (int index : indexesOf(text, CLIENT_SITE)) {
                sites++;
                if (!statementAt(text, index).contains(CONNECT_TIMEOUT)) {
                    offenders.add("%s 第 %d 行的 %s 未在同语句设 %s".formatted(
                            relative(source), lineOf(text, index), CLIENT_SITE, CONNECT_TIMEOUT));
                }
            }
        }

        assertThat(sites).as("必须真的扫到出站客户端站点（当前 3 个：ReferenceMall 1 + SecondMall 2）")
                .isEqualTo(REGISTERED_CLIENT_SITES);
        assertThat(offenders)
                .as("设计 §3.3 L85：每个客户端的建连必须有超时，禁止无界等待")
                .isEmpty();
    }

    @Test
    @DisplayName("每个 HttpRequest.newBuilder( 站点都在同一语句里设了请求超时")
    void everyHttpRequestSetsItsOwnTimeout() {
        List<Path> sources = scanMainJava();
        List<String> offenders = new ArrayList<>();
        int sites = 0;
        for (Path source : sources) {
            String text = stripComments(read(source));
            for (int index : indexesOf(text, REQUEST_SITE)) {
                sites++;
                if (!statementAt(text, index).contains(REQUEST_TIMEOUT)) {
                    offenders.add("%s 第 %d 行的 %s 未在同语句设 %s".formatted(
                            relative(source), lineOf(text, index), REQUEST_SITE, REQUEST_TIMEOUT));
                }
            }
        }

        assertThat(sites).as("必须真的扫到出站请求站点（S3-46 实测 4 个：两家适配器各 2 个）")
                .isEqualTo(REGISTERED_REQUEST_SITES);
        assertThat(offenders)
                .as("设计 §3.3 L85：每条出站请求必须有请求级超时（连接超时管不到已建连后的无界等待）")
                .isEmpty();
    }

    @Test
    @DisplayName("出站客户端站点集合恰好等于已登记的所有者集合（新客户端必须显式登记）")
    void clientSitesMatchRegisteredOwnerSet() {
        Map<String, Integer> actual = new TreeMap<>();
        for (Path source : scanMainJava()) {
            String text = stripComments(read(source));
            int count = indexesOf(text, CLIENT_SITE).size() + indexesOf(text, REQUEST_SITE).size();
            if (count > 0) {
                actual.put(relative(source), count);
            }
        }

        assertThat(actual)
                .as("S3-46 登记：商城客户端站点只有两家适配器；新增客户端必须先登记超时纪律")
                .containsExactlyInAnyOrderEntriesOf(new TreeMap<>(REGISTERED_SITES));
    }

    @Test
    @DisplayName("超时值只有一个配置属主，且两家适配器共用它")
    void timeoutValueHasASingleConfigurationOwner() {
        Map<String, Integer> owners = new LinkedHashMap<>();
        for (Path source : scanMainJava()) {
            int occurrences = countOf(read(source), TIMEOUT_KEY);
            if (occurrences > 0) {
                owners.put(relative(source), occurrences);
            }
        }

        assertThat(owners)
                .as("超时键只能有一处声明（两处钥匙＝两个属主，反熵纪律不允许）")
                .containsExactly(Map.entry(TIMEOUT_OWNER_FILE, 1));
        String beans = read(MODULE_ROOT.resolve(TIMEOUT_OWNER_FILE));
        assertThat(beans)
                .as("超时键的声明与默认值（改名或改默认值＝改既有配置语义，必须先登记/裁决）")
                .contains("@Value(\"${" + TIMEOUT_KEY + ":3000}\")");
        assertThat(beans)
                .as("唯一属主必须把同一个值交给两家适配器（不各自一套超时键）")
                .contains("new ReferenceMallHttpAdapter(")
                .contains("new SecondMallHttpAdapter(");
        assertThat(countOf(beans, "Duration.ofMillis("))
                .as("两家适配器各取一次同一个超时值")
                .isEqualTo(2);
    }

    // ── 扫描与文本工具 ────────────────────────────────────────────────────────

    private static List<Path> scanMainJava() {
        Path root = MODULE_ROOT.resolve("src/main/java");
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path path) {
        return MODULE_ROOT.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static List<Integer> indexesOf(String text, String needle) {
        List<Integer> found = new ArrayList<>();
        int index = text.indexOf(needle);
        while (index >= 0) {
            found.add(index);
            index = text.indexOf(needle, index + needle.length());
        }
        return found;
    }

    private static int countOf(String text, String needle) {
        return indexesOf(text, needle).size();
    }

    /** 站点所在的语句片段：从站点起到下一个分号（含跨行链式调用） */
    private static String statementAt(String text, int index) {
        int end = text.indexOf(';', index);
        return end < 0 ? text.substring(index) : text.substring(index, end);
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 按字符扫描剥离注释（行注释与块注释），<b>保留</b>字符串/字符/文本块里的内容与所有换行，
     * 并用等长空白替换注释字符 —— 这样剥离后按下标取行号仍然对得上原文。
     */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        boolean line = false;
        boolean block = false;
        boolean string = false;
        boolean character = false;
        boolean textBlock = false;
        while (i < n) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') {
                    line = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
                i++;
                continue;
            }
            if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    out.append("  ");
                    i += 2;
                } else {
                    out.append(c == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }
            if (textBlock) {
                if (c == '"' && next == '"' && i + 2 < n && text.charAt(i + 2) == '"') {
                    textBlock = false;
                    out.append("\"\"\"");
                    i += 3;
                } else {
                    out.append(c);
                    i++;
                }
                continue;
            }
            if (string || character) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(next);
                    i += 2;
                    continue;
                }
                if (string && c == '"') {
                    string = false;
                } else if (character && c == '\'') {
                    character = false;
                }
                i++;
                continue;
            }
            if (c == '/' && next == '/') {
                line = true;
                out.append("  ");
                i += 2;
                continue;
            }
            if (c == '/' && next == '*') {
                block = true;
                out.append("  ");
                i += 2;
                continue;
            }
            if (c == '"' && next == '"' && i + 2 < n && text.charAt(i + 2) == '"') {
                textBlock = true;
                out.append("\"\"\"");
                i += 3;
                continue;
            }
            if (c == '"') {
                string = true;
            } else if (c == '\'') {
                character = true;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
