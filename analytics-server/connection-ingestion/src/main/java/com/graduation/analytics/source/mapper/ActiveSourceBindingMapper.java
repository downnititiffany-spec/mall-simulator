package com.graduation.analytics.source.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 「当前激活源」的唯一读写口（P1-03）。
 *
 * <p>设计裁决（《开发过程事实与决策记录》D-035 裁决 2/3）：当前激活源**不是**新的列或新表，
 * 而是唯一 ACTIVE {@code runtime_profile} 行的 {@code source_id}。
 * 「只有一个当前源」是结构性事实——单列不可能同时持有两个值，因此不需要额外的唯一性约束或对账任务。</p>
 *
 * <p>本接口刻意用两条**显式 SQL**，而不是给 {@code RuntimeProfile} 实体加字段：</p>
 * <ul>
 *   <li>加字段会让 {@code PUT /api/v1/runtime-profiles/{id}} 变成第二个绑定写入口
 *       （P1-02 的冻结面 + 会出现两个所有者），也会改变该接口的响应 JSON；</li>
 *   <li>{@code RuntimeProfileServiceImpl.activate} 原来没有行锁，并发激活可能留下两行 ACTIVE
 *       （既有缺陷，P1-03 不修改该方法、只在新路径上用锁）。</li>
 * </ul>
 *
 * <p><b>取锁顺序是硬约定</b>：先锁 {@code runtime_profile} 的 ACTIVE 行，再改 {@code source_registry}。
 * 顺序颠倒会让"当前源不应是 PAUSED"在并发下不成立。</p>
 */
public interface ActiveSourceBindingMapper {

    /**
     * 锁定当前 ACTIVE 运行环境行并读出其绑定源。
     *
     * <p>{@code FOR UPDATE} 是并发正确性的唯一机制：它让「读绑定 → 判断 → 写绑定/写状态」
     * 落在同一个事务里串行执行。必须由调用方开启事务（{@code @Transactional}），
     * 否则 MySQL 自动提交会让行锁立刻释放、锁形同虚设。</p>
     *
     * @return 无 ACTIVE 行时返回 {@code null}（调用方须明确拒绝，不得静默只改源状态）
     */
    @Select("SELECT id AS profileId, source_id AS sourceId FROM runtime_profile "
            + "WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1 FOR UPDATE")
    ActiveProfileBinding lockActive();

    /**
     * 只读读取当前绑定（**不加锁**）：读路径（列表/详情/{@code test}）用它派生 DTO 的 {@code current}。
     * 与 {@link #lockActive()} 分开是刻意的——只读请求不该持有行锁，
     * 否则每次 {@code GET} 都会在 ACTIVE 行上排队，并把"读"变成"写事务"。
     */
    @Select("SELECT id AS profileId, source_id AS sourceId FROM runtime_profile "
            + "WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    ActiveProfileBinding findActive();

    /** 把 ACTIVE 运行环境绑定到指定源；返回受影响行数（0 说明该行已不是 ACTIVE，属并发异常，须失败） */
    @Update("UPDATE runtime_profile SET source_id = #{sourceId}, updated_at = NOW(3) WHERE id = #{profileId}")
    int bind(@Param("profileId") Long profileId, @Param("sourceId") Long sourceId);

    /**
     * ACTIVE 运行环境的绑定视图。
     *
     * @param profileId ACTIVE 行 id
     * @param sourceId  绑定的源 id；{@code null} 表示尚未绑定任何源
     */
    record ActiveProfileBinding(Long profileId, Long sourceId) {
    }
}
