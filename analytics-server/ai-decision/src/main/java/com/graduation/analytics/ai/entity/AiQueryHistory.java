package com.graduation.analytics.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 查询历史（§6.6）：问题、SQL、表、状态、耗时、反馈 —— 可追溯。
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

    private String status;

    private Integer rowsReturned;

    private Long elapsedMs;

    private String feedback;

    private String errors;

    private LocalDateTime createdAt;
}