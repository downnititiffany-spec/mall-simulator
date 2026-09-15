package com.graduation.analytics.mapping.activation;

import com.graduation.analytics.common.PlatformBizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 激活指针的**占位**持久化实现：读返回"没有激活"、写**拒绝并上报能力缺口**（S2-03 决策门）。
 *
 * <p><b>为什么存在</b>：激活指针必须落在一个正式存储上，而现有 schema 没有能承载它的位置：
 * {@code source_registry} 只有 {@code profile_version VARCHAR(32)}（画像自声明的版本，不是内容哈希），
 * {@code runtime_profile} 没有任何 mapping 列，全库也没有第二处 source/mapping 维度的 checksum 列。
 * 要真正落库就必须新增列/建表 ⇒ 新增一条正式 Flyway 迁移（V21）⇒ 属总控决策门，本轮**不自行加迁移**。</p>
 *
 * <p><b>为什么不假装成功</b>：把激活结果存在进程内 Map 里，重启即丢，却对外宣称"已激活"，
 * 会让采集侧拿着一个不存在的"激活事实"跑数据——这正是设计 §7.2 禁止的"两个 owner"。
 * 所以读路径返回空（这是**事实**：当前没有任何已激活映射），写路径显式失败：
 * {@code MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE} → 501（能力缺口，与 500 服务故障可区分）。</p>
 *
 * <p><b>对生产的实际影响</b>（必须如实说明）：当前登记源 {@code mock-mall} 的画像
 * {@code analytics-server/source-profiles/mock-mall.v1.json} 是 v1 兼容语法，采集走
 * {@code SourceMapping.legacy()} 直通分支，**不查激活指针**，故既有采集行为不变；
 * 只有 v2 严格画像会因"没有激活指针"被 fail-closed 拒绝（{@code MAPPING_NOT_ACTIVE}，409）。
 * 迁移落地后删除本类、换成正式存储实现即可，端口与调用方都不动。</p>
 */
@Component
public class UnavailableActiveMappingPointerStore implements ActiveMappingPointerStore {

    private static final Logger log = LoggerFactory.getLogger(UnavailableActiveMappingPointerStore.class);

    private final AtomicBoolean warned = new AtomicBoolean(false);

    @Override
    public Optional<ActiveMappingPointer> find(long sourceId) {
        if (warned.compareAndSet(false, true)) {
            log.warn("激活指针持久化尚未落地（需新增正式 Flyway 迁移，属决策门）：本次进程内所有源的"
                    + "'当前激活映射'都按【不存在】处理；v2 严格画像的采集会被 fail-closed 拒绝，"
                    + "v1 兼容画像走 legacy 直通分支不受影响。");
        }
        return Optional.empty();
    }

    @Override
    public void save(ActiveMappingPointer pointer) {
        throw new PlatformBizException(PlatformBizException.MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE,
                "激活指针的正式持久化尚未交付：source_registry 无 checksum 列、runtime_profile 无 mapping 列，"
                        + "落库需新增正式 Flyway 迁移（属总控决策门，本轮不自行加迁移）。"
                        + "因此本次激活未生效，**不得**按已激活处理（sourceId=" + pointer.sourceId() + "）");
    }
}
