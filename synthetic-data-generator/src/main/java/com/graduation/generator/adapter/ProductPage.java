package com.graduation.generator.adapter;

import java.util.List;

/**
 * 商品目录的一页（§4.1 {@code listProducts} 的返回类型；指导书未定义字段，此处为最小设计）。
 *
 * @param products 本页商品（可能为空——"这一页没有商品"是<b>实测结果</b>，不是错误）
 * @param total    施加分页前适配器看到的候选总数（用于运行报告说明"分页截断"是否发生）
 */
public record ProductPage(List<ExternalProduct> products, int total) {

    public ProductPage {
        products = products == null ? List.of() : List.copyOf(products);
        if (total < products.size()) {
            total = products.size();
        }
    }

    public static ProductPage empty() {
        return new ProductPage(List.of(), 0);
    }

    public boolean isEmpty() {
        return products.isEmpty();
    }
}
