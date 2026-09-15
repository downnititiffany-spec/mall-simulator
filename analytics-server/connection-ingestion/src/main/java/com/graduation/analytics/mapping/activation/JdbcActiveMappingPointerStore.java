package com.graduation.analytics.mapping.activation;

import com.graduation.analytics.mapping.activation.entity.SourceMappingActiveEntity;
import com.graduation.analytics.mapping.activation.mapper.SourceMappingActiveMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 激活指针的**正式**落库实现（S2-03.1）：表 {@code source_mapping_active}（V21 迁移），一源一行。
 *
 * <p><b>它凭什么算"正式"</b>：激活状态写进元数据库后**跨进程重启、跨 service 调用保持**
 * （内存实现只能在本进程内"看起来生效"）；采集侧下一次跑用的就是这里读到的指针，
 * 因此"预览过的画像 == 采集正在用的画像"不再依赖任何进程内状态。</p>
 *
 * <p><b>只做映射与往返，不做业务判断</b>：准入判据（报告是否可激活、三处 checksum 是否相等）
 * 全在 {@code MappingActivationService}；"同内容 ⇒ 幂等"的判据在 {@link ActiveMappingPointer#sameContentAs}。
 * 本类若自己再加一层"看起来一样就不写"的判断，就会出现第二个判据来源。</p>
 *
 * <p><b>读写姿势</b>：{@link #lockBySourceId(long)} 走 {@code SELECT ... FOR UPDATE}
 * （供 activate 在事务内"读完就决定写不写"），{@link #find(long)} 走普通读（供采集侧高频只读）。</p>
 */
public class JdbcActiveMappingPointerStore implements ActiveMappingPointerStore {

    private final SourceMappingActiveMapper mapper;

    public JdbcActiveMappingPointerStore(SourceMappingActiveMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ActiveMappingPointer> find(long sourceId) {
        return Optional.ofNullable(mapper.findById(sourceId)).map(JdbcActiveMappingPointerStore::toPointer);
    }

    @Override
    @Transactional
    public Optional<ActiveMappingPointer> lockBySourceId(long sourceId) {
        return Optional.ofNullable(mapper.lockById(sourceId)).map(JdbcActiveMappingPointerStore::toPointer);
    }

    @Override
    @Transactional
    public void save(ActiveMappingPointer pointer) {
        mapper.upsert(toEntity(pointer));
    }

    private static ActiveMappingPointer toPointer(SourceMappingActiveEntity row) {
        return new ActiveMappingPointer(
                row.getSourceId(),
                // source_code 不是本表的列：它由 mapper 的 JOIN source_registry 取回（唯一 owner 仍是源登记表）。
                row.getSourceCode(),
                row.getProfileRef(),
                row.getProfileVersion(),
                row.getProfileChecksum(),
                row.getContractVersion(),
                row.getContractChecksum(),
                row.getReportId(),
                row.getActivatedAt(),
                row.getActivatedBy());
    }

    private static SourceMappingActiveEntity toEntity(ActiveMappingPointer pointer) {
        SourceMappingActiveEntity row = new SourceMappingActiveEntity();
        row.setSourceId(pointer.sourceId());
        row.setProfileRef(pointer.profilePath());
        row.setProfileVersion(pointer.profileVersion());
        row.setProfileChecksum(pointer.profileChecksum());
        row.setContractVersion(pointer.contractVersion());
        row.setContractChecksum(pointer.contractChecksum());
        row.setReportId(pointer.reportId());
        row.setActivatedAt(pointer.activatedAt());
        row.setActivatedBy(pointer.activatedBy());
        return row;
    }
}
