package com.graduation.analytics.migration;

import com.graduation.analytics.metric.QualityRuleCatalog;
import com.graduation.analytics.metric.QualityRuleDefinition;
import com.graduation.analytics.metric.RuleSeverity;
import com.graduation.analytics.testsupport.RepoRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-F8b / F88-GAP-05：V19 种子 ↔ QualityRuleCatalog.DEFINITIONS 35 条规则全量穷举映射台账测试。
 *
 * <p>本测试专门覆盖 F88-GAP-05 缺口：
 * 断言 V19 SQL 种子（{@code V19__quality_rule_definition.sql}）中的 35 条 INSERT 记录，
 * 与 Java 目录 {@link QualityRuleCatalog#DEFAULT} 中对应的 35 条 version=1 规则定义之间，
 * 计数同为 35、逐条 rule_code、version（全为 1）、checksum 全等、且两端映射差异为 0。</p>
 */
class QualityRuleV19MappingLedgerTest {

    private static final Path V19_SQL_PATH = RepoRoot.path(
            "analytics-server/platform-app/src/main/resources/db/meta/V19__quality_rule_definition.sql");

    /** V19 种子行正则：提取 rule_code, version, source_scope, stage, severity, severity_mode, threshold_json, enabled, effective_from, effective_to, checksum */
    private static final Pattern SEED_ROW_PATTERN = Pattern.compile(
            "(?m)^\\s*\\(\\s*'([A-Z0-9_]+)'\\s*,\\s*(\\d+)\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,"
                    + "\\s*'([A-Z]+)'\\s*,\\s*'([A-Z_]+)'\\s*,\\s*(NULL|'([^']*)')\\s*,"
                    + "\\s*(\\d+)\\s*,\\s*(NULL|'([^']*)')\\s*,\\s*(NULL|'([^']*)')\\s*,"
                    + "\\s*'([0-9a-f]{64})'\\s*\\)");

    /** V19 规范 35 条规则码的字母排序全量清单 */
    private static final List<String> EXPECTED_V19_RULE_CODES = List.of(
            "ADS_DWS_FUNNEL_RECONCILE",
            "ADS_STAGING_KEY_NOT_NULL",
            "ADS_STAGING_PRESENT",
            "ADS_STAGING_SNAPSHOT_ISOLATION",
            "AMOUNT_RECONCILE",
            "DWD_DWS_AMOUNT_RECONCILE",
            "ENUM_WHITELIST",
            "EVENT_ID_UNIQUE",
            "MP_ACTIVE_SNAPSHOT",
            "MP_ADS_ROWS_DB_MATCH",
            "MP_ADS_ROWS_MATCH",
            "MP_ADS_WRITE_MATCH",
            "MP_EXPORT_FILES",
            "MP_HIVE_PATH_PINNED",
            "MP_MANIFEST_SNAPSHOT",
            "MP_MANIFEST_TABLES",
            "MP_METRIC_DICT_VERSION",
            "MP_METRIC_VALUE_COUNT",
            "MP_METRIC_VALUE_DB_MATCH",
            "MP_OLD_ACTIVE_ARCHIVED",
            "MP_OVERVIEW_CORE_NOT_NULL",
            "MP_REQUIRED_TABLES_NONEMPTY",
            "MP_ROW_SHAPE_CONSISTENT",
            "MP_VALUE_MATCH_ADS",
            "MXP_EXPORT_COMPLETE",
            "MXP_EXPORT_ROWS",
            "MXP_SNAPSHOT_PINNED",
            "ORDER_ITEM_AMOUNT_FORMULA",
            "PUB_DQ_BLOCKING_RULES",
            "PUB_DQ_EVENT_ID_UNIQUE",
            "PUB_FORMAL_PARTITION_MATCH",
            "PUB_POINTER_SWITCH",
            "PUB_STAGING_PRUNE",
            "PUB_STAGING_READY",
            "REQUIRED_FIELD_NULL_RATE"
    );

    record SqlSeedEntry(
            String ruleCode,
            int version,
            String sourceScope,
            String stage,
            String severity,
            String severityMode,
            String thresholdJson,
            boolean enabled,
            String checksum
    ) {}

    @Test
    @DisplayName("F88-GAP-05 台账断言：V19 SQL 种子 35 条 ↔ Java 目录 35 条 version=1 定义逐条全量映射，差异为 0")
    void v19SqlSeedsAndJavaCatalogDefinitionsMatchExhaustively() {
        // 1. 从 V19__quality_rule_definition.sql 解析 35 条种子行
        List<SqlSeedEntry> sqlSeeds = parseV19Seeds();
        assertThat(sqlSeeds)
                .as("V19 SQL 种子必须恰好包含 35 条规则定义")
                .hasSize(35);

        // 2. 从 Java QualityRuleCatalog 获取全部定义，并筛选出对应 V19 初始 35 码的 version=1 定义
        Set<String> sqlRuleCodes = sqlSeeds.stream()
                .map(SqlSeedEntry::ruleCode)
                .collect(Collectors.toCollection(TreeSet::new));

        List<QualityRuleDefinition> catalogV1Defs = QualityRuleCatalog.DEFAULT.definitions().stream()
                .filter(d -> d.version() == 1 && sqlRuleCodes.contains(d.ruleCode()))
                .toList();

        assertThat(catalogV1Defs)
                .as("Java 目录中与 V19 对应的 version=1 定义必须恰好包含 35 条")
                .hasSize(35);

        // 3. 逐条构建映射对照表：rule_code -> (version, checksum)
        Map<String, SqlSeedEntry> sqlMap = new LinkedHashMap<>();
        for (SqlSeedEntry entry : sqlSeeds) {
            sqlMap.put(entry.ruleCode(), entry);
        }

        Map<String, QualityRuleDefinition> catalogMap = new LinkedHashMap<>();
        for (QualityRuleDefinition def : catalogV1Defs) {
            catalogMap.put(def.ruleCode(), def);
        }

        // 4. 集合对比：两端规则码集无任何多余或缺失
        Set<String> onlyInSql = new TreeSet<>(sqlMap.keySet());
        onlyInSql.removeAll(catalogMap.keySet());

        Set<String> onlyInCatalog = new TreeSet<>(catalogMap.keySet());
        onlyInCatalog.removeAll(sqlMap.keySet());

        assertThat(onlyInSql)
                .as("仅在 V19 SQL 种子存在而在 Java 目录缺失的规则")
                .isEmpty();
        assertThat(onlyInCatalog)
                .as("仅在 Java 目录存在而在 V19 SQL 种子缺失的规则")
                .isEmpty();

        // 5. 逐条比对：rule_code, version (必全为 1), checksum (全等), stage, severity, severity_mode
        List<String> discrepancies = new ArrayList<>();
        for (String ruleCode : sqlRuleCodes) {
            SqlSeedEntry sql = sqlMap.get(ruleCode);
            QualityRuleDefinition cat = catalogMap.get(ruleCode);

            if (sql.version() != 1) {
                discrepancies.add(ruleCode + ": SQL version 不是 1 (实际: " + sql.version() + ")");
            }
            if (cat.version() != 1) {
                discrepancies.add(ruleCode + ": Catalog version 不是 1 (实际: " + cat.version() + ")");
            }
            if (!sql.checksum().equals(cat.checksum())) {
                discrepancies.add(ruleCode + ": Checksum 不一致 (SQL: " + sql.checksum() + ", Catalog: " + cat.checksum() + ")");
            }
            if (!sql.stage().equals(cat.stage())) {
                discrepancies.add(ruleCode + ": Stage 不一致 (SQL: " + sql.stage() + ", Catalog: " + cat.stage() + ")");
            }
            if (!sql.severity().equals(cat.severity())) {
                discrepancies.add(ruleCode + ": Severity 不一致 (SQL: " + sql.severity() + ", Catalog: " + cat.severity() + ")");
            }
            if (!sql.severityMode().equals(cat.severityMode().name())) {
                discrepancies.add(ruleCode + ": SeverityMode 不一致 (SQL: " + sql.severityMode() + ", Catalog: " + cat.severityMode() + ")");
            }
            String expectedThreshold = cat.thresholdJson() == null ? "" : cat.thresholdJson();
            if (!sql.thresholdJson().equals(expectedThreshold)) {
                discrepancies.add(ruleCode + ": ThresholdJson 不一致 (SQL: " + sql.thresholdJson() + ", Catalog: " + expectedThreshold + ")");
            }
        }

        assertThat(discrepancies)
                .as("V19 种子与 Java 目录逐条映射差异必须为 0")
                .isEmpty();
    }

    @Test
    @DisplayName("F88-GAP-05 码表断言：V19 种子所含规则码全集与预期 35 码清单精确一致")
    void v19RuleCodesMatchStrictExpectedList() {
        List<SqlSeedEntry> sqlSeeds = parseV19Seeds();
        List<String> actualCodes = sqlSeeds.stream()
                .map(SqlSeedEntry::ruleCode)
                .distinct()
                .sorted()
                .toList();

        assertThat(actualCodes)
                .as("V19 种子 35 个规则码精确匹配")
                .containsExactlyElementsOf(EXPECTED_V19_RULE_CODES);
    }

    @Test
    @DisplayName("F88-GAP-05 不变量断言：V19 所有 35 条种子 version 必须为 1，source_scope 必须为 '*'，enabled 必须为 true")
    void v19SeedsSatisfyGlobalBaselineInvariants() {
        List<SqlSeedEntry> sqlSeeds = parseV19Seeds();
        for (SqlSeedEntry seed : sqlSeeds) {
            assertThat(seed.version())
                    .as("%s version 必须为 1", seed.ruleCode())
                    .isEqualTo(1);
            assertThat(seed.sourceScope())
                    .as("%s source_scope 必须为 '*'", seed.ruleCode())
                    .isEqualTo("*");
            assertThat(seed.enabled())
                    .as("%s enabled 必须为 1", seed.ruleCode())
                    .isTrue();
            assertThat(seed.checksum())
                    .as("%s checksum 必须为 64 位 SHA-256", seed.ruleCode())
                    .hasSize(64);
        }
    }

    private static List<SqlSeedEntry> parseV19Seeds() {
        String content;
        try {
            content = Files.readString(V19_SQL_PATH, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 V19 SQL 失败: " + V19_SQL_PATH, e);
        }

        // 去掉注释
        String cleaned = content.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ");
        int valuesAt = cleaned.toUpperCase(java.util.Locale.ROOT).indexOf("VALUES");
        assertThat(valuesAt).as("V19 SQL 必须有 VALUES 子句").isNotNegative();

        String valuesSection = cleaned.substring(valuesAt);
        Matcher matcher = SEED_ROW_PATTERN.matcher(valuesSection);

        List<SqlSeedEntry> entries = new ArrayList<>();
        while (matcher.find()) {
            String ruleCode = matcher.group(1);
            int version = Integer.parseInt(matcher.group(2));
            String sourceScope = matcher.group(3);
            String stage = matcher.group(4);
            String severity = matcher.group(5);
            String severityMode = matcher.group(6);
            String thresholdJson = matcher.group(8) == null ? "" : matcher.group(8);
            boolean enabled = "1".equals(matcher.group(9));
            String checksum = matcher.group(14);

            entries.add(new SqlSeedEntry(ruleCode, version, sourceScope, stage, severity,
                    severityMode, thresholdJson, enabled, checksum));
        }
        return entries;
    }
}
