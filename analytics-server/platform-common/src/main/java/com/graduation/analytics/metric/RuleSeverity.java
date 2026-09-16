package com.graduation.analytics.metric;

import java.util.List;

/**
 * 质量规则的**严重度唯一所有者**（V2.4 指导书 §7.3 / V2.0 §16.3 / D-142 §1）。
 *
 * <p>口径（D-142 §1 人裁决，指导书正文不改）：<b>{@code BLOCKING} 与 {@code ERROR} 失败即阻断发布</b>；
 * {@code WARN}/{@code INFO} 记录并展示，<b>不阻断</b>。</p>
 *
 * <p>为什么需要这个类：规则码由 Spark 作业（{@code spark-jobs}，F-88 轮次禁改）回传，
 * 回传的 severity 是作业侧写死的字面量，历史上与 §7.3 不一致（例如把"只记录"的观察项写成
 * {@code ERROR}）。本类把「规则码 → 严重度」的映射集中到**一处**，由
 * {@code com.graduation.analytics.pipeline.PipelineService} 在落库前归一化，并由
 * {@code com.graduation.analytics.pipeline.DataQualityGate}（读侧质量门结论）与
 * {@code com.graduation.analytics.metric.publish.MetricPublishValidator}（指标库发布对账）
 * 在判定时使用同一口径 —— 避免出现第二个「严重度所有者」，也避免同一个规则码在不同模块
 * 被判成不同严重度。</p>
 *
 * <p><b>F-94 读侧归一化</b>：{@code data_quality_result.severity} 存的是**作业回传的字面量**，
 * 因此它会与平台口径漂移（例如全库 {@code severity='WARN'} 行数为 0，而本目录把两条重复率规则
 * 判为 WARN）。所以阻断判据是 {@link #blocks(String, String)}：**先按规则码查本目录**，
 * 不能直接拿库里的字面 severity 当结论。</p>
 *
 * <p>阈值的来源与「批准」含义：阈值一律沿用设计文稿 §5.4.2「默认阈值」与既有 SQL，本类
 * <b>不改任何阈值</b>。降级为 {@code WARN} 只能在「下游已确定性去重（或该现象本身不是错误）
 * 且错误率未超已批准阈值」时成立；这一点逐条写在 {@link #rationale(String)} 里。</p>
 *
 * <p>位置说明：本类由 {@code warehouse-pipeline} 上移到 {@code platform-common}（F-88 裁决 4）。
 * 原因是 {@code MetricPublishValidator} 在 {@code metric-analysis}，而该模块只依赖
 * {@code platform-common}；若把本类留在 {@code warehouse-pipeline}，就会制造
 * 「分析模块反向依赖流水线模块」的新耦合。包名 {@code metric} 与 {@link MetricQualityGate} 同族。</p>
 *
 * <p>覆盖范围：本类只登记**实测枚举到**的规则码 —— Spark dqc/pub/mxp、Java Landing，以及
 * 指标库发布对账（{@code MetricPublishValidator} 的 {@code check(...)} 调用产出）。
 * 逐码清单与计数**不在本注释里维护第二份**（历史上两处计数已漂移过：F-88 时写 17/15，
 * 实际随 V23、S3-10 增长到 18/17）—— 唯一对账点是 {@code RuleSeverityTest} 的
 * {@code ALL_REGISTERED_CODES} 与 {@link QualityRuleCatalog} 目录；
 * 未登记码兜底 {@link #UNREGISTERED}。S3-10 新增
 * {@code ADS_DWS_FUNNEL_RATE_RECONCILE}（率列跨层对账）；S3-22 新增
 * {@code ADS_GMV_NET_SALE_INVARIANT}（ADS 大盘「GMV ≥ 净销售 ≥ 0」同归属口径不变量，
 * 设计 §12.3 第 8 项）；S3-23 新增 {@code ADS_UV_PV_INVARIANT}（ADS 大盘「UV ≤ PV」同过滤条件
 * 不变量，设计 §12.3 第 9 项；与第 8 项分开成码）；S3-25 新增
 * {@code DWS_UV_PV_INVARIANT}（DWS 商品×日期行为宽表「UV ≤ PV」，第 9 项在 DWS 层的**同型站点**；
 * 与 ADS 侧同型但**不同码**——粒度、表、分区维度都不同，一处通过不能证明另一处通过）。</p>
 */
public final class RuleSeverity {

    /** 阻断发布：规则未通过即不得发布正式分区与新快照（指导书 §7.3）。 */
    public static final String BLOCKING = "BLOCKING";

    /** 阻断发布：与 {@link #BLOCKING} 同等阻断，仅语义上表示「错误」而非「硬门」。 */
    public static final String ERROR = "ERROR";

    /** 不阻断：允许发布，页面展示但不算失败。 */
    public static final String WARN = "WARN";

    /** 不阻断：发布操作审计项（切换/清理计数），不冒充质量规则。 */
    public static final String INFO = "INFO";

    /**
     * 未登记规则码的兜底严重度。取 {@code BLOCKING} 是**故意的保守默认**：
     * 新增规则若忘记登记，宁可阻断发布（人可介入），也不能静默放行。
     *
     * <p><b>注意（F-88 实测踩到的坑）</b>：本常量与 {@link #BLOCKING} **取值相同**，因此
     * 「{@code of(code) == BLOCKING}」**不能**用来判断规则码是否已登记 —— 必须用
     * {@link #registered(String)} 查 {@link #REGISTERED} 注册表。否则显式登记为阻断的规则会被
     * 误判成「未登记」而回退到库中过时的字面 severity，未登记的规则也可能因库里恰好写着
     * {@code WARN} 而被静默放行。</p>
     */
    public static final String UNREGISTERED = BLOCKING;

    /** 目录实际登记的规则码（读侧判据用；新增规则必须同时登记进 {@link #of(String)} 与本表）。 */
    private static final java.util.Set<String> REGISTERED = java.util.Set.of(
            // Landing（Java QualityChecker 内联规则）
            "AMOUNT_RECONCILE", "REQUIRED_FIELD_NULL_RATE", "ENUM_WHITELIST",
            "EVENT_ID_UNIQUE", "PUB_DQ_EVENT_ID_UNIQUE",
            // ADS 暂存层（Spark dqc）
            "ADS_STAGING_PRESENT", "ADS_STAGING_SNAPSHOT_ISOLATION", "ADS_STAGING_KEY_NOT_NULL",
            "PUB_DQ_BLOCKING_RULES", "ADS_DWS_FUNNEL_RECONCILE", "ADS_DWS_FUNNEL_RATE_RECONCILE",
            "ADS_GMV_NET_SALE_INVARIANT",
            "ADS_UV_PV_INVARIANT",
            // DWS 层（Spark dqc 读 DWS 宽表；第 9 项的同型站点，S3-25）
            "DWS_UV_PV_INVARIANT",
            // 发布层（Spark pub / mxp）
            "PUB_STAGING_READY", "PUB_FORMAL_PARTITION_MATCH", "PUB_POINTER_SWITCH", "PUB_STAGING_PRUNE",
            "MXP_SNAPSHOT_PINNED", "MXP_EXPORT_ROWS", "MXP_EXPORT_COMPLETE",
            // 指标库发布对账（MetricPublishValidator）
            "MP_MANIFEST_TABLES", "MP_MANIFEST_SNAPSHOT", "MP_HIVE_PATH_PINNED", "MP_EXPORT_FILES",
            "MP_EXPORT_CHECKSUM",
            "MP_ADS_ROWS_MATCH", "MP_REQUIRED_TABLES_NONEMPTY", "MP_ROW_SHAPE_CONSISTENT",
            "MP_OVERVIEW_CORE_NOT_NULL", "MP_METRIC_DICT_VERSION", "MP_VALUE_MATCH_ADS",
            "MP_METRIC_VALUE_COUNT", "MP_ACTIVE_SNAPSHOT", "MP_ADS_ROWS_DB_MATCH",
            "MP_METRIC_VALUE_DB_MATCH", "MP_ADS_WRITE_MATCH", "MP_OLD_ACTIVE_ARCHIVED");

    private RuleSeverity() {
    }

    /**
     * 规则码 → 严重度（指导书 §7.3 口径）。
     *
     * @param ruleCode 规则码（大小写不敏感，前后空白忽略）
     * @return {@link #BLOCKING}/{@link #ERROR}/{@link #WARN}/{@link #INFO}；未登记返回 {@link #UNREGISTERED}
     * @deprecated V2.5 §7.3.1 line 520：全局 {@code ruleCode} 覆盖**不是最终完成形态**。
     *         生产路径请改用 {@link QualityRuleCatalog#DEFAULT} 冻结版本后走
     *         {@link #resolve(QualityRuleCatalog.FrozenRules, String, Integer)}。
     *         本方法仅保留给「查看某码的当前默认档位」这类展示用途与既有测试；
     *         <b>不得</b>再用它决定门禁结论（那正是 F-93「历史结论被追溯改写」的成因）。
     */
    @Deprecated
    public static String of(String ruleCode) {
        String code = ruleCode == null ? "" : ruleCode.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (code) {
            // ── Landing 层（Java QualityChecker 内联规则，规则码与 AdsSql.dataQuality 同名同阈值）──
            // 金额对账：整批订单总额 vs 支付金额，不一致必须阻断（D-142 §1「金额对账不一致 ⇒ 必须阻断」）
            case "AMOUNT_RECONCILE" -> BLOCKING;
            // 必需字段缺失：下游 DwdSql.behaviorClean 直接 WHERE user_id/product_id IS NOT NULL **静默丢弃**，
            // 丢弃量直接改变 DWS/ADS 口径（D-142 §1「必需字段缺失 ⇒ 必须阻断」）
            case "REQUIRED_FIELD_NULL_RATE" -> BLOCKING;
            // 非法枚举：下游 DwdSql.behaviorClean 的 IN ('view','favorite','cart_add','cart_remove','search')
            // 同样**静默丢弃**（设计文稿 §5.4.2「非法行为类型比例为 0」），超阈值必须阻断
            case "ENUM_WHITELIST" -> BLOCKING;
            // 原始事件重复：**下游确定性去重**（DwdSql.behaviorClean 的
            // ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time) + WHERE rn=1；
            // TradeDwdJob 的 dropDuplicates("event_id")），重复行记入 dwd_reject_record 的
            // DUPLICATE_EVENT，属设计文稿 §5.4「标识唯一性：重复数据进入拒绝记录」的正常路径；
            // 阈值 = 设计文稿 §5.4.2「主键重复率不超过 0.05%」(0.0005)，本类**不放宽**该阈值
            case "EVENT_ID_UNIQUE", "PUB_DQ_EVENT_ID_UNIQUE" -> WARN;

            // ── ADS 暂存层（Spark dqc）──
            case "ADS_STAGING_PRESENT" -> BLOCKING;          // 8 张暂存表本次快照分区必须存在且非空
            // 历史暂存快照存在**本身不是错误**（D-142 §1）：发布按「本次快照的暂存路径」逐表切指针，
            // 陈旧分区不污染正式分区；且清理发生在 pub（dqc 之后），设为阻断会造成发布死锁
            // （实测依据见 AdsQualityJob.scala:55-59 与 docs/remediation-status.md:183）。
            // 「当前发布读取或混入其他快照数据 ⇒ 阻断」由 MXP_SNAPSHOT_PINNED(BLOCKING) 承担
            case "ADS_STAGING_SNAPSHOT_ISOLATION" -> WARN;
            // ADS 关键列非空：逐表真实 COUNT，阈值 0，NULL 即口径破坏 ⇒ 阻断
            case "ADS_STAGING_KEY_NOT_NULL" -> BLOCKING;
            // ADS 暂存宽表内 3 条阻断规则（AMOUNT_RECONCILE/REQUIRED_FIELD_NULL_RATE/ENUM_WHITELIST）
            // 必须全部 passed=1 —— 与 Java 侧 corePassed 同口径，缺失即阻断
            case "PUB_DQ_BLOCKING_RULES" -> BLOCKING;
            // 跨层对账（ADS 漏斗 stage 汇总 = DWS 漏斗对应列），不一致即口径破坏 ⇒ 阻断
            case "ADS_DWS_FUNNEL_RECONCILE" -> BLOCKING;
            // 跨层对账（ADS 漏斗**率列**逐格 = DWS 全站行同 dt 率列；S3-10）：ADS 只透传不重算，
            // 率列错位会让发布出去的漏斗结论直接错，而行数/关键列非空可能同时正常 ⇒ 阻断。
            // 只判跨层一致性：不判比率数值是否异常（设计 §12.3 第 10 项「宽松口径异常不一概阻断」），
            // NULL 与 NULL 判等（分母 0 时率列是 NULL）
            case "ADS_DWS_FUNNEL_RATE_RECONCILE" -> BLOCKING;
            // 同归属口径不变量（S3-22）：ADS 大盘 GMV(sale_amount) ≥ 净销售(net_sale_amount) ≥ 0
            // （设计 §12.3 第 8 项）。净销售是「支付 − 成功退款」的发布口径（设计 line 428），
            // 一旦大于实付或为负，页面上的 GMV/净销售/退款率结论整体错 ⇒ 阻断；
            // 金额任一列为 NULL 同样阻断（不可证明的不变量不得放行）。只判不改、不修数据。
            // 第 9 项「UV ≤ PV」另立一码，二者独立（同 line 512）
            case "ADS_GMV_NET_SALE_INVARIANT" -> BLOCKING;
            case "ADS_UV_PV_INVARIANT" -> BLOCKING;
            // DWS 同型站点（S3-25）：DWS 商品×日期行为宽表按 product_id×category_id 分组，
            // 一行一商品，`uv > pv` 同样是「两列已取自不同过滤条件」的口径破坏
            // （同过滤条件下的去重用户数不可能超过次数）。该表无 snapshot 维度 ⇒ 作用域＝本次 dt 分区；
            // NULL 在本层**没有**直接所有者（keyPredicates 只覆盖 ADS 暂存表）⇒ 由本码自判，
            // 任一列为 NULL 即不通过。与 ADS 侧同型但不合并（粒度/表/分区维度不同，line 512）。
            case "DWS_UV_PV_INVARIANT" -> BLOCKING;

            // ── 发布层（Spark pub / mxp）──
            case "PUB_STAGING_READY" -> BLOCKING;            // 暂存分区未就绪不得切换任何正式分区
            case "PUB_FORMAL_PARTITION_MATCH" -> BLOCKING;   // 正式分区行数必须等于暂存分区行数
            case "PUB_POINTER_SWITCH" -> INFO;               // 发布操作审计（切换表数）
            case "PUB_STAGING_PRUNE" -> INFO;                // 发布操作审计（清理计数）
            // 正式分区 Location 必须指向本次 snapshot_id —— D-142 §1「当前发布读取或混入
            // 其他快照数据 ⇒ 阻断」的**真阻断点**
            case "MXP_SNAPSHOT_PINNED" -> BLOCKING;
            case "MXP_EXPORT_ROWS" -> BLOCKING;              // 导出文件行数 = Hive 分区行数
            case "MXP_EXPORT_COMPLETE" -> BLOCKING;          // 8 张表导出完成且行数一致

            // ── 指标库发布对账（MetricPublishValidator，四阶段纯函数校验）──
            // 登记理由：这些规则由 metric-analysis 直接判阻断并阻断「写指标值 + 切 ACTIVE」，
            // 与 §7.2「跨库发布对账」同类。它们**不进** data_quality_result（只进发布报告证据），
            // 因此不经过落库归一化；登记是为了让「严重度目录」不出现未知断言，
            // 未登记的兜底也是 BLOCKING，所以此处登记只增可读性、不改变行为。
            // 名单 = 实读 MetricPublishValidator 全部 check(...) 调用产出（F-88 裁决 4 补齐 11 个漏登）。
            case "MP_MANIFEST_TABLES", "MP_MANIFEST_SNAPSHOT", "MP_HIVE_PATH_PINNED", "MP_EXPORT_FILES",
                 "MP_EXPORT_CHECKSUM",
                 "MP_ADS_ROWS_MATCH", "MP_REQUIRED_TABLES_NONEMPTY", "MP_ROW_SHAPE_CONSISTENT",
                 "MP_OVERVIEW_CORE_NOT_NULL", "MP_METRIC_DICT_VERSION", "MP_VALUE_MATCH_ADS",
                 "MP_METRIC_VALUE_COUNT", "MP_ACTIVE_SNAPSHOT", "MP_ADS_ROWS_DB_MATCH",
                 "MP_METRIC_VALUE_DB_MATCH" -> BLOCKING;
            // 目录登记项，当前实现未产出该码；与错误码 MP_ADS_WRITE_FAILED（见
            // S20260901_38.failure_reason）易混，**勿合并**：前者是"清单对账"规则码，
            // 后者是"写 ADS 宽表失败"的失败原因串，两者语义与位置都不同。
            case "MP_ADS_WRITE_MATCH" -> BLOCKING;
            // 上一个 ACTIVE 是否已归档：**由实现显式声明为 INFO**（不是阻断项）
            case "MP_OLD_ACTIVE_ARCHIVED" -> INFO;

            default -> UNREGISTERED;
        };
    }

    /**
     * 规则码是否已登记进目录（大小写不敏感，前后空白忽略）。
     *
     * <p>为什么需要独立方法而不是比较 {@link #of(String)} 与 {@link #UNREGISTERED}：
     * 两者取值相同（{@code "BLOCKING"}），比较无法区分「显式登记为阻断」与「未登记」。</p>
     *
     * @param ruleCode 规则码
     * @return 已登记为 {@code true}
     * @deprecated V2.5 §7.3.1：登记状态应由**冻结的规则集**回答
     *         （{@link QualityRuleCatalog.FrozenRules#find(String)}），
     *         因为同一规则码在不同版本/作用域下可以有不同的启用状态。
     *         本方法只反映代码内默认目录，不代表某次 run 的冻结口径。
     */
    @Deprecated
    public static boolean registered(String ruleCode) {
        return REGISTERED.contains(ruleCode == null
                ? "" : ruleCode.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 全部已登记规则码的**只读视图**（S3-49 新增；内容与本类 {@code REGISTERED} 是同一实体，不是副本）。
     *
     * <p><b>为什么必须由本类暴露</b>：跨模块守卫（{@code SparkRuleCodeRegistryGuardTest}）要做
     * **双向**核对 —— 「Spark 生产源码里的码都已登记」＋「登记表里由 Spark 承载的码都还有站点」。
     * 若守卫在自己那里另抄一份码表，就等于**再造一个所有者**，正是 S3-30/S3-49 要消除的漂移面；
     * 因此把唯一所有者按只读方式暴露出来，而不是复制。</p>
     *
     * <p>语义边界：本集合是**代码内默认目录**（与 {@link #registered(String)} 同一口径），
     * 不代表某次 run 的冻结规则集（冻结口径见 {@link QualityRuleCatalog.FrozenRules}）。</p>
     *
     * @return 不可变集合（{@code Set.of} 语义）；空/未登记仍由 {@link #registered(String)} 回答
     */
    public static java.util.Set<String> registeredCodes() {
        return REGISTERED;
    }

    /**
     * 该规则为什么是这个严重度（审计用，落 IMPL-REPORT 与运维页说明）。
     *
     * @param ruleCode 规则码
     * @return 中文依据；未登记返回兜底说明
     */
    public static String rationale(String ruleCode) {
        String code = ruleCode == null ? "" : ruleCode.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (code) {
            case "AMOUNT_RECONCILE" -> "金额对账不一致（D-142 §1 必须阻断）";
            case "REQUIRED_FIELD_NULL_RATE" -> "必需字段缺失；下游 DwdSql.behaviorClean 静默丢弃该行（D-142 §1 必须阻断）";
            case "ENUM_WHITELIST" -> "非法枚举；下游 DwdSql.behaviorClean 静默丢弃该行（设计文稿 §5.4.2 比例为 0）";
            case "EVENT_ID_UNIQUE", "PUB_DQ_EVENT_ID_UNIQUE" ->
                    "原始事件重复；下游确定性去重（ROW_NUMBER PARTITION BY event_id / dropDuplicates），"
                            + "重复行进 dwd_reject_record 的 DUPLICATE_EVENT；阈值 0.0005 来自设计文稿 §5.4.2，未放宽";
            case "ADS_STAGING_PRESENT" -> "ADS 暂存分区缺失/为空 ⇒ 发布无数据可切（硬门）";
            case "ADS_STAGING_SNAPSHOT_ISOLATION" ->
                    "历史暂存快照存在本身不是错误（D-142 §1）；指针按本次快照切换，陈旧分区由 pub 按引用清理；"
                            + "真阻断点由 MXP_SNAPSHOT_PINNED 承担（设为阻断会造成发布死锁）";
            case "ADS_STAGING_KEY_NOT_NULL" -> "ADS 关键列 NULL ⇒ 口径破坏（阈值 0）";
            case "PUB_DQ_BLOCKING_RULES" -> "暂存宽表内 3 条阻断规则必须全过（与 Java corePassed 同口径）";
            case "ADS_DWS_FUNNEL_RECONCILE" -> "ADS↔DWS 跨层漏斗对账不一致 ⇒ 口径破坏";
            case "ADS_DWS_FUNNEL_RATE_RECONCILE" ->
                    "ADS 漏斗率列 ≠ DWS 全站行同 dt 率列 ⇒ 发布出去的漏斗结论直接错"
                            + "（行数与关键列非空可能同时正常，只有逐格率对账能发现；"
                            + "只判跨层一致性，不判比率数值是否异常）";
            case "ADS_GMV_NET_SALE_INVARIANT" ->
                    "ADS 大盘同归属口径不变量被破坏（设计 §12.3 第 8 项）：净销售 > GMV，或净销售/GMV 为负，"
                            + "或金额列为 NULL ⇒ GMV、净销售、退款率等结论整体不可信（净销售=支付−成功退款，"
                            + "设计 line 428）；只判不变量、不修数据；第 9 项「UV≤PV」另立一码";
            case "ADS_UV_PV_INVARIANT" ->
                    "ADS 大盘同过滤条件不变量被破坏（设计 §12.3 第 9 项 L507）：去重浏览用户数 > 浏览次数 "
                            + "⇒ 两列已取自**不同过滤条件**（同过滤条件下的去重用户数不可能超过次数），"
                            + "pv/uv 是转化率等结论的分子分母，口径一破则整组浏览类结论不可信；"
                            + "dau 是全事件口径（不同过滤条件），dau > uv 合法、不纳入本码；"
                            + "NULL 由 ADS_STAGING_KEY_NOT_NULL 唯一判定；只判不变量、不修数据";
            case "DWS_UV_PV_INVARIANT" ->
                    "DWS 商品×日期行为宽表同过滤条件不变量被破坏（设计 §12.3 第 9 项 L507 的同型站点，S3-25）："
                            + "某商品去重浏览用户数 > 浏览次数 ⇒ 两列已取自**不同过滤条件**"
                            + "（DwsSql.productBehaviorDay 中 pv/uv 同出一个 behavior_type = 'view' 条件）；"
                            + "该表是商品热度/商品转化等 ADS 结论的直连来源，口径一破则整组商品浏览结论不可信。"
                            + "与 ADS 侧 ADS_UV_PV_INVARIANT 同型但**不合并**（粒度＝商品×日期逐行、表、"
                            + "分区维度（无 snapshot）都不同，一处通过不能证明另一处通过）；"
                            + "pv/uv 任一为 NULL 由**本码**判不通过（该表无既有关键列非空守卫，唯一所有者空缺）；"
                            + "只判不变量、不修数据";
            case "PUB_STAGING_READY" -> "暂存未就绪不得切换正式分区（发布前预检）";
            case "PUB_FORMAL_PARTITION_MATCH" -> "正式分区行数 ≠ 暂存分区行数 ⇒ 发布不完整";
            case "MXP_SNAPSHOT_PINNED" -> "正式分区未指向本次 snapshot_id ⇒ 混入其他快照数据（D-142 §1 必须阻断）";
            case "MXP_EXPORT_ROWS" -> "导出文件行数 ≠ Hive 分区行数 ⇒ 指标库会写入残缺数据";
            case "MXP_EXPORT_COMPLETE" -> "存在未完成/行数不一致的导出表 ⇒ 指标库快照不完整";
            case "PUB_POINTER_SWITCH", "PUB_STAGING_PRUNE" -> "发布操作审计项（切换/清理计数），不冒充质量规则";
            case "MP_MANIFEST_SNAPSHOT" -> "发布清单的快照/业务日与本次请求不一致 ⇒ 可能把上一版数据当本次发布";
            case "MP_MANIFEST_TABLES" -> "8 张宽表未齐备或清单列与白名单不一致 ⇒ 发布面不完整";
            case "MP_HIVE_PATH_PINNED" -> "来源分区未指向本次 snapshot_id ⇒ 会读到上一版数据";
            case "MP_EXPORT_FILES" -> "导出文件缺失 ⇒ 写入的是残缺数据";
            case "MP_EXPORT_CHECKSUM" ->
                    "导出制品内容摘要 ≠ 清单 checksum ⇒ 制品被截断/错位改写"
                            + "（行数与文件存在性都可能同时正常，只有内容验证能发现）";
            case "MP_ADS_ROWS_MATCH" -> "写入行数 ≠ 导出清单行数 ⇒ 搬运不完整";
            case "MP_REQUIRED_TABLES_NONEMPTY" -> "概览/趋势/漏斗/活跃表为空 ⇒ 看板核心指标无来源";
            case "MP_ROW_SHAPE_CONSISTENT" -> "同表各行列集不一致 ⇒ 批量插入会错位";
            case "MP_OVERVIEW_CORE_NOT_NULL" -> "概览核心字段为空 ⇒ 指标值为空";
            case "MP_METRIC_DICT_VERSION" -> "指标码不在字典内或版本不一致 ⇒ 无口径可溯";
            case "MP_VALUE_MATCH_ADS" -> "metric_value 与 ADS 概览不同值 ⇒ 发布器重算/篡改了口径";
            case "MP_METRIC_VALUE_COUNT" -> "核心指标值不足 8 条 ⇒ 指标库快照不完整";
            case "MP_ACTIVE_SNAPSHOT" -> "ACTIVE 指针未指向本次快照 ⇒ 发布未生效";
            case "MP_ADS_ROWS_DB_MATCH" -> "只读账号实读行数 ≠ 清单行数 ⇒ 写入未真正落地";
            case "MP_METRIC_VALUE_DB_MATCH" -> "只读账号实读指标值 ≠ 发布值 ⇒ 读侧看不到发布结果";
            case "MP_ADS_WRITE_MATCH" ->
                    "目录登记项，当前实现未产出该码；与错误码 MP_ADS_WRITE_FAILED（见 S20260901_38.failure_reason）"
                            + "易混，勿合并";
            case "MP_OLD_ACTIVE_ARCHIVED" ->
                    "上一个 ACTIVE 的归档情况说明（发布结果告知项），由实现显式声明为 INFO，不阻断";
            default -> "未登记规则码，按保守默认 BLOCKING 处理（宁可阻断也不静默放行）";
        };
    }

    /**
     * 该严重度失败时是否**阻断发布**（D-142 §1）。
     *
     * <p>大小写不敏感；{@code null}/空/未知值一律按阻断处理（与 {@link #UNREGISTERED} 同一条保守原则）。</p>
     *
     * @deprecated 读侧请改用 {@link #blocks(String, String)}（按规则码归一化，F-94）；
     *         本单参重载只看字面 severity，用于「作业回传标签」自证与测试。
     */
    @Deprecated
    public static boolean blocks(String severity) {
        if (severity == null) {
            return true;
        }
        String s = severity.trim().toUpperCase(java.util.Locale.ROOT);
        if (s.isEmpty()) {
            return true;
        }
        return !WARN.equals(s) && !INFO.equals(s);
    }

    /**
     * 读侧阻断判据（**版本化**，V2.5 指导书 §7.3.1 line 520/524 的正解）。
     *
     * <p>与旧实现的关键差别：判据不再问「这个规则码全局是什么严重度」，而是问
     * 「<b>本次 run 冻结的规则集</b>里，这个码的这一版是什么严重度」。
     * 这正是 §7.3.1 line 520 的要求 —— 全局 {@code ruleCode} 覆盖不是最终形态。</p>
     *
     * <p><b>不回退库中字面 severity</b>：库里的 {@code severity} 是 Spark 作业回传的字面量
     * （本泳道禁改作业侧），已实测与平台口径漂移（两条重复率规则在库里是 {@code ERROR}，
     * 平台口径是观察项）。用它当回退会让「作业写错标签」直接决定门禁结论。</p>
     *
     * <p><b>未登记码不得盲信传来的 WARN</b>（§7.3.1 line 524 原文）：未登记 ⇒ 返回
     * {@link RuleVerdict#UNREGISTERED_RULE}，调用方（发布路径）必须**停止发布并报未登记规则**，
     * 而不是当成通过。</p>
     *
     * @param rules    本次 run 冻结的规则集
     * @param ruleCode 规则码
     * @param passed   {@code passed}（{@code null} 或 {@code != 1} 视为未通过）
     * @return 判定结论（含有效严重度与兼容解释）
     */
    public static RuleVerdict resolve(QualityRuleCatalog.FrozenRules rules, String ruleCode, Integer passed) {
        java.util.Optional<QualityRuleDefinition> def = rules == null
                ? java.util.Optional.empty() : rules.find(ruleCode);
        if (def.isEmpty()) {
            // 未登记规则码：不盲信、不静默放行，交由调用方停止发布并报错
            return RuleVerdict.unregistered(ruleCode);
        }
        QualityRuleDefinition d = def.get();
        // 条件严重度：未超批准阈值 ⇒ 观察项（WARN，不阻断）；超阈值 ⇒ 阻断。
        // §7.3.1 line 522：「原始重复事件仅在确定性去重已证且重复率不超批准阈值时为观察项；超过阈值阻断」
        String effective = d.severityMode() == QualityRuleDefinition.SeverityMode.THRESHOLD_OBSERVATION
                ? (failed(passed) ? BLOCKING : d.severity())
                : d.severity();
        String explanation = d.severityMode() == QualityRuleDefinition.SeverityMode.THRESHOLD_OBSERVATION
                ? (failed(passed)
                    ? "阈值判定未通过 ⇒ 由观察项升为阻断（§7.3.1 line 522「超过阈值阻断」）"
                    : "阈值判定通过 ⇒ 观察项，不阻断（确定性去重已证且重复率未超批准阈值）")
                : "固定严重度（" + d.stage() + " 阶段）";
        boolean blocks = (BLOCKING.equals(effective) || ERROR.equals(effective)) && failed(passed);
        return new RuleVerdict(true, d.ruleCode(), d.version(), d.severity(), effective, blocks, explanation);
    }

    /**
     * 版本化判据的便捷入口：未登记码或未通过都按**阻断**处理（保守默认）。
     *
     * <p>仅供不需要区分「未登记」与「阻断」的汇总场景使用；发布路径请用
     * {@link #resolve} 以便对未登记码**报错停止**而不是仅仅阻断。</p>
     */
    public static boolean blocks(QualityRuleCatalog.FrozenRules rules, String ruleCode, Integer passed) {
        return resolve(rules, ruleCode, passed).blocks();
    }

    /**
     * 严重度是否为已知档位（{@code BLOCKING}/{@code ERROR}/{@code WARN}/{@code INFO}）。
     * 用于校验 {@code quality_rule_definition.severity} 的取值合法性。
     */
    public static boolean isKnownSeverity(String severity) {
        if (severity == null) {
            return false;
        }
        String s = severity.trim().toUpperCase(java.util.Locale.ROOT);
        return BLOCKING.equals(s) || ERROR.equals(s) || WARN.equals(s) || INFO.equals(s);
    }

    /**
     * 未登记规则码的报错（§7.3.1 line 524：「未知规则码不可盲信传来的 WARN，
     * 应停止发布并报未登记规则」）。
     *
     * <p>为什么用异常而不是返回 {@code true}：返回 {@code true} 只表达「阻断」，
     * 运维看到的是"质量失败"，无从知道根因是**规则未登记**（需要登记规则，而非改数据）。
     * §7.3.1 line 528 同时要求「失败必须可定位到 stage/rule，不只显示 RUN_JOB_FAILED」。</p>
     */
    public static class UnknownRuleException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient List<String> ruleCodes;

        public UnknownRuleException(List<String> ruleCodes) {
            super("检测到未登记规则码，停止发布：" + ruleCodes + "（§7.3.1 line 524：未知规则码不可盲信传来的 WARN）");
            this.ruleCodes = List.copyOf(ruleCodes == null ? List.of() : ruleCodes);
        }

        /** 未登记的规则码列表（供证据/页面定位到 rule）。 */
        public List<String> ruleCodes() {
            return ruleCodes;
        }
    }

    /**
     * 版本化判定的结论。
     *
     * <p>「有效严重度」与「原始严重度」分开记录是 §7.3.1 line 520 的明确要求：
     * 原始严重度是作业回传/库中存量值，有效严重度是本目录按版本判定的值。
     * 两者都要保留，接口再给出兼容解释（§7.3.1 line 524）。</p>
     *
     * @param registered     规则码是否已在目录登记
     * @param ruleCode       规范化规则码
     * @param ruleVersion    生效版本（未登记为 {@code 0}）
     * @param declaredSeverity 定义里声明的严重度（未登记为 {@code null}）
     * @param effectiveSeverity 有效严重度（未登记为 {@link #UNREGISTERED}）
     * @param blocks         未通过时是否阻断发布
     * @param explanation    中文解释（页面/证据展示兼容口径用）
     */
    public record RuleVerdict(
            boolean registered,
            String ruleCode,
            int ruleVersion,
            String declaredSeverity,
            String effectiveSeverity,
            boolean blocks,
            String explanation) {

        /** 未登记规则码的结论：有效严重度按兜底（阻断），但 {@code registered=false} 供调用方报错。 */
        static RuleVerdict unregistered(String ruleCode) {
            return new RuleVerdict(false, QualityRuleDefinition.normalize(ruleCode), 0, null, UNREGISTERED, true,
                    "未登记规则码：按保守默认阻断，须先登记规则版本（§7.3.1 line 524：不得盲信传来的 WARN）");
        }
    }

    /** 该结果是否「未通过」（{@code passed != 1}，含 NULL=未判定）。 */
    public static boolean failed(Integer passed) {
        return passed == null || passed != 1;
    }

    /**
     * 把布尔 {@code passed} 适配成 {@link #failed(Integer)} 的三态约定
     * （{@code true} → 1，{@code false} → 0），供 {@code MetricPublisherPort.Check} 这类
     * 布尔字段的调用方复用同一套 `failed` 判定。
     */
    public static Integer failedFlag(boolean passed) {
        return passed ? 1 : 0;
    }
}
