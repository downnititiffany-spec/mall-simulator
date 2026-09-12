package com.graduation.analytics.warehouse;

import com.graduation.analytics.common.PlatformBizException;

/**
 * 一次运行的「源身份」：源编码（{@code source_registry.source_code}）+ 由该源派生的数仓命名空间。
 *
 * <p>为什么把两者捆成一个值，而不是各传一个字符串：它们是**同一次读取**同一行的两个字段
 * （{@code source_registry} id → 行 → {@code source_code} / {@code warehouse_prefix}），
 * 拆成两个参数就会出现"前缀取自第 1 次读、源编码取自第 2 次读"的漂移窗口，
 * 也可能被调用方配错对（A 源的库名 + B 源的 source_system）。捆绑后
 * 「同源」是类型层面的保证，不由调用方自觉维持。</p>
 *
 * <p>两个字段各自对应一个 spark-submit 参数，都必须在**提交前**确定：
 * {@code --hiveDatabasePrefix=<namespace.prefix()>}（库名，P2-07/D-070）与
 * {@code --sourceSystem=<sourceCode>}（ODS 行的 {@code source_system} 取值，P2-01 起
 * spark-jobs 侧要求"缺失即失败"）。本类**不提供任何缺省值**：构造即校验，缺一即拒。</p>
 *
 * <p>失败面（fail-closed，无兜底）：{@code source_code} 为空 → {@code PARAM_INVALID}；
 * 命名空间为 null → 调用方漏了解析，属编程错误 → {@code IllegalArgumentException}。
 * 两者的取值链与"空值为什么也要拒"见 {@link WarehouseNamespaceProvider}。</p>
 *
 * @param sourceCode 源编码，写入 ODS 表 {@code source_system} 列；不得为空白
 * @param namespace  由同一行的 {@code warehouse_prefix} 解析出的命名空间（非 null）
 */
public record RunSourceIdentity(String sourceCode, WarehouseNamespace namespace) {

    public RunSourceIdentity {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "本次运行的源身份缺失：source_registry.source_code 为空，拒绝提交作业"
                            + "（--sourceSystem 无值可下发，不做任何兜底；P2-01 起 spark-jobs 侧"
                            + "缺少该参数即失败）");
        }
        if (namespace == null) {
            // 到这一步说明调用方绕过了唯一解析链（WarehouseNamespaceProvider.runSource），
            // 而不是"数据缺失"，故按编程错误处理。
            throw new IllegalArgumentException(
                    "RunSourceIdentity.namespace 不能为空：库名前缀必须由 WarehouseNamespaceProvider "
                            + "按同一行解析后传入");
        }
    }
}
