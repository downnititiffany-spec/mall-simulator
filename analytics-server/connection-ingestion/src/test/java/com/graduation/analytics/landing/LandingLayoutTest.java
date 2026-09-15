package com.graduation.analytics.landing;

import com.graduation.analytics.common.PlatformBizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S2-04B：Landing 布局词汇的唯一所有者（设计 §8.2 规则 1/3/4、§8.3）。
 *
 * <p>采集端要读的是哪一种 Landing 布局（滚动日志 vs Flume spool 原始区）必须是一个**封闭词汇**：
 * 空值＝未配置（等价于默认滚动日志，兼容 V22 之前的存量行），已登记值＝精确匹配，其它一律拒绝。
 * 为什么拒绝而不是回落到默认：把 {@code flume-raw} 这种大小写/拼写错误静默当成 {@code ROLLING_LOG}，
 * 会去读 {@code events/} 而运维以为在读 {@code raw/}——结果"采集成功但 0 条"，这属于最难查的一类错。</p>
 */
class LandingLayoutTest {

    @Test
    @DisplayName("空值（null/空白）＝未配置：取默认滚动日志布局，且归一为 null 不落库")
    void blankMeansDefaultAndNormalizesToNull() {
        assertThat(LandingLayout.effective(null)).isEqualTo(LandingLayout.ROLLING_LOG);
        assertThat(LandingLayout.effective("")).isEqualTo(LandingLayout.ROLLING_LOG);
        assertThat(LandingLayout.effective("   ")).isEqualTo(LandingLayout.ROLLING_LOG);
        assertThat(LandingLayout.normalizeForWrite(null)).isNull();
        assertThat(LandingLayout.normalizeForWrite(" ")).isNull();
    }

    @Test
    @DisplayName("已登记值精确匹配：只容忍首尾空白，不做大小写折叠（大小写写错＝写错）")
    void registeredValuesMatchExactly() {
        assertThat(LandingLayout.effective("ROLLING_LOG")).isEqualTo(LandingLayout.ROLLING_LOG);
        assertThat(LandingLayout.effective("FLUME_RAW")).isEqualTo(LandingLayout.FLUME_RAW);
        assertThat(LandingLayout.effective("  FLUME_RAW  ")).isEqualTo(LandingLayout.FLUME_RAW);
        assertThat(LandingLayout.normalizeForWrite("FLUME_RAW")).isEqualTo("FLUME_RAW");
        assertThat(LandingLayout.normalizeForWrite(" FLUME_RAW ")).isEqualTo("FLUME_RAW");
    }

    @Test
    @DisplayName("未登记值一律拒绝（写入侧 PARAM_INVALID，读取侧同样 fail-closed）")
    void unknownValuesAreRejectedOnBothPaths() {
        for (String bad : new String[]{"flume-raw", "rolling_log", "SPOOL", "events", "TAILDIR"}) {
            assertThatThrownBy(() -> LandingLayout.normalizeForWrite(bad))
                    .as("写入侧必须拒绝未登记值: %s", bad)
                    .isInstanceOf(PlatformBizException.class)
                    .hasMessageContaining("landing_layout");
            assertThatThrownBy(() -> LandingLayout.effective(bad))
                    .as("读取侧同样拒绝（存量行值被写坏时不得静默回落到默认布局）: %s", bad)
                    .isInstanceOf(PlatformBizException.class)
                    .hasMessageContaining("landing_layout");
        }
    }

    @Test
    @DisplayName("列宽不变量：装得下最长登记名，且不超过 64（上界＝别把这列当描述字段）")
    void columnWidthFitsEveryRegisteredName() {
        int longest = LandingLayout.longestNameLength();
        assertThat(longest)
                .as("最长登记名必须是真实存在的那个：%s", java.util.Arrays.toString(LandingLayout.values()))
                .isEqualTo(java.util.Arrays.stream(LandingLayout.values())
                        .mapToInt(layout -> layout.name().length()).max().orElse(0));
        assertThat(LandingLayout.COLUMN_WIDTH).isGreaterThanOrEqualTo(longest).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("输入根按布局推出：滚动日志＝<landing>/events（不递归），Flume＝<landing>/raw（递归）")
    void inputRootAndRecursionComeFromLayout() {
        Path landing = Paths.get("D:/landing/demo");
        assertThat(LandingLayout.ROLLING_LOG.inputRoot(landing)).isEqualTo(landing.resolve("events"));
        assertThat(LandingLayout.FLUME_RAW.inputRoot(landing)).isEqualTo(landing.resolve("raw"));
        assertThat(LandingLayout.ROLLING_LOG.recursive()).isFalse();
        assertThat(LandingLayout.FLUME_RAW.recursive()).isTrue();
    }
}
