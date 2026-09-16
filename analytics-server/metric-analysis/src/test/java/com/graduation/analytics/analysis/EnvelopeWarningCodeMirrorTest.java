package com.graduation.analytics.analysis;

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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨工程对账守卫：后端**告警码**（信封降级码）与前端**展示文案表**不得各自漂移（S3-39 / 台账 L443）。
 *
 * <p>背景（实测，2026-09-16）：跨树没有任何自动化对账——
 * <ul>
 *   <li>后端 owner：{@code AnalysisViewModel.WARN_*} 共 8 个常量（契约 §16.4：所有新错误码统一 owner ＝
 *       {@code AnalysisViewModel}），{@code docs/contracts/analysis-viewmodel-r7-4.md:182} 约定 {@code filters}
 *       等字段原样回显、前端据码展示中文文案；</li>
 *   <li>前端镜像：{@code web/src/utils/envelope.js} 的 {@code WARNING_TEXT}（8 键）＋
 *       {@code web/src/utils/context.js} 的 {@code WARNING_TEXT_EXTRA}（4 键，页面本地码）。</li>
 * </ul>
 * 两侧一旦不同步，页面会把**原始编码**直接打给用户（{@code warningText = (code) => WARNING_TEXT[code] || String(code)}），
 * 且不会有任何测试变红。本守卫把这条边界钉住：**新增信封码而不补文案 ⇒ 红**。
 *
 * <p>额外实测发现（同一轮登记，见 {@code docs/acceptance/s3-39-warning-code-cross-tree-guard-20260916/}）：
 * 「同一码值在后端多处各自声明」确实存在——{@code NO_ACTIVE_SNAPSHOT} 有 3 处
 * （{@code AnalysisViewModel}、{@code EvidencePackage}、{@code SqlPolicy}），另有 3 个码各 2 处。
 * 本守卫不擅自合并它们（跨模块引用属架构裁决），只把**当前形态钉死**：声明处数量变化 ⇒ 红，
 * 必须先决定单一属主或登记，详见 {@link #displayedCodesKeepRegisteredDeclarationSites()}。
 *
 * <p>边界（诚实记录，不假装覆盖）：只认「常量声明」形态（{@code public static final String NAME = "CODE";}）。
 * 若有人在 {@code warnings} 列表里直接写中划线字符串字面量，本守卫看不见——本轮已用字面量扫描确认
 * 信封 8 码在前端两个表之外的后端 main 源码中只以常量引用形态出现，没有裸字面量散落。
 */
class EnvelopeWarningCodeMirrorTest {

    /** 信封告警码的 owner（契约 §16.4）。 */
    private static final Path ENVELOPE_OWNER = RepoRoot.path(
            "analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/AnalysisViewModel.java");
    /** 前端信封码文案表（与 {@code AnalysisViewModel.WARN_*} 声称一一对应）。 */
    private static final Path ENVELOPE_TEXT_TABLE = RepoRoot.path("web/src/utils/envelope.js");
    /** 前端非信封接口的补全文案表（页面本地降级码）。 */
    private static final Path EXTRA_TEXT_TABLE = RepoRoot.path("web/src/utils/context.js");

    /** 与 {@code PlatformMallBoundarySourcePolicyTest} 同一份模块清单（按模块列目录，天然跳过 target/）。 */
    private static final List<String> MODULES = List.of(
            "platform-common", "connection-ingestion", "warehouse-pipeline",
            "metric-analysis", "ai-decision", "platform-app");

    private static final Pattern WARN_CONSTANT =
            Pattern.compile("public static final String (WARN_[A-Z0-9_]+)\\s*=\\s*\"([A-Z][A-Z0-9_]*)\"\\s*;");
    private static final Pattern CODE_CONSTANT =
            Pattern.compile("public static final String ([A-Z][A-Z0-9_]*)\\s*=\\s*\"([A-Z][A-Z0-9_]*)\"\\s*;");
    private static final Pattern TABLE_KEY = Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\s*:");

    @Test
    @DisplayName("信封告警码 owner 与前端 WARNING_TEXT 一一对应（新增码不补文案即红）")
    void envelopeOwnerCodesMatchFrontendWarningText() {
        Set<String> owner = warnCodes(read(ENVELOPE_OWNER));
        Set<String> mirror = tableKeys(read(ENVELOPE_TEXT_TABLE), "WARNING_TEXT");

        assertThat(owner).as("AnalysisViewModel 的 WARN_* 常量必须存在（守卫不可空跑）").isNotEmpty();
        assertThat(mirror).as("前端 WARNING_TEXT 表必须存在（守卫不可空跑）").isNotEmpty();
        assertThat(mirror)
                .as("前端 WARNING_TEXT 的键必须与 AnalysisViewModel.WARN_* 一一对应："
                        + "后端多一个 ⇒ 页面把原始编码打给用户；前端多一个 ⇒ 前端自造了后端不认的码（%s）", mirror)
                .containsExactlyInAnyOrderElementsOf(owner);
    }

    @Test
    @DisplayName("两张前端文案表互斥（同一码不得有两种文案）")
    void frontendWarningTablesAreDisjoint() {
        Set<String> envelope = tableKeys(read(ENVELOPE_TEXT_TABLE), "WARNING_TEXT");
        Set<String> extra = tableKeys(read(EXTRA_TEXT_TABLE), "WARNING_TEXT_EXTRA");

        assertThat(extra).as("前端 WARNING_TEXT_EXTRA 表必须存在（守卫不可空跑）").isNotEmpty();
        assertThat(intersection(envelope, extra))
                .as("信封码表与非信封码表不得重叠（重叠说明同一码有两个文案 owner）")
                .isEmpty();
    }

    /**
     * 「同一码值在后端多处各自声明」按当前实测形态钉死。
     *
     * <p>本用例是**已登记缺口的形态守卫**，不是"这是对的"的断言：声明处数量一旦变化（新增重复声明、
     * 或有人把重复收敛掉），本用例即红，逼迫先做裁决并同步 {@code EvidencePackage}/{@code SqlPolicy}
     * 的登记与前端文案，而不是悄悄扩散。
     *
     * <p>已知重复（实测 2026-09-16）：
     * <ul>
     *   <li>{@code NO_ACTIVE_SNAPSHOT} ×3：{@code AnalysisViewModel#WARN_NO_ACTIVE_SNAPSHOT}、
     *       {@code EvidencePackage#WARN_NO_ACTIVE_SNAPSHOT}（AI 证据包自持一份字面量）、
     *       {@code SqlPolicy#NO_ACTIVE_SNAPSHOT}（AI SQL 作用域错误码，无 WARN_ 前缀）；</li>
     *   <li>{@code UNKNOWN_SNAPSHOT}／{@code UNKNOWN_DIMENSION_TABLE}／{@code QUALITY_STATUS_UNAVAILABLE}
     *       各 ×2：{@code AnalysisViewModel} 与 {@code EvidencePackage}；</li>
     *   <li>{@code QUALITY_RULE_FAILED} ×1：前端本地码值与后端 {@code AnomalyRules#RULE_QUALITY_RULE_FAILED}
     *       （AI 异常候选规则码）同名，**语义是否同指未经裁决**，此处只钉声明处数量。</li>
     * </ul>
     */
    @Test
    @DisplayName("展示侧码值的声明处数量＝已登记形态（多一处/少一处即红）")
    void displayedCodesKeepRegisteredDeclarationSites() {
        Map<String, String> sources = backendMainSources();
        assertThat(sources.size()).as("后端 main 源码扫描面不得为空").isGreaterThan(100);

        Map<String, Set<String>> sites = indexDeclarations(sources);

        Set<String> displayed = new TreeSet<>(tableKeys(read(ENVELOPE_TEXT_TABLE), "WARNING_TEXT"));
        displayed.addAll(tableKeys(read(EXTRA_TEXT_TABLE), "WARNING_TEXT_EXTRA"));

        Map<String, Integer> expected = new TreeMap<>();
        expected.put("NO_ACTIVE_SNAPSHOT", 3);
        expected.put("UNKNOWN_SNAPSHOT", 2);
        expected.put("UNKNOWN_DIMENSION_TABLE", 2);
        expected.put("QUALITY_STATUS_UNAVAILABLE", 2);
        expected.put("RFM_AMOUNT_UNAVAILABLE", 1);
        expected.put("RFM_RAW_VALUES_UNAVAILABLE", 1);
        expected.put("RFM_PERIOD_UNAVAILABLE", 1);
        expected.put("MULTIPLE_RULE_VERSIONS", 1);
        expected.put("QUALITY_RULE_FAILED", 1);
        expected.put("ENVELOPE_MISSING", 0);
        expected.put("AI_EVIDENCE_PARTIAL", 0);
        expected.put("NO_SNAPSHOT_SELECTED", 0);

        assertThat(displayed).as("展示侧码值集合必须与登记一致").containsExactlyInAnyOrderElementsOf(expected.keySet());

        Map<String, Integer> actual = new TreeMap<>();
        displayed.forEach(code -> actual.put(code, sites.getOrDefault(code, Set.of()).size()));

        assertThat(actual)
                .as("展示侧码值的后端声明处数量变化 ⇒ 先决定单一属主或登记，再改本守卫与前端文案；实测声明处：%s",
                        describe(sites, displayed))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("守卫自身有牙：新增码/漏键/重复声明都被解析器抓到")
    void extractorsHaveTeeth() {
        // ① owner 侧新增一个 WARN_* 常量 ⇒ 解析结果多一个码（T1 会红）
        String ownerWithExtra = """
                public static final String WARN_NO_ACTIVE_SNAPSHOT = "NO_ACTIVE_SNAPSHOT";
                public static final String WARN_BRAND_NEW = "BRAND_NEW";
                """;
        assertThat(warnCodes(ownerWithExtra)).containsExactly("BRAND_NEW", "NO_ACTIVE_SNAPSHOT");

        // ② 非 WARN_ 前缀的同名声明不被 T1 误当成信封码（正是 SqlPolicy 的形态）
        assertThat(warnCodes("public static final String NO_ACTIVE_SNAPSHOT = \"NO_ACTIVE_SNAPSHOT\";")).isEmpty();

        // ③ 前端表：漏键/多键都能被数出来，且不会把值当键
        String table = """
                export const WARNING_TEXT = {
                  NO_ACTIVE_SNAPSHOT: '当前没有已发布快照',
                  UNKNOWN_SNAPSHOT: '快照不存在',
                }
                """;
        assertThat(tableKeys(table, "WARNING_TEXT")).containsExactly("NO_ACTIVE_SNAPSHOT", "UNKNOWN_SNAPSHOT");
        // 表名找不到时必须显式失败（不得静默返回空集把守卫变成空跑）
        assertThat(catchThrowable(() -> tableKeys(table, "WARNING_TEXT_EXTRA"))).isNotNull();

        // ④ 重复声明索引：同码两处 ⇒ 两处；不同码不互相污染
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("A.java", "public static final String WARN_X = \"DUP\";");
        sources.put("B.java", "public static final String X = \"DUP\";");
        sources.put("C.java", "public static final String Y = \"OTHER\";");
        Map<String, Set<String>> sites = indexDeclarations(sources);
        assertThat(sites.get("DUP")).containsExactlyInAnyOrder("A.java#WARN_X", "B.java#X");
        assertThat(sites.get("OTHER")).containsExactly("C.java#Y");
    }

    // ---------- 解析（纯文本入参，便于自检） ----------

    private static Set<String> warnCodes(String source) {
        Set<String> codes = new TreeSet<>();
        Matcher matcher = WARN_CONSTANT.matcher(source);
        while (matcher.find()) {
            codes.add(matcher.group(2));
        }
        return codes;
    }

    private static Set<String> tableKeys(String source, String tableName) {
        Matcher block = Pattern
                .compile("(?:export )?const " + Pattern.quote(tableName) + " = \\{([^}]*)\\}")
                .matcher(source);
        if (!block.find()) {
            throw new IllegalStateException("前端找不到表 " + tableName + "：守卫不得静默跳过");
        }
        Set<String> keys = new TreeSet<>();
        for (String line : block.group(1).split("\n")) {
            Matcher key = TABLE_KEY.matcher(line);
            if (key.find()) {
                keys.add(key.group(1));
            }
        }
        return keys;
    }

    /** 码值 → {「文件名#常量名」}，只认常量声明形态。 */
    private static Map<String, Set<String>> indexDeclarations(Map<String, String> fileKeyToSource) {
        Map<String, Set<String>> sites = new TreeMap<>();
        fileKeyToSource.forEach((fileKey, source) -> {
            String fileName = fileKey.substring(fileKey.lastIndexOf('/') + 1);
            Matcher matcher = CODE_CONSTANT.matcher(source);
            while (matcher.find()) {
                sites.computeIfAbsent(matcher.group(2), code -> new TreeSet<>())
                        .add(fileName + "#" + matcher.group(1));
            }
        });
        return sites;
    }

    /**
     * 后端 main 源码：键＝{@code 模块/文件名}。
     *
     * <p>键里带模块名（而不是只用文件名）是为了让「同名文件互相覆盖 ⇒ 扫描面悄悄缩水」不可能发生：
     * 同名文件仍然不同键，且重复键会显式抛错而不是静默丢弃。当前实测 203 个文件、无跨模块重名。
     */
    private static Map<String, String> backendMainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String module : MODULES) {
            Path dir = RepoRoot.path("analytics-server").resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(path -> sources.merge(module + "/" + path.getFileName(), read(path), (left, right) -> {
                            throw new IllegalStateException("源文件键重复：" + module + "/" + path.getFileName());
                        }));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sources;
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> both = new TreeSet<>(left);
        both.retainAll(right);
        return both;
    }

    private static String describe(Map<String, Set<String>> sites, Set<String> codes) {
        List<String> lines = new ArrayList<>();
        codes.forEach(code -> lines.add(code + "=" + sites.getOrDefault(code, Set.of())));
        return String.join(", ", lines);
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
