package com.graduation.analytics.testsupport;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 写入型 IT 的「默认关闭」条件（指导书 V2.5 §9.4）。
 *
 * <p>语义：**没有登记测试隔离档案 → 整个类不执行**（JUnit 报告为 disabled）。
 * 这与 {@link TestIsolationGuard.MissingConfigurationException} 是同一口径的两面：</p>
 * <ul>
 *   <li>类级 {@code ExecutionCondition} 负责「不执行」——避免任何连接/写入尝试；</li>
 *   <li>{@link TestIsolationGuard#loadContext()} 负责「一旦真的执行就必须有合法配置」——
 *       任何绕过注解的路径（直接调用、反射、后续重构）都会**失败**而不是静默跳过。</li>
 * </ul>
 *
 * <p>反向证明在 {@code TestIsolationGuardTest}：配置缺失/不完整/指向正式库时，
 * guard 一律抛异常。这里不做「找不到就假设本地库」的兜底，也不提供
 * {@code analytics_metric} 等正式库 fallback。</p>
 *
 * <p><b>只有「档案不存在」才允许变成 disabled。</b>档案存在但非法（指向正式库、路径不是隔离根、
 * 账号在禁止清单内等）会抛 {@link TestIsolationGuard.IsolationViolationException}，本条件
 * **原样抛出**——那属于「配置错了」，必须让整个类红掉，绝不能被降级成「跳过」而假装通过。
 * 同理，任何非预期异常也不吞。</p>
 */
public final class IsolationProfileCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            TestIsolationGuard.TestIsolationConfig config = TestIsolationGuard.load();
            return ConditionEvaluationResult.enabled("测试隔离档案已登记：" + config.source()
                    + " testRunId=" + config.testRunId() + " metricDb=" + config.metricDb());
        } catch (TestIsolationGuard.MissingConfigurationException e) {
            return ConditionEvaluationResult.disabled("未提供测试隔离配置，本写入型 IT 默认关闭（"
                    + e.getClass().getSimpleName() + "：" + e.getMessage() + "）");
        }
    }
}
