package com.graduation.analytics.analysis;

import com.graduation.analytics.metric.MetricAdsCatalog;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * R7-4 源码策略测试（L0，不连库）：把契约的数据来源策略钉在代码上。
 *
 * <p>为什么用源码断言而不是只用行为断言：JSON/落地区读取与商城明细查询都属于"删掉旧路径"的整改，
 * 只要有人后来再加回去（哪怕走了一条当前用不到的兜底分支），行为测试仍然全绿。
 * 这里直接扫描分析包的生产源码，作为"旧路径确实死掉了"的负向证据（契约 §1.1/§1.3）。</p>
 */
class AnalysisSourcePolicyTest {

    /**
     * 被扫描的生产源码目录。
     *
     * <p>用 {@link RepoRoot} 定位（DEF-16）：此前写的是相对路径 {@code src/main/java/...}，
     * 隐含假设"工作目录＝模块 basedir"——surefire 只在 fork 时才如此，
     * 因此 {@code -DforkCount=0}（本机内存受限时唯一跑得动的配置）下本类必然报
     * {@code NoSuchFileException}。定位逻辑收归 platform-common 的测试工具唯一所有者。</p>
     */
    private static final Path ANALYSIS_PACKAGE = RepoRoot.path(
            "analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis");

    /** 禁止在分析包出现的数据来源痕迹（小写比较） */
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "landing",        // 落地区事件
            "jsonl",          // 落地区事件文件格式
            "objectmapper",   // JSON 反序列化
            "jackson",        // JSON 框架
            "mall_",          // 商城业务表（mall_order 等）
            "jdbc",           // 分析服务不得自己连库，只能走 MetricStore/MetricAdsReader
            "files.",         // 文件读取
            "paths.",         // 文件读取
            "readalllines");  // 文件读取

    private static final Pattern ADS_TABLE = Pattern.compile("ads_[a-z_]+");

    @Test
    @DisplayName("分析包源码不出现落地区 JSON / 商城业务表 / 直连数据库的痕迹")
    void analysisPackageHasNoRetiredDataSource() {
        List<Path> sources = analysisSources();
        assertThat(sources).as("分析包生产源码必须存在").isNotEmpty();

        for (Path source : sources) {
            String text = read(source).toLowerCase(Locale.ROOT);
            for (String token : FORBIDDEN_TOKENS) {
                assertThat(text)
                        .as("%s 不得出现数据来源痕迹 '%s'（R7-4 契约 §1.1：分析只读指标库）", source.getFileName(), token)
                        .doesNotContain(token);
            }
        }
    }

    @Test
    @DisplayName("分析服务引用的 ADS 表名全部在 MetricAdsCatalog 白名单内")
    void everyAdsTableLiteralIsWhitelisted() {
        int found = 0;
        for (Path source : analysisSources()) {
            Matcher matcher = ADS_TABLE.matcher(read(source));
            while (matcher.find()) {
                String table = matcher.group();
                found++;
                assertThatCode(() -> MetricAdsCatalog.require(table))
                        .as("%s 引用了白名单外的表名 %s", source.getFileName(), table)
                        .doesNotThrowAnyException();
            }
        }
        assertThat(found).as("分析包应当确实引用了 ADS 宽表").isGreaterThan(0);
    }

    @Test
    @DisplayName("R7-4 六个端点各自的数据来源都在（含用户画像表）")
    void endpointSourcesArePresent() {
        String service = read(ANALYSIS_PACKAGE.resolve("AnalysisService.java"));
        assertThat(service).contains("ads_sale_trend_m", "ads_active_trend_m", "ads_behavior_funnel_m",
                "ads_hot_product_m", "ads_product_conversion_m", "ads_data_quality_m");
        assertThat(read(ANALYSIS_PACKAGE.resolve("RfmService.java"))).contains("ads_user_profile_m");
        // 销售四项指标取 metric_value 的指标码
        assertThat(service).contains("\"gmv\"", "\"net_sale\"", "\"refund_rate\"", "\"full_refund_rate\"");
    }

    private static List<Path> analysisSources() {
        try (Stream<Path> stream = Files.list(ANALYSIS_PACKAGE)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".java")).sorted().toList();
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
}
