package com.graduation.analytics.mapping.activation;

import java.util.Optional;

/**
 * 激活指针的持久化端口（S2-03）。
 *
 * <p><b>为什么是"端口 + 实现"而不是直接一个 Service</b>：指针的**所有者**必须是唯一的正式存储。
 * 采集侧（{@code SourceMapper}）与激活侧（{@code MappingActivationService}）都只通过本接口读写，
 * 谁都不许自己再维护一份内存映射冒充"当前激活"（设计 §7.2：不得既写文件又写 DB 形成两个 owner）。</p>
 *
 * <p><b>落库实现的额外义务（现在还没有落库实现，登记为待办）</b>：{@code find} + {@code save} 的
 * "读-判-写"序列必须落在同一个事务里（行锁或唯一键），否则并发 activate 会互相覆盖。
 * 本轮不预先发明 {@code lock()} API——锁的形状（{@code SELECT ... FOR UPDATE} 还是 upsert 唯一键）
 * 由真实存储形态决定，先造一个没人用的锁接口只会变成下一个需要清理的假抽象。</p>
 */
public interface ActiveMappingPointerStore {

    /** 该源当前已激活的映射；没有激活过 ⇒ 空（**不回落"最新画像"**：没有就是没有）。 */
    Optional<ActiveMappingPointer> find(long sourceId);

    /**
     * 落盘一次激活（同源覆盖：一个源最多一个激活指针）。
     *
     * <p>实现必须保证写入后 {@link #find(long)} 立刻能读到同一事实（"激活状态跨普通 service 调用保持"），
     * 不能只存在控制器局部变量或请求作用域里。</p>
     */
    void save(ActiveMappingPointer pointer);
}
