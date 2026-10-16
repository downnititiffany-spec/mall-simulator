package com.graduation.analytics.mapping.activation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.graduation.analytics.mapping.activation.entity.SourceMappingActiveEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 激活指针表读写（S2-03.1）。**只有本接口与 {@code JdbcActiveMappingPointerStore} 碰这张表**：
 * 采集侧（{@code SourceMapper}）与激活侧（{@code MappingActivationService}）都走
 * {@link com.graduation.analytics.mapping.activation.ActiveMappingPointerStore} 端口，
 * 谁都不许自己再维护一份内存映射（设计 §7.2：同一事实只能有一个 owner）。
 *
 * <p>注册点：{@code AnalyticsApplication} 的 {@code @MapperScan} 增加
 * {@code com.graduation.analytics.mapping.activation.mapper}（不新建第二个扫描配置）。</p>
 */
public interface SourceMappingActiveMapper extends BaseMapper<SourceMappingActiveEntity> {

    /**
     * 读某源当前激活指针（**不加锁**，供采集侧等只读调用方使用）。
     *
     * <p>列名逐列显式 AS：不依赖 {@code map-underscore-to-camel-case} 的隐式行为
     * （与 {@code SourceRegistryMapper} 同口径）。</p>
     *
     * <p>{@code source_code} 来自 {@code JOIN source_registry}：本表不存它（唯一 owner 是源登记表），
     * 但指针对象在进程间往返后仍需带着它（审计/响应要用）。</p>
     */
    @Select("SELECT a.source_id AS sourceId, s.source_code AS sourceCode, a.profile_ref AS profileRef, "
            + "a.profile_version AS profileVersion, a.profile_checksum AS profileChecksum, "
            + "a.contract_version AS contractVersion, a.contract_checksum AS contractChecksum, "
            + "a.report_id AS reportId, a.activated_at AS activatedAt, a.activated_by AS activatedBy, "
            + "a.created_at AS createdAt, a.updated_at AS updatedAt "
            + "FROM source_mapping_active a JOIN source_registry s ON s.id = a.source_id "
            + "WHERE a.source_id = #{sourceId}")
    SourceMappingActiveEntity findById(@Param("sourceId") Long sourceId);

    /**
     * 锁内重读某源激活指针（{@code SELECT ... FOR UPDATE}）。
     *
     * <p><b>为什么必须有这个方法</b>：{@code activate} 的幂等判断（"同源同内容 ⇒ 零写入，
     * 不改激活时间与操作者"）必须基于**锁内**读到的最新版本。MySQL 默认 REPEATABLE READ 下，
     * 事务里第一次普通 SELECT 就固定了一致性快照，此后普通 SELECT 一直看到旧版本——
     * 于是并发的第二个 activate 会把"别人刚写进去的指针"看成"还没有指针"，全部走替换分支，
     * 一次逻辑激活产生 N 行重复审计。<b>锁定读永远读最新已提交版本</b>，故用 FOR UPDATE。</p>
     *
     * <p>必须在事务内调用（{@code MappingActivationService#activate} 已带 {@code @Transactional}）；
     * 无行可锁时返回 {@code null}，由调用方按"该源尚未激活"处理。
     * 锁是加在 {@code source_mapping_active} 那一行上的（{@code FOR UPDATE OF} 之外的
     * JOIN 只做列取值，不会把源登记行一起锁住）。</p>
     */
    @Select("SELECT a.source_id AS sourceId, s.source_code AS sourceCode, a.profile_ref AS profileRef, "
            + "a.profile_version AS profileVersion, a.profile_checksum AS profileChecksum, "
            + "a.contract_version AS contractVersion, a.contract_checksum AS contractChecksum, "
            + "a.report_id AS reportId, a.activated_at AS activatedAt, a.activated_by AS activatedBy, "
            + "a.created_at AS createdAt, a.updated_at AS updatedAt "
            + "FROM source_mapping_active a JOIN source_registry s ON s.id = a.source_id "
            + "WHERE a.source_id = #{sourceId} FOR UPDATE")
    SourceMappingActiveEntity lockById(@Param("sourceId") Long sourceId);

    /**
     * 写入/替换激活指针（一源一行，主键冲突即覆盖）。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是"先删后插"或"先查再判"：单语句原子，
     * 不产生"删掉了但没插上"的中间态，也不给两个并发 activate 留下"都以为自己是第一个"的窗口
     * （唯一键 {@code PRIMARY KEY (source_id)} 是最终防线）。</p>
     *
     * <p>{@code created_at}/{@code updated_at} 不入列：前者由列默认值只在首次插入时取值，
     * 后者由 {@code ON UPDATE CURRENT_TIMESTAMP(3)} 维护。若把 {@code created_at} 也写进 SET 子句，
     * "首次激活时刻"会在每次替换时被抹掉。</p>
     */
    @Insert("INSERT INTO source_mapping_active (source_id, profile_ref, profile_version, profile_checksum, "
            + "contract_version, contract_checksum, report_id, activated_at, activated_by) "
            + "VALUES (#{sourceId}, #{profileRef}, #{profileVersion}, #{profileChecksum}, "
            + "#{contractVersion}, #{contractChecksum}, #{reportId}, #{activatedAt}, #{activatedBy}) "
            + "ON DUPLICATE KEY UPDATE "
            + "profile_ref = VALUES(profile_ref), profile_version = VALUES(profile_version), "
            + "profile_checksum = VALUES(profile_checksum), contract_version = VALUES(contract_version), "
            + "contract_checksum = VALUES(contract_checksum), report_id = VALUES(report_id), "
            + "activated_at = VALUES(activated_at), activated_by = VALUES(activated_by)")
    int upsert(SourceMappingActiveEntity row);
}
