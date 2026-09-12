package com.graduation.generator.adapter;

import java.util.List;

/**
 * 商品目录查询条件（§4.1 {@code listProducts(ProductQuery)} 的入参；指导书未定义其字段，此处为最小设计）。
 *
 * <p><b>为什么带分页而参考商城不带</b>：参考商城的 {@code GET /api/v1/mall/products} 只接受
 * {@code categoryId}，没有分页参数（实测 2026-09-11）。因此 {@code offset}/{@code limit} 由适配器
 * <b>客户端侧</b>施加在返回列表上，语义是"取该分类下的第 offset 条起的 limit 条"，不是服务端分页。
 * 这一点在 README 的"指导书未定义处的最小设计"里登记，不冒充服务端能力。</p>
 *
 * @param categoryId 只取该分类；null 表示不筛分类
 * @param keyword    名称关键字，大小写不敏感；null 表示不筛关键字（参考商城无此参数，客户端侧过滤）
 * @param offset     偏移，≥0
 * @param limit      单页条数，1..500；保证内存与请求量有界
 */
public record ProductQuery(Long categoryId, String keyword, int offset, int limit) {

    /** 单页上限：一次拉全表会让生成器的内存与后续调用失去上界 */
    public static final int MAX_LIMIT = 500;

    public ProductQuery {
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不得为负：" + offset);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit 必须在 1.." + MAX_LIMIT + " 之间：" + limit);
        }
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    public static ProductQuery firstPage(int limit) {
        return new ProductQuery(null, null, 0, limit);
    }

    /** 客户端侧施加 offset/limit（参考商城无分页参数，见类注释） */
    public <T> List<T> window(List<T> all) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        return List.copyOf(all.subList(from, to));
    }
}
