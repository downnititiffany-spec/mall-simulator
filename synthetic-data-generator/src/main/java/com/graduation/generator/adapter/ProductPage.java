package com.graduation.generator.adapter;

import java.util.List;

/**
 * 商品目录的一页（§4.1 {@code listProducts} 的返回类型；指导书未定义字段，此处为最小设计）。
 *
 * <p><b>缺口随页返回，不留在适配器实例上</b>（硬约束 2/9）：适配器是单例（见
 * {@code config/GeneratorBeans}），凡是"本次目录读取"的事实都必须挂在这一页上随调用返回；
 * 放进适配器的字段就会跨运行、跨目标累计，让 B 商城这次的报告里出现 A 商城上次见过的词。
 * 引擎侧因此也只读本页，不需要认识任何一家商城的词表，更不需要按适配器类型分支。</p>
 *
 * <p><b>三个缺口通道是并列的</b>（指导书 V2.4 §4.1.1.3 第 245 行）：{@code unmappedStateWords}
 * ／{@code stateFieldMissing}／{@code priceUnreadable} 各装一类"这次读取里读不懂或没给"的事实，
 * <b>三个都必须被引擎报成缺口行</b>。只报其中一部分，被排除的商品就会在页、流水与运行报告里
 * 一起消失（H2：{@code priceUnreadable} 曾经"有收集、无出口"——只在 debug 日志里留痕）。
 * 三个清单一律<b>去重、按字典序</b>，这样同一批应答在任何一次运行里都得到同一串顺序。</p>
 *
 * @param products          本页商品（可能为空——"这一页没有商品"是<b>实测结果</b>，不是错误）
 * @param total             施加分页前适配器看到的候选总数（用于运行报告说明"分页截断"是否发生）。
 *                          它是<b>读取侧</b>的事实：做了关键字过滤的分支也必须原样带过去，
 *                          不许改成过滤后的件数，否则同一字段在两条分支下有两种口径。
 * @param unmappedStateWords 本次读取里出现过、但映射不到规范状态词表的<b>商城原词</b>
 *                           （去重、按字典序；与 {@link ExternalProduct#status()} 为 {@code null}
 *                           的件数对应）。约定两条：<b>(1)</b> 只装商城真的返回过的值——字段缺失
 *                           不是"商城给了一个词"，见 {@code stateFieldMissing}；<b>(2)</b> 装的是
 *                           原词，便于运维知道该在适配器映射表里补哪一行。
 * @param stateFieldMissing 本次读取里<b>商城没给状态字段（或为空）</b>的商品 ID（去重、按字典序）。
 *                           刻意与 {@code unmappedStateWords} 分开：两者的修法不同（一个补商城侧数据、
 *                           一个补适配器映射表），混进同一个集合会让报告像是在说"商城返回过这个词"。
 * @param priceUnreadable   本次读取里<b>报价读不懂</b>（商城没给 {@code unit_price_cents}，或给的不是
 *                           非负整数分）而被排除的商品 ID（去重、按字典序）。它与
 *                           {@code stateFieldMissing} 同理而<b>不能合并</b>：状态缺口是"这件商品进不了
 *                           可用目录"，报价缺口是"这件商品的金额口径不成立"，两者的运维动作也不同
 *                           （补映射表/补商城字段 vs 查商城计价口径）。
 */
public record ProductPage(List<ExternalProduct> products, int total,
                          List<String> unmappedStateWords, List<String> stateFieldMissing,
                          List<String> priceUnreadable) {

    public ProductPage {
        products = products == null ? List.of() : List.copyOf(products);
        if (total < products.size()) {
            total = products.size();
        }
        unmappedStateWords = unmappedStateWords == null ? List.of() : List.copyOf(unmappedStateWords);
        stateFieldMissing = stateFieldMissing == null ? List.of() : List.copyOf(stateFieldMissing);
        priceUnreadable = priceUnreadable == null ? List.of() : List.copyOf(priceUnreadable);
    }

    /**
     * 不带缺口信息的页："这一页没有读不懂的状态词，也没有读不懂的报价"
     * （参考商城的词表就是规范词表，F-25 无缺口）。
     *
     * <p>三个缺口清单为空是<b>如实声明</b>，不是"没算"：有缺口的适配器必须用五参构造器把原词/ID 带上。</p>
     */
    public ProductPage(List<ExternalProduct> products, int total) {
        this(products, total, List.of(), List.of(), List.of());
    }

    public static ProductPage empty() {
        return new ProductPage(List.of(), 0, List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return products.isEmpty();
    }

    /**
     * 本次读取有没有任何缺口（三个通道任一非空）——"适配器自己登记了缺口"这一事实的判据。
     *
     * <p>注意它<b>不是</b>引擎"要不要记缺口行"的唯一依据：引擎还会按
     * {@link ExternalProduct#status()} 为 {@code null} 的件数独立计数（适配器漏登记通道时也必须报出来），
     * 见 {@code engine/MallApiGenerationEngine#describeCatalogGaps}。</p>
     */
    public boolean hasGap() {
        return !unmappedStateWords.isEmpty() || !stateFieldMissing.isEmpty() || !priceUnreadable.isEmpty();
    }
}
