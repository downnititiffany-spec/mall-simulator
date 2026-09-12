package com.graduation.analytics.ingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采集批次（§5.2.7 状态机：GENERATED→COLLECTING→LANDED→VALIDATING→SUCCESS/QUARANTINED）。
 */
@Data
@TableName("ingestion_batch")
public class IngestionBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchNo;

    /** 采集归属的运行环境（manifest 携带 runtimeProfileId，§9.3） */
    private Long runtimeProfileId;

    /**
     * 数据源归因（{@code source_registry.id}；P1-05，D-037 裁决 4）。
     *
     * <p>列**可空**：它是归因/审计列，不参与任何唯一键；可空是诚实的——V17 之前的历史批次
     * 确实不知道自己的来源，而 V17 已把能确定的 39 行回填为源 1。采集侧的新行**必须写**
     * （由 {@code IngestionService} 在解析出源之后才 insert，未绑定源时 fail-closed 不落行）。</p>
     */
    private Long sourceId;

    /**
     * <b>连接器类型</b>，不是源标识（D-037 裁决 5）。
     *
     * <p>当前恒为 {@code local-file}；不得改名，也不得被复用为源身份——
     * 源身份由 {@link #sourceId} + manifest 的 {@code sourceCode} 承担。</p>
     */
    private String source;

    private String status;

    private Long recordCount;

    private Long errorCount;

    private Long quarantineCount;

    private String landingDir;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    public static final String STATUS_GENERATED = "GENERATED";
    public static final String STATUS_COLLECTING = "COLLECTING";
    public static final String STATUS_LANDED = "LANDED";
    public static final String STATUS_VALIDATING = "VALIDATING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_QUARANTINED = "QUARANTINED";
    public static final String STATUS_FAILED = "FAILED";
}