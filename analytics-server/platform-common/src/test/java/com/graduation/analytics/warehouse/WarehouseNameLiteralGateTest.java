package com.graduation.analytics.warehouse;

import com.graduation.analytics.testsupport.RepoRoot;
import com.graduation.analytics.warehouse.WarehouseNameLiteralScanner.CommentSyntax;
import com.graduation.analytics.warehouse.WarehouseNameLiteralScanner.Hit;
import com.graduation.analytics.warehouse.WarehouseNameLiteralScanner.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库名字面量门禁（P1-04 的 DoD 自动化）。
 *
 * <p>数仓库名只能由唯一所有者派生：Java {@code WarehouseNamespace} 与 Scala {@code WarehouseNamespace}
 * （同名镜像，规格 {@code contract-specs/specs/warehouse-namespace.v2.json}，P2-07 起；
 * v1 为历史冻结件）。本测试扫**生产源码**
 * （两个程序的 {@code src/main}、{@code warehouse/ddl}、{@code scripts}），
 * 出现 `dw_ods` 这类裸库名、或 `dw_$layer` / `"dw_" + x` 这类在代码里拼前缀的写法即失败。</p>
 *
 * <p>P2-07 的额外意义：源级前缀下沉后，新写入点（V18 迁移、源登记服务、命令构造）都在扫描范围内，
 * 因此"多一个所有者"这件事会被本门禁挡住，而不只是靠评审。</p>
 *
 * <p>**不扫测试源码**：测试里出现 `"dw_ods"` 是断言期望值（例如规格向量的 names 字段），
 * 那是「验证」而不是「第二处所有者」。**不扫文档/验收证据**：历史证据必须保持原样。</p>
 *
 * <p>本测试自身是「规格之外的第二道门」：即使有人绕过测试改实现，只要留下字面量就会红。</p>
 *
 * <h2>V25-T01：注释误命中（本类 2026-09-14 的改动）</h2>
 * <p>原实现逐行跑正则、**不看注释**，于是两条 scaladoc 里引用的历史报错原文被判成第二处所有者：</p>
 * <ul>
 *   <li>{@code spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala:145}
 *       —— 「建后即删，未碰 {@code dw_dwd.dwd_order_detail}」；</li>
 *   <li>{@code spark-jobs/src/main/scala/com/graduation/analytics/sql/SurrogateKey.scala:160}
 *       —— Spark 报错原文里的 {@code spark_catalog.dw_dwd.dwd_order_detail}。</li>
 * </ul>
 * <p>按指导书 §9.5 的「若改扫描器，须以词法方式排除注释且保留真实 SQL 字面量红例，不能跳过整个文件」，
 * 判定逻辑移入 {@link WarehouseNameLiteralScanner}：**只把注释区间换成空格**（行数与行号不动），
 * 字符串字面量内容一律保留。因此本类同时钉住三件事，缺一不可：</p>
 * <ol>
 *   <li>{@link #noBareWarehouseNameLiterals()}：真实仓库**仍按原口径全量扫描**（文件数下限、所有者文件、
 *       两个程序都在范围内），词法排除注释后**零命中**；</li>
 *   <li>{@link #realSqlLiteralIsStillFlaggedEndToEnd()}：**真会红的负例** —— 在临时夹具仓里放一句
 *       真正的 {@code INSERT OVERWRITE TABLE dw_dwd.dwd_order_detail ...}，走同一条
 *       「收集→定范围→词法→匹配」路径，必须报红（不是正则说明，是可复跑的用例）；</li>
 *   <li>{@link #commentOnlyMentionsAreLexicallyExcludedNotDeleted()}：**见证两条注释仍在库里**
 *       —— 原文命中非空且全部落在注释里，证明变绿的原因是「词法排除」而不是「把历史原文删了」。</li>
 * </ol>
 *
 * <p><b>历史原文留存的唯一凭据</b>：{@code SurrogateKey.scala} 那句的原文完整保存在
 * {@code docs/acceptance/m3-step8-parity-20260912/raw/post/p2-03-regression-evidence.txt:120-121}
 * （run 46 的 Spark 原始输出）。{@link #historicalErrorTextIsPreservedInEvidence()} 做逐字核对：
 * 证据侧是 Spark CLI 原文（带反引号、按终端宽度折行），源码侧去掉了反引号并加了 scaladoc 前缀，
 * 因此**不是逐字相同**，而是「去掉引用符号与折行后逐字相同」——这个差别写在这里，不藏。</p>
 */
class WarehouseNameLiteralGateTest {

    /** 门槛：真实仓库至少应扫到这么多生产文本文件（过少说明范围失效） */
    private static final int MIN_SCANNED = 60;

    @Test
    @DisplayName("生产源码里除唯一所有者外，不得出现库名字面量或前缀拼接（注释按词法排除）")
    void noBareWarehouseNameLiterals() {
        Result result = WarehouseNameLiteralScanner.scan(RepoRoot.path());

        // 门禁必须先证明「真的扫到了东西」，否则仓根定位/过滤出错会伪装成通过
        assertThat(result.scanned()).as("扫描到的生产文件数（过少说明范围失效）").hasSizeGreaterThan(MIN_SCANNED);
        for (String owner : WarehouseNameLiteralScanner.OWNER_FILES) {
            assertThat(result.scanned()).as("唯一所有者必须落在扫描范围内: %s", owner)
                    .contains(RepoRoot.path(owner));
        }
        assertThat(result.scanned()).as("必须扫到两个程序各自的文件")
                .anyMatch(p -> p.toString().replace('\\', '/').contains("/spark-jobs/src/main/"))
                .anyMatch(p -> p.toString().replace('\\', '/').contains("/analytics-server/"));

        assertThat(result.codeHits())
                .as("库名必须只由 WarehouseNamespace 派生（唯一所有者）；注释已按词法排除，"
                        + "此处若仍有命中即为真实代码里的裸字面量或前缀拼接：%n%s",
                        join(result.codeHits()))
                .isEmpty();
    }

    @Test
    @DisplayName("负例（真会红）：临时夹具仓里真实 SQL 字符串中的裸库名必须报红")
    void realSqlLiteralIsStillFlaggedEndToEnd(@TempDir Path tmp) throws IOException {
        Path scala = write(tmp, "spark-jobs/src/main/scala/fixture/BadSql.scala", """
                object BadSql {
                  def insert(ns: String): String =
                    s"INSERT OVERWRITE TABLE dw_dwd.dwd_order_detail PARTITION (dt) SELECT 1"
                }
                """);
        Path java = write(tmp, "analytics-server/platform-common/src/main/java/fixture/BadTextBlock.java", """
                class BadTextBlock {
                  static final String DDL = \"""
                      CREATE TABLE IF NOT EXISTS dw_ods.ods_order (id BIGINT);
                      \""";
                }
                """);
        Path sql = write(tmp, "warehouse/ddl/bad.sql", """
                -- 下面这句是真实 SQL，不是注释
                INSERT OVERWRITE TABLE dw_dwd.dwd_order_detail PARTITION (dt) SELECT 1;
                """);
        Path sh = write(tmp, "scripts/bad.sh", """
                #!/bin/sh
                # dw_ods.ods_order 只出现在注释里
                spark-sql -e "SELECT * FROM dw_ads.ads_overview"
                """);

        Result result = WarehouseNameLiteralScanner.scan(tmp);

        assertThat(result.scanned()).as("四个夹具文件都要进扫描范围").contains(scala, java, sql, sh);
        assertThat(result.codeHits()).as("真实 SQL/代码里的裸库名必须仍然报红：%n%s", join(result.codeHits()))
                .extracting(Hit::file)
                .containsExactlyInAnyOrder(
                        "spark-jobs/src/main/scala/fixture/BadSql.scala",
                        "analytics-server/platform-common/src/main/java/fixture/BadTextBlock.java",
                        "warehouse/ddl/bad.sql",
                        "scripts/bad.sh");
        assertThat(result.codeHits()).as("SQL 文件里的命中行号必须指向真语句（注释行是第 1 行）")
                .filteredOn(h -> h.file().endsWith("bad.sql"))
                .extracting(Hit::line)
                .containsExactly(2);
    }

    @Test
    @DisplayName("负例（真会绿）：临时夹具仓里仅注释提到库名时不报红")
    void commentOnlyMentionsInFixtureAreNotFlagged(@TempDir Path tmp) throws IOException {
        Path commented = write(tmp, "spark-jobs/src/main/scala/fixture/Commented.scala", """
                /**
                 * 历史报错原文：表 spark_catalog.dw_dwd.dwd_order_detail 的 user_key
                 * 一次性实验未碰 dw_dwd.dwd_order_detail；前缀写法 dw_$layer 也只是文档。
                 */
                object Commented {
                  val ok = 1
                }
                """);

        Result result = WarehouseNameLiteralScanner.scan(tmp);

        assertThat(result.scanned()).contains(commented);
        assertThat(result.codeHits()).as("注释里的库名不得命中").isEmpty();
        assertThat(result.rawHits()).as("原文口径下确实命中（否则本负例是空跑）").isNotEmpty();
    }

    @Test
    @DisplayName("见证：两条历史注释仍在库内（变绿是词法排除，不是删数据）")
    void commentOnlyMentionsAreLexicallyExcludedNotDeleted() {
        Result result = WarehouseNameLiteralScanner.scan(RepoRoot.path());

        assertThat(result.rawHits())
                .as("原文口径（= 旧门禁口径）必须仍有命中：若这里空了，说明历史注释被删改，"
                        + "本见证失效 —— 请在证据目录登记新的见证，不得借此放宽词法排除")
                .extracting(Hit::file)
                .contains(
                        "spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala",
                        "spark-jobs/src/main/scala/com/graduation/analytics/sql/SurrogateKey.scala");
        assertThat(result.commentOnly())
                .as("原文命中应**全部**落在注释里（有代码命中就会在 noBareWarehouseNameLiterals 处红）")
                .hasSameSizeAs(result.rawHits());

        for (Hit hit : result.rawHits()) {
            System.out.println("[V25-T01 见证] 原文命中（注释内，已按词法排除）: " + hit);
        }
    }

    @Test
    @DisplayName("历史原文留存核对：源码注释与已保留证据的报错原文一致（差异只允许引用符号与折行）")
    void historicalErrorTextIsPreservedInEvidence() throws IOException {
        String evidence = read(RepoRoot.path(
                "docs/acceptance/m3-step8-parity-20260912/raw/post/p2-03-regression-evidence.txt"));
        String source = read(RepoRoot.path(
                "spark-jobs/src/main/scala/com/graduation/analytics/sql/SurrogateKey.scala"));

        // 证据侧：Spark CLI 原文，列名/库名/表名带反引号，且按终端宽度折行
        String citationFree = evidence.replace("`", "");
        assertThat(citationFree)
                .as("证据侧（run 46 原始输出）必须含同一条报错的表名与判据")
                .contains("data for the table spark_catalog.dw_dwd.dwd_order_detail: Cannot safely cast user_key")
                .contains("\"STRING\" to \"BIGINT\".");
        // 源码侧：去掉反引号、加 scaladoc 前缀后逐字相同
        assertThat(source)
                .as("源码注释必须仍是同一条报错原文（若已改动，请同步证据与本文档说明）")
                .contains("table spark_catalog.dw_dwd.dwd_order_detail: Cannot safely cast user_key \"STRING\" to \"BIGINT\".");
    }

    @Test
    @DisplayName("词法表覆盖所有被扫描的文本扩展名（fail-closed：新增扩展名必须显式登记）")
    void commentSyntaxTableCoversScannedExtensions() {
        assertThat(WarehouseNameLiteralScanner.declaredSyntaxExtensions())
                .as("TEXT_EXTENSIONS 里的每种扩展名都必须声明注释语法（无注释语法写 NONE），"
                        + "否则新文件类型会绕开词法剥离")
                .containsAll(WarehouseNameLiteralScanner.TEXT_EXTENSIONS);

        Result result = WarehouseNameLiteralScanner.scan(RepoRoot.path());
        for (Path file : result.scanned()) {
            String rel = RepoRoot.path().relativize(file).toString().replace('\\', '/');
            assertThat(WarehouseNameLiteralScanner.syntaxOf(rel)).as("未登记语法的文件: %s", rel).isNotNull();
        }
    }

    @Test
    @DisplayName("词法单元用例：各家族的注释被剥离、字符串与代码原样保留")
    void lexerFamilies() {
        // Java/Scala：块注释与行注释被剥离；字符串（含三引号）内容保留；注释里的 // 不干扰后续代码
        String scala = """
                val a = s"SELECT * FROM dw_dwd.t"   // dw_ods.t 注释
                /* 块注释 dw_dwd.t
                   跨行块注释 dw_dim.t */
                """;
        String scalaCode = CommentSyntax.strip(scala, CommentSyntax.SLASH);
        assertThat(WarehouseNameLiteralScanner.matches(line(scalaCode, 0))).as("代码行仍命中").isTrue();
        assertThat(scalaCode).as("行注释与块注释内容被剥离").doesNotContain("dw_ods").doesNotContain("dw_dim");
        assertThat(WarehouseNameLiteralScanner.matches(line(scalaCode, 1))).as("块注释首行不再命中").isFalse();
        assertThat(WarehouseNameLiteralScanner.matches(line(scalaCode, 2))).as("块注释次行不再命中").isFalse();
        assertThat(scalaCode.split("\n", -1))
                .as("行数不变（行号可继续引用）")
                .hasSameSizeAs(scala.split("\n", -1));

        // SQL：-- 注释被剥离，'...' 字符串保留
        String sql = "SELECT * FROM dw_ods.t WHERE x = 'dw_dwd.y'; -- dw_ads.z\n";
        String sqlCode = CommentSyntax.strip(sql, CommentSyntax.DASH);
        assertThat(WarehouseNameLiteralScanner.matches(line(sqlCode, 0))).isTrue();
        assertThat(sqlCode).doesNotContain("dw_ads");

        // yml / sh：# 行注释被剥离，引号里的值保留
        String yml = "name: \"dw_ods\"  # dw_dwd 注释\n";
        String ymlCode = CommentSyntax.strip(yml, CommentSyntax.HASH);
        assertThat(WarehouseNameLiteralScanner.matches(line(ymlCode, 0))).isTrue();
        assertThat(ymlCode).doesNotContain("dw_dwd");

        // PS1：# 行注释与 <# #> 块注释都剥离
        String ps = "Write-Host \"dw_ods\" # dw_dwd\n<# dw_dim #>\n";
        String psCode = CommentSyntax.strip(ps, CommentSyntax.POWERSHELL);
        assertThat(psCode).doesNotContain("dw_dwd").doesNotContain("dw_dim");
        assertThat(WarehouseNameLiteralScanner.matches(line(psCode, 0))).isTrue();

        // XML/MD：<!-- --> 剥离，正文保留
        String xml = "<a>dw_ods</a><!-- dw_dwd -->\n";
        String xmlCode = CommentSyntax.strip(xml, CommentSyntax.XML);
        assertThat(WarehouseNameLiteralScanner.matches(line(xmlCode, 0))).isTrue();
        assertThat(xmlCode).doesNotContain("dw_dwd");

        // NONE（json/txt）：一个字符都不剥离
        String json = "{ \"x\": \"dw_ods\" }\n";
        assertThat(CommentSyntax.strip(json, CommentSyntax.NONE)).isEqualTo(json);
    }

    @Test
    @DisplayName("扫描范围声明本身可解析（范围/所有者清单不为空）")
    void scopeIsWellFormed() {
        assertThat(WarehouseNameLiteralScanner.SCOPES).isNotEmpty();
        assertThat(WarehouseNameLiteralScanner.OWNER_FILES).hasSize(2);
        assertThat(WarehouseNameLiteralScanner.collect(RepoRoot.path()))
                .as("collect() 至少能找到所有者的文件")
                .contains(RepoRoot.path("spark-jobs/src/main/scala/com/graduation/analytics/warehouse/WarehouseNamespace.scala"));
    }

    @Test
    @DisplayName("warehouse/ddl 的库名走 ${WAREHOUSE_PREFIX} 变量（不写死、不另造第二套前缀约定）")
    void ddlDerivesDatabaseNamesFromSharedPrefix() {
        Path ddlDir = RepoRoot.path("warehouse/ddl");
        List<Path> files;
        try (var stream = Files.list(ddlDir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + ddlDir, e);
        }
        assertThat(files).as("warehouse/ddl 下的 SQL 文件").hasSize(5);

        Set<String> layers = new LinkedHashSet<>();
        Set<String> variables = new LinkedHashSet<>();
        Matcher layerRef = Pattern.compile("\\$\\{WAREHOUSE_PREFIX\\}_(ods|dwd|dim|dws|ads)\\b").matcher("");
        Matcher anyVar = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?}").matcher("");

        for (Path file : files) {
            String rel = RepoRoot.path().relativize(file).toString().replace('\\', '/');
            String text;
            try {
                text = Files.readString(file);
            } catch (IOException e) {
                throw new UncheckedIOException("读取失败: " + rel, e);
            }
            assertThat(text)
                    .as("%s 必须用 ${WAREHOUSE_PREFIX}_<层> 派生库名（漏传变量时 Hive 原样保留 ${...} → 建库处语法报错，fail-closed）", rel)
                    .contains("${WAREHOUSE_PREFIX}_");
            layerRef.reset(text);
            while (layerRef.find()) {
                layers.add(layerRef.group(1));
            }
            anyVar.reset(text);
            while (anyVar.find()) {
                variables.add(anyVar.group(1));
            }
        }

        assertThat(layers).as("DDL 覆盖的层后缀（五层都要有）")
                .containsExactlyInAnyOrder("ods", "dwd", "dim", "dws", "ads");
        assertThat(variables).as("DDL 里只允许 WAREHOUSE_PREFIX 一个变量：另造第二个前缀名就是第二处所有者")
                .containsExactly("WAREHOUSE_PREFIX");
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────

    private static Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static String read(Path file) throws IOException {
        assertThat(file).as("证据/源码文件必须存在: %s", file).exists();
        return Files.readString(file);
    }

    private static String line(String text, int index) {
        return text.split("\n", -1)[index];
    }

    private static String join(List<Hit> hits) {
        return String.join(System.lineSeparator(), hits.stream().map(Hit::toString).toList());
    }
}
