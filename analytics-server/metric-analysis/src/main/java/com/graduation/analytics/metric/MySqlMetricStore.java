package com.graduation.analytics.metric;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.metric.entity.MetricSnapshot;
import com.graduation.analytics.metric.entity.MetricValue;
import com.graduation.analytics.metric.mapper.MetricSnapshotMapper;
import com.graduation.analytics.metric.mapper.MetricValueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MySQL 指标服务实现（§19.3/§21.11）：
 * publish 在单事务内：写 metric_value（按快照整体覆盖）→ 旧 ACTIVE→ARCHIVED → 新快照→ACTIVE。
 * 幂等：同快照重复发布为全量覆盖，不会产生重复行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MySqlMetricStore implements MetricStore {

    private final MetricValueMapper valueMapper;
    private final MetricSnapshotMapper snapshotMapper;

    @Override
    public String type() {
        return "mysql";
    }

    @Override
    public List<MetricValue> query(MetricQuery query) {
        String snapshotId = query.snapshotId();
        if ((snapshotId == null || snapshotId.isBlank()) && query.latestActive()) {
            MetricSnapshot active = snapshotMapper.selectOne(new LambdaQueryWrapper<MetricSnapshot>()
                    .eq(MetricSnapshot::getStatus, MetricSnapshot.STATUS_ACTIVE)
                    .orderByDesc(MetricSnapshot::getId)
                    .last("LIMIT 1"));
            if (active == null) {
                return List.of();
            }
            snapshotId = active.getSnapshotId();
        }
        if (snapshotId == null || snapshotId.isBlank()) {
            return List.of();
        }
        return valueMapper.selectList(new LambdaQueryWrapper<MetricValue>()
                .eq(MetricValue::getSnapshotId, snapshotId)
                .orderByAsc(MetricValue::getMetricCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int publish(SnapshotRef snapshot, List<MetricValue> datasets) {
        // 1. 整体覆盖该快照的指标值（幂等：重复发布不产生重复行）
        valueMapper.delete(new LambdaQueryWrapper<MetricValue>()
                .eq(MetricValue::getSnapshotId, snapshot.snapshotId()));
        LocalDateTime now = LocalDateTime.now();
        for (MetricValue v : datasets) {
            v.setUpdatedAt(now);
            valueMapper.insert(v);
        }
        // 2. 同环境旧 ACTIVE → ARCHIVED
        snapshotMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MetricSnapshot>()
                .eq(MetricSnapshot::getRuntimeProfileId, snapshot.runtimeProfileId())
                .eq(MetricSnapshot::getStatus, MetricSnapshot.STATUS_ACTIVE)
                .set(MetricSnapshot::getStatus, MetricSnapshot.STATUS_ARCHIVED));
        // 3. 新快照 → ACTIVE（唯一 ACTIVE 由同一事务保证）
        int switched = snapshotMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MetricSnapshot>()
                .eq(MetricSnapshot::getSnapshotId, snapshot.snapshotId())
                .eq(MetricSnapshot::getStatus, MetricSnapshot.STATUS_VERIFYING)
                .set(MetricSnapshot::getStatus, MetricSnapshot.STATUS_ACTIVE)
                .set(MetricSnapshot::getPublishedAt, now));
        return switched;
    }

    @Override
    public HealthResult healthCheck() {
        try {
            snapshotMapper.selectCount(null);
            return new HealthResult(true, "mysql metric store ok");
        } catch (Exception e) {
            return new HealthResult(false, e.getMessage());
        }
    }
}