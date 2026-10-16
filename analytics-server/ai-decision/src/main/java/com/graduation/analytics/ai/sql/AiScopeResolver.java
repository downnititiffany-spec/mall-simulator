package com.graduation.analytics.ai.sql;

import com.graduation.analytics.runtime.ActiveRuntimeProfileIdProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 快照作用域解析器（R8-2 契约 §2.2）：AI 问数在生成 SQL **之前**必须先拿到
 * 「当前 ACTIVE 快照」或用户明确选择的「已发布快照」及其业务日，再把它们作为字面量注入提示词。
 *
 * <p>走与 {@link SqlExecutor} 同一个 {@code metricReadDataSource}（metric_read 只读账号）：
 * 快照表属于 analytics_metric，读它必须和读 ADS 用同一条最小权限通道，不允许走 meta 库或可写账号。
 * 数据源缺失时 fail-closed（抛 {@code METRIC_READ_SOURCE_MISSING}），不静默降级。</p>
 *
 * <p>反熵（R8-2 契约 §2.2）：默认 ACTIVE 不存在 → 抛 {@code NO_ACTIVE_SNAPSHOT}，
 * **不得自动回退**到归档快照。仅当用户显式提交 snapshotId 时才允许读取 ACTIVE/ARCHIVED；
 * 不存在、BUILDING、VERIFYING、FAILED 均 fail-closed。这样历史回看是明示选择，而非静默降级。</p>
 */
@Slf4j
@Component
public class AiScopeResolver {

    /** 契约 §2.2 冻结查询：同一 runtime_profile 下最多一个 ACTIVE（DB 层唯一索引保证） */
    public static final String ACTIVE_SNAPSHOT_SQL =
            "SELECT snapshot_id, business_time, definition_version, source_id FROM metric_snapshot "
                    + "WHERE runtime_profile_id = ? AND status='ACTIVE' ORDER BY id DESC LIMIT 1";
    public static final String PUBLISHED_SNAPSHOT_SQL =
            "SELECT snapshot_id, business_time, definition_version, source_id FROM metric_snapshot "
                    + "WHERE snapshot_id = ? AND status IN ('ACTIVE','ARCHIVED') LIMIT 1";

    private final JdbcTemplate metricReadJdbcTemplate;
    private final ActiveRuntimeProfileIdProvider activeRuntimeProfileIdProvider;

    /** Reuse the centrally configured metric_read template so scope queries inherit its timeout. */
    @Autowired
    public AiScopeResolver(@Qualifier("metricReadJdbcTemplate") JdbcTemplate metricReadJdbcTemplate,
                           ActiveRuntimeProfileIdProvider activeRuntimeProfileIdProvider) {
        this.metricReadJdbcTemplate = metricReadJdbcTemplate;
        this.activeRuntimeProfileIdProvider = activeRuntimeProfileIdProvider;
    }

    /** Compatibility constructor for focused unit tests; default resolution fails closed without a profile provider. */
    public AiScopeResolver(JdbcTemplate metricReadJdbcTemplate) {
        this(metricReadJdbcTemplate, null);
    }

    /**
     * 解析当前 ACTIVE 快照作用域。
     *
     * @throws AiSqlException {@code METRIC_READ_SOURCE_MISSING} 只读源未配置；
     *                        {@code NO_ACTIVE_SNAPSHOT} 无 ACTIVE 快照或快照行缺少必要字段
     */
    public AiScope resolve() {
        return resolve(null);
    }

    /**
     * 解析显式选择快照；未指定时维持原有 ACTIVE 默认行为，不做归档回退。
     * 只有 ACTIVE/ARCHIVED 两种已发布状态可用于只读问数。
     */
    public AiScope resolve(String requestedSnapshotId) {
        if (metricReadJdbcTemplate == null) {
            throw new AiSqlException(SqlPolicy.METRIC_READ_SOURCE_MISSING,
                    "metricReadDataSource 未配置：AI 作用域必须从 analytics_metric 只读源解析（§17.1 禁止回退 meta）");
        }
        String requested = requestedSnapshotId == null ? null : requestedSnapshotId.trim();
        boolean explicit = requested != null && !requested.isEmpty();
        List<Map<String, Object>> rows;
        try {
            if (explicit) {
                rows = metricReadJdbcTemplate.queryForList(PUBLISHED_SNAPSHOT_SQL, requested);
            } else {
                Optional<Long> activeProfileId = activeRuntimeProfileIdProvider == null
                        ? Optional.empty() : activeRuntimeProfileIdProvider.activeRuntimeProfileId();
                if (activeProfileId.isEmpty()) {
                    throw new AiSqlException(SqlPolicy.NO_ACTIVE_SNAPSHOT,
                            "没有唯一的 ACTIVE RuntimeProfile，AI 问数拒绝解析默认快照（fail-closed）");
                }
                rows = metricReadJdbcTemplate.queryForList(ACTIVE_SNAPSHOT_SQL, activeProfileId.get());
            }
        } catch (AiSqlException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiSqlException(SqlPolicy.METRIC_READ_SOURCE_MISSING,
                    "读取指标快照作用域失败（fail-closed）");
        }
        if (rows == null || rows.isEmpty()) {
            if (explicit) {
                throw new AiSqlException(SqlPolicy.SNAPSHOT_NOT_AVAILABLE,
                        "所选快照不存在或未发布：仅允许明确选择 ACTIVE/ARCHIVED 快照");
            }
            throw new AiSqlException(SqlPolicy.NO_ACTIVE_SNAPSHOT,
                    "metric_snapshot 中没有 status='ACTIVE' 的快照：AI 问数拒绝执行（不自动回退归档/最新快照）");
        }
        Map<String, Object> row = rows.get(0);
        String snapshotId = asText(row.get("snapshot_id"));
        LocalDate businessDate = toBusinessDate(row.get("business_time"));
        String definitionVersion = asText(row.get("definition_version"));
        if (snapshotId == null || snapshotId.isBlank() || businessDate == null) {
            throw new AiSqlException(SqlPolicy.NO_ACTIVE_SNAPSHOT,
                    "所选快照行缺少 snapshot_id/business_time，无法确定问数作用域（fail-closed）");
        }
        Long sourceId = null;
        if (row.get("source_id") != null) {
            try {
                sourceId = Long.valueOf(row.get("source_id").toString());
                if (sourceId <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new AiSqlException(SqlPolicy.SNAPSHOT_NOT_AVAILABLE,
                        "快照来源身份无效，无法确定问数作用域（fail-closed）");
            }
        }
        AiScope scope = AiScope.of(snapshotId, definitionVersion == null ? "" : definitionVersion, businessDate, sourceId);
        log.debug("AI 问数作用域: snapshot={} businessDate={} 允许区间=[{}, {}]",
                scope.snapshotId(), scope.businessDate(), scope.minAllowedDate(), scope.businessDate());
        return scope;
    }

    private static String asText(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** business_time 是 DATETIME(3)：取日期部分作为业务日（MySQL / 驱动可能返回 Timestamp 或 LocalDateTime） */
    static LocalDate toBusinessDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (value instanceof LocalDate ld) {
            return ld;
        }
        if (value instanceof java.util.Date d) {
            return new Timestamp(d.getTime()).toLocalDateTime().toLocalDate();
        }
        String text = String.valueOf(value).trim();
        if (text.length() >= 10) {
            try {
                return LocalDate.parse(text.substring(0, 10));
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
