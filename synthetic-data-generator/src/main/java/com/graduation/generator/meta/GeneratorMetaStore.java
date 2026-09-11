package com.graduation.generator.meta;

import com.graduation.generator.contract.ContractFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 生成器自有元数据读写（§4.2 五张表），只连 {@code generator_meta} 独立库（§3.4-1）。
 *
 * <p>两处刻意的实现选择：</p>
 * <ol>
 *   <li><b>业务时间按 {@link ContractFormat#BUSINESS_ZONE} 显式换算</b>：{@code DATETIME(3)} 不带时区，
 *       若依赖 JVM 默认时区，换一台机器/换一个 {@code -Duser.timezone} 就会静默错 8 小时。
 *       写入用 {@code setObject(LocalDateTime)} 原样落库，读取用 {@code getObject(..., LocalDateTime.class)} 再按业务时区还原为 {@link Instant}。</li>
 *   <li><b>状态迁移一律走条件 UPDATE</b>：{@code UPDATE ... WHERE status = ...} 返回受影响行数，
 *       并发下只有一个调用者能成功，避免"取消"和"完成"互相覆盖（§4.4 幂等取消）。</li>
 * </ol>
 */
public class GeneratorMetaStore {

    private static final String TARGET_COLUMNS =
            "id, name, adapter_type, base_url, credential_ref, config_json, config_version, status, "
                    + "test_environment, capabilities";
    private static final String PLAN_COLUMNS =
            "id, plan_id, version, mode, target_id, scenario, seed, start_time, end_time, event_count, "
                    + "rate_per_second, dirty_profile, output_uri";
    private static final String RUN_COLUMNS =
            "id, run_id, plan_id, plan_version, target_id, target_version, status, started_at, finished_at, "
                    + "success_count, failed_count, checksum, error_code, error_message, cancel_requested";

    private final JdbcTemplate jdbc;

    public GeneratorMetaStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    // ---------- generator_target ----------

    public long insertTarget(TargetRow row) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO generator_target
                        (name, adapter_type, base_url, credential_ref, config_json, config_version,
                         status, test_environment, capabilities)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.name());
            ps.setString(2, row.adapterType());
            ps.setString(3, row.baseUrl());
            ps.setString(4, row.credentialRef());
            ps.setString(5, row.configJson());
            ps.setInt(6, row.configVersion());
            ps.setString(7, row.status());
            ps.setBoolean(8, row.testEnvironment());
            ps.setString(9, row.capabilities());
            return ps;
        }, keys);
        return requireKey(keys, "generator_target");
    }

    /** 更新目标并自增 {@code config_version}（§4.2：每次运行冻结版本） */
    public boolean updateTarget(TargetRow row) {
        int updated = jdbc.update("""
                UPDATE generator_target
                   SET name = ?, adapter_type = ?, base_url = ?, credential_ref = ?, config_json = ?,
                       config_version = config_version + 1, status = ?, test_environment = ?, capabilities = ?
                 WHERE id = ?
                """, row.name(), row.adapterType(), row.baseUrl(), row.credentialRef(), row.configJson(),
                row.status(), row.testEnvironment(), row.capabilities(), row.id());
        return updated == 1;
    }

    public Optional<TargetRow> findTarget(long id) {
        return jdbc.query("SELECT " + TARGET_COLUMNS + " FROM generator_target WHERE id = ?", TARGET_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<TargetRow> findTargetByName(String name) {
        return jdbc.query("SELECT " + TARGET_COLUMNS + " FROM generator_target WHERE name = ?", TARGET_MAPPER, name)
                .stream().findFirst();
    }

    public List<TargetRow> listTargets() {
        return jdbc.query("SELECT " + TARGET_COLUMNS + " FROM generator_target ORDER BY id", TARGET_MAPPER);
    }

    // ---------- generation_plan ----------

    /**
     * 追加一个新计划版本（同一 {@code plan_id} 内 version 递增）。
     *
     * <p>版本号由"当前最大 + 1"计算，靠唯一键 {@code uk_generation_plan_version} 兜住并发：
     * 冲突时重算一次再试，避免用表锁。</p>
     */
    public int appendPlanVersion(PlanRow row) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Integer maxVersion = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version), 0) FROM generation_plan WHERE plan_id = ?", Integer.class,
                    row.planId());
            int version = (maxVersion == null ? 0 : maxVersion) + 1;
            try {
                jdbc.update("""
                        INSERT INTO generation_plan
                            (plan_id, version, mode, target_id, scenario, seed, start_time, end_time,
                             event_count, rate_per_second, dirty_profile, output_uri)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, row.planId(), version, row.mode(), row.targetId(), row.scenario(), row.seed(),
                        toDbTime(row.startTime()), toDbTime(row.endTime()), row.eventCount(),
                        row.ratePerSecond(), row.dirtyProfile(), row.outputUri());
                return version;
            } catch (DuplicateKeyException e) {
                // 版本被并发抢占，重算
            }
        }
        throw new IllegalStateException("计划版本追加失败（并发冲突重试 3 次）：" + row.planId());
    }

    public Optional<PlanRow> findPlan(String planId, int version) {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM generation_plan WHERE plan_id = ? AND version = ?",
                PLAN_MAPPER, planId, version).stream().findFirst();
    }

    public Optional<PlanRow> findLatestPlan(String planId) {
        return jdbc.query("SELECT " + PLAN_COLUMNS
                        + " FROM generation_plan WHERE plan_id = ? ORDER BY version DESC LIMIT 1",
                PLAN_MAPPER, planId).stream().findFirst();
    }

    // ---------- generation_run ----------

    public long insertRun(RunRow row) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO generation_run
                        (run_id, plan_id, plan_version, target_id, target_version, status,
                         success_count, failed_count, cancel_requested)
                    VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.runId());
            ps.setString(2, row.planId());
            ps.setInt(3, row.planVersion());
            if (row.targetId() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, row.targetId());
            }
            if (row.targetVersion() == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, row.targetVersion());
            }
            ps.setString(6, row.status().name());
            return ps;
        }, keys);
        return requireKey(keys, "generation_run");
    }

    /** {@code PENDING -> RUNNING}，同时写入 started_at；返回是否由本次调用完成迁移 */
    public boolean markRunning(String runId) {
        return jdbc.update("""
                UPDATE generation_run
                   SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP(3)
                 WHERE run_id = ? AND status = 'PENDING'
                """, runId) == 1;
    }

    /** 终态收口：只允许从 PENDING/RUNNING 迁到终态（§4.2），重复调用不再改动 */
    public boolean finishRun(String runId, RunStatus terminal, long successCount, long failedCount,
                             String checksum, String errorCode, String errorMessage) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("finishRun 只接受终态，实际=" + terminal);
        }
        return jdbc.update("""
                UPDATE generation_run
                   SET status = ?, finished_at = CURRENT_TIMESTAMP(3), success_count = ?, failed_count = ?,
                       checksum = ?, error_code = ?, error_message = ?
                 WHERE run_id = ? AND status IN ('PENDING', 'RUNNING')
                """, terminal.name(), successCount, failedCount, checksum, errorCode, truncate(errorMessage),
                runId) == 1;
    }

    /** 幂等取消标记（§4.4）：终态运行不再置位，返回 false 表示"无需再取消" */
    public boolean requestCancel(String runId) {
        return jdbc.update("""
                UPDATE generation_run
                   SET cancel_requested = 1
                 WHERE run_id = ? AND status IN ('PENDING', 'RUNNING')
                """, runId) == 1;
    }

    public boolean isCancelRequested(String runId) {
        return jdbc.query("SELECT cancel_requested FROM generation_run WHERE run_id = ?",
                        (rs, rowNum) -> rs.getBoolean("cancel_requested"), runId)
                .stream().findFirst().orElse(false);
    }

    public Optional<RunRow> findRun(String runId) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM generation_run WHERE run_id = ?", RUN_MAPPER, runId)
                .stream().findFirst();
    }

    public List<RunRow> listRuns(int limit) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM generation_run ORDER BY id DESC LIMIT ?",
                RUN_MAPPER, limit);
    }

    public List<RunRow> listRunsByPlan(String planId, int limit) {
        return jdbc.query("SELECT " + RUN_COLUMNS
                        + " FROM generation_run WHERE plan_id = ? ORDER BY id DESC LIMIT ?",
                RUN_MAPPER, planId, limit);
    }

    // ---------- generation_artifact ----------

    /** 同一 URI 重复登记按唯一键忽略（重复关账不会产生重复行） */
    public boolean insertArtifact(String runId, ArtifactRow row) {
        return jdbc.update("""
                INSERT IGNORE INTO generation_artifact
                    (run_id, uri, kind, checksum, bytes, record_count, min_event_time, max_event_time, schema_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, row.uri(), row.kind(), row.checksum(), row.bytes(), row.recordCount(),
                toDbTime(row.minEventTime()), toDbTime(row.maxEventTime()), row.schemaVersion()) == 1;
    }

    public List<ArtifactRow> listArtifacts(String runId) {
        return jdbc.query("""
                SELECT uri, kind, checksum, bytes, record_count, min_event_time, max_event_time, schema_version
                  FROM generation_artifact WHERE run_id = ? ORDER BY id
                """, ARTIFACT_MAPPER, runId);
    }

    // ---------- generation_event_stat ----------

    public void upsertEventStat(String runId, String eventType, long eventCount, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO generation_event_stat (run_id, event_type, event_count, amount)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE event_count = VALUES(event_count), amount = VALUES(amount)
                """, runId, eventType, eventCount, amount == null ? BigDecimal.ZERO : amount);
    }

    public List<EventStatRow> listEventStats(String runId) {
        return jdbc.query("""
                SELECT event_type, event_count, amount FROM generation_event_stat
                 WHERE run_id = ? ORDER BY event_count DESC, event_type
                """, (rs, rowNum) -> new EventStatRow(rs.getString("event_type"), rs.getLong("event_count"),
                rs.getBigDecimal("amount")), runId);
    }

    // ---------- 映射与工具 ----------

    private static final RowMapper<TargetRow> TARGET_MAPPER = (rs, rowNum) -> new TargetRow(
            rs.getLong("id"), rs.getString("name"), rs.getString("adapter_type"), rs.getString("base_url"),
            rs.getString("credential_ref"), rs.getString("config_json"), rs.getInt("config_version"),
            rs.getString("status"), rs.getBoolean("test_environment"), rs.getString("capabilities"));

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, rowNum) -> new PlanRow(
            rs.getLong("id"), rs.getString("plan_id"), rs.getInt("version"), rs.getString("mode"),
            nullableLong(rs.getObject("target_id")), rs.getString("scenario"), rs.getLong("seed"),
            toInstant(rs.getObject("start_time", LocalDateTime.class)),
            toInstant(rs.getObject("end_time", LocalDateTime.class)),
            rs.getLong("event_count"), rs.getInt("rate_per_second"), rs.getString("dirty_profile"),
            rs.getString("output_uri"));

    private static final RowMapper<RunRow> RUN_MAPPER = (rs, rowNum) -> new RunRow(
            rs.getLong("id"), rs.getString("run_id"), rs.getString("plan_id"), rs.getInt("plan_version"),
            nullableLong(rs.getObject("target_id")), nullableInt(rs.getObject("target_version")),
            RunStatus.fromDb(rs.getString("status")),
            toInstant(rs.getObject("started_at", LocalDateTime.class)),
            toInstant(rs.getObject("finished_at", LocalDateTime.class)),
            rs.getLong("success_count"), rs.getLong("failed_count"),
            rs.getString("checksum"), rs.getString("error_code"), rs.getString("error_message"),
            rs.getBoolean("cancel_requested"));

    private static final RowMapper<ArtifactRow> ARTIFACT_MAPPER = (rs, rowNum) -> new ArtifactRow(
            rs.getString("uri"), rs.getString("kind"), rs.getString("checksum"), rs.getLong("bytes"),
            rs.getLong("record_count"), toInstant(rs.getObject("min_event_time", LocalDateTime.class)),
            toInstant(rs.getObject("max_event_time", LocalDateTime.class)), rs.getString("schema_version"));

    /**
     * {@link Instant} → 库中 {@code DATETIME(3)} 字面值，按业务时区换算。
     *
     * <p>刻意用 {@link LocalDateTime}（JDBC 4.2 的 {@code setObject}）而不是 {@link java.sql.Timestamp}：
     * {@code Timestamp} 携带的是一个"瞬时"，驱动会再按连接/JVM 时区做一次换算，最终落库的字面值会随
     * {@code -Duser.timezone} 漂移；{@code LocalDateTime} 原样落库，读回来再按业务时区还原，行为确定。</p>
     */
    static LocalDateTime toDbTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ContractFormat.BUSINESS_ZONE);
    }

    static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ContractFormat.BUSINESS_ZONE).toInstant();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 497) + "...";
    }

    private static long requireKey(KeyHolder keys, String table) {
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException(table + " 未返回自增主键");
        }
        return key.longValue();
    }

    // ---------- 行对象（与 §4.2 列一一对应） ----------

    public record TargetRow(Long id, String name, String adapterType, String baseUrl, String credentialRef,
                            String configJson, int configVersion, String status, boolean testEnvironment,
                            String capabilities) {
    }

    public record PlanRow(Long id, String planId, int version, String mode, Long targetId, String scenario,
                          long seed, Instant startTime, Instant endTime, long eventCount, int ratePerSecond,
                          String dirtyProfile, String outputUri) {
    }

    public record RunRow(Long id, String runId, String planId, int planVersion, Long targetId, Integer targetVersion,
                         RunStatus status, Instant startedAt, Instant finishedAt, long successCount, long failedCount,
                         String checksum, String errorCode, String errorMessage, boolean cancelRequested) {
    }

    public record ArtifactRow(String uri, String kind, String checksum, long bytes, long recordCount,
                              Instant minEventTime, Instant maxEventTime, String schemaVersion) {
    }

    public record EventStatRow(String eventType, long eventCount, BigDecimal amount) {
    }
}
