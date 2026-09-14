package com.graduation.generator.adapter;

/**
 * 商城状态词表（F-25 的<b>适配器侧</b>可选窄接口）：只有"拥有一张自己的状态词映射表"的适配器
 * 才有资格谈"哪些商城原词没被映射"。
 *
 * <h2>本接口不再被引擎使用（修复轮记录）</h2>
 * <p>引擎曾经用 {@code adapter instanceof MallStatusVocabulary} 判断"能不能问出原词"——
 * 那是一条<b>旁路</b>：引擎一旦按适配器类型分支，就说明它知道"哪家商城有词表"，
 * 于是"换一家商城"要改引擎，而不是只加一个适配器。</p>
 *
 * <p>现在事实的载体是 {@code ProductPage}：<b>适配器在读取目录时</b>把本次读取的两个缺口清单
 * （{@code unmappedStateWords} / {@code stateFieldMissing}）随返回值带走，引擎只读页、
 * 不按类型分支、不认识任何一家商城的词表。本接口因此<b>退役</b>：
 * 新适配器要报缺口，请把清单放进 {@code ProductPage}，不要实现本接口。</p>
 *
 * <p>不报缺口的适配器不会因此失去缺口报告：引擎照样按 {@code status() == null} 报
 * "K 件读不懂状态词"（那不需要懂任何词表）。</p>
 *
 * @deprecated 缺口已随 {@code ProductPage} 返回，引擎不再经本接口取原词；
 *             保留此类型仅为记录"词表归适配器"这条分工，新实现请勿使用。
 */
@Deprecated
public interface MallStatusVocabulary {

    /**
     * 最近一次目录读取里出现过、且<b>没能映射到规范状态词表</b>的商城原词（去重，顺序不限）。
     *
     * <p>约定：这些原词<b>绝不</b>出现在 {@code ExternalProduct#status()} 里——那个字段只装规范词
     * （{@code on_sale}/{@code off_sale}/{@code pending}），或装 {@code null} 表示"映射不到"。
     * 把商城原词塞进规范字段会在下游撞上契约枚举校验（F-25 的原始症状）。</p>
     */
    java.util.List<String> unmappedStatusWords();
}
