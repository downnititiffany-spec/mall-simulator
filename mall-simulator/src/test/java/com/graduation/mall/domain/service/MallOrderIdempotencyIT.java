package com.graduation.mall.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.mall.common.MallBizException;
import com.graduation.mall.controller.MallDtos.CreateUserReq;
import com.graduation.mall.controller.MallDtos.OrderCreateReq;
import com.graduation.mall.controller.MallDtos.OrderItemReq;
import com.graduation.mall.domain.entity.EventOutbox;
import com.graduation.mall.domain.entity.Inventory;
import com.graduation.mall.domain.entity.MallOrder;
import com.graduation.mall.domain.entity.MallRequestIdempotency;
import com.graduation.mall.domain.entity.Payment;
import com.graduation.mall.domain.mapper.EventOutboxMapper;
import com.graduation.mall.domain.mapper.InventoryMapper;
import com.graduation.mall.domain.mapper.MallOrderMapper;
import com.graduation.mall.domain.mapper.MallRequestIdempotencyMapper;
import com.graduation.mall.domain.mapper.PaymentMapper;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.TraceContext;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real MySQL 3307 integration checks. Runs only in the explicit isolated-tests profile. */
@Tag("it")
class MallOrderIdempotencyIT extends MallTestSupport {

    private static final String ORDER_KEY = "0a7f0f6b0d22d5b04561";
    private static final Long PRODUCT_ID = 1001L;

    @Autowired
    private MallBusinessService mall;
    @Autowired
    private MallOrderMapper orderMapper;
    @Autowired
    private MallRequestIdempotencyMapper idempotencyMapper;
    @Autowired
    private PaymentMapper paymentMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private EventOutboxMapper outboxMapper;

    @Test
    void committedReplayReturnsSameOrderWithoutRepeatingStockOrOutboxEffects() {
        Long userId = registerUser();
        OrderCreateReq request = order(userId, PRODUCT_ID, 2);

        Long first = mall.createOrder(request, TraceContext.create(), ORDER_KEY);
        Inventory afterFirst = inventoryMapper.selectById(PRODUCT_ID);
        long outboxAfterFirst = countOrderCreated();

        Long replay = mall.createOrder(request, TraceContext.create(), ORDER_KEY);

        assertEquals(first, replay);
        MallRequestIdempotency ledger = idempotencyMapper.selectById(ORDER_KEY);
        assertEquals("createOrder", ledger.getOperation());
        assertEquals(first.toString(), ledger.getResultValue());
        assertEquals(1L, orderMapper.selectCount(new LambdaQueryWrapper<MallOrder>()
                .eq(MallOrder::getIdempotencyKey, ORDER_KEY)));
        assertEquals(afterFirst.getAvailableQty(), inventoryMapper.selectById(PRODUCT_ID).getAvailableQty());
        assertEquals(afterFirst.getReservedQty(), inventoryMapper.selectById(PRODUCT_ID).getReservedQty());
        assertEquals(outboxAfterFirst, countOrderCreated());
    }

    @Test
    void orderKeyCannotBeReusedForRegistration() {
        Long userId = registerUser();
        Long orderId = mall.createOrder(order(userId, PRODUCT_ID, 1), TraceContext.create(), ORDER_KEY);

        MallBizException failure = assertThrows(MallBizException.class,
                () -> mall.registerUser(new CreateUserReq("35-44", "tier2", "silver"),
                        TraceContext.create(), ORDER_KEY));

        assertEquals(MallBizException.IDEMPOTENCY_CONFLICT, failure.getCode());
        assertEquals(1L, orderMapper.selectCount(new LambdaQueryWrapper<MallOrder>()
                .eq(MallOrder::getIdempotencyKey, ORDER_KEY)
                .eq(MallOrder::getOrderId, orderId)));
    }

    @Test
    void concurrentSameKeyRequestsCommitOnlyOneOrderAndOneStockReservation() throws Exception {
        Long userId = registerUser();
        OrderCreateReq request = order(userId, PRODUCT_ID, 2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> one = pool.submit(() -> createAtSameTime(ready, start, request));
            Future<Long> two = pool.submit(() -> createAtSameTime(ready, start, request));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "两个事务应进入并发起跑点");
            start.countDown();
            Long first = one.get(30, TimeUnit.SECONDS);
            Long second = two.get(30, TimeUnit.SECONDS);

            assertEquals(first, second);
            assertEquals(1L, orderMapper.selectCount(new LambdaQueryWrapper<MallOrder>()
                    .eq(MallOrder::getIdempotencyKey, ORDER_KEY)));
            Inventory inventory = inventoryMapper.selectById(PRODUCT_ID);
            assertEquals(98, inventory.getAvailableQty());
            assertEquals(2, inventory.getReservedQty());
            assertEquals(1L, countOrderCreated());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "并发测试线程应结束");
        }
    }

    @Test
    void sameKeyWithDifferentRequestIsConflictWithoutFurtherSideEffects() {
        Long userId = registerUser();
        mall.createOrder(order(userId, PRODUCT_ID, 2), TraceContext.create(), ORDER_KEY);
        Inventory afterFirst = inventoryMapper.selectById(PRODUCT_ID);
        long outboxAfterFirst = countOrderCreated();

        MallBizException failure = assertThrows(MallBizException.class,
                () -> mall.createOrder(order(userId, PRODUCT_ID, 3), TraceContext.create(), ORDER_KEY));

        assertEquals(MallBizException.IDEMPOTENCY_CONFLICT, failure.getCode());
        assertEquals(afterFirst.getAvailableQty(), inventoryMapper.selectById(PRODUCT_ID).getAvailableQty());
        assertEquals(afterFirst.getReservedQty(), inventoryMapper.selectById(PRODUCT_ID).getReservedQty());
        assertEquals(outboxAfterFirst, countOrderCreated());
    }

    @Test
    void committedRegistrationReplayReturnsOriginalUserAndDoesNotAppendOutboxAgain() {
        CreateUserReq request = new CreateUserReq("25-34", "tier1", "gold");
        long first = mall.registerUser(request, TraceContext.create(), "register-unique-key");
        long beforeReplay = outboxMapper.selectCount(new LambdaQueryWrapper<EventOutbox>()
                .eq(EventOutbox::getEventType, EventContract.USER_REGISTERED));

        long replay = mall.registerUser(request, TraceContext.create(), "register-unique-key");

        assertEquals(first, replay);
        assertEquals(beforeReplay, outboxMapper.selectCount(new LambdaQueryWrapper<EventOutbox>()
                .eq(EventOutbox::getEventType, EventContract.USER_REGISTERED)));
    }

    @Test
    void committedPaymentReplayDoesNotCreateAnotherPaymentOrOutboxEvent() {
        Long userId = registerUser();
        Long orderId = mall.createOrder(order(userId, PRODUCT_ID, 1), TraceContext.create());
        mall.payOrder(orderId, userId, TraceContext.create(), "pay-unique-key");
        long beforeReplay = outboxMapper.selectCount(new LambdaQueryWrapper<EventOutbox>()
                .eq(EventOutbox::getEventType, EventContract.ORDER_PAID));

        mall.payOrder(orderId, userId, TraceContext.create(), "pay-unique-key");

        assertEquals(1L, paymentMapper.selectCount(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderId, orderId)));
        assertEquals(beforeReplay, outboxMapper.selectCount(new LambdaQueryWrapper<EventOutbox>()
                .eq(EventOutbox::getEventType, EventContract.ORDER_PAID)));
    }

    private Long createAtSameTime(CountDownLatch ready, CountDownLatch start, OrderCreateReq request)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("并发起跑信号超时");
        }
        return mall.createOrder(request, TraceContext.create(), ORDER_KEY);
    }

    private Long registerUser() {
        return mall.registerUser(new CreateUserReq("25-34", "tier1", "gold"), TraceContext.create());
    }

    private static OrderCreateReq order(Long userId, Long productId, int quantity) {
        return new OrderCreateReq(userId, List.of(new OrderItemReq(productId, quantity)));
    }

    private long countOrderCreated() {
        return outboxMapper.selectCount(new LambdaQueryWrapper<EventOutbox>()
                .eq(EventOutbox::getEventType, EventContract.ORDER_CREATED));
    }
}
