package com.graduation.mall.metric;

import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.EventEnvelope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LOCAL 模式 ADS 计算器（阶段 6）：口径完全来自 docs/contracts/metric-dictionary.md v1
 * （§21.3 经营基础指标、§21.4 宽松用户漏斗）。
 * 集群模式由 spark-jobs 产出 ADS，发布机制（§21.11）完全一致。
 */
@Component
public class MetricCalculator {

    public record MetricDataset(String businessDate,
                                Map<String, BigDecimal> metrics,
                                Map<String, Long> funnel,
                                Map<String, String> units) {
    }

    private static final Set<String> BEHAVIORS =
            Set.of("view", "favorite", "cart_add", "cart_remove", "search");

    public MetricDataset compute(List<EventEnvelope> events, String businessDate) {
        long pv = 0, favCnt = 0, cartCnt = 0;
        Set<String> viewUsers = new HashSet<>();
        Set<String> dauUsers = new HashSet<>();
        Set<String> intentUsers = new HashSet<>();     // favorite ∪ cart_add
        Set<String> orderUsers = new HashSet<>();
        Set<String> cartAddUsers = new HashSet<>();
        Set<String> payUsers = new HashSet<>();

        Map<String, BigDecimal> paidByOrder = new HashMap<>();
        Map<String, BigDecimal> totalByOrder = new HashMap<>();
        Map<String, BigDecimal> refundByOrder = new HashMap<>();
        Set<String> refundedOrders = new HashSet<>();

        for (EventEnvelope e : events) {
            String userId = str(e.payload().get("user_id"));
            switch (e.eventType()) {
                case EventContract.BEHAVIOR -> {
                    dauUsers.add(userId);
                    String bt = str(e.payload().get("behavior_type"));
                    if ("view".equals(bt)) {
                        pv++;
                        viewUsers.add(userId);
                    } else if ("favorite".equals(bt)) {
                        favCnt++;
                        intentUsers.add(userId);
                    } else if ("cart_add".equals(bt)) {
                        cartCnt++;
                        cartAddUsers.add(userId);
                        intentUsers.add(userId);
                    }
                }
                case EventContract.ORDER_CREATED -> {
                    String orderId = str(e.payload().get("order_id"));
                    totalByOrder.put(orderId, dec(e.payload().get("total_amount")));
                    orderUsers.add(userId);
                }
                case EventContract.ORDER_PAID -> {
                    String orderId = str(e.payload().get("order_id"));
                    paidByOrder.put(orderId, dec(e.payload().get("amount")));
                    payUsers.add(userId);
                }
                case EventContract.REFUND_COMPLETED -> {
                    String orderId = str(e.payload().get("order_id"));
                    refundByOrder.merge(orderId, dec(e.payload().get("amount")), BigDecimal::add);
                    refundedOrders.add(orderId);
                }
                default -> {
                }
            }
        }

        BigDecimal gmv = paidByOrder.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refunds = refundByOrder.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netSale = gmv.subtract(refunds);
        int paidOrderCount = paidByOrder.size();
        BigDecimal avgOrder = paidOrderCount == 0 ? null
                : gmv.divide(BigDecimal.valueOf(paidOrderCount), 2, RoundingMode.HALF_UP);
        BigDecimal refundRate = paidOrderCount == 0 ? null
                : BigDecimal.valueOf(refundedOrders.size())
                .divide(BigDecimal.valueOf(paidOrderCount), 2, RoundingMode.HALF_UP);
        BigDecimal buyRate = viewUsers.isEmpty() ? null
                : BigDecimal.valueOf(payUsers.size())
                .divide(BigDecimal.valueOf(viewUsers.size()), 4, RoundingMode.HALF_UP);
        BigDecimal cartRate = viewUsers.isEmpty() ? null
                : BigDecimal.valueOf(cartAddUsers.size())
                .divide(BigDecimal.valueOf(viewUsers.size()), 4, RoundingMode.HALF_UP);

        Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        metrics.put("pv", bd(pv));
        metrics.put("uv", bd(viewUsers.size()));
        metrics.put("dau", bd(dauUsers.size()));
        metrics.put("fav_cnt", bd(favCnt));
        metrics.put("cart_add_cnt", bd(cartCnt));
        metrics.put("paid_order_cnt", bd(paidOrderCount));
        metrics.put("gmv", gmv.setScale(4));
        metrics.put("net_sale", netSale.setScale(4));
        if (avgOrder != null) {
            metrics.put("avg_order_value", avgOrder.setScale(4));
        } else {
            metrics.put("avg_order_value", null);
        }
        if (refundRate != null) {
            metrics.put("refund_rate", refundRate);
        } else {
            metrics.put("refund_rate", null);
        }
        if (buyRate != null) {
            metrics.put("buy_rate", buyRate);
        } else {
            metrics.put("buy_rate", null);
        }
        if (cartRate != null) {
            metrics.put("cart_rate", cartRate);
        } else {
            metrics.put("cart_rate", null);
        }
        // 移除空值指标（无可计算数据 → 不发布，页面显示"无可计算数据"）
        metrics.entrySet().removeIf(en -> en.getValue() == null);

        Map<String, Long> funnel = new LinkedHashMap<>();
        funnel.put("view", (long) viewUsers.size());
        funnel.put("intent", (long) intentUsers.size());
        funnel.put("order", (long) orderUsers.size());
        funnel.put("pay", (long) payUsers.size());

        Map<String, String> units = new HashMap<>();
        units.put("pv", "次");
        units.put("gmv", "元");
        units.put("net_sale", "元");
        units.put("avg_order_value", "元");

        return new MetricDataset(businessDate, metrics, funnel, units);
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static BigDecimal dec(Object v) {
        return new BigDecimal(String.valueOf(v));
    }
}