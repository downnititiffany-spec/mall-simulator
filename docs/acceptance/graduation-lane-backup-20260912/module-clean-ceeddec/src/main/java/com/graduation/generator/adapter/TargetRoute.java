package com.graduation.generator.adapter;

/**
 * 一次操作的 HTTP 方法与路径模板（指导书 §4.1.1.1 的契约记录，逐字对齐）。
 *
 * <p><b>为什么需要这个类型</b>（硬约束 6）：运行流水里的 {@code http_method}/{@code route} 必须是
 * <b>这一次真实发出去的请求形状</b>，而"哪台商城用什么方法打什么路径"只有适配器知道。
 * 以前引擎内置了参考商城的字面量（{@code GET /api/v1/mall/products}），于是接第二家商城时流水会记成
 * 别人的路由——那是"看起来有证据、实际是编的"。有了本类型，
 * {@link MallTargetAdapter#operationRoutes(TargetConfig)} 把路由的<b>所有权</b>交回适配器，
 * 引擎只负责如实记录。</p>
 *
 * <p><b>路径模板</b>：动态段写成 {@code {orderId}} / {@code {refundId}} 这类占位（例如
 * {@code /open/v2/orders/{orderId}/settle}），<b>不含</b> {@code base_url} 与凭据，也<b>不含</b>
 * 查询串（一次读操作的 {@code ?categoryId=} 这类可选参数不属于路径模板）。</p>
 *
 * @param method HTTP 方法（如 {@code GET}/{@code POST}）
 * @param path   路径模板（以 {@code /} 开头）
 */
public record TargetRoute(String method, String path) {

    public TargetRoute {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method 必填（适配器必须说清它真实使用的方法）");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 必填（适配器必须说清它真实使用的路径）");
        }
    }
}
