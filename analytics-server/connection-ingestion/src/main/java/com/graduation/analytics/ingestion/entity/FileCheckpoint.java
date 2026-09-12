package com.graduation.analytics.ingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件断点（Taildir position 等价：下次从 next_offset 继续，暂停恢复不丢不重）。
 *
 * <p>整改书 §9.2 要求"唯一键至少含 runtime_profile_id + absolute_source_id + file_identity"。
 * P1-05（2026-09-12，D-037 裁决 1）之前，唯一键是 V8 的 {@code uk_ckpt}
 * {@code (runtime_profile_id, file_path, file_identity)}：路径充当了 absolute_source_id 的替身，
 * 而"当前源"= {@code runtime_profile.source_id}（D-035 裁决 ②），**切换源改的是同一行 profile 的
 * source_id** ⇒ 同一个 runtime_profile_id 会先后服务不同源，两边共用一套断点，切源即**静默少采**。
 * V17 起改为 {@code uk_ckpt_source(runtime_profile_id, source_id, file_path, file_identity)}，
 * 读写两端都带 {@code source_id}（{@code LocalFileIngestor} 是本表的唯一读写口）。
 * {@code sourceId} 为 NOT NULL，且**不得有默认值**：来源不明时宁可失败也不许冒名（D-037 裁决 2）。</p>
 *
 * <p>fileIdentity 变化（文件删除重建等）视为新版本，从头读取；同一 (profile, source, path) 下
 * identity 相同的行只有一行，靠唯一键保证（应用层不重复去重）。</p>
 */
@Data
@TableName("file_checkpoint")
public class FileCheckpoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 运行环境（§9.2：checkpoint 归属 runtime profile） */
    private Long runtimeProfileId;

    /**
     * 数据源（{@code source_registry.id}；D-037 裁决 1：断点按源隔离）。
     * NOT NULL 且无默认值——采集时必须显式传入解析出的源，不允许缺省。
     */
    private Long sourceId;

    /** 源文件绝对路径（absolute_source_id） */
    private String filePath;

    /** 文件身份：Windows 用创建时间戳（epoch millis），删除重建即变化 → 新版本 */
    private String fileIdentity;

    /** 下一个待读【字节】偏移 */
    private Long nextOffset;

    private LocalDateTime updatedAt;
}