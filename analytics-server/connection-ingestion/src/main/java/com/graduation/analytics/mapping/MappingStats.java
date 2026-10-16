package com.graduation.analytics.mapping;

import java.util.Map;

/**
 * 单事件映射的统计事实（设计 §7.3 规则 13）。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>{@code reasonCounts} 按**违例条数**计，因此可大于隔离行数（一个事件多个违例全部计入）；
 *       只放非零项，避免把「无违例」伪装成 0 计数。</li>
 *   <li>{@code requiredCoverage = 成功必填位置 / 总必填位置}；分母为 0 时记 {@code null}（不写 0.0）。
 *       总量只含契约必填且**非平台生成**的字段：信封 7 个（ingest_time 由平台 generation 层产出，见 S2-02）+ 该类事件的载荷必填字段。</li>
 *   <li>{@code enumCoverage} 按「字段路径 + 原始枚举值」去重统计（同一字段同一原始值只算一对）；
 *       分母为 0 时记 {@code null}。已裁定的第三态（null）算**未解决**对。</li>
 *   <li>{@code itemMapMode} ∈ NONE | MAPPED | PASSTHROUGH_STRING（D-063：字符串形态 items 直通，解析归一属 DWD）。</li>
 *   <li>{@code payloadBase} ∈ ROOT | PAYLOAD_CONTAINER，记录载荷来源的解析基准，便于对账。</li>
 *   <li>{@code optionalOmitted} 恒为 0：必填唯一来源是 canonical 契约，契约 1.0 的载荷字段全部必填，
 *       S2-01A.1 起画像不再声明 {@code requiredPolicy}，因此不存在「可选字段被省略」的情形。</li>
 * </ul>
 */
public record MappingStats(
        Map<MappingReason, Integer> reasonCounts,
        int requiredPositionsTotal,
        int requiredPositionsOk,
        Double requiredCoverage,
        int enumPairsObserved,
        int enumPairsResolved,
        Double enumCoverage,
        String eventTimeFormatUsed,
        int eventTimeFormatsMatched,
        int amountRounded,
        int keptExtensions,
        int optionalOmitted,
        int itemsMapped,
        String itemMapMode,
        String payloadBase) {

    public static final String ITEM_MAP_NONE = "NONE";
    public static final String ITEM_MAP_MAPPED = "MAPPED";
    public static final String ITEM_MAP_PASSTHROUGH_STRING = "PASSTHROUGH_STRING";
    public static final String BASE_ROOT = "ROOT";
    public static final String BASE_PAYLOAD_CONTAINER = "PAYLOAD_CONTAINER";
}
