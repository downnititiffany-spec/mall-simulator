package com.graduation.analytics.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_feedback")
public class AiFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long historyId;
    private String userId;
    private String rating;
    private String reasonCode;
    private String reasonCodeVersion;
    private Long sourceId;
    private String snapshotId;
    private String definitionVersion;
    private String evidenceId;
    private LocalDateTime createdAt;
}
