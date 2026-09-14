package com.graduation.generator.adapter;

import java.util.Map;

/**
 * 一次调用之前的能力声明（§4.1 的 {@code capabilities()} 返回类型）。
 *
 * <p><b>与 {@link MallTargetAdapter#test} 的分工（指导书未定义，最小设计）</b>：
 * {@code capabilities()} 是<b>不联网</b>的静态声明——"这台适配器按当前配置<b>应该</b>能做哪些事"；
 * {@code test(TargetConfig)} 是<b>联网实测</b>——"这台商城<b>实际</b>能应答哪些路由"。
 * 两者都重要：引擎在自动化任务里不能为每次运行先做一轮探测，但也不能把声明当成实测结论，
 * 所以运行时写入 {@code generation_run.notes} 的是 {@code test} 的结果，而不是这里的声明。</p>
 *
 * @param verdicts 能力 → 三态判定；缺失的键一律按 {@link CapabilityVerdict#UNDETERMINED} 处理
 * @param declared true 表示这批判定来自配置声明（未实测），用于断言与日志区分
 */
public record TargetCapabilities(Map<MallCapability, CapabilityVerdict> verdicts, boolean declared) {

    public TargetCapabilities {
        if (verdicts == null) {
            throw new IllegalArgumentException("capabilities 不得为 null（无能力请传空表）");
        }
        verdicts = Map.copyOf(verdicts);
    }

    /** 只有声明、没有实测（{@code capabilities()} 的默认形态） */
    public static TargetCapabilities declared(Map<MallCapability, CapabilityVerdict> verdicts) {
        return new TargetCapabilities(verdicts, true);
    }

    /** 空能力表：什么都不声明，全部落 UNDETERMINED */
    public static TargetCapabilities none() {
        return declared(Map.of());
    }

    /** 缺失即 {@code UNDETERMINED}——查不到不等于"不支持" */
    public CapabilityVerdict verdict(MallCapability capability) {
        return verdicts.getOrDefault(capability, CapabilityVerdict.UNDETERMINED);
    }

    public boolean isSupported(MallCapability capability) {
        return verdict(capability) == CapabilityVerdict.SUPPORTED;
    }

    @Override
    public String toString() {
        return (declared ? "declared" : "measured") + verdicts;
    }
}
