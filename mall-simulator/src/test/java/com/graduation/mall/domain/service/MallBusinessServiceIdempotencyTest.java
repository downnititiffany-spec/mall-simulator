package com.graduation.mall.domain.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.graduation.mall.common.MallBizException;
import com.graduation.mall.controller.MallDtos.OrderCreateReq;
import com.graduation.mall.controller.MallDtos.OrderItemReq;
import com.graduation.mall.controller.MallDtos.CreateUserReq;
import com.graduation.mall.controller.MallDtos.CartAddReq;
import com.graduation.mall.domain.entity.MallOrder;
import com.graduation.mall.domain.entity.MallRequestIdempotency;
import com.graduation.mall.domain.mapper.CartItemMapper;
import com.graduation.mall.domain.mapper.InventoryMapper;
import com.graduation.mall.domain.mapper.MallOrderMapper;
import com.graduation.mall.domain.mapper.MallRequestIdempotencyMapper;
import com.graduation.mall.domain.mapper.MallUserMapper;
import com.graduation.mall.domain.mapper.OrderItemMapper;
import com.graduation.mall.domain.mapper.PaymentMapper;
import com.graduation.mall.domain.mapper.ProductMapper;
import com.graduation.mall.domain.mapper.RefundMapper;
import com.graduation.mall.domain.entity.MallUser;
import com.graduation.mall.outbox.EventClock;
import com.graduation.mall.outbox.EventOutboxService;
import com.graduation.mall.outbox.TraceContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;

class MallBusinessServiceIdempotencyTest {

    private final MallOrderMapper orders = mock(MallOrderMapper.class);
    private final MallUserMapper users = mock(MallUserMapper.class);
    private final CartItemMapper carts = mock(CartItemMapper.class);
    private final MallRequestIdempotencyMapper idempotency = mock(MallRequestIdempotencyMapper.class);
    private final EventClock clock = mock(EventClock.class);
    private final MallBusinessService service = new MallBusinessService(
            users, mock(ProductMapper.class), mock(InventoryMapper.class),
            carts, orders, idempotency,
            mock(OrderItemMapper.class), mock(PaymentMapper.class),
            mock(RefundMapper.class), mock(EventOutboxService.class), clock);

    @Test
    void replayWithSameKeyAndBodyReturnsExistingOrderWithoutSideEffects() {
        MallOrder existing = new MallOrder();
        existing.setOrderId(123L);
        existing.setRequestHash(hashFor(7L, List.of(new OrderItemReq(10L, 2))));
        when(orders.selectOne(org.mockito.ArgumentMatchers.<Wrapper<MallOrder>>any())).thenReturn(existing);

        Long result = service.createOrder(request(), TraceContext.create(), "same-key");

        assertEquals(123L, result);
        org.mockito.ArgumentCaptor<MallRequestIdempotency> ledger =
                org.mockito.ArgumentCaptor.forClass(MallRequestIdempotency.class);
        verify(idempotency).insert(ledger.capture());
        assertEquals("createOrder", ledger.getValue().getOperation());
        assertEquals(hashFor(7L, List.of(new OrderItemReq(10L, 2))),
                ledger.getValue().getRequestHash());
        assertEquals("123", ledger.getValue().getResultValue());
        verify(idempotency).updateById(ledger.getValue());
        verify(orders, never()).insert(any(MallOrder.class));
    }

    @Test
    void reusingKeyWithDifferentBodyReturnsConflict() {
        MallOrder existing = new MallOrder();
        existing.setOrderId(123L);
        existing.setRequestHash("different-request-hash");
        when(orders.selectOne(org.mockito.ArgumentMatchers.<Wrapper<MallOrder>>any())).thenReturn(existing);

        MallBizException failure = assertThrows(MallBizException.class,
                () -> service.createOrder(request(), TraceContext.create(), "same-key"));

        assertEquals(MallBizException.IDEMPOTENCY_CONFLICT, failure.getCode());
        verify(orders, never()).insert(any(MallOrder.class));
    }

    @Test
    void replayOfCommittedRegistrationReturnsOriginalUserWithoutRegisteringAgain() {
        CreateUserReq req = new CreateUserReq("25-34", "tier1", "gold");
        MallRequestIdempotency existing = new MallRequestIdempotency();
        existing.setIdempotencyKey("registration-key");
        existing.setOperation("registerUser");
        existing.setRequestHash(MallBusinessService.requestHash("25-34", "tier1", "gold"));
        existing.setResultValue("812345678901234567");
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate"))
                .when(idempotency).insert(any(MallRequestIdempotency.class));
        when(idempotency.selectByKeyForUpdate("registration-key")).thenReturn(existing);

        Long replay = service.registerUser(req, TraceContext.create(), "registration-key");

        assertEquals(812345678901234567L, replay);
        verify(users, never()).insert(any(com.graduation.mall.domain.entity.MallUser.class));
    }

    @Test
    void replayOfCartWriteAcceptsEmptyResultWithoutApplyingQuantityAgain() {
        CartAddReq req = new CartAddReq(7L, 10L, 2);
        MallRequestIdempotency existing = new MallRequestIdempotency();
        existing.setIdempotencyKey("cart-key");
        existing.setOperation("addCartItem");
        existing.setRequestHash(MallBusinessService.requestHash("7", "10", "2"));
        existing.setResultValue("");
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate"))
                .when(idempotency).insert(any(MallRequestIdempotency.class));
        when(idempotency.selectByKeyForUpdate("cart-key")).thenReturn(existing);

        service.addCartItem(req, TraceContext.create(), "cart-key");

        org.mockito.ArgumentCaptor<MallRequestIdempotency> ledger =
                org.mockito.ArgumentCaptor.forClass(MallRequestIdempotency.class);
        verify(idempotency).insert(ledger.capture());
        assertEquals("addCartItem", ledger.getValue().getOperation());
        assertEquals(MallBusinessService.requestHash("7", "10", "2"),
                ledger.getValue().getRequestHash());
        assertEquals("", ledger.getValue().getResultValue());
        verify(carts, org.mockito.Mockito.never()).insert(any(com.graduation.mall.domain.entity.CartItem.class));
        verify(carts, org.mockito.Mockito.never()).update(any(), any());
        verify(idempotency, never()).updateById(any(MallRequestIdempotency.class));
    }

    @Test
    void successfulRegistrationPersistsTheResultInTheIdempotencyLedger() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-07T14:00:00+08:00");
        when(clock.now()).thenReturn(now);
        doAnswer(invocation -> {
            ((MallUser) invocation.getArgument(0)).setUserId(812345678901234567L);
            return 1;
        }).when(users).insert(any(MallUser.class));

        Long result = service.registerUser(new CreateUserReq("25-34", "tier1", "gold"),
                TraceContext.create(), "registration-key");

        assertEquals(812345678901234567L, result);
        org.mockito.ArgumentCaptor<MallRequestIdempotency> record =
                org.mockito.ArgumentCaptor.forClass(MallRequestIdempotency.class);
        verify(idempotency).insert(record.capture());
        assertEquals("registerUser", record.getValue().getOperation());
        assertEquals(MallBusinessService.requestHash("25-34", "tier1", "gold"),
                record.getValue().getRequestHash());
        assertEquals("812345678901234567", record.getValue().getResultValue());
        verify(idempotency).updateById(record.getValue());
        verify(users, times(1)).insert(any(MallUser.class));
    }

    @Test
    void reusingCommittedKeyForDifferentOperationOrInputReturnsConflict() {
        MallRequestIdempotency existing = new MallRequestIdempotency();
        existing.setIdempotencyKey("registration-key");
        existing.setOperation("payOrder");
        existing.setRequestHash(MallBusinessService.requestHash("25-34", "tier1", "gold"));
        existing.setResultValue("812345678901234567");
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate"))
                .when(idempotency).insert(any(MallRequestIdempotency.class));
        when(idempotency.selectByKeyForUpdate("registration-key")).thenReturn(existing);

        MallBizException failure = assertThrows(MallBizException.class,
                () -> service.registerUser(new CreateUserReq("25-34", "tier1", "gold"),
                        TraceContext.create(), "registration-key"));

        assertEquals(MallBizException.IDEMPOTENCY_CONFLICT, failure.getCode());
        verify(users, never()).insert(any(com.graduation.mall.domain.entity.MallUser.class));
    }

    @Test
    void reusingCommittedKeyForSameOperationWithDifferentInputReturnsConflict() {
        MallRequestIdempotency existing = new MallRequestIdempotency();
        existing.setIdempotencyKey("registration-key");
        existing.setOperation("registerUser");
        existing.setRequestHash(MallBusinessService.requestHash("25-34", "tier1", "gold"));
        existing.setResultValue("812345678901234567");
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate"))
                .when(idempotency).insert(any(MallRequestIdempotency.class));
        when(idempotency.selectByKeyForUpdate("registration-key")).thenReturn(existing);

        MallBizException failure = assertThrows(MallBizException.class,
                () -> service.registerUser(new CreateUserReq("35-44", "tier1", "gold"),
                        TraceContext.create(), "registration-key"));

        assertEquals(MallBizException.IDEMPOTENCY_CONFLICT, failure.getCode());
        verify(users, never()).insert(any(com.graduation.mall.domain.entity.MallUser.class));
    }

    @Test
    void orderKeyConflictsWithTheSharedLedgerWhenItWasCommittedForAnotherOperation() {
        MallRequestIdempotency existing = new MallRequestIdempotency();
        existing.setIdempotencyKey("shared-key");
        existing.setOperation("registerUser");
        existing.setRequestHash(MallBusinessService.requestHash("25-34", "tier1", "gold"));
        existing.setResultValue("812345678901234567");
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate"))
                .when(idempotency).insert(any(MallRequestIdempotency.class));
        when(idempotency.selectByKeyForUpdate("shared-key")).thenReturn(existing);

        MallBizException failure = assertThrows(MallBizException.class,
                () -> service.createOrder(request(), TraceContext.create(), "shared-key"));

        assertEquals(MallBizException.IDEMPOTENCY_CONFLICT, failure.getCode());
        verify(orders, never()).selectOne(org.mockito.ArgumentMatchers.<Wrapper<MallOrder>>any());
        verify(orders, never()).insert(any(MallOrder.class));
    }

    private static OrderCreateReq request() {
        return new OrderCreateReq(7L, List.of(new OrderItemReq(10L, 2)));
    }

    private static String hashFor(Long userId, List<OrderItemReq> items) {
        return MallBusinessService.orderRequestHash(new OrderCreateReq(userId, items));
    }
}
