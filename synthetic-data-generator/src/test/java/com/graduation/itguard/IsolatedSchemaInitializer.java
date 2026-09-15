package com.graduation.itguard;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

/**
 * DEV-003a：把「全新 schema → migration → test」变成确定性编排，供**裸 JDBC** 集成测试复用。
 *
 * <h3>要解决的问题（2026-09-15 在全新 runId 上实测复现）</h3>
 *
 * <p>{@code GeneratorMetaStoreTest} 用裸 {@code DriverManagerDataSource}，不依赖 Spring 上下文，
 * 因此 <b>不享受 Spring 的 Flyway 自动迁移</b>。surefire 的类执行顺序里它排在
 * {@code GeneratorApiSmokeTest} 之前，于是全新 runId 首次执行时：</p>
 *
 * <pre>
 *   ...GeneratorMetaStoreTest  ← 先跑，表还不存在 ⇒ SQLSyntaxErrorException: Table '&lt;runId&gt;_generator.generation_run' doesn't exist
 *   ...GeneratorApiSmokeTest   ← Spring 上下文启动时才 Flyway migrate（"Successfully applied 1 migration"）
 * </pre>
 *
 * <p>后果是<b>状态依赖</b>：同一个库第二次执行就"假绿"（表已经被上一轮的 Spring 上下文建好了）。
 * 证据见 {@code docs/acceptance/dev003-isolated-entry-20260915/REPORT.md}。</p>
 *
 * <h3>为什么用 JUnit5 扩展而不是其它办法</h3>
 *
 * <ul>
 *   <li><b>不用</b>人工预建表、不用复用旧 runId、不在测试里 catch "table doesn't exist"、
 *       不把用例改成 skip、不依赖前一个 Spring 测试恰好先跑、不写死用例执行顺序——
 *       这些都会把"环境不具备"或"顺序凑巧"伪装成通过（§9.4）。</li>
 *   <li>扩展在<b>每个标注它的测试类执行前</b>跑一次：与类顺序、与是否有 Spring 上下文都无关。</li>
 *   <li>迁移参数与生产同源（库内迁移脚本 + {@code application.yml} 的 Flyway 口径），
 *       不新增"测试专用 schema 定义"这第二个事实来源。</li>
 *   <li>门禁仍然先于 DDL：{@link IsolationGuard#verifyBeforeWrite} 在真实连接上核对
 *       库/账号/端口/hostname:port 指纹之后，才允许 Flyway 建表。**没有绕开隔离门禁的捷径。**</li>
 * </ul>
 *
 * <h3>用法</h3>
 *
 * <pre>
 * &#64;Tag("it")
 * &#64;ExtendWith(IsolatedSchemaInitializer.class)
 * class SomeBareJdbcIT { ... }
 * </pre>
 *
 * <p>配置只从 {@link IsolationGuard} 的既有通道读取（系统属性 {@code -Dit.guard.*} →
 * 环境变量 {@code IT_GUARD_*} → 档案 {@code it-guard.local.properties}），
 * 因此与 {@code scripts/it-prepare-isolation.ps1} / {@code scripts/run-isolated-tests.ps1}
 * 的注入方式一致，不需要任何新参数。</p>
 */
public final class IsolatedSchemaInitializer implements BeforeAllCallback {

    /**
     * 迁移脚本位置：与 {@code src/main/resources/application.yml} 的
     * {@code spring.flyway.locations} 同源（{@code classpath:db/generator}）。
     *
     * <p>刻意写成编译期常量而不是再读一遍 yml：避免在测试里引入 YAML 解析这一条新依赖；
     * 一致性由本模块的隔离档实测保证（迁移条数与 {@code flyway_schema_history} 对账）。</p>
     */
    static final String MIGRATION_LOCATIONS = "classpath:db/generator";

    /** 迁移必须落地的表（{@code V1__generator_meta.sql} 的 5 张表）。缺一张即判初始化失败。 */
    private static final List<String> REQUIRED_TABLES = List.of(
            "generator_target", "generation_plan", "generation_run",
            "generation_artifact", "generation_event_stat");

    @Override
    public void beforeAll(ExtensionContext context) {
        String who = context.getRequiredTestClass().getSimpleName();
        // 门禁与目标解析：与 GeneratorMetaStoreTest 完全同一套通道（无默认值、不猜目标）。
        IsolationGuard.requireEnabled("IsolatedSchemaInitializer:" + who);
        String url = IsolationGuard.require("url");
        String user = IsolationGuard.require("user");
        String password = IsolationGuard.requireCredential(IsolationGuard.require("password"),
                "IsolatedSchemaInitializer:" + who);
        String expectedDb = IsolationGuard.assertUrlAllowed(url, "IsolatedSchemaInitializer:" + who);

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // 迁移 = DDL：先过写前门禁（实连核对库/账号/端口/指纹），再建表。
        IsolationGuard.LiveFacts facts = IsolationGuard.verifyBeforeWrite(
                dataSource, expectedDb, "isolated-schema-init:" + who);

        MigrateResult result = Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATIONS)
                .baselineOnMigrate(true)
                .ignoreMigrationPatterns("*:missing")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertSchemaReady(jdbc, expectedDb, who);

        System.out.printf("[DEV-003a] %s 前置编排完成：database=%s account=%s hostname=%s port=%d runId=%s"
                        + " | flyway 本次执行迁移数=%d 目标版本=%s%n",
                who, facts.database(), facts.account(), facts.hostname(), facts.port(), facts.runId(),
                result.migrationsExecuted, result.targetSchemaVersion);

        // 首次执行的第一手证据：把 flyway_schema_history 打进入口日志，便于"首跑即成功"的取证。
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT installed_rank, version, description, installed_on, success"
                        + " FROM flyway_schema_history ORDER BY installed_rank")) {
            System.out.printf("[DEV-003a] %s flyway_schema_history: rank=%s version=%s description=%s"
                            + " installed_on=%s success=%s%n",
                    who, row.get("installed_rank"), row.get("version"),
                    row.get("description"), row.get("installed_on"), row.get("success"));
        }
    }

    /** 迁移后自检：5 张表必须都在，否则说明本次编排没有真正把 schema 准备好（失败关闭）。 */
    private static void assertSchemaReady(JdbcTemplate jdbc, String expectedDb, String who) {
        List<String> present = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, expectedDb);
        List<String> missing = REQUIRED_TABLES.stream().filter(t -> !present.contains(t)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("[DEV-003a] " + who + " 前置迁移后仍缺表：" + missing
                    + "（已存在：" + present + "）——拒绝在未就绪的 schema 上执行用例。");
        }
    }
}
