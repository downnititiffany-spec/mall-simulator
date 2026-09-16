package com.graduation.analytics.ingestion;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「向上找仓库根」单一所有者守卫（Java 侧，S3-45）。
 *
 * <p>背景：{@code connection-ingestion} 的测试树此前有 5 份各写一遍的 walk-up
 * （{@code IngestionBoundaryMatrixTest}／{@code IngestionFailureTaxonomyTest}／
 * {@code IngestionServiceMappingTest}／{@code FlumeSpoolConfigTest}／{@code MappingTestSupport}），
 * 各自锚点文件还不同（{@code canonical-event.v1.schema.json} 与 {@code flume-spooldir.conf}），
 * 其注释自 S2-02A 起就自称「已登记的重复项，等 RepoRoot 提到 test-jar 后一并删除」。
 * 本用例把「唯一所有者」从注释变成**可执行**断言：</p>
 *
 * <ol>
 *   <li>扫遍 {@code analytics-server} 全部模块的测试树 ⇒ walk-up 所有者集合必须**恰好**是
 *       {@code platform-common} 的 {@code RepoRoot.java}（既拦「多出来」＝新副本，
 *       也拦「所有者自己不见了」＝防恒真）；</li>
 *   <li>生产树（{@code src/main/java}）**零命中** —— 生产代码不得靠工作目录向上找仓库根；</li>
 *   <li>被收编的 5 个文件必须引用唯一所有者且不再自持 walk-up；</li>
 *   <li>所有者必须真的定位到仓库根下的中立契约文件（保持性检查）。</li>
 * </ol>
 *
 * <p>特征片段用**字符串拼装**，守卫源文件本身不含该片段，故无需「排除自身文件」兜底
 * （照 S3-30／S3-31 两次自伤教训）。</p>
 */
@DisplayName("S3-45 仓库根定位单一所有者守卫")
class RepoRootSingleOwnerTest {

    private static final String OWNER =
            "analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/RepoRoot.java";

    /** walk-up 的判据片段：拼装而成，本文件自身不含完整片段。 */
    private static final String WALK_UP =
            "for (Path dir = start; dir != null; dir = dir." + "getParent())";

    private static final String CONTRACT_REL = "contract-specs/schemas/canonical-event.v1.schema.json";

    private static final String CONTRACT_ID =
            "https://graduation.local/contract-specs/schemas/canonical-event.v1.schema.json";

    /** 本轮被收编的 5 个文件（改前各自持有一份 walk-up）。 */
    private static final List<String> FORMER_OWNERS = List.of(
            "analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/ingestion/IngestionBoundaryMatrixTest.java",
            "analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/ingestion/IngestionFailureTaxonomyTest.java",
            "analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/ingestion/IngestionServiceMappingTest.java",
            "analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/landing/FlumeSpoolConfigTest.java",
            "analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/mapping/MappingTestSupport.java");

    /** 扫面规模下限：防「扫了个空目录」把守卫变成恒真（实测 analytics-server 测试源文件 130 份）。 */
    private static final int MIN_SCANNED_TEST_FILES = 100;

    @Test
    @DisplayName("全 analytics-server 测试树里 walk-up 只有 platform-common 的 RepoRoot 一份，且生产树零命中")
    void onlyPlatformCommonOwnsRepoRootLookup() {
        Path analytics = RepoRoot.path("analytics-server");
        assertThat(Files.isDirectory(analytics)).as("仓库根下应有 analytics-server/：%s", analytics).isTrue();

        List<Path> testTrees = moduleTrees(analytics, "src/test/java");
        List<Path> mainTrees = moduleTrees(analytics, "src/main/java");
        assertThat(testTrees).as("没有扫到任何测试树，守卫会退化成空跑").isNotEmpty();

        Set<String> owners = new LinkedHashSet<>();
        int scannedTestFiles = 0;
        for (Path tree : testTrees) {
            for (Path file : javaFiles(tree)) {
                scannedTestFiles++;
                if (read(file).contains(WALK_UP)) {
                    owners.add(relative(file));
                }
            }
        }
        assertThat(scannedTestFiles)
                .as("扫到的测试源文件数应远多于下限 %d", MIN_SCANNED_TEST_FILES)
                .isGreaterThan(MIN_SCANNED_TEST_FILES);
        assertThat(owners)
                .as("walk-up 所有者集合必须恰好是单一所有者（多出来＝新副本；空集＝所有者自己不见了）")
                .containsExactly(OWNER);

        Set<String> mainHits = new LinkedHashSet<>();
        for (Path tree : mainTrees) {
            for (Path file : javaFiles(tree)) {
                if (read(file).contains(WALK_UP)) {
                    mainHits.add(relative(file));
                }
            }
        }
        assertThat(mainHits).as("生产代码不得靠工作目录向上找仓库根").isEmpty();
    }

    @Test
    @DisplayName("被收编的 5 个文件引用唯一所有者，且不再自持 walk-up")
    void formerOwnersDelegateToTheSingleOwner() {
        for (String file : FORMER_OWNERS) {
            String text = read(RepoRoot.path(file));
            assertThat(text).as("%s 不应再自持 walk-up 实现", file).doesNotContain(WALK_UP);
            assertThat(text).as("%s 应引用唯一所有者 RepoRoot", file).contains("RepoRoot");
        }
    }

    @Test
    @DisplayName("所有者定位到仓库根下的中立契约文件（保持性：守卫非恒真）")
    void ownerResolvesPinnedContractFile() {
        Path root = RepoRoot.path();
        assertThat(Files.isDirectory(root.resolve("contract-specs")))
                .as("仓库根下应有 contract-specs/：%s", root).isTrue();
        assertThat(Files.isDirectory(root.resolve("spark-jobs")))
                .as("仓库根下应有 spark-jobs/：%s", root).isTrue();
        assertThat(Files.isRegularFile(root.resolve("analytics-server/pom.xml")))
                .as("仓库根下应有 analytics-server/pom.xml：%s", root).isTrue();

        Path contract = RepoRoot.path(CONTRACT_REL);
        assertThat(contract.startsWith(root)).as("契约文件必须落在仓库根下：%s", contract).isTrue();
        assertThat(Files.isRegularFile(contract)).as("契约文件必须真实存在：%s", contract).isTrue();
        assertThat(read(contract))
                .as("必须读到被 pin 的那份契约文件（$id 对不上说明找错了根或换错了文件）")
                .contains(CONTRACT_ID);
    }

    private static List<Path> moduleTrees(Path analytics, String suffix) {
        try (Stream<Path> modules = Files.list(analytics)) {
            return modules.map(module -> module.resolve(suffix))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaFiles(Path tree) {
        try (Stream<Path> walk = Files.walk(tree)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path file) {
        return RepoRoot.path().relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
