package com.graduation.generator.adapter;

import java.math.BigDecimal;

/**
 * 商城侧的退款单（§4.1 {@code refund} 的返回类型；指导书未定义字段，此处为最小设计）。
 *
 * @param refundId 商城侧退款单 ID
 * @param orderId  归属订单 ID
 * @param status   商城侧状态原文（参考商城为 {@code COMPLETED}/{@code PENDING} 等，逐字符透出）。
 *                 <b>商城没给状态字段（或给了空白）时是 {@code null}</b>：它是"商城没给"的标记，
 *                 <b>不是商城原词、不得当作状态使用</b>；这里永远不会出现生成器自己编的状态词
 *                 （曾经的 UNKNOWN 就是这么一个词——它会把"读不到"伪装成一个具体的
 *                 商城状态，让"商城说的是它"与"生成器猜的它"在同一个字段里再也分不开）。
 *                 需要非空状态才能继续的调用方必须<b>自己响亮失败</b>，不许拿 {@code null} 当状态、
 *                 也不许替换成占位词
 * @param amount   退款金额（元）；商城未回传时为 null
 */
public record ExternalRefund(String refundId, String orderId, String status, BigDecimal amount) {

    public ExternalRefund {
        if (refundId == null || refundId.isBlank()) {
            throw new IllegalArgumentException("refundId 必填（商城侧唯一键）");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 必填（退款必须能归到订单）");
        }
        // 空白状态词 = 商城没给（不是"商城给了一个空状态词"）：落成 null 这一个"未给"语义，
        // 而不是落成某个词。改这一行的方向只能是"更少地造值"，不允许再出现任何编出来的词。
        if (status != null && status.isBlank()) {
            status = null;
        }
    }

    /**
     * 商城原文是否等于它自己的 {@code COMPLETED}。
     *
     * <p>只对<b>商城原词</b>成立：商城没给状态（{@code status == null}）或给的是别的词时都是
     * {@code false}——<b>不要把 {@code false} 读成"商城说它没完成"</b>，两件事在这里没有区分手段。</p>
     */
    public boolean completed() {
        return "COMPLETED".equals(status);
    }
}
