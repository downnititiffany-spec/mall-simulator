package com.graduation.analytics.mapping.dryrun;

import com.graduation.analytics.mapping.MappingProfile;
import com.graduation.analytics.mapping.MappingReason;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 映射 dry-run 报告（设计 §7.3 规则 10 / §7.4）。**纯数据、无副作用**：报告只由输入（候选画像原文 +
 * 受控样本 + limit）与冻结的执行器事实推导而来。
 *
 * <h2>事实来源（不新造第二份真相）</h2>
 * <ul>
 *   <li>画像事实：{@link MappingProfile}（{@code profileVersion/contractVersion/syntax/profileChecksum/
 *       activationBlocks/capabilityGaps}）。</li>
 *   <li>执行事实：逐行调用 {@code MappingExecutor} 得到的 {@code MappingOutcome}（violations/warnings/
 *       stats/canonicalChecksum）——本报告**不重算**任何一条映射语义。</li>
 * </ul>
 *
 * <h2>聚合口径</h2>
 * <ul>
 *   <li>{@code processedCount} = 实际执行的非空样本行数 = {@code min(sampleLineCount, requestedLimit)}；
 *       {@code acceptedCount + quarantinedCount + systemErrors.size()} = {@code processedCount}
 *       （系统异常既不冒充坏数据、也不冒充成功，故单独一列，规则 13）。</li>
 *   <li>{@code requiredCoverage} = Σ成功必填位置 / Σ总必填位置；{@code enumCoverage} = Σ已解决对 / Σ观察到对。
 *       分母为 0 ⇒ {@code null}（不写 0.0，与 {@code MappingStats} 同口径）。</li>
 *   <li>{@code enumCoverage} 用合计比而不是跨行去重比：对固定画像，一对 (字段路径, 原始值) 的解析结果是
 *       恒定的（要么恒解决、要么恒未解决），所以「合计比」与规则 13 的「按字段路径 + 原始枚举去重比」
 *       **数值恒等**；执行器内部本来也按 (path, 原始值) 去重，本报告不去跨行重算。</li>
 *   <li>{@code reasonCounts}/{@code warningCounts} 只记非零项（与 {@code MappingStats.reasonCounts} 同口径：
 *       按**违例条数**计，可大于隔离行数）。</li>
 * </ul>
 *
 * <h2>checksum 口径</h2>
 * <ul>
 *   <li>{@code profileChecksum} = 候选画像**原文的 sha256**（{@code MappingHash.sha256Hex(profileText)}），
 *       即装载器使用的同一算法（S2-01A 冻结）；装载失败时照样给出，用于标识"被拒的那一版"。</li>
 *   <li>{@code contractChecksum} = 平台契约文件字节的 sha256；{@code contractVersion} 取自契约本身。
 *       候选画像声明的 {@code contractVersion} 由装载器比对，不一致即 {@code PROFILE_INVALID}
 *       （见 {@code profileIssues}）。</li>
 *   <li>{@code sampleChecksum} = 样本文件字节的 sha256；{@code acceptedCanonicalChecksums} = 逐行
 *       canonical 的 sha256（执行器产出，按样本顺序）。</li>
 * </ul>
 *
 * <h2>可激活判据（fail-closed）</h2>
 * <p>{@code activationEligible} = 画像装载成功 且 {@code processedCount>0} 且 无 violation 且 无系统异常
 * 且 {@code activationBlocks} 空 且 {@code capabilityGaps} 空。**本轮不存在"无害缺口"白名单**：任何 capabilityGap
 * 都按"影响正确性"处理（例如 {@code identityPolicy}/{@code quarantinePolicy} 未实现、v1 的 {@code checksum}
 * 兼容元数据、未使用的金额单位声明之外的缺口）。将来若确证某缺口无害，必须在激活阶段显式登记白名单并附证据，
 * 不允许在这里悄悄放行。{@code activationIneligibleReasons} 逐条列出原因，便于对账。</p>
 * <p>{@code processedCount>0} 这条是"零事实 ≠ 无罪"：空样本/全空行不会有违例，若不额外要求"至少处理过一行"，
 * 一份什么都没证明的报告就会被判"可激活"。宁可对空样本 fail-closed。</p>
 *
 * <h2>边界</h2>
 * <p>{@code profileAccepted=false} 时：{@code processedCount=0}、不执行任何样本行、{@code profileIssues}
 * 给出全部装载原因（fail-closed 展示，而不是抛出 400 把多个原因压成一句话）。</p>
 * <p>预览 {@code preview} 至多 {@code PREVIEW_MAX} 条；全量事实在 {@code processedCount} 与
 * {@code acceptedCanonicalChecksums} 里，不靠预览推断。</p>
 */
public record MappingDryRunReport(
        String reportId,
        String sourceId,
        LocalDateTime createdAt,

        String profileVersion,
        MappingProfile.ProfileSyntax profileSyntax,
        String profileChecksum,
        String contractVersion,
        String contractChecksum,
        boolean profileAccepted,
        List<MappingDryRunIssue> profileIssues,

        String sampleRef,
        String sampleChecksum,
        int sampleLineCount,
        int requestedLimit,

        int processedCount,
        int acceptedCount,
        int quarantinedCount,
        List<String> acceptedCanonicalChecksums,
        Double requiredCoverage,
        int requiredPositionsTotal,
        int requiredPositionsOk,
        Double enumCoverage,
        int enumPairsObserved,
        int enumPairsResolved,
        int amountRoundedCount,
        Map<MappingReason, Integer> reasonCounts,
        Map<MappingReason, Integer> warningCounts,
        List<MappingDryRunIssue> violations,
        List<MappingDryRunIssue> warnings,
        List<String> systemErrors,
        List<MappingDryRunPreview> preview,

        List<String> activationBlocks,
        List<String> capabilityGaps,
        List<String> unusedDeclarations,
        List<String> activationIneligibleReasons,
        boolean activationEligible) {

    public MappingDryRunReport {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(createdAt, "createdAt");
        profileIssues = List.copyOf(profileIssues);
        acceptedCanonicalChecksums = List.copyOf(acceptedCanonicalChecksums);
        reasonCounts = Collections.unmodifiableMap(new TreeMap<>(reasonCounts));
        warningCounts = Collections.unmodifiableMap(new TreeMap<>(warningCounts));
        violations = List.copyOf(violations);
        warnings = List.copyOf(warnings);
        systemErrors = List.copyOf(systemErrors);
        preview = List.copyOf(preview);
        activationBlocks = List.copyOf(activationBlocks);
        capabilityGaps = List.copyOf(capabilityGaps);
        unusedDeclarations = List.copyOf(unusedDeclarations);
        activationIneligibleReasons = List.copyOf(activationIneligibleReasons);
    }
}
