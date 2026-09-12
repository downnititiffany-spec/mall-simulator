package com.graduation.generator.adapter;

import java.util.Map;

/**
 * 目标商城适配器（S4 的 SPI，逐字对齐指导书 §4.1）。
 *
 * <p><b>为什么要有这一层</b>：分析系统是工具，不能被写死到某一个模拟商城上（用户 2026-09-11 的固定指令），
 * 生成器同理——换一台商城应当是"新增一个 {@code MallTargetAdapter} 实现并注册"，而不是去改探测服务里的
 * {@code if} 分支。{@code adapter_type} 是这一层的唯一定位键（§4.2 {@code generator_target.adapter_type}）。</p>
 *
 * <p><b>实现的义务</b>：① 探测（{@link #test}）必须是对目标的真实调用，不能返回写死的能力表；
 * ② 探测只允许发安全方法（{@code OPTIONS}/{@code GET}），绝不为了探测而改目标商城的数据；
 * ③ 说不清的地方必须报 {@link CapabilityVerdict#UNDETERMINED}，不得猜测成"支持"；
 * ④ 业务调用（其余七项）只允许走商城<b>公开 REST 接口</b>——不直连商城库、不注入商城内部类、
 * 不绕过商城校验（§3.3 A 的硬约束）；⑤ 做不到的事必须<b>响亮失败</b>（{@link MallOperationException}），
 * 绝不静默跳过或返回伪造成功。</p>
 *
 * <p><b>方法名与返回类型逐字取自 §4.1</b>（L116–139）：{@code test/capabilities/listProducts/
 * createSyntheticUser/emitBehavior/createOrder/pay/cancel/refund} 九项。§4.1 未定义这些方法用到的方法
 * 返回的 DTO 字段，本包内的 DTO 是<b>最小设计</b>，已在验收 README 登记。</p>
 *
 * <p><b>为什么每次调用都带 {@link TargetConfig}</b>：§4.1 只写了 {@code listProducts(ProductQuery)} 这样的
 * 单参形态，没有 {@code TargetConfig} 形参。适配器的其它调用必须知道"调哪台商城、用哪份凭据引用"，
 * 而这两个值都只存在于 {@code TargetConfig}（§4.2 {@code generator_target}）。若把 {@code TargetConfig}
 * 塞进适配器实例，{@code MallTargetAdapterRegistry} 就得维护"每目标一个实例"的生命周期，
 * 那才是真正的重复所有者。故本实现让适配器保持<b>无状态单例</b>、把目标作为首个形参传入——
 * 这是对 §4.1 形参表的<b>最小必要偏离</b>，方法的<b>名字、语义与返回类型与 §4.1 完全一致</b>，
 * 已在验收 README 的"与 §4.1 的偏离"一节逐条登记。</p>
 */
public interface MallTargetAdapter {

    /** 本实现负责的 {@code adapter_type}（与 §4.2 的枚举字面量一致；比较时不区分大小写） */
    String adapterType();

    /** §4.1 {@code test}：对该目标做一次真实检查，返回能力判定与过程说明 */
    TargetCheckResult test(TargetConfig config);

    /**
     * §4.1 {@code capabilities}：这台适配器按当前配置<b>声明</b>支持哪些能力（不联网）。
     *
     * <p>与 {@link #test} 的区别是刻意的：声明用于"自动化运行前的快速判断"，实测用于"运行报告里的能力事实"。
     * 两者都不能把 {@code UNDETERMINED} 说成 {@code SUPPORTED}。</p>
     *
     * <p>默认实现是空声明（全部 {@code UNDETERMINED}）——"什么都没声明"就应当什么都不支持，
     * 而不是要求每个实现都必须写一张表。</p>
     */
    default TargetCapabilities capabilities(TargetConfig config) {
        return TargetCapabilities.none();
    }

    /**
     * §4.1.1 的 {@code operationRoutes}：本适配器在各操作上<b>真实使用</b>的 HTTP 方法与路径
     * （供运行流水如实记录）；无 HTTP 语义的操作不给条目。
     *
     * <p><b>键 = SPI 操作名</b>（§4.1.1.3 的七项，字面量与 {@code MallDispatchPlan.OP_*} 一致）：
     * {@code listProducts}/{@code createSyntheticUser}/{@code emitBehavior}/{@code createOrder}/
     * {@code pay}/{@code cancel}/{@code refund}。</p>
     *
     * <p><b>未声明即"该目标不支持该操作 / 路由未知"</b>：引擎在流水里写明确占位，<b>绝不</b>回落到
     * 任何一家商城的字面量（硬约束 6）。为什么默认是空表：{@code CANONICAL_EVENT_FILE} 这类目标
     * 没有 HTTP 语义，强迫它写一张表只会制造噪音；而"什么都没声明"必须读成"一条路由都没有"，
     * 不能读成"用参考商城那套"。</p>
     *
     * <p>本方法<b>不联网</b>、不读凭据值，只按 {@code config}（含 {@code config_json} 的路径声明）
     * 回答问题；适配器<b>不该</b>在本接口里写任何商城字面量——字面量属于各实现。</p>
     */
    default Map<String, TargetRoute> operationRoutes(TargetConfig config) {
        return Map.of();
    }

    /** §4.1 {@code listProducts}：读目标商城的商品目录 */
    default ProductPage listProducts(TargetConfig config, ProductQuery query) {
        throw unsupported("listProducts");
    }

    /** §4.1 {@code createSyntheticUser}：在目标商城建一个合成用户 */
    default ExternalUser createSyntheticUser(TargetConfig config, UserCommand command) {
        throw unsupported("createSyntheticUser");
    }

    /** §4.1 {@code emitBehavior}：经公开埋点接口产生一条行为事件（B-04：不得绕过商城校验） */
    default void emitBehavior(TargetConfig config, BehaviorCommand command) {
        throw unsupported("emitBehavior");
    }

    /** §4.1 {@code createOrder}：在目标商城下单 */
    default ExternalOrder createOrder(TargetConfig config, OrderCommand command) {
        throw unsupported("createOrder");
    }

    /** §4.1 {@code pay}：支付订单 */
    default ExternalOrder pay(TargetConfig config, PayCommand command) {
        throw unsupported("pay");
    }

    /** §4.1 {@code cancel}：取消订单 */
    default ExternalOrder cancel(TargetConfig config, CancelCommand command) {
        throw unsupported("cancel");
    }

    /** §4.1 {@code refund}：对订单发起退款 */
    default ExternalRefund refund(TargetConfig config, RefundCommand command) {
        throw unsupported("refund");
    }

    /**
     * 未实现能力的统一出口：<b>默认响亮失败</b>而不是编译期强制。
     *
     * <p>理由：{@code CANONICAL_EVENT_FILE} 这类目标本来就没有商城能力，强迫它的适配器写七个
     * {@code throw} 只会制造噪音；而"没实现却被调用"必须当场失败、且说清是哪个适配器不支持，
     * 不能静默变成空操作（那会产出"看起来成功、其实什么都没发生"的运行报告）。</p>
     */
    private MallOperationException unsupported(String operation) {
        return new MallOperationException(operation,
                "适配器 " + adapterType() + " 不支持该操作（§4.1 的 " + operation + " 未实现）");
    }
}
