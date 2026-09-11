package com.graduation.analytics.ai.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 版本化固定模板（§19.3、§24.7）：六段式叙述，**不依赖任何模型**即可产出完整结论，
 * LLM 只允许在模板结果之上改写措辞（数值一律来自证据包，禁止新增）。
 *
 * <p>段落顺序固定：①发生了什么 ②与上期相比 ③维度贡献 ④数据质量可信度 ⑤核查行动 ⑥限制。
 * 段落增删或语义变化必须同时升 {@link EvidencePackage#TEMPLATE_VERSION}。</p>
 */
public final class EvidenceTemplates {

    private EvidenceTemplates() {
    }

    /** 模板产出的叙述（providerUsed=template 时即最终结果） */
    public record Narrative(String summary, List<Section> sections, List<String> limitations,
                            String providerUsed, String templateVersion) {
        public static final String PROVIDER_TEMPLATE = "template";

        public Narrative {
            sections = sections == null ? List.of() : List.copyOf(sections);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }

        /** 段落标题列表（顺序即契约顺序，供测试与前端导航复用） */
        public List<String> titles() {
            return sections.stream().map(Section::title).toList();
        }

        /** 渲染为纯文本（喂给模型改写、或直接展示） */
        public String toText() {
            StringBuilder sb = new StringBuilder(summary).append('\n');
            for (Section s : sections) {
                sb.append("\n【").append(s.title()).append("】\n");
                s.lines().forEach(line -> sb.append("- ").append(line).append('\n'));
            }
            return sb.toString();
        }
    }

    /** 段落（标题 + 若干事实行；每行自带证据引用） */
    public record Section(String title, List<String> lines) {
        public Section {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public static final String T_WHAT_HAPPENED = "发生了什么";
    public static final String T_VS_PREVIOUS = "与上期相比";
    public static final String T_DIMENSIONS = "维度贡献";
    public static final String T_QUALITY = "数据质量可信度";
    public static final String T_ACTIONS = "核查行动";
    public static final String T_LIMITS = "限制";

    public static Narrative render(EvidencePackage pkg) {
        List<Section> sections = new ArrayList<>();
        sections.add(whatHappened(pkg));
        sections.add(vsPrevious(pkg));
        sections.add(dimensions(pkg));
        sections.add(quality(pkg));
        sections.add(actions(pkg));
        List<String> limitations = limitations(pkg);
        sections.add(new Section(T_LIMITS, limitations));
        return new Narrative(summary(pkg), sections, limitations,
                Narrative.PROVIDER_TEMPLATE, pkg.templateVersion());
    }

    // ── ① 发生了什么 ───────────────────────────────────────────────────────

    private static Section whatHappened(EvidencePackage pkg) {
        List<String> lines = new ArrayList<>();
        String period = periodText(pkg.currentPeriod());
        if (pkg.snapshotId() == null) {
            lines.add("当前没有可用快照（" + String.join("、", pkg.warnings()) + "），本期无事实可陈述");
            return new Section(T_WHAT_HAPPENED, lines);
        }
        lines.add("期间 " + period + "，快照 " + pkg.snapshotId()
                + "，指标口径版本 " + blankToDash(pkg.definitionVersion()));
        for (EvidencePackage.Fact f : pkg.facts()) {
            lines.add(name(f.metricCode(), f.metricName()) + " = " + f.value()
                    + blankToEmpty(f.unit()) + "（期间 " + blankToDash(f.period()) + "）[" + f.evidenceRef() + "]");
        }
        if (pkg.facts().isEmpty()) {
            lines.add("该快照下没有读到指标值");
        }
        return new Section(T_WHAT_HAPPENED, lines);
    }

    // ── ② 与上期相比 ───────────────────────────────────────────────────────

    private static Section vsPrevious(EvidencePackage pkg) {
        List<String> lines = new ArrayList<>();
        if (pkg.comparisonPeriod() == null) {
            lines.add("无可比上期（" + EvidencePackage.WARN_NO_COMPARISON_PERIOD
                    + "）：无法给出环比，不用 0 冒充基线");
            return new Section(T_VS_PREVIOUS, lines);
        }
        lines.add("上期期间 " + periodText(pkg.comparisonPeriod()));
        for (EvidencePackage.Comparison c : pkg.comparisons()) {
            if (c.baseline() == null) {
                lines.add(name(c.metricCode(), c.metricName()) + " 当期 " + c.current()
                        + "，上期缺失，无法比较 [" + c.evidenceRef() + "]");
                continue;
            }
            lines.add(name(c.metricCode(), c.metricName()) + " 当期 " + c.current()
                    + "，上期 " + c.baseline() + "，变化 " + blankToDash(c.delta())
                    + "（" + rateText(c.deltaRate()) + "）[" + c.evidenceRef() + " ← " + c.baselineRef() + "]");
        }
        return new Section(T_VS_PREVIOUS, lines);
    }

    // ── ③ 维度贡献 ─────────────────────────────────────────────────────────

    private static Section dimensions(EvidencePackage pkg) {
        List<String> lines = new ArrayList<>();
        int rows = 0;
        for (Map.Entry<String, List<EvidencePackage.DimensionContribution>> e : pkg.dimensions().entrySet()) {
            List<EvidencePackage.DimensionContribution> list = e.getValue();
            if (list.isEmpty()) {
                lines.add(dimensionLabel(e.getKey()) + "：无来源表/无数据，本期不做该维度归因");
                continue;
            }
            lines.add(dimensionLabel(e.getKey()) + "（度量 " + list.get(0).metricCode() + "）:");
            for (EvidencePackage.DimensionContribution d : list) {
                lines.add("  - " + d.label() + " = " + d.value() + "，占比 " + d.share()
                        + " [" + d.evidenceRef() + "]");
                rows++;
            }
        }
        if (rows == 0) {
            lines.add("本期没有任何维度贡献可陈述（只按指标整体陈述）");
        }
        return new Section(T_DIMENSIONS, lines);
    }

    // ── ④ 数据质量可信度 ───────────────────────────────────────────────────

    private static Section quality(EvidencePackage pkg) {
        List<String> lines = new ArrayList<>();
        EvidencePackage.DataQuality q = pkg.dataQuality();
        if (q == null) {
            lines.add("质量结论不可用");
            return new Section(T_QUALITY, lines);
        }
        lines.add("质量门结论 " + q.gateStatus() + "：" + q.rulePassed() + "/" + q.ruleTotal() + " 条规则通过");
        if (!q.failedRules().isEmpty()) {
            lines.add("未通过规则：" + String.join("、", q.failedRules()));
        }
        q.warnings().forEach(w -> lines.add("质量数据缺口：" + w));
        lines.add(EvidencePackage.DataQuality.GATE_PASS.equals(q.gateStatus())
                ? "结论：可信度可接受，但未通过规则涉及的字段仍需按第 ⑤ 段核查"
                : "结论：可信度不足，本期数值只作参考");
        return new Section(T_QUALITY, lines);
    }

    // ── ⑤ 核查行动 ─────────────────────────────────────────────────────────

    private static Section actions(EvidencePackage pkg) {
        List<String> lines = new ArrayList<>();
        for (EvidencePackage.AnomalyCandidate a : pkg.anomalies()) {
            // 陈述里带「可能相关，不构成因果」的口径限制：核实动作必须让读者看到这句话，
            // 否则 §20.4 的不因果要求只存在于数据里、不存在于页面上。
            lines.add("核查规则 " + a.ruleCode() + "（" + blankToDash(a.severity()) + "）：" + a.statement()
                    + " [观测 " + a.observed() + " / 阈值 " + blankToDash(a.threshold())
                    + " / 偏离 " + blankToDash(a.deviation())
                    + (a.metricCode() == null ? "" : "，指标 " + a.metricCode())
                    + "][" + a.evidenceRef() + "]");
        }
        if (pkg.anomalies().isEmpty()) {
            lines.add("本期没有命中的候选异常规则，不需要额外核查动作");
        }
        lines.add("以上为规则命中的候选线索，相关性不等于因果，需结合业务再判断");
        return new Section(T_ACTIONS, lines);
    }

    // ── ⑥ 限制 ─────────────────────────────────────────────────────────────

    private static List<String> limitations(EvidencePackage pkg) {
        List<String> lines = new ArrayList<>();
        lines.add("本结论只覆盖快照 " + blankToDash(pkg.snapshotId()) + " 已发布的数据，不含实时增量");
        lines.add("候选异常来自固定阈值规则，不是模型预测，也不构成因果结论");
        lines.add("口径版本 " + blankToDash(pkg.definitionVersion())
                + "；口径变更后同指标的历史值不可直接比较");
        if (pkg.warnings().contains(EvidencePackage.WARN_UNKNOWN_DIMENSION_TABLE)) {
            lines.add("分类/地区维度在本期没有 Hive 来源 ADS，无法给出该维度贡献");
        }
        if (pkg.warnings().contains(EvidencePackage.WARN_NO_COMPARISON_PERIOD)) {
            lines.add("上一期没有可用快照，因此没有环比数据");
        }
        for (String w : pkg.warnings()) {
            lines.add("数据缺口：" + w);
        }
        return lines;
    }

    // ── 摘要 ───────────────────────────────────────────────────────────────

    private static String summary(EvidencePackage pkg) {
        if (pkg.snapshotId() == null) {
            return "当前没有可用快照，无法给出经营结论（" + String.join("、", pkg.warnings()) + "）";
        }
        String head = "快照 " + pkg.snapshotId() + "（" + periodText(pkg.currentPeriod()) + "）共 "
                + pkg.facts().size() + " 个指标，质量门 " + (pkg.dataQuality() == null
                ? EvidencePackage.DataQuality.GATE_UNKNOWN : pkg.dataQuality().gateStatus());
        String tail = pkg.anomalies().isEmpty()
                ? "，未命中候选异常规则。"
                : "，命中 " + pkg.anomalies().size() + " 条候选异常（详见核查行动）。";
        return head + tail;
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    private static String periodText(EvidencePackage.Period p) {
        if (p == null) {
            return "-";
        }
        return p.from() == null ? blankToDash(p.to())
                : p.from().equals(p.to()) ? p.from() : p.from() + "~" + p.to();
    }

    private static String dimensionLabel(String key) {
        return switch (key == null ? "" : key) {
            case "product" -> "商品维度";
            case "category" -> "分类维度";
            case "region" -> "地区维度";
            case "channel" -> "渠道维度";
            default -> key;
        };
    }

    private static String name(String code, String metricName) {
        return metricName == null || metricName.isBlank() ? code : metricName + "(" + code + ")";
    }

    private static String rateText(String deltaRate) {
        return deltaRate == null ? "无可比基数" : "环比 " + deltaRate;
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }
}
