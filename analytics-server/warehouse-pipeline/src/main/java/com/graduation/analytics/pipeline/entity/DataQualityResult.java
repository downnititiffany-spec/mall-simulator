package com.graduation.analytics.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据质量结果（§5.4：规则、计数、阈值、是否阻断）。
 *
 * <p><b>严重度是两列，不是一列（F-88 / V25-Q01，V2.5 §7.3.1 line 520）</b>：
 * {@link #severity} 是规则<b>声明</b>的档位（来自 {@code quality_rule_definition.severity}），
 * {@link #effectiveSeverity} 是本行结果<b>实际生效</b>的档位（经 {@link com.graduation.analytics.metric.RuleSeverity#resolve}
 * 条件判定后）。两列并存才能事后区分「声明 WARN 但超阈值生效阻断」与「本来就是阻断」——
 * 只看一列时这两种完全不同的判定会写成同一个值，无法复算（F-93 的成因之一）。</p>
 *
 * <p>映射：本实体无 XML，由 MyBatis-Plus 按 camelCase→snake_case 自动映射
 * （{@code effectiveSeverity} → {@code effective_severity} 等），与既有的 {@code runId}→{@code run_id} 同机制。</p>
 */
@Data
@TableName("data_quality_result")
public class DataQualityResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String ruleCode;

    /** 规则层次（R6-13 §16.1：LANDING/DWD/DWS/ADS_STAGING/PUBLISH），运维页分层展示 */
    private String layer;

    /**
     * 规则<b>声明</b>的档位（BLOCKING 阻断发布 / ERROR 记录 / INFO 操作审计）。
     *
     * <p><b>本列不是「生效档位」</b>：条件观察项（{@code THRESHOLD_OBSERVATION}，如
     * {@code EVENT_ID_UNIQUE}）声明 WARN，超阈值时生效 BLOCKING —— 生效档位见
     * {@link #effectiveSeverity}。写侧落库时本列取 {@code RuleVerdict#declaredSeverity()}。</p>
     *
     * <p><b>可空</b>：NULL 的含义是「本行为版本化引入前记录，无版本信息，不得解读为 WARN/PASS」；
     * 未登记规则码的新行同样写 NULL（见 {@link #ruleVersion}）。</p>
     */
    private String severity;

    /**
     * 本行结果<b>实际生效</b>的档位（BLOCKING/ERROR/WARN/INFO，未登记规则码为 {@code UNREGISTERED}）。
     *
     * <p>为什么必须与 {@link #severity} 分成两列：条件判定会把观察项升为阻断，只保留一个值时
     * 「声明 WARN 但生效阻断」与「本来就是阻断」不可区分，历史 run 的结论就无法复算。</p>
     *
     * <p><b>可空</b>：NULL 的含义是「本行为版本化引入前记录，无版本信息，不得解读为 WARN/PASS」。
     * 写侧接入（F-88）之后新产生的行必须非空 —— 因此「新行是否漏填」与「历史行」可由本列直接区分。</p>
     */
    private String effectiveSeverity;

    /**
     * 本行结果所用规则的版本号（对应 {@code quality_rule_definition.version}）。
     *
     * <p><b>可空</b>，两种 NULL 含义不同但都表示「没有可用版本号」：</p>
     * <ul>
     *   <li>版本化引入前的历史行：本列为 NULL；</li>
     *   <li>规则码<b>未登记</b>：本列也写 NULL（<b>不写 0</b>）。为什么不是 0：0 是一个"像版本号"的
     *       值，下游会把它当成「第 0 版规则」而不是「查不到版本」；{@code RuleVerdict.ruleVersion()}
     *       在未登记时给的 0 是<b>内存哨兵</b>，落库必须显式转成 NULL，否则「未登记」在库里
     *       伪装成「登记过的第 0 版」。</li>
     * </ul>
     *
     * <p>不得回填猜测值：历史行本来就没有版本信息，猜测会被下游读成事实。也不设 DEFAULT，
     * 否则漏填会被静默伪装成某个真值（见 V20 迁移注释）。</p>
     */
    private Integer ruleVersion;

    /**
     * 本次判定所用兼容策略版本（目录常量 {@code compat-v1}）。
     *
     * <p>存在的意义：将来若放宽兼容策略，历史行仍能指出「当初按哪版策略判的」。</p>
     *
     * <p><b>可空</b>：NULL 的含义是「本行为版本化引入前记录，无版本信息，不得解读为 WARN/PASS」。
     * 未登记规则码的新行<b>仍要写</b>本列（版本查不到，不代表"这次判定用的策略"查不到）。</p>
     */
    private String compatPolicyVersion;

    /**
     * 本次 run 冻结的<b>整个规则集</b>指纹（SHA-256 小写十六进制，64 位）。
     *
     * <p>粒度是「run 冻结的那一组规则」，不是单条规则 —— 单条规则的指纹在
     * {@code quality_rule_definition.checksum}。指纹相同 ⇒ 两次判定用的是同一套口径。</p>
     *
     * <p><b>可空</b>：NULL 的含义是「本行为版本化引入前记录，无版本信息，不得解读为 WARN/PASS」。
     * 未登记规则码的新行<b>仍要写</b>本列，否则「新产生但未登记」与「版本化之前的历史行」无法区分。</p>
     */
    private String ruleFingerprint;

    /** 规则作用对象（表名或分区范围） */
    private String targetTable;

    /** 本次快照号（可追溯规则作用于哪份快照） */
    private String snapshotId;

    private Long checkCount;

    private Long errorCount;

    private BigDecimal errorRate;

    private String threshold;

    private Integer passed;

    private String detail;

    private LocalDateTime createdAt;
}