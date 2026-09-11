package com.graduation.generator.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graduation.generator.contract.CanonicalEvent;
import com.graduation.generator.contract.CanonicalEventFactory;
import com.graduation.generator.contract.CanonicalPayloads;
import com.graduation.generator.contract.ContractFormat;
import com.graduation.generator.contract.EventSink;
import com.graduation.generator.contract.EventTypes;
import com.graduation.generator.core.BehaviorChain;
import com.graduation.generator.core.DirtySample;
import com.graduation.generator.core.DistributionKit;
import com.graduation.generator.core.ExpectedEffect;
import com.graduation.generator.core.GenerationFactors;
import com.graduation.generator.core.GenerationResult;
import com.graduation.generator.core.GeneratorConfig;
import com.graduation.generator.core.HourlyTraffic;
import com.graduation.generator.core.ScenarioRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * 文件模式生成引擎（V2.1 §3.3 B / §4.3）：只通过 {@link EventSink} 产出契约事件，不碰任何库、不碰 landing。
 *
 * <h2>可复现性的落点</h2>
 * <p>随机源全部来自 {@link GenerationRequest#seed()} 按"数据流"派生的子种子
 * （{@link #subSeed(long, String)}：users/products/behaviors/orders/time/dirty），<b>不使用</b> {@code Math.random()}、
 * 不读时钟、不依赖线程调度。因此同一 {@link GenerationRequest#reproducibilityKey()} 必得逐字节相同的产物。
 * 事件 ID 以种子为前缀（不含 runId），所以同一计划版本重跑产物一致——代价是<b>跨运行的 event_id 会重复</b>，
 * 这一点已登记为 D-017（§4.2 把可复现排在唯一性之前）。</p>
 *
 * <h2>契约硬约束</h2>
 * <ul>
 *   <li>{@code event_count} 是<b>事件预算</b>：写入无异常时 {@code successCount == eventCount}（不多不少）。</li>
 *   <li>金额一律按"分"用 {@code long}/{@code BigDecimal} 计算，落盘走 {@link ContractFormat#amount(long)}，无浮点尾差。</li>
 *   <li>引用完整性靠构造顺序保证：先用户、再商品，订单只引用池内 ID。</li>
 *   <li>异常样本<b>不进主事件流</b>：以 {@link DirtySample} 返回，由运行服务写成独立制品（{@code KIND_DIRTY_SAMPLE}），
 *       于是主流的契约有效性可被独立验证，同时期望隔离数仍可逐类型对账。</li>
 * </ul>
 *
 * <h2>如实声明的缺口（逐条进运行报告 notes）</h2>
 * <ul>
 *   <li>{@code rate_per_second} 在文件模式不生效：离线产物没有真实负载，限速只对 {@code MALL_API} 有意义。</li>
 *   <li>{@code traffic_multiplier} 在固定预算下表现为<b>峰值时段集中度</b>（相对重加权），不改变总条数。</li>
 *   <li>单线程生成；子种子按数据流派生（将来并行化时按分片同法派生），不是"每线程一个 RNG"的字面形式。</li>
 *   <li>{@code ingest_time} 取事件时间：文件模式无真实采集链路，取时钟会直接破坏可复现（§4.2）。</li>
 *   <li>用户/商品规模、脏样本条数上限、脏样本类型词表都是<b>引擎自定规则</b>（契约与指导书未冻结），见 D-015。</li>
 * </ul>
 */
public final class FileModeGenerationEngine implements GenerationEngine {

    /** 脏数据档位（词汇未冻结，登记在 D-015）：none / light / heavy */
    public static final String DIRTY_LIGHT = "light";
    public static final String DIRTY_HEAVY = "heavy";

    private static final Map<String, Double> DIRTY_RATE_BY_PROFILE = Map.of(
            GenerationRequest.DIRTY_NONE, 0.0,
            DIRTY_LIGHT, 0.01,
            DIRTY_HEAVY, 0.05);

    private static final int MAX_DIRTY_SAMPLES = 200;
    private static final int CANCELLATION_POLL_EVERY = 32;
    private static final int MAX_WRITE_FAILURE_NOTES = 20;
    private static final int SAMPLE_EVENT_IDS = 5;

    private static final double BASE_PAY_PROBABILITY = 0.35;
    private static final double BASE_REFUND_PROBABILITY = 0.08;

    private static final String[] BEHAVIOR_TYPES = {"view", "favorite", "cart_add", "cart_remove", "search"};
    private static final double[] BEHAVIOR_WEIGHTS = {0.62, 0.08, 0.13, 0.05, 0.12};
    private static final String[] CHANNELS = {"app", "pc", "h5"};
    private static final double[] CHANNEL_WEIGHTS = {0.55, 0.30, 0.15};
    private static final String[] AGE_GROUPS = {"under18", "18-24", "25-34", "35-44", "45+"};
    private static final double[] AGE_WEIGHTS = {0.06, 0.28, 0.34, 0.22, 0.10};
    private static final String[] CITY_LEVELS = {"tier1", "tier2", "tier3", "other"};
    private static final double[] CITY_WEIGHTS = {0.28, 0.34, 0.26, 0.12};
    private static final String[] MEMBER_LEVELS = {"normal", "silver", "gold", "platinum"};
    private static final double[] MEMBER_WEIGHTS = {0.60, 0.25, 0.12, 0.03};

    private static final String[] CANCEL_REASONS = {"user_cancel", "payment_timeout", "out_of_stock"};
    private static final String[] REFUND_REASONS = {"quality_issue", "wrong_item", "user_regret", "late_delivery"};

    /** 脏样本类型（7 类；指导书写 6 类，差异登记为 D-018） */
    static final String[] DIRTY_TYPES = {"missing_field", "bad_amount", "bad_time", "unknown_enum",
            "negative_quantity", "truncated_json", "order_total_mismatch"};

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public EngineOutcome run(GenerationRequest request, EventSink sink, BooleanSupplier cancelled) {
        Double dirtyRate = DIRTY_RATE_BY_PROFILE.get(request.dirtyProfile().toLowerCase(Locale.ROOT));
        if (dirtyRate == null) {
            throw new IllegalArgumentException("未知脏数据档位 " + request.dirtyProfile()
                    + "，已实现档位：" + DIRTY_RATE_BY_PROFILE.keySet());
        }
        return new Run(request, sink, cancelled, dirtyRate).execute();
    }

    /** 用户数规则（事件预算的派生量；登记 D-015） */
    static int usersFor(long eventCount) {
        return (int) clamp(eventCount / 12.0, 3, 5_000);
    }

    /** 商品池规模规则（事件预算的派生量；登记 D-015） */
    static int productsFor(long eventCount) {
        return (int) clamp(eventCount / 20.0, 4, 2_000);
    }

    /** 一次运行的可变状态（单次调用内使用，不跨线程共享） */
    private final class Run {

        private final GenerationRequest request;
        private final EventSink sink;
        private final BooleanSupplier cancelled;
        private final double dirtyRate;
        private final ExpectedEffect expectedEffect;
        private final GenerationFactors factors;

        private final DistributionKit userRng;
        private final DistributionKit productRng;
        private final DistributionKit behaviorRng;
        private final DistributionKit orderRng;
        private final DistributionKit timeRng;
        private final DistributionKit dirtyRng;

        private final CanonicalEventFactory factory;
        private final Map<String, EventTypeStat> stats = new TreeMap<>();
        private final List<DirtySample> dirtySamples = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private final List<String> sampleEventIds = new ArrayList<>();
        private final Map<String, Long> behaviorsByType = new LinkedHashMap<>();
        private final List<String> productIds = new ArrayList<>();
        private final Map<String, Long> priceCents = new LinkedHashMap<>();
        private final Map<String, Long> availableQty = new LinkedHashMap<>();
        private final Map<String, String> categoryByProduct = new LinkedHashMap<>();
        private final Set<String> affectedCategories = new LinkedHashSet<>();
        private final Set<String> shortageProducts = new LinkedHashSet<>();

        private final int userCount;
        private final int productCount;
        private final int categoryCount;
        private final double[] hourWeights = new double[24];
        private final double[] productWeights;

        private long emitted;
        private long failed;
        private long seq;
        private long polls;

        private long usersCreated;
        private long productsCreated;
        private long ordersCreated;
        private long ordersPaid;
        private long ordersCancelled;
        private long ordersCompleted;
        private long refundsApplied;
        private long refundsCompleted;
        private long stockShortageHits;
        private long gmvCents;
        private long refundCents;

        private Run(GenerationRequest request, EventSink sink, BooleanSupplier cancelled, double dirtyRate) {
            this.request = request;
            this.sink = sink;
            this.cancelled = cancelled;
            this.dirtyRate = dirtyRate;
            this.expectedEffect = ScenarioRegistry.get(request.scenario()).expectedEffect();
            this.factors = ScenarioRegistry.get(request.scenario()).factors(config());
            this.userRng = new DistributionKit(subSeed(request.seed(), "users"));
            this.productRng = new DistributionKit(subSeed(request.seed(), "products"));
            this.behaviorRng = new DistributionKit(subSeed(request.seed(), "behaviors"));
            this.orderRng = new DistributionKit(subSeed(request.seed(), "orders"));
            this.timeRng = new DistributionKit(subSeed(request.seed(), "time"));
            this.dirtyRng = new DistributionKit(subSeed(request.seed(), "dirty"));
            this.factory = new CanonicalEventFactory(this::nextEventId, this::nextTraceId);
            this.userCount = usersFor(request.eventCount());
            this.productCount = productsFor(request.eventCount());
            this.categoryCount = (int) clamp(productCount / 4.0, 3, 24);
            this.productWeights = new double[productCount];
            double traffic = clamp(factors.trafficMultiplier(), 0.2, 5.0);
            for (int hour = 0; hour < 24; hour++) {
                hourWeights[hour] = Math.pow(Math.max(1e-6, HourlyTraffic.HOUR_WEIGHTS[hour]), traffic);
            }
        }

        private GeneratorConfig config() {
            return new GeneratorConfig(
                    usersFor(request.eventCount()),
                    productsFor(request.eventCount()),
                    Math.max(1, request.ratePerSecond()),
                    BASE_PAY_PROBABILITY,
                    LocalDateTime.ofInstant(request.startTime(), ContractFormat.BUSINESS_ZONE),
                    LocalDateTime.ofInstant(request.endTime(), ContractFormat.BUSINESS_ZONE),
                    request.seed(),
                    dirtyRate,
                    request.scenario());
        }

        EngineOutcome execute() {
            notes.add("文件模式事件预算 event_count=%d；rate_per_second=%d 在文件模式不生效（离线产物无真实负载）"
                    .formatted(request.eventCount(), request.ratePerSecond()));
            notes.add("ingest_time 取事件时间：文件模式无真实采集链路，取时钟会破坏可复现（§4.2）");
            notes.add("单线程生成，子种子按数据流派生：users/products/behaviors/orders/time/dirty");
            notes.add("用户数 %d、商品池 %d（按事件预算派生，属引擎自定规则）".formatted(userCount, productCount));

            buildProductPool();
            emitUsers();
            emitProducts();
            emitPriceUpdatesForAffectedCategories();
            emitBehaviorStream();
            if (request.injectsDirtySamples()) {
                buildDirtySamples();
            } else {
                notes.add("脏数据档位 none：不注入异常样本，期望隔离数为 0");
            }
            sink.flush();

            GenerationResult result = GenerationResult.builder()
                    .configKey(request.reproducibilityKey())
                    .scenario(request.scenario())
                    .expectedEffect(expectedEffect)
                    .usersCreated(usersCreated)
                    .productsCreated(productsCreated)
                    .behaviorsByType(new LinkedHashMap<>(behaviorsByType))
                    .ordersCreated(ordersCreated)
                    .ordersPaid(ordersPaid)
                    .ordersCancelled(ordersCancelled)
                    .ordersCompleted(ordersCompleted)
                    .refundsApplied(refundsApplied)
                    .refundsCompleted(refundsCompleted)
                    .gmv(cents(gmvCents))
                    .netSale(cents(gmvCents - refundCents))
                    .avgOrderValue(ordersPaid == 0 ? BigDecimal.ZERO
                            : cents(gmvCents).divide(BigDecimal.valueOf(ordersPaid), 2, RoundingMode.HALF_UP))
                    .stockShortageHits(stockShortageHits)
                    .totalEvents(emitted)
                    .sampleEventIds(List.copyOf(sampleEventIds))
                    .dirtySamples(List.copyOf(dirtySamples))
                    .build();
            return new EngineOutcome(result, stats, emitted, failed, dirtySamples, notes);
        }

        // ---------- 商品池 ----------

        private void buildProductPool() {
            double[] categoryWeights = new double[categoryCount];
            for (int i = 0; i < categoryCount; i++) {
                categoryWeights[i] = DistributionKit.zipfWeight(i + 1, 1.1);
            }
            if (factors.selectAffectedCategories()) {
                int wanted = Math.max(1, categoryCount / 3);
                while (affectedCategories.size() < wanted) {
                    affectedCategories.add("C%03d".formatted(1 + productRng.nextInt(categoryCount)));
                }
            }
            for (int i = 0; i < productCount; i++) {
                String productId = "P%05d".formatted(i + 1);
                String categoryId = "C%03d".formatted(1 + productRng.weightedPick(categoryWeights));
                long price = Math.max(100, Math.round(productRng.boundedLogNormal(500, 5000, 50000)));
                productIds.add(productId);
                categoryByProduct.put(productId, categoryId);
                priceCents.put(productId, price);
                availableQty.put(productId, 60L + productRng.nextInt(440));
                productWeights[i] = DistributionKit.zipfWeight(i + 1, 1.2);
            }
            if (factors.selectHotProduct()) {
                int index = productRng.nextInt(Math.min(10, productCount));
                productWeights[index] *= 10.0;
                notes.add("场景爆款商品 %s：抽样权重 ×10（Zipf 头部再放大）".formatted(productIds.get(index)));
            }
            if (factors.selectShortageProduct()) {
                int index = productRng.nextInt(Math.min(5, productCount));
                String productId = productIds.get(index);
                availableQty.put(productId, 2L);
                shortageProducts.add(productId);
                notes.add("场景缺货商品 %s：可用库存压到 2（命中缺货时计 stockShortageHits）".formatted(productId));
            }
            if (!affectedCategories.isEmpty()) {
                notes.add("场景受影响分类 %s：价格乘数 %.2f 通过 product_updated 事件表达（不改历史订单金额）"
                        .formatted(String.join(",", affectedCategories), factors.priceMultiplier()));
            }
        }

        private void emitUsers() {
            for (int i = 0; i < userCount && emitted < request.eventCount(); i++) {
                if (cancelled()) {
                    return;
                }
                String userId = "U%06d".formatted(i + 1);
                Instant registeredAt = timeAt(i);
                String ageGroup = AGE_GROUPS[userRng.weightedPick(AGE_WEIGHTS)];
                String cityLevel = CITY_LEVELS[userRng.weightedPick(CITY_WEIGHTS)];
                String memberLevel = MEMBER_LEVELS[userRng.weightedPick(MEMBER_WEIGHTS)];
                CanonicalEvent event = factory.userRegistered(userId, ageGroup, cityLevel, memberLevel,
                        ContractFormat.time(registeredAt), registeredAt);
                if (write(event, null)) {
                    usersCreated++;
                }
            }
        }

        private void emitProducts() {
            for (int i = 0; i < productCount && emitted < request.eventCount(); i++) {
                if (cancelled()) {
                    return;
                }
                String productId = productIds.get(i);
                Instant createdAt = timeAt(userCount + i);
                Map<String, Object> payload = CanonicalPayloads.productCreated(productId, "商品" + productId,
                        categoryByProduct.get(productId), brandOf(productId),
                        ContractFormat.amount(priceCents.get(productId)),
                        ContractFormat.amount(costCents(priceCents.get(productId))), "on_sale");
                if (write(factory.create(EventTypes.PRODUCT_CREATED, payload, ContractFormat.time(createdAt),
                        createdAt), null)) {
                    productsCreated++;
                }
            }
        }

        /** 受影响分类调价：用 product_updated 表达，不回头改已发生的订单金额 */
        private void emitPriceUpdatesForAffectedCategories() {
            if (affectedCategories.isEmpty() || factors.priceMultiplier() == 1.0) {
                return;
            }
            int budget = Math.max(1, productCount / 10);
            int updated = 0;
            for (String productId : productIds) {
                if (updated >= budget || emitted >= request.eventCount() || cancelled()) {
                    return;
                }
                if (!affectedCategories.contains(categoryByProduct.get(productId))) {
                    continue;
                }
                long newPrice = Math.max(100, Math.round(priceCents.get(productId) * factors.priceMultiplier()));
                if (newPrice == priceCents.get(productId)) {
                    continue;
                }
                priceCents.put(productId, newPrice);
                Instant at = timeAt(emitted);
                Map<String, Object> payload = CanonicalPayloads.productUpdated(productId, "商品" + productId,
                        categoryByProduct.get(productId), brandOf(productId), ContractFormat.amount(newPrice),
                        ContractFormat.amount(costCents(newPrice)), "on_sale");
                write(factory.create(EventTypes.PRODUCT_UPDATED, payload, ContractFormat.time(at), at), null);
                updated++;
            }
        }

        // ---------- 行为流与订单链 ----------

        private void emitBehaviorStream() {
            long orderSeq = 0;
            while (emitted < request.eventCount()) {
                if (cancelled()) {
                    notes.add("运行被取消：已生成 %d/%d 条事件后提前返回".formatted(emitted, request.eventCount()));
                    return;
                }
                String userId = pickUser();
                String productId = pickProduct();
                String behaviorType = BEHAVIOR_TYPES[behaviorRng.weightedPick(BEHAVIOR_WEIGHTS)];
                String channel = CHANNELS[behaviorRng.weightedPick(CHANNEL_WEIGHTS)];
                String sessionId = "S%s-%d".formatted(userId.substring(1), seq % 7 + 1);
                Instant at = timeAt(emitted);
                if (write(factory.behavior(userId, productId, sessionId, behaviorType, channel,
                        ContractFormat.time(at), at), null)) {
                    behaviorsByType.merge(behaviorType, 1L, Long::sum);
                }
                if (emitted >= request.eventCount()) {
                    return;
                }
                if (orderRng.chance(payProbability())) {
                    orderSeq = emitOrderChain(orderSeq + 1, userId, productId);
                }
            }
        }

        private long emitOrderChain(long orderSeq, String userId, String productId) {
            String orderId = "O%08d".formatted(orderSeq);
            int quantity = 1 + orderRng.nextInt(3);
            long unitPrice = priceCents.get(productId);
            long gross = unitPrice * quantity;
            long discount = orderRng.chance(0.35) ? Math.round(gross * (0.05 + orderRng.nextDouble() * 0.10)) : 0L;
            long itemAmount = gross - discount;
            CanonicalPayloads.OrderItem item = new CanonicalPayloads.OrderItem(productId, quantity,
                    ContractFormat.amount(unitPrice), ContractFormat.amount(discount),
                    ContractFormat.amount(itemAmount));
            Instant createdAt = timeAt(emitted);
            Map<String, Object> created = CanonicalPayloads.orderCreated(orderId, userId, List.of(item),
                    ContractFormat.amount(itemAmount), ContractFormat.time(createdAt));
            if (!write(factory.create(EventTypes.ORDER_CREATED, created, ContractFormat.time(createdAt), createdAt),
                    cents(itemAmount))) {
                return orderSeq;
            }
            ordersCreated++;
            if (emitted >= request.eventCount()) {
                return orderSeq;
            }

            long available = availableQty.getOrDefault(productId, 0L);
            if (available < quantity) {
                stockShortageHits++;
                long restock = 100L;
                Instant restockAt = timeAt(emitted);
                Map<String, Object> inbound = CanonicalPayloads.stockChanged(productId, "inbound", restock,
                        ContractFormat.amount(available + restock));
                write(factory.create(EventTypes.STOCK_CHANGED, inbound, ContractFormat.time(restockAt), restockAt), null);
                available += restock;
                if (emitted >= request.eventCount()) {
                    return orderSeq;
                }
            }
            availableQty.put(productId, available - quantity);
            Instant reservedAt = timeAt(emitted);
            Map<String, Object> reserved = CanonicalPayloads.stockReserved(productId, orderId, quantity,
                    ContractFormat.amount(quantity), ContractFormat.amount(available - quantity));
            write(factory.create(EventTypes.STOCK_RESERVED, reserved, ContractFormat.time(reservedAt), reservedAt), null);
            if (emitted >= request.eventCount()) {
                return orderSeq;
            }

            double refundProbability = clamp(BASE_REFUND_PROBABILITY * factors.refundMultiplier(), 0.0, 0.9);
            String next = BehaviorChain.next(BehaviorChain.ORDER_CREATED, orderRng, payProbability(), refundProbability);
            if (BehaviorChain.PAID.equals(next)) {
                Instant paidAt = timeAt(emitted);
                Map<String, Object> paid = CanonicalPayloads.orderPaid(orderId, userId, "PAY" + orderId,
                        ContractFormat.amount(itemAmount), ContractFormat.time(paidAt));
                if (write(factory.create(EventTypes.ORDER_PAID, paid, ContractFormat.time(paidAt), paidAt),
                        cents(itemAmount))) {
                    ordersPaid++;
                    gmvCents += itemAmount;
                }
                if (emitted >= request.eventCount()) {
                    return orderSeq;
                }
                String afterPaid = BehaviorChain.next(BehaviorChain.PAID, orderRng, payProbability(), refundProbability);
                if (BehaviorChain.REFUNDING.equals(afterPaid)) {
                    orderSeq = emitRefund(orderSeq, orderId, userId, itemAmount);
                } else {
                    ordersCompleted++;
                }
                return orderSeq;
            }

            Instant cancelledAt = timeAt(emitted);
            String reason = CANCEL_REASONS[orderRng.nextInt(CANCEL_REASONS.length)];
            Map<String, Object> cancelledPayload = CanonicalPayloads.orderCancelled(orderId, userId, reason,
                    ContractFormat.time(cancelledAt));
            if (write(factory.create(EventTypes.ORDER_CANCELLED, cancelledPayload, ContractFormat.time(cancelledAt),
                    cancelledAt), null)) {
                ordersCancelled++;
            }
            if (emitted < request.eventCount()) {
                long restored = availableQty.getOrDefault(productId, 0L) + quantity;
                availableQty.put(productId, restored);
                Instant releasedAt = timeAt(emitted);
                Map<String, Object> released = CanonicalPayloads.stockReleased(productId, orderId, quantity,
                        ContractFormat.amount(0), ContractFormat.amount(restored));
                write(factory.create(EventTypes.STOCK_RELEASED, released, ContractFormat.time(releasedAt), releasedAt),
                        null);
            }
            return orderSeq;
        }

        private long emitRefund(long orderSeq, String orderId, String userId, long amountCents) {
            String refundId = "R%08d".formatted(orderSeq);
            String reason = REFUND_REASONS[orderRng.nextInt(REFUND_REASONS.length)];
            Instant refundAt = timeAt(emitted);
            Map<String, Object> createdPayload = CanonicalPayloads.refundCreated(refundId, orderId, userId,
                    ContractFormat.amount(amountCents), reason, ContractFormat.time(refundAt));
            if (write(factory.create(EventTypes.REFUND_CREATED, createdPayload, ContractFormat.time(refundAt),
                    refundAt), cents(amountCents))) {
                refundsApplied++;
            }
            if (emitted < request.eventCount()) {
                Instant doneAt = timeAt(emitted);
                Map<String, Object> donePayload = CanonicalPayloads.refundCompleted(refundId, orderId, userId,
                        ContractFormat.amount(amountCents), ContractFormat.time(doneAt));
                if (write(factory.create(EventTypes.REFUND_COMPLETED, donePayload, ContractFormat.time(doneAt),
                        doneAt), cents(amountCents))) {
                    refundsCompleted++;
                    refundCents += amountCents;
                }
            }
            return orderSeq;
        }

        // ---------- 脏样本（独立制品，不进主事件流） ----------

        private void buildDirtySamples() {
            int target = (int) clamp(Math.round(dirtyRate * request.eventCount()), 1, MAX_DIRTY_SAMPLES);
            for (int i = 0; i < target; i++) {
                if (cancelled()) {
                    notes.add("取消时脏样本只生成 %d/%d 条".formatted(i, target));
                    break;
                }
                dirtySamples.add(dirtySample(DIRTY_TYPES[i % DIRTY_TYPES.length]));
            }
            notes.add("脏数据档位 %s：%d 条异常样本写入独立制品（KIND_DIRTY_SAMPLE），期望隔离数见运行报告"
                    .formatted(request.dirtyProfile(), dirtySamples.size()));
        }

        /**
         * 手工组信封而不走 {@code CanonicalEventFactory}：异常样本按定义就违反契约，
         * 走校验构造器会直接抛错——那等于"造不出脏样本"。
         */
        private DirtySample dirtySample(String type) {
            String userId = pickUser();
            String productId = pickProduct();
            Instant at = timeAt(emitted);
            String eventTime = ContractFormat.time(at);
            String eventType = EventTypes.BEHAVIOR;
            Map<String, Object> payload = new LinkedHashMap<>(
                    CanonicalPayloads.behavior(userId, productId, "SDIRTY" + dirtyRng.nextInt(1000), "view", "app"));
            String expected = "隔离：契约校验应拒绝该行（实际口径见 B-06 裁决）";

            switch (type) {
                case "bad_amount" -> {
                    eventType = EventTypes.ORDER_PAID;
                    payload = new LinkedHashMap<>();
                    payload.put("order_id", "ODIRTY" + dirtyRng.nextInt(1000));
                    payload.put("user_id", userId);
                    payload.put("payment_id", "PDIRTY");
                    payload.put("amount", "12.345");
                    payload.put("paid_at", eventTime);
                    expected = "隔离：amount 违反 ^\\d+(\\.\\d{1,2})?$（三位小数）";
                }
                case "bad_time" -> {
                    eventTime = "2026-09-01 10:00:00";
                    expected = "隔离：event_time 非 ISO8601（缺 T 与偏移）";
                }
                case "unknown_enum" -> {
                    payload.put("channel", "web");
                    expected = "隔离：channel 不在 {app,pc,h5}";
                }
                case "negative_quantity" -> {
                    eventType = EventTypes.STOCK_CHANGED;
                    payload = new LinkedHashMap<>();
                    payload.put("product_id", productId);
                    payload.put("change_type", "adjust");
                    payload.put("quantity", -5);
                    payload.put("available_qty", ContractFormat.amount(10L));
                    expected = "隔离：库存数量不得为负";
                }
                case "order_total_mismatch" -> {
                    eventType = EventTypes.ORDER_CREATED;
                    payload = new LinkedHashMap<>();
                    payload.put("order_id", "ODIRTY" + dirtyRng.nextInt(1000));
                    payload.put("user_id", userId);
                    payload.put("items", List.of(Map.of("product_id", productId, "quantity", 2,
                            "unit_price", ContractFormat.amount(1000L), "discount", ContractFormat.amount(0L),
                            "amount", ContractFormat.amount(2000L))));
                    payload.put("total_amount", ContractFormat.amount(9999L));
                    payload.put("status", "CREATED");
                    payload.put("created_at", eventTime);
                    expected = "隔离：total_amount 与 Σ items.amount 不一致（跨字段一致性）";
                }
                default -> {
                    // missing_field / truncated_json 在序列化后破坏结构
                }
            }

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("event_id", nextEventId());
            envelope.put("event_type", eventType);
            envelope.put("event_time", eventTime);
            envelope.put("ingest_time", ContractFormat.time(at));
            envelope.put("source_system", ContractFormat.SOURCE_SYSTEM);
            envelope.put("schema_version", ContractFormat.SCHEMA_VERSION);
            envelope.put("trace_id", nextTraceId());
            envelope.put("payload", payload);

            String json;
            try {
                json = mapper.writeValueAsString(envelope);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("脏样本序列化失败：" + type, e);
            }
            if ("missing_field".equals(type)) {
                json = json.replaceFirst("\"schema_version\":\"[^\"]*\",", "");
                expected = "隔离：缺少契约必填 schema_version";
            } else if ("truncated_json".equals(type)) {
                json = json.substring(0, Math.max(1, json.length() - 8));
                expected = "隔离：JSON 被截断，无法解析";
            }
            return new DirtySample(type, expected, json);
        }

        // ---------- 写入与工具 ----------

        private boolean write(CanonicalEvent event, BigDecimal amount) {
            if (emitted >= request.eventCount()) {
                return false;
            }
            try {
                sink.write(event);
                sink.rotateIfNeeded();
            } catch (RuntimeException e) {
                failed++;
                if (notes.size() < MAX_WRITE_FAILURE_NOTES) {
                    notes.add("事件写入失败（计入 failed_count）：" + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return false;
            }
            emitted++;
            if (sampleEventIds.size() < SAMPLE_EVENT_IDS) {
                sampleEventIds.add(event.eventId());
            }
            stats.merge(event.eventType(), new EventTypeStat(1, amount),
                    (left, right) -> left.plus(right.count(), right.amount()));
            return true;
        }

        private boolean cancelled() {
            return cancelled != null && ++polls % CANCELLATION_POLL_EVERY == 0 && cancelled.getAsBoolean();
        }

        private double payProbability() {
            return clamp(BASE_PAY_PROBABILITY * factors.conversionMultiplier(), 0.01, 0.95);
        }

        /** 老用户会话权重：{@code oldUserWeight} &lt; 1 表示老用户流失（老用户被抽中的相对权重下降） */
        private String pickUser() {
            int oldCount = Math.max(1, userCount / 3);
            double oldShare = clamp(0.3 * factors.oldUserWeight(), 0.02, 0.9);
            int index = userRng.chance(oldShare)
                    ? userRng.nextInt(oldCount)
                    : oldCount + userRng.nextInt(Math.max(1, userCount - oldCount));
            return "U%06d".formatted(Math.min(userCount, index + 1));
        }

        private String pickProduct() {
            return productIds.get(behaviorRng.weightedPick(productWeights));
        }

        /** 品牌按商品序号分摊（契约 product 快照要求 brand_id 必填，商品池里不单独建模品牌） */
        private String brandOf(String productId) {
            int rank = Integer.parseInt(productId.substring(1));
            return "B%03d".formatted(1 + (rank - 1) % 8);
        }

        /** 成本按定价的 60% 取整（分）——只用于让商品快照的 cost 字段自洽，不参与任何指标计算 */
        private static long costCents(long price) {
            return Math.max(1, Math.round(price * 0.6));
        }

        /**
         * 事件时间：小时权重（{@code trafficMultiplier} 表现为峰值集中度）+ 周末相对放大。
         * 预算固定，所以"周末放大"必须是相对重加权：周末样本全收，工作日样本以 1/boost 概率收。
         */
        private Instant timeAt(long index) {
            Instant start = request.startTime();
            long span = Math.max(1, Duration.between(start, request.endTime()).getSeconds());
            double weekendBoost = clamp(factors.weekendBoost(), 1.0, 10.0);
            for (int attempt = 0; attempt < 8; attempt++) {
                Instant candidate = start.plusSeconds((long) (timeRng.nextDouble() * span));
                ZonedDateTime zoned = candidate.atZone(ContractFormat.BUSINESS_ZONE);
                if (zoned.getHour() != timeRng.weightedPick(hourWeights)) {
                    continue;
                }
                if (zoned.getDayOfWeek().getValue() < 6 && weekendBoost > 1.0
                        && !timeRng.chance(1.0 / weekendBoost)) {
                    continue;
                }
                return candidate;
            }
            return start.plusSeconds((index * 7919L) % span);
        }

        private String nextEventId() {
            return "E%s%08d".formatted(Long.toHexString(request.seed() & 0xFFFF_FFFFL), ++seq);
        }

        private String nextTraceId() {
            return "T%s%08d".formatted(Long.toHexString(request.seed() & 0xFFFF_FFFFL), seq);
        }
    }

    // ---------- 确定性工具（静态，便于独立测试） ----------

    /** 子种子派生：同一 seed + 同一数据流名 → 同一子种子（跨 JVM 稳定，不依赖 String.hashCode） */
    static long subSeed(long seed, String stream) {
        long hash = 0xcbf29ce484222325L ^ seed;
        for (byte b : stream.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xFF;
            hash *= 0x100000001B3L;
        }
        return mix64(hash);
    }

    private static long mix64(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BigDecimal cents(long value) {
        return BigDecimal.valueOf(value, 2);
    }
}
