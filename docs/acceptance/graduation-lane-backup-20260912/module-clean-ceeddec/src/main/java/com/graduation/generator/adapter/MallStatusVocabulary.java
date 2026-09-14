package com.graduation.generator.adapter;

/**
 * 商城状态词表（F-25 的可选能力面）：<b>拥有词表的适配器</b>才能报出"哪些商城原词没被映射"。
 *
 * <p>为什么单独立一个接口，而不是加到 {@link MallTargetAdapter}：</p>
 * <ul>
 *   <li>词表是<b>某一家商城独有</b>的知识（参考商城的 {@code on_sale}、第二家的 {@code SALE}），
 *       不是"每家适配器都必须回答"的问题——把它塞进 SPI 会逼着所有实现（含文件模式）回答一个
 *       与它们无关的问题；</li>
 *   <li>而"缺口要能看见"是引擎的义务：引擎按 {@link ExternalProduct#status()} 为 {@code null}
 *       统计件数（这不需要懂任何词表），再通过本接口问一句"原词是什么"。
 *       于是分工是干净的——<b>映射与词表归适配器，计数与报缺口归引擎</b>。</li>
 * </ul>
 *
 * <p>不实现本接口的适配器不会因此失去缺口报告：引擎照样报"K 件读不懂状态词"，只是给不出原词。</p>
 */
public interface MallStatusVocabulary {

    /**
     * 最近一次目录读取里出现过、且<b>没能映射到规范状态词表</b>的商城原词（去重，顺序不限）。
     *
     * <p>约定：这些原词<b>绝不</b>出现在 {@link ExternalProduct#status()} 里——那个字段只装规范词
     * （{@code on_sale}/{@code off_sale}/{@code pending}），或装 {@code null} 表示"映射不到"。
     * 把商城原词塞进规范字段会在下游撞上契约枚举校验（F-25 的原始症状）。</p>
     */
    java.util.List<String> unmappedStatusWords();
}
