package com.graduation.analytics.ingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件断点（Taildir position 等价：下次从 next_offset 继续，暂停恢复不丢不重）。
 */
@Data
@TableName("file_checkpoint")
public class FileCheckpoint {

    @TableId(type = IdType.INPUT)
    private String filePath;

    private Long nextOffset;

    private LocalDateTime updatedAt;
}