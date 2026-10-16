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

    /**
     * Landing 输入布局（S2-04B，列由 V22 新增）：告诉采集端到 landing 根下的**哪个子目录**、
     * 以什么方式枚举输入。取值是 {@code LandingLayout} 的登记值
     * （{@code ROLLING_LOG} / {@code FLUME_RAW}）。
     *
     * <p>与 {@link #landingUri} 的分工：{@code landingUri} 回答"落在哪"（存储位置，V2 起就有），
     * 本列回答"目录与文件怎么摆"（枚举语义）。两者不能合成一个：同一个 landing 根下
     * 既可能有滚动日志区，也可能有 Flume 目标区。</p>
     *
     * <p>兼容期可空：空值＝**未配置**，读取侧等价于默认 {@code ROLLING_LOG}（V22 之前的存量行
     * 语义必须与 V2 完全一致）；写入侧空值归一为 {@code null}，未登记值一律
     * {@code PARAM_INVALID} 拒绝，<b>不回落默认</b>——拼写错误静默变成"去 events/ 采样"，
     * 会产出"成功但 0 条"这种事后无从察觉的结果。</p>
     */
    private String landingLayout;

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