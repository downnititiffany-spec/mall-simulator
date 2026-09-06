package com.graduation.mall.metric;

import com.graduation.mall.metric.entity.MetricSnapshot;
import com.graduation.mall.metric.entity.MetricValue;
import com.graduation.mall.metric.mapper.MetricSnapshotMapper;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL MetricStore 测试（§21.11）：发布/查询一致、幂等覆盖、唯一 ACTIVE。
 */
class MySqlMetricStoreTest extends MallTestSupport {

    @Autowired
    private MetricStore metricStore;

    @Autowired
    private MetricSnapshotMapper snapshotMapper;

    private void insertSnapshot(String snapshotId, String status, int version) {
        MetricSnapshot s = new MetricSnapshot();
        s.setSnapshotId(snapshotId);
        s.setRuntimeProfileId(1L);
        s.setBusinessTime(java.time.LocalDateTime.of(2026, 9, 1, 0, 0));
        s.setStatus(status);
        s.setVersion(version);
        s.setDataUpdatedAt(java.time.LocalDateTime.now());
        s.setSource("test");
        s.setCreatedAt(java.time.LocalDateTime.now());
        snapshotMapper.insert(s);
    }

    private List<MetricValue> dataset(String snapshotId, String metric, String value) {
        MetricValue v = new MetricValue();
        v.setSnapshotId(snapshotId);
        v.setMetricCode(metric);
        v.setMetricValue(new BigDecimal(value));
        v.setUnit("元");
        v.setPeriod("day:2026-09-01");
        v.setDefinitionVersion("v1");
        return List.of(v);
    }

    @Test
    @DisplayName("发布后查询一致；重复发布幂等覆盖")
    void publishAndQueryConsistent() {
        insertSnapshot("S1", MetricSnapshot.STATUS_VERIFYING, 1);
        metricStore.publish(new MetricStore.SnapshotRef("S1", 1L, "day:2026-09-01"),
                dataset("S1", "gmv", "1275.00"));

        var rows = metricStore.query(new MetricStore.MetricQuery("S1", false));
        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("1275.00").compareTo(rows.get(0).getMetricValue()));

        // 幂等：重复发布覆盖而非追加
        metricStore.publish(new MetricStore.SnapshotRef("S1", 1L, "day:2026-09-01"),
                dataset("S1", "gmv", "1299.99"));
        rows = metricStore.query(new MetricStore.MetricQuery("S1", false));
        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("1299.99").compareTo(rows.get(0).getMetricValue()));
    }

    @Test
    @DisplayName("新快照 ACTIVE 时旧 ACTIVE 自动 ARCHIVED，同环境唯一 ACTIVE")
    void onlyOneActivePerProfile() {
        insertSnapshot("OLD", MetricSnapshot.STATUS_ACTIVE, 1);
        insertSnapshot("NEW", MetricSnapshot.STATUS_VERIFYING, 1);

        metricStore.publish(new MetricStore.SnapshotRef("NEW", 1L, "day:2026-09-01"),
                dataset("NEW", "gmv", "100.00"));

        assertEquals(MetricSnapshot.STATUS_ARCHIVED, snapshotMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MetricSnapshot>()
                        .eq(MetricSnapshot::getSnapshotId, "OLD")).getStatus());
        assertEquals(MetricSnapshot.STATUS_ACTIVE, snapshotMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MetricSnapshot>()
                        .eq(MetricSnapshot::getSnapshotId, "NEW")).getStatus());

        long actives = snapshotMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MetricSnapshot>()
                        .eq(MetricSnapshot::getRuntimeProfileId, 1L)
                        .eq(MetricSnapshot::getStatus, MetricSnapshot.STATUS_ACTIVE));
        assertEquals(1, actives);
    }

    @Test
    @DisplayName("健康检查通过")
    void healthOk() {
        MetricStore.HealthResult health = metricStore.healthCheck();
        assertTrue(health.ok(), health.detail());
    }
}