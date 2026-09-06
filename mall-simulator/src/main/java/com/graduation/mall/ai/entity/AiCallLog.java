package com.graduation.mall.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 模型调用日志（§6.6）：供应商、模型、令牌、耗时、状态与错误类型。
 */
@Data
@TableName("ai_call_log")
public class AiCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String useCase;

    private String provider;

    private String model;

    private String promptVersion;

    private Integer inputTokens;

    private Integer outputTokens;

    private Long elapsedMs;

    private String status;

    private String error;

    private LocalDateTime createdAt;
}