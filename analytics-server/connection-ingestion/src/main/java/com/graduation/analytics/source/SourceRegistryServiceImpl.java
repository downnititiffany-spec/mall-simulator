package com.graduation.analytics.source;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.source.dto.SourceChangeOutcome;
import com.graduation.analytics.source.dto.SourceCheckResult;
import com.graduation.analytics.source.dto.SourceRegistryCreateReq;
import com.graduation.analytics.source.dto.SourceRegistryUpdateReq;
import com.graduation.analytics.source.dto.SourceRegistryView;
import com.graduation.analytics.source.entity.SourceRegistry;
import com.graduation.analytics.source.mapper.ActiveSourceBindingMapper;
import com.graduation.analytics.source.mapper.SourceRegistryMapper;
import com.graduation.analytics.warehouse.WarehouseNamespaceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 源登记服务实现（P1-03）。
 *
 * <p><b>步骤顺序是刻意的</b>：{@code activate} 先做完全部可能失败的判断（存在性、状态可变更、画像校验、
 * 取到 ACTIVE 行），**再**开始任何写入。这样"失败不留半成品"不依赖回滚，
 * 在真库（{@code @Transactional} 回滚）与 E2 内存替身（无事务管理器）下行为一致——
 * 替身不必假装会回滚。</p>
 *
 * <p><b>并发正确性来自三件事</b>：{@code @Transactional}（否则 {@code FOR UPDATE} 立刻失效）、
 * 「先锁 ACTIVE 行、再写源状态」的固定顺序（否则"当前源不应是 PAUSED"在并发下不成立），
 * 以及**锁内用锁定读重读被决策的那一行**（否则 REPEATABLE READ 的一致性快照会让
 * 并发的第二个调用把别人的变更看成没发生，报出 N 次假 {@code changed=true}；
 * 这条是 E2 并发用例 {@code concurrentActivateSameSourceFromDraftCollapsesToOneChange} 抓出来的）。
 * 并发证据：E2 见 {@code SourceRegistryConcurrencyTest}（内存行锁替身），
 * 真库语义见 E3 真 HTTP + 真 MySQL 副本库验收。</p>
 */
@Slf4j
@Service
public class SourceRegistryServiceImpl implements SourceRegistryService {

    private final SourceRegistryMapper sourceMapper;
    private final ActiveSourceBindingMapper bindingMapper;
    private final SourceProfileValidator profileValidator;

    public SourceRegistryServiceImpl(SourceRegistryMapper sourceMapper,
                                     ActiveSourceBindingMapper bindingMapper,
                                     SourceProfileValidator profileValidator) {
        this.sourceMapper = sourceMapper;
        this.bindingMapper = bindingMapper;
        this.profileValidator = profileValidator;
    }

    // ---------------------------------------------------------------- 读路径

    @Override
    public List<SourceRegistryView> list() {
        Long current = currentSourceId().orElse(null);
        List<SourceRegistry> rows = sourceMapper.selectList(
                new LambdaQueryWrapper<SourceRegistry>().orderByDesc(SourceRegistry::getId));
        if (rows == null) {
            return List.of();
        }
        List<SourceRegistryView> views = new ArrayList<>(rows.size());
        for (SourceRegistry row : rows) {
            views.add(SourceRegistryView.of(row, current));
        }
        return List.copyOf(views);
    }

    @Override
    public SourceRegistryView get(Long id) {
        return SourceRegistryView.of(require(id), currentSourceId().orElse(null));
    }

    @Override
    public Optional<Long> currentSourceId() {
        ActiveSourceBindingMapper.ActiveProfileBinding binding = bindingMapper.findActive();
        return binding == null ? Optional.empty() : Optional.ofNullable(binding.sourceId());
    }

    // ---------------------------------------------------------------- 创建 / 修改

    @Override
    @Transactional
    public SourceChangeOutcome create(SourceRegistryCreateReq req) {
        if (req == null) {
            throw param("请求体必填");
        }
        String sourceCode = validateSourceCode(req.sourceCode());
        String displayName = requireText(req.displayName(), "display_name", 128);
        String ingestMode = validateIngestMode(req.ingestMode());
        String profilePath = SourcePathPolicy.requireRepoRelative(req.profilePath(), "profile_path");
        String timezone = requireText(req.timezone(), "timezone", 64);
        String currency = validateCurrency(req.currency());
        String profileVersion = requireText(req.profileVersion(), "profile_version", 32);
        // D-074 挂点①：数仓命名空间前缀。必填 + 形状由唯一判据判（不新写一份规则），
        // 返回的就是**入库值**（WarehouseNamespace 不做归一化，原样存取）。
        String warehousePrefix =
                WarehouseNamespaceProvider.requireSourcePrefix(req.warehousePrefix()).prefix();
        String status = normalize(req.status());
        if (status == null) {
            status = SourceRegistry.STATUS_DRAFT;
        }
        if (!SourceRegistry.CREATABLE_STATUSES.contains(status)) {
            // 排序后再拼：Set 的迭代顺序不保证稳定，两次运行会给出 [DRAFT, PAUSED] / [PAUSED, DRAFT]
            // 两种文本（真机验收实测到），语义相同但会让证据逐字比对变得不可信。
            throw param("status 只允许 " + new TreeSet<>(SourceRegistry.CREATABLE_STATUSES) + "（缺省 DRAFT）；"
                    + "ACTIVE 必须经 /activate 获得，否则会出现未校验画像却已激活的源：" + status);
        }
        if (sourceMapper.countBySourceCode(sourceCode) > 0) {
            throw param("source_code 已存在：" + sourceCode);
        }

        LocalDateTime now = LocalDateTime.now();
        SourceRegistry row = new SourceRegistry();
        row.setSourceCode(sourceCode);
        row.setDisplayName(displayName);
        row.setIngestMode(ingestMode);
        row.setProfilePath(profilePath);
        row.setTimezone(timezone);
        row.setCurrency(currency);
        row.setStatus(status);
        row.setProfileVersion(profileVersion);
        row.setWarehousePrefix(warehousePrefix);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        sourceMapper.insert(row);

        log.info("源登记创建 sourceCode={} id={} status={}", sourceCode, row.getId(), status);
        return SourceChangeOutcome.of(SourceRegistryView.of(sourceMapper.selectById(row.getId()), null), null);
    }

    @Override
    @Transactional
    public SourceChangeOutcome update(Long id, SourceRegistryUpdateReq req) {
        SourceRegistry row = require(id);
        if (req == null) {
            throw param("请求体必填");
        }
        if (isChanged(req.sourceCode(), row.getSourceCode())) {
            throw new PlatformBizException(PlatformBizException.SOURCE_CODE_IMMUTABLE,
                    "source_code 创建后不可修改：现值 " + row.getSourceCode());
        }
        if (isChanged(req.status(), row.getStatus())) {
            throw param("status 不能经 PUT 变更（请用 /activate 或 /pause），现值 " + row.getStatus());
        }

        Long current = currentSourceId().orElse(null);
        SourceRegistryView before = SourceRegistryView.of(row, current);

        // 部分更新：null 字段保持原值（V16 里这些列都是 NOT NULL，"清空"没有意义）
        if (req.displayName() != null) {
            row.setDisplayName(requireText(req.displayName(), "display_name", 128));
        }
        if (req.ingestMode() != null) {
            row.setIngestMode(validateIngestMode(req.ingestMode()));
        }
        if (req.profilePath() != null) {
            row.setProfilePath(SourcePathPolicy.requireRepoRelative(req.profilePath(), "profile_path"));
        }
        if (req.timezone() != null) {
            row.setTimezone(requireText(req.timezone(), "timezone", 64));
        }
        if (req.currency() != null) {
            row.setCurrency(validateCurrency(req.currency()));
        }
        if (req.profileVersion() != null) {
            row.setProfileVersion(requireText(req.profileVersion(), "profile_version", 32));
        }
        // D-074 挂点②：只挂 create 会被 PUT 绕过 ⇒ update 同样过唯一判据。
        // 语义与其余字段一致：null = 保持原值；显式写空白/非法值 = 拒绝（不是"改成缺省"）。
        if (req.warehousePrefix() != null) {
            row.setWarehousePrefix(
                    WarehouseNamespaceProvider.requireSourcePrefix(req.warehousePrefix()).prefix());
        }
        row.setUpdatedAt(LocalDateTime.now());
        sourceMapper.updateById(row);

        log.info("源登记修改 id={} sourceCode={}", id, row.getSourceCode());
        return SourceChangeOutcome.of(SourceRegistryView.of(sourceMapper.selectById(id), current), before);
    }

    // ---------------------------------------------------------------- 只读校验

    @Override
    public SourceCheckResult test(Long id) {
        SourceRegistry row = require(id);
        List<SourceCheckResult.CheckItem> items = new ArrayList<>(7);

        boolean pathOk = SourcePathPolicy.isRepoRelative(row.getProfilePath());
        items.add(new SourceCheckResult.CheckItem("profile_path_policy", pathOk, true,
                pathOk ? "profile_path 是仓库相对路径：" + row.getProfilePath()
                        : "profile_path 违反仓库相对路径策略（值已脱敏，未回显）"));

        SourceProfileValidator.ProfileCheck check =
                profileValidator.check(row.getProfilePath(), row.getSourceCode(), row.getProfileVersion());
        boolean canInspect = check.exists() && check.jsonObject();
        String notApplicable = "前置项未通过，无法评估";
        // 不适用（applicable=false）的项 passed 恒为 false：**"没评估"不等于"通过"**。
        // 否则种子源会呈现出 profile_required_top_level_keys=passed 这种"没读文件却报通过"的假绿。
        // 每一项的 detail 只讲这一项自己的事实（真机验收发现：原先所有项共用 check.detail()，
        // 于是 passed=true 的 profile_profile_version_matches 会说"sourceCode 与登记不一致"，
        // 输出自相矛盾、无法据此定位问题）。
        items.add(new SourceCheckResult.CheckItem("profile_file_exists", check.exists(), true,
                check.exists() ? "画像文件存在且可读：" + row.getProfilePath() : check.detail()));
        items.add(new SourceCheckResult.CheckItem("profile_json_object", check.jsonObject(), check.exists(),
                canInspect ? "顶层是合法 JSON 对象：" + row.getProfilePath() : check.detail()));
        items.add(new SourceCheckResult.CheckItem("profile_source_code_matches", check.sourceCodeMatches(),
                canInspect, canInspect
                        ? (check.sourceCodeMatches()
                                ? "画像 sourceCode 与登记一致：" + row.getSourceCode()
                                : "画像 sourceCode 与登记不一致：登记=" + row.getSourceCode()
                                        + " 文件=" + check.actualSourceCode())
                        : notApplicable));
        items.add(new SourceCheckResult.CheckItem("profile_profile_version_matches", check.profileVersionMatches(),
                canInspect, canInspect
                        ? (check.profileVersionMatches()
                                ? "画像 profileVersion 与登记一致：" + row.getProfileVersion()
                                : "画像 profileVersion 与登记不一致：登记=" + row.getProfileVersion()
                                        + " 文件=" + check.actualProfileVersion())
                        : notApplicable));
        items.add(new SourceCheckResult.CheckItem("profile_required_top_level_keys",
                canInspect && check.missingKeys().isEmpty(),
                canInspect, canInspect
                        ? (check.missingKeys().isEmpty()
                                ? "设计 §4.2 的 " + SourceProfileValidator.REQUIRED_TOP_LEVEL_KEYS.size() + " 个顶层必备键齐全"
                                : "画像缺设计 §4.2 顶层必备键：" + String.join(",", check.missingKeys()))
                        : notApplicable));

        // 第七项＝activate 的第三个失败面：状态是否允许变更（DRAFT/ACTIVE/PAUSED 可以，DISABLED 不行）
        boolean mutable = lifecycleMutable(row.getStatus());
        items.add(new SourceCheckResult.CheckItem("status_transition_allowed", mutable, true,
                mutable ? "当前状态允许生命周期变更：" + row.getStatus()
                        : "当前状态不允许生命周期变更（本期未实现的状态迁移入口）：" + row.getStatus()));

        boolean ok = items.stream().filter(SourceCheckResult.CheckItem::applicable)
                .allMatch(SourceCheckResult.CheckItem::passed);
        return new SourceCheckResult(row.getId(), row.getSourceCode(), ok, List.copyOf(items));
    }
    // ---------------------------------------------------------------- 激活 / 暂停

    @Override
    @Transactional
    public SourceChangeOutcome activate(Long id) {
        SourceRegistry row = require(id);

        // ① 先取全局串行化锁：唯一 ACTIVE runtime_profile 行（此后到提交为止，"当前源"不会被别人改掉）
        ActiveSourceBindingMapper.ActiveProfileBinding binding = bindingMapper.lockActive();
        if (binding == null) {
            throw param("尚无 ACTIVE 运行环境，无法绑定当前源（源状态未改动）");
        }

        // ② **锁内重读**这一行：决策必须基于最新已提交版本，否则并发下会把别人的变更看成没发生
        //    （REPEATABLE READ 的一致性快照会让普通 SELECT 一直返回旧版本，故用 FOR UPDATE，见 mapper 注释）
        row = requireLocked(id);
        requireLifecycleMutable(row);

        // D-074 挂点③：激活是"这个源开始被用"的开关，库名就此生效 ⇒ 库里存着的前缀必须合法。
        // 位置与画像校验同理：放在幂等短路**之前**，否则"已是当前源但前缀非法"会被幂等吞掉。
        // 正常路径下该值必经 create/update 判据（写入侧），此处是防止历史行/人工改库造成的坏值放行。
        WarehouseNamespaceProvider.requireSourcePrefix(row.getWarehousePrefix());

        // ③ 画像校验在幂等判断**之前**：否则"已是当前源但画像缺失"会被幂等短路成成功，
        //    种子源 mock-mall 的预期结果（SOURCE_PROFILE_INVALID）就取不到了（D-035 裁决 11）。
        SourceProfileValidator.ProfileCheck check =
                profileValidator.check(row.getProfilePath(), row.getSourceCode(), row.getProfileVersion());
        if (!check.ok()) {
            throw new PlatformBizException(PlatformBizException.SOURCE_PROFILE_INVALID,
                    "源画像校验未通过：" + check.detail());
        }
        SourceRegistryView before = SourceRegistryView.of(row, binding.sourceId());

        // ④ 幂等：已是当前源且已是 ACTIVE → 成功返回，零写入（控制器据此不写重复审计）
        if (id.equals(binding.sourceId()) && SourceRegistry.STATUS_ACTIVE.equals(row.getStatus())) {
            return SourceChangeOutcome.unchanged(before);
        }

        row.setStatus(SourceRegistry.STATUS_ACTIVE);
        row.setUpdatedAt(LocalDateTime.now());
        sourceMapper.updateById(row);

        int bound = bindingMapper.bind(binding.profileId(), id);
        if (bound != 1) {
            throw new PlatformBizException(PlatformBizException.INTERNAL,
                    "绑定当前源失败：ACTIVE 运行环境行 " + binding.profileId() + " 已被并发修改");
        }

        log.info("源激活 sourceCode={} id={} 绑定 runtime_profile.id={}", row.getSourceCode(), id, binding.profileId());
        return SourceChangeOutcome.of(SourceRegistryView.of(sourceMapper.selectById(id), id), before);
    }

    @Override
    @Transactional
    public SourceChangeOutcome pause(Long id) {
        require(id);

        ActiveSourceBindingMapper.ActiveProfileBinding binding = bindingMapper.lockActive();
        SourceRegistry row = requireLocked(id);
        requireLifecycleMutable(row);

        Long current = binding == null ? null : binding.sourceId();
        SourceRegistryView before = SourceRegistryView.of(row, current);

        if (id.equals(current)) {
            throw new PlatformBizException(PlatformBizException.SOURCE_IN_USE,
                    "该源是当前激活源，暂停前请先切换当前源（源状态未改动）：" + row.getSourceCode());
        }
        if (SourceRegistry.STATUS_PAUSED.equals(row.getStatus())) {
            return SourceChangeOutcome.unchanged(before);
        }

        row.setStatus(SourceRegistry.STATUS_PAUSED);
        row.setUpdatedAt(LocalDateTime.now());
        sourceMapper.updateById(row);

        log.info("源暂停 sourceCode={} id={}", row.getSourceCode(), id);
        return SourceChangeOutcome.of(SourceRegistryView.of(sourceMapper.selectById(id), current), before);
    }

    // ---------------------------------------------------------------- 内部

    private SourceRegistry require(Long id) {
        if (id == null) {
            throw param("源 id 必填");
        }
        SourceRegistry row = sourceMapper.selectById(id);
        if (row == null) {
            throw new PlatformBizException(PlatformBizException.SOURCE_NOT_FOUND, "源不存在: " + id);
        }
        return row;
    }

    /**
     * 锁内重读：拿到串行化锁之后，用**锁定读**取这一行的最新已提交版本
     * （普通 SELECT 在 REPEATABLE READ 下会一直返回事务第一次读时的旧快照）。
     * 顺带覆盖"第一次 {@link #require} 之后、抢到锁之前这一行被删掉"的窗口。
     */
    private SourceRegistry requireLocked(Long id) {
        SourceRegistry row = sourceMapper.lockById(id);
        if (row == null) {
            throw new PlatformBizException(PlatformBizException.SOURCE_NOT_FOUND, "源不存在: " + id);
        }
        return row;
    }

    /**
     * DISABLED 是读取层接受的历史值，本期**没有**任何写入方会产生它，也没有"复活"入口。
     * 对处于该状态的行执行生命周期变更一律 fail-closed 拒绝——
     * 静默把它改成 ACTIVE/PAUSED 等于让一个已被人工下线的源悄悄回到在用集合里。
     * （D-035 未覆盖"从 DISABLED 迁出"的语义，此处的选择作为待裁决点上报。）
     *
     * <p>这个判断的**唯一所有者**是 {@link #lifecycleMutable(String)}：
     * {@code activate}/{@code pause} 的拒绝与 {@code /test} 的
     * {@code status_transition_allowed} 检查项必须永远同源，否则"校验通过却激活失败"会重新出现。</p>
     */
    private void requireLifecycleMutable(SourceRegistry row) {
        if (!lifecycleMutable(row.getStatus())) {
            throw param("源处于 DISABLED 状态（本期未实现的状态迁移入口），拒绝变更：" + row.getSourceCode());
        }
    }

    private static boolean lifecycleMutable(String status) {
        return !SourceRegistry.STATUS_DISABLED.equals(status);
    }

    private static boolean isChanged(String requested, String current) {
        return requested != null && !requested.isBlank() && !requested.trim().equals(current);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String validateSourceCode(String value) {
        String code = requireText(value, "source_code", 64);
        if (!code.matches(SourceRegistry.SOURCE_CODE_PATTERN)) {
            throw param("source_code 必须匹配 " + SourceRegistry.SOURCE_CODE_PATTERN
                    + "（小写字母开头，仅小写字母/数字/连字符，长度 2–64）：" + code);
        }
        return code;
    }

    private static String validateIngestMode(String value) {
        String mode = value == null || value.isBlank() ? SourceRegistry.INGEST_MODE_FILE
                : value.trim().toUpperCase(Locale.ROOT);
        if (!SourceRegistry.SUPPORTED_INGEST_MODES.contains(mode)) {
            String hint = SourceRegistry.RESERVED_INGEST_MODES.contains(mode)
                    ? "（预留能力，本期未实现，登记层不假装支持）" : "";
            throw param("ingest_mode 本期唯一实现 FILE" + hint + "：" + mode);
        }
        return mode;
    }

    private static String validateCurrency(String value) {
        String currency = requireText(value, "currency", 3).toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw param("currency 必须是 ISO 4217 三字母代码：" + currency);
        }
        return currency;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw param(field + " 必填");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw param(field + " 超过 " + maxLength + " 字符（与 V16 列宽一致）");
        }
        return trimmed;
    }

    private static PlatformBizException param(String message) {
        return new PlatformBizException(PlatformBizException.PARAM_INVALID, message);
    }
}
