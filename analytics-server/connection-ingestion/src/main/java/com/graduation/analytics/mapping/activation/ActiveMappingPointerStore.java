package com.graduation.analytics.mapping.activation;

import java.util.Optional;

/**
 * 激活指针的持久化端口（S2-03 建立，S2-03.1 补上事务内的锁定读）。
 *
 * <p><b>为什么是"端口 + 实现"而不是直接一个 Service</b>：指针的**所有者**必须是唯一的正式存储。
 * 采集侧（{@code SourceMapper}）与激活侧（{@code MappingActivationService}）都只通过本接口读写，
 * 谁都不许自己再维护一份内存映射冒充"当前激活"（设计 §7.2：不得既写文件又写 DB 形成两个 owner）。</p>
 *
 * <p><b>正式实现</b>：{@link JdbcActiveMappingPointerStore}（表 {@code source_mapping_active}，V21 迁移）。
 * {@link UnavailableActiveMappingPointerStore} 只作"装配里确实没有持久化能力"时的 fail-closed 兜底，
 * 不是生产路径（装配由 {@code MappingActivationPersistenceConfig} 决定，只有一个 bean）。</p>
 *
 * <p><b>读-判-写必须原子</b>：{@code activate} 的"发现已有指针 ⇒ 判同内容 ⇒ 决定写不写"
 * 必须落在同一事务里，且判断依据取自 {@link #lockBySourceId(long)}（锁定读）而不是 {@link #find(long)}：
 * MySQL REPEATABLE READ 下普通 SELECT 在事务内固定快照，会把并发刚提交的指针看成"还没有"，
 * 于是并发的第二个 activate 也走替换分支，一次逻辑激活产生 N 行重复审计。
 * 这与 {@code SourceRegistryMapper#lockById} 是同一类坑，口径一致。</p>
 */
public interface ActiveMappingPointerStore {

    /** 该源当前已激活的映射；没有激活过 ⇒ 空（**不回落"最新画像"**：没有就是没有）。 */
    Optional<ActiveMappingPointer> find(long sourceId);

    /**
     * 事务内的锁定读：该源当前已激活的映射；没有 ⇒ 空。
     *
     * <p>调用方必须已处于事务中（{@code MappingActivationService#activate} 已带 {@code @Transactional}）。
     * 不允许把它当 {@link #find(long)} 的"更保险版本"到处用：锁定读会阻塞并发写，
     * 只在"读完就要决定写不写"的地方用。</p>
     */
    Optional<ActiveMappingPointer> lockBySourceId(long sourceId);

    /**
     * 落盘一次激活（同源覆盖：一个源最多一个激活指针）。
     *
     * <p>实现必须保证写入后 {@link #find(long)} 立刻能读到同一事实（"激活状态跨普通 service 调用保持"），
     * 不能只存在控制器局部变量或请求作用域里。</p>
     */
    void save(ActiveMappingPointer pointer);
}
