package com.graduation.mall.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.mall.metric.entity.MetricValue;
import com.graduation.mall.metric.entity.MetricSnapshot;
import com.graduation.mall.metric.MetricStore;
import com.graduation.mall.metric.mapper.MetricSnapshotMapper;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.EventEnvelope;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 专题分析服务（阶段 7，§5.5/§5.6）：从 landing/events 按口径实时聚合。
 * 口径唯一来源 docs/contracts/metric-dictionary.md v1；与 MetricCalculator 共享语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final Set<String> BEHAVIORS =
            Set.of("view", "favorite", "cart_add", "cart_remove", "search");

    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final MetricStore metricStore;
    private final MetricSnapshotMapper snapshotMapper;

    // ── 输出 DTO ──────────────────────────────────────────────────────────

    public record SalesDay(String date, long orderCount, BigDecimal saleAmount, long buyerCount) {
    }

    public record ProductRankItem(Long productId, String productName, String categoryName,
                                  long pv, long fav, long cart, long buy, BigDecimal heat, int rank) {
    }

    public record FunnelStage(String stage, long users, BigDecimal rate) {
    }

    public record ActiveDay(String date, long dau, long behaviorCount) {
    }

    public record Overview(Map<String, Object> snapshotMetrics, List<SalesDay> salesTrend,
                           List<ActiveDay> activeTrend, String snapshotId) {
    }

    // ── 数据装载（实时解析 events，按日过滤） ──────────────────────────────

    private List<EventEnvelope> loadEvents(LocalDate from, LocalDate to) {
        List<EventEnvelope> events = new ArrayList<>();
        Path eventsDir = Path.of(environment.getProperty("mall.landing.path", "./landing")).resolve("events");
        if (!Files.isDirectory(eventsDir)) {
            return events;
        }
        try (Stream<Path> list = Files.list(eventsDir)) {
            for (Path f : list.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        EventEnvelope e = EventEnvelope.fromJson(line, objectMapper);
                        if (e.eventTime() != null && e.eventTime().length() >= 10) {
                            LocalDate eventDate = LocalDate.parse(e.eventTime().substring(0, 10));
                            if (!eventDate.isBefore(from) && !eventDate.isAfter(to)) {
                                events.add(e);
                            }
                        }
                    } catch (Exception ex) {
                        // 坏行跳过（采集层已隔离，此处防御）
                    }
                }
            }
        } catch (IOException e) {
            log.warn("analysis load events failed: {}", e.getMessage());
        }
        return events;
    }

    // ── 销售趋势（§5.6.2：order_paid 按日聚合） ────────────────────────────

    public List<SalesDay> salesTrend(LocalDate from, LocalDate to) {
        Map<String, SalesAccum> byDay = new TreeMap<>();
        for (EventEnvelope e : loadEvents(from, to)) {
            if (!EventContract.ORDER_PAID.equals(e.eventType())) {
                continue;
            }
            String date = e.eventTime().substring(0, 10);
            SalesAccum acc = byDay.computeIfAbsent(date, d -> new SalesAccum());
            acc.orderCount++;
            acc.saleAmount = acc.saleAmount.add(dec(e.payload().get("amount")));
            acc.buyers.add(String.valueOf(e.payload().get("user_id")));
        }
        List<SalesDay> result = new ArrayList<>();
        byDay.forEach((date, acc) -> result.add(new SalesDay(date, acc.orderCount,
                acc.saleAmount.setScale(2), acc.buyers.size())));
        return result;
    }

    private static final class SalesAccum {
        long orderCount = 0;
        BigDecimal saleAmount = BigDecimal.ZERO;
        final Set<String> buyers = new java.util.HashSet<>();
    }

    // ── 商品热度 TopN（§5.6.1/§21.7 对数权重） ─────────────────────────────

    public List<ProductRankItem> productRank(int topN, LocalDate from, LocalDate to) {
        Map<String, BehaviorCount> byProduct = new HashMap<>();
        for (EventEnvelope e : loadEvents(from, to)) {
            if (!EventContract.BEHAVIOR.equals(e.eventType())) {
                continue;
            }
            String productId = str(e.payload().get("product_id"));
            if (productId.isEmpty()) {
                continue;
            }
            BehaviorCount bc = byProduct.computeIfAbsent(productId, p -> new BehaviorCount());
            switch (str(e.payload().get("behavior_type"))) {
                case "view" -> bc.pv++;
                case "favorite" -> bc.fav++;
                case "cart_add" -> bc.cart++;
                default -> {
                }
            }
        }
        List<ProductRankItem> items = new ArrayList<>();
        byProduct.forEach((productId, bc) -> {
            double heat = 1.0 * Math.log1p(bc.pv) + 2.0 * Math.log1p(bc.fav)
                    + 3.0 * Math.log1p(bc.cart) + 5.0 * Math.log1p(bc.buy);
            items.add(new ProductRankItem(Long.valueOf(productId), "商品-" + productId, "",
                    bc.pv, bc.fav, bc.cart, bc.buy,
                    BigDecimal.valueOf(heat).setScale(4, RoundingMode.HALF_UP), 0));
        });
        items.sort((a, b) -> b.heat().compareTo(a.heat()));
        List<ProductRankItem> ranked = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, items.size()); i++) {
            ProductRankItem it = items.get(i);
            ranked.add(new ProductRankItem(it.productId(), it.productName(),
                    it.categoryName(), it.pv(), it.fav(), it.cart(), it.buy(), it.heat(), i + 1));
        }
        return ranked;
    }

    private static final class BehaviorCount {
        long pv = 0;
        long fav = 0;
        long cart = 0;
        long buy = 0;
    }

    // ── 漏斗（§21.4 宽松用户口径） ────────────────────────────────────────

    public List<FunnelStage> funnelDay(LocalDate date) {
        Set<String> view = new java.util.HashSet<>();
        Set<String> intent = new java.util.HashSet<>();
        Set<String> order = new java.util.HashSet<>();
        Set<String> pay = new java.util.HashSet<>();
        for (EventEnvelope e : loadEvents(date, date)) {
            String userId = str(e.payload().get("user_id"));
            switch (e.eventType()) {
                case EventContract.BEHAVIOR -> {
                    String bt = str(e.payload().get("behavior_type"));
                    if ("view".equals(bt)) {
                        view.add(userId);
                    } else if ("favorite".equals(bt) || "cart_add".equals(bt)) {
                        intent.add(userId);
                    }
                }
                case EventContract.ORDER_CREATED -> order.add(userId);
                case EventContract.ORDER_PAID -> pay.add(userId);
                default -> {
                }
            }
        }
        List<FunnelStage> stages = new ArrayList<>();
        stages.add(new FunnelStage("view", view.size(), null));
        stages.add(new FunnelStage("intent", intent.size(), rate(intent.size(), view.size())));
        stages.add(new FunnelStage("order", order.size(), rate(order.size(), intent.size())));
        stages.add(new FunnelStage("pay", pay.size(), rate(pay.size(), order.size())));
        return stages;
    }

    private BigDecimal rate(long next, long prev) {
        if (prev == 0) {
            return null;
        }
        return BigDecimal.valueOf(next).divide(BigDecimal.valueOf(prev), 4, RoundingMode.HALF_UP);
    }

    // ── 活跃趋势（§5.5.1 用户活跃趋势） ───────────────────────────────────

    public List<ActiveDay> userActiveTrend(LocalDate from, LocalDate to) {
        Map<String, ActiveAccum> byDay = new TreeMap<>();
        for (EventEnvelope e : loadEvents(from, to)) {
            if (!EventContract.BEHAVIOR.equals(e.eventType())) {
                continue;
            }
            String date = e.eventTime().substring(0, 10);
            ActiveAccum acc = byDay.computeIfAbsent(date, d -> new ActiveAccum());
            acc.behaviorCount++;
            acc.users.add(str(e.payload().get("user_id")));
        }
        List<ActiveDay> result = new ArrayList<>();
        byDay.forEach((date, acc) -> result.add(new ActiveDay(date, acc.users.size(), acc.behaviorCount)));
        return result;
    }

    private static final class ActiveAccum {
        long behaviorCount = 0;
        final Set<String> users = new java.util.HashSet<>();
    }

    // ── 大盘（§25.1 首页：最新 ACTIVE 快照 + 近 7 日趋势） ─────────────────

    public Overview overview() {
        List<MetricValue> values = metricStore.query(new MetricStore.MetricQuery(null, true));
        Map<String, Object> metrics = new LinkedHashMap<>();
        String snapshotId = null;
        if (!values.isEmpty()) {
            snapshotId = values.get(0).getSnapshotId();
        }
        for (MetricValue v : values) {
            metrics.put(v.getMetricCode(), Map.of(
                    "value", v.getMetricValue(),
                    "unit", v.getUnit(),
                    "period", v.getPeriod(),
                    "definitionVersion", v.getDefinitionVersion()));
        }
        LocalDate today = LocalDate.now();
        List<SalesDay> sales = salesTrend(today.minusDays(6), today);
        List<ActiveDay> active = userActiveTrend(today.minusDays(6), today);
        return new Overview(metrics, sales, active, snapshotId);
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static BigDecimal dec(Object v) {
        return new BigDecimal(String.valueOf(v));
    }
}