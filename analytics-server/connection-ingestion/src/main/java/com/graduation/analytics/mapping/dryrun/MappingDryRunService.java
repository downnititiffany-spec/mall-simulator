package com.graduation.analytics.mapping.dryrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.mapping.CanonicalContract;
import com.graduation.analytics.mapping.ContractSnapshot;
import com.graduation.analytics.mapping.MappingExecutor;
import com.graduation.analytics.mapping.MappingHash;
import com.graduation.analytics.mapping.MappingJson;
import com.graduation.analytics.mapping.MappingOutcome;
import com.graduation.analytics.mapping.MappingProfile;
import com.graduation.analytics.mapping.MappingProfileLoad;
import com.graduation.analytics.mapping.MappingProfileLoader;
import com.graduation.analytics.mapping.MappingReason;
import com.graduation.analytics.mapping.MappingStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 映射 dry-run 编排（设计 §7.3 规则 10、§7.4）。
 *
 * <p><b>职责边界</b>：本类只做四件事——校验入参、把受控样本按行喂给已冻结的
 * {@link MappingExecutor}、聚合执行器给出的事实、把报告放进进程内存储。
 * 它<b>不</b>实现任何映射语义（枚举/金额/时间/必填全在 S2-01A 的核心与契约里），
 * 也<b>不</b>写任何生产状态（无 ACTIVE 指针、无 checkpoint、无 manifest、无 canonical/隔离落库、
 * 无 MySQL、无 3306/3307）。</p>
 *
 * <p><b>为什么样本要按行喂</b>：dry-run 的输入是"受控脱敏样本"（JSONL：一行一个原始事件），
 * 与未来采集链路的落盘形态一致；行号也是报告里唯一能让人回到原文定位的坐标
 * （见 {@link MappingDryRunIssue}）。</p>
 */
@Service
public class MappingDryRunService {

    /** limit 下界（含） */
    public static final int LIMIT_MIN = 1;

    /** limit 上界（含）：设计 §7.3 规则 10「最多100条受控脱敏样本」 */
    public static final int LIMIT_MAX = 100;

    /** 报告里内联的 canonical 预览条数上限（全量事实看 checksum 列表，不靠预览推断） */
    public static final int PREVIEW_MAX = 5;

    /** 契约文件默认位置：仓库相对（平台启动约定＝工作目录为仓库根） */
    public static final String DEFAULT_CONTRACT_PATH = "contract-specs/schemas/canonical-event.v1.schema.json";

    private static final DateTimeFormatter REPORT_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** sourceId / reportId 的形状：路径段安全、可回显、无空白 */
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final MappingDryRunReportRepository reports;
    private final EventClock clock;
    private final Path contractPath;
    private final Path sampleRoot;
    private final ObjectMapper mapper = MappingJson.mapper();

    /** 惰性加载的契约快照（契约是仓库里的只读文件，进程内缓存一次即可；不随请求变化） */
    private volatile ContractSnapshot contract;

    public MappingDryRunService(MappingDryRunReportRepository reports,
                                EventClock clock,
                                @Value("${platform.mapping.contract-path:" + DEFAULT_CONTRACT_PATH + "}")
                                String contractPath,
                                @Value("${platform.mapping.sample-root:./sample-data}") String sampleRoot) {
        this.reports = reports;
        this.clock = clock;
        this.contractPath = Path.of(contractPath).toAbsolutePath().normalize();
        this.sampleRoot = Path.of(sampleRoot).toAbsolutePath().normalize();
    }

    /**
     * 执行一次 dry-run 并把报告存入进程内存储。
     *
     * @param sourceId    报告归属的源（本轮**不查**源登记表：dry-run 不读库，sourceId 仅作归属标识）
     * @param profileText 候选画像**原文**（checksum 与装载都用这一份字节，避免二次序列化改变哈希）
     * @param sampleRef   受控样本引用（仓库相对；见 {@link SampleRefPolicy}）
     * @param limit       本轮最多处理多少行（1..100，必填）
     */
    public MappingDryRunReport run(String sourceId, String profileText, String sampleRef, Integer limit) {
        String source = requireToken(sourceId, "sourceId");
        int maxLines = requireLimit(limit);
        if (profileText == null || profileText.isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "profileText 必填：候选画像原文（JSON 文本）；空值不做隐式默认");
        }
        String ref = SampleRefPolicy.requireSampleRef(sampleRef);
        Path sample = SampleRefPolicy.resolveUnderSampleRoot(sampleRoot, ref);
        byte[] sampleBytes = readSample(sample, ref);
        ContractSnapshot snapshot = contract();
        String profileChecksum = MappingHash.sha256Hex(profileText);
        MappingProfileLoad load = new MappingProfileLoader(snapshot.contract()).load(profileText, "dry-run:" + source);

        String reportId = "dr-" + REPORT_NO.format(clock.nowLdt()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        if (!load.ok()) {
            MappingDryRunReport rejected = rejectedReport(reportId, source, snapshot, profileChecksum,
                    load.issues(), ref, sampleBytes, maxLines);
            reports.save(rejected);
            return rejected;
        }
        MappingDryRunReport report = executedReport(reportId, source, snapshot, profileChecksum, load.profile(),
                ref, sampleBytes, maxLines);
        reports.save(report);
        return report;
    }

    /**
     * 取回本进程内的一张报告。
     *
     * <p>{@code sourceId} 必须与报告归属一致：否则按"不存在"处理（不泄露别的源有没有跑过预览）。
     * 进程重启后报告消失 ⇒ 404，这是"报告不落库"的既定代价。</p>
     */
    public MappingDryRunReport report(String sourceId, String reportId) {
        String source = requireToken(sourceId, "sourceId");
        String id = requireToken(reportId, "reportId");
        return reports.find(id)
                .filter(r -> r.sourceId().equals(source))
                .orElseThrow(() -> new PlatformBizException(PlatformBizException.DRY_RUN_REPORT_NOT_FOUND,
                        "dry-run 报告不存在（reportId 与 sourceId 必须同时匹配；报告只存在本进程内，重启即失效）"));
    }

    // ------------------------------------------------------------------ 装载失败：fail-closed 展示

    private MappingDryRunReport rejectedReport(String reportId, String source, ContractSnapshot snapshot,
                                               String profileChecksum, List<com.graduation.analytics.mapping.MappingIssue> issues,
                                               String ref, byte[] sampleBytes, int maxLines) {
        List<MappingDryRunIssue> profileIssues = new ArrayList<>();
        for (com.graduation.analytics.mapping.MappingIssue issue : issues) {
            // 画像级问题不针对任何样本行：lineNo=0（见 MappingDryRunIssue 的取值约定），
            // 绝不拿"第 1 行"冒充，否则报告会指向一个没被执行过的位置。
            profileIssues.add(new MappingDryRunIssue(0, issue.reason(), issue.path(), issue.detail()));
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("profileAccepted=false（候选画像未通过装载，fail-closed 不执行样本）");
        return new MappingDryRunReport(reportId, source, clock.nowLdt(),
                null, null, profileChecksum,
                snapshot.contract().contractVersion(), snapshot.checksum(),
                false, profileIssues,
                ref, MappingHash.sha256Hex(sampleBytes), countEventLines(sampleBytes), maxLines,
                0, 0, 0, List.of(), null, 0, 0, null, 0, 0, 0,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), reasons, false);
    }

    // ------------------------------------------------------------------ 正常执行与聚合

    private MappingDryRunReport executedReport(String reportId, String source, ContractSnapshot snapshot,
                                               String profileChecksum, MappingProfile profile,
                                               String ref, byte[] sampleBytes, int maxLines) {
        MappingExecutor executor = executor(snapshot.contract(), mapper);
        List<SampleLine> lines = eventLines(sampleBytes);
        int processed = Math.min(lines.size(), maxLines);

        List<MappingDryRunIssue> violations = new ArrayList<>();
        List<MappingDryRunIssue> warnings = new ArrayList<>();
        List<String> systemErrors = new ArrayList<>();
        List<String> acceptedChecksums = new ArrayList<>();
        List<MappingDryRunPreview> preview = new ArrayList<>();
        Map<MappingReason, Integer> reasonCounts = new TreeMap<>();
        Map<MappingReason, Integer> warningCounts = new TreeMap<>();
        int accepted = 0;
        int quarantined = 0;
        int requiredTotal = 0;
        int requiredOk = 0;
        int enumObserved = 0;
        int enumResolved = 0;
        int amountRounded = 0;

        for (int index = 0; index < processed; index++) {
            SampleLine sampleLine = lines.get(index);
            int lineNo = sampleLine.lineNo();
            MappingOutcome outcome;
            try {
                outcome = executor.execute(profile, sampleLine.text());
            } catch (RuntimeException e) {
                // 规则 13：系统异常不冒充坏数据。单独记一列，不计数成败，也让 activationEligible=false。
                systemErrors.add("样本第 " + lineNo + " 行执行异常: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                continue;
            }
            for (com.graduation.analytics.mapping.MappingIssue issue : outcome.violations()) {
                violations.add(MappingDryRunIssue.of(lineNo, issue));
            }
            for (com.graduation.analytics.mapping.MappingIssue issue : outcome.warnings()) {
                warnings.add(MappingDryRunIssue.of(lineNo, issue));
                warningCounts.merge(issue.reason(), 1, Integer::sum);
            }
            MappingStats stats = outcome.stats();
            stats.reasonCounts().forEach((reason, count) -> reasonCounts.merge(reason, count, Integer::sum));
            requiredTotal += stats.requiredPositionsTotal();
            requiredOk += stats.requiredPositionsOk();
            enumObserved += stats.enumPairsObserved();
            enumResolved += stats.enumPairsResolved();
            amountRounded += stats.amountRounded();
            if (outcome.quarantined()) {
                quarantined++;
            } else {
                accepted++;
                acceptedChecksums.add(outcome.canonicalChecksum());
                if (preview.size() < PREVIEW_MAX) {
                    preview.add(new MappingDryRunPreview(lineNo, outcome.canonical()));
                }
            }
        }

        List<String> activationBlocks = profile.activationBlocks();
        List<String> capabilityGaps = profile.capabilityGaps();
        List<String> unused = unusedAmountDeclarations(profile, snapshot.contract());
        List<String> reasons = new ArrayList<>();
        if (processed == 0) {
            // 零事实不能当"无违例"用：否则空样本/全空行会让一份什么都没证明的预览被判"可激活"。
            reasons.add("processedCount=0（样本里没有可执行行，零事实不能判定可激活）");
        }
        if (!violations.isEmpty()) {
            reasons.add("violations=" + violations.size() + "（有违例即不可激活）");
        }
        if (!systemErrors.isEmpty()) {
            reasons.add("systemErrors=" + systemErrors.size() + "（平台侧异常未查清前不可激活）");
        }
        if (!activationBlocks.isEmpty()) {
            reasons.add("activationBlocks=" + activationBlocks);
        }
        if (!capabilityGaps.isEmpty()) {
            reasons.add("capabilityGaps=" + capabilityGaps + "（本轮无'无害缺口'白名单，一律视为影响正确性）");
        }
        boolean eligible = reasons.isEmpty();

        return new MappingDryRunReport(reportId, source, clock.nowLdt(),
                profile.profileVersion(), profile.syntax(), profileChecksum,
                snapshot.contract().contractVersion(), snapshot.checksum(),
                true, List.of(),
                ref, MappingHash.sha256Hex(sampleBytes), lines.size(), maxLines,
                processed, accepted, quarantined, acceptedChecksums,
                coverage(requiredOk, requiredTotal), requiredTotal, requiredOk,
                coverage(enumResolved, enumObserved), enumObserved, enumResolved,
                amountRounded, reasonCounts, warningCounts,
                violations, warnings, systemErrors, preview,
                activationBlocks, capabilityGaps, unused, reasons, eligible);
    }

    /**
     * 「未使用声明」提示（S2-01B 口径四）：{@code amountPolicy.bySourceField} 里声明了单位、
     * 但**当前画像的任何金额目标都引用不到**的源字段。
     *
     * <p>静态可达性判定（画像声明 + 契约字段类型），不做样本内容推断：
     * 只要该源字段被某个金额目标引用（信封/载荷/订单项），就认为"声明在用"。
     * 这样即使本批样本没出现该字段，也不会误报"未使用"；代价是"本批样本恰好没用上"不会单独提示——
     * 这属于提示强度问题，不影响正确性，故不引入第二套推断逻辑。</p>
     */
    private static List<String> unusedAmountDeclarations(MappingProfile profile, CanonicalContract contract) {
        if (profile.amountPolicy() == null || profile.amountPolicy().bySourceField().isEmpty()) {
            return List.of();
        }
        Set<String> used = new LinkedHashSet<>();
        profile.envelopeFieldMappings().forEach((sourceField, target) -> {
            CanonicalContract.FieldSpec spec = contract.envelopeField(target);
            if (spec != null && spec.kind() == CanonicalContract.Kind.AMOUNT) {
                used.add(sourceField);
            }
        });
        profile.payloadFieldMappings().forEach((eventType, mappings) -> mappings.forEach((sourceField, target) -> {
            CanonicalContract.FieldSpec spec = contract.payloadField(eventType, target);
            if (spec != null && spec.kind() == CanonicalContract.Kind.AMOUNT) {
                used.add(sourceField);
            }
        }));
        profile.itemMaps().forEach((eventType, maps) -> maps.values().forEach(itemMap ->
                itemMap.itemFields().forEach((sourceField, target) -> {
                    CanonicalContract.FieldSpec spec = contract.itemFields().get(target);
                    if (spec != null && spec.kind() == CanonicalContract.Kind.AMOUNT) {
                        used.add(sourceField);
                    }
                })));
        if (profile.envelopeSourceMode() == MappingProfile.EnvelopeSourceMode.CANONICAL_NAME_IDENTITY) {
            // v1 扁平画像：源字段名即 canonical 字段名，同名身份模式下金额字段无需显式声明来源
            contract.envelopeFields().forEach((name, spec) -> {
                if (spec.kind() == CanonicalContract.Kind.AMOUNT) {
                    used.add(name);
                }
            });
            for (String eventType : contract.eventTypes()) {
                contract.payloadFields(eventType).forEach((name, spec) -> {
                    if (spec.kind() == CanonicalContract.Kind.AMOUNT) {
                        used.add(name);
                    }
                });
            }
            contract.itemFields().forEach((name, spec) -> {
                if (spec.kind() == CanonicalContract.Kind.AMOUNT) {
                    used.add(name);
                }
            });
        }
        List<String> unused = new ArrayList<>();
        profile.amountPolicy().bySourceField().keySet().stream()
                .filter(key -> !used.contains(key))
                .forEach(unused::add);
        return unused;
    }

    // ------------------------------------------------------------------ 输入与文件

    private static Double coverage(int ok, int total) {
        return total == 0 ? null : (double) ok / (double) total;
    }

    private static String requireToken(String value, String field) {
        String token = value == null ? null : value.trim();
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    field + " 必须是 1..64 位的 [A-Za-z0-9._-]（值已脱敏，未回显）");
        }
        return token;
    }

    private static int requireLimit(Integer limit) {
        if (limit == null || limit < LIMIT_MIN || limit > LIMIT_MAX) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "limit 必填且必须在 " + LIMIT_MIN + ".." + LIMIT_MAX + " 之间（设计 §7.3 规则 10：最多 100 条受控样本）");
        }
        return limit;
    }

    private byte[] readSample(Path sample, String ref) {
        if (!Files.isRegularFile(sample) || !Files.isReadable(sample)) {
            throw new PlatformBizException(PlatformBizException.DRY_RUN_SAMPLE_NOT_FOUND,
                    "样本不存在或不可读（sampleRef=" + ref + "）");
        }
        try {
            return Files.readAllBytes(sample);
        } catch (IOException e) {
            throw new UncheckedIOException("读取样本失败: " + sample, e);
        }
    }

    /** 非空样本行（保持文件顺序，跳过空白行；行号是物理行号） */
    private static List<SampleLine> eventLines(byte[] sampleBytes) {
        List<SampleLine> lines = new ArrayList<>();
        String[] physical = new String(sampleBytes, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < physical.length; i++) {
            String raw = physical[i];
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (!line.isBlank()) {
                lines.add(new SampleLine(i + 1, line));
            }
        }
        return lines;
    }

    private static int countEventLines(byte[] sampleBytes) {
        return eventLines(sampleBytes).size();
    }

    /** 样本里的一行：物理行号 + 原文（空行不参与执行，但占行号） */
    private record SampleLine(int lineNo, String text) {
    }

    private ContractSnapshot contract() {
        ContractSnapshot local = contract;
        if (local == null) {
            synchronized (this) {
                local = contract;
                if (local == null) {
                    // S2-03：契约快照（装载 + 字节 sha256）的唯一所有者是 ContractSnapshot.load，
                    // 激活侧用的是同一个工厂——两处各写一份就会算出两个"当前契约 checksum"。
                    local = ContractSnapshot.load(contractPath);
                    contract = local;
                }
            }
        }
        return local;
    }

    /**
     * 构造逐行执行器。做成方法而不是内联 {@code new}，只为一件事：让"逐行执行抛异常"这条**规则 13 分支
     * （系统异常不冒充坏数据）**能被独立验证——测试可覆写返回一个受控执行器。
     * 生产路径只有这一个实现，执行器依旧是那个冻结的确定性核心。
     */
    protected MappingExecutor executor(CanonicalContract contract, ObjectMapper mapper) {
        return new MappingExecutor(contract, mapper);
    }
}
