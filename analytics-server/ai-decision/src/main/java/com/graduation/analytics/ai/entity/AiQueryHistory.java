package com.graduation.analytics.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 查询历史（§6.6）：问题、SQL、表、状态、耗时、反馈 —— 可追溯。
 *
 * <p>R8-2（契约 §2.3）：审计必须能回答「这次问数钉的是哪个快照、允许查哪段时间、
 * EXPLAIN 估算扫多少行、行数上限多少」，因此补 5 个真实列（由 R8-3 的
 * {@code V14__r8_identity_decision.sql} 第 4 段纯增量 ALTER 提供）：
 * {@code snapshot_id / scope_min_date / scope_max_date / explain_rows / scope_row_limit}。
 * 列未落地时插入会直接报 Unknown column，属 fail-closed（不会悄悄丢审计信息）。</p>
 */
@Data
@TableName("ai_query_history")
public class AiQueryHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private String question;

    private String sqlText;

    private String tables;

    /** R8-2：本次问数钉住的 ACTIVE 快照（禁止 SQL 内 MAX(snapshot_id)） */
    private String snapshotId;

    /** R8-2：允许的最早日期（业务日 - 89，扫描上限 90 天） */
    private LocalDate scopeMinDate;

    /** R8-2：允许的最晚日期（快照业务日） */
    private LocalDate scopeMaxDate;

    /** R8-2：EXPLAIN 估算扫描行数；未通过校验/未执行 EXPLAIN 时为 null */
    private Long explainRows;

    /** R8-2：单次查询最大返回行数（200） */
    private Integer scopeRowLimit;

    private String status;

    private Integer rowsReturned;

    private Long elapsedMs;

    private String feedback;

    /** 真正的错误文本（规则码 + 说明，脱敏后截断），不再承载快照/范围等结构化信息 */
    private String errors;

    private LocalDateTime createdAt;
}
