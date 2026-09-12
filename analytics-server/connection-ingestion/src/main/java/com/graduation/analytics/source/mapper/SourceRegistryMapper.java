package com.graduation.analytics.source.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.graduation.analytics.source.entity.SourceRegistry;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 源登记表读写（P1-03）。**只有本接口与 {@link ActiveSourceBindingMapper} 碰这两张表**：
 * 控制器不得直接访问 mapper（任务书 §4 P1-03 硬约束），全部经
 * {@code com.graduation.analytics.source.SourceRegistryService}。
 *
 * <p>注册点：{@code AnalyticsApplication} 的 {@code @MapperScan} 增加
 * {@code com.graduation.analytics.source.mapper}（不新建第二个扫描配置）。</p>
 */
public interface SourceRegistryMapper extends BaseMapper<SourceRegistry> {

    /**
     * source_code 唯一性预检（V16 的 {@code uk_source_registry_code} 仍是最终防线）。
     * 用命名方法而不是 {@code LambdaQueryWrapper} 计数：SQL 显式可读、
     * 且单元测试里可以整表替身而不必解释 wrapper 的 SQL 片段。
     */
    @Select("SELECT COUNT(*) FROM source_registry WHERE source_code = #{sourceCode}")
    long countBySourceCode(@Param("sourceCode") String sourceCode);

    /**
     * 锁内重读一行源登记（{@code SELECT ... FOR UPDATE}）。
     *
     * <p><b>为什么必须有这个方法</b>：{@code activate}/{@code pause} 的幂等判断
     * （"已是当前源且已是 ACTIVE → 零写入"）必须基于**锁内**读到的最新版本。
     * MySQL 默认 REPEATABLE READ 下，事务里第一次普通 SELECT 就固定了一致性快照，
     * 此后普通 SELECT 一直看到旧版本——于是并发的第二个 activate 会把
     * "别人刚改成 ACTIVE"看成"还是 DRAFT"，全部报 {@code changed=true}，
     * 一次逻辑变更产生 N 行重复审计。<b>锁定读永远读最新已提交版本</b>，故用 FOR UPDATE 重读。</p>
     *
     * <p>列名逐列显式 AS：不依赖 {@code map-underscore-to-camel-case} 的隐式行为。</p>
     */
    @Select("SELECT id AS id, source_code AS sourceCode, display_name AS displayName, "
            + "ingest_mode AS ingestMode, profile_path AS profilePath, timezone AS timezone, "
            + "currency AS currency, status AS status, profile_version AS profileVersion, "
            + "warehouse_prefix AS warehousePrefix, "
            + "created_at AS createdAt, updated_at AS updatedAt "
            + "FROM source_registry WHERE id = #{id} FOR UPDATE")
    SourceRegistry lockById(@Param("id") Long id);
}
