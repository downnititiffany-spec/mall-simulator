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
 * @param status     <b>规范</b>状态词（{@code on_sale}/{@code off_sale}/{@code pending}，
 *                   即 {@code ContractEnums.PRODUCT_STATUS}）；{@code null} 的语义被写死为
 *                   <b>"商城的词无法映射到规范词表"</b>（含商城未给该字段）。
 *                   <p>两条禁令（F-25，硬约束 3）：<b>(1)</b> 绝不把商城原词（参考商城的 {@code PAID}、
 *                   第二家的 {@code SALE}）放进这个字段——规范事件契约对 {@code status} 是
 *                   required + 枚举，写原词就是产出违约数据；<b>(2)</b> 映射不到时不许静默丢弃，
 *                   适配器要用 {@link MallStatusVocabulary#unmappedStatusWords()} 把原词报出来，
 *                   由引擎记成"目录缺口（K 件被排除）"。</p>
 *                   <p>由此得到的读法：{@code status == null} ⇒ 该商品既不能算"在售"、也不能算"下架"，
 *                   只能被排除在可用目录之外，并且必须留下可见的缺口记录。</p>
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
