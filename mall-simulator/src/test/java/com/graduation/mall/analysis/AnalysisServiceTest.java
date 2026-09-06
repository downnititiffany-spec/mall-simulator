package com.graduation.mall.analysis;

import com.graduation.mall.analysis.AnalysisService.ActiveDay;
import com.graduation.mall.analysis.AnalysisService.FunnelStage;
import com.graduation.mall.analysis.AnalysisService.Overview;
import com.graduation.mall.analysis.AnalysisService.ProductRankItem;
import com.graduation.mall.analysis.AnalysisService.SalesDay;
import com.graduation.mall.metric.entity.MetricSnapshot;
import com.graduation.mall.metric.entity.MetricValue;
import com.graduation.mall.metric.mapper.MetricSnapshotMapper;
import com.graduation.mall.metric.mapper.MetricValueMapper;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专题分析服务测试（§5.5/§5.6）：黄金数据驱动，口径与指标字典一致。
 */
class AnalysisServiceTest extends MallTestSupport {

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private MetricSnapshotMapper snapshotMapper;

    @Autowired
    private MetricValueMapper valueMapper;

    private void copyGolden() throws IOException {
        Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path src = repoRoot.resolve("tests/golden-dataset/events/golden-20260901.jsonl");
        Path dir = LANDING.get().resolve("events");
        Files.createDirectories(dir);
        Files.copy(src, dir.resolve("golden-20260901.jsonl"), StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    @DisplayName("销售趋势：当日 2 单 GMV=1275 买家=2（=黄金标准）")
    void salesTrendMatchesGolden() throws IOException {
        copyGolden();
        List<SalesDay> sales = analysisService.salesTrend(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
        assertEquals(1, sales.size());
        assertEquals(2, sales.get(0).orderCount());
        assertEquals(0, new BigDecimal("1275.00").compareTo(sales.get(0).saleAmount()));
        assertEquals(2, sales.get(0).buyerCount());
    }

    @Test
    @DisplayName("商品热度：P1(耳机) 最高，TopN 排序与 §21.7 公式一致")
    void productRankMatchesHeatFormula() throws IOException {
        copyGolden();
        List<ProductRankItem> ranks = analysisService.productRank(10,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));

        assertEquals(4, ranks.size());
        assertEquals(1, ranks.get(0).rank());
        // P1: view2+fav1+cart1 → 1×ln3+2×ln2+3×ln2
        double expected = 1.0 * Math.log1p(2) + 2.0 * Math.log1p(1) + 3.0 * Math.log1p(1);
        assertEquals(0, BigDecimal.valueOf(expected).setScale(4, java.math.RoundingMode.HALF_UP)
                .compareTo(ranks.get(0).heat()), "热度必须等于对数公式值");
        assertTrue(ranks.get(0).pv() >= ranks.get(1).pv(), "头部商品浏览量最高");
    }

    @Test
    @DisplayName("漏斗：宽松用户口径 view=3/intent=2/order=3/pay=2")
    void funnelMatchesGolden() throws IOException {
        copyGolden();
        List<FunnelStage> stages = analysisService.funnelDay(LocalDate.of(2026, 9, 1));
        assertEquals(4, stages.size());
        assertEquals("view", stages.get(0).stage());
        assertEquals(3, stages.get(0).users());
        // 阶段人数（§21.4 宽松口径：每阶段独立去重）
        assertEquals(2, stages.get(1).users(), "intent=收藏或加购用户（用户1+用户2）");
        assertEquals(3, stages.get(2).users(), "order=创建订单用户");
        assertEquals(2, stages.get(3).users(), "pay=支付用户");
        assertNotNull(stages.get(3).rate(), "pay 阶段转化率可计算");
    }

    @Test
    @DisplayName("活跃趋势与大盘：快照指标进入 overview")
    void overviewIncludesActiveSnapshot() throws IOException {
        copyGolden();
        MetricSnapshot s = new MetricSnapshot();
        s.setSnapshotId("S_OVERVIEW");
        s.setRuntimeProfileId(1L);
        s.setBusinessTime(LocalDateTime.of(2026, 9, 1, 0, 0));
        s.setStatus(MetricSnapshot.STATUS_ACTIVE);
        s.setVersion(1);
        s.setDataUpdatedAt(LocalDateTime.now());
        s.setSource("test");
        s.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insert(s);

        MetricValue v = new MetricValue();
        v.setSnapshotId("S_OVERVIEW");
        v.setMetricCode("gmv");
        v.setMetricValue(new BigDecimal("1275.00"));
        v.setUnit("元");
        v.setPeriod("day:2026-09-01");
        v.setDefinitionVersion("v1");
        valueMapper.insert(v);

        Overview overview = analysisService.overview(null, null);
        assertEquals("S_OVERVIEW", overview.snapshotId());
        BigDecimal gmv = new BigDecimal(String.valueOf(
                ((java.util.Map<?, ?>) overview.snapshotMetrics().get("gmv")).get("value")));
        assertEquals(0, new BigDecimal("1275.00").compareTo(gmv), "大盘必须包含快照 GMV");

        List<ActiveDay> active = analysisService.userActiveTrend(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1));
        assertEquals(1, active.size());
        assertEquals(3, active.get(0).dau(), "黄金数据当日 3 个活跃用户");
    }
}