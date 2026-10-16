package com.graduation.analytics.mapping.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.contracts.EventClock;
import com.graduation.analytics.mapping.CanonicalContract;
import com.graduation.analytics.mapping.CanonicalContractLoader;
import com.graduation.analytics.mapping.MappingExecutor;
import com.graduation.analytics.mapping.MappingIssue;
import com.graduation.analytics.mapping.MappingOutcome;
import com.graduation.analytics.mapping.MappingProfile;
import com.graduation.analytics.mapping.MappingProfileLoad;
import com.graduation.analytics.mapping.MappingProfileLoader;
import com.graduation.analytics.mapping.activation.ActiveMappingPointer;
import com.graduation.analytics.mapping.activation.ActiveMappingPointerStore;
import com.graduation.analytics.source.SourcePathPolicy;
import com.graduation.analytics.source.dto.SourceRegistryView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 真实采集路径上的映射适配器（S2-02）：把「本源登记画像」变成可执行的逐行映射，
 * 并补齐平台生成的信封字段 {@code ingest_time}。
 *
 * <p><b>与 dry-run 共用同一套执行器</b>（设计 §8.3：{@code Mapper：raw + mapping → canonical 或 quarantine，
 * 纯转换与持久化分离，供 dry-run 与真实采集共用}）：本类只做「装载 + 平台字段」，
 * 转换语义一律来自 {@link MappingExecutor}，报告侧（{@code MappingDryRunService}）用的是同一个类。
 * 两份实现会立刻产生"预览说可以、真跑说不行"的双所有者问题。</p>
 *
 * <h2>谁决定这一轮要不要映射</h2>
 * 判据只有两条，都在任何写入之前判完（{@code IngestionService.runOne} 里甚至在批次行 insert 之前），
 * 因此不存在"写了一半才发现画像不能用"的中间态：
 * <ol>
 *   <li>画像非法/缺失/与登记源不一致 ⇒ {@code MAPPING_PROFILE_INVALID}(409) 拒绝本轮；</li>
 *   <li>v2 画像可装载但被激活门阻止（关键目标未映射）⇒ {@code MAPPING_PROFILE_BLOCKED}(409) 拒绝本轮；</li>
 *   <li>v1 兼容画像 ⇒ <b>不映射</b>（{@link SourceMapping#legacy()}）：v1 既有文件缺金额单位，
 *       逐行映射会把所有含金额的事件判成 {@code MISSING_AMOUNT_POLICY} 而全部隔离——
 *       那是在"接入映射"的名义下打断既有采集链路。v1 只读兼容是 Loader 自己声明的语法语义
 *       （{@code ProfileSyntax.V1_COMPATIBILITY}），这里只是尊重它；</li>
 *   <li><b>S2-03 新增</b>：v2 画像还必须**正是该源当前已激活的那一份**
 *       （{@link ActiveMappingPointerStore} 里的指针，判据见 {@link #requireActivated}）⇒
 *       没有激活记录 {@code MAPPING_NOT_ACTIVE}(409)、已激活画像与磁盘现状不符
 *       {@code MAPPING_ACTIVE_PROFILE_DRIFT}(409)。<b>磁盘上存在一份能装载的 v2 画像不等于它已激活</b>：
 *       绝不 latest-wins，也绝不"能装载就用"。</li>
 * </ol>
 *
 * <p><b>采集侧只比对画像哈希，不比对契约哈希（如实登记的不对称）</b>：契约与画像的绑定关系在
 * **激活时**已判过（契约字节漂移即 409，见 {@code MappingActivationService}），采集侧再比一次会得到
 * 与激活侧不同的第二判据（契约文件在激活后被改动时：激活侧会拒绝下一次激活，而采集侧如果也比对，
 * 就会把"已激活的映射"莫名其妙地停掉）。这里的选择是：采集侧只保证"跑的是被激活的那份画像字节"，
 * 契约漂移由激活门与 dry-run 负责暴露。</p>
 *
 * <p><b>激活指针落地后的接线</b>：{@link #prepare} 是读那个指针的**唯一**位置，
 * 采集链路上不再有第二处分叉。</p>
 *
 * <p><b>ingest_time 的所有者</b>：{@code ingest_time} 由平台采集层生成（{@code EventClock}，
 * 业务时区 Asia/Shanghai，秒级截断 + 显式偏移），Mapper 不写它、只登记源侧候选
 * （{@code MappingOutcome.ingestTimeCandidate}）。原始行自带的同名值一律不采信：
 * 契约对它的定义就是"该行的**采集**时间（Java 侧生成）"，链路延迟 = {@code ingest_time - event_time}。</p>
 */
@Slf4j
@Component
public class SourceMapper {

    /** canonical 契约位置（与 dry-run 同一个配置键，避免"契约有两个位置"）。 */
    static final String DEFAULT_CONTRACT_PATH = "contract-specs/schemas/canonical-event.v1.schema.json";

    /** {@code quarantine_record.reason} 是 {@code VARCHAR(255)}：截断在写入之前做，不靠数据库报错。 */
    static final int QUARANTINE_REASON_LIMIT = 255;

    private static final String REASON_PREFIX = "MAPPING:";
    private static final DateTimeFormatter INGEST_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path profileRoot;
    private final Path contractPath;
    private final EventClock clock;
    private final ObjectMapper mapper;
    private final ActiveMappingPointerStore pointers;

    /** 契约 + 执行器的懒加载缓存（执行器无状态，可跨批次复用）。 */
    private volatile Holder holder;

    public SourceMapper(@Value("${platform.source.profile-root:.}") String profileRoot,
                        @Value("${platform.mapping.contract-path:" + DEFAULT_CONTRACT_PATH + "}") String contractPath,
                        EventClock clock,
                        ObjectMapper mapper,
                        ActiveMappingPointerStore pointers) {
        this.profileRoot = Path.of(profileRoot).toAbsolutePath().normalize();
        this.contractPath = Path.of(contractPath).toAbsolutePath().normalize();
        this.clock = clock;
        this.mapper = mapper;
        this.pointers = pointers;
    }

    /**
     * 装载本源的映射绑定（fail-closed：分支见类注释）。
     *
     * @param source 本轮归属的登记源；{@code profile_path} 是仓库相对路径，
     *               根目录由 {@code platform.source.profile-root} 决定（与 P1-03 登记校验同一个根）
     */
    public SourceMapping prepare(SourceRegistryView source) {
        String sourceCode = source.sourceCode();
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "登记源缺 source_code，无法装载映射画像（source_id=" + source.id() + "）");
        }
        String repoRelative = source.profilePath();
        if (!SourcePathPolicy.isRepoRelative(repoRelative)) {
            // 不回显该值：它可能来自库里的历史行，回显等于把部署机路径写进异常（任务书 §6）
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "profile_path 违反仓库相对路径策略（值已脱敏，未回显），拒绝采集：sourceCode=" + sourceCode);
        }
        Path file = SourcePathPolicy.resolveUnderRoot(profileRoot, repoRelative);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "画像文件不存在或不可读（仓库相对路径）：" + repoRelative + "，拒绝采集：sourceCode=" + sourceCode);
        }

        String profileText = readText(file, repoRelative, sourceCode);
        Holder current = holder();
        MappingProfileLoad load = new MappingProfileLoader(current.contract())
                .load(profileText, "ingest:" + sourceCode);
        if (!load.ok()) {
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "画像装载失败（sourceCode=" + sourceCode + "）：" + issueCodes(load.issues()));
        }
        MappingProfile profile = load.profile();
        if (!sourceCode.equals(profile.sourceCode())) {
            // 用 A 源的画像映射 B 源的字节会产出"字段看着对、语义是别的源"的 canonical，必须拒绝
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "画像 sourceCode 与登记不一致：登记=" + sourceCode + " 文件=" + profile.sourceCode()
                            + "（profile_path=" + repoRelative + "）");
        }
        if (profile.syntax() == MappingProfile.ProfileSyntax.V1_COMPATIBILITY) {
            log.info("映射不生效（v1 只读兼容画像）：sourceCode={} profileVersion={} profile_path={}",
                    sourceCode, profile.profileVersion(), repoRelative);
            return SourceMapping.legacy();
        }
        if (profile.activationBlocked()) {
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_BLOCKED,
                    "画像可装载但禁止激活，拒绝采集（sourceCode=" + sourceCode + " profileVersion="
                            + profile.profileVersion() + "）：" + String.join(",", profile.activationBlocks()));
        }
        requireActivated(source, profile, repoRelative);
        log.info("映射生效：sourceCode={} profileVersion={} profileChecksum={} profile_path={}",
                sourceCode, profile.profileVersion(), profile.profileChecksum(), repoRelative);
        return SourceMapping.of(profile);
    }

    /**
     * S2-03 激活门：正式采集只使用**该源当前已激活**的那一份画像（设计 §7.4）。
     *
     * <p>两条判据，都在任何写入之前：</p>
     * <ol>
     *   <li>该源必须在 {@link ActiveMappingPointerStore} 里有激活记录，且记录指向的
     *       {@code profile_path} 就是本次登记加载的那一份 ⇒ 否则 {@code MAPPING_NOT_ACTIVE}(409)。
     *       覆盖两种事实：从来没有激活过；或者登记表后来被指到了另一个画像文件（那一份没被激活过）。</li>
     *   <li>已激活画像字节的 sha256 必须等于磁盘上这一份的 sha256 ⇒ 否则
     *       {@code MAPPING_ACTIVE_PROFILE_DRIFT}(409)：磁盘被换了内容而激活记录还是旧的。</li>
     * </ol>
     *
     * <p>为什么比的是**哈希**而不是"文件修改时间/最新一份"：激活是一个显式动作（走
     * {@code POST /mappings/activate}，要一张已通过的 dry-run 报告）。按 mtime 或"磁盘上最新"取用
     * 等于让"往目录里丢一个文件"变成一次隐式激活——预览与运行脱钩，正是要禁止的 latest-wins。</p>
     */
    private void requireActivated(SourceRegistryView source, MappingProfile profile, String repoRelative) {
        ActiveMappingPointer active = pointers.find(source.id()).orElse(null);
        if (active == null || !repoRelative.equals(active.profilePath())) {
            throw new PlatformBizException(PlatformBizException.MAPPING_NOT_ACTIVE,
                    "该源没有已激活的映射画像，拒绝在正式采集上使用未激活画像（sourceCode="
                            + source.sourceCode() + " profileVersion=" + profile.profileVersion()
                            + (active == null ? "）：从未激活过"
                            : "）：当前登记的 profile_path 与已激活记录不一致"
                              + "（已激活=" + active.profilePath() + "，本次登记=" + repoRelative + "）")
                            + "；请先对该画像执行 dry-run 并调用 activate");
        }
        if (!profile.profileChecksum().equals(active.profileChecksum())) {
            throw new PlatformBizException(PlatformBizException.MAPPING_ACTIVE_PROFILE_DRIFT,
                    "磁盘上的画像与已激活画像内容不一致，拒绝采集（sourceCode=" + source.sourceCode()
                            + " profile_path=" + repoRelative
                            + "）：已激活=" + shortHash(active.profileChecksum())
                            + "，当前=" + shortHash(profile.profileChecksum())
                            + "；激活记录不会被自动覆盖，请重新 dry-run 后显式 activate");
        }
    }

    /** 哈希截断显示：足够区分两次激活，又不把整串哈希灌进日志/异常。 */
    private static String shortHash(String checksum) {
        return checksum == null ? "null" : checksum.substring(0, Math.min(12, checksum.length()));
    }

    /**
     * 映射一行原始 JSON（只有 {@link SourceMapping#applied()} 为 true 时才应调用；
     * 直通分支由采集器自己走既有校验，本方法拒绝代劳——否则"没映射"会被伪装成"映射成功"）。
     */
    public MappedLine map(SourceMapping mapping, String rawLine) {
        if (!mapping.applied()) {
            throw new IllegalArgumentException("legacy 直通不经过映射器：调用方必须先判 SourceMapping.applied()");
        }
        MappingOutcome outcome = holder().executor().execute(mapping.profile(), rawLine);
        if (outcome.quarantined()) {
            return MappedLine.quarantined(formatViolations(outcome.violations()));
        }
        JsonNode canonical = outcome.canonical();
        if (!(canonical instanceof ObjectNode node)) {
            throw new IllegalStateException("映射产出的 canonical 不是 JSON 对象（不应发生）：" + canonical);
        }
        return MappedLine.accepted(toCanonicalLine(node));
    }

    /**
     * 按契约信封顺序输出 canonical 行，并把平台生成的 {@code ingest_time} 放回契约位置
     * （不是追加到末尾）：Mapper 产出的键恰好是"契约信封字段 - ingest_time"，因此这里既不丢键、
     * 也不引入第二套顺序。
     */
    private String toCanonicalLine(ObjectNode canonical) {
        CanonicalContract contract = holder().contract();
        String platformField = contract.platformIngestField();
        ObjectNode out = mapper.createObjectNode();
        for (String field : contract.envelopeFields().keySet()) {
            if (field.equals(platformField)) {
                out.put(field, ingestTime());
                continue;
            }
            JsonNode value = canonical.get(field);
            if (value != null) {
                out.set(field, value);
            }
        }
        try {
            return mapper.writeValueAsString(out);
        } catch (IOException e) {
            throw new UncheckedIOException("canonical 行序列化失败", e);
        }
    }

    /** 平台采集时间：业务时区、秒级、显式偏移（契约 {@code iso8601_time} pattern）。 */
    private String ingestTime() {
        return INGEST_TIME.format(clock.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * 隔离原因文本：{@code MAPPING:<CODE>@<path>[;<CODE>@<path>…][;+N]}，总长不超过
     * {@link #QUARANTINE_REASON_LIMIT}（{@code quarantine_record.reason VARCHAR(255)}）。
     *
     * <p>带 {@code MAPPING:} 前缀是为了与契约校验器给出的原因（如"缺失必要字段: X"）在同一列里可区分：
     * 两者是两道不同的闸门，事后追查需要知道"是映射判的，还是 canonical 契约判的"。</p>
     */
    public static String formatViolations(List<MappingIssue> violations) {
        StringBuilder text = new StringBuilder(REASON_PREFIX);
        int included = 0;
        for (MappingIssue issue : violations) {
            String token = issue.reason().name() + "@" + (issue.path() == null ? "" : issue.path());
            if (text.length() + token.length() + 1 > QUARANTINE_REASON_LIMIT) {
                break;
            }
            if (included > 0) {
                text.append(';');
            }
            text.append(token);
            included++;
        }
        int omitted = violations.size() - included;
        if (omitted > 0) {
            String suffix = ";+" + omitted;
            if (text.length() + suffix.length() > QUARANTINE_REASON_LIMIT) {
                text.setLength(QUARANTINE_REASON_LIMIT - suffix.length());
            }
            text.append(suffix);
        }
        return text.toString();
    }

    private static String issueCodes(List<MappingIssue> issues) {
        return issues.stream()
                .map(i -> i.reason().name() + "@" + i.path())
                .collect(Collectors.joining(","));
    }

    private static String readText(Path file, String repoRelative, String sourceCode) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PlatformBizException(PlatformBizException.MAPPING_PROFILE_INVALID,
                    "画像文件读取失败（仓库相对路径）：" + repoRelative + "，拒绝采集：sourceCode=" + sourceCode);
        }
    }

    private Holder holder() {
        Holder local = holder;
        if (local == null) {
            synchronized (this) {
                local = holder;
                if (local == null) {
                    if (!Files.isRegularFile(contractPath)) {
                        throw new IllegalStateException(
                                "canonical 契约文件不存在（platform.mapping.contract-path）：" + contractPath);
                    }
                    CanonicalContract contract = CanonicalContractLoader.load(contractPath);
                    local = new Holder(contract, new MappingExecutor(contract, mapper));
                    holder = local;
                }
            }
        }
        return local;
    }

    private record Holder(CanonicalContract contract, MappingExecutor executor) {
    }
}
