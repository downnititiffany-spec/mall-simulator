package com.graduation.generator.adapter;

/**
 * 目标商城适配器（S4 的 SPI）。
 *
 * <p><b>为什么要有这一层</b>：分析系统是工具，不能被写死到某一个模拟商城上（用户 2026-09-11 的固定指令），
 * 生成器同理——换一台商城应当是"新增一个 {@code MallTargetAdapter} 实现并注册"，而不是去改探测服务里的
 * {@code if} 分支。{@code adapter_type} 是这一层的唯一定位键（§4.2 {@code generator_target.adapter_type}）。</p>
 *
 * <p><b>实现的义务</b>：① 探测必须是对目标的真实调用，不能返回写死的能力表；② 只允许发安全方法
 * （{@code OPTIONS}/{@code GET}），绝不为了探测而改目标商城的数据；③ 说不清的地方必须报
 * {@link CapabilityVerdict#UNDETERMINED}，不得猜测成"支持"。</p>
 *
 * <p><b>与指导书 §4.1 的关系（S4a 只落前两项）</b>：指导书把本接口写成
 * {@code test/capabilities/listProducts/createSyntheticUser/emitBehavior/createOrder/pay/cancel/refund} 九项。
 * S4a 只落"识别（{@link #adapterType()}）＋ 真实检查（{@link #test(TargetConfig)}）"两项，其余七项与
 * {@code capabilities()} 属 S4b（MALL_API 生成引擎）范围，届时<b>逐字对齐 §4.1 的方法名与语义</b>，
 * 不另起名字。这里刻意先把方法名定成 §4.1 的 {@code test}，避免以后为了改名而改一遍所有调用点。</p>
 */
public interface MallTargetAdapter {

    /** 本实现负责的 {@code adapter_type}（与 §4.2 的枚举字面量一致；比较时不区分大小写） */
    String adapterType();

    /** 对该目标做一次真实检查（§4.1 的 {@code test}），返回能力判定与过程说明 */
    TargetCheckResult test(TargetConfig config);
}
