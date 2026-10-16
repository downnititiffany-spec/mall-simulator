package com.graduation.analytics.mapping.activation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 激活指针落库行（S2-03.1，表 {@code source_mapping_active} 由 V21 迁移建立，本类只映射、不改表）。
 *
 * <p><b>它不承载业务判断</b>：业务形态是 {@link com.graduation.analytics.mapping.activation.ActiveMappingPointer}
 * （record + {@code sameContentAs} 的"同内容"判据）。本类只是 JDBC 往返的载体，
 * 存在的唯一理由是 MyBatis 的字段映射需要可变属性（record 需构造函数映射，该项目既有 mapper 一律走实体）。
 * 二者的转换只在 {@code JdbcActiveMappingPointerStore} 一处发生，别处不得再写一遍。</p>
 *
 * <p>主键是 {@code source_id} 本身（{@link IdType#INPUT}：由业务给值，不是自增）——
 * "一个源最多一个激活指针"是**表级**不变式，不靠应用层自觉。</p>
 */
@Data
@TableName("source_mapping_active")
public class SourceMappingActiveEntity {

    /** source_registry.id：一源一行 */
    @TableId(value = "source_id", type = IdType.INPUT)
    private Long sourceId;

    /**
     * 登记源业务键（{@code source_registry.source_code}）。
     *
     * <p><b>本表没有这一列</b>：它由查询时 {@code JOIN source_registry} 得到，
     * 写库时不写它（{@code upsert} 的列清单里没有它）。之所以这么绕，是因为 source_code 的
     * 唯一 owner 是源登记表——在激活指针里再存一份就是第二个 owner，
     * 改名后两处不一致时谁也说不清哪个是真的。读出来是为了让指针对象在进程间往返后
     * 仍然带着完整事实（审计与响应都需要它），而不是因为本表拥有它。</p>
     */
    private String sourceCode;

    /** 激活的画像文件仓库相对路径 */
    private String profileRef;

    private String profileVersion;

    /** 激活时刻画像文件字节 SHA-256（小写 hex） */
    private String profileChecksum;

    private String contractVersion;

    /** 激活时刻契约文件字节 SHA-256（小写 hex） */
    private String contractChecksum;

    /** 授权本次激活的 dry-run 报告 id */
    private String reportId;

    /** 激活时刻（事件时钟，非入库时刻） */
    private LocalDateTime activatedAt;

    private String activatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
