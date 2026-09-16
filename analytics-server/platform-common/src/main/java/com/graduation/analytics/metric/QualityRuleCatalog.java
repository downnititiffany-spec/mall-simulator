package com.graduation.analytics.metric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 质量规则目录 —— {@code quality_rule_definition} 的**版本化**单一所有者（V2.5 指导书 §7.3.1 line 520）。
 *
 * <p><b>本类取代「全局 ruleCode 覆盖」</b>：§7.3.1 line 520 原文指出
 * 「当前 {@code RuleSeverity} 的全局 {@code ruleCode} 覆盖不是最终完成形态」。
 * 因此判定不再问「这个码全局是什么严重度」，而是问
 * 「<b>本次 run 冻结的规则版本集</b>里，这个码的这一版是什么严重度」。</p>
 *
 * <p><b>与物理表的关系（重要，别误读）</b>：本类当前是
 * {@code quality_rule_definition} 的**代码内实现**（classpath 目录），不是数据库读。
 * 原因是 DDL 迁移号由总控独占分配，本泳道**不得新建迁移文件、不得执行 DDL**。
 * 契约（{@link QualityRuleDefinition} 的字段、唯一键、指纹算法）已按 §7.3.1 line 520 固定，
 * 将来把 {@link #definitions()} 换成 mapper 查询即可，**调用方无需改动**。
 * DDL 草案见 {@code docs/acceptance/f88-dq-severity-20260912/raw/}。</p>
 *
 * <p><b>执行顺序</b>（§7.3.1 line 522）：选择作用域/版本 → 计算指标与阈值判定 →
 * 决定严重度 → 汇总门禁。{@link #freeze(String)} 承担第 1 步；
 * 第 2/3 步由 {@link RuleSeverity#resolve} 承担（它同时看阈值判定结果 {@code passed}）；
 * 第 4 步由 {@code DataQualityGate} 汇总。</p>
 */
public final class QualityRuleCatalog {

    /** 目录自身的契约版本：字段集/指纹算法/生效语义变化时递增（与规则版本无关）。 */
    public static final String CATALOG_VERSION = "qrc-1";

    /**
     * 历史兼容策略版本（§7.3.1 line 524 要求随结果记录）。
     *
     * <p>取值依据见 {@code raw/q01-version-scope.md}。要点：兼容只对
     * <b>明确版本范围内的历史批次</b>生效，且必须显式登记；不在批准范围内的
     * **不得自动降级**。</p>
     */
    public static final String COMPAT_POLICY_VERSION = "compat-v1";

    /** 阶段常量（写进 {@code stage} 列，便于按阶段定位失败）。 */
    public static final String STAGE_LANDING = "LANDING";
    public static final String STAGE_DWD = "DWD";
    public static final String STAGE_DWS = "DWS";
    public static final String STAGE_ADS = "ADS";
    public static final String STAGE_PUBLISH = "PUBLISH";
    public static final String STAGE_METRIC_PUBLISH = "METRIC_PUBLISH";

    /**
     * 规则码常量 —— §7.3.1 line 526 的**三条金额校验必须各自独立**，故各自一个规则码。
     *
     * <p>原文：「付款 vs 订单总额、订单项公式、DWD↔DWS 金额是三种独立校验，
     * <b>不能用一个 {@code AMOUNT_RECONCILE} 测试替代全部</b>」。因此三个码不可合并、
     * 不可互相替代，也不能共用一个测试用例（任一条漏测都会让另两条的绿灯掩盖它）。</p>
     */
    /** ① 付款 vs 订单总额（Landing：支付事件 amount 对比订单应付总额） */
    public static final String RULE_AMOUNT_PAID_VS_ORDER = "AMOUNT_RECONCILE";
    /** ② 订单项公式（DWD：sum(订单项金额) 对比订单总额） */
    public static final String RULE_ORDER_ITEM_FORMULA = "ORDER_ITEM_AMOUNT_FORMULA";
    /** ③ DWD↔DWS 金额（DWS：按明细重算的金额对比层间聚合结果） */
    public static final String RULE_DWD_DWS_AMOUNT = "DWD_DWS_AMOUNT_RECONCILE";

    /** 其余 Landing 层规则码。 */
    public static final String RULE_REQUIRED_FIELD = "REQUIRED_FIELD_NULL_RATE";
    public static final String RULE_ENUM_WHITELIST = "ENUM_WHITELIST";
    public static final String RULE_EVENT_ID_UNIQUE = "EVENT_ID_UNIQUE";
    /** 发布层对同一重复率的口径复用码（Spark pub 侧产出，与 {@link #RULE_EVENT_ID_UNIQUE} 同阈值同语义）。 */
    public static final String RULE_PUB_DQ_EVENT_ID_UNIQUE = "PUB_DQ_EVENT_ID_UNIQUE";

    /** ADS 暂存层规则码。 */
    public static final String RULE_ADS_STAGING_PRESENT = "ADS_STAGING_PRESENT";
    public static final String RULE_ADS_STAGING_SNAPSHOT_ISOLATION = "ADS_STAGING_SNAPSHOT_ISOLATION";
    public static final String RULE_ADS_STAGING_KEY_NOT_NULL = "ADS_STAGING_KEY_NOT_NULL";
    public static final String RULE_PUB_DQ_BLOCKING_RULES = "PUB_DQ_BLOCKING_RULES";
    public static final String RULE_ADS_DWS_FUNNEL_RECONCILE = "ADS_DWS_FUNNEL_RECONCILE";

    /** 发布层（Spark pub / mxp）规则码。 */
    public static final String RULE_PUB_STAGING_READY = "PUB_STAGING_READY";
    public static final String RULE_PUB_FORMAL_PARTITION_MATCH = "PUB_FORMAL_PARTITION_MATCH";
    public static final String RULE_PUB_POINTER_SWITCH = "PUB_POINTER_SWITCH";
    public static final String RULE_PUB_STAGING_PRUNE = "PUB_STAGING_PRUNE";
    public static final String RULE_MXP_SNAPSHOT_PINNED = "MXP_SNAPSHOT_PINNED";
    public static final String RULE_MXP_EXPORT_ROWS = "MXP_EXPORT_ROWS";
    public static final String RULE_MXP_EXPORT_COMPLETE = "MXP_EXPORT_COMPLETE";

    /** 指标库发布对账规则码（{@code MetricPublishValidator}，只进发布报告证据，不落 data_quality_result）。 */
    public static final String RULE_MP_MANIFEST_TABLES = "MP_MANIFEST_TABLES";
    public static final String RULE_MP_MANIFEST_SNAPSHOT = "MP_MANIFEST_SNAPSHOT";
    public static final String RULE_MP_HIVE_PATH_PINNED = "MP_HIVE_PATH_PINNED";
    public static final String RULE_MP_EXPORT_FILES = "MP_EXPORT_FILES";
    public static final String RULE_MP_EXPORT_CHECKSUM = "MP_EXPORT_CHECKSUM";
    public static final String RULE_MP_ADS_ROWS_MATCH = "MP_ADS_ROWS_MATCH";
    public static final String RULE_MP_REQUIRED_TABLES_NONEMPTY = "MP_REQUIRED_TABLES_NONEMPTY";
    public static final String RULE_MP_ROW_SHAPE_CONSISTENT = "MP_ROW_SHAPE_CONSISTENT";
    public static final String RULE_MP_OVERVIEW_CORE_NOT_NULL = "MP_OVERVIEW_CORE_NOT_NULL";
    public static final String RULE_MP_METRIC_DICT_VERSION = "MP_METRIC_DICT_VERSION";
    public static final String RULE_MP_VALUE_MATCH_ADS = "MP_VALUE_MATCH_ADS";
    public static final String RULE_MP_METRIC_VALUE_COUNT = "MP_METRIC_VALUE_COUNT";
    public static final String RULE_MP_ACTIVE_SNAPSHOT = "MP_ACTIVE_SNAPSHOT";
    public static final String RULE_MP_ADS_ROWS_DB_MATCH = "MP_ADS_ROWS_DB_MATCH";
    public static final String RULE_MP_METRIC_VALUE_DB_MATCH = "MP_METRIC_VALUE_DB_MATCH";
    public static final String RULE_MP_ADS_WRITE_MATCH = "MP_ADS_WRITE_MATCH";
    public static final String RULE_MP_OLD_ACTIVE_ARCHIVED = "MP_OLD_ACTIVE_ARCHIVED";

    /**
     * Landing 层规则定义（version 均为 1）。
     *
     * <p>阈值出处：设计文稿 §5.4.2「默认阈值」。其中 {@code EVENT_ID_UNIQUE} 的 {@code 0.0005}
     * 由 §7.3.1 line 522 明确「历史阈值未经新裁决不得修改」，此处只是把它搬进契约。</p>
     */
    private static final List<QualityRuleDefinition> DEFINITIONS = List.of(
            new QualityRuleDefinition(RULE_AMOUNT_PAID_VS_ORDER, 1, QualityRuleDefinition.SCOPE_ALL, STAGE_LANDING,
                    RuleSeverity.BLOCKING, QualityRuleDefinition.SeverityMode.FIXED, "{\"maxAbsDiff\":0.01}",
                    true, null, null,
                    "金额对账①「付款 vs 订单总额」不一致 ⇒ 必须阻断；§7.3.1 line 522 明确金额对账失败不得被降 WARN；"
                            + "§7.3.1 line 526 要求它与「订单项公式」「DWD↔DWS 金额」是**三种独立校验**，不可互相替代"),
            new QualityRuleDefinition(RULE_ORDER_ITEM_FORMULA, 1, QualityRuleDefinition.SCOPE_ALL, STAGE_DWD,
                    RuleSeverity.BLOCKING, QualityRuleDefinition.SeverityMode.FIXED, "{\"maxAbsDiff\":0.01}",
                    true, null, null,
                    "金额对账②「订单项公式」（sum(订单项金额) vs 订单总额）不一致 ⇒ 必须阻断；"
                            + "§7.3.1 line 526 把本条列为与①③并列的独立校验"),
            new QualityRuleDefinition(RULE_DWD_DWS_AMOUNT, 1, QualityRuleDefinition.SCOPE_ALL, STAGE_DWS,
                    RuleSeverity.BLOCKING, QualityRuleDefinition.SeverityMode.FIXED, "{\"maxAbsDiff\":0.01}",
                    true, null, null,
                    "金额对账③「DWD↔DWS 金额」（按明细重算 vs 层间聚合）不一致 ⇒ 必须阻断；"
                            + "§7.3.1 line 526 把本条列为与①②并列的独立校验"),
            new QualityRuleDefinition(RULE_REQUIRED_FIELD, 1, QualityRuleDefinition.SCOPE_ALL, STAGE_LANDING,
                    RuleSeverity.BLOCKING, QualityRuleDefinition.SeverityMode.FIXED, "{\"nullRateMax\":0.001}",
                    true, null, null,
                    "必需字段缺失；下游 DwdSql.behaviorClean 静默丢弃该行 ⇒ 必须阻断；§7.3.1 line 522 禁止降 WARN"),
            new QualityRuleDefinition(RULE_ENUM_WHITELIST, 1, QualityRuleDefinition.SCOPE_ALL, STAGE_LANDING,
                    RuleSeverity.BLOCKING, QualityRuleDefinition.SeverityMode.FIXED, "{\"illegalRatio\":0}",
                    true, null, null,
                    "非法枚举；下游静默丢弃该行（设计文稿 §5.4.2 比例为 0）⇒ 必须阻断；§7.3.1 line 522 禁止降 WARN"),
            new QualityRuleDefinition(RULE_EVENT_ID_UNIQUE, 1, QualityRuleDefinition.SCOPE_ALL, STAGE_LANDING,
                    RuleSeverity.WARN, QualityRuleDefinition.SeverityMode.THRESHOLD_OBSERVATION,
                    "{\"dupRateMax\":0.0005,\"dedupDeterministic\":true}", true, null, null,
                    "原始事件重复：确定性去重已证（DwdSql.behaviorClean 的 ROW_NUMBER PARTITION BY event_id；"
                            + "TradeDwdJob 的 dropDuplicates）且重复率 <= 0.0005 时为观察项；"
                            + "超阈值即阻断（§7.3.1 line 522）；阈值未经新裁决不得修改"),

            // ── 以下为「既有规则码的版本化登记」（version 1）─────────────────────────────
            // 为什么必须登记：§7.3.1 line 524 要求「未知规则码…应停止发布并报未登记规则」。
            // 本目录若不登记这些码，读侧会把**历史 run 的既有结果**全判为「未登记规则」而停止发布 ——
            // 等于用新契约追溯改写了历史结论，与 line 524「不回填历史结论」直接冲突。
            // 因此这里把现行全部规则码登记为 version 1，severity 取**当前代码目录的既有档位**
            // （与 RuleSeverity.of 逐条一致），本版本**不改变任何既有码的档位**；
            // 档位真要变，必须发新 version 并显式裁决，不能借登记之名偷偷改。
            new QualityRuleDefinition(RULE_PUB_DQ_EVENT_ID_UNIQUE, 1, QualityRuleDefinition.SCOPE_ALL, STAGE_PUBLISH,
                    RuleSeverity.WARN, QualityRuleDefinition.SeverityMode.THRESHOLD_OBSERVATION,
                    "{\"dupRateMax\":0.0005,\"dedupDeterministic\":true}", true, null, null,
                    "发布层复用的重复率口径，与 EVENT_ID_UNIQUE 同阈值同语义（观察项，超阈值阻断）"),

            fixed(RULE_ADS_STAGING_PRESENT, STAGE_ADS, RuleSeverity.BLOCKING, "8 张暂存表本次快照分区必须存在且非空"),
            fixed(RULE_ADS_STAGING_SNAPSHOT_ISOLATION, STAGE_ADS, RuleSeverity.WARN,
                    "历史暂存快照存在本身不是错误（陈旧分区不污染正式分区，且清理在 pub 之后，"
                            + "设阻断会造成发布死锁）；「混入其他快照」由 MXP_SNAPSHOT_PINNED(BLOCKING) 承担"),
            fixed(RULE_ADS_STAGING_KEY_NOT_NULL, STAGE_ADS, RuleSeverity.BLOCKING, "ADS 关键列逐表真实 COUNT，阈值 0"),
            fixed(RULE_PUB_DQ_BLOCKING_RULES, STAGE_ADS, RuleSeverity.BLOCKING,
                    "暂存宽表内 3 条阻断规则必须全 passed=1，与 Java 侧 corePassed 同口径"),
            fixed(RULE_ADS_DWS_FUNNEL_RECONCILE, STAGE_ADS, RuleSeverity.BLOCKING,
                    "ADS 漏斗 stage 汇总 = DWS 漏斗对应列，不一致即口径破坏"),

            fixed(RULE_PUB_STAGING_READY, STAGE_PUBLISH, RuleSeverity.BLOCKING, "暂存分区未就绪不得切换正式分区"),
            fixed(RULE_PUB_FORMAL_PARTITION_MATCH, STAGE_PUBLISH, RuleSeverity.BLOCKING,
                    "正式分区行数必须等于暂存分区行数"),
            fixed(RULE_PUB_POINTER_SWITCH, STAGE_PUBLISH, RuleSeverity.INFO, "发布操作审计（切换表数）"),
            fixed(RULE_PUB_STAGING_PRUNE, STAGE_PUBLISH, RuleSeverity.INFO, "发布操作审计（清理计数）"),
            fixed(RULE_MXP_SNAPSHOT_PINNED, STAGE_PUBLISH, RuleSeverity.BLOCKING,
                    "正式分区 Location 必须指向本次 snapshot_id（D-142 §1 的真阻断点）"),
            fixed(RULE_MXP_EXPORT_ROWS, STAGE_PUBLISH, RuleSeverity.BLOCKING, "导出文件行数 = Hive 分区行数"),
            fixed(RULE_MXP_EXPORT_COMPLETE, STAGE_PUBLISH, RuleSeverity.BLOCKING, "8 张表导出完成且行数一致"),

            fixed(RULE_MP_MANIFEST_TABLES, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "清单表数对账"),
            fixed(RULE_MP_MANIFEST_SNAPSHOT, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "清单快照一致性"),
            fixed(RULE_MP_HIVE_PATH_PINNED, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "Hive 路径必须钉住本次快照"),
            fixed(RULE_MP_EXPORT_FILES, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "导出文件齐备"),
            fixed(RULE_MP_EXPORT_CHECKSUM, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING,
                    "导出制品内容摘要 = 清单 checksum（逐表重算，防行数相同但内容被截断/错位搬运）"),
            fixed(RULE_MP_ADS_ROWS_MATCH, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "清单行数 = 文件行数"),
            fixed(RULE_MP_REQUIRED_TABLES_NONEMPTY, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING,
                    "必须有数据的表不得为空"),
            fixed(RULE_MP_ROW_SHAPE_CONSISTENT, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "行形状一致"),
            fixed(RULE_MP_OVERVIEW_CORE_NOT_NULL, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "总览核心列非空"),
            fixed(RULE_MP_METRIC_DICT_VERSION, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "指标字典版本一致"),
            fixed(RULE_MP_VALUE_MATCH_ADS, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "指标值 = ADS 值"),
            fixed(RULE_MP_METRIC_VALUE_COUNT, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "指标值条数下限"),
            fixed(RULE_MP_ACTIVE_SNAPSHOT, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "ACTIVE 快照指针唯一"),
            fixed(RULE_MP_ADS_ROWS_DB_MATCH, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING, "库内行数 = 清单行数"),
            fixed(RULE_MP_METRIC_VALUE_DB_MATCH, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING,
                    "库内指标值 = 发布值"),
            fixed(RULE_MP_ADS_WRITE_MATCH, STAGE_METRIC_PUBLISH, RuleSeverity.BLOCKING,
                    "清单对账规则码；与错误串 MP_ADS_WRITE_FAILED（写 ADS 宽表失败）语义不同，勿合并"),
            fixed(RULE_MP_OLD_ACTIVE_ARCHIVED, STAGE_METRIC_PUBLISH, RuleSeverity.INFO,
                    "上一个 ACTIVE 是否已归档；实现显式声明为 INFO，不是阻断项"));

    /** 固定严重度定义的简写构造（登记既有码用；阈值留空表示该码不按阈值定档）。 */
    private static QualityRuleDefinition fixed(String ruleCode, String stage, String severity, String rationale) {
        return new QualityRuleDefinition(ruleCode, 1, QualityRuleDefinition.SCOPE_ALL, stage,
                severity, QualityRuleDefinition.SeverityMode.FIXED, null, true, null, null, rationale);
    }

    /** 冻结的规则集：一次 run 用哪一套口径，由它回答。 */
    private final FrozenRules frozen;

    private QualityRuleCatalog(FrozenRules frozen) {
        this.frozen = frozen;
    }

    /**
     * 按 source 作用域冻结规则集（§7.3.1 line 522 第 1 步）。
     *
     * @param sourceId 源标识；{@code null} 时只取全源（{@code *}）定义
     * @return 冻结集（含逐条定义、整体指纹、目录版本、兼容策略版本）
     */
    public FrozenRules freeze(String sourceId) {
        List<QualityRuleDefinition> selected = new ArrayList<>();
        for (QualityRuleDefinition d : frozen.definitions()) {
            if (d.appliesToSource(sourceId)) {
                selected.add(d);
            }
        }
        return FrozenRules.of(selected);
    }

    /** 全部定义（未按作用域过滤），按 {@code (source_scope, rule_code, version)} 排序，保证指纹稳定。 */
    public List<QualityRuleDefinition> definitions() {
        return frozen.definitions();
    }

    /** 规则集整体指纹。 */
    public String fingerprint() {
        return frozen.fingerprint();
    }

    /** 目录版本。 */
    public String catalogVersion() {
        return frozen.catalogVersion();
    }

    /**
     * 取该规则码在当前冻结集里的**生效版本**（启用、版本号最大者；同码多作用域时优先精确作用域）。
     *
     * @param ruleCode 规则码（大小写不敏感）
     * @param sourceId 源标识（可为 {@code null}）
     * @return 命中的定义；未登记返回 {@link Optional#empty()}
     */
    public Optional<QualityRuleDefinition> find(String ruleCode, String sourceId) {
        String code = QualityRuleDefinition.normalize(ruleCode);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        QualityRuleDefinition best = null;
        for (QualityRuleDefinition d : frozen.definitions()) {
            if (!d.enabled() || !d.ruleCode().equals(code) || !d.appliesToSource(sourceId)) {
                continue;
            }
            if (best == null
                    || d.version() > best.version()
                    // 同版本时精确作用域优先于全源
                    || (d.version() == best.version()
                        && !QualityRuleDefinition.SCOPE_ALL.equals(d.sourceScope()))) {
                best = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 规则码是否已在目录中登记（任一启用版本）。仅用于展示/兼容解释，**不得**作为放行依据。 */
    public boolean registered(String ruleCode) {
        String code = QualityRuleDefinition.normalize(ruleCode);
        return frozen.definitions().stream()
                .anyMatch(d -> d.enabled() && d.ruleCode().equals(code));
    }

    @Override
    public String toString() {
        return "QualityRuleCatalog[" + frozen.catalogVersion() + ", defs=" + frozen.definitions().size()
                + ", fingerprint=" + frozen.fingerprint() + ']';
    }

    /**
     * 代码内目录（最终形态应改为读 {@code quality_rule_definition}；见类注释的迁移说明）。
     *
     * <p>{@code DEFAULT} 的冻结集在类初始化时构造一次，因此**同一 JVM 内指纹恒定**；
     * 若将来目录来自数据库，{@code freeze(...)} 应改为按 run 起始时间查
     * {@code effective_from/to} 区间，并把指纹落进 run 证据。</p>
     */
    public static final QualityRuleCatalog DEFAULT = new QualityRuleCatalog(FrozenRules.of(DEFINITIONS));

    /**
     * 一次 run 冻结的规则集与指纹（§7.3.1 line 520「一次 run 冻结完整规则版本与指纹」）。
     *
     * <p>为什么需要单独一个对象：门禁结论必须能回答「依据哪一版规则判的」。
     * 把规则集与指纹一起传递，才能让结果记录规则版本、并在事后复算
     * （修复 F-93：结论不再随代码改动被追溯改写）。</p>
     *
     * @param definitions       冻结的全部定义（不可变）
     * @param fingerprint       整体指纹
     * @param catalogVersion    目录契约版本
     * @param compatPolicyVersion 兼容策略版本
     */
    public record FrozenRules(
            List<QualityRuleDefinition> definitions,
            String fingerprint,
            String catalogVersion,
            String compatPolicyVersion) {

        /** 紧凑构造器：防御性拷贝 + 稳定排序，保证指纹可复算。 */
        public FrozenRules {
            List<QualityRuleDefinition> copy = new ArrayList<>(definitions == null ? List.of() : definitions);
            copy.sort(Comparator.comparing(QualityRuleDefinition::sourceScope)
                    .thenComparing(QualityRuleDefinition::ruleCode)
                    .thenComparingInt(QualityRuleDefinition::version));
            definitions = List.copyOf(copy);
        }

        /** 由一组定义构造冻结集；指纹 = 各定义 checksum 再哈希（顺序无关：先排序再算）。 */
        public static FrozenRules of(List<QualityRuleDefinition> definitions) {
            FrozenRules ordered = new FrozenRules(definitions, "", CATALOG_VERSION, COMPAT_POLICY_VERSION);
            StringBuilder sb = new StringBuilder(CATALOG_VERSION).append('\u001f').append(COMPAT_POLICY_VERSION);
            for (QualityRuleDefinition d : ordered.definitions()) {
                sb.append('\u001e').append(d.checksum());
            }
            return new FrozenRules(ordered.definitions(), QualityRuleDefinition.sha256Hex(sb.toString()),
                    CATALOG_VERSION, COMPAT_POLICY_VERSION);
        }

        /** 规则码 → 定义（启用版本中版本号最大者）。 */
        public Optional<QualityRuleDefinition> find(String ruleCode) {
            String code = QualityRuleDefinition.normalize(ruleCode);
            QualityRuleDefinition best = null;
            for (QualityRuleDefinition d : definitions) {
                if (d.enabled() && d.ruleCode().equals(code) && (best == null || d.version() > best.version())) {
                    best = d;
                }
            }
            return Optional.ofNullable(best);
        }

        /** 按规则码索引（含全部版本，用于展示）。 */
        public Map<String, List<QualityRuleDefinition>> byRuleCode() {
            Map<String, List<QualityRuleDefinition>> map = new TreeMap<>();
            for (QualityRuleDefinition d : definitions) {
                map.computeIfAbsent(d.ruleCode(), k -> new ArrayList<>()).add(d);
            }
            Map<String, List<QualityRuleDefinition>> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, List.copyOf(v)));
            return Map.copyOf(out);
        }
    }
}
