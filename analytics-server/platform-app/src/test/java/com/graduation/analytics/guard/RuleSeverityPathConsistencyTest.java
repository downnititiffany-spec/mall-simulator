package com.graduation.analytics.guard;

import com.graduation.analytics.metric.MetricQualityGate;
import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.QualityRuleDefinition;
import com.graduation.analytics.metric.RuleSeverity;
import com.graduation.analytics.metric.publish.MetricPublisherPort;
import com.graduation.analytics.metric.publish.MetricPublishValidator;
import com.graduation.analytics.pipeline.DataQualityGate;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <b>跨模块严重度口径一致性护栏（R01 首版缺口 #1）。</b>
 *
 * <p><b>为什么必须放在 platform-app</b>：实测依赖方向为
 * {@code warehouse-pipeline → metric-analysis → platform-common}，且
 * {@code metric-analysis} 只依赖 {@code platform-common}。因此
 * 「读侧质量门」{@link DataQualityGate}（在 warehouse-pipeline）与
 * 「发布侧判定」{@link MetricPublishValidator}（在 metric-analysis）
 * 在各自模块内**永远无法出现在同一个类路径上** —— 唯一同时依赖这两者的是
 * {@code platform-app}（已核对其 pom：同时含 {@code warehouse-pipeline} 与 {@code metric-analysis}）。
 * 这就是本类存在的理由：把「两条路径必须同严重度」钉在一个能同时看见两者的模块里。</p>
 *
 * <p><b>本类要防的分叉形态</b>：R01 审计前，两条路径各自持有严重度判据（读侧按库中字面
 * {@code severity}，发布侧写死 {@code "BLOCKING".equals(...)}）。后果是**同一条质量结果
 * 在「门禁是否阻断」与「能否发布」上给出不同答案**，且库里字面标签可被作业侧写错而直接改变门禁结论。
 * 现两条路径都改为唯一所有者 {@link RuleSeverity#resolve}，本类即为该收口的回归护栏。</p>
 *
 * <p><b>本类不做的事</b>：不重复测试 {@code RuleSeverity} 自身的档位语义（那是
 * {@code RuleSeverityTest} 的职责），也不测试阈值算术。本类只断言**两条独立路径的结论一致**，
 * 以及未登记码在两边的保守行为。</p>
 *
 * <p><b>已验证状态</b>：本类曾因另一泳道未入库文件
 * （{@code IngestionManifestRuntimePatrolTest.java} 的 {@code IOException} 编译错误）导致
 * {@code platform-app} 的 test-compile 整体失败而**无法编译、无法执行**。
 * 该阻塞于 2026-09-14 12:4x 解除，本类随即执行通过；执行证据（命令/退出码/用例数）
 * 见 {@code docs/acceptance/f88-dq-severity-20260912/raw/}。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuleSeverityPathConsistencyTest {

    /** 冻结规则集的获取方式与生产一致：目录默认集按全源冻结（见 q01-version-scope.md §1）。 */
    private static final QualityRuleCatalog.FrozenRules RULES = QualityRuleCatalog.DEFAULT.freeze(null);

    /** 目录中不存在的规则码；必须两边都保守（停止发布）。 */
    private static final String UNREGISTERED_CODE = "BRAND_NEW_CROSS_MODULE_RULE";

    @Mock
    private DataQualityResultMapper qualityMapper;

    /**
     * 第 1 步：**两条独立路径在同一冻结规则集下必须同严重度**。
     *
     * <p>取值域不写死字面清单，而是**遍历目录里每一条已登记定义**（实测 35 条）——
     * 这样将来新增规则码时本用例自动覆盖，新增一条就多断言一轮；若某条新规则的档位在两条路径上
     * 被实现成不同结果，本用例立即变红，而不需要有人记得来改测试。</p>
     *
     * <p>对每个规则码分别验 {@code passed=1}（通过）与 {@code passed=0}（未通过）：
     * 读侧取 {@code GateDecision.blockingRules()} 是否含该码，发布侧取
     * {@link MetricPublishValidator#blocked} 的布尔结论，二者必须等价。</p>
     */
    @Test
    @DisplayName("同一冻结规则集下：读侧质量门与发布侧判定对每个已登记码结论一致")
    void bothPathsAgreeForEveryRegisteredRuleCode() {
        List<QualityRuleDefinition> definitions = QualityRuleCatalog.DEFAULT.definitions();
        assertThat(definitions)
                .as("目录必须有定义，否则本用例会空转而假绿")
                .isNotEmpty();

        List<String> disagreements = new ArrayList<>();
        int compared = 0;

        for (QualityRuleDefinition def : definitions) {
            String code = def.ruleCode();
            for (int passed : new int[]{1, 0}) {
                compared++;

                RuleSeverity.RuleVerdict verdict = RuleSeverity.resolve(RULES, code, passed);

                // 路径 A：读侧质量门（经 mapper 读结果行）
                when(qualityMapper.selectList(any())).thenReturn(List.of(result(code, "WARN", passed)));
                DataQualityGate.GateDecision decision = new DataQualityGate(qualityMapper).decisionForRun(7L);
                boolean gateBlocks = decision.blockingRules().contains(code);

                // 路径 B：发布侧判定（静态入口，与真实发布路径同一实现）
                boolean publishBlocks = MetricPublishValidator.blocked(List.of(check(code, "WARN", passed)), RULES);

                if (gateBlocks != verdict.blocks() || publishBlocks != verdict.blocks()) {
                    disagreements.add(code + " passed=" + passed
                            + " 期望(唯一所有者)=" + verdict.blocks()
                            + " 门禁=" + gateBlocks + " 发布=" + publishBlocks
                            + " 有效严重度=" + verdict.effectiveSeverity());
                }
            }
        }

        assertThat(compared).as("必须真的比过规则（防止目录为空导致空转假绿）").isGreaterThan(0);
        assertThat(disagreements)
                .as("读侧质量门与发布侧判定出现分叉 ⇒ 严重度存在第二个所有者，必须回到 RuleSeverity 收口")
                .isEmpty();

        // 证据留痕（供 raw/ 记录）：证明本轮真的比过 N 条规则 × 2 个 passed 值，不是空转假绿
        long blockingOnFailure = definitions.stream()
                .map(QualityRuleDefinition::ruleCode).distinct()
                .filter(code -> RuleSeverity.resolve(RULES, code, 0).blocks()).count();
        System.out.println("[GUARD-EVIDENCE] catalogDefinitions=" + definitions.size()
                + " comparisons=" + compared
                + " blockingCodesOnFailure=" + blockingOnFailure);
    }

    /**
     * 第 1 步的补充：两条路径的**失败详情**也必须一致。
     *
     * <p>只断言布尔结论不够：{@code PipelineService} 的发布前门断言读的是
     * {@link DataQualityGate#blockingFailuresForRun(Long, QualityRuleCatalog.FrozenRules)}，
     * 而页面/日志展示的是 {@link MetricPublishValidator#failedRules}。
     * 若两者一致但详情不一致，会出现「门禁说没阻断、发布说失败在 X」的自相矛盾记录。</p>
     */
    @Test
    @DisplayName("同一冻结规则集下：阻断规则码清单与发布侧 failedRules 指向同一集合")
    void blockingRuleCodeListsAreTheSameOnBothPaths() {
        // 全部按 passed=0（未通过）构造：只有真正的阻断档才会出现在两侧清单里
        List<DataQualityResult> gateRows = new ArrayList<>();
        List<MetricPublisherPort.Check> pubChecks = new ArrayList<>();
        for (QualityRuleDefinition def : QualityRuleCatalog.DEFAULT.definitions()) {
            gateRows.add(result(def.ruleCode(), "WARN", 0));
            pubChecks.add(check(def.ruleCode(), "WARN", 0));
        }
        when(qualityMapper.selectList(any())).thenReturn(gateRows);
        DataQualityGate.GateDecision decision = new DataQualityGate(qualityMapper).decisionForRun(7L);

        List<String> expected = QualityRuleCatalog.DEFAULT.definitions().stream()
                .map(QualityRuleDefinition::ruleCode)
                .filter(code -> RuleSeverity.resolve(RULES, code, 0).blocks())
                .distinct()
                .sorted()
                .toList();

        List<String> gateBlocking = decision.blockingRules().stream().distinct().sorted().toList();
        List<String> publishFailed = Arrays.stream(MetricPublishValidator.failedRules(pubChecks, RULES).split(","))
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .toList();

        assertThat(gateBlocking).as("门禁阻断清单必须等于唯一所有者的判定").isEqualTo(expected);
        assertThat(publishFailed).as("发布侧失败清单必须等于唯一所有者的判定").isEqualTo(expected);

        // 证据留痕（供 raw/ 记录）：证明本轮真的比过整份目录、不是空转假绿
        System.out.println("[GUARD-EVIDENCE] rowsCompared=" + gateRows.size()
                + " blockingCodes=" + expected.size() + " => " + expected);
    }

    /**
     * 第 2 步：**未登记规则码不可盲信传来的 {@code WARN}**（§7.3.1 line 524 逐字要求
     * 「未知规则码不可盲信传来的 WARN，应停止发布并报未登记规则」）。
     *
     * <p>反例设计：传入的字面 {@code severity} 恰好是最"无害"的 {@code WARN}，
     * 且 {@code passed} 取 {@code 1}（通过）。若任一实现存在「按字面 severity 判」的旧逻辑，
     * 这里就会被放行。断言两边都必须**阻断**，且读侧必须把该码报进
     * {@code unregisteredRules}（「报未登记规则」与「真的停止发布」必须同时成立）。</p>
     */
    @Test
    @DisplayName("未登记码即使回传 WARN 且 passed=1，两条路径都必须停止发布并报未登记")
    void unregisteredCodeWithHarmlessLiteralStillStopsPublishOnBothPaths() {
        RuleSeverity.RuleVerdict verdict = RuleSeverity.resolve(RULES, UNREGISTERED_CODE, 1);
        assertThat(verdict.registered()).as("前提：该码确实未登记").isFalse();

        when(qualityMapper.selectList(any())).thenReturn(List.of(result(UNREGISTERED_CODE, "WARN", 1)));
        DataQualityGate.GateDecision decision = new DataQualityGate(qualityMapper).decisionForRun(7L);

        assertThat(decision.unregisteredRules())
                .as("读侧必须报出未登记规则码（而不只是判红）")
                .contains(UNREGISTERED_CODE);
        assertThat(decision.status())
                .as("未登记 ⇒ 停止发布，不得冒充 PASS")
                .isEqualTo(MetricQualityGate.FAIL);
        assertThat(decision.blockingRules())
                .as("未登记码必须同时进 blockingRules，否则「报未登记」与「停止发布」脱节")
                .contains(UNREGISTERED_CODE);

        assertThat(MetricPublishValidator.blocked(List.of(check(UNREGISTERED_CODE, "WARN", 1)), RULES))
                .as("发布侧同样不得盲信字面 WARN")
                .isTrue();
        assertThat(MetricPublishValidator.failedRules(List.of(check(UNREGISTERED_CODE, "WARN", 1)), RULES))
                .as("发布侧必须点名该未登记码，不能只说'有失败'")
                .isEqualTo(UNREGISTERED_CODE);
    }

    /**
     * 第 3 步：{@link RuleSeverity#isKnownSeverity} 的**边界纪律**。
     *
     * <p>总控点 ② 警告：该方法**只许**用于校验
     * {@code quality_rule_definition.severity} 的取值域，**绝不可**用来决定某条规则是否可发布。
     * 本用例用反证把这条边界钉住：一个「合法严重度字面」并不蕴含「可发布」——
     * {@code isKnownSeverity("WARN") == true}，但未登记码带着 {@code WARN} 仍必须停止发布。
     * 同时 {@code isKnownSeverity} 对乱填值为 false，保证它作为**取值域校验器**是有效的。</p>
     */
    @Test
    @DisplayName("isKnownSeverity 只校验取值域：合法字面不蕴含可发布")
    void isKnownSeverityValidatesTheDomainOnly() {
        // (a) 作为取值域校验器有效
        assertThat(RuleSeverity.isKnownSeverity("BLOCKING")).isTrue();
        assertThat(RuleSeverity.isKnownSeverity("ERROR")).isTrue();
        assertThat(RuleSeverity.isKnownSeverity("WARN")).isTrue();
        assertThat(RuleSeverity.isKnownSeverity("INFO")).isTrue();
        assertThat(RuleSeverity.isKnownSeverity(" warn ")).as("大小写与空白应被容忍").isTrue();
        assertThat(RuleSeverity.isKnownSeverity(null)).isFalse();
        assertThat(RuleSeverity.isKnownSeverity("")).isFalse();
        assertThat(RuleSeverity.isKnownSeverity("UNKNOWN_LEVEL")).as("乱填值必须被判为非法").isFalse();
        assertThat(RuleSeverity.isKnownSeverity(UNREGISTERED_CODE)).isFalse();

        // (b) 反证：合法字面 ≠ 可发布。若有人拿 isKnownSeverity 当发布判据，本断言会失败。
        assertThat(RuleSeverity.isKnownSeverity("WARN")).isTrue();
        assertThat(MetricPublishValidator.blocked(List.of(check(UNREGISTERED_CODE, "WARN", 1)), RULES))
                .as("字面合法但规则未登记 ⇒ 仍停止发布；故 isKnownSeverity 不能当发布判据")
                .isTrue();
    }

    /**
     * 一条质量结果行；{@code literalSeverity} 是**会被归一化覆盖**的字面标签，不参与判定。
     *
     * <p>F-88/V20 起结果行还带 {@code effectiveSeverity}（生效档位）与版本化三列；本夹具刻意
     * **只设 {@code severity}**、其余留空，以同时钉住「门禁不看结果行任何档位列」：
     * 判定输入只有 (冻结规则集, ruleCode, passed)，两列写什么都不改变结论
     * （单测侧的反证见 warehouse-pipeline 的 {@code DataQualityGateTest#gateIgnoresBothSeverityColumns}）。</p>
     */
    private static DataQualityResult result(String ruleCode, String literalSeverity, Integer passed) {
        DataQualityResult r = new DataQualityResult();
        r.setRuleCode(ruleCode);
        r.setSeverity(literalSeverity);
        r.setPassed(passed);
        return r;
    }

    /** 一条发布侧 check；{@code literalSeverity} 同上，不参与判定。 */
    private static MetricPublisherPort.Check check(String ruleCode, String literalSeverity, Integer passed) {
        return new MetricPublisherPort.Check(ruleCode, "ADS", "ads_operation_overview_m",
                10L, passed != null && passed == 1 ? 0L : 3L,
                literalSeverity, passed != null && passed == 1, "跨模块一致性护栏夹具");
    }
}
