package com.graduation.generator.adapter;

import java.math.BigDecimal;

/**
 * 目标商城的商品（§4.1 {@code listProducts} 的返回元素；指导书未定义字段，此处为最小设计）。
 *
 * <p>字段取自参考商城 {@code GET /api/v1/mall/products} 的<b>真实应答</b>
 * （{@code productId/productName/categoryId/brandId/price/status}，实测 2026-09-11），
 * 并按"生成器需要什么就取什么"裁剪：不需要成本价（{@code cost}）与品牌名，避免把商城内部口径搬进来。</p>
 *
 * <p>金额用 {@link BigDecimal}（不用 double）：下单与退款的金额要回传商城，浮点误差会变成
 * "金额对不上"的真实业务错误。</p>
 *
 * @param productId  商城侧商品 ID（雪花，十进制字符串）
 * @param name       商品名
 * @param categoryId 分类 ID，可为 null（商城未给时）
 * @param price      单价（元，两位小数）
 * @param status     商城侧状态原文（如 {@code on_sale}），保持原样不翻译，避免生成器自造口径
 */
public record ExternalProduct(String productId, String name, Long categoryId, BigDecimal price, String status) {

    public ExternalProduct {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId 必填（商城侧唯一键）");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("price 必须为非负金额：" + price);
        }
    }

    public boolean onSale() {
        return "on_sale".equals(status);
    }
}
