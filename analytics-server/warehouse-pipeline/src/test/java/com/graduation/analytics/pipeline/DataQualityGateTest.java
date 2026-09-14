package com.graduation.analytics.pipeline;

import com.graduation.analytics.metric.MetricQualityGate;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R7-4 质量门 L0 单测：analytics_meta.data_quality_result → PASS/FAIL/UNKNOWN 的判定口径。
 *
 * <p>口径 = D-142 §1（指导书 §7.3 原文不变）：{@code BLOCKING} 与 {@code ERROR} 未通过都算阻断，
 * {@code WARN}/{@code INFO} 未通过只记录、不阻断。看板信封的 {@code qualityStatus} 直接由本组件决定，
 * 判定放宽（例如把 ERROR 当放行、或把"查不到"当 PASS）会让页面显示错误的质量结论（§16.3/§18.3）。</p>
 *
 * <p><b>F-94 读侧归一化（本测试的夹具纪律）</b>：判定依据是<b>规则码</b>（版本化后为
 * {@code QualityRuleCatalog.FrozenRules.find(ruleCode)}），库里的字面 {@code severity} <b>完全不参与</b>判定。
 * 因此本类的夹具<b>必须显式给出与语义相符的规则码</b>：
 * 早期版本让 {@code result(severity, passed)} 一律把 ruleCode 写成 {@code EVENT_ID_UNIQUE}（一个已登记的
 * WARN 码），于是"BLOCKING 未通过 ⇒ FAIL"之类的用例其实一条都没测到 —— 实测表现为 5 个用例集体由 FAIL 变 PASS。
 * 现改为 {@code result(ruleCode, severity, passed)}：{@code ruleCode} 才是判定输入，{@code severity} 只是
 * 被归一化覆盖的字面标签。</p>
 *
 * <p><b>V2.5 §7.3.1 对上述 F-94 口径的收窄（本轮返工）</b>：F-94 时期的读侧归一化会把「未登记规则码」
 * 回退到库中字面 severity，于是未登记码只要回传 {@code WARN} 就被放行。
 * §7.3.1 line 524 明确否定：「未知规则码不可盲信传来的 WARN，应停止发布并报未登记规则」。
 * 本类相应新增 {@code unregisteredCodeWithWarnLiteralStillStopsPublish} 等用例钉住新口径。
 * 归一化本身也被收窄为**明确版本范围内的历史兼容**（{@code QualityRuleCatalog.COMPAT_POLICY_VERSION}）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataQualityGateTest {

    /** 一个已登记的阻断级规则码（金额对账）。 */
    private static final String BLOCKING_CODE = "AMOUNT_RECONCILE";
    /**
     * 一个**条件观察项**规则码（原始事件重复；下游确定性去重）。
     *
     * <p>§7.3.1 line 522：仅在「确定性去重已证 **且** 重复率不超批准阈值」时为观察项 —— 因此
     * {@code passed=1} 才是观察项（不阻断），{@code passed=0} 表示**超阈值**，必须升为阻断。</p>
     */
    private static final String THRESHOLD_OBSERVATION_CODE = "EVENT_ID_UNIQUE";
    /** 一个**固定** WARN 规则码（历史暂存快照存在；阈值串自己写着「观察项」）。 */
    private static final String PLAIN_WARN_CODE = "ADS_STAGING_SNAPSHOT_ISOLATION";
    /** 一个已登记的 INFO 规则码（发布操作审计）。 */
    private static final String INFO_CODE = "PUB_POINTER_SWITCH";
    /** 目录中不存在的规则码（用于验证「未登记 ⇒ 停止发布并报未登记规则」）。 */
    private static final String UNREGISTERED_CODE = "BRAND_NEW_RULE";

    @Mock
    private DataQualityResultMapper qualityMapper;

    @InjectMocks
    private DataQualityGate gate;

    @Test
    @DisplayName("BLOCKING 未通过 → FAIL")
    void blockingFailureFailsTheGate() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", 1),
                result(PLAIN_WARN_CODE, "WARN", 0),
                result(BLOCKING_CODE, "BLOCKING", 0)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);
    }

    @Test
    @DisplayName("ERROR 未通过 → FAIL（D-142 §1：ERROR 失败即阻断，不再只是记录）")
    void errorFailureAlsoFailsTheGate() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", 1),
                result("ADS_STAGING_PRESENT", "ERROR", 0),
                result(THRESHOLD_OBSERVATION_CODE, "WARN", 1)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);
    }

    @Test
    @DisplayName("ERROR 未通过时能报出规则码（发布前门断言用）")
    void errorFailureIsListedAsBlocking() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", 1),
                result("ADS_STAGING_PRESENT", "ERROR", 0)));

        assertThat(gate.blockingFailuresForRun(24L)).containsExactly("ADS_STAGING_PRESENT");
    }

    @Test
    @DisplayName("F-94 反向：库里字面 WARN，但规则码是阻断级 ⇒ 仍 FAIL（字面标签不得覆盖目录）")
    void registeredBlockingCodeOverridesStaleWarnLiteral() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "WARN", 0)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);
        assertThat(gate.blockingFailuresForRun(24L)).containsExactly(BLOCKING_CODE);
    }

    @Test
    @DisplayName("实测 run 24/47 的真实形状：重复率确实超阈值 ⇒ FAIL（F-94「归一化后 PASS」的结论已被实测推翻）")
    void duplicateRateRowsFromRun24ReallyExceedThreshold() {
        // 夹具 = 只读实测的 run 24/47 三行 passed=0（severity 库里都是 ERROR）：
        //   EVENT_ID_UNIQUE        error_rate=0.020408  threshold='<=0.0005' ⇒ 约 40 倍
        //   PUB_DQ_EVENT_ID_UNIQUE error_rate=0.071429  threshold='0.0005'   ⇒ 约 143 倍
        //   ADS_STAGING_SNAPSHOT_ISOLATION error_rate=0.500000 threshold='0 个非本次快照分区（观察项）'
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", 1),
                result(THRESHOLD_OBSERVATION_CODE, "ERROR", 0),
                result("PUB_DQ_EVENT_ID_UNIQUE", "ERROR", 0),
                result(PLAIN_WARN_CODE, "ERROR", 0)));

        // 两条重复率规则必须阻断（§7.3.1 line 522「超过阈值阻断」）；
        // 快照隔离那条按目录是固定观察项，失败也不阻断 —— 三者的区别必须体现出来。
        assertThat(gate.blockingFailuresForRun(24L))
                .containsExactlyInAnyOrder(THRESHOLD_OBSERVATION_CODE, "PUB_DQ_EVENT_ID_UNIQUE");
        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);
        // 定位到 stage/rule，而不是笼统失败（§7.3.1 line 528）
        assertThat(gate.decisionForRun(24L).reason()).contains(THRESHOLD_OBSERVATION_CODE);
    }

    @Test
    @DisplayName("F-94 正向：已登记 WARN 码在**未超阈值**时不被字面 ERROR 带偏（固定观察项同理）")
    void registeredWarnCodeIsJudgedByCatalogNotByLiteral() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", 1),
                // 条件观察项：passed=1 ⇒ 未超阈值 ⇒ 观察项，库里写 ERROR 也不阻断
                result(THRESHOLD_OBSERVATION_CODE, "ERROR", 1),
                // 固定 WARN：失败也不阻断（阈值串自带「观察项」语义）
                result(PLAIN_WARN_CODE, "ERROR", 0)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);
        assertThat(gate.blockingFailuresForRun(24L)).isEmpty();
    }

    @Test
    @DisplayName("只有 WARN/INFO 失败 → PASS，不误报为 FAIL（记录并展示、不阻断）")
    void nonBlockingFailureKeepsPass() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(PLAIN_WARN_CODE, "WARN", 0),
                result(INFO_CODE, "INFO", 0),
                result(BLOCKING_CODE, "BLOCKING", 1)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);
        assertThat(gate.blockingFailuresForRun(24L)).isEmpty();
    }

    @Test
    @DisplayName("全部通过 → PASS")
    void allRulesPassed() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", 1),
                result("ADS_STAGING_PRESENT", "ERROR", 1)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);
    }

    @Test
    @DisplayName("BLOCKING 的 passed 为 NULL（未判定）按未通过处理 → FAIL")
    void nullPassedOnBlockingIsTreatedAsFailed() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", null)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);
    }

    @Test
    @DisplayName("规则码未登记 + severity 未知/为空 ⇒ 停止发布并报未登记规则（§7.3.1 line 524，不静默放过）")
    void unregisteredCodeWithUnknownSeverityStopsPublish() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(UNREGISTERED_CODE, "", 0)));
        DataQualityGate.GateDecision decision = gate.decisionForRun(24L);
        assertThat(decision.status()).isEqualTo(MetricQualityGate.FAIL);
        assertThat(decision.unregisteredRules()).containsExactly(UNREGISTERED_CODE);
        assertThat(decision.stopPublish()).isTrue();
        assertThat(decision.reason()).contains("未登记规则码").contains(UNREGISTERED_CODE);

        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(UNREGISTERED_CODE, null, 0)));
        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.FAIL);
    }

    @Test
    @DisplayName("未登记规则码即使回传 WARN 也必须停止发布并报未登记规则（§7.3.1 line 524 明令「不可盲信传来的 WARN」）")
    void unregisteredCodeWithWarnLiteralStillStopsPublish() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(UNREGISTERED_CODE, "WARN", 0)));

        DataQualityGate.GateDecision decision = gate.decisionForRun(24L);

        // 旧实现（F-94 读侧归一化）在这里回退字面 WARN ⇒ PASS。§7.3.1 line 524 明确否定该行为：
        // 「未知规则码不可盲信传来的 WARN，应停止发布并报未登记规则」。
        assertThat(decision.status()).isEqualTo(MetricQualityGate.FAIL);
        assertThat(decision.unregisteredRules()).containsExactly(UNREGISTERED_CODE);
        assertThat(decision.stopPublish()).isTrue();
        // 失败必须可定位到 rule，而不是笼统的 RUN_JOB_FAILED（§7.3.1 line 528）
        assertThat(decision.allRuleCodes()).contains(UNREGISTERED_CODE);
        assertThat(decision.reason()).contains("未登记规则码");
    }

    @Test
    @DisplayName("未登记规则码也必须进 blockingFailuresForRun（发布前门断言不能漏掉它）")
    void unregisteredCodeIsListedForPublishGate() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", 1),
                result(UNREGISTERED_CODE, "WARN", 0)));

        assertThat(gate.blockingFailuresForRun(24L)).contains(UNREGISTERED_CODE);
    }

    @Test
    @DisplayName("§7.3.1 line 526：三条金额校验是三种独立校验，任一条失败都各自阻断（不因另一条通过而放行）")
    void threeAmountChecksAreIndependentlyBlocking() {
        // ① 付款 vs 订单总额
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result("AMOUNT_RECONCILE", "BLOCKING", 0),
                result("ORDER_ITEM_AMOUNT_FORMULA", "BLOCKING", 1),
                result("DWD_DWS_AMOUNT_RECONCILE", "BLOCKING", 1)));
        assertThat(gate.blockingFailuresForRun(24L)).containsExactly("AMOUNT_RECONCILE");

        // ② 订单项公式（① 与 ③ 都通过，仍必须因②失败而阻断）
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result("AMOUNT_RECONCILE", "BLOCKING", 1),
                result("ORDER_ITEM_AMOUNT_FORMULA", "BLOCKING", 0),
                result("DWD_DWS_AMOUNT_RECONCILE", "BLOCKING", 1)));
        assertThat(gate.blockingFailuresForRun(24L)).containsExactly("ORDER_ITEM_AMOUNT_FORMULA");

        // ③ DWD↔DWS 金额
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result("AMOUNT_RECONCILE", "BLOCKING", 1),
                result("ORDER_ITEM_AMOUNT_FORMULA", "BLOCKING", 1),
                result("DWD_DWS_AMOUNT_RECONCILE", "BLOCKING", 0)));
        assertThat(gate.blockingFailuresForRun(24L)).containsExactly("DWD_DWS_AMOUNT_RECONCILE");

        // 三条都通过 ⇒ 不阻断
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result("AMOUNT_RECONCILE", "BLOCKING", 1),
                result("ORDER_ITEM_AMOUNT_FORMULA", "BLOCKING", 1),
                result("DWD_DWS_AMOUNT_RECONCILE", "BLOCKING", 1)));
        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);
    }

    @Test
    @DisplayName("EVENT_ID_UNIQUE 超阈值判定失败 ⇒ 升为阻断（§7.3.1 line 522：超过阈值阻断，不得直接放行）")
    void eventIdUniqueBeyondThresholdBlocks() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", 1),
                result(THRESHOLD_OBSERVATION_CODE, "ERROR", 0)));

        DataQualityGate.GateDecision decision = gate.decisionForRun(24L);

        assertThat(decision.status()).isEqualTo(MetricQualityGate.FAIL);
        assertThat(decision.blockingRules()).containsExactly(THRESHOLD_OBSERVATION_CODE);
        assertThat(decision.reason()).contains("阈值判定未通过");
    }

    @Test
    @DisplayName("EVENT_ID_UNIQUE 未超阈值（passed=1）⇒ 观察项，不阻断")
    void eventIdUniqueWithinThresholdIsObservation() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(BLOCKING_CODE, "BLOCKING", 1),
                result(THRESHOLD_OBSERVATION_CODE, "WARN", 1)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);
    }

    @Test
    @DisplayName("未登记码的原始 severity 不回填历史结论：原始值仍在实体里可读（§7.3.1 line 524）")
    void originalSeverityStaysReadable() {
        DataQualityResult row = result(UNREGISTERED_CODE, "WARN", 0);
        when(qualityMapper.selectList(any())).thenReturn(List.of(row));

        // 门禁结论按版本化判据（未登记 ⇒ 停止发布），但**原始结果字段不被改写**
        assertThat(gate.decisionForRun(24L).stopPublish()).isTrue();
        assertThat(row.getSeverity()).isEqualTo("WARN");
        // F-88：读侧也不得把生效档位回填到实体上（夹具未设 effectiveSeverity，判定后仍须为 null）
        assertThat(row.getEffectiveSeverity()).isNull();
        assertThat(row.getPassed()).isZero();
    }

    /**
     * F-88（V20）读侧纪律：{@code severity}（声明档位）与 {@code effectiveSeverity}（生效档位）
     * <b>两列都不参与门禁判定</b> —— 结论只由 (冻结规则集, 规则码, passed) 决定。
     *
     * <p>为什么必须钉这一条：本次改动刚把两列填满，很容易被人"顺手"改成读
     * {@code row.getEffectiveSeverity()} 当结论。那会立刻制造第二个口径来源：库里某行的
     * {@code effective_severity} 是**当时**按当时规则集判的，而门禁要回答的是「按本次规则集，
     * 这个 run 的结果算不算阻断」；一旦读库中列，历史行（NULL 或旧档位）与漏填行就会污染结论，
     * 且与 §7.3.1 line 524「不回填历史结论」冲突。本用例用**两列同时说谎**的夹具反证。</p>
     */
    @Test
    @DisplayName("F-88：门禁不看 severity/effectiveSeverity 任何一列 —— 两列同时说谎也改不了结论")
    void gateIgnoresBothSeverityColumns() {
        // (a) 已登记的 BLOCKING 码、passed=0（必须阻断），但两列都写最"无害"的 WARN
        DataQualityResult lie = result(BLOCKING_CODE, "WARN", 0);
        lie.setEffectiveSeverity("WARN");
        when(qualityMapper.selectList(any())).thenReturn(List.of(lie));
        assertThat(gate.statusForRun(24L))
                .as("库里两列都写 WARN 也挡不住 BLOCKING 码判定失败 ⇒ FAIL")
                .isEqualTo(MetricQualityGate.FAIL);
        assertThat(gate.blockingFailuresForRun(24L)).containsExactly(BLOCKING_CODE);

        // (b) 条件观察项未超阈值（passed=1 ⇒ 不阻断），但两列都写最"吓人"的 BLOCKING。
        //     夹具故意"说谎"：作业侧历史上确实会把观察项写成 ERROR（见类注释的实测漂移），
        //     这里把谎说得更极端（写成 BLOCKING）以证明门禁只认 (规则码, passed)。
        DataQualityResult scary = result(THRESHOLD_OBSERVATION_CODE, "BLOCKING", 1);
        scary.setEffectiveSeverity("BLOCKING");
        when(qualityMapper.selectList(any())).thenReturn(List.of(scary));
        assertThat(gate.statusForRun(24L))
                .as("库里两列都写 BLOCKING，但该码是未超阈值的条件观察项 ⇒ 仍 PASS")
                .isEqualTo(MetricQualityGate.PASS);
        assertThat(gate.blockingFailuresForRun(24L)).isEmpty();

        // 版本化引入前的历史行（两列均为 NULL，V20 未回填）同样按规则码判定：BLOCKING 码 passed=0 ⇒ FAIL
        DataQualityResult historical = result(BLOCKING_CODE, null, 0);
        historical.setEffectiveSeverity(null);
        historical.setRuleVersion(null);
        historical.setCompatPolicyVersion(null);
        historical.setRuleFingerprint(null);
        when(qualityMapper.selectList(any())).thenReturn(List.of(historical));
        assertThat(gate.statusForRun(24L))
                .as("历史行无版本信息（两列 NULL）也按规则码判定，不因缺列而放行")
                .isEqualTo(MetricQualityGate.FAIL);
        // 且判定过程不改写实体（读侧不得回填猜测值）
        assertThat(historical.getEffectiveSeverity()).isNull();
        assertThat(historical.getSeverity()).isNull();
    }

    @Test
    @DisplayName("WARN 失败+未判定（passed=null）也不算阻断")
    void warnWithNullPassedDoesNotBlock() {
        when(qualityMapper.selectList(any())).thenReturn(List.of(
                result(PLAIN_WARN_CODE, "WARN", null),
                result(BLOCKING_CODE, "BLOCKING", 1)));

        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.PASS);
    }

    @Test
    @DisplayName("该 run 没有任何质量结果 → UNKNOWN（不臆造为 PASS）")
    void missingResultsAreUnknown() {
        when(qualityMapper.selectList(any())).thenReturn(List.of());
        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.UNKNOWN);

        when(qualityMapper.selectList(any())).thenReturn(null);
        assertThat(gate.statusForRun(24L)).isEqualTo(MetricQualityGate.UNKNOWN);
    }

    @Test
    @DisplayName("没有 pipeline_run_id（快照未关联流水线）→ UNKNOWN，且不查库")
    void nullRunIdIsUnknown() {
        assertThat(gate.statusForRun(null)).isEqualTo(MetricQualityGate.UNKNOWN);
        assertThat(gate.blockingFailuresForRun(null)).isEmpty();
    }

    /**
     * 一条质量结果行夹具。
     *
     * <p>{@code severity} 是**能被归一化覆盖**的字面标签，不参与判定（见类注释的夹具纪律）。
     * F-88/V20 起 {@code effectiveSeverity} 也是结果行自带的档位列，同样**不参与**门禁判定
     * （反证见 {@code gateIgnoresBothSeverityColumns}）；本夹具默认不设它，
     * 由需要区分两列的用例自行设置。</p>
     */
    private static DataQualityResult result(String ruleCode, String severity, Integer passed) {
        DataQualityResult entity = new DataQualityResult();
        entity.setRunId(24L);
        entity.setRuleCode(ruleCode);
        entity.setSeverity(severity);
        entity.setPassed(passed);
        return entity;
    }
}
