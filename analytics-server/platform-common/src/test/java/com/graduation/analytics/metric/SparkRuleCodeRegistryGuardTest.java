package com.graduation.analytics.metric;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则码跨模块漂移门禁（S3-49）：{@code spark-jobs} 生产源码里的规则码字面量
 * ↔ Java 侧唯一登记表 {@link RuleSeverity#REGISTERED}（经 {@link RuleSeverity#registeredCodes()} 只读暴露）。
 *
 * <h2>本门禁补的是哪个缺口</h2>
 * <p>{@code PROJECT_STATUS.md} backlog 行原文：「**「Spark 规则码字面量 ↔ 登记集」没有自动守卫**」
 * —— S3-10 的 {@code ADS_DWS_FUNNEL_RATE_RECONCILE} 只落 Spark、未登记进读侧登记表，
 * 读侧会按 §7.3.1 判「未登记规则」而**拒发**，当时是**人工**发现的。同族的另一半
 * （{@code MetricAdsSpecTest} 硬编码 Java 列清单镜像）已由 S3-30 改为读唯一所有者；本类收口剩下一半。</p>
 *
 * <h2>三条判据（缺一不可）</h2>
 * <ol>
 *   <li>{@link #sparkLiteralsAreRegisteredCodesOrDeclaredNonRuleTokens()}：Spark 生产源码里**每个**
 *       大写字面量，要么**已登记**，要么在**显式非规则 token 表**里。未登记的新码必然红
 *       —— 这是 fail-closed 的那一半：新码不能靠「没人发现」混过去。</li>
 *   <li>{@link #declaredNonRuleTokensAreStillPresent()}：非规则 token 表**自身不得陈旧**
 *       —— 每条都必须在 Spark 生产源码里真的出现，否则就是「表越攒越大、判据被悄悄放宽」。</li>
 *   <li>{@link #registeredSparkBearingCodesStillHaveSparkSites()}：**反向漂移** —— 登记表里
 *       由 Spark 承载的码（前缀族 {@code ADS_/DWS_/PUB_/MXP_}）必须**仍有** Spark 站点。
 *       只做正向会漏掉「规则被删、登记表留着」这类「登记为阻断但实际不执行」的静默失效。</li>
 * </ol>
 *
 * <h2>诚实表述（不得越界）</h2>
 * <ul>
 *   <li><b>无经典 RED</b>：本类是**特征化守卫** —— 首跑即绿，因为改动前两侧恰好一致
 *       （实测 21 个字面量全部已登记）。「首跑绿」本身**不构成**判据有效的证据；
 *       非空性由 {@link #commentOnlyMentionsAreIgnoredWhileRealLiteralsAreFlagged()} 夹具
 *       与登记件 §5.2 的变异探针逐条证明（每条探针单独注入、单独红、注回后复原一致）。</li>
 *   <li>本门禁**只覆盖** {@code spark-jobs/src/main/scala} 的**双引号整串**大写 token；
 *      拼接码、{@code src/main/resources}、真集群运行期行为都**未覆盖、未测**。</li>
 *   <li>本门禁**不证明**规则语义正确、不证明阈值合理、不证明真集群上这些规则真的会跑
 *       —— 它只证明「两侧的码字面量集合没有各说各话」。</li>
 * </ul>
 *
 * <p>登记件：{@code docs/acceptance/s3-49-spark-rule-code-registry-guard-20260916/DESIGN-DIFF-REGISTER-20260916.md}。</p>
 */
class SparkRuleCodeRegistryGuardTest {

    /** 非空性标尺：Spark 生产树至少应有这么多 {@code .scala} 文件（实测 36；过少说明扫描面失效） */
    private static final int MIN_SCALA_FILES = 30;

    /** 非空性标尺：至少应认出这么多**已登记**码（实测 17；过少说明提取器空跑） */
    private static final int MIN_DISTINCT_REGISTERED_CODES = 15;

    /** 非空性标尺：至少应抓到大写字面量这么多处（实测 52；过少说明词法剥离把代码也剥掉了） */
    private static final int MIN_LITERAL_SITES = 45;

    /** Spark 承载的规则码前缀族（登记表里带这些前缀的码必须仍有 Spark 站点） */
    private static final List<String> SPARK_BEARING_PREFIXES = List.of("ADS_", "DWS_", "PUB_", "MXP_");

    /**
     * 逃生舱（**今日为空**）：已登记、带上述前缀，但**刻意不由 Spark 承载**的码。
     *
     * <p>留这个口子是 fail-closed 的必要条件：真出现「Java 侧读侧规则」时，必须**显式登记豁免**
     * 并写明理由，而不是让守卫去猜。{@link #registeredSparkBearingCodesStillHaveSparkSites()}
     * 同时反向检查这里的每条豁免：必须已登记，且**真的**不在 Spark 里（否则就是陈旧豁免）。</p>
     */
    private static final Set<String> REGISTERED_WITHOUT_SPARK_SITE = Set.of();

    /**
     * 非规则 token 表：形态像规则码、但**不是**规则码的大写字面量（实测闭集，2026-09-16 全量扫描）。
     *
     * <p>分五类，逐条都有出处，不是「为了变绿加的」：</p>
     * <ol>
     *   <li>层/阶段名：{@code ADS_STAGING}（{@code QualityCheck} 第二实参的 stage 名）、{@code PUBLISH}；</li>
     *   <li>严重度/结论：{@code BLOCKING}/{@code ERROR}/{@code INFO}/{@code WARN}/{@code SUCCESS}/
     *       {@code FAILED}/{@code COMPLETED}/{@code CANCELLED}；</li>
     *   <li>类型/字面量：{@code STRING}/{@code BIGINT}/{@code NULL}/{@code HASH64}；</li>
     *   <li>业务枚举（Landing 清洗的状态机取值）：{@code CREATED}/{@code PAID}/{@code REFUNDED}/
     *       {@code REFUNDING}；</li>
     *   <li>解析器错误码与命名约束常量：{@code PAYLOAD_*}（{@code JsonObjectSlicer} 的切片错误码）、
     *       {@code EMPTY_LINE}、{@code WAREHOUSE_PREFIX_*}（{@code WarehouseNamespace} 的约束名）。</li>
     * </ol>
     * <p><b>这张表不是第二份规则码清单</b>：规则码的唯一所有者仍是 {@code RuleSeverity.REGISTERED}；
     * 本表只声明「哪些 token 不是规则码」。表里每条都必须仍在 Spark 源码里出现（判据 ②）。</p>
     */
    private static final Set<String> NON_RULE_TOKENS = Set.of(
            "ADS_STAGING", "PUBLISH",
            "BLOCKING", "ERROR", "INFO", "WARN", "SUCCESS", "FAILED", "COMPLETED", "CANCELLED",
            "STRING", "BIGINT", "NULL", "HASH64",
            "CREATED", "PAID", "REFUNDED", "REFUNDING",
            "EMPTY_LINE",
            "PAYLOAD_COLON_MISSING", "PAYLOAD_KEY_DUPLICATE", "PAYLOAD_KEY_MISSING", "PAYLOAD_KEY_NESTED",
            "PAYLOAD_NOT_OBJECT", "PAYLOAD_UNBALANCED_OBJECT", "PAYLOAD_UNTERMINATED_STRING",
            "PAYLOAD_VALUE_MISSING",
            "WAREHOUSE_PREFIX_LAYER_SUFFIX", "WAREHOUSE_PREFIX_PATTERN", "WAREHOUSE_PREFIX_RESERVED",
            "WAREHOUSE_PREFIX_UNDERSCORE");

    /** 三个规则码承载文件（实测：Spark 生产树里全部规则码站点都在这三个文件里） */
    private static final List<String> RULE_CODE_FILES = List.of(
            "spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala",
            "spark-jobs/src/main/scala/com/graduation/analytics/job/AdsPublishJob.scala",
            "spark-jobs/src/main/scala/com/graduation/analytics/job/MetricExportJob.scala");

    @Test
    @DisplayName("Spark 生产源码里的每个大写字面量：要么是已登记规则码，要么在显式非规则 token 表里")
    void sparkLiteralsAreRegisteredCodesOrDeclaredNonRuleTokens() {
        SparkRuleCodeScan.Result result = SparkRuleCodeScan.scan(RepoRoot.path());

        // 1) 先证明「真的扫到了东西」：范围失效不得伪装成通过
        assertThat(result.scanned()).as("扫描面：Spark 生产 .scala 文件数（过少说明范围失效）")
                .hasSizeGreaterThan(MIN_SCALA_FILES);
        assertThat(relative(result.scanned())).as("三个规则码承载文件必须在扫描范围内")
                .containsAll(RULE_CODE_FILES);
        assertThat(result.literals()).as("抓到的字面量站点数（过少说明词法剥离把代码也剥掉了）")
                .hasSizeGreaterThan(MIN_LITERAL_SITES);

        // 2) 判据本体：未登记且不在非规则表里的 token ⇒ 红
        assertThat(undeclared(result))
                .as("Spark 生产源码里出现了「未登记、也不在非规则 token 表里」的大写字面量。%n"
                        + "规则码的唯一所有者是 RuleSeverity.REGISTERED（platform-common）：%n"
                        + "  · 新规则码 ⇒ 把它登记进 RuleSeverity.of(String) 与本表（并经 QualityRuleCatalog 目录），%n"
                        + "  · 确实不是规则码 ⇒ 把它登记进本测试类的 NON_RULE_TOKENS 并写明出处；%n"
                        + "不得靠删除 Spark 站点、或把整类判据放宽来变绿。命中：%n%s", join(undeclared(result)))
                .isEmpty();

        // 3) 非空性：必须真的认出足够多的**已登记**码（否则「全部命中都在非规则表里」这种退化会伪装成绿）
        assertThat(registeredCodesIn(result)).as("必须真的认出已登记规则码（过少说明提取器空跑）%n%s",
                        join(registeredCodesIn(result)))
                .hasSizeGreaterThanOrEqualTo(MIN_DISTINCT_REGISTERED_CODES);
    }

    @Test
    @DisplayName("非规则 token 表自身不得陈旧：每条都仍须在 Spark 生产源码里出现")
    void declaredNonRuleTokensAreStillPresent() {
        SparkRuleCodeScan.Result result = SparkRuleCodeScan.scan(RepoRoot.path());
        List<String> present = result.codes();

        assertThat(NON_RULE_TOKENS)
                .as("非规则 token 表里每条都必须仍在 Spark 生产源码里出现；"
                        + "若某个 token 已随代码删除，请从表里删掉它 —— 否则这张表会越攒越大、判据被悄悄放宽")
                .allSatisfy(token -> assertThat(present)
                        .as("陈旧的非规则 token（源码里已找不到）: %s", token)
                        .contains(token));
    }

    @Test
    @DisplayName("反向漂移：登记表里 Spark 承载的规则码必须仍有 Spark 站点（否则「登记为阻断却从不执行」）")
    void registeredSparkBearingCodesStillHaveSparkSites() {
        SparkRuleCodeScan.Result result = SparkRuleCodeScan.scan(RepoRoot.path());
        List<String> present = result.codes();

        List<String> expected = RuleSeverity.registeredCodes().stream()
                .filter(code -> SPARK_BEARING_PREFIXES.stream().anyMatch(code::startsWith))
                .filter(code -> !REGISTERED_WITHOUT_SPARK_SITE.contains(code))
                .sorted()
                .toList();
        assertThat(expected).as("前提：登记表里 Spark 承载前缀族的码非空（否则本判据空跑）").isNotEmpty();

        assertThat(expected.stream().filter(code -> !present.contains(code)).toList())
                .as("下列规则码已登记（读侧认它），但在 Spark 生产源码里**找不到任何站点** ⇒ "
                        + "「登记为阻断却从不执行」的静默失效。命中：%n%s 全量对照：%n%s",
                        join(expected.stream().filter(code -> !present.contains(code)).toList()), join(expected))
                .isEmpty();

        // 逃生舱自身不得陈旧：豁免项必须已登记，且真的不在 Spark 里
        for (String exempt : REGISTERED_WITHOUT_SPARK_SITE) {
            assertThat(RuleSeverity.registered(exempt)).as("豁免项必须是已登记码: %s", exempt).isTrue();
            assertThat(present).as("豁免项已重新出现在 Spark 源码里 ⇒ 应删除这条豁免: %s", exempt)
                    .doesNotContain(exempt);
        }
    }

    @Test
    @DisplayName("负例（真会红／真会绿）：注释里的码不算，代码里的未登记码必须红")
    void commentOnlyMentionsAreIgnoredWhileRealLiteralsAreFlagged(@TempDir Path tmp) throws IOException {
        Path fixture = write(tmp, SparkRuleCodeScan.SPARK_MAIN + "/fixture/ProbeJob.scala", """
                object ProbeJob {
                  // checks += QualityCheck("S349_COMMENT_ONLY_PROBE", "PUBLISH", t, 0L, 0L, "x", "INFO", true, "")
                  /* 块注释里的第二处：QualityCheck("S349_BLOCK_COMMENT_PROBE", ...) */
                  val real = QualityCheck("S349_REAL_UNREGISTERED_PROBE", "PUBLISH", t, 0L, 0L, "x", "INFO", true, "")
                }
                """);

        SparkRuleCodeScan.Result result = SparkRuleCodeScan.scan(tmp);

        assertThat(result.scanned()).as("夹具文件必须进扫描范围").contains(fixture);
        // 注意：**不**钉整个字面量清单 —— 真实那行里还有 ``"PUBLISH"``（stage 名）与 ``"INFO"``（severity）
        // 这类非规则 token；本用例只钉「注释里的两处不算、代码里的那处算」。
        assertThat(result.codes()).as("剥注释后：代码里的未登记码必须在，两处注释里的码必须不在")
                .contains("S349_REAL_UNREGISTERED_PROBE")
                .doesNotContain("S349_COMMENT_ONLY_PROBE", "S349_BLOCK_COMMENT_PROBE");
        assertThat(undeclared(result)).as("代码里的未登记码必须报红（这条证明判据不是空跑）")
                .extracting(SparkRuleCodeScan.Lit::code)
                .containsExactly("S349_REAL_UNREGISTERED_PROBE");

        // 见证：两处注释**确实存在于原文**（变绿的原因是词法排除，不是「这两行本来就不在」）
        String raw = SparkRuleCodeScan.read(fixture);
        assertThat(raw).as("行注释里的码仍在原文里").contains("S349_COMMENT_ONLY_PROBE");
        assertThat(raw).as("块注释里的码仍在原文里").contains("S349_BLOCK_COMMENT_PROBE");
    }

    // ── 判据与小工具 ────────────────────────────────────────────────────────

    /** 判据本体：既未登记、也不在显式非规则 token 表里的大写字面量 */
    private static List<SparkRuleCodeScan.Lit> undeclared(SparkRuleCodeScan.Result result) {
        return result.literals().stream()
                .filter(lit -> !RuleSeverity.registered(lit.code()))
                .filter(lit -> !NON_RULE_TOKENS.contains(lit.code()))
                .toList();
    }

    /** 命中的**已登记**码（去重、字典序） */
    private static List<String> registeredCodesIn(SparkRuleCodeScan.Result result) {
        return result.codes().stream().filter(RuleSeverity::registered).toList();
    }

    private static List<String> relative(List<Path> files) {
        Path root = RepoRoot.path();
        return files.stream().map(p -> root.relativize(p).toString().replace('\\', '/')).toList();
    }

    private static String join(List<?> items) {
        return String.join(System.lineSeparator(), items.stream().map(String::valueOf).toList());
    }

    private static Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
