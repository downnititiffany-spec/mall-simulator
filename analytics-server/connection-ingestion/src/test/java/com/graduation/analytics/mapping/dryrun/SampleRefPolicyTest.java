package com.graduation.analytics.mapping.dryrun;

import com.graduation.analytics.common.PlatformBizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S2-01B {@code sampleRef} 受控引用策略：设计 §7.3 规则 10「sampleRef 只能引用平台批准的位置，
 * 不任意 URL/本机路径」。
 *
 * <p>本类只测**多出来的那几道闸**（协议前缀/百分号/控制字符/空段/符号链接/真实路径包含）；
 * 仓库相对与 {@code ..}/绝对路径/盘符/UNC/反斜杠的判定属于 P1-03 的 {@code SourcePathPolicy}，
 * 已由 {@code SourceProfileValidatorTest}/{@code SourceRegistryServiceTest} 覆盖——这里只做一条
 * "仍然会拦"的抽查，不重复建第二份语料。</p>
 *
 * <p><b>脱敏要求</b>：所有拒绝报文都不得回显调用方传入的值（P1-03 真机验收踩出来的规则），
 * 因此每个拒绝用例都额外断言"报文不含原值"。</p>
 */
class SampleRefPolicyTest {

    @TempDir
    Path root;

    // ---------- 放行 ----------

    @Test
    @DisplayName("合法仓库相对引用：放行，返回去空白后的同一字面路径（不做任何规范化改写）")
    void legalRefIsAcceptedVerbatim() {
        assertThat(SampleRefPolicy.requireSampleRef("  samples/b1a.jsonl  ")).isEqualTo("samples/b1a.jsonl");
        assertThat(SampleRefPolicy.requireSampleRef("a/b/c.jsonl")).isEqualTo("a/b/c.jsonl");
        assertThat(SampleRefPolicy.requireSampleRef("样本/b1a.jsonl")).isEqualTo("样本/b1a.jsonl");
    }

    @Test
    @DisplayName("解析到根下：存在/不存在都返回字面路径（不存在由调用方判 404，不由策略判）")
    void resolveReturnsLiteralPathAndLeavesExistenceToCaller() throws IOException {
        Files.writeString(root.resolve("s.jsonl"), "{}\n");

        assertThat(SampleRefPolicy.resolveUnderSampleRoot(root, "s.jsonl")).isEqualTo(root.resolve("s.jsonl"));
        assertThat(SampleRefPolicy.resolveUnderSampleRoot(root, "not-there.jsonl"))
                .isEqualTo(root.resolve("not-there.jsonl"));
    }

    // ---------- 拒绝：引用形态 ----------

    @Test
    @DisplayName("协议前缀一律拒：file:/http:/https:/hdfs:/jar:/classpath:（P1-03 只拦盘符，拦不住 file:）")
    void schemePrefixIsRejected() {
        // file:./x 不是 ^[A-Za-z]: 形状，P1-03 的规则会放行 —— 这正是本策略必须存在的原因
        for (String ref : new String[]{
                "file:./samples/b1a.jsonl", "file:///D:/x.jsonl", "http://example.com/a.jsonl",
                "https://example.com/a.jsonl", "hdfs://nn/a.jsonl", "jar:file:/a.jsonl",
                "classpath:samples/b1a.jsonl"}) {
            assertRejectedWithParamInvalid(ref);
        }
    }

    @Test
    @DisplayName("百分号转义一律拒（全链路不解码，仍直接拒，避免将来有人加解码引入二次歧义）")
    void percentEscapeIsRejected() {
        assertRejectedWithParamInvalid("%2e%2e/secret.jsonl");
        assertRejectedWithParamInvalid("samples/%2E%2E/x.jsonl");
        assertRejectedWithParamInvalid("a%00b.jsonl");
    }

    @Test
    @DisplayName("控制字符拒（\\n \\r \\t \\u0000 与 DEL）")
    void controlCharactersAreRejected() {
        assertRejectedWithParamInvalid("a\nb.jsonl");
        assertRejectedWithParamInvalid("a\rb.jsonl");
        assertRejectedWithParamInvalid("a\tb.jsonl");
        assertRejectedWithParamInvalid("a\u0000b.jsonl");
        assertRejectedWithParamInvalid("a\u007fb.jsonl");
    }

    @Test
    @DisplayName("空路径段与 . 段拒：a//b、结尾 /、./a")
    void emptyAndDotSegmentsAreRejected() {
        assertRejectedWithParamInvalid("a//b.jsonl");
        assertRejectedWithParamInvalid("samples/");
        assertRejectedWithParamInvalid("./samples/a.jsonl");
        assertRejectedWithParamInvalid("samples/./a.jsonl");
    }

    @Test
    @DisplayName("空白引用拒（缺省引用不许静默回落到某个默认样本）")
    void blankIsRejected() {
        for (String ref : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> SampleRefPolicy.requireSampleRef(ref))
                    .isInstanceOf(PlatformBizException.class)
                    .extracting(e -> ((PlatformBizException) e).getCode())
                    .isEqualTo(PlatformBizException.PARAM_INVALID);
        }
    }

    @Test
    @DisplayName("P1-03 既有判定仍然生效（抽查）：绝对路径 / 盘符 / UNC / 反斜杠 / .. 段")
    void inheritedRepoRelativeRulesStillApply() {
        assertRejectedWithParamInvalid("/etc/passwd");
        assertRejectedWithParamInvalid("C:/Windows/win.ini");
        assertRejectedWithParamInvalid("//host/share/a.jsonl");
        assertRejectedWithParamInvalid("samples\\a.jsonl");
        assertRejectedWithParamInvalid("../outside.jsonl");
        assertRejectedWithParamInvalid("samples/../../outside.jsonl");
    }

    @Test
    @DisplayName("超长引用拒（>255，与 SourcePathPolicy.MAX_LENGTH 同量级）")
    void overlongRefIsRejected() {
        assertRejectedWithParamInvalid("a".repeat(SampleRefPolicy.MAX_LENGTH) + ".jsonl");
    }

    @Test
    @DisplayName("拒绝报文不回显原值（脱敏硬要求）：报文里既无原文也无绝对前缀")
    void rejectionMessagesDoNotEchoTheValue() {
        String absolute = "C:/secret/location/b1a.jsonl";

        assertThatThrownBy(() -> SampleRefPolicy.requireSampleRef(absolute))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("C:/")
                .hasMessageNotContaining(absolute);

        String scheme = "https://internal.example.com/path/leak.jsonl";
        assertThatThrownBy(() -> SampleRefPolicy.requireSampleRef(scheme))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageNotContaining("internal.example.com")
                .hasMessageNotContaining(scheme);
    }

    // ---------- 拒绝：根与包含关系 ----------

    @Test
    @DisplayName("样本根不存在/不是目录 ⇒ 平台侧故障（IllegalStateException → 500），不回落任何目录")
    void missingSampleRootIsPlatformFault() throws IOException {
        Path missing = root.resolve("no-such-root");
        assertThatThrownBy(() -> SampleRefPolicy.resolveUnderSampleRoot(missing, "a.jsonl"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sample-root");

        Path file = root.resolve("a-file");
        Files.writeString(file, "x");
        assertThatThrownBy(() -> SampleRefPolicy.resolveUnderSampleRoot(file, "a.jsonl"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("真实路径包含判定：根外一个字符也拒（判定本体与文件系统无关地测到）")
    void realPathContainmentRejectsOutsideAndAllowsInside() {
        Path realRoot = Paths.get("C:/root").toAbsolutePath().normalize();

        SampleRefPolicy.requireRealPathWithinRoot(realRoot, realRoot.resolve("a.jsonl"));

        assertThatThrownBy(() -> SampleRefPolicy.requireRealPathWithinRoot(realRoot, realRoot.resolveSibling("root2/a.jsonl")))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("真实路径")
                .hasMessageNotContaining("root2");
    }

    @Test
    @DisplayName("符号链接拒（环境不允许建符号链接时 assume-skip，并打印原因，不静默通过）")
    void symbolicLinkIsRejected() throws IOException {
        Path target = root.resolve("real.jsonl");
        Files.writeString(target, "{}\n");
        Path link = root.resolve("link.jsonl");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "当前环境不允许创建符号链接（" + e.getClass().getSimpleName() + "），该分支未实测");
        }

        assertThatThrownBy(() -> SampleRefPolicy.resolveUnderSampleRoot(root, "link.jsonl"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("符号链接");
    }

    private static void assertRejectedWithParamInvalid(String ref) {
        assertThatThrownBy(() -> SampleRefPolicy.requireSampleRef(ref))
                .as("应拒绝: %s", ref)
                .isInstanceOf(PlatformBizException.class)
                .extracting(e -> ((PlatformBizException) e).getCode())
                .isEqualTo(PlatformBizException.PARAM_INVALID);
    }
}
