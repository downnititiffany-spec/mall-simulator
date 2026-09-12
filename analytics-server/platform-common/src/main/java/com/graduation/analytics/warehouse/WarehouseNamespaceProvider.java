package com.graduation.analytics.warehouse;

import com.graduation.analytics.common.PlatformBizException;

import java.util.Optional;

/**
 * 「某个源的数仓库名空间是什么」的**唯一**来源（P1-04 建立，P2-07 改为按源解析）。
 *
 * <p>取值链只有一条：{@code source_registry.warehouse_prefix}（P2-07 起，见
 * {@code V18__source_warehouse_prefix.sql}）→ {@link WarehouseNamespace}。
 * {@link #current()} 只是 {@code forSource(唯一 ACTIVE runtime_profile 行的 source_id)}
 * 的简写，不是第二条链。实现放在 connection-ingestion
 * （{@code ActiveProfileWarehouseNamespaceProvider}），消费方（AI 证据血缘、流水线提交、
 * P4 页面）只依赖本接口，不各自拼库名，也不各自读配置。</p>
 *
 * <p><b>非法前缀不静默兜底</b>：本接口不提供任何"取不到就给缺省"的重载。源级前缀为空、
 * 形状非法、源不存在、运行环境未绑定源 —— 四种情况一律如实失败并带稳定错误码，
 * 由调用方在 spark-submit 之前中止。（为什么连"空"也要拒：DDL 层刻意不设
 * {@code DEFAULT}，若解析侧把空当成缺省，等于把 DDL 层拒绝掉的静默兜底从代码里放回来
 * —— 忘记填前缀的源会住进第一个源的库。）</p>
 *
 * <p>契约（规则本体，非本文件）：{@code contract-specs/specs/warehouse-namespace.v2.json}，
 * 规则实现唯一所有者是 {@link WarehouseNamespace}。</p>
 */
public interface WarehouseNamespaceProvider {

    /**
     * 按**指定源**解析本次运行的源身份（源编码 + 命名空间，P2-07 / D-070）：一次运行的库名
     * 与 {@code source_system} 都跟着那次运行所用的源走。
     *
     * <p>这是本接口唯一的读取点：{@code sourceId} → {@code source_registry} 一行 →
     * （{@code source_code}, {@code warehouse_prefix}），一次读取得到两者，
     * 因此不存在"库名来自 A 源、源编码来自 B 源"的窗口。返回值是**已校验**的不可变值对象。</p>
     *
     * @param sourceId {@code source_registry.id}；{@code null} 表示运行环境未绑定源
     *                 → {@code SOURCE_NOT_BOUND}（fail-closed，不回落到"当前源"）
     * @throws PlatformBizException {@code SOURCE_NOT_BOUND}（未绑定源）、
     *         {@code SOURCE_NOT_FOUND}（源不存在）、
     *         {@code PARAM_INVALID}（{@code source_code} 或前缀为空）、
     *         {@code WAREHOUSE_PREFIX_*}（前缀形状非法）
     */
    RunSourceIdentity runSource(Long sourceId);

    /** 本次运行的源身份（= 唯一 ACTIVE {@code runtime_profile} 行绑定源的 {@code runSource}） */
    RunSourceIdentity currentRunSource();

    /**
     * 库名视图（AI 证据血缘等只关心库名的消费方用）：{@code currentRunSource().namespace()}。
     * 不是第二条链——同一次读取的同一结果；失败面与 {@link #runSource(Long)} 相同。
     */
    default WarehouseNamespace current() {
        return currentRunSource().namespace();
    }

    /**
     * 库名视图（按指定源）：{@code runSource(sourceId).namespace()}。
     * 消费方需要"源编码 + 库名"两者时请直接用 {@link #runSource(Long)}，不要分别调用两次。
     */
    default WarehouseNamespace forSource(Long sourceId) {
        return runSource(sourceId).namespace();
    }

    /**
     * 源级前缀列的**接受判据**（唯一一处，写入侧与解析侧共用）：
     * 先要求"已登记"（非 null、非空白），再把形状/保留字/层后缀交给
     * {@link WarehouseNamespace#validationError(String)}（规则本体，本方法不复制任何规则）。
     *
     * <p>为什么"必填"要与形状分开：{@code validationError} 按契约把 null/空串视为合法
     * （契约 {@code blankIsDefault: true} 描述的是**字符串→命名空间**这一层的历史语义），
     * 但源级列是 NOT NULL 的登记值，"没登记"不是一种前缀。两者混在一起就会出现
     * "库里存着空串 → 解析成缺省 → 静默住进别人的库"。故此处显式拒绝空值，
     * 并把契约四码原样透出（写入侧据此断言具体错误码）。</p>
     */
    static WarehouseNamespace requireSourcePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "warehouse_prefix 必填且不得为空白（数仓命名空间前缀已下沉到源级，D-070/D-071）："
                            + "不提供缺省值——缺省会让忘记填前缀的源静默落进别的源的库");
        }
        Optional<String> error = WarehouseNamespace.validationError(raw);
        if (error.isPresent()) {
            throw new PlatformBizException(error.get(),
                    "warehouse_prefix 非法（" + error.get() + "）：" + raw
                            + "；形状 " + WarehouseNamespace.PREFIX_PATTERN
                            + "，不得含连续下划线、不得是保留库名、不得已带层后缀");
        }
        return WarehouseNamespace.of(raw);
    }

    /**
     * 源编码（{@code source_registry.source_code}）的**接受判据**（唯一一处）：
     * 非 null、非空白。为什么连"空"也拒、且不给缺省：与
     * {@link #requireSourcePrefix(String)} 同一条理由——源编码会作为 {@code --sourceSystem}
     * 下发并写进 ODS 的 {@code source_system} 列，猜一个源编码等于把数据标成别人的源
     * （P2-01 起 spark-jobs 侧对缺失即失败，平台侧必须在提交前给出同一个结论，
     * 而不是"先提交、让 Spark 30 秒后失败"）。
     *
     * <p>形状规则不在这里：{@code source_code} 的形状唯一所有者是
     * {@code SourceRegistry.SOURCE_CODE_PATTERN}（写入侧已强制），本方法只负责"有没有值"。</p>
     */
    static String requireSourceCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "source_registry.source_code 为空，无法确定本次运行的源身份（--sourceSystem）："
                            + "不提供缺省值——猜一个源编码会让数据被标成别的源（D-070/D-073 同口径）");
        }
        return raw;
    }
}
