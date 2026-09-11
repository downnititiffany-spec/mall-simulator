package com.graduation.analytics.ai.sql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * ACTIVE 快照作用域解析器（R8-2 契约 §2.2）：AI 问数在生成 SQL **之前**必须先拿到
 * 「当前生效快照 + 其业务日」，再把它们作为字面量注入提示词。
 *
 * <p>走与 {@link SqlExecutor} 同一个 {@code metricReadDataSource}（metric_read 只读账号）：
 * 快照表属于 analytics_metric，读它必须和读 ADS 用同一条最小权限通道，不允许走 meta 库或可写账号。
 * 数据源缺失时 fail-closed（抛 {@code METRIC_READ_SOURCE_MISSING}），不静默降级。</p>
 *
 * <p>反熵（R8-2 契约 §2.2）：ACTIVE 不存在 → 抛业务异常 {@code NO_ACTIVE_SNAPSHOT}，
 * **不得**回退到「最新归档快照」或「id 最大的任意快照」。归档快照是已被替换的旧口径，
 * 用它回答问数等于给用户一份过期证据；失败的 → 失败，比悄悄返回旧数据安全。</p>
 */
@Slf4j
@Component
public class AiScopeResolver {

    /** 契约 §2.2 冻结查询：同一 runtime_profile 下最多一个 ACTIVE（DB 层唯一索引保证） */
    public static final String ACTIVE_SNAPSHOT_SQL =
            "SELECT snapshot_id, business_time, definition_version FROM metric_snapshot "
                    + "WHERE status='ACTIVE' ORDER BY id DESC LIMIT 1";

    private final JdbcTemplate metricReadJdbcTemplate;

    /**
     * 生产构造：只读源缺失（Bean 为 null）时保持 jdbcTemplate=null，调用 resolve() 即 fail-closed。
     *
     * <p>{@code @Autowired} 必须标在**构造器**上（不是参数上）：本类有两个 public 构造器
     * （另一个给测试直接注入 JdbcTemplate），Spring 无法自行择一，会退化成"找无参构造"并启动失败——
     * 2026-09-11 真机启动实测踩到（MockMvc 测试手工 new，覆盖不到装配路径）。</p>
     */
    @Autowired
    public AiScopeResolver(@Autowired(required = false)
                           @Qualifier("metricReadDataSource") DataSource metricReadDataSource) {
        this.metricReadJdbcTemplate = metricReadDataSource == null
                ? null : new JdbcTemplate(metricReadDataSource);
    }

    /** 测试/装配用构造：直接给定只读 JdbcTemplate（必须是 metricReadDataSource 上的那一个） */
    public AiScopeResolver(JdbcTemplate metricReadJdbcTemplate) {
        this.metricReadJdbcTemplate = metricReadJdbcTemplate;
    }

    /**
     * 解析当前 ACTIVE 快照作用域。
     *
     * @throws AiSqlException {@code METRIC_READ_SOURCE_MISSING} 只读源未配置；
     *                        {@code NO_ACTIVE_SNAPSHOT} 无 ACTIVE 快照或快照行缺少必要字段
     */
    public AiScope resolve() {
        if (metricReadJdbcTemplate == null) {
            throw new AiSqlException(SqlPolicy.METRIC_READ_SOURCE_MISSING,
                    "metricReadDataSource 未配置：AI 作用域必须从 analytics_metric 只读源解析（§17.1 禁止回退 meta）");
        }
        List<Map<String, Object>> rows;
        try {
            rows = metricReadJdbcTemplate.queryForList(ACTIVE_SNAPSHOT_SQL);
        } catch (RuntimeException e) {
            throw new AiSqlException(SqlPolicy.NO_ACTIVE_SNAPSHOT,
                    "读取 ACTIVE 快照失败（fail-closed，不回退归档快照）: " + e.getMessage(), e);
        }
        if (rows == null || rows.isEmpty()) {
            throw new AiSqlException(SqlPolicy.NO_ACTIVE_SNAPSHOT,
                    "metric_snapshot 中没有 status='ACTIVE' 的快照：AI 问数拒绝执行（不回退归档/最新快照）");
        }
        Map<String, Object> row = rows.get(0);
        String snapshotId = asText(row.get("snapshot_id"));
        LocalDate businessDate = toBusinessDate(row.get("business_time"));
        String definitionVersion = asText(row.get("definition_version"));
        if (snapshotId == null || snapshotId.isBlank() || businessDate == null) {
            throw new AiSqlException(SqlPolicy.NO_ACTIVE_SNAPSHOT,
                    "ACTIVE 快照行缺少 snapshot_id/business_time，无法确定问数作用域（fail-closed）");
        }
        AiScope scope = AiScope.of(snapshotId, definitionVersion == null ? "" : definitionVersion, businessDate);
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
