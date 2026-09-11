package com.graduation.analytics.source;

import com.graduation.analytics.source.dto.SourceChangeOutcome;
import com.graduation.analytics.source.dto.SourceCheckResult;
import com.graduation.analytics.source.dto.SourceRegistryCreateReq;
import com.graduation.analytics.source.dto.SourceRegistryUpdateReq;
import com.graduation.analytics.source.dto.SourceRegistryView;

import java.util.List;
import java.util.Optional;

/**
 * 源登记服务（P1-03）。控制器**只能**经本接口访问数据，不得直接注入 mapper。
 *
 * <p>失败码（唯一所有者 = {@code PlatformBizException}）：</p>
 * <ul>
 *   <li>{@code SOURCE_NOT_FOUND} — 目标 id 不存在（404）</li>
 *   <li>{@code SOURCE_CODE_IMMUTABLE} — 试图修改 {@code source_code}（409）</li>
 *   <li>{@code SOURCE_PROFILE_INVALID} — 画像文件缺失/非法/与登记不一致（409）</li>
 *   <li>{@code SOURCE_IN_USE} — 该源是当前激活源，不能暂停（409）</li>
 * </ul>
 * 其余参数类错误用既有的 {@code PARAM_INVALID}（400），不新造错误码。
 */
public interface SourceRegistryService {

    List<SourceRegistryView> list();

    SourceRegistryView get(Long id);

    /** 新建：status 缺省 DRAFT（或显式 PAUSED），不接受 ACTIVE */
    SourceChangeOutcome create(SourceRegistryCreateReq req);

    /** 修改：source_code 不可改；不改状态；{@code null} 字段保持原值 */
    SourceChangeOutcome update(Long id, SourceRegistryUpdateReq req);

    /** 只读校验：不改状态、不落审计 */
    SourceCheckResult test(Long id);

    /**
     * 设为当前源（唯一带画像校验的入口，单事务）：
     * ① 画像校验（不通过 → {@code SOURCE_PROFILE_INVALID}）→ ② 源置 ACTIVE →
     * ③ 绑定唯一 ACTIVE {@code runtime_profile.source_id}。
     * 已是当前源时幂等成功（{@code changed=false}，不重复写绑定审计）。
     */
    SourceChangeOutcome activate(Long id);

    /** 暂停源；当前源不可暂停（{@code SOURCE_IN_USE}），须先切换当前源 */
    SourceChangeOutcome pause(Long id);

    /** 当前激活源 id（无 ACTIVE 运行环境或未绑定时为空）。读路径派生 DTO 的 {@code current} 用 */
    Optional<Long> currentSourceId();
}
