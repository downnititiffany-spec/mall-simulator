package com.graduation.generator.meta;

import com.graduation.generator.meta.GeneratorMetaStore.ArtifactRow;
import com.graduation.generator.meta.GeneratorMetaStore.PlanRow;
import com.graduation.generator.meta.GeneratorMetaStore.RunRow;
import com.graduation.generator.meta.GeneratorMetaStore.TargetRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeneratorMetaStore} 对真实 MySQL 独立库的集成测试（E2，真库真表，无 Mock）。
 *
 * <p>为什么必须真库：本类里的风险全在 SQL 与类型/时区映射上——条件 UPDATE 的幂等语义、
 * 唯一键冲突、{@code ON DUPLICATE KEY UPDATE}、{@code DATETIME(3)} 与 {@link Instant} 的换算。
 * 这些用 Mock 测等于什么都没测。</p>
 *
 * <h3>V25-S03 R-4 整改：把「skip 后 PASS」改成「显式失败」</h3>
 *
 * <p><b>整改前</b>：{@code URL/USER/PASSWORD} 是
 * {@code jdbc:mysql://127.0.0.1:3306/generator_meta…} / {@code root} / {@code 123456}
 * 三个硬编码默认值；库不可达时走 {@code assumeTrue(false, "跳过：…")}。
 * 两处都违反 §9.4：</p>
 * <ul>
 *   <li>默认值把目标钉在**宿主正式实例 3306**（且带 {@code createDatabaseIfNotExist=true}
 *       形态的同类配置见 application.yml），而本类的 {@link #cleanUp()} 会
 *       {@code DELETE FROM …} 四张表——连错库就是删正式数据；</li>
 *   <li>{@code assumeTrue(false)} 让「没连上库」表现为 <b>skipped</b>。
 *       Surefire 把 skipped 算进绿，于是"环境不具备"被伪装成"测试通过"。</li>
 * </ul>
 *
 * <p><b>处置：选「环境不具备时显式失败」（而不是登记 DEFERRED 移出用例集）。</b>
 * 理由：本类覆盖的是 SQL/类型/幂等语义，是{@code GeneratorMetaStore} 唯一的真库证据；
 * 把它移出用例集会留下一个无证据的核心持久层。改成显式失败后，
 * 用例集里保留着一个"要么真跑、要么红"的用例——红灯就是提醒，
 * 而不是一块永远绿的空白。代价是：未准备隔离环境时 {@code mvn test} 会红，
 * 这是**有意**的（见 README §R-4 证据）。</p>
 *
 * <p>配置改为门禁读取（无默认值）：{@code -Dit.guard.enabled=true} 必须显式给出，
 * 目标只能是登记的隔离实例（端口白名单），库名必须以本次 runId 为前缀。
 * 口令支持 {@code credref:<id>} 引用，源码与命令行都不落明文。
 * 连接参数与 {@code application.yml} 同源但**不再继承它的 root 默认值**。</p>
 */
class GeneratorMetaStoreTest {

    static {
        // 在任何断言/连接之前把门禁打开并做 URL 预检（缺配置 -> 类初始化失败 -> 用例红，不是 skip）
        com.graduation.itguard.IsolationGuard.requireEnabled("GeneratorMetaStoreTest");
    }

    /** 目标 URL：**无默认值**。整改前默认 {@code 127.0.0.1:3306/generator_meta…}。 */
    private static final String URL = com.graduation.itguard.IsolationGuard.require("url");

    /** 目标账号：**无默认值**。整改前默认 {@code root}。 */
    private static final String USER = com.graduation.itguard.IsolationGuard.require("user");

    /** 目标口令：**无默认值、不落明文**。整改前默认 {@code 123456}。 */
    private static final String PASSWORD = com.graduation.itguard.IsolationGuard.requireCredential(
            com.graduation.itguard.IsolationGuard.require("password"), "GeneratorMetaStoreTest");

    /** 声明目标库（从 URL 解出后与实连核对，防配置漂移）。 */
    private static final String EXPECTED_DB =
            com.graduation.itguard.IsolationGuard.assertUrlAllowed(URL, "GeneratorMetaStoreTest:url");

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String planId = "it-plan-" + suffix;
    private final String runId = "it-run-" + suffix;
    private final String runId2 = "it-run2-" + suffix;
    private final String targetName = "it-target-" + suffix;

    private JdbcTemplate jdbc;
    private GeneratorMetaStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new JdbcTemplate(dataSource);
        // V25-S03 R-4：整改前这里 catch(RuntimeException) -> assumeTrue(false, "跳过：…")，
        // 于是「隔离库没准备好」= skipped = 绿。现在改成显式失败：环境不具备就是红。
        // 写前门禁也在这里顶住：目标不是登记的隔离实例/库、账号是正式账号、
        // 库名不带本次 runId 前缀时，都在任何 DELETE 之前拒绝。
        try {
            com.graduation.itguard.IsolationGuard.verifyBeforeWrite(
                    dataSource, EXPECTED_DB, "GeneratorMetaStoreTest.setUp");
        } catch (RuntimeException e) {
            throw new AssertionError("测试无法开始：隔离库未就绪或被门禁拒绝——"
                    + "本用例不提供 skip 形态的通过（V25-S03 R-4）。"
                    + "请按 it-guard.local.properties 准备隔离实例后重跑。原因：" + e.getMessage(), e);
        }
        store = new GeneratorMetaStore(jdbc);
    }

    @AfterEach
    void cleanUp() {
        if (jdbc == null) {
            return;
        }
        for (String run : List.of(runId, runId2)) {
            jdbc.update("DELETE FROM generation_event_stat WHERE run_id = ?", run);
            jdbc.update("DELETE FROM generation_artifact WHERE run_id = ?", run);
            jdbc.update("DELETE FROM generation_run WHERE run_id = ?", run);
        }
        jdbc.update("DELETE FROM generation_plan WHERE plan_id = ?", planId);
        jdbc.update("DELETE FROM generator_target WHERE name = ?", targetName);
    }

    @Test
    @DisplayName("目标配置：写入/按名查询/更新时 config_version 自增（运行冻结版本的依据）")
    void targetCrudIncrementsConfigVersion() {
        long id = store.insertTarget(new TargetRow(null, targetName, "CANONICAL_EVENT_FILE",
                "file:///D:/Develop_code/GraduationProject/generator-output", null, "{}", 1, "ACTIVE", true,
                "product,user,behavior"));

        Optional<TargetRow> inserted = store.findTarget(id);
        assertThat(inserted).as("插入后可按主键读回").isPresent();
        assertThat(inserted.get().name()).isEqualTo(targetName);
        assertThat(inserted.get().configVersion()).isEqualTo(1);
        assertThat(inserted.get().testEnvironment()).as("TINYINT 与 boolean 映射").isTrue();
        assertThat(store.findTargetByName(targetName)).isPresent();

        assertThat(store.updateTarget(new TargetRow(id, targetName, "CANONICAL_EVENT_FILE",
                "file:///D:/Develop_code/GraduationProject/generator-output", null, "{\"rotate\":10}", 1,
                "ACTIVE", true, "product,user,behavior,order"))).isTrue();
        assertThat(store.findTarget(id).orElseThrow().configVersion())
                .as("配置更新必须让 config_version 自增（§4.2 每次运行冻结版本）").isEqualTo(2);

        assertThat(store.listTargets()).extracting(TargetRow::name).contains(targetName);
    }

    @Test
    @DisplayName("计划版本：同一 plan_id 追加两次得到 v1/v2，历史版本仍可按版本读取（不可变）")
    void planVersionsAreAppendOnly() {
        int first = store.appendPlanVersion(plan());
        int second = store.appendPlanVersion(plan());
        assertThat(List.of(first, second)).as("版本递增").containsExactly(1, 2);

        assertThat(store.findPlan(planId, 1)).as("旧版本仍可读（运行可引用旧版本）").isPresent();
        assertThat(store.findLatestPlan(planId).orElseThrow().version()).isEqualTo(2);

        PlanRow stored = store.findPlan(planId, 2).orElseThrow();
        assertThat(stored.seed()).isEqualTo(20260911L);
        assertThat(stored.mode()).isEqualTo("CANONICAL_EVENT_FILE");
        assertThat(stored.dirtyProfile()).isEqualTo("none");
        assertThat(stored.targetId()).as("文件模式允许 target_id 为空").isNull();
        assertThat(stored.startTime()).as("Instant 经 DATETIME(3) 往返后保持业务时区语义")
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    @DisplayName("运行生命周期：PENDING→RUNNING→SUCCESS 只能走一次，终态不被重复收口")
    void runLifecycleIsSingleWayAndTerminalProtected() {
        store.insertRun(new RunRow(null, runId, planId, 1, null, null, RunStatus.PENDING,
                null, null, 0, 0, null, null, null, false));

        assertThat(store.findRun(runId).orElseThrow().status()).isEqualTo(RunStatus.PENDING);
        assertThat(store.markRunning(runId)).as("PENDING→RUNNING 成功").isTrue();
        assertThat(store.markRunning(runId)).as("重复启动无效（幂等）").isFalse();
        assertThat(store.findRun(runId).orElseThrow().startedAt()).as("started_at 由库侧写入").isNotNull();

        assertThat(store.finishRun(runId, RunStatus.SUCCESS, 51, 4, "abc123", null, null)).isTrue();
        assertThat(store.finishRun(runId, RunStatus.FAILED, 0, 0, null, "X", "不应生效"))
                .as("终态不得被第二次收口覆盖").isFalse();

        RunRow finished = store.findRun(runId).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.SUCCESS);
        assertThat(finished.successCount()).isEqualTo(51);
        assertThat(finished.failedCount()).isEqualTo(4);
        assertThat(finished.checksum()).isEqualTo("abc123");
        assertThat(finished.finishedAt()).isNotNull();
        assertThat(store.listRunsByPlan(planId, 10)).extracting(RunRow::runId).contains(runId);
    }

    @Test
    @DisplayName("幂等取消：PENDING 可取消、取消后终态不再接受取消请求")
    void cancelIsIdempotent() {
        store.insertRun(new RunRow(null, runId2, planId, 1, null, null, RunStatus.PENDING,
                null, null, 0, 0, null, null, null, false));

        assertThat(store.requestCancel(runId2)).isTrue();
        assertThat(store.isCancelRequested(runId2)).isTrue();
        assertThat(store.finishRun(runId2, RunStatus.CANCELLED, 0, 0, null, null, "用户取消")).isTrue();
        assertThat(store.findRun(runId2).orElseThrow().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(store.requestCancel(runId2)).as("已终止的运行再取消返回 false（调用方据此做幂等响应）").isFalse();
    }

    @Test
    @DisplayName("制品与统计：同一 URI 只登记一次，统计按主键更新而不是重复插入")
    void artifactsAndStatsAreIdempotent() {
        store.insertRun(new RunRow(null, runId, planId, 1, null, null, RunStatus.PENDING,
                null, null, 0, 0, null, null, null, false));

        Instant min = Instant.parse("2026-09-01T02:00:00.123Z");
        Instant max = Instant.parse("2026-09-01T02:54:00.123Z");
        ArtifactRow artifact = new ArtifactRow("file:///D:/out/" + runId + "/events-0001.jsonl",
                "EVENT_JSONL", "deadbeef", 4096, 55, min, max, "1.0");
        assertThat(store.insertArtifact(runId, artifact)).isTrue();
        assertThat(store.insertArtifact(runId, artifact)).as("重复关账不产生第二行").isFalse();

        List<ArtifactRow> stored = store.listArtifacts(runId);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).bytes()).isEqualTo(4096);
        assertThat(stored.get(0).recordCount()).isEqualTo(55);
        assertThat(stored.get(0).minEventTime()).as("min_event_time 毫秒级往返").isEqualTo(min);
        assertThat(stored.get(0).maxEventTime()).isEqualTo(max);

        store.upsertEventStat(runId, "behavior", 14, new BigDecimal("0.00"));
        store.upsertEventStat(runId, "order_created", 6, new BigDecimal("2042.00"));
        store.upsertEventStat(runId, "behavior", 15, new BigDecimal("1.00"));
        assertThat(store.listEventStats(runId)).extracting(GeneratorMetaStore.EventStatRow::eventType)
                .containsExactlyInAnyOrder("behavior", "order_created");
        assertThat(store.listEventStats(runId).stream()
                .filter(row -> row.eventType().equals("behavior")).findFirst().orElseThrow().eventCount())
                .as("重复统计走 ON DUPLICATE KEY UPDATE").isEqualTo(15);
    }

    private PlanRow plan() {
        return new PlanRow(null, planId, 0, "CANONICAL_EVENT_FILE", null, "baseline_55",
                20260911L, Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"),
                55, 0, "none", "file:///D:/Develop_code/GraduationProject/generator-output");
    }
}
