package com.graduation.analytics.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.graduation.analytics.testsupport.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * S3-07 反熵守卫：**商品热度（product_heat）的「权重 + 版本」在两处属主之间必须逐字一致**。
 *
 * <p>两处属主（设计 §11.2 L434「product_heat | 1·ln(1+PV)+2·ln(1+收藏)+3·ln(1+加购)+5·ln(1+支付件数) |
 * <b>版本化业务权重</b>，不称学习模型」）：</p>
 * <ol>
 *   <li>meta 库 <b>指标字典</b> {@code metric_definition} 的 {@code product_heat} 种子行
 *       （{@code db/meta/V2__platform_pipeline_quality.sql}）：公式文本 + {@code definition_version}
 *       —— 指标字典 {@code docs/contracts/metric-dictionary.md:31} 逐字「权重来自业务设定，<b>存配置表</b>」，
 *       配置文件是权重与版本的语义所有者；</li>
 *   <li>{@code AdsSql.hotProduct}（Spark 侧 ADS 热门商品 SQL）：权重字面量 + {@code HeatRuleVersion}
 *       —— 运行时实现，必须引用同一枚版本键。</li>
 * </ol>
 *
 * <p><b>为什么需要本守卫</b>：两处同值靠「历史一致性」维持 —— 改权重或改版本而漏改另一处，编译不报错、
 * 普通单测也不报错（Spark 侧单测只对账"公式 vs 夹具"，不解析 meta 迁移文本），只有真跑发布链才会出现
 * 「ADS 行声称 v1、字典已是 v2」这种口径分裂（V2 审计 L177 登记的正是「权重必须保存在规则版本中 |
 * 未做 | 权重调整无版本可溯」）。</p>
 *
 * <p><b>本守卫不修改任何一处的取值</b>（不合并属主、不改权重）：只做只读比对。真要收拢属主，
 * 属于反熵治理动作，须显式裁决后另行执行。</p>
 *
 * <p>判定顺序：①版本一致（{@code AdsSql.HeatRuleVersion} = 字典 {@code definition_version}）
 * → ②权重一致（Spark 公式的 {@code N.0*LOG1P(} 取值 = 字典公式的 {@code N×ln(} 取值）
 * → ③公式只有一处文字属主（同一 SQL 里不许把公式抄两遍）
 * → ④ADS 行携带版本列且引用**单一常量**（不许写死字面量）
 * → ⑤稳定次序键为「热度降序 → 销量降序 → 商品号升序」（设计 §11.5 L455「商品排行…有稳定次序键」）。</p>
 */
class AdsHotProductHeatWeightDriftTest {

    private static final Path ADS_SQL =
            RepoRoot.path("spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala");
    private static final Path METRIC_DEFINITION_SEED = RepoRoot.path(
            "analytics-server/platform-app/src/main/resources/db/meta/V2__platform_pipeline_quality.sql");

    /** 指标字典里 product_heat 的种子行标记（整行含公式文本与 definition_version） */
    private static final String SEED_MARKER = "('product_heat'";

    /** Spark 侧热度权重版本常量：单一字面量，其余位置一律引用它 */
    private static final Pattern HEAT_VERSION_CONSTANT =
            Pattern.compile("val\\s+HeatRuleVersion\\s*:\\s*String\\s*=\\s*\"([^\"]+)\"");

    /** 字典公式里的权重（`1×ln(` / `2×ln(` …） */
    private static final Pattern DICT_WEIGHT = Pattern.compile("([0-9]+)\\s*×\\s*ln\\(");

    /** Spark 公式里的权重（`1.0*LOG1P(` / `2.0*LOG1P(` …） */
    private static final Pattern SQL_WEIGHT =
            Pattern.compile("([0-9]+)\\.0\\s*\\*\\s*LOG1P\\(", Pattern.CASE_INSENSITIVE);

    /** 种子行末尾的版本字段（`..., '', 'v1'),`） */
    private static final Pattern SEED_VERSION = Pattern.compile("'([^']*)'\\s*\\)\\s*,?\\s*$");

    /** 版本列必须由常量渲染，不许写死字面量 */
    private static final Pattern VERSION_REFERENCE =
            Pattern.compile("'\\$HeatRuleVersion'\\s+AS\\s+rule_version", Pattern.CASE_INSENSITIVE);

    /** 稳定次序键（§11.5 L455；三级形态见 V2 指导书 L639） */
    private static final String ORDER_KEY = "ORDER BY heat_score DESC, buy DESC, product_id ASC";

    // ------------------------------------------------------------ 解析

    /** 指标字典的 product_heat 种子行（**必须恰有一行**：多于一行说明字典里有两个热度属主） */
    private static String seedLine() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String line : Files.readAllLines(METRIC_DEFINITION_SEED, StandardCharsets.UTF_8)) {
            if (line.contains(SEED_MARKER)) {
                hits.add(line);
            }
        }
        assertThat(hits)
                .as("指标字典 %s 里 product_heat 种子行应恰有 1 行", METRIC_DEFINITION_SEED.getFileName())
                .hasSize(1);
        return hits.get(0);
    }

    /** 字典侧权重（按公式文本出现顺序） */
    private static List<String> dictWeights() throws IOException {
        List<String> out = new ArrayList<>();
        Matcher m = DICT_WEIGHT.matcher(seedLine());
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 字典侧版本 */
    private static String dictVersion() throws IOException {
        Matcher m = SEED_VERSION.matcher(seedLine().strip());
        assertThat(m.find())
                .as("指标字典 product_heat 种子行末尾未解析出版本字段：%s", seedLine().strip())
                .isTrue();
        return m.group(1);
    }

    /** 只取 `def hotProduct` 这一个方法的文本（避免误抓本文件其他 `LOG1P` 片段） */
    private static String hotProductBlock() throws IOException {
        String text = Files.readString(ADS_SQL, StandardCharsets.UTF_8);
        int start = text.indexOf("def hotProduct(");
        assertThat(start).as("AdsSql 未找到 def hotProduct").isGreaterThanOrEqualTo(0);
        int next = text.indexOf("\n  def ", start + 1);
        return next < 0 ? text.substring(start) : text.substring(start, next);
    }

    /** Spark 侧权重（按公式文本出现顺序） */
    private static List<String> sqlWeights() throws IOException {
        List<String> out = new ArrayList<>();
        Matcher m = SQL_WEIGHT.matcher(hotProductBlock());
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Spark 侧版本常量（**必须恰好一处声明**：多处声明即多个属主） */
    private static String sparkVersion() throws IOException {
        Matcher m = HEAT_VERSION_CONSTANT.matcher(Files.readString(ADS_SQL, StandardCharsets.UTF_8));
        List<String> hits = new ArrayList<>();
        while (m.find()) {
            hits.add(m.group(1));
        }
        assertThat(hits)
                .as("AdsSql 应恰好声明一处 `val HeatRuleVersion: String = \"...\"`，实际 %s", hits)
                .hasSize(1);
        return hits.get(0);
    }

    // ------------------------------------------------------------ 断言

    @Test
    void 热度权重版本等于指标字典的定义版本() throws IOException {
        assertThat(sparkVersion())
                .as("ADS 行声称的热度版本与指标字典 product_heat.definition_version 不一致（权重调整将失去可溯性）")
                .isEqualTo(dictVersion());
    }

    @Test
    void 热度权重与指标字典公式一致() throws IOException {
        assertThat(dictWeights())
                .as("指标字典 product_heat 公式应恰含 4 个权重（1×ln/2×ln/3×ln/5×ln）")
                .hasSize(4);
        assertThat(sqlWeights())
                .as("Spark 热度公式的权重与指标字典不一致（字典=%s）", dictWeights())
                .isEqualTo(dictWeights());
    }

    @Test
    void Spark侧热度公式只有一处文字属主() throws IOException {
        String block = hotProductBlock();
        long log1p = Pattern.compile("LOG1P\\(", Pattern.CASE_INSENSITIVE).matcher(block).results().count();
        assertThat(log1p)
                .as("热度公式在 def hotProduct 里应只有一处（LOG1P 出现 4 次 = 四项权重），"
                        + "实际 %s 次 ⇒ 公式被抄了 %s 遍，两处字面量将来会各自漂移", log1p, log1p / 4)
                .isEqualTo(4);
    }

    @Test
    void ADS热门商品行携带热度版本列且引用单一常量() throws IOException {
        long refs = VERSION_REFERENCE.matcher(hotProductBlock()).results().count();
        assertThat(refs)
                .as("def hotProduct 应恰有一处 `'$HeatRuleVersion' AS rule_version`（设计 §9.3 L320 每行带定义版本）")
                .isEqualTo(1);
    }

    @Test
    void 热门商品排行使用三级稳定次序键() throws IOException {
        String upper = hotProductBlock().toUpperCase(Locale.ROOT);
        String key = ORDER_KEY.toUpperCase(Locale.ROOT);
        assertThat(upper)
                .as("排行次序键必须是「热度降序 → 销量降序 → 商品号升序」（设计 §11.5 L455 稳定次序键）")
                .contains(key);
        assertThat(upper.indexOf(key))
                .as("次序键必须落在 ROW_NUMBER 的窗口里（否则 rank_no 仍不确定）")
                .isGreaterThan(upper.indexOf("ROW_NUMBER() OVER ("));
    }
}
