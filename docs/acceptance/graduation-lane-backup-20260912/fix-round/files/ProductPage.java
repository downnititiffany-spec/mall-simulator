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
 * @param products          本页商品（可能为空——"这一页没有商品"是<b>实测结果</b>，不是错误）
 * @param total             施加分页前适配器看到的候选总数（用于运行报告说明"分页截断"是否发生）
 * @param unmappedStateWords 本次读取里出现过、但映射不到规范状态词表的<b>商城原词</b>
 *                           （去重、按字典序；与 {@link ExternalProduct#status()} 为 {@code null}
 *                           的件数对应）。约定两条：<b>(1)</b> 只装商城真的返回过的值——字段缺失
 *                           不是"商城给了一个词"，见 {@code stateFieldMissing}；<b>(2)</b> 装的是
 *                           原词，便于运维知道该在适配器映射表里补哪一行。
 * @param stateFieldMissing 本次读取里<b>商城没给状态字段（或为空）</b>的商品 ID（去重、按字典序）。
 *                           刻意与 {@code unmappedStateWords} 分开：两者的修法不同（一个补商城侧数据、
 *                           一个补适配器映射表），混进同一个集合会让报告像是在说"商城返回过这个词"。
 */
public record ProductPage(List<ExternalProduct> products, int total,
                          List<String> unmappedStateWords, List<String> stateFieldMissing) {

    public ProductPage {
        products = products == null ? List.of() : List.copyOf(products);
        if (total < products.size()) {
            total = products.size();
        }
        unmappedStateWords = unmappedStateWords == null ? List.of() : List.copyOf(unmappedStateWords);
        stateFieldMissing = stateFieldMissing == null ? List.of() : List.copyOf(stateFieldMissing);
    }

    /**
     * 不带缺口信息的页："这一页没有读不懂的状态词"（参考商城的词表就是规范词表，F-25 无缺口）。
     *
     * <p>两个缺口清单为空是<b>如实声明</b>，不是"没算"：有缺口的适配器必须用四参构造器把原词带上。</p>
     */
    public ProductPage(List<ExternalProduct> products, int total) {
        this(products, total, List.of(), List.of());
    }

    public static ProductPage empty() {
        return new ProductPage(List.of(), 0, List.of(), List.of());
    }

    public boolean isEmpty() {
        return products.isEmpty();
    }

    /** 本次读取有没有"读不懂/没给"的状态字段缺口——预检据此决定要不要记一行缺口 */
    public boolean hasStateVocabularyGap() {
        return !unmappedStateWords.isEmpty() || !stateFieldMissing.isEmpty();
    }
}
