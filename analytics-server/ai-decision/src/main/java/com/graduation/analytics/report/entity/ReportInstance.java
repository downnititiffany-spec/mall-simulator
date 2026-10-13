package com.graduation.analytics.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("report_instance")
public class ReportInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String templateCode;
    private String templateVersion;
    private Long sourceId;
    private String snapshotId;
    private String definitionVersion;
    private LocalDate fromDate;
    private LocalDate toDate;
    private String promptVersion;
    private String creatorId;
    private String status;
    private String artifactUri;
    private String artifactChecksum;
    private String artifactJson;
    private String stableErrorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
