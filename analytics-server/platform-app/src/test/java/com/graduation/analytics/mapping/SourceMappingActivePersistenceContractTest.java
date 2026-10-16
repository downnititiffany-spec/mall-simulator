package com.graduation.analytics.mapping;

import com.graduation.analytics.mapping.activation.entity.SourceMappingActiveEntity;
import com.graduation.analytics.mapping.activation.mapper.SourceMappingActiveMapper;
import com.graduation.analytics.testsupport.RepoRoot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-03.1 激活指针"表 ↔ 实体 ↔ SQL"三者的契约门禁（静态，不连库）。
 *
 * <p><b>为什么需要它</b>：这三者是同一份事实的三处写法，MyBatis 的失败方式恰好是最不吵的那种——
 * 实体多一个字段、SQL 少一列，编译与启动都不报错，只有真跑到那条 SQL 才炸；而"锁定读"写漏一个
 * {@code FOR UPDATE} 更是完全静默，只在并发下变成重复审计。把三处对齐关系写成测试，
 * 是唯一能在提交前拦住它们的机制。</p>
 *
 * <p>真库行为（SQL 能否执行、并发是否真的串行）仍只能由 MySqlIT 取证；本类只管"写法自洽"。</p>
 */
class SourceMappingActivePersistenceContractTest {

    private static final Path META_DIR =
            RepoRoot.path("analytics-server/platform-app/src/main/resources/db/meta");
    private static final String SCRIPT_NAME = "V21__source_mapping_active.sql";
    private static final String TABLE = "source_mapping_active";

    /** 表里有、但不由激活写入的列（时间戳由列默认值与 ON UPDATE 维护） */
    private static final Set<String> DB_MAINTAINED_COLUMNS = Set.of("created_at", "updated_at");

    /** 实体里有、但**不是本表列**的字段（由 JOIN source_registry 取回，见实体注释） */
    private static final Set<String> JOIN_DERIVED_FIELDS = Set.of("sourceCode");

    @Test
    @DisplayName("表列 ↔ 实体字段一一对应：多一列没人读、多一个字段没列，都在这里变红")
    void entityFieldsMatchTableColumns() {
        Set<String> columns = tableColumns();
        Set<String> entityFields = new LinkedHashSet<>();
        for (Field field : SourceMappingActiveEntity.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !JOIN_DERIVED_FIELDS.contains(field.getName())) {
                entityFields.add(field.getName());
            }
        }
        entityFields.remove("serialVersionUID");

        assertThat(entityFields)
                .as("实体字段（去掉 JOIN 取回的 %s）必须与 %s 的列一一对应", JOIN_DERIVED_FIELDS, TABLE)
                .containsExactlyInAnyOrderElementsOf(camelCase(columns));
    }

    @Test
    @DisplayName("锁定读必须有 FOR UPDATE，普通读必须没有 —— 写反了并发就会各写一次")
    void onlyTheLockingReadUsesForUpdate() {
        assertThat(selectSql("lockById"))
                .as("activate 的幂等判断依赖锁定读；缺 FOR UPDATE 时 REPEATABLE READ 快照会让并发各写一行")
                .containsPattern("(?is)\\bfor\\s+update\\b");
        assertThat(selectSql("findById"))
                .as("采集侧高频只读不得拿写锁（否则一次采集会阻塞激活）")
                .doesNotContainPattern("(?is)\\bfor\\s+update\\b");
    }

    @Test
    @DisplayName("两条 SELECT 都必须 JOIN source_registry 取 source_code，且按别名显式映射")
    void selectsJoinSourceRegistryForSourceCode() {
        for (String method : List.of("findById", "lockById")) {
            assertThat(selectSql(method))
                    .as("%s 必须带上 source_code（它不在本表，唯一 owner 是源登记）", method)
                    .containsPattern("(?is)join\\s+source_registry\\b")
                    .containsPattern("(?is)source_code\\s+as\\s+sourceCode");
        }
    }

    @Test
    @DisplayName("写入是单语句 upsert：ON DUPLICATE KEY UPDATE + 主键冲突即覆盖，且不写 created_at")
    void upsertIsSingleStatementAndKeepsCreatedAt() {
        String sql = insertSql();

        assertThat(sql)
                .as("同源重复写入必须是覆盖同一行（唯一键是最终防线），不能先删后插")
                .containsPattern("(?is)insert\\s+into\\s+" + TABLE + "\\b")
                .containsPattern("(?is)on\\s+duplicate\\s+key\\s+update\\b");
        assertThat(sql)
                .as("created_at 不入列：它只在首次插入时由列默认值取到，写进 SET 会把'首次激活时刻'抹掉")
                .doesNotContain("created_at");
        assertThat(sql)
                .as("激活事实的每一列都要在 SET 子句里被覆盖（不然替换激活会留下上一次的残留值）")
                .contains("profile_ref = VALUES(profile_ref)")
                .contains("profile_version = VALUES(profile_version)")
                .contains("profile_checksum = VALUES(profile_checksum)")
                .contains("contract_version = VALUES(contract_version)")
                .contains("contract_checksum = VALUES(contract_checksum)")
                .contains("report_id = VALUES(report_id)")
                .contains("activated_at = VALUES(activated_at)")
                .contains("activated_by = VALUES(activated_by)");
    }

    @Test
    @DisplayName("upsert 的列清单 ⊆ 表列：SQL 不许写一个不存在的列")
    void upsertColumnsExistInTable() {
        String sql = insertSql();
        Set<String> columns = tableColumns();

        Matcher matcher = Pattern.compile("(?is)insert\\s+into\\s+" + TABLE
                + "\\s*\\(([^)]*)\\)").matcher(sql);
        assertThat(matcher.find()).as("upsert 必须是显式列清单的 INSERT：%s", sql).isTrue();
        Set<String> inserted = new LinkedHashSet<>();
        for (String column : matcher.group(1).split(",")) {
            inserted.add(column.trim());
        }

        assertThat(columns)
                .as("upsert 写入的列必须都真实存在于 %s：%s", TABLE, inserted)
                .containsAll(inserted);
        assertThat(inserted)
                .as("除数据库自维护的两列外，激活事实的每一列都必须被写入")
                .containsExactlyInAnyOrderElementsOf(minus(columns, DB_MAINTAINED_COLUMNS));
    }

    // ---------------------------------------------------------------- 读取注解与 DDL

    private static String selectSql(String methodName) {
        Method method = java.util.Arrays.stream(SourceMappingActiveMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mapper 上找不到方法 " + methodName));
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as("%s 必须是 @Select 注解方法（查 SQL 找不到就等于没写）", methodName).isNotNull();
        return String.join(" ", select.value());
    }

    private static String insertSql() {
        Method method = java.util.Arrays.stream(SourceMappingActiveMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals("upsert"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mapper 上找不到 upsert"));
        Insert insert = method.getAnnotation(Insert.class);
        assertThat(insert).as("upsert 必须是 @Insert 注解方法").isNotNull();
        return String.join(" ", insert.value());
    }

    /** 解析 V21 的 CREATE TABLE 列清单（跳过 PRIMARY KEY / KEY / CONSTRAINT 等表级子句）。 */
    private static Set<String> tableColumns() {
        String ddl;
        try {
            ddl = Files.readString(META_DIR.resolve(SCRIPT_NAME), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + SCRIPT_NAME + " 失败", e);
        }
        Matcher body = Pattern.compile("(?is)create\\s+table\\s+" + TABLE + "\\s*\\((.*?)\\)\\s*engine")
                .matcher(ddl);
        assertThat(body.find()).as("%s 的建表语句必须可解析", TABLE).isTrue();

        Set<String> columns = new LinkedHashSet<>();
        for (String rawLine : body.group(1).split("\n")) {
            String line = rawLine.strip();
            // DDL 里类型是大写（BIGINT/VARCHAR…），列名是小写 ⇒ 必须大小写不敏感，否则一列都解析不出来
            Matcher column = Pattern.compile("(?i)^([a-z_]+)\\s+(bigint|varchar|char|datetime|int|text)\\b")
                    .matcher(line);
            if (column.find()) {
                columns.add(column.group(1));
            }
        }
        assertThat(columns).as("解析出的列清单不应为空").isNotEmpty();
        return columns;
    }

    private static Set<String> minus(Set<String> columns, Set<String> excluded) {
        Set<String> rest = new LinkedHashSet<>(columns);
        excluded.forEach(rest::remove);
        return rest;
    }

    private static Set<String> camelCase(Set<String> columns) {
        Set<String> fields = new LinkedHashSet<>();
        for (String column : columns) {
            StringBuilder field = new StringBuilder();
            boolean upper = false;
            for (char c : column.toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    field.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            fields.add(field.toString());
        }
        return fields;
    }
}
