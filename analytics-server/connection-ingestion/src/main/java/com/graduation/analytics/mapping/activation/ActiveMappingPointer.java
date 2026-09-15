package com.graduation.analytics.mapping.activation;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 某个源**当前已激活**的映射画像（S2-03，设计 §7.4「激活指针」）。
 *
 * <p><b>它只描述事实，不含画像内容</b>：画像内容的所有者仍是文件（设计 §7.2：首版继续文件为不可变画像内容，
 * DB 只登记引用与版本）。指针钉住的是「哪一份字节」——{@link #profileChecksum} 是画像**原始字节**的 sha256
 * （与 {@code MappingProfile.profileChecksum} / dry-run 报告的 {@code profileChecksum} 同一个算法与同一份字节），
 * 因此"预览过的内容"与"运行使用的内容"是可对账的同一个值，而不是"看起来差不多"。</p>
 *
 * <p><b>为什么连 {@link #contractChecksum} 一起钉住</b>：映射语义是"画像 × 契约"的共同产物。
 * 只钉画像会让契约被改写后仍然"看起来是同一版激活"；两个 hash 都记下来，激活时的漂移才可判定（409）。</p>
 *
 * @param sourceId          登记源 id（{@code source_registry.id}）
 * @param sourceCode        登记源业务键（写进 manifest/ODS 的 {@code source_system}）
 * @param profilePath       画像文件仓库相对路径（定位依据；绝对路径绝不入指针，任务书 §6）
 * @param profileVersion    画像声明的版本（来自画像本身，非反推）
 * @param profileChecksum   画像原始字节的 sha256（激活时钉住；运行侧据此判"运行冻结同一版本"）
 * @param contractVersion   契约版本（取自契约文件）
 * @param contractChecksum  契约文件原始字节的 sha256
 * @param reportId          授权本次激活的 dry-run 报告 id（设计 §7.4「已通过预览引用」）
 * @param activatedAt       激活时间（取自注入的业务时间源 {@code EventClock}，不用系统当前时间）
 * @param activatedBy       操作者（取自登录会话；不接受请求头自报身份）
 */
public record ActiveMappingPointer(
        long sourceId,
        String sourceCode,
        String profilePath,
        String profileVersion,
        String profileChecksum,
        String contractVersion,
        String contractChecksum,
        String reportId,
        LocalDateTime activatedAt,
        String activatedBy) {

    public ActiveMappingPointer {
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(profilePath, "profilePath");
        Objects.requireNonNull(profileVersion, "profileVersion");
        Objects.requireNonNull(profileChecksum, "profileChecksum");
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(contractChecksum, "contractChecksum");
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(activatedBy, "activatedBy");
    }

    /** 同一份画像字节与同一份契约字节 ⇒ 这次激活是重复请求（幂等成功，不产生第二个指针）。 */
    public boolean sameContentAs(ActiveMappingPointer other) {
        return other != null
                && profileChecksum.equals(other.profileChecksum)
                && contractChecksum.equals(other.contractChecksum);
    }
}
