package com.graduation.analytics.runtime.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 落地存储（整改书 §8.2）：source/events 之外的统一落地层入口。
 * 目录职责（§9.1）：landing/accepted/{batchId} 校验通过、landing/quarantine/{batchId} 坏行、
 * landing/manifests/{batchId}.json 批次清单。实现：LocalLandingStorage / HdfsLandingStorage。
 * ODS 只能读取 accepted 或 Flume 的 HDFS 落地区，禁止直接读 source/events（§9.1）。
 */
public interface LandingStorage {

    /** local / hdfs / flume-hdfs */
    String type();

    /** 命名空间（所在 RuntimeProfile 的 landing_uri） */
    String namespace();

    /** 路径是否存在（相对 storage 根的相对路径） */
    boolean exists(String relativePath);

    /** 列出目录下的直接子项名 */
    List<String> list(String relativeDir);

    /** 打开可读流（调用方负责关闭） */
    InputStream open(String relativePath);

    /** 文件统计（大小/最后修改/是否目录） */
    FileStat stat(String relativePath);

    /** 写入批次清单（idempotent：同批次覆盖） */
    String writeManifest(String batchId, String manifestJson);

    /** 连通性检查：可写/可读探针 */
    HealthResult healthCheck();

    record FileStat(long size, long lastModified, boolean directory) {
    }

    record HealthResult(boolean ok, String detail) {
    }
}