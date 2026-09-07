package com.graduation.analytics.ingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件断点（Taildir position 等价：下次从 next_offset 继续，暂停恢复不丢不重）。
 * 整改书 §9.2：唯一键至少含 runtime_profile_id + absolute_source_id + file_identity，
 * 不能只用文件名 —— 实体字段与 V8 迁移的 uk_ckpt (runtime_profile_id, file_path,
 * file_identity) 对应；fileIdentity 变化（文件删除重建等）视为新版本，从头读取。
 */
@Data
@TableName("file_checkpoint")
public class FileCheckpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 运行环境（§9.2：checkpoint 归属 runtime profile） */
    private Long runtimeProfileId;

    /** 源文件绝对路径（absolute_source_id） */
    private String filePath;

    /** 文件身份：Windows 用创建时间戳（epoch millis），删除重建即变化 → 新版本 */
    private String fileIdentity;

    /** 下一个待读【字节】偏移 */
    private Long nextOffset;

    private LocalDateTime updatedAt;
}