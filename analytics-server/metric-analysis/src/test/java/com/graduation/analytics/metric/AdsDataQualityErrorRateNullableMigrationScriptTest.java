package com.graduation.analytics.metric;

import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-021 迁移脚本门（不需要真库）：{@code V11__ads_data_quality_error_rate_nullable.sql}
 * 必须且只能把 {@code ads_data_quality_m.error_rate} 放开为可空，并且 V1~V10 一字不动
 * （append-only）。
 *
 * <p>为什么需要它：D-019 的合法空态质量行（0/0/passed=1/error_rate=NULL）在 T-R2 attempt-4
 * 首次写真库时被 V3 的 {@code NOT NULL DEFAULT 0} 整批拒绝（MP_ADS_WRITE）。修这类缺陷
 * 最危险的错误姿势恰是「顺手改 V3」或「在迁移里夹带 NULL→0 的 UPDATE」——前者破坏 Flyway
 * checksum，后者再次把「未定义」伪装成「真实比率为 0」。本测试把两条路都焊死；真库行为
 * （information_schema 可空性 + 空态行真实写入）由
 * {@code AdsDataQualityErrorRateNullableMySqlIT} 在 3307 隔离库提供第二层证据。</p>
 *
 * <p>解析口径与 platform-app 侧 {@code QualityRuleVersionMigrationScriptTest}（meta 迁移门）一致：先剥离
 * {@code /* ... *}{@code /} 与 {@code --} 注释再对语句体做断言；「未执行」标注只存在于
 * 注释里，必须对原始文本检查。</p>
 */
class AdsDataQualityErrorRateNullableMigrationScriptTest {

    private static final Path METRIC_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/metric");

    private static final String V11_NAME = "V11__ads_data_quality_error_rate_nullable.sql";
    private static final String V3_NAME = "V3__metric_ads_r7.sql";

    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /** 剥离注释后的语句体必须整体匹配这一条 ALTER（多一个字都不行） */
    private static final Pattern V11_BODY = Pattern.compile(
            "^ALTER\\s+TABLE\\s+`?ads_data_quality_m`?\\s+"
                    + "MODIFY\\s+COLUMN\\s+`?error_rate`?\\s+DECIMAL\\(12,6\\)\\s+NULL\\s+DEFAULT\\s+NULL\\s*;$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 迁移语句体里除 MODIFY 外禁止出现的动作词（CHANGE 可改列名/类型，一并禁止） */
    private static final Pattern FORBIDDEN_ACTION = Pattern.compile(
            "\\b(CREATE|INSERT|UPDATE|DELETE|DROP|TRUNCATE|RENAME|CHANGE)\\b", Pattern.CASE_INSENSITIVE);

    private static final String V3_ERROR_RATE_SEMANTICS =
            "error_rate\\s+DECIMAL\\(12,6\\)\\s+NOT\\s+NULL\\s+DEFAULT\\s+0,";

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("V11 只含一条 ALTER：error_rate DECIMAL(12,6) NULL DEFAULT NULL，且标注真库未执行")
    void v11RelaxesExactlyTheErrorRateColumn() throws IOException {
        Path v11 = METRIC_DIR.resolve(V11_NAME);
        assertTrue(Files.isRegularFile(v11), "迁移文件必须存在: " + V11_NAME);
        String raw = Files.readString(v11, StandardCharsets.UTF_8);
        String body = stripComments(raw);

        List<String> violations = v11Violations(body);
        assertTrue(violations.isEmpty(), "V11 脚本门违规: " + violations);

        assertTrue(raw.contains("未执行") && raw.contains("3306"),
                "V11 必须在头部注释标注「真库（3306）未执行」（3306 冻结）");
    }

    @Test
    @DisplayName("append-only：V3 的 error_rate 原始定义未被改动，db/metric 版本号唯一且 V11 是下一个号")
    void appendOnlyHoldsAndV11IsTheNextVersion() throws IOException {
        String v3 = Files.readString(METRIC_DIR.resolve(V3_NAME), StandardCharsets.UTF_8);
        assertTrue(Pattern.compile(V3_ERROR_RATE_SEMANTICS).matcher(v3).find(),
                "V3 的 error_rate DECIMAL(12,6) NOT NULL DEFAULT 0 必须原样保留（append-only，"
                        + "改历史迁移会破坏 Flyway checksum）");

        Map<Integer, String> versions = new LinkedHashMap<>();
        try (var list = Files.list(METRIC_DIR)) {
            list.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .forEach(n -> {
                        Matcher m = MIGRATION_FILE.matcher(n);
                        assertTrue(m.matches(), "db/metric 下出现不合规迁移文件名: " + n);
                        int version = Integer.parseInt(m.group(1));
                        assertFalse(versions.containsKey(version), "db/metric 版本号重复 V" + version + ": " + n);
                        versions.put(version, n);
                    });
        }
        int max = versions.keySet().stream().max(Comparator.naturalOrder()).orElse(0);
        assertEquals(11, max, "V11 必须是 db/metric 当前最高版本（下一个 append-only 号），实测最高 V" + max);
        assertTrue(versions.containsKey(11) && versions.get(11).equals(V11_NAME),
                "V11 文件名必须是 " + V11_NAME);
    }

    @Test
    @DisplayName("反向对照：NOT NULL / 多语句 / 夹带 UPDATE / 改错表 都会被脚本门抓出（证明守卫非空洞）")
    void parserDetectsViolations() {
        String good = "ALTER TABLE ads_data_quality_m\n"
                + "    MODIFY COLUMN error_rate DECIMAL(12,6) NULL DEFAULT NULL;";

        assertTrue(violationText("NOT NULL 未放开", "ALTER TABLE ads_data_quality_m\n"
                        + "    MODIFY COLUMN error_rate DECIMAL(12,6) NOT NULL DEFAULT NULL;")
                        .stream().anyMatch(v -> v.contains("必须整体匹配")),
                "保留 NOT NULL 的脚本必须被判违规");

        assertTrue(violationText("夹带第二条语句", good
                        + "\nUPDATE ads_data_quality_m SET error_rate = 0 WHERE error_rate IS NULL;")
                        .stream().anyMatch(v -> v.contains("语句数") || v.contains("禁止出现")),
                "NULL→0 的 UPDATE 必须被判违规");

        assertTrue(violationText("改错表", "ALTER TABLE ads_operation_overview_m\n"
                        + "    MODIFY COLUMN error_rate DECIMAL(12,6) NULL DEFAULT NULL;")
                        .stream().anyMatch(v -> v.contains("必须整体匹配")),
                "目标表不是 ads_data_quality_m 必须被判违规");

        // 正向对照守恒：同样的检查器对合法脚本必须放行，否则上面的“抓出”是空洞的
        assertTrue(violationText("合法脚本", good).isEmpty(), "合法脚本不得误报");
    }

    // ------------------------------------------------------------------ 检查器

    /** 对剥离注释后的 V11 语句体做全部结构断言，返回违规描述（空 = 通过）。供正反两向用例共用。 */
    private static List<String> v11Violations(String strippedBody) {
        List<String> violations = new ArrayList<>();
        int statements = countMatches(strippedBody, ';');
        if (statements != 1) {
            violations.add("语句数必须恰好 1，实际 " + statements);
        }
        if (!V11_BODY.matcher(strippedBody.strip()).matches()) {
            violations.add("语句必须整体匹配 ALTER TABLE ads_data_quality_m "
                    + "MODIFY COLUMN error_rate DECIMAL(12,6) NULL DEFAULT NULL");
        }
        if (strippedBody.toUpperCase(Locale.ROOT).contains("NOT NULL")) {
            violations.add("语句体不得再含 NOT NULL");
        }
        Matcher forbidden = FORBIDDEN_ACTION.matcher(strippedBody);
        if (forbidden.find()) {
            violations.add("语句体禁止出现动作词（只允许 MODIFY COLUMN）: " + forbidden.group());
        }
        return violations;
    }

    private static List<String> violationText(String label, String sql) {
        return v11Violations(stripComments(sql));
    }

    // ------------------------------------------------------------------ 解析

    /** 剥离 {@code /* ... *}{@code /} 与 {@code --} 行注释（本迁移无字符串字面量，安全） */
    private static String stripComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return noBlock.replaceAll("--[^\n]*", " ");
    }

    private static int countMatches(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}
