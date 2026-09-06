package com.graduation.mall.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.mall.metric.entity.MetricSnapshot;
import com.graduation.mall.metric.entity.MetricValue;
import com.graduation.mall.metric.mapper.MetricSnapshotMapper;
import com.graduation.mall.metric.mapper.MetricValueMapper;
import com.graduation.mall.outbox.TraceContext;
import com.graduation.mall.pipeline.entity.DataQualityResult;
import com.graduation.mall.pipeline.entity.PipelineRun;
import com.graduation.mall.pipeline.mapper.DataQualityResultMapper;
import com.graduation.mall.pipeline.mapper.PipelineRunMapper;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线集成测试（§23.1/§21.11/T10）：
 * 全链 SUCCESS + 快照 ACTIVE + 指标值=黄金标准；幂等键；金额不平阻断发布。
 * NOT_SUPPORTED：服务调用独立事务并真实提交，断言看到的是一致状态。
 */
class PipelineRunTest extends MallTestSupport {

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private MetricSnapshotMapper snapshotMapper;

    @Autowired
    private MetricValueMapper valueMapper;

    @Autowired
    private DataQualityResultMapper qualityMapper;

    @Autowired
    private PipelineRunMapper runMapper;

    private Path copyGolden() throws IOException {
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path src = repoRoot.resolve("tests/golden-dataset/events/golden-20260901.jsonl");
        Path dir = LANDING.get().resolve("events");
        Files.createDirectories(dir);
        Path dst = dir.resolve("golden-20260901.jsonl");
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return dst;
    }

    @Test
    @DisplayName("黄金数据全链：SUCCESS + 快照 ACTIVE + 指标值与标准答案一致")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void fullPipelinePublishesGoldenMetrics() throws IOException {
        copyGolden();
        PipelineService.RunResult result = pipelineService.run(1L, "DAILY_CORE",
                LocalDateTime.of(2026, 9, 1, 0, 0), "golden-v1", "idem-golden-1",
                TraceContext.create().traceId());

        assertEquals("SUCCESS", result.status());
        assertEquals(7, result.stages().size(), "7 个阶段全部执行");
        result.stages().forEach(s -> assertEquals("SUCCESS", s.getStatus(),
                "阶段 " + s.getStageCode() + " 必须成功"));

        MetricSnapshot active = snapshotMapper.selectOne(new LambdaQueryWrapper<MetricSnapshot>()
                .eq(MetricSnapshot::getStatus, MetricSnapshot.STATUS_ACTIVE)
                .last("LIMIT 1"));
        assertTrue(active != null, "必须存在 ACTIVE 快照");

        MetricValue gmv = valueMapper.selectOne(new LambdaQueryWrapper<MetricValue>()
                .eq(MetricValue::getSnapshotId, active.getSnapshotId())
                .eq(MetricValue::getMetricCode, "gmv"));
        assertEquals(0, new BigDecimal("1275.00").compareTo(gmv.getMetricValue()), "GMV=1275.00");

        MetricValue net = valueMapper.selectOne(new LambdaQueryWrapper<MetricValue>()
                .eq(MetricValue::getSnapshotId, active.getSnapshotId())
                .eq(MetricValue::getMetricCode, "net_sale"));
        assertEquals(0, new BigDecimal("1225.00").compareTo(net.getMetricValue()), "净额=1225.00");

        List<DataQualityResult> rules = qualityMapper.selectList(new LambdaQueryWrapper<DataQualityResult>()
                .eq(DataQualityResult::getRunId, result.runId()));
        assertEquals(4, rules.size());
        rules.forEach(r -> assertEquals(1, r.getPassed(), "规则 " + r.getRuleCode() + " 必须通过"));
    }

    @Test
    @DisplayName("相同幂等键返回原 run，不重复执行")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void idempotencyReturnsSameRun() throws IOException {
        copyGolden();
        PipelineService.RunResult first = pipelineService.run(1L, "DAILY_CORE",
                LocalDateTime.of(2026, 9, 1, 0, 0), "golden-v1", "idem-golden-2",
                TraceContext.create().traceId());
        PipelineService.RunResult second = pipelineService.run(1L, "DAILY_CORE",
                LocalDateTime.of(2026, 9, 1, 0, 0), "golden-v1", "idem-golden-2",
                TraceContext.create().traceId());

        assertEquals(first.runId(), second.runId(), "同幂等键必须返回原 run");
        assertEquals(1L, runMapper.selectCount(new LambdaQueryWrapper<PipelineRun>()
                .eq(PipelineRun::getIdempotencyKey, "idem-golden-2")), "不得创建重复 run");
    }

    @Test
    @DisplayName("金额不平事件：QUALITY_FAILED，快照不发布，旧 ACTIVE 保留（T10）")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void amountMismatchBlocksPublish() throws IOException {
        MetricSnapshot old = new MetricSnapshot();
        old.setSnapshotId("S_PREV");
        old.setRuntimeProfileId(1L);
        old.setBusinessTime(LocalDateTime.of(2026, 8, 31, 0, 0));
        old.setStatus(MetricSnapshot.STATUS_ACTIVE);
        old.setVersion(1);
        old.setDataUpdatedAt(LocalDateTime.now());
        old.setSource("test");
        old.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insert(old);

        Path dir = LANDING.get().resolve("events");
        Files.createDirectories(dir);
        Files.write(dir.resolve("bad-20260901.jsonl"), List.of(
                mismatchOrderCreated(), mismatchOrderPaid()), StandardCharsets.UTF_8);

        PipelineService.RunResult result = pipelineService.run(1L, "DAILY_CORE",
                LocalDateTime.of(2026, 9, 1, 0, 0), "bad-v1", "idem-bad-1",
                TraceContext.create().traceId());

        assertEquals("FAILED", result.status());
        assertEquals("PIPELINE_QUALITY_FAILED", result.errorCode());

        // 旧 ACTIVE 保留；没有产生第二个快照
        assertEquals(MetricSnapshot.STATUS_ACTIVE, snapshotMapper.selectById(old.getId()).getStatus());
        assertEquals(1L, snapshotMapper.selectCount(null), "失败不得创建/发布新快照");

        List<DataQualityResult> rules = qualityMapper.selectList(new LambdaQueryWrapper<DataQualityResult>()
                .eq(DataQualityResult::getRunId, result.runId()));
        assertTrue(rules.stream().anyMatch(r -> r.getRuleCode().equals("AMOUNT_RECONCILE") && r.getPassed() == 0),
                "金额对账规则必须失败");
    }

    @Test
    @DisplayName("失败后重试成功（attempt_no 递增）")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void retryAfterFailure() throws IOException {
        // 第一轮：金额不平 → 质量失败
        Path dir = LANDING.get().resolve("events");
        Files.createDirectories(dir);
        Files.write(dir.resolve("bad-20260901.jsonl"), List.of(
                mismatchOrderCreated(), mismatchOrderPaid()), StandardCharsets.UTF_8);
        PipelineService.RunResult bad = pipelineService.run(1L, "DAILY_CORE",
                LocalDateTime.of(2026, 9, 1, 0, 0), "bad-v1", "idem-retry-1",
                TraceContext.create().traceId());
        assertEquals("FAILED", bad.status());
        assertEquals("PIPELINE_QUALITY_FAILED", bad.errorCode());

        // 修复数据：移除坏文件，放入黄金数据（09-01 事件）
        Files.deleteIfExists(dir.resolve("bad-20260901.jsonl"));
        copyGolden();

        PipelineService.RunResult retried = pipelineService.retry(bad.runId(), TraceContext.create().traceId());
        assertEquals("SUCCESS", retried.status());
        assertEquals(2, retried.attemptNo(), "重试必须递增 attempt_no");
    }

    private String mismatchOrderCreated() {
        return "{\"event_id\":\"bad-1\",\"event_type\":\"order_created\","
                + "\"event_time\":\"2026-09-01T10:00:00+08:00\",\"ingest_time\":\"2026-09-01T10:00:01+08:00\","
                + "\"source_system\":\"mock-mall\",\"schema_version\":\"1.0\",\"trace_id\":\"t\","
                + "\"payload\":{\"order_id\":\"9001\",\"user_id\":\"1\","
                + "\"items\":[],\"total_amount\":\"100.00\",\"status\":\"CREATED\","
                + "\"created_at\":\"2026-09-01T10:00:00+08:00\"}}";
    }

    private String mismatchOrderPaid() {
        return "{\"event_id\":\"bad-2\",\"event_type\":\"order_paid\","
                + "\"event_time\":\"2026-09-01T10:05:00+08:00\",\"ingest_time\":\"2026-09-01T10:05:01+08:00\","
                + "\"source_system\":\"mock-mall\",\"schema_version\":\"1.0\",\"trace_id\":\"t\","
                + "\"payload\":{\"order_id\":\"9001\",\"user_id\":\"1\",\"payment_id\":\"p1\","
                + "\"amount\":\"200.00\",\"paid_at\":\"2026-09-01T10:05:00+08:00\"}}";
    }
}