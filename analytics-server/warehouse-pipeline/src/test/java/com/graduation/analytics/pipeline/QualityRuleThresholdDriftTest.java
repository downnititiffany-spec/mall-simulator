package com.graduation.analytics.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.QualityRuleDefinition;
import com.graduation.analytics.testsupport.RepoRoot;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * S3-05 反熵守卫：**同一条质量规则的「版本 + 阈值」在三处属主之间必须逐字一致**。
 *
 * <p>三处属主（设计 §7.3.1 line 520 只承认第一个是所有者，另两处是运行时实现）：</p>
 * <ol>
 *   <li>{@link QualityRuleCatalog}（`quality_rule_definition` 的版本化单一所有者：版本 + 阈值 JSON）；</li>
 *   <li>{@code AdsSql.dataQuality}（Spark 侧 ADS 质量大盘 SQL：阈值字面量 + 规则定义版本）；</li>
 *   <li>{@link QualityChecker}（Landing 层运行时：阈值字面量）。</li>
 * </ol>
 *
 * <p><b>为什么需要本守卫</b>：三处同值靠「历史一致性」维持 —— 改一处而漏改另两处，编译不报错、
 * 普通单测也不报错，只有真跑发布链才会出现「大盘说 passed=1 而门禁说阻断」这种口径分裂
 * （V2 审计 §24.3 已登记「双所有者残留」）。因此把三处按规则码逐条对账，任何一处漂移立刻变红。</p>
 *
 * <p><b>本守卫不修改任何一处的取值</b>（不合并属主、不改阈值）：只做只读比对。真要收拢属主，
 * 属于反熵治理动作，须显式裁决后另行执行。</p>
 *
 * <p>判定顺序：①规则码登记（ADS 码必须在目录中登记）→ ②版本一致（ADS `rule_version` = 目录 `version`）
 * → ③阈值数值一致（ADS / QualityChecker 的阈值字面量 = 目录阈值 JSON 里的唯一数值）。</p>
 */
class QualityRuleThresholdDriftTest {

    private static final Path ADS_SQL =
            RepoRoot.path("spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala");
    private static final Path QUALITY_CHECKER = RepoRoot.path(
            "analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/QualityChecker.java");

    /** 规则明文里的阈值字面量：去掉 `<=` 前缀后应与目录阈值数值相等 */
    private static final Pattern CHECKER_RULE = Pattern.compile(
            "rule\\(runId,\\s*\"([A-Z_]+)\",\\s*[^,]+,\\s*[^,]+,\\s*\"([^\"]+)\"");
    private static final Pattern JSON_NUMBER = Pattern.compile("\"([A-Za-z]+)\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    /** Spark 侧的规则版本常量（单一字面量，规则分支一律引用它，不许各写各的） */
    private static final Pattern VERSION_CONSTANT =
            Pattern.compile("val\\s+QualityRuleVersion\\s*=\\s*([0-9]+)");
    private static final Pattern VERSION_REFERENCE =
            Pattern.compile("\\$QualityRuleVersion\\s+AS\\s+rule_version");

    /** ADS `dataQuality` 的一条规则分支 */
    private record AdsRule(String code, String threshold, int version) {
    }

    // ------------------------------------------------------------ 解析

    /** 只取 `def dataQuality` 这一个方法的文本（避免误抓本文件其他 `AS rule_code` 片段） */
    private static String dataQualityBlock() throws IOException {
        String text = Files.readString(ADS_SQL, StandardCharsets.UTF_8);
        int start = text.indexOf("def dataQuality(");
        assertThat(start).as("AdsSql 未找到 def dataQuality").isGreaterThanOrEqualTo(0);
        int next = text.indexOf("\n  def ", start + 1);
        return next < 0 ? text.substring(start) : text.substring(start, next);
    }

    /** 解析 ADS 质量大盘的规则分支：按 `UNION ALL` 切片，每片必须恰好一条 rule_code/threshold/rule_version */
    private static List<AdsRule> adsRules() throws IOException {
        String block = dataQualityBlock();
        Matcher constMatcher = VERSION_CONSTANT.matcher(Files.readString(ADS_SQL, StandardCharsets.UTF_8));
        assertThat(constMatcher.find())
                .as("AdsSql 未声明 `val QualityRuleVersion = <版本>`（规则版本必须有单一字面量）").isTrue();
        int version = Integer.parseInt(constMatcher.group(1));
        long references = VERSION_REFERENCE.matcher(block).results().count();
        assertThat(references)
                .as("ADS 规则分支引用 `$QualityRuleVersion AS rule_version` 的次数应为 4，实际 %s", references)
                .isEqualTo(4);
        Pattern code = Pattern.compile("'([A-Z_]+)'\\s+AS\\s+rule_code");
        Pattern threshold = Pattern.compile("'([0-9.]+)'\\s+AS\\s+threshold");
        List<AdsRule> rules = new ArrayList<>();
        for (String chunk : block.split("UNION ALL")) {
            Matcher c = code.matcher(chunk);
            if (!c.find()) {
                continue;
            }
            Matcher t = threshold.matcher(chunk);
            assertThat(t.find())
                    .as("ADS 规则 %s 缺 '...' AS threshold", c.group(1)).isTrue();
            assertThat(VERSION_REFERENCE.matcher(chunk).find())
                    .as("ADS 规则 %s 缺规则定义版本列（§9.3 L335 要求规则版本随行落库）", c.group(1))
                    .isTrue();
            rules.add(new AdsRule(c.group(1), t.group(1), version));
        }
        return rules;
    }

    /** 解析 QualityChecker 的 `rule(runId, "<码>", ..., "<阈值>", ...)` 调用 */
    private static Map<String, String> checkerThresholds() throws IOException {
        String text = Files.readString(QUALITY_CHECKER, StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = CHECKER_RULE.matcher(text);
        while (m.find()) {
            out.put(m.group(1), m.group(2).replace("<=", "").trim());
        }
        return out;
    }

    /** 目录阈值 JSON 里的唯一数值（多于一个数值 ⇒ 直接失败，迫使本守卫显式扩展而不是猜） */
    private static BigDecimal catalogThreshold(QualityRuleDefinition def) {
        String json = def.thresholdJson();
        assertThat(json).as("规则 %s 无阈值 JSON", def.ruleCode()).isNotNull();
        Matcher m = JSON_NUMBER.matcher(json);
        List<String> values = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        while (m.find()) {
            fields.add(m.group(1));
            values.add(m.group(2));
        }
        assertThat(values)
                .as("规则 %s 的阈值 JSON 应恰含一个数值字段，实际 %s", def.ruleCode(), fields)
                .hasSize(1);
        return new BigDecimal(values.get(0));
    }

    private static QualityRuleDefinition require(QualityRuleCatalog.FrozenRules frozen, String code) {
        Optional<QualityRuleDefinition> def = frozen.find(code);
        assertThat(def)
                .as("质量规则码未在 QualityRuleCatalog 登记: %s（§7.3.1 line 524：未登记规则不得发布）", code)
                .isPresent();
        return def.get();
    }

    private static QualityRuleCatalog.FrozenRules frozen() {
        return QualityRuleCatalog.DEFAULT.freeze(null);
    }

    // ------------------------------------------------------------ 断言

    @Test
    void ADS质量大盘的每条规则都已在规则目录登记() throws IOException {
        QualityRuleCatalog.FrozenRules frozen = frozen();
        List<AdsRule> rules = adsRules();
        assertThat(rules).as("ADS 质量大盘规则数应为 4").hasSize(4);
        Set<String> codes = new LinkedHashSet<>();
        for (AdsRule r : rules) {
            require(frozen, r.code());
            assertThat(codes.add(r.code())).as("ADS 质量大盘规则码重复: %s", r.code()).isTrue();
        }
    }

    @Test
    void ADS质量大盘的规则定义版本等于目录版本() throws IOException {
        QualityRuleCatalog.FrozenRules frozen = frozen();
        for (AdsRule r : adsRules()) {
            QualityRuleDefinition def = require(frozen, r.code());
            assertThat(r.version())
                    .as("ADS 行 rule_version(%s) 与目录 version(%s) 不一致：规则 %s",
                            r.version(), def.version(), r.code())
                    .isEqualTo(def.version());
        }
    }

    @Test
    void ADS质量大盘的阈值等于目录阈值() throws IOException {
        QualityRuleCatalog.FrozenRules frozen = frozen();
        for (AdsRule r : adsRules()) {
            QualityRuleDefinition def = require(frozen, r.code());
            assertThat(new BigDecimal(r.threshold()))
                    .as("ADS 阈值(%s) 与目录阈值(%s) 不一致：规则 %s",
                            r.threshold(), def.thresholdJson(), r.code())
                    .isEqualByComparingTo(catalogThreshold(def));
        }
    }

    @Test
    void QualityChecker的阈值等于目录阈值() throws IOException {
        QualityRuleCatalog.FrozenRules frozen = frozen();
        Map<String, String> checker = checkerThresholds();
        for (AdsRule r : adsRules()) {
            QualityRuleDefinition def = require(frozen, r.code());
            assertThat(checker)
                    .as("QualityChecker 未产出规则 %s（ADS 大盘有、Landing 运行时没有 ⇒ 口径分裂）", r.code())
                    .containsKey(r.code());
            assertThat(new BigDecimal(checker.get(r.code())))
                    .as("QualityChecker 阈值(%s) 与目录阈值(%s) 不一致：规则 %s",
                            checker.get(r.code()), def.thresholdJson(), r.code())
                    .isEqualByComparingTo(catalogThreshold(def));
        }
    }

    /**
     * ADS 质量大盘必须覆盖目录里**全部 Landing 层带阈值**的规则。
     *
     * <p>契约出处：{@code AdsSql.dataQuality} 的类注释「4 规则与 QualityChecker 同名同阈值」、
     * 设计 §9.3 L335（质量专题）与 §12.3 L512（每条规则记录阈值/版本）。
     * 若将来目录新增 Landing 阈值规则而 ADS 大盘没跟上，本用例会指出需要显式裁决的差异
     * （要么补进大盘，要么在目录里说明为什么它不该进大盘）。</p>
     */
    @Test
    void ADS质量大盘覆盖全部Landing层带阈值的规则() throws IOException {
        QualityRuleCatalog.FrozenRules frozen = frozen();
        Set<String> landing = new LinkedHashSet<>();
        for (QualityRuleDefinition def : frozen.definitions()) {
            if (def.enabled() && QualityRuleCatalog.STAGE_LANDING.equals(def.stage())
                    && def.thresholdJson() != null) {
                landing.add(def.ruleCode());
            }
        }
        Set<String> ads = new LinkedHashSet<>();
        for (AdsRule r : adsRules()) {
            ads.add(r.code());
        }
        assertThat(ads)
                .as("ADS 质量大盘规则集合与目录 Landing 阈值规则集合不一致（多出的或缺失的都需显式裁决）")
                .isEqualTo(landing);
    }
}
