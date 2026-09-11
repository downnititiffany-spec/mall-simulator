package com.graduation.analytics.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Landing 根解析唯一实现的回归测试（V2.1 §5.4/§6.2）。
 *
 * <p>本测试来自一个真实缺陷：旧实现（采集/流水线/落地存储各一份）把标准写法
 * {@code file:///D:/landing} 截成 {@code /D:/landing}，Windows 上抛
 * {@code InvalidPathException: Illegal char <:> at index 2}；而 {@code hdfs://...} 在旧实现里
 * 会被静默当作相对目录。故此处同时钉住"标准写法可用"与"非法写法报错而不是猜"。</p>
 */
class LandingUriTest {

    @Test
    @DisplayName("标准 file:/// URI（含 Windows 盘符）解析为等价本机路径")
    void resolvesStandardFileUri(@TempDir Path dir) {
        String uri = dir.toAbsolutePath().toUri().toString();
        assertThat(uri).as("toUri() 必须给出三斜杠形式").startsWith("file:/");
        assertThat(LandingUri.resolve(uri)).isEqualTo(dir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("历史写法与裸路径保持原语义")
    void resolvesLegacyAndBareForms() {
        Path legacy = Path.of(".", "landing").toAbsolutePath().normalize();
        assertThat(LandingUri.resolve("file://./landing")).isEqualTo(legacy);
        assertThat(LandingUri.resolve("./landing")).isEqualTo(legacy);
        assertThat(LandingUri.resolve("landing")).isEqualTo(legacy);
        assertThat(LandingUri.resolve("  file://./landing  ")).as("两端空白应被忽略").isEqualTo(legacy);
    }

    @Test
    @DisplayName("盘符无斜杠写法 file://D:/landing 与三斜杠写法等价")
    void resolvesDriveLetterWithoutSlash() {
        Path withSlash = LandingUri.resolve("file:///D:/landing");
        Path withoutSlash = LandingUri.resolve("file://D:/landing");
        assertThat(withoutSlash).isEqualTo(withSlash);
    }

    @Test
    @DisplayName("空值 / 主机名形式 / 非 file 协议一律报错，绝不回退默认目录")
    void rejectsAmbiguousOrUnsupportedValues() {
        for (String bad : new String[]{null, "", "   ", "file://host/share/landing", "hdfs://namenode:8020/landing"}) {
            assertThatThrownBy(() -> LandingUri.resolve(bad))
                    .as("landingUri=%s 必须明确报错", bad)
                    .isInstanceOf(MallBizException.class)
                    .hasMessageContaining("landingUri");
        }
    }
}
