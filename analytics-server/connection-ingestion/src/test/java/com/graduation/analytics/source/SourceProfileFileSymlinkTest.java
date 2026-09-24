package com.graduation.analytics.source;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.source.dto.SourceRegistryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SourceProfileFileSymlinkTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("真实路径仍在画像根内时允许，逃逸到兄弟目录时拒绝")
    void checksResolvedPathContainment() {
        Path realRoot = temp.resolve("profiles").toAbsolutePath().normalize();

        assertThatCode(() -> SourceProfileFile.requireRealPathWithinRoot(
                realRoot, realRoot.resolve("source.json"), "拒绝采集", "mall-a"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> SourceProfileFile.requireRealPathWithinRoot(
                realRoot, realRoot.resolveSibling("profiles-escape/source.json"), "拒绝采集", "mall-a"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("逃逸出画像根目录")
                .hasMessageNotContaining("profiles-escape");
    }

    @Test
    @DisplayName("画像根中的符号链接在读取前被拒绝，且错误不泄漏目标绝对路径")
    void rejectsSymlinkedProfile() throws IOException {
        Path profileRoot = Files.createDirectory(temp.resolve("profiles"));
        Path outside = Files.writeString(temp.resolve("outside.json"), "{}");
        Path link = profileRoot.resolve("source.json");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "当前环境不允许创建符号链接（" + e.getClass().getSimpleName() + "），该分支未实测");
        }

        SourceRegistryView source = new SourceRegistryView(1L, "mall-a", "Mall A", "FILE",
                "source.json", "Asia/Shanghai", "CNY", "ACTIVE", "v1", true,
                null, null, "mall_a");

        assertThatThrownBy(() -> SourceProfileFile.resolve(profileRoot, source, "拒绝采集"))
                .isInstanceOf(PlatformBizException.class)
                .hasMessageContaining("符号链接")
                .hasMessageNotContaining(outside.toString());
    }
}
