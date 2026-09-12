package com.graduation.generator.adapter;

import java.math.BigDecimal;

/**
 * 商城侧的订单（§4.1 {@code createOrder/pay/cancel} 的返回类型；指导书未定义字段，此处为最小设计）。
 *
 * <p>{@code status} 用<b>商城回传的原文</b>（如 {@code PAID}/{@code CANCELLED}），不由生成器翻译成
 * 自己的枚举：订单状态机归商城所有，生成器只是调用方，翻译一次就多一份要对齐的口径。</p>
 *
 * @param orderId     商城侧订单 ID
 * @param userId      商城侧用户 ID
 * @param status      商城侧状态原文（逐字符透出）。<b>商城没给状态字段（或给了空白）时是
 *                    {@code null}</b>：这个 {@code null} 是"商城没给"的标记，<b>不是商城原词、
 *                    不得当作状态使用</b>。这里永远不会出现生成器自己编的状态词
 *                    （曾经的 UNKNOWN 就是这么一个词）：编一个词会让"商城说的是它"
 *                    与"生成器猜的它"在同一个字段里长得一模一样，按状态聚合或对账时再也分不开，
 *                    还会把"读不到"伪装成一个具体的商城状态。需要非空状态才能继续的调用方，
 *                    必须<b>自己响亮失败</b>（见 {@code engine/MallApiDispatchSink} 对商品规范状态的
 *                    处理），不许把这里的 {@code null} 当状态用、更不许替换成占位词
 * @param totalAmount 订单总额（元）；商城未回传时为 null
 * @param itemCount   明细条数；商城未回传时为 -1
 */
public record ExternalOrder(String orderId, String userId, String status,
                            BigDecimal totalAmount, int itemCount) {

    /** 下单应答只有 {@code {"orderId": "..."}} 时的最小构造（实测：POST /orders 的应答只有 orderId） */
    public static ExternalOrder created(String orderId, String userId) {
        return new ExternalOrder(orderId, userId, "CREATED", null, -1);
    }

    public ExternalOrder {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 必填（商城侧唯一键）");
        }
        // 空白状态词 = 商城没给（不是"商城给了一个空状态词"）：落成 null 这一个"未给"语义，
        // 而不是落成某个词。改这一行的方向只能是"更少地造值"，不允许再出现任何编出来的词。
        if (status != null && status.isBlank()) {
            status = null;
        }
    }

    /**
     * 商城原文是否等于它自己的 {@code CANCELLED}。
     *
     * <p>只对<b>商城原词</b>成立：商城没给状态（{@code status == null}）或给的是别的词时都是
     * {@code false}——<b>不要把 {@code false} 读成"商城说它没取消"</b>，那两件事在这里没有区分手段。</p>
     */
    public boolean cancelled() {
        return "CANCELLED".equals(status);
    }

    /**
     * 商城原文是否等于它自己的 {@code PAID}。语义与 {@link #cancelled()} 同：{@code false} 不等于
     * "商城说它没支付"，也可能只是商城没给状态词。
     */
    public boolean paid() {
        return "PAID".equals(status);
    }
}
