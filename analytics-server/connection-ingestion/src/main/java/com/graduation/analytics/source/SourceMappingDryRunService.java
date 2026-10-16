package com.graduation.analytics.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.ingestion.EventContractValidator;
import com.graduation.analytics.mapper.DryRunEngine;
import com.graduation.analytics.mapper.DryRunReport;
import com.graduation.analytics.mapper.MappingSampleResolver;
import com.graduation.analytics.mapper.PureMapper;
import com.graduation.analytics.mapper.RepoPathLocator;
import com.graduation.analytics.mapper.SourceProfile;
import com.graduation.analytics.mapper.store.DryRunReportStore;
import com.graduation.analytics.mapper.store.StoredDryRunReport;
import com.graduation.analytics.source.dto.MappingDryRunReportView;
import com.graduation.analytics.source.dto.MappingDryRunReq;
import com.graduation.analytics.source.dto.SourceRegistryView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 映射 Dry-Run 服务（WP-2A-2 / Design V3.0 §7.3 规则 10、规则 14 / §7.4）。
 *
 * <p>核心职责：
 * <ul>
 *   <li>按 sourceId 解析源登记与不可变 SourceProfile；</li>
 *   <li>规则 14：严格校验请求中指定的 profileVersion / profileContentHash 与实际加载的画像一致性（防陈旧预览）；</li>
 *   <li>样本安全解析（仅限平台批准的白名单位置，严禁内联记录，上限 100 条）；</li>
 *   <li>执行纯计算 DryRunEngine，零持久化（不推进 checkpoint、不落 manifest、不改激活指针）；</li>
 *   <li>报告存入内存存储并返回；支持按 sourceId + reportId 只读对账查询。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class SourceMappingDryRunService {

    private final SourceRegistryService sourceService;
    private final DryRunReportStore reportStore;
    private final MappingSampleResolver sampleResolver;
    private final PureMapper pureMapper;
    private final EventContractValidator contractValidator;
    private final Path repoRoot;

    public SourceMappingDryRunService(SourceRegistryService sourceService,
                                      DryRunReportStore reportStore,
                                      MappingSampleResolver sampleResolver,
                                      PureMapper pureMapper,
                                      EventContractValidator contractValidator,
                                      @Value("${platform.source.profile-root:.}") String profileRoot) {
        this.sourceService = sourceService;
        this.reportStore = reportStore;
        this.sampleResolver = sampleResolver;
        this.pureMapper = pureMapper;
        this.contractValidator = contractValidator;
        this.repoRoot = RepoPathLocator.locateRepoRoot(profileRoot);
    }

    public SourceMappingDryRunService(SourceRegistryService sourceService,
                                      DryRunReportStore reportStore,
                                      MappingSampleResolver sampleResolver,
                                      PureMapper pureMapper,
                                      EventContractValidator contractValidator) {
        this(sourceService, reportStore, sampleResolver, pureMapper, contractValidator, ".");
    }

    /**
     * 执行 Dry-Run 试运行并生成报告。
     */
    public MappingDryRunReportView runDryRun(Long sourceId, MappingDryRunReq req) {
        if (req == null) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "请求体必填");
        }

        // 1. 明确拒绝内联记录（WP-2A-2 约束）
        if (req.hasInlineRecords()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "不接受内联记录（inline records），样本仅允许通过 sampleRef 从平台批准的白名单位置加载");
        }

        // 2. 必须提供 sampleRef
        if (req.sampleRef() == null || req.sampleRef().isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sampleRef 必填");
        }

        // 3. 校验源存在性（不存在则抛 SOURCE_NOT_FOUND -> 404）
        SourceRegistryView sourceView = sourceService.get(sourceId);

        // 4. 加载画像文件并生成 SourceProfile
        SourceProfile profile = loadProfile(sourceView);

        // 5. 规则 14：陈旧预览防护（stale preview protection）
        verifyProfileVersionAndHash(profile, req);

        // 6. 安全加载样本记录（已在 resolver 内完成白名单、URL、遍历和 100 条上限校验）
        List<String> records = sampleResolver.resolveAndLoad(req.sampleRef());

        // 7. 执行 DryRunEngine 纯计算
        DryRunEngine engine = new DryRunEngine(pureMapper, contractValidator);
        DryRunReport report = engine.run(profile, records);

        // 8. 存入内存存储
        String reportId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        StoredDryRunReport stored = new StoredDryRunReport(reportId, sourceId, report, now);
        reportStore.save(stored);

        log.info("Dry-run 执行完成 sourceId={} reportId={} totalRows={} canonicalRows={} quarantinedRows={}",
                sourceId, reportId, report.totalRows(), report.canonicalRows(), report.quarantinedRows());

        return MappingDryRunReportView.from(reportId, sourceId, report, now);
    }

    /**
     * 按 sourceId 和 reportId 获取已存储的报告。
     */
    public MappingDryRunReportView getDryRunReport(Long sourceId, String reportId) {
        if (reportId == null || reportId.isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "reportId 必填");
        }

        // 校验源存在性（不存在则 404）
        sourceService.get(sourceId);

        StoredDryRunReport stored = reportStore.findById(reportId)
                .orElseThrow(() -> new PlatformBizException(PlatformBizException.DRY_RUN_REPORT_NOT_FOUND,
                        "Dry run 报告不存在: " + reportId));

        if (!sourceId.equals(stored.sourceId())) {
            throw new PlatformBizException(PlatformBizException.DRY_RUN_REPORT_NOT_FOUND,
                    "Dry run 报告 " + reportId + " 不属于源 " + sourceId);
        }

        return MappingDryRunReportView.from(stored.reportId(), stored.sourceId(), stored.report(), stored.createdAt());
    }

    private SourceProfile loadProfile(SourceRegistryView sourceView) {
        String profilePathStr = sourceView.profilePath();
        if (profilePathStr == null || profilePathStr.isBlank()) {
            throw new PlatformBizException(PlatformBizException.SOURCE_PROFILE_INVALID, "源登记未配置 profilePath");
        }

        Path file = repoRoot.resolve(profilePathStr).normalize().toAbsolutePath();
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            // 兜底尝试作为绝对路径或直接相对路径
            Path direct = Paths.get(profilePathStr).normalize().toAbsolutePath();
            if (Files.isRegularFile(direct) && Files.isReadable(direct)) {
                file = direct;
            } else {
                throw new PlatformBizException(PlatformBizException.SOURCE_PROFILE_INVALID,
                        "源画像文件不存在或不可读: " + profilePathStr);
            }
        }

        try {
            return SourceProfile.fromFile(file);
        } catch (IOException e) {
            throw new PlatformBizException(PlatformBizException.SOURCE_PROFILE_INVALID,
                    "源画像文件读取或解析失败: " + e.getMessage());
        }
    }

    private void verifyProfileVersionAndHash(SourceProfile profile, MappingDryRunReq req) {
        if (req.profileVersion() != null && !req.profileVersion().isBlank()) {
            if (!req.profileVersion().trim().equals(profile.profileVersion())) {
                throw new PlatformBizException(PlatformBizException.PROFILE_VERSION_CONFLICT,
                        "请求的 profileVersion (" + req.profileVersion()
                                + ") 与源画像版本 (" + profile.profileVersion() + ") 不一致");
            }
        }

        String expectedHash = req.effectiveContentHash();
        if (expectedHash != null && !expectedHash.isBlank()) {
            if (!expectedHash.equalsIgnoreCase(profile.contentHash())) {
                throw new PlatformBizException(PlatformBizException.PROFILE_VERSION_CONFLICT,
                        "请求的 profileContentHash 与源画像内容哈希不一致");
            }
        }
    }
}
