package com.graduation.analytics.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-01A 确定性映射执行器：设计 §7.3 的 14 条规则的正/负用例。
 *
 * <p>纯转换：无 Controller、无 dry-run 端点、无 DB、无 checkpoint、无 Spark、无时钟/随机源；
 * 同一输入 + 同一画像必须得到完全相同的 {@link MappingOutcome}（含两个校验和）。</p>
 */
class MappingExecutorTest {

    private static final String BODY_OK =
            "\"buyer\":1,\"item\":\"7\",\"visit\":\"s-1\",\"act\":\"BROWSE\",\"term\":\"APP\",\"ext_coupon\":\"C-9\"";

    private static String behavior(String body) {
        return "{\"msg_id\":\"e-1\",\"msg_kind\":\"browse\",\"occurred\":\"2026-09-20T11:00:00+08:00\","
                + "\"origin\":\"s2-src\",\"rev\":\"1.0\",\"trc\":\"t-1\",\"body\":{" + body + "}}";
    }

    private static String orderCreated(String lines, String totalFen) {
        return "{\"id\":\"e-2\",\"kind\":\"order_new_se\",\"at\":\"2026-09-20T12:00:00+08:00\",\"sys\":\"src\","
                + "\"rev\":\"1.0\",\"tr\":\"t-2\",\"data\":{\"ord\":\"O-1\",\"buyer\":\"U-1\",\"lines\":" + lines
                + ",\"total_fen\":" + totalFen + ",\"state\":\"CREATED\",\"placed\":\"2026-09-20T12:00:00+08:00\"}}";
    }

    private static String orderPaid(String localTs, String paid) {
        return "{\"id\":\"e-3\",\"kind\":\"pay\",\"local_ts\":\"" + localTs + "\",\"sys\":\"src\",\"rev\":\"1.0\","
                + "\"tr\":\"t-3\",\"data\":{\"ord\":\"O-1\",\"buyer\":\"U-1\",\"pay\":\"P-1\",\"paid\":\"" + paid
                + "\",\"paid_at\":\"2026-01-05T14:00:00-05:00\"}}";
    }

    private static String inlineProfile(String timeJson, String amountJson) {
        return inlineProfile("browse", "behavior",
                "\"buyer\": \"user_id\", \"item\": \"product_id\", \"visit\": \"session_id\", "
                        + "\"act\": \"behavior_type\", \"term\": \"channel\"",
                timeJson, amountJson);
    }

    private static String inlineProfile(String declaredSource,
                                       String canonicalType,
                                       String payloadFields,
                                       String timeJson,
                                       String amountJson) {
        return """
                {
                  "profileVersion": "2.0", "sourceCode": "inline", "contractVersion": "1.0",
                  "eventTypeMappings": { "sourceField": "kind", "values": { "%s": "%s" } },
                  "fieldMappings": {
                    "envelope": { "id": "event_id", "kind": "event_type", "at": "event_time", "sys": "source_system",
                                  "rev": "schema_version", "tr": "trace_id", "data": "payload" },
                    "payload": { "%s": { %s } }
                  },
                  "enumSemantics": {},
                  "timePolicy": %s
                  %s
                }
                """.formatted(declaredSource, canonicalType, canonicalType, payloadFields, timeJson, amountJson);
    }

    private static String inlineRaw(String at) {
        return "{\"id\":\"e-9\",\"kind\":\"browse\",\"at\":\"" + at + "\",\"sys\":\"src\",\"rev\":\"1.0\","
                + "\"tr\":\"t-9\",\"data\":{\"buyer\":\"U-1\",\"item\":\"P-1\",\"visit\":\"s-9\","
                + "\"act\":\"view\",\"term\":\"app\"}}";
    }

    // ---------- 规则2/3：字段映射与扩展保留 ----------

    @Test
    @DisplayName("规则2/8：信封+载荷正常映射，必填/枚举覆盖率=1，缺 ingest_time 只登记为平台待生成")
    void mapsEnvelopeAndPayloadHappyPath() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json", behavior(BODY_OK));

        assertThat(outcome.quarantined()).isFalse();
        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.canonical().path("event_id").asText()).isEqualTo("e-1");
        assertThat(outcome.canonical().path("event_type").asText()).isEqualTo("behavior");
        assertThat(outcome.canonical().path("event_time").asText()).isEqualTo("2026-09-20T11:00:00+08:00");
        assertThat(outcome.canonical().path("source_system").asText()).isEqualTo("s2-src");
        assertThat(outcome.canonical().path("schema_version").asText()).isEqualTo("1.0");
        assertThat(outcome.canonical().path("trace_id").asText()).isEqualTo("t-1");
        assertThat(MappingTestSupport.payloadText(outcome, "user_id")).isEqualTo("1");
        assertThat(MappingTestSupport.payloadText(outcome, "product_id")).isEqualTo("7");
        assertThat(MappingTestSupport.payloadText(outcome, "session_id")).isEqualTo("s-1");
        assertThat(MappingTestSupport.payloadText(outcome, "behavior_type")).isEqualTo("view");
        assertThat(MappingTestSupport.payloadText(outcome, "channel")).isEqualTo("app");
        assertThat(outcome.stats().requiredCoverage()).isEqualTo(1.0);
        assertThat(outcome.stats().enumCoverage()).isEqualTo(1.0);
        assertThat(outcome.stats().reasonCounts()).isEmpty();
        assertThat(outcome.stats().eventTimeFormatUsed()).isEqualTo("ISO_OFFSET_DATE_TIME");
        assertThat(outcome.stats().eventTimeFormatsMatched()).isEqualTo(1);
        assertThat(outcome.pendingPlatformFields()).containsExactly("ingest_time");
        assertThat(outcome.canonical().has("ingest_time")).isFalse();
        assertThat(outcome.rawChecksum()).matches("[0-9a-f]{64}");
        assertThat(outcome.canonicalChecksum()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("规则8：JSON 数字型身份 ID 规范为字符串（1 ⇒ \"1\"），不做数值补零/浮点化")
    void numberIdentityIsNormalizedToString() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior(BODY_OK.replace("\"buyer\":1", "\"buyer\":10001")));

        assertThat(MappingTestSupport.payloadText(outcome, "user_id")).isEqualTo("10001");
        assertThat(outcome.canonical().path("payload").path("user_id").isTextual()).isTrue();
    }

    @Test
    @DisplayName("规则3：@keep 按原始叶子名保留扩展，不参与规范字段归一")
    void keepExtensionKeptByLeafName() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json", behavior(BODY_OK));

        assertThat(MappingTestSupport.payloadText(outcome, "ext_coupon")).isEqualTo("C-9");
        assertThat(outcome.stats().keptExtensions()).isEqualTo(1);
    }

    @Test
    @DisplayName("规则3：@keep 叶子名撞 canonical 字段名 ⇒ 静态可判，装载期即 PROFILE_INVALID")
    void keepCollidingWithCanonicalFieldIsRejected() {
        String profile = """
                {
                  "profileVersion": "2.0", "sourceCode": "inline-keep", "contractVersion": "1.0",
                  "eventTypeMappings": { "sourceField": "msg_kind", "values": { "browse": "behavior" } },
                  "fieldMappings": {
                    "envelope": { "msg_id": "event_id", "msg_kind": "event_type", "occurred": "event_time",
                                  "origin": "source_system", "rev": "schema_version", "trc": "trace_id", "body": "payload" },
                    "payload": { "behavior": { "buyer": "user_id", "item": "product_id", "visit": "session_id",
                                               "act": "behavior_type", "term": "channel", "channel": "@keep" } }
                  },
                  "enumSemantics": {}, "timePolicy": { "field": "occurred", "formats": ["ISO_OFFSET_DATE_TIME"] }
                }
                """;

        MappingProfileLoad load = MappingTestSupport.load(profile, "inline/keep-conflict.v2.json");

        assertThat(load.ok()).isFalse();
        assertThat(load.profile()).isNull();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.detail()).contains("channel").contains("KEEP");
    }

    // ---------- 规则12：枚举三态 ----------

    @Test
    @DisplayName("规则12：已裁定枚举正常映射（BROWSE ⇒ view）")
    void enumResolvedValueMapped() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json", behavior(BODY_OK));

        assertThat(MappingTestSupport.payloadText(outcome, "behavior_type")).isEqualTo("view");
        assertThat(outcome.warnings()).isEmpty();
    }

    @Test
    @DisplayName("规则12：声明 null = 观察到但未裁定 ⇒ 保留 null + warning，不隔离")
    void enumNullIsWarningThirdState() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior(BODY_OK.replace("\"act\":\"BROWSE\"", "\"act\":\"PURCHASE\"")));

        assertThat(outcome.quarantined()).isFalse();
        assertThat(MappingTestSupport.payloadText(outcome, "behavior_type")).isNull();
        assertThat(MappingTestSupport.has(outcome.warnings(), MappingReason.ENUM_UNRESOLVED)).isTrue();
        MappingIssue warning = MappingTestSupport.issue(outcome.warnings(), MappingReason.ENUM_UNRESOLVED);
        assertThat(warning.path()).isEqualTo("payload.behavior_type");
        assertThat(warning.detail()).contains("PURCHASE");
        assertThat(outcome.stats().enumCoverage()).isEqualTo(0.5);
        assertThat(outcome.stats().requiredCoverage()).isLessThan(1.0);
    }

    @Test
    @DisplayName("规则12：完全没登记的枚举值 ⇒ BAD_ENUM + 隔离（不用『未知=成功』吞掉）")
    void unregisteredEnumValueIsBadEnum() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior(BODY_OK.replace("\"act\":\"BROWSE\"", "\"act\":\"CLICK\"")));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.BAD_ENUM);
        assertThat(issue.path()).isEqualTo("payload.behavior_type");
        assertThat(issue.detail()).contains("CLICK");
        assertThat(outcome.stats().enumCoverage()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("规则12：枚举值合法但不在契约枚举内的直通值 ⇒ BAD_ENUM（无声明组时按契约校验）")
    void verbatimEnumValueOutsideContractIsBadEnum() {
        MappingOutcome outcome = MappingTestSupport.executeText(
                inlineProfile("{ \"field\": \"at\", \"formats\": [\"ISO_OFFSET_DATE_TIME\"] }", ""),
                "inline/verbatim-enum.v2.json", inlineRaw("2026-09-20T11:00:00+08:00").replace("\"term\":\"app\"", "\"term\":\"web\""));

        assertThat(outcome.quarantined()).isTrue();
        assertThat(MappingTestSupport.issue(outcome.violations(), MappingReason.BAD_ENUM).detail()).contains("web");
    }

    // ---------- 规则4：时间 ----------

    @Test
    @DisplayName("规则4：有序 formats 首个成功者生效；两个同时匹配时按顺序取第一个（不随机）")
    void orderedTimeFormatsFirstMatchWins() {
        String profile = inlineProfile(
                "{ \"field\": \"at\", \"formats\": [\"dd/MM/yyyy HH:mm:ss\", \"MM/dd/yyyy HH:mm:ss\"], \"zone\": \"UTC\" }",
                "");

        MappingOutcome outcome = MappingTestSupport.executeText(profile, "inline/time-order.v2.json",
                inlineRaw("05/03/2026 14:00:00"));

        assertThat(outcome.quarantined()).isFalse();
        assertThat(outcome.canonical().path("event_time").asText()).contains("2026-03-05T14:00:00");
        assertThat(outcome.stats().eventTimeFormatUsed()).isEqualTo("dd/MM/yyyy HH:mm:ss");
        assertThat(outcome.stats().eventTimeFormatsMatched()).isEqualTo(2);
    }

    @Test
    @DisplayName("规则4：所有声明格式都不匹配 ⇒ BAD_TIME_FORMAT + 隔离")
    void badTimeFormatIsQuarantined() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior(BODY_OK).replace("2026-09-20T11:00:00+08:00", "20-09-2026 11:00"));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.BAD_TIME_FORMAT);
        assertThat(issue.path()).isEqualTo("event_time");
        assertThat(outcome.stats().eventTimeFormatUsed()).isNull();
    }

    @Test
    @DisplayName("规则4：IANA 时区 DST 跳空（2026-03-08T02:30 America/New_York）⇒ TIME_AMBIGUOUS_LOCAL")
    void dstGapIsTimeAmbiguousLocal() {
        MappingOutcome outcome = MappingTestSupport.execute("order-yuan-dst.v2.json",
                orderPaid("2026-03-08T02:30:00", "19.99"));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.TIME_AMBIGUOUS_LOCAL);
        assertThat(issue.detail()).isEqualTo("DST_GAP");
        assertThat(issue.path()).isEqualTo("event_time");
    }

    @Test
    @DisplayName("规则4：IANA 时区 DST 重叠（2026-11-01T01:30 America/New_York）⇒ TIME_AMBIGUOUS_LOCAL")
    void dstOverlapIsTimeAmbiguousLocal() {
        MappingOutcome outcome = MappingTestSupport.execute("order-yuan-dst.v2.json",
                orderPaid("2026-11-01T01:30:00", "19.99"));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.TIME_AMBIGUOUS_LOCAL);
        assertThat(issue.detail()).isEqualTo("DST_OVERLAP");
    }

    @Test
    @DisplayName("规则4：本地时间按声明时区规范化（America/New_York ⇒ -05:00），原文另存")
    void localTimeIsNormalizedToDeclaredZone() {
        MappingOutcome outcome = MappingTestSupport.execute("order-yuan-dst.v2.json",
                orderPaid("2026-01-05T14:00:00", "19.99"));

        assertThat(outcome.quarantined()).isFalse();
        assertThat(outcome.canonical().path("event_time").asText()).isEqualTo("2026-01-05T14:00:00-05:00");
        assertThat(outcome.raw().path("local_ts").asText()).isEqualTo("2026-01-05T14:00:00");
    }

    @Test
    @DisplayName("规则4：EPOCH_MILLIS 必须显式声明；时区由画像明文声明（Asia/Shanghai）后按它规范化")
    void epochMillisDeclaredFormatNormalizesToBusinessZone() {
        String profile = inlineProfile(
                "{ \"field\": \"at\", \"formats\": [\"EPOCH_MILLIS\"], \"zone\": \"Asia/Shanghai\" }", "");

        MappingOutcome outcome = MappingTestSupport.executeText(profile, "inline/epoch.v2.json",
                inlineRaw("1789869600000"));

        assertThat(outcome.quarantined()).isFalse();
        assertThat(outcome.canonical().path("event_time").asText()).startsWith("2026-09-20T10:00:00+08:00");
        assertThat(outcome.stats().eventTimeFormatUsed()).isEqualTo("EPOCH_MILLIS");
    }

    @Test
    @DisplayName("S2-01A.1：只声明自带偏移格式且不声明时区 ⇒ 保留原文偏移，不再隐式折算业务时区")
    void offsetFormatWithoutZoneKeepsOriginalOffset() {
        String profile = inlineProfile("{ \"field\": \"at\", \"formats\": [\"ISO_OFFSET_DATE_TIME\"] }", "");

        MappingOutcome outcome = MappingTestSupport.executeText(profile, "inline/offset-keep.v2.json",
                inlineRaw("2026-09-20T11:00:00Z"));

        assertThat(outcome.quarantined()).isFalse();
        assertThat(outcome.canonical().path("event_time").asText()).isEqualTo("2026-09-20T11:00:00Z");
    }

    @Test
    @DisplayName("S2-01A.1：v2 声明的载荷容器在 raw 里不存在 ⇒ PROFILE_INVALID（fail-closed，不回落到根）")
    void strictProfileWithoutDeclaredPayloadContainerIsQuarantined() {
        String profile = inlineProfile("{ \"field\": \"at\", \"formats\": [\"ISO_OFFSET_DATE_TIME\"] }", "");
        // 该画像把 data 声明为 payload 容器；这里故意给一个没有 data 的 raw（字段都在根上）
        String raw = "{\"id\":\"e-7\",\"kind\":\"browse\",\"at\":\"2026-09-20T11:00:00+08:00\",\"sys\":\"src\","
                + "\"rev\":\"1.0\",\"tr\":\"t-7\",\"buyer\":\"U-1\",\"item\":\"P-1\",\"visit\":\"s-7\","
                + "\"act\":\"view\",\"term\":\"app\"}";

        MappingOutcome outcome = MappingTestSupport.executeText(profile, "inline/no-container.v2.json", raw);

        assertThat(outcome.quarantined()).isTrue();
        assertThat(outcome.canonical()).isNull();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("payload");
        assertThat(issue.detail()).isEqualTo("PAYLOAD_CONTAINER_NOT_FOUND:data");
        // 只报一个根因违例，不逐字段刷 EMPTY_FIELD 噪音
        assertThat(outcome.violations()).hasSize(1);
    }

    // ---------- 规则7：金额 ----------

    @Test
    @DisplayName("规则7：分（整数）⇒ 元 decimal 字符串，ITEM_MAP 保持元素顺序与数量")
    void fenIntegerConvertedToYuanString() {
        MappingOutcome outcome = MappingTestSupport.execute("order-items-fen.v2.json", orderCreated("""
                [{"sku":"P-1","qty":2,"unit_fen":19900,"disc_fen":0,"amt_fen":39800},
                 {"sku":"P-2","qty":1,"unit_fen":5000,"disc_fen":1000,"amt_fen":4000},
                 {"sku":"P-3","qty":3,"unit_fen":100,"disc_fen":0,"amt_fen":300}]""", "44100"));

        assertThat(outcome.quarantined()).isFalse();
        JsonNode items = outcome.canonical().path("payload").path("items");
        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).path("product_id").asText()).isEqualTo("P-1");
        assertThat(items.get(1).path("product_id").asText()).isEqualTo("P-2");
        assertThat(items.get(2).path("product_id").asText()).isEqualTo("P-3");
        assertThat(items.get(0).path("unit_price").asText()).isEqualTo("199.00");
        assertThat(items.get(0).path("amount").asText()).isEqualTo("398.00");
        assertThat(items.get(1).path("discount").asText()).isEqualTo("10.00");
        assertThat(items.get(2).path("amount").asText()).isEqualTo("3.00");
        assertThat(items.get(1).path("quantity").asInt()).isEqualTo(1);
        assertThat(outcome.canonical().path("payload").path("total_amount").asText()).isEqualTo("441.00");
        assertThat(outcome.stats().itemsMapped()).isEqualTo(3);
        assertThat(outcome.stats().itemMapMode()).isEqualTo("MAPPED");
    }

    @Test
    @DisplayName("规则7：分的值不是整数 ⇒ BAD_AMOUNT(FEN_NOT_INTEGRAL) + 隔离")
    void fenNonIntegralIsBadAmount() {
        MappingOutcome outcome = MappingTestSupport.execute("order-items-fen.v2.json",
                orderCreated("""
                        [{"sku":"P-1","qty":1,"unit_fen":199.5,"disc_fen":0,"amt_fen":199.5}]""", "19950"));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.BAD_AMOUNT);
        assertThat(issue.detail()).isEqualTo("FEN_NOT_INTEGRAL");
    }

    @Test
    @DisplayName("规则7：负金额按契约拒绝 ⇒ BAD_AMOUNT(NEGATIVE) + 隔离")
    void negativeAmountIsBadAmount() {
        MappingOutcome outcome = MappingTestSupport.execute("order-items-fen.v2.json",
                orderCreated("""
                        [{"sku":"P-1","qty":1,"unit_fen":1000,"disc_fen":0,"amt_fen":1000}]""", "-100"));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.BAD_AMOUNT);
        assertThat(issue.path()).isEqualTo("payload.total_amount");
        assertThat(issue.detail()).isEqualTo("NEGATIVE");
    }

    @Test
    @DisplayName("规则7：ITEM_MAP 任一必填项失败 ⇒ 整事件隔离，不留下成功几项")
    void itemMapFailureQuarantinesWholeEvent() {
        MappingOutcome outcome = MappingTestSupport.execute("order-items-fen.v2.json",
                orderCreated("""
                        [{"sku":"P-1","qty":1,"unit_fen":1000,"disc_fen":0,"amt_fen":1000},
                         {"sku":"P-2","qty":1,"unit_fen":99.5,"disc_fen":0,"amt_fen":99.5}]""", "1999"));

        assertThat(outcome.quarantined()).isTrue();
        assertThat(outcome.canonical()).isNull();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.BAD_AMOUNT);
        assertThat(issue.path()).isEqualTo("payload.items[1].unit_price");
        assertThat(issue.detail()).isEqualTo("FEN_NOT_INTEGRAL");
    }

    @Test
    @DisplayName("规则9（D-063）：items 字符串形态直通（解析归一属 DWD），不做逐项金额转换")
    void itemMapStringFormPassesThrough() {
        MappingOutcome outcome = MappingTestSupport.execute("order-items-fen.v2.json",
                orderCreated("\"[{\\\"sku\\\":\\\"P-1\\\",\\\"amt_fen\\\":1000}]\"", "1000"));

        assertThat(outcome.quarantined()).isFalse();
        assertThat(outcome.canonical().path("payload").path("items").isTextual()).isTrue();
        assertThat(outcome.stats().itemMapMode()).isEqualTo("PASSTHROUGH_STRING");
        assertThat(outcome.stats().itemsMapped()).isZero();
    }

    @Test
    @DisplayName("规则7：YUAN 超过两位小数按精确舍入到分（HALF_UP），并登记舍入次数")
    void yuanAmountWithExcessScaleRoundsHalfUp() {
        MappingOutcome outcome = MappingTestSupport.execute("order-yuan-dst.v2.json",
                orderPaid("2026-01-05T14:00:00", "199.005"));

        assertThat(outcome.quarantined()).isFalse();
        assertThat(MappingTestSupport.payloadText(outcome, "amount")).isEqualTo("199.01");
        assertThat(outcome.stats().amountRounded()).isEqualTo(1);
        assertThat(outcome.stats().enumCoverage()).isNull();
    }

    @Test
    @DisplayName("规则7：YUAN 金额统一为两位小数（199 ⇒ 199.00），枚举覆盖率分母 0 记 null")
    void yuanAmountNormalizedToTwoDecimals() {
        MappingOutcome outcome = MappingTestSupport.execute("order-yuan-dst.v2.json",
                orderPaid("2026-01-05T14:00:00", "199"));

        assertThat(MappingTestSupport.payloadText(outcome, "amount")).isEqualTo("199.00");
        assertThat(outcome.stats().amountRounded()).isZero();
        assertThat(outcome.stats().enumCoverage()).isNull();
        assertThat(outcome.stats().requiredCoverage()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("S2-01A.1：金额单位按源字段声明（paid=YUAN），无画像级默认单位")
    void bySourceFieldUnitApplies() {
        String raw = "{\"id\":\"e-4\",\"kind\":\"browse\",\"at\":\"2026-09-20T11:00:00+08:00\",\"sys\":\"src\","
                + "\"rev\":\"1.0\",\"tr\":\"t-4\",\"data\":{\"ord\":\"O-1\",\"buyer\":\"U-1\",\"pay\":\"P-1\","
                + "\"paid\":\"19.9\",\"paid_at\":\"2026-09-20T11:00:00+08:00\",\"paid_yuan_ext\":\"0\"}}";
        // 冻结形状：只有 bySourceField；paid（源字段名）声明为 YUAN ⇒ 19.9 元 ⇒ "19.90"
        String orderProfile = inlineProfile("browse", "order_paid",
                "\"ord\": \"order_id\", \"buyer\": \"user_id\", \"pay\": \"payment_id\", "
                        + "\"paid\": \"amount\", \"paid_at\": \"paid_at\"",
                "{ \"field\": \"at\", \"formats\": [\"ISO_OFFSET_DATE_TIME\"] }",
                ", \"amountPolicy\": { \"bySourceField\": { \"paid\": \"YUAN\" } }");
        assertThat(orderProfile).contains("\"order_paid\": {").contains("\"paid\": \"amount\"");

        MappingOutcome outcome = MappingTestSupport.executeText(orderProfile, "inline/amount-by-field.v2.json", raw);

        assertThat(outcome.quarantined()).isFalse();
        assertThat(MappingTestSupport.payloadText(outcome, "amount")).isEqualTo("19.90");
    }

    @Test
    @DisplayName("S2-01A.1：v2 有金额目标却缺该源字段单位 ⇒ 装载期即 PROFILE_INVALID（不给无单位画像跑的机会）")
    void missingAmountUnitIsRejectedAtLoadTime() {
        MappingProfileLoad load = MappingTestSupport.load(
                MappingTestSupport.resourceText("order-no-amount-policy.v2.json"),
                "s2-01a/order-no-amount-policy.v2.json");

        assertThat(load.ok()).isFalse();
        assertThat(load.profile()).isNull();
        MappingIssue issue = MappingTestSupport.issue(load.issues(), MappingReason.PROFILE_INVALID);
        assertThat(issue.path()).isEqualTo("amountPolicy.bySourceField.paid_fen");
        assertThat(issue.detail()).isEqualTo("MISSING_AMOUNT_POLICY");
    }

    // ---------- 规则5：缺失三态 ----------

    @Test
    @DisplayName("规则5：必填缺键 ⇒ EMPTY_FIELD(MISSING) + 隔离")
    void missingRequiredFieldIsEmptyFieldMissing() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior("\"buyer\":1,\"visit\":\"s-1\",\"act\":\"BROWSE\",\"term\":\"APP\""));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.EMPTY_FIELD);
        assertThat(issue.path()).isEqualTo("payload.product_id");
        assertThat(issue.detail()).isEqualTo("MISSING");
        assertThat(outcome.stats().requiredCoverage()).isLessThan(1.0);
    }

    @Test
    @DisplayName("规则5：必填显式 null ⇒ EMPTY_FIELD(NULL) + 隔离")
    void nullRequiredFieldIsEmptyFieldNull() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior("\"buyer\":1,\"item\":null,\"visit\":\"s-1\",\"act\":\"BROWSE\",\"term\":\"APP\""));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.EMPTY_FIELD);
        assertThat(issue.path()).isEqualTo("payload.product_id");
        assertThat(issue.detail()).isEqualTo("NULL");
    }

    @Test
    @DisplayName("规则5：必填 trim 后空白 ⇒ EMPTY_FIELD(BLANK) + 隔离（不隐式补0/补值）")
    void blankRequiredFieldIsEmptyFieldBlank() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior("\"buyer\":1,\"item\":\"   \",\"visit\":\"s-1\",\"act\":\"BROWSE\",\"term\":\"APP\""));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.EMPTY_FIELD);
        assertThat(issue.detail()).isEqualTo("BLANK");
    }

    @Test
    @DisplayName("规则13：reasonCounts 按违例计（可大于隔离行数），一个事件多个违例全部记录")
    void reasonCountsCountViolationsNotRows() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior("\"buyer\":1,\"item\":\"   \",\"visit\":\"s-1\",\"act\":\"CLICK\",\"term\":\"APP\""));

        assertThat(outcome.quarantined()).isTrue();
        assertThat(outcome.violations()).hasSize(2);
        assertThat(outcome.stats().reasonCounts())
                .containsEntry(MappingReason.EMPTY_FIELD, 1)
                .containsEntry(MappingReason.BAD_ENUM, 1);
    }

    // ---------- 规则6：错误码 ----------

    @Test
    @DisplayName("规则6：源声明不支持的 schema_version ⇒ UNSUPPORTED_SCHEMA_VERSION + 隔离")
    void unsupportedSchemaVersionIsQuarantined() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior(BODY_OK).replace("\"rev\":\"1.0\"", "\"rev\":\"2.0\""));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.UNSUPPORTED_SCHEMA_VERSION);
        assertThat(issue.path()).isEqualTo("schema_version");
        assertThat(issue.detail()).contains("2.0");
    }

    @Test
    @DisplayName("规则6：类型不符（对象映射到 string；文本映射到 integer）⇒ TYPE_MISMATCH + 隔离")
    void typeMismatchIsQuarantined() {
        MappingOutcome objectToString = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior("\"buyer\":{\"a\":1},\"item\":\"7\",\"visit\":\"s-1\",\"act\":\"BROWSE\",\"term\":\"APP\""));
        MappingOutcome textToInteger = MappingTestSupport.execute("order-items-fen.v2.json",
                orderCreated("""
                        [{"sku":"P-1","qty":"two","unit_fen":1000,"disc_fen":0,"amt_fen":1000}]""", "1000"));

        assertThat(objectToString.quarantined()).isTrue();
        assertThat(MappingTestSupport.issue(objectToString.violations(), MappingReason.TYPE_MISMATCH).path())
                .isEqualTo("payload.user_id");
        assertThat(textToInteger.quarantined()).isTrue();
        assertThat(MappingTestSupport.issue(textToInteger.violations(), MappingReason.TYPE_MISMATCH).path())
                .isEqualTo("payload.items[0].quantity");
    }

    @Test
    @DisplayName("规则6：未登记事件类型取值 ⇒ UNKNOWN_EVENT_TYPE(UNKNOWN)")
    void undeclaredEventTypeValueIsUnknownEventType() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior(BODY_OK).replace("\"msg_kind\":\"browse\"", "\"msg_kind\":\"mystery\""));

        assertThat(outcome.quarantined()).isTrue();
        MappingIssue issue = MappingTestSupport.issue(outcome.violations(), MappingReason.UNKNOWN_EVENT_TYPE);
        assertThat(issue.detail()).isEqualTo("UNKNOWN");
        assertThat(issue.path()).isEqualTo("event_type");
    }

    @Test
    @DisplayName("规则12：事件类型显式声明为 null（未裁定）⇒ UNKNOWN_EVENT_TYPE(TYPE_UNRESOLVED)")
    void unresolvedEventTypeIsUnknownEventType() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json",
                behavior(BODY_OK).replace("\"msg_kind\":\"browse\"", "\"msg_kind\":\"coupon_redeemed\""));

        assertThat(outcome.quarantined()).isTrue();
        assertThat(MappingTestSupport.issue(outcome.violations(), MappingReason.UNKNOWN_EVENT_TYPE).detail())
                .isEqualTo("TYPE_UNRESOLVED");
    }

    @Test
    @DisplayName("规则6：raw 事件不是合法 JSON ⇒ JSON_PARSE_ERROR + 隔离")
    void jsonParseErrorOnRawText() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json", "{oops");

        assertThat(outcome.quarantined()).isTrue();
        assertThat(outcome.canonical()).isNull();
        assertThat(MappingTestSupport.codes(outcome.violations())).containsExactly("JSON_PARSE_ERROR");
    }

    // ---------- 规则8/14：确定性、双校验和 ----------

    @Test
    @DisplayName("规则14：同一输入 + 同一画像重复执行 ⇒ canonical/校验和/违例/统计完全一致")
    void deterministicForSameInputAndProfile() {
        MappingProfile profile = MappingTestSupport.profile("behavior-enum-keep.v2.json");
        String raw = behavior(BODY_OK);

        MappingOutcome first = new MappingExecutor(MappingTestSupport.contract(), MappingTestSupport.mapper())
                .execute(profile, raw);
        MappingOutcome second = new MappingExecutor(MappingTestSupport.contract(), MappingTestSupport.mapper())
                .execute(profile, raw);

        assertThat(second.canonical().toString()).isEqualTo(first.canonical().toString());
        assertThat(second.rawChecksum()).isEqualTo(first.rawChecksum());
        assertThat(second.canonicalChecksum()).isEqualTo(first.canonicalChecksum());
        assertThat(MappingTestSupport.codes(second.violations()))
                .isEqualTo(MappingTestSupport.codes(first.violations()));
        assertThat(second.stats()).isEqualTo(first.stats());
        assertThat(second.pendingPlatformFields()).isEqualTo(first.pendingPlatformFields());
    }

    @Test
    @DisplayName("规则8：raw 与 canonical 各自 checksum 且互不相同（原始/标准化隔离）")
    void rawAndCanonicalChecksumsAreDistinct() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json", behavior(BODY_OK));

        assertThat(outcome.rawChecksum()).matches("[0-9a-f]{64}");
        assertThat(outcome.canonicalChecksum()).matches("[0-9a-f]{64}");
        assertThat(outcome.canonicalChecksum()).isNotEqualTo(outcome.rawChecksum());
    }

    @Test
    @DisplayName("规则8：raw 记录全部输入（含被 @keep 与未映射字段），不丢原文")
    void rawKeepsAllInput() {
        MappingOutcome outcome = MappingTestSupport.execute("behavior-enum-keep.v2.json", behavior(BODY_OK));

        assertThat(outcome.raw().path("body").path("ext_coupon").asText()).isEqualTo("C-9");
        assertThat(outcome.raw().path("msg_id").asText()).isEqualTo("e-1");
    }
}
