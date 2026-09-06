package com.graduation.mall.generator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.mall.controller.MallDtos.CartAddReq;
import com.graduation.mall.controller.MallDtos.CreateUserReq;
import com.graduation.mall.controller.MallDtos.OrderCreateReq;
import com.graduation.mall.controller.MallDtos.OrderItemReq;
import com.graduation.mall.domain.entity.Category;
import com.graduation.mall.domain.entity.Product;
import com.graduation.mall.domain.mapper.CategoryMapper;
import com.graduation.mall.domain.mapper.ProductMapper;
import com.graduation.mall.domain.service.InventoryService;
import com.graduation.mall.domain.service.MallBusinessService;
import com.graduation.mall.domain.service.ProductManagementService;
import com.graduation.mall.generator.scenario.ScenarioRegistry;
import com.graduation.mall.outbox.EventClock;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.EventOutboxService;
import com.graduation.mall.outbox.EventPayloadFactory;
import com.graduation.mall.outbox.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景化数据生成引擎（§5.2.3/§20.2-20.3）：
 * - 分层用户（高15%/中35%/低50%）、Zipf 商品权重、24h 流量曲线、价格接受度、分类偏好；
 * - 行为混合比例 + 有限状态链（订单生命期，BehaviorChain）；
 * - 业务路径完全走既有 Service（registerUser/addCartItem/createOrder/pay/cancel/refund）；
 *   仅 view/favorite/search/cart_remove 为日志型行为事件直接进 outbox（无业务表）；
 * - 单一 Random(seed) 顺序消费 + 固定 List 迭代顺序 → 同种子同结果（§20.6）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationEngine {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final double[] ACTIVITY_RATIO = {0.15, 0.35, 0.50};
    private static final double[] ACTIVITY_WEIGHT = {1.8, 1.0, 0.4};
    private static final double[] BEHAVIOR_MIX = {88, 4, 5, 1.5, 1.5}; // view/fav/cart_add/cart_remove/search
    private static final double ZIPF_S = 1.0;
    /** 支付基线的业务概率（下单后支付）：浏览→支付概率由 baseConversionRate×场景乘数承担，支付环节不重复折扣 */
    private static final double PAY_BASE_PROB = 0.85;

    private final MallBusinessService mall;
    private final ProductManagementService productManagement;
    private final InventoryService inventoryService;
    private final EventOutboxService outboxService;
    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final com.graduation.mall.domain.mapper.InventoryMapper inventoryMapper;
    private final EventClock eventClock;

    private record SeedUser(Long userId, int activityLevel, double[] categoryPref, double sensitivity) {
    }

    private record SeedProduct(Long productId, long categoryParent, BigDecimal price, boolean isNew, int rank) {
    }

    /**
     * 执行一次可复现生成，返回运行摘要。
     */
    public GenerationResult run(GeneratorConfig config, GenerationFactors factors, TraceContext trace) {
        // 场景模拟器的确定性起始状态（§20.6）：每次 run 重置全部商品库存，
        // 否则上一次 run 的预扣/售罄会让相同种子产生不同决策流
        resetAllInventory();

        DistributionKit rng = new DistributionKit(config.randomSeed());

        List<SeedUser> users = createUsers(config, trace, rng);
        List<SeedProduct> pool = buildProductPool(config, factors, trace, rng);
        factors = applySelections(config, factors, pool, rng, trace);
        pool = applyPrices(config, factors, pool, trace);

        Counters c = simulate(config, factors, users, pool, trace, rng);

        return new GenerationResult(
                config.reproducibilityKey(),
                config.scenario(),
                ScenarioRegistry.get(config.scenario()).expectedEffect(),
                (long) users.size(),
                pool.stream().filter(p -> p.isNew()).count(),
                c.behaviorsByType,
                c.ordersCreated, c.ordersPaid, c.ordersCancelled, c.ordersCompleted,
                c.refundsApplied, c.refundsCompleted,
                c.gmv, c.netSale, c.avgOrderValue(),
                c.stockShortageHits,
                c.totalEvents,
                c.eventIds.isEmpty()
                        ? List.of("no-events")
                        : c.eventIds.subList(0, Math.min(20, c.eventIds.size())),
                List.of());
    }

    // ── 1. 分层用户（§20.3：15/35/50） ─────────────────────────────────────

    /** 重置全部商品库存为种子状态（100 可售 / 0 预留 / version 0） */
    private void resetAllInventory() {
        try {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                    com.graduation.mall.domain.entity.Inventory> w =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
            w.set(com.graduation.mall.domain.entity.Inventory::getAvailableQty, 100)
                    .set(com.graduation.mall.domain.entity.Inventory::getReservedQty, 0)
                    .set(com.graduation.mall.domain.entity.Inventory::getVersion, 0);
            inventoryMapper.update(null, w);
        } catch (Exception e) {
            log.warn("reset inventory failed: {}", e.getMessage());
        }
    }

    private List<SeedUser> createUsers(GeneratorConfig config, TraceContext trace, DistributionKit rng) {
        List<SeedUser> users = new ArrayList<>();
        String[] ageGroups = {"under18", "age18_24", "age25_34", "age35_44", "age45_plus"};
        String[] cities = {"tier1", "tier2", "tier3", "other"};
        String[] members = {"normal", "silver", "gold", "platinum"};
        List<Category> topCategories = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getLevel, 1).orderByAsc(Category::getId));
        int catCount = topCategories.size();

        eventClock.pushSimulated(config.startTime().atZone(ZONE).toOffsetDateTime());
        try {
            for (int i = 0; i < config.userCount(); i++) {
                int activity = rng.weightedPick(ACTIVITY_RATIO);
                Long userId = mall.registerUser(new CreateUserReq(
                        ageGroups[rng.nextInt(ageGroups.length)],
                        cities[rng.nextInt(cities.length)],
                        members[rng.nextInt(members.length)]), trace);
                double[] prefs = new double[catCount];
                double sum = 0;
                for (int c = 0; c < catCount; c++) {
                    prefs[c] = 0.2 + rng.nextDouble(); // 随机偏好向量
                    sum += prefs[c];
                }
                for (int c = 0; c < catCount; c++) {
                    prefs[c] /= sum;
                }
                users.add(new SeedUser(userId, activity, prefs, rng.uniform(0.5, 1.5)));
            }
        } finally {
            eventClock.popSimulated();
        }
        return users;
    }

    // ── 2. 商品池（种子商品 + 可选按模板补建新品） ──────────────────────────

    private List<SeedProduct> buildProductPool(GeneratorConfig config, GenerationFactors factors,
                                               TraceContext trace, DistributionKit rng) {
        List<Product> seed = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, "on_sale").orderByAsc(Product::getProductId));
        Map<Long, Long> parentByCat = loadCategoryParents();
        List<SeedProduct> pool = new ArrayList<>();
        int rank = 1;
        for (Product p : seed) {
            pool.add(toSeedProduct(p, parentByCat, false, rank++));
        }

        if (config.productCount() > 0) {
            // 补建商品：按二级分类模板对数正态定价（§20.2）
            eventClock.pushSimulated(config.startTime().atZone(ZONE).toOffsetDateTime());
            try {
                List<Category> leafCategories = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                        .eq(Category::getLevel, 2).orderByAsc(Category::getId));
                for (int i = 0; i < config.productCount(); i++) {
                    Category leaf = leafCategories.get(rng.nextInt(leafCategories.size()));
                    double medianByCat = 60 + (leaf.getId() % 8) * 40.0;
                    BigDecimal price = BigDecimal.valueOf(rng.boundedLogNormal(9.9, medianByCat, 999))
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal cost = price.multiply(BigDecimal.valueOf(rng.uniform(0.45, 0.80)))
                            .setScale(2, RoundingMode.HALF_UP);
                    Long productId = productManagement.createProduct(
                            leaf.getName() + " 精选" + (i + 1), leaf.getId(), 900L + (i % 9L),
                            price, cost, trace);
                    pool.add(new SeedProduct(productId, leaf.getParentId(), price, true, rank++));
                }
            } finally {
                eventClock.popSimulated();
            }
        }
        return pool;
    }

    private Map<Long, Long> loadCategoryParents() {
        Map<Long, Long> parent = new LinkedHashMap<>();
        for (Category c : categoryMapper.selectList(null)) {
            parent.put(c.getId(), c.getParentId() == null || c.getParentId() == 0 ? c.getId() : c.getParentId());
        }
        return parent;
    }

    private SeedProduct toSeedProduct(Product p, Map<Long, Long> parentByCat, boolean isNew, int rank) {
        return new SeedProduct(p.getProductId(), parentByCat.getOrDefault(p.getCategoryId(), p.getCategoryId()),
                p.getPrice(), isNew, rank);
    }

    // ── 3. 场景选择（爆款/缺货分类/受影响分类，用同一 RNG 确定） ──────────────

    private GenerationFactors applySelections(GeneratorConfig config, GenerationFactors factors,
                                              List<SeedProduct> pool, DistributionKit rng, TraceContext trace) {
        List<Long> hot = new ArrayList<>(factors.hotProductIds());
        List<Long> shortage = new ArrayList<>(factors.shortageProductIds());
        List<Long> cats = new ArrayList<>(factors.affectedCategories());

        if (factors.selectHotProduct() && !pool.isEmpty()) {
            int head = Math.max(1, pool.size() / 5);
            hot.add(pool.get(rng.nextInt(head)).productId());
        }
        if (factors.selectShortageProduct() && !pool.isEmpty()) {
            int head = Math.max(1, pool.size() / 5);
            Long id = pool.get(rng.nextInt(head)).productId();
            while (shortage.contains(id)) {
                id = pool.get(rng.nextInt(pool.size())).productId();
            }
            shortage.add(id);
            // 真实压低可售库存（仅当当前库存充足）：adjust 直接设定
            inventoryService.adjustStock(id, "adjust", 2, trace);
        }
        if (factors.selectAffectedCategories() && !pool.isEmpty()) {
            long parent = pool.get(rng.nextInt(pool.size())).categoryParent();
            cats.add(parent);
        }
        return factors.withHot(hot).withShortage(shortage).withCategories(cats);
    }

    /** 价格场景：受影响分类商品真实改价（product_updated 事件），随后重建商品池 */
    private List<SeedProduct> applyPrices(GeneratorConfig config, GenerationFactors factors,
                                          List<SeedProduct> pool, TraceContext trace) {
        if (factors.affectedCategories().isEmpty() || factors.priceMultiplier() == 1.0) {
            return pool;
        }
        Map<Long, Long> parentByCat = loadCategoryParents();
        for (Product row : productMapper.selectList(null)) {
            Long parent = parentByCat.get(row.getCategoryId());
            if (parent != null && factors.affectedCategories().contains(parent)) {
                BigDecimal newPrice = row.getPrice().multiply(BigDecimal.valueOf(factors.priceMultiplier()))
                        .setScale(2, RoundingMode.HALF_UP);
                productManagement.updatePrice(row.getProductId(), newPrice, trace);
            }
        }
        List<SeedProduct> result = new ArrayList<>();
        int rank = 1;
        for (Product row : productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, "on_sale").orderByAsc(Product::getProductId))) {
            boolean isNew = false;
            for (SeedProduct sp : pool) {
                if (sp.productId().equals(row.getProductId())) {
                    isNew = sp.isNew();
                    break;
                }
            }
            result.add(new SeedProduct(row.getProductId(),
                    parentByCat.getOrDefault(row.getCategoryId(), row.getCategoryId()),
                    row.getPrice(), isNew, rank++));
        }
        return result;
    }

    // ── 4. 行为与交易模拟 ──────────────────────────────────────────────────

    private Counters simulate(GeneratorConfig config, GenerationFactors factors,
                              List<SeedUser> users, List<SeedProduct> pool,
                              TraceContext trace, DistributionKit rng) {
        Counters c = new Counters();
        long totalSeconds = Duration.between(config.startTime(), config.endTime()).getSeconds();
        long eventBudget = Math.max(1, (long) config.eventsPerSecond() * totalSeconds);

        // 每个整点小时的流量权重（先算好数组，保证 RNG 消费顺序与小时无关）
        int hourCount = (int) Math.ceil(totalSeconds / 3600.0);
        if (hourCount == 0) {
            hourCount = 1;
        }
        double[] hourWeights = new double[hourCount];
        double weightSum = 0;
        LocalDateTime cur = config.startTime().withMinute(0).withSecond(0).withNano(0);
        int idx = 0;
        while (!cur.isAfter(config.endTime()) && idx < hourCount) {
            double w = HourlyTraffic.HOUR_WEIGHTS[cur.getHour()]
                    * HourlyTraffic.weekendBoost(cur.toLocalDate(), factors.weekendBoost())
                    * factors.trafficMultiplier();
            if (w <= 0) {
                w = 0.05;
            }
            hourWeights[idx] = w;
            weightSum += w;
            cur = cur.plusHours(1);
            idx++;
        }

        double poolMedian = medianPrice(pool);
        double conversionProb = clampP(config.baseConversionRate() * factors.conversionMultiplier());
        double refundProb = clampP(0.05 * factors.refundMultiplier());
        List<Long> hotIds = factors.hotProductIds();
        List<Long> shortageIds = factors.shortageProductIds();

        for (long i = 0; i < eventBudget; i++) {
            int hourIdx = rng.weightedPick(hourWeights);
            LocalDateTime baseHour = config.startTime().withMinute(0).withSecond(0).withNano(0).plusHours(hourIdx);
            LocalDateTime eventTime = baseHour.plusSeconds(rng.nextInt(Math.min(3599, (int) totalSeconds)));

            SeedUser user = pickUser(users, factors, rng);
            int btype = rng.weightedPick(BEHAVIOR_MIX);
            SeedProduct product = pickProduct(pool, user, poolMedian, hotIds, factors, rng);
            if (product == null) {
                continue;
            }

            switch (btype) {
                case 0 -> emitBehavior(user, product, eventTime, "view", trace, c);
                case 1 -> emitBehavior(user, product, eventTime, "favorite", trace, c);
                case 2 -> {
                    emitBehavior(user, product, eventTime, "cart_add", trace, c);
                    c.addCart.add(product.productId());
                    if (rng.chance(conversionProb)) {
                        eventClock.pushSimulated(toOffset(eventTime));
                        try {
                            orderFlow(user, product, eventTime, conversionProb, refundProb,
                                    hotIds, shortageIds, trace, rng, c);
                        } finally {
                            eventClock.popSimulated();
                        }
                    }
                }
                case 3 -> emitBehavior(user, product, eventTime, "cart_remove", trace, c);
                default -> emitBehavior(user, product, eventTime, "search", trace, c);
            }
        }
        return c;
    }

    private SeedUser pickUser(List<SeedUser> users, GenerationFactors factors, DistributionKit rng) {
        double[] weights = new double[users.size()];
        for (int i = 0; i < users.size(); i++) {
            SeedUser u = users.get(i);
            double w = ACTIVITY_WEIGHT[u.activityLevel()];
            // old_user_churn：注册靠前的一半用户（老用户）会话权重下调
            if (i < users.size() / 2) {
                w *= factors.oldUserWeight();
            }
            weights[i] = w;
        }
        return users.get(rng.weightedPick(weights));
    }

    private SeedProduct pickProduct(List<SeedProduct> pool, SeedUser user, double poolMedian,
                                    List<Long> hotIds, GenerationFactors factors, DistributionKit rng) {
        double[] weights = new double[pool.size()];
        int catCount = user.categoryPref().length;
        for (int i = 0; i < pool.size(); i++) {
            SeedProduct p = pool.get(i);
            double zipf = DistributionKit.zipfWeight(p.rank() + 1, ZIPF_S);
            double pref = user.categoryPref()[(int) (p.categoryParent() % catCount)];
            double priceAccept = Math.exp(-user.sensitivity()
                    * (p.price().doubleValue() - poolMedian) / Math.max(1, poolMedian));
            double w = zipf * Math.max(0.05, pref) * priceAccept;
            if (hotIds.contains(p.productId())) {
                w *= 3.0;
            }
            if (p.isNew()) {
                w *= 3.0; // 新品冷启动加权曝光
            }
            weights[i] = w;
        }
        return pool.get(rng.weightedPick(weights));
    }

    private void emitBehavior(SeedUser user, SeedProduct product, LocalDateTime eventTime,
                              String behaviorType, TraceContext trace, Counters c) {
        String eventId = outboxService.append(trace, EventContract.AGG_USER, String.valueOf(user.userId()),
                EventContract.BEHAVIOR, toOffset(eventTime),
                EventPayloadFactory.behavior(user.userId(), product.productId(),
                        "gen-session-" + user.userId(), behaviorType, "app",
                        toOffset(eventTime)));
        c.behaviorsByType.merge(behaviorType, 1L, Long::sum);
        c.totalEvents++;
        c.recordEventId(eventId);
    }

    /** 订单全生命周期：下单→(支付|取消)→(完成|退款)→退款完成，全部走业务 Service */
    private void orderFlow(SeedUser user, SeedProduct product, LocalDateTime eventTime,
                           double conversionProb, double refundProb,
                           List<Long> hotIds, List<Long> shortageIds,
                           TraceContext trace, DistributionKit rng, Counters c) {
        int quantity = 1 + rng.nextInt(3);
        try {
            Long orderId = mall.createOrder(new OrderCreateReq(user.userId(),
                    List.of(new OrderItemReq(product.productId(), quantity))), trace);
            c.ordersCreated++;
            String step = BehaviorChain.next(BehaviorChain.ORDER_CREATED, rng, PAY_BASE_PROB, refundProb);
            if (BehaviorChain.PAID.equals(step)) {
                mall.payOrder(orderId, user.userId(), trace);
                c.ordersPaid++;
                BigDecimal amount = orderAmount(product, quantity);
                c.gmv = c.gmv.add(amount);
                c.netSale = c.netSale.add(amount);
                String step2 = BehaviorChain.next(BehaviorChain.PAID, rng, PAY_BASE_PROB, refundProb);
                if (BehaviorChain.REFUNDING.equals(step2)) {
                    BigDecimal refundAmount = amount
                            .multiply(BigDecimal.valueOf(rng.uniform(0.3, 1.0)))
                            .setScale(2, RoundingMode.HALF_UP);
                    Long refundId = mall.applyRefund(orderId, user.userId(), refundAmount,
                            "quality_issue", trace);
                    mall.completeRefund(refundId, user.userId(), trace);
                    c.refundsApplied++;
                    c.refundsCompleted++;
                    c.netSale = c.netSale.subtract(refundAmount);
                } else {
                    mall.completeOrder(orderId, trace);
                    c.ordersCompleted++;
                }
            } else if (BehaviorChain.CANCELLED.equals(step)) {
                mall.cancelOrder(orderId, user.userId(), "change_of_mind", trace);
                c.ordersCancelled++;
            }
        } catch (Exception e) {
            // 库存不足等业务拒绝属预期（stock_shortage 场景会产生），统计但不中断
            c.stockShortageHits++;
        }
    }

    private BigDecimal orderAmount(SeedProduct product, int quantity) {
        return product.price().multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    private double medianPrice(List<SeedProduct> pool) {
        List<Double> prices = new ArrayList<>();
        for (SeedProduct p : pool) {
            prices.add(p.price().doubleValue());
        }
        prices.sort(Double::compareTo);
        if (prices.isEmpty()) {
            return 100;
        }
        return prices.get(prices.size() / 2);
    }

    private static double clampP(double p) {
        return Math.max(0, Math.min(0.95, p));
    }

    private static OffsetDateTime toOffset(LocalDateTime ldt) {
        return ldt.atZone(ZONE).toOffsetDateTime();
    }

    /** 运行期计数器（本类内部使用） */
    private static final class Counters {
        final Map<String, Long> behaviorsByType = new LinkedHashMap<>();
        long ordersCreated, ordersPaid, ordersCancelled, ordersCompleted;
        long refundsApplied, refundsCompleted;
        long stockShortageHits;
        long totalEvents;
        BigDecimal gmv = BigDecimal.ZERO;
        BigDecimal netSale = BigDecimal.ZERO;
        final List<Long> addCart = new ArrayList<>();
        final List<String> eventIds = new ArrayList<>();

        BigDecimal avgOrderValue() {
            return ordersPaid == 0 ? BigDecimal.ZERO
                    : gmv.divide(BigDecimal.valueOf(ordersPaid), 2, RoundingMode.HALF_UP);
        }

        void recordEventId(String id) {
            if (id != null && eventIds.size() < 20) {
                eventIds.add(id);
            }
        }
    }
}