package com.graduation.analytics.runtime.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运行环境档案（整改书 §8.1）：LOCAL/SINGLE_NODE/REMOTE_CLUSTER 环境定义。
 * 数据库只保存凭据引用 credential_ref，不保存明文密码；LOCAL 不需要 SSH 字段。
 * status: DRAFT → TESTING → ACTIVE | DISABLED（§8.3 激活流程）。
 */
@Data
@TableName("runtime_profile")
public class RuntimeProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String profileCode;

    private String profileName;

    /** LOCAL / SINGLE_NODE / REMOTE_CLUSTER */
    private String type;

    /** DRAFT / TESTING / ACTIVE / DISABLED */
    private String status;

    /** file:/// 或 hdfs:// 落地基础路径 */
    private String landingUri;

    private String hdfsUri;

    private String hiveJdbcUrl;

    private String hiveDatabasePrefix;

    /**
     * 本运行环境绑定到哪个源（{@code source_registry.id}，列由 V16 新增）。
     *
     * <p>P2-07 起本字段有两个消费者：①「当前激活源」读口（{@code ActiveSourceBindingMapper}
     * 用显式 SQL 读写它，那是唯一写者）；② 一次运行的环境快照
     * （{@link com.graduation.analytics.runtime.RuntimeProfileSnapshot}）——
     * 库名按快照里的 {@code sourceId} 解析，运行中途切换源不会改了这一次 run 的库名。</p>
     *
     * <p>兼容期可空（存量行先于源登记存在）；需要它的路径一律 fail-closed 拒绝空值
     * （{@code SOURCE_NOT_BOUND}），不回落到"缺省源"。</p>
     */
    private Long sourceId;

    private String sparkMaster;

    private String deployMode;

    private String yarnQueue;

    private String sshHost;

    private Integer sshPort;

    private String sshUser;

    private String sparkSubmitPath;

    private String sparkJobJarUri;

    /** MYSQL；第二阶段 DORIS */
    private String metricStoreType;

    private String metricStoreConfigRef;

    /** 凭据引用（密钥库 Key，不存明文） */
    private String credentialRef;

    private String timezone;

    /** 环境配置版本（§8.3 激活递增） */
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static final String TYPE_LOCAL = "LOCAL";
    public static final String TYPE_SINGLE_NODE = "SINGLE_NODE";
    public static final String TYPE_REMOTE_CLUSTER = "REMOTE_CLUSTER";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_TESTING = "TESTING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
}