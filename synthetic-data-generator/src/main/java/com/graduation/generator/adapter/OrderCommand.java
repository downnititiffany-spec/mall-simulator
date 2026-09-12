package com.graduation.generator.adapter;

import java.util.List;

/**
 * 下单指令（§4.1 {@code createOrder(OrderCommand)} 的入参；指导书未定义字段，此处为最小设计）。
 *
 * <p>只允许"一个用户 + 若干 (商品, 数量)"：参考商城的 {@code POST /api/v1/mall/orders} 就是这个形状，
 * 而且没有优惠券/地址/运费等参数，不为此发明生成器侧字段。</p>
 */
public record OrderCommand(String userId, List<Item> items) {

    public OrderCommand {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 必填（订单必须归属到商城侧用户）");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items 不得为空（空订单会被商城拒绝，属于调用方的错）");
        }
        items = List.copyOf(items);
    }

    public static OrderCommand single(String userId, String productId, int quantity) {
        return new OrderCommand(userId, List.of(new Item(productId, quantity)));
    }

    public int totalQuantity() {
        return items.stream().mapToInt(Item::quantity).sum();
    }

    /** 一条明细 */
    public record Item(String productId, int quantity) {

        public Item {
            if (productId == null || productId.isBlank()) {
                throw new IllegalArgumentException("productId 必填");
            }
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity 必须 ≥1：" + quantity);
            }
        }
    }
}
