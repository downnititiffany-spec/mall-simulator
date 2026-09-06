package com.graduation.mall.ingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 批次文件明细：start_offset/end_offset/record_count 是重放与对账依据（§5.2.8）。
 */
@Data
@TableName("ingestion_batch_file")
public class IngestionBatchFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;

    private String filePath;

    private Long startOffset;

    private Long endOffset;

    private Long recordCount;

    private String status;
}