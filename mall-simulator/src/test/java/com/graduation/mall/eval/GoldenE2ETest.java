package com.graduation.mall.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.mall.ingestion.IngestionService;
import com.graduation.mall.metric.MetricStore;
import com.graduation.mall.metric.entity.MetricValue;
import com.graduation.mall.outbox.TraceContext;
import com.graduation.mall.pipeline.PipelineService;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 黄金数据端到端回归（§27.1 黄金数据集/§13 测试金字塔的数据链路层）：
 * 黄金事件 → 采集（30 行 SUCCESS）→ 流水线（7 阶段）→ 快照 ACTIVE →
 * MetricStore 查询 == 人工核算标准答案。任何算法/口径变化先跑此闸门。
 */
class GoldenE2ETest extends MallTestSupport {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private MetricStore metricStore;

    private Path copyGolden() throws IOException {
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path src = repoRoot.resolve("tests/golden-dataset/events/golden-20260901.jsonl");
        Path dir = LANDING.get().resolve("events");
        Files.createDirectories(dir);
        return Files.copy(src, dir.resolve("golden-20260901.jsonl"), StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    @DisplayName("黄金数据全链路：30 行采集 → 流水线 SUCCESS → 指标全等于标准答案")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void goldenEndToEnd() throws IOException {
        copyGolden();

        // 1) 采集（§5.2.9 验收：30 行全部落地，无隔离）
        IngestionService.RunResult ingestion = ingestionService.runOne(TraceContext.create());
        assertEquals("SUCCESS", ingestion.status());
        assertEquals(30, ingestion.recordCount(), "黄金数据 30 行必须全部采集落地");
        assertEquals(0, ingestion.quarantineCount());

        // 2) 流水线（§23.1：7 阶段全成功）
        PipelineService.RunResult pipeline = pipelineService.run(1L, "DAILY_CORE",
                LocalDateTime.of(2026, 9, 1, 0, 0), "golden-v1", "idem-golden-e2e-1",
                TraceContext.create().traceId());
        assertEquals("SUCCESS", pipeline.status(), "流水线必须成功: " + pipeline.errorCode());
        assertEquals(7, pipeline.stages().size());

        // 3) 指标 API（MetricStore 查询）== 黄金标准答案
        List<MetricValue> values = metricStore.query(new MetricStore.MetricQuery(null, true));
        assertTrue(!values.isEmpty(), "必须有 ACTIVE 快照指标");

        assertMetric(values, "pv", "6");
        assertMetric(values, "uv", "3");
        assertMetric(values, "dau", "3");
        assertMetric(values, "paid_order_cnt", "2");
        assertMetric(values, "gmv", "1275.00");
        assertMetric(values, "net_sale", "1225.00");
        assertMetric(values, "avg_order_value", "637.50");
        assertMetric(values, "refund_rate", "0.50");
        assertMetric(values, "buy_rate", "0.6667");
    }

    private void assertMetric(List<MetricValue> values, String code, String expect) {
        MetricValue v = values.stream()
                .filter(m -> m.getMetricCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少指标 " + code));
        assertEquals(0, new BigDecimal(expect).compareTo(v.getMetricValue()),
                code + " 必须等于标准答案 " + expect + "，实际 " + v.getMetricValue());
    }
}