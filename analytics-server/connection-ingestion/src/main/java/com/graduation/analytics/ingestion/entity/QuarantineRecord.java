package com.graduation.analytics.ingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 隔离数据索引（§24.1 quarantine_record）：未知版本/契约违规行，待适配后重处理。
 */
@Data
@TableName("quarantine_record")
public class QuarantineRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private String eventId;

    private String schemaVersion;

    private String reason;

    private String rawPath;

    private LocalDateTime createdAt;
}