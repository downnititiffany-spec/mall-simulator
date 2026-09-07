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