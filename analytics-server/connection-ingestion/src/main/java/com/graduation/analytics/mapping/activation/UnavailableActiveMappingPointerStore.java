package com.graduation.analytics.mapping.activation;

import com.graduation.analytics.common.PlatformBizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 激活指针的 **fail-closed 兜底**实现：读返回"没有激活"、写与锁定读**拒绝并上报能力缺口**。
 *
 * <p><b>本类的定位在 S2-03.1 之后变了</b>：正式存储（表 {@code source_mapping_active}，V21 迁移）
 * 已经交付，正常装配由 {@code MappingActivationPersistenceConfig} 装
 * {@link JdbcActiveMappingPointerStore}。本类只剩一种出场场景：**装配里确实没有 mapper**
 * （元数据 mapper 扫描缺包等）。它不再是"等迁移"的占位，也不再是组件扫描的默认实现
 * ——兜底被有条件地选中时配置类会 WARN 一行，不允许静默降级。</p>
 *
 * <p><b>为什么不假装成功</b>：把激活结果存在进程内 Map 里，重启即丢，却对外宣称"已激活"，
 * 会让采集侧拿着一个不存在的"激活事实"跑数据——这正是设计 §7.2 禁止的"两个 owner"。
 * 所以读路径返回空（这是**事实**：本进程没有任何持久化的已激活映射），
 * 写路径与锁定读显式失败：{@code MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE} → 501
 * （能力缺口，与 500 服务故障可区分）。</p>
 *
 * <p><b>锁定读为什么也拒绝而不是返回空</b>：{@code activate} 拿锁定读的结果判断"是不是重复激活"。
 * 返回空等于告诉它"该源从未激活过"⇒ 它会走替换分支去写——而写必然失败，只是在更晚一步失败，
 * 且中途已经把"没有激活指针"这个**未经证实**的结论喂给了判据。读不到就明确说读不到。</p>
 */
public class UnavailableActiveMappingPointerStore implements ActiveMappingPointerStore {

    private static final Logger log = LoggerFactory.getLogger(UnavailableActiveMappingPointerStore.class);

    private final AtomicBoolean warned = new AtomicBoolean(false);

    @Override
    public Optional<ActiveMappingPointer> find(long sourceId) {
        warnOnce();
        return Optional.empty();
    }

    @Override
    public Optional<ActiveMappingPointer> lockBySourceId(long sourceId) {
        throw unavailable("锁定读");
    }

    @Override
    public void save(ActiveMappingPointer pointer) {
        throw unavailable("写入（sourceId=" + pointer.sourceId() + "）");
    }

    private PlatformBizException unavailable(String action) {
        warnOnce();
        return new PlatformBizException(PlatformBizException.MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE,
                "本次装配没有激活指针持久化能力（未装配 SourceMappingActiveMapper）：" + action
                        + "无法进行。因此本次激活未生效，**不得**按已激活处理");
    }

    private void warnOnce() {
        if (warned.compareAndSet(false, true)) {
            log.warn("激活指针持久化不可用（未装配 SourceMappingActiveMapper）：本进程内所有源的"
                    + "'当前激活映射'都按【不存在】处理；v2 严格画像的采集会被 fail-closed 拒绝，"
                    + "v1 兼容画像走 legacy 直通分支不受影响。正常装配下不应出现本行。");
        }
    }
}
