package com.graduation.analytics.mapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 已装载、已静态校验的映射画像（设计 §7.2 最小字段集的机器形状）。
 *
 * <p>字段名尽量沿用设计原文：{@code profileVersion}、{@code sourceCode}、{@code contractVersion}、
 * {@code eventTypeMappings}、{@code fieldMappings}（信封/载荷分组）、{@code enumSemantics}、
 * {@code timePolicy}、{@code amountPolicy}。设计里的 {@code checksum} 取 {@code profileChecksum}
 * （装载时按画像原文 sha256 计算，不读画像里的 checksum 字段）；v2 画像若在顶层声明 {@code checksum}
 * 即 {@code PROFILE_INVALID}（权威哈希没有自声明形式，避免「checksum 覆盖哪些字节」的循环定义）。</p>
 *
 * <p><b>必填策略只有唯一来源</b>：canonical 契约（{@code schema.required}）。画像**不再**声明
 * {@code requiredPolicy} 之类的必填旋钮——S2-01A.1 已删除该字段，避免第二份必填清单与契约漂移。</p>
 *
 * <p>{@link #syntax()} 记录画像语法模式：{@code V2_STRICT}（设计 §7.2 形状，未知顶层键/缺 contractVersion/
 * 本地时间缺时区/金额缺单位一律 {@code PROFILE_INVALID}）与 {@code V1_COMPATIBILITY}（既有扁平画像，
 * 只读兼容：允许 {@code canonical.schemaVersion} 回退契约版本、允许载荷容器缺失时回落根，
 * 但身份/隔离策略等未实现能力一律记入 {@link #capabilityGaps()}）。</p>
 *
 * <p>{@code enumSemantics} 的键是 **canonical 字段名**（规则 12「按字段路径」）；v1 画像的
 * {@code enumSemantics.<组名>} 先在装载期绑定到 canonical 字段（见 {@link #enumBindings()}），
 * 绑不上的组记录在 {@link #enumUnboundGroups()} / {@link #enumAmbiguousGroups()}，不猜。</p>
 *
 * <p>{@link #activationBlocks()} 是「可装载但禁止激活」的原因（规则 12：关键字段未决禁止激活；
 * 规则 7：有金额目标却缺 amountPolicy）；{@link #capabilityGaps()} 是不能判为非法、但会让该源
 * 少映射/多隔离的能力缺口，dry-run 报告应展示。</p>
 */
public record MappingProfile(
        String profileVersion,
        String sourceCode,
        String contractVersion,
        ProfileSyntax syntax,
        String profileChecksum,
        String eventTypeSourceField,
        Map<String, String> eventTypeMappings,
        EnvelopeSourceMode envelopeSourceMode,
        Map<String, String> envelopeFieldMappings,
        Map<String, Map<String, String>> payloadFieldMappings,
        Map<String, Map<String, ItemMapSpec>> itemMaps,
        Map<String, Map<String, String>> enumSemantics,
        Map<String, String> enumBindings,
        List<String> enumUnboundGroups,
        List<String> enumAmbiguousGroups,
        TimePolicySpec timePolicy,
        AmountPolicySpec amountPolicy,
        List<String> activationBlocks,
        List<String> capabilityGaps) {

    /** 画像语法模式（v2 = 严格模式，v1 = 只读兼容模式）。 */
    public enum ProfileSyntax {
        /** 设计 §7.2 形状：未知顶层键、缺 contractVersion、本地时间缺时区、金额缺单位都 fail-closed。 */
        V2_STRICT,
        /** 既有 v1 扁平画像：只读兼容，未实现能力进 {@link #capabilityGaps()}，既有文件一字不改。 */
        V1_COMPATIBILITY
    }

    /** 信封来源模式。 */
    public enum EnvelopeSourceMode {
        /** 画像显式声明了信封字段映射，只按声明取（缺声明 ⇒ 必填缺失，不回落同名）。 */
        DECLARED_ONLY,
        /** 画像未声明任何信封映射（v1 扁平画像的现状）：按 canonical 字段名从根直取。 */
        CANONICAL_NAME_IDENTITY
    }

    public MappingProfile {
        Objects.requireNonNull(profileVersion, "profileVersion");
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(syntax, "syntax");
        Objects.requireNonNull(profileChecksum, "profileChecksum");
        Objects.requireNonNull(eventTypeSourceField, "eventTypeSourceField");
        Objects.requireNonNull(envelopeSourceMode, "envelopeSourceMode");
        Objects.requireNonNull(timePolicy, "timePolicy");
        eventTypeMappings = frozen(eventTypeMappings);
        envelopeFieldMappings = frozen(envelopeFieldMappings);
        payloadFieldMappings = frozenNested(payloadFieldMappings);
        Map<String, Map<String, ItemMapSpec>> itemMapCopy = new LinkedHashMap<>();
        itemMaps.forEach((k, v) -> itemMapCopy.put(k, frozen(v)));
        itemMaps = Collections.unmodifiableMap(itemMapCopy);
        enumSemantics = frozenNested(enumSemantics);
        enumBindings = frozen(enumBindings);
        enumUnboundGroups = List.copyOf(enumUnboundGroups);
        enumAmbiguousGroups = List.copyOf(enumAmbiguousGroups);
        activationBlocks = List.copyOf(activationBlocks);
        capabilityGaps = List.copyOf(capabilityGaps);
    }

    public boolean activationBlocked() {
        return !activationBlocks.isEmpty();
    }

    public Map<String, String> payloadFieldMappings(String eventType) {
        return payloadFieldMappings.getOrDefault(eventType, Map.of());
    }

    public Map<String, ItemMapSpec> itemMaps(String eventType) {
        return itemMaps.getOrDefault(eventType, Map.of());
    }

    /** 反向查信封来源：canonical 信封字段名 → 源路径。 */
    public String envelopeSourceFor(String envelopeTarget) {
        for (Map.Entry<String, String> entry : envelopeFieldMappings.entrySet()) {
            if (entry.getValue().equals(envelopeTarget)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static <K, V> Map<K, V> frozen(Map<K, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static <K, V> Map<K, Map<K, V>> frozenNested(Map<K, Map<K, V>> map) {
        Map<K, Map<K, V>> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(k, Collections.unmodifiableMap(new LinkedHashMap<>(v))));
        return Collections.unmodifiableMap(copy);
    }
}
