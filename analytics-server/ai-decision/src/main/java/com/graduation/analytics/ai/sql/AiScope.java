package com.graduation.analytics.ai.sql;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * AI 问数作用域（R8-2 契约 §2.2 冻结结构）：一次问数在**生成 SQL 之前**先确定的不变量。
 *
 * <p>它是「日期与快照必须参数化注入」这条规则的载体（§19.5）：
 * 生成阶段把 {@code snapshotId} / {@code businessDate} / {@code minAllowedDate} 作为**字面量**
 * 写进提示词，校验阶段再用同一份 scope 反向核对 SQL 里的字面量，
 * 因此 SQL 里**不允许**再出现 {@code (SELECT MAX(snapshot_id) ...)}、{@code (SELECT MAX(dt) ...)}
 * 这类会把归档快照一起扫进来的子查询。</p>
 *
 * @param snapshotId        ACTIVE 快照发布号（如 {@code S20260901_24}）
 * @param definitionVersion 该快照使用的指标口径版本
 * @param businessDate      ACTIVE 快照业务日（快照数据「最新到哪天」）
 * @param minAllowedDate    = businessDate - 89（含端点共 90 天，与 maxScanDays 对齐）
 * @param maxScanDays       WHERE 中 dt 范围允许的最大跨度（含端点）
 * @param rowLimit          结果行上限（LIMIT 强制重写上限）
 */
public record AiScope(String snapshotId,
                      String definitionVersion,
                      LocalDate businessDate,
                      LocalDate minAllowedDate,
                      int maxScanDays,
                      int rowLimit) {

    /** dt 范围含端点：businessDate 往前共 90 天（2026-09-04 → [2026-06-07, 2026-09-04]） */
    public static final int DEFAULT_MAX_SCAN_DAYS = 90;

    /** 契约 §2.3：LIMIT 上限 200（rowLimit=200） */
    public static final int DEFAULT_ROW_LIMIT = 200;

    /**
     * ADS 落库 {@code dt} 的**唯一**文本格式：紧凑 {@code yyyyMMdd}（如 {@code 20260901}）。
     *
     * <p>2026-09-11 真机事故：AI 问数原先按 ISO {@code yyyy-MM-dd} 生成 dt 字面量，
     * 而 MySQL 镜像表的 {@code dt} 是 {@code varchar(16)} 存 Spark 写的紧凑值
     * （见 {@code metric-analysis/AdsRows} 与 {@code db/metric V3}），
     * 字符串比较 {@code '20260901' <= '2026-09-01'} 恒为 false →
     * **每个问题都静默返回 0 行**。格式现在只在这里定义，生成/校验/提示词全部引用它。</p>
     */
    public static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 按 ADS 落库格式渲染 dt 字面量内容（供 SQL 生成与提示词共用） */
    public static String dt(LocalDate date) {
        return date == null ? null : date.format(DT_FORMAT);
    }

    /** 允许区间左端（紧凑格式） */
    public String dtFrom() {
        return dt(minAllowedDate);
    }

    /** 允许区间右端 = 业务日（紧凑格式） */
    public String dtTo() {
        return dt(businessDate);
    }

    /** 按契约口径由业务日派生 minAllowedDate / maxScanDays / rowLimit */
    public static AiScope of(String snapshotId, String definitionVersion, LocalDate businessDate) {
        return new AiScope(snapshotId, definitionVersion, businessDate,
                businessDate.minusDays(DEFAULT_MAX_SCAN_DAYS - 1L),
                DEFAULT_MAX_SCAN_DAYS, DEFAULT_ROW_LIMIT);
    }

    public boolean businessDateInRange(LocalDate date) {
        return date != null && !date.isBefore(minAllowedDate) && !date.isAfter(businessDate);
    }
}
