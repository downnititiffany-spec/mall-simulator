package com.graduation.analytics.source.dto;

import java.util.List;

/**
 * 源校验明细（{@code POST /api/v1/sources/{id}/test} 的返回，P1-03）。
 *
 * <p><b>只读</b>：本接口不改任何状态、不落审计（D-035 裁决 8）。它回答的是
 * 「这个源现在能不能被激活」，而不是「外部商城连不连得通」——后者属生成器侧的
 * 目标适配器职责（P3 交付物），平台不越界探测外部系统。</p>
 *
 * <p><b>{@code ok} 的口径（刻意收窄，避免"校验说行、激活却失败"）</b>：
 * 它等于 {@code activate} 在**该源自身**这一侧的三个失败面是否全部通过——
 * ① {@code profile_path} 合规；② 画像文件存在、是 JSON 对象、其 {@code sourceCode}/
 * {@code profileVersion} 与登记一致、设计 §4.2 的顶层键齐全；③ 当前状态允许生命周期变更。
 * 它**不**覆盖两类非源自身的情况，调用方不应把 {@code ok=true} 理解为"激活一定成功"：</p>
 * <ul>
 *   <li>运行环境侧：库里没有 ACTIVE 的 {@code runtime_profile} 行（此时 activate 报
 *       {@code PARAM_INVALID}）——那是环境级问题，与"这个源行不行"无关；</li>
 *   <li>并发竞争：校验与激活之间的状态变化由 {@code activate} 自己在锁内重新判定。</li>
 * </ul>
 * <p>摄取侧的可达性（landing 目录、外部系统连通性）**不在**本接口口径内：{@code activate}
 * 不要求它，且它属 P1-05/P3 的摄取与目标适配职责。</p>
 *
 * @param ok    所有 {@code applicable=true} 的检查项是否全通过（见上方口径说明）
 * @param items 逐项明细；{@code applicable=false} 表示前置条件不成立、该项无法评估（不阻断，但如实标注）
 */
public record SourceCheckResult(Long sourceId, String sourceCode, boolean ok, List<CheckItem> items) {

    /**
     * 检查项：{@code detail} 只说人话，且只回显**仓库相对路径**，不含绝对本机路径。
     *
     * <p>不变量：{@code applicable=false} ⇒ {@code passed=false}（"没评估"不等于"通过"）。</p>
     */
    public record CheckItem(String name, boolean passed, boolean applicable, String detail) {

        public CheckItem(String name, boolean passed, String detail) {
            this(name, passed, true, detail);
        }
    }
}
