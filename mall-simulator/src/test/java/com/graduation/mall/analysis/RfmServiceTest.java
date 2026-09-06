package com.graduation.mall.analysis;

import com.graduation.mall.analysis.RfmService.RfmReport;
import com.graduation.mall.analysis.RfmService.RfmUser;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFM 分层服务测试（§21.6）：构造 3 用户的差异化订单 → 分桶/标签/分布/生命周期。
 */
class RfmServiceTest extends MallTestSupport {

    @Autowired
    private RfmService rfmService;

    @Autowired
    private JdbcTemplate jdbc;

    private void paid(long userId, BigDecimal amount, LocalDateTime paidAt) {
        long orderId = System.nanoTime() % 1_000_000 + userId % 1000;
        jdbc.update("INSERT INTO mall_order (order_id, user_id, status, total_amount, created_at, paid_at) VALUES (?,?,?,?,?,?)",
                orderId, userId, "PAID", amount, paidAt.minusDays(1), paidAt);
    }

    @Test
    @DisplayName("三个差异化用户 → R 反向/F/M 正向分桶与八类标签、生命周期")
    void reportBucketsAndLabels() {
        LocalDateTime now = LocalDateTime.now();
        // 高价值：最近购买、5 单、5000 元
        long u1 = 900001;
        for (int i = 0; i < 5; i++) {
            paid(u1, BigDecimal.valueOf(1000), now.minusDays(2 + i));
        }
        // 流失风险：60+ 天前 1 单 100 元
        long u2 = 900002;
        paid(u2, BigDecimal.valueOf(100), now.minusDays(70));
        // 沉默：45 天前 2 单 300 元
        long u3 = 900003;
        paid(u3, BigDecimal.valueOf(150), now.minusDays(45));
        paid(u3, BigDecimal.valueOf(150), now.minusDays(46));

        RfmReport report = rfmService.rfmReport(50);
        assertTrue(report.users().size() >= 3, "至少 3 用户参与分层");

        RfmUser a = report.users().stream().filter(u -> u.userId().equals(u1)).findFirst().orElseThrow();
        RfmUser b = report.users().stream().filter(u -> u.userId().equals(u2)).findFirst().orElseThrow();
        RfmUser c = report.users().stream().filter(u -> u.userId().equals(u3)).findFirst().orElseThrow();

        // 分桶：u1 的 R 应该 ≥ u2 的 R（反向），M 应该最高分
        assertTrue(a.mScore() >= c.mScore() && c.mScore() >= b.mScore(), "M 正向分桶");
        // u1 高 M 高 R 高 F → 重要价值（在 3 人分布中应占优）
        assertEquals("重要价值", a.label(), "最近购买+多单+高金额 → 重要价值");
        assertEquals("流失风险", b.lifecycle(), "70 天未购买 → 流失风险");
        assertEquals("沉默", c.lifecycle(), "45 天未购买 → 沉默");
        assertTrue(report.distribution().values().stream().mapToLong(Long::longValue).sum() == 3,
                "分布合计 = 用户数");
    }
}