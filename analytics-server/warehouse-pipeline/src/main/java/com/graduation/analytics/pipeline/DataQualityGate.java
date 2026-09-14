package com.graduation.analytics.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.metric.MetricQualityGate;
import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.QualityRuleDefinition;
import com.graduation.analytics.metric.RuleSeverity;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * R7-4：质量门结论的唯一实现（analytics_meta.data_quality_result → PASS/FAIL/UNKNOWN）。
 *
 * <p>为什么由本模块实现：{@code data_quality_result} 的 mapper/实体在 warehouse-pipeline（§17.2 表所有权），
 * 分析服务只通过 platform-common 的 {@link MetricQualityGate} 接口取结论，不自己查这张表 ——
 * 避免出现第二个「质量结论所有者」。</p>
 *
 * <p>判定口径（D-142 §1，指导书 §7.3 原文不变）：<b>{@code BLOCKING} 与 {@code ERROR} 未通过都算阻断</b>
 * （{@code passed != 1}，含 NULL=未判定）；{@code WARN}/{@code INFO} 未通过只记录、不阻断。</p>
 *
 * <p><b>版本化判定（V2.5 §7.3.1 line 520，替代原「全局 ruleCode 覆盖」）</b>：严重度取自
 * {@link QualityRuleCatalog.FrozenRules}（一次 run 冻结的规则版本与指纹），<b>不</b>拿
 * {@code data_quality_result.severity} 的字面值当结论。原因：该列存的是 Spark 作业回传的
 * 原始标签（本轮次禁改作业侧），与平台口径存在**已实测的漂移** —— 例如
 * {@code EVENT_ID_UNIQUE} / {@code PUB_DQ_EVENT_ID_UNIQUE} / {@code ADS_STAGING_SNAPSHOT_ISOLATION}
 * 在库里是 {@code ERROR}（run 24 与 run 47 各 3 行），而目录口径是观察项。</p>
 *
 * <p><b>F-88/V20 之后：结果行自带的 {@code effectiveSeverity} 也不参与判定</b>（反证见
 * {@code DataQualityGateTest#gateIgnoresBothSeverityColumns}）。两列含义不同：
 * 结果行的 {@code effective_severity} 是**写入当时**按当时冻结集判出的档位（用于事后复算「当时怎么判的」），
 * 而本类要回答的是「按**本次**规则集，这个 run 的结果算不算阻断」。若读库中列，会立刻出现两套口径，
 * 且版本化引入前的历史行（该列为 NULL）与漏填行都会污染结论。因此判定输入仍然只有
 * (冻结规则集, ruleCode, passed) 三元组。</p>
 *
 * <p><b>未登记规则码 ⇒ FAIL</b>（不再静默放行）：§7.3.1 line 524「未知规则码不可盲信传来的 WARN，
 * 应停止发布并报未登记规则」。因此本类把「未登记码」与「阻断级未通过」分开统计，
 * 由 {@link #decisionForRun(Long)} 同时给出结论、未登记码列表与可定位的规则码。</p>
 *
 * <p><b>已知缺口（显式声明，不掩盖）</b>：写侧已按 F-88 把「该 run 用的规则版本与规则集指纹」落进
 * {@code data_quality_result}（{@code rule_version/effective_severity/compat_policy_version/rule_fingerprint}，V20），
 * 但本类仍以**调用方传入的冻结集**为准来判定，不按结果行上的 {@code rule_fingerprint} 选版本 ——
 * 「读侧按行内指纹重算历史 run 的当时结论」属 F-93，**不在 F-88 范围内**。
 * 当前冻结集取目录的默认版本集，因此同一规则码跨版本的历史差异仍未被读侧冻结。</p>
 *
 * <p>run 号为空或该 run 没有任何质量结果 → UNKNOWN（取不到就是取不到，不冒充 PASS）。</p>
 */
@Component
@RequiredArgsConstructor
public class DataQualityGate implements MetricQualityGate {

    private final DataQualityResultMapper qualityMapper;

    @Override
    public String statusForRun(Long pipelineRunId) {
        return decisionForRun(pipelineRunId).status();
    }

    /**
     * 质量门判定明细（结论 + 未登记码 + 阻断规则码 + 冻结指纹 + 中文解释）。
     *
     * <p>为什么返回明细而不只返回 PASS/FAIL：§7.3.1 line 528 要求
     * 「失败必须可定位到 stage/rule，不只显示 {@code RUN_JOB_FAILED}」。
     * 只返回一个字符串无法满足该要求，故把规则码与未登记码一并返回。</p>
     *
     * @param pipelineRunId 流水线 run 号
     * @return 判定明细；run 号为空或无结果时为 {@link MetricQualityGate#UNKNOWN}
     */
    public GateDecision decisionForRun(Long pipelineRunId) {
        return decisionForRun(pipelineRunId, QualityRuleCatalog.DEFAULT.freeze(null));
    }

    /**
     * 按**指定冻结规则集**判定（供写侧传入该 run 冻结的版本集；见类注释的已知缺口）。
     *
     * @param pipelineRunId run 号
     * @param rules         冻结规则集
     * @return 判定明细
     */
    public GateDecision decisionForRun(Long pipelineRunId, QualityRuleCatalog.FrozenRules rules) {
        List<DataQualityResult> results = resultsOf(pipelineRunId);
        if (results == null || results.isEmpty()) {
            return new GateDecision(UNKNOWN, List.of(), List.of(), rules == null ? "" : rules.fingerprint(),
                    "该 run 没有质量结果（或 run 号为空）⇒ 取不到结论，不冒充 PASS");
        }
        List<String> blocking = new ArrayList<>();
        List<String> unregistered = new ArrayList<>();
        List<String> explanations = new ArrayList<>();
        for (DataQualityResult r : results) {
            RuleSeverity.RuleVerdict verdict = RuleSeverity.resolve(rules, r.getRuleCode(), r.getPassed());
            if (!verdict.registered()) {
                String raw = r.getRuleCode() == null ? "" : r.getRuleCode();
                if (!unregistered.contains(raw)) {
                    unregistered.add(raw);
                }
                // 未登记码同样必须进 blockingRules：它使 stopPublish()=true，
                // 若只记在 unregisteredRules 里，发布前门断言读 blockingFailuresForRun 时会漏掉它，
                // 于是「报未登记规则」与「真的停止发布」脱节（§7.3.1 line 524 要求两者同时成立）。
                if (!blocking.contains(raw)) {
                    blocking.add(raw);
                }
                continue;
            }
            if (verdict.blocks() && !blocking.contains(verdict.ruleCode())) {
                blocking.add(verdict.ruleCode());
            }
            // 兼容解释对**每条**规则都留痕（不只未阻断的）：§7.3.1 line 524 要求
            // 「接口同时展示兼容解释」，超阈值被升为阻断的那条更需要说明为什么升。
            String note = verdict.ruleCode() + "(" + verdict.declaredSeverity() + "→"
                    + verdict.effectiveSeverity() + "): " + verdict.explanation();
            if (!explanations.contains(note)) {
                explanations.add(note);
            }
        }
        String status;
        String reason;
        if (!unregistered.isEmpty()) {
            // §7.3.1 line 524：停止发布并报未登记规则（而不是当成通过）
            status = FAIL;
            reason = "存在未登记规则码，停止发布：" + unregistered;
        } else if (!blocking.isEmpty()) {
            status = FAIL;
            reason = "阻断级规则未通过：" + blocking;
        } else {
            status = PASS;
            reason = "无阻断级未通过项；规则集指纹=" + (rules == null ? "" : rules.fingerprint());
        }
        return new GateDecision(status, blocking, unregistered, rules == null ? "" : rules.fingerprint(),
                reason + (explanations.isEmpty() ? "" : "；兼容解释：" + explanations));
    }

    /**
     * 阻断级规则未通过的规则码（发布前门断言用，见
     * {@code PipelineService} 的 PUBLISH_METRIC 前置检查）。
     *
     * <p>判定走 {@link RuleSeverity#resolve}（版本化，先按规则码定版本），
     * 因此库里 {@code severity} 的字面漂移不会改变本方法的结论。</p>
     *
     * @param pipelineRunId 流水线 run 号；为空时返回空列表（无 run 无从判定，由调用方决定语义）
     * @return 未通过的阻断级规则码列表（可能为空）
     */
    public List<String> blockingFailuresForRun(Long pipelineRunId) {
        return decisionForRun(pipelineRunId).blockingRules();
    }

    /**
     * 按**指定冻结规则集**取阻断规则码（发布前门专用，§7.3.1 line 520）。
     *
     * <p>必须与 {@link #decisionForRun(Long, QualityRuleCatalog.FrozenRules)} 用同一份 rules，
     * 否则「判定」与「发布前门断言」会分叉成两套口径（R01 实测的缺口形态）。</p>
     *
     * @param pipelineRunId run 号
     * @param rules         该 run 冻结的规则集
     * @return 未通过的阻断级规则码列表（含未登记码；可能为空）
     */
    public List<String> blockingFailuresForRun(Long pipelineRunId, QualityRuleCatalog.FrozenRules rules) {
        return decisionForRun(pipelineRunId, rules).blockingRules();
    }

    /** 查该 run 的质量结果；run 号为空直接返回空列表（不查库）。 */
    private List<DataQualityResult> resultsOf(Long pipelineRunId) {
        if (pipelineRunId == null) {
            return List.of();
        }
        return qualityMapper.selectList(
                new LambdaQueryWrapper<DataQualityResult>().eq(DataQualityResult::getRunId, pipelineRunId));
    }

    /**
     * 质量门判定明细。
     *
     * @param status           {@link MetricQualityGate#PASS}/{@link MetricQualityGate#FAIL}/{@link MetricQualityGate#UNKNOWN}
     * @param blockingRules    阻断级且未通过的规则码（可定位到 rule）
     * @param unregisteredRules 未登记规则码（须先登记规则版本，不是改数据）
     * @param rulesFingerprint 本次判定所用冻结规则集的指纹
     * @param reason           中文解释（含兼容解释，§7.3.1 line 524）
     */
    public record GateDecision(
            String status,
            List<String> blockingRules,
            List<String> unregisteredRules,
            String rulesFingerprint,
            String reason) {

        public GateDecision {
            blockingRules = List.copyOf(blockingRules == null ? List.of() : blockingRules);
            unregisteredRules = List.copyOf(unregisteredRules == null ? List.of() : unregisteredRules);
        }

        /** 该结论是否要求**停止发布**（含未登记规则）。 */
        public boolean stopPublish() {
            return FAIL.equals(status);
        }

        /** 涉及的全部规则码（阻断 + 未登记），供日志/证据按 rule 定位。 */
        public List<String> allRuleCodes() {
            List<String> all = new ArrayList<>(blockingRules);
            for (String u : unregisteredRules) {
                if (!all.contains(u)) {
                    all.add(u);
                }
            }
            return List.copyOf(all);
        }
    }

    /**
     * 未登记规则码的发布中止异常（§7.3.1 line 524）。
     *
     * <p>与 {@link RuleSeverity.UnknownRuleException} 同源：写侧/发布路径检测到未登记码时抛本异常，
     * 使失败可定位到 **rule**，而不是笼统的 {@code RUN_JOB_FAILED}（§7.3.1 line 528）。</p>
     */
    public static class UnregisteredRuleException extends RuleSeverity.UnknownRuleException {
        private static final long serialVersionUID = 1L;

        public UnregisteredRuleException(List<String> ruleCodes) {
            super(ruleCodes);
        }
    }

    /** 该结果是否来自「阈值判定未通过」的条件观察项（用于展示层区分）。 */
    static boolean isConditionalObservation(QualityRuleCatalog.FrozenRules rules, DataQualityResult r) {
        if (rules == null || r == null) {
            return false;
        }
        return rules.find(r.getRuleCode())
                .map(d -> d.severityMode() == QualityRuleDefinition.SeverityMode.THRESHOLD_OBSERVATION
                        && Objects.equals(r.getPassed(), 1))
                .orElse(false);
    }
}
