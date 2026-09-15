package com.graduation.analytics.mapping.activation;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.mapping.ContractSnapshot;
import com.graduation.analytics.mapping.MappingHash;
import com.graduation.analytics.mapping.dryrun.MappingDryRunReport;
import com.graduation.analytics.mapping.dryrun.MappingDryRunReportRepository;
import com.graduation.analytics.source.SourceProfileFile;
import com.graduation.analytics.source.SourceRegistryService;
import com.graduation.analytics.source.dto.SourceRegistryView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 映射激活生命周期（S2-03）：把一张**已通过 dry-run 的报告**变成一个**已激活的映射指针**。
 *
 * <p><b>核心不变式（设计 §7.3 规则 14 / §7.4）</b>：预览的内容必须就是激活的内容。
 * 判据是**字节级哈希相等**，不是"再跑一遍看着差不多"：
 * ①{@code expectedProfileChecksum} 必须等于报告里钉住的 {@code profileChecksum}（调用方没在预览之后换画像）；
 * ②磁盘上待激活画像的字节 sha256 必须等于同一个值（预览之后画像文件没被改过）；
 * ③当前契约文件字节 sha256 必须等于 dry-run 时的 {@code contractChecksum}（契约没被改过）。
 * 三条任一不成立即 409，**绝不"重新执行一遍然后认为差不多一样"**——重跑会引入第二个判据来源。</p>
 *
 * <p><b>只读前置的失败顺序</b>：源不存在(404) → 报告不存在或不属于该源(404，不泄露归属) →
 * 报告不可激活(409) → checksum 不一致(409) → 契约漂移(409) → 画像字节变化(409)，
 * **全部检查跑完才开始写**（与 {@code SourceRegistryServiceImpl} 的"先判后写"同构），
 * 因此不存在"写了一半才发现不合格"的中间态。</p>
 *
 * <p><b>为什么不重新装载画像</b>：装载结论属于 dry-run 报告的事实，而"磁盘字节 == 报告字节"已由
 * 哈希相等证明，重装只会引入第二个判据（且两次装载若因契约漂移而不一致，会变成谁也说不清）。
 * 画像自声明的 {@code sourceCode} 与登记源是否一致由采集侧 {@code SourceMapper} 判定
 * （不一致即 {@code MAPPING_PROFILE_INVALID}），激活侧不重复判定——这一点已登记为已知边界。</p>
 *
 * <p><b>审计不在本类</b>：真实变更的 {@code operation_audit_log} 行由控制器层写（与
 * {@code SourceRegistryController} 同约定：审计写在控制器、失败也要留 FAILED 行）。
 * 本类返回 {@link MappingActivationOutcome}，把 before/after 事实交给调用方落审计。</p>
 *
 * <p><b>事务</b>：现在只有一次写入（等价单语句原子），{@code @Transactional} 是为落库实现准备的门槛——
 * 届时 {@code find + save} 必须落在同一事务里。L0 测试无事务管理器时代理不存在、注解不生效，
 * 与既有 {@code SourceRegistryServiceImpl} 的证据口径一致。</p>
 */
@Service
public class MappingActivationService {

    /** sha256 十六进制（小写；与 {@code MappingHash} 同一形状）。 */
    private static final Pattern CHECKSUM = Pattern.compile("^[0-9a-fA-F]{64}$");

    /** 路径上的源 id：数字登记 id，允许 `src-` 前缀（见 {@link #requireSourceId}）。 */
    private static final Pattern SOURCE_ID = Pattern.compile("^(?:src-)?(\\d{1,18})$");

    private final MappingDryRunReportRepository reports;
    private final SourceRegistryService sources;
    private final ActiveMappingPointerStore pointers;
    private final EventClock clock;
    private final Path profileRoot;
    private final Path contractPath;

    public MappingActivationService(MappingDryRunReportRepository reports,
                                    SourceRegistryService sources,
                                    ActiveMappingPointerStore pointers,
                                    EventClock clock,
                                    @Value("${platform.source.profile-root:.}") String profileRoot,
                                    @Value("${platform.mapping.contract-path:contract-specs/schemas/canonical-event.v1.schema.json}")
                                    String contractPath) {
        this.reports = reports;
        this.sources = sources;
        this.pointers = pointers;
        this.clock = clock;
        this.profileRoot = Path.of(profileRoot).toAbsolutePath().normalize();
        this.contractPath = Path.of(contractPath).toAbsolutePath().normalize();
    }

    /**
     * 激活一个已通过预览的映射画像。
     *
     * @param sourceIdToken           路径上的源 id（字符串；非数字/不存在一律按"源不存在"处理，不回落）
     * @param reportId                授权本次激活的 dry-run 报告 id
     * @param expectedProfileChecksum 调用方手上的画像哈希（必须等于报告里钉住的那个）
     * @param operator                操作者（取自登录会话，不接受请求头自报）
     */
    @Transactional
    public MappingActivationOutcome activate(String sourceIdToken, String reportId,
                                             String expectedProfileChecksum, String operator) {
        long sourceId = requireSourceId(sourceIdToken);
        String id = requireText(reportId, "reportId");
        String expected = requireChecksum(expectedProfileChecksum, "expectedProfileChecksum");
        requireText(operator, "operator");

        // ① 源必须在登记表里（不存在就是不存在，不回落任何默认源）
        SourceRegistryView source = sources.get(sourceId);

        // ② 报告必须存在且**归属该源**：归属不符按"不存在"处理，不泄露别的源有没有跑过预览
        MappingDryRunReport report = reports.find(id)
                .filter(candidate -> candidate.sourceId().equals(sourceIdToken.trim()))
                .orElseThrow(() -> new PlatformBizException(PlatformBizException.DRY_RUN_REPORT_NOT_FOUND,
                        "dry-run 报告不存在或不属于该源（reportId 与 path sourceId 必须同时匹配；"
                                + "报告只存在本进程内，重启即失效）"));

        // ③ 报告必须本身可激活（逐条判据都点名，便于调用方定位；不重跑预览）
        requireActivatable(report);

        // ④ 调用方手上的画像哈希必须就是被预览的那一份
        if (!report.profileChecksum().equals(expected)) {
            throw new PlatformBizException(PlatformBizException.MAPPING_ACTIVATION_CHECKSUM_MISMATCH,
                    "expectedProfileChecksum 与报告里的 profileChecksum 不一致："
                            + "激活只接受被预览过的那一份画像字节（报告的 profileChecksum="
                            + report.profileChecksum() + "）");
        }

        // ⑤ 契约不得在预览之后被动过（比对契约文件字节哈希：版本号相同内容也可能不同）
        ContractSnapshot current = ContractSnapshot.load(contractPath);
        if (!current.checksum().equals(report.contractChecksum())) {
            throw new PlatformBizException(PlatformBizException.MAPPING_CONTRACT_DRIFT,
                    "canonical 契约在 dry-run 之后已变化：报告 contractChecksum=" + report.contractChecksum()
                            + "，当前=" + current.checksum() + "；须重新预览后再激活");
        }

        // ⑥ 磁盘上待激活画像的字节必须仍是报告里那一份（"预览的内容必须就是激活的内容"）
        Path file = SourceProfileFile.resolve(profileRoot, source, "拒绝激活");
        String currentChecksum = MappingHash.sha256Hex(
                SourceProfileFile.readBytes(file, source.profilePath(), source.sourceCode(), "拒绝激活"));
        if (!currentChecksum.equals(report.profileChecksum())) {
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_CHANGED,
                    "待激活画像的字节与 dry-run 时不一致（当前=" + currentChecksum
                            + "，报告=" + report.profileChecksum() + "）；须重新预览后再激活");
        }

        ActiveMappingPointer candidate = new ActiveMappingPointer(sourceId, source.sourceCode(),
                source.profilePath(), report.profileVersion(), report.profileChecksum(),
                report.contractVersion(), report.contractChecksum(), id, clock.nowLdt(), operator);

        // ⑦ 幂等：同源 + 同画像字节 + 同契约字节 ⇒ 重复请求，不写第二个指针、不改激活时间与操作者
        ActiveMappingPointer existing = pointers.find(sourceId).orElse(null);
        if (candidate.sameContentAs(existing)) {
            return MappingActivationOutcome.idempotent(existing);
        }
        // ⑧ 不同内容 ⇒ 显式替换（一个源最多一个激活指针；被替换的 checksum 随结果返回，供审计留痕）
        pointers.save(candidate);
        return MappingActivationOutcome.of(candidate, existing);
    }

    /**
     * 报告侧"不可激活"的唯一判据集合（逐条点名，不用一句笼统的"报告不可用"）。
     *
     * <p>为什么逐条列出而不是只信 {@code activationEligible}：那是 dry-run 的汇总结论。
     * 一旦汇总规则演化或报告不自洽，激活侧就失去了自己的判断。这里把成功条件③④⑤⑥⑦⑧
     * （profileAccepted / violations / systemErrors / activationBlocks / capabilityGaps /
     * activationEligible）全部显式复核，任一不成立即 409。</p>
     */
    private static void requireActivatable(MappingDryRunReport report) {
        List<String> reasons = new ArrayList<>();
        if (!report.profileAccepted()) {
            reasons.add("profileAccepted=false（候选画像未通过装载，不能拿它跑真实采集）");
        }
        if (!report.violations().isEmpty()) {
            reasons.add("violations=" + report.violations().size());
        }
        if (!report.systemErrors().isEmpty()) {
            reasons.add("systemErrors=" + report.systemErrors().size());
        }
        if (!report.activationBlocks().isEmpty()) {
            reasons.add("activationBlocks=" + report.activationBlocks());
        }
        if (!report.capabilityGaps().isEmpty()) {
            reasons.add("capabilityGaps=" + report.capabilityGaps());
        }
        if (!report.activationEligible()) {
            reasons.add("activationEligible=false（dry-run 结论：" + report.activationIneligibleReasons() + "）");
        }
        if (!reasons.isEmpty()) {
            throw new PlatformBizException(PlatformBizException.MAPPING_ACTIVATION_INELIGIBLE,
                    "该 dry-run 报告不满足激活前置：" + String.join("；", reasons));
        }
    }

    /**
     * 路径上的源 id：非数字一律按"源不存在"处理（404），不做猜测、不回落任何默认源。
     *
     * <p>接受 {@code 7} 与 {@code src-7} 两种写法（登记表主键是数字，而 dry-run 侧
     * {@code sourceId} 是不透明 token，实测调用示例里出现过 {@code src-1} 这种形状）。
     * 两者都解析到同一个登记 id；但条件②"报告归属"仍按**字符串**比对路径 token，
     * 因此 dry-run 与 activate 必须用同一个 token 写法，不做隐式等价——那会让归属判据出现第二种解释。</p>
     */
    private static long requireSourceId(String sourceIdToken) {
        String token = sourceIdToken == null ? null : sourceIdToken.trim();
        Matcher matcher = token == null ? null : SOURCE_ID.matcher(token);
        try {
            if (matcher == null || !matcher.matches()) {
                throw new NumberFormatException("形状不匹配");
            }
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new PlatformBizException(PlatformBizException.SOURCE_NOT_FOUND,
                    "源不存在（sourceId 必须是登记表里的数字 id，可带 src- 前缀；值已脱敏，未回显）");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, field + " 必填");
        }
        return value.trim();
    }

    private static String requireChecksum(String value, String field) {
        String checksum = requireText(value, field);
        if (!CHECKSUM.matcher(checksum).matches()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    field + " 必须是 64 位十六进制 sha256（值已脱敏，未回显）");
        }
        return checksum;
    }
}
