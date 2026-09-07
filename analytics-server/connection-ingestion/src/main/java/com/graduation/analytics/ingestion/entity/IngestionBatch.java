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