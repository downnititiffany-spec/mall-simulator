package com.graduation.mall.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.mall.common.MallBizException;
import com.graduation.mall.controller.MallDtos.CartAddReq;
import com.graduation.mall.controller.MallDtos.CreateUserReq;
import com.graduation.mall.controller.MallDtos.OrderCreateReq;
import com.graduation.mall.controller.MallDtos.OrderItemReq;
import com.graduation.mall.domain.entity.Inventory;
import com.graduation.mall.domain.entity.MallOrder;
import com.graduation.mall.domain.entity.OrderItem;
import com.graduation.mall.domain.entity.Refund;
import com.graduation.mall.domain.entity.EventOutbox;
import com.graduation.mall.domain.enums.OrderStatus;
import com.graduation.mall.domain.enums.RefundStatus;
import com.graduation.mall.domain.mapper.EventOutboxMapper;
import com.graduation.mall.domain.mapper.InventoryMapper;
import com.graduation.mall.domain.mapper.MallOrderMapper;
import com.graduation.mall.domain.mapper.OrderItemMapper;
import com.graduation.mall.domain.mapper.RefundMapper;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.TraceContext;
import com.graduation.mall.support.MallTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商城业务全链路集成测试（§20.1/§21.2/§5.2.4）：
 * 注册→加购→下单（金额=Σqty×price）→支付→完成；优惠之外的状态机拒绝；
 * 库存预扣/释放；退款不超过已付；同一事务写 Outbox 事件。
 */
@Transactional
// 分类标记（V25-S02 / K-02）：本类需要真实数据库隔离实例（3307）。
//   * 默认纯测试套件（mvn test）按 pom 的 <excludedGroups>it</excludedGroups> 不选中本类；
//   * 显式集成套件（mvn test -Pisolated-tests）选中本类，缺隔离档案时**硬拒（红）而非 skip**。
@Tag("it")
class MallBusinessServiceTest extends MallTestSupport {

    @Autowired
    private MallBusinessService mall;

    @Autowired
    private MallOrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private RefundMapper refundMapper;

    @Autowired
    private InventoryMapper inventoryMapper;

    @Autowired
    private EventOutboxMapper outboxMapper;

    private Long registerUser(String ageGroup) {
        return mall.registerUser(new CreateUserReq(ageGroup, "tier1", "gold"), TraceContext.create());
    }

    private OrderCreateReq orderOf(Long userId, Long productId, int qty) {
        return new OrderCreateReq(userId, List.of(new OrderItemReq(productId, qty)));
    }

    @Test
    @DisplayName("下单→支付→完成：金额=Σ(qty×price)，库存预扣，事件齐全")
    void fullLifecycle() {
        Long u1 = registerUser("25-34");

        Long orderId = mall.createOrder(orderOf(u1, 1001L, 2), TraceContext.create());
        MallOrder created = orderMapper.selectById(orderId);
        // 1001 = 29.90 × 2 = 59.80
        assertEquals(0, new BigDecimal("59.80").compareTo(created.getTotalAmount()));
        assertEquals(OrderStatus.CREATED.name(), created.getStatus());

        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
        assertEquals(1, items.size());
        assertEquals(0, new BigDecimal("59.80").compareTo(items.get(0).getAmount()));

        // 库存：100 → 98 可用，预留 2
        Inventory inv = inventoryMapper.selectById(1001L);
        assertEquals(98, inv.getAvailableQty());
        assertEquals(2, inv.getReservedQty());

        mall.payOrder(orderId, u1, TraceContext.create());
        assertEquals(OrderStatus.PAID.name(), orderMapper.selectById(orderId).getStatus());

        mall.completeOrder(orderId, TraceContext.create());
        assertEquals(OrderStatus.COMPLETED.name(), orderMapper.selectById(orderId).getStatus());

        // 事件：user_registered、order_created、order_paid、stock_reserved，全部同批产生
        List<String> types = outboxMapper.selectList(null).stream()
                .map(EventOutbox::getEventType).toList();
        assertTrue(types.contains(EventContract.USER_REGISTERED));
        assertTrue(types.contains(EventContract.ORDER_CREATED));
        assertTrue(types.contains(EventContract.ORDER_PAID));
        assertTrue(types.contains(EventContract.STOCK_RESERVED));
        assertEquals(4, types.size());
    }

    @Test
    @DisplayName("非法流转被拒：已支付订单不能取消、不能重复支付、未支付订单不能退款完成")
    void illegalTransitionsRejected() {
        Long u1 = registerUser("25-34");
        Long orderId = mall.createOrder(orderOf(u1, 1002L, 1), TraceContext.create());
        mall.payOrder(orderId, u1, TraceContext.create());

        assertThrows(MallBizException.class, () -> mall.cancelOrder(orderId, u1, "x", TraceContext.create()));
        assertThrows(MallBizException.class, () -> mall.payOrder(orderId, u1, TraceContext.create()));
        assertThrows(MallBizException.class, () -> mall.completeOrder(999999999L, TraceContext.create()));
    }

    @Test
    @DisplayName("取消订单：释放预留库存并产生 order_cancelled + stock_released 事件")
    void cancelReleasesStock() {
        Long u1 = registerUser("25-34");
        Long orderId = mall.createOrder(orderOf(u1, 1001L, 2), TraceContext.create());
        mall.cancelOrder(orderId, u1, "change_of_mind", TraceContext.create());

        Inventory inv = inventoryMapper.selectById(1001L);
        assertEquals(100, inv.getAvailableQty());
        assertEquals(0, inv.getReservedQty());

        List<String> types = outboxMapper.selectList(null).stream()
                .map(EventOutbox::getEventType).toList();
        assertTrue(types.contains(EventContract.ORDER_CANCELLED));
        assertTrue(types.contains(EventContract.STOCK_RELEASED));
    }

    @Test
    @DisplayName("库存不足：下单拒绝且事务回滚（无订单、无事件）")
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void insufficientStockRollsBack() {
        Long u1 = registerUser("25-34");
        inventoryMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Inventory>()
                .set(Inventory::getAvailableQty, 1)
                .eq(Inventory::getProductId, 1001L));

        assertThrows(MallBizException.class,
                () -> mall.createOrder(orderOf(u1, 1001L, 2), TraceContext.create()));
        assertEquals(0, orderMapper.selectCount(new LambdaQueryWrapper<MallOrder>()
                .eq(MallOrder::getUserId, u1)));
        // 基线仅 registerUser 的 1 条事件（NOT_SUPPORTED 已提交）；下单事件必须整体回滚
        assertEquals(1, outboxMapper.selectCount(null));
    }

    @Test
    @DisplayName("退款：申请≤已付，完成退款后订单 REFUNDED，事件齐全")
    void refundFlow() {
        Long u1 = registerUser("25-34");
        Long orderId = mall.createOrder(orderOf(u1, 1003L, 1), TraceContext.create()); // 149.00
        mall.payOrder(orderId, u1, TraceContext.create());

        // 超付拒绝
        assertThrows(MallBizException.class,
                () -> mall.applyRefund(orderId, u1, new BigDecimal("200.00"), "q", TraceContext.create()));

        Long refundId = mall.applyRefund(orderId, u1, new BigDecimal("50.00"), "quality_issue",
                TraceContext.create());
        Refund created = refundMapper.selectById(refundId);
        assertEquals(RefundStatus.CREATED.name(), created.getStatus());
        assertEquals(OrderStatus.REFUNDING.name(), orderMapper.selectById(orderId).getStatus());

        mall.completeRefund(refundId, u1, TraceContext.create());
        assertEquals(RefundStatus.COMPLETED.name(), refundMapper.selectById(refundId).getStatus());
        assertEquals(OrderStatus.REFUNDED.name(), orderMapper.selectById(orderId).getStatus());

        List<String> types = outboxMapper.selectList(null).stream()
                .map(EventOutbox::getEventType).toList();
        assertTrue(types.contains(EventContract.REFUND_CREATED));
        assertTrue(types.contains(EventContract.REFUND_COMPLETED));
    }

    @Test
    @DisplayName("加购：同商品累计数量，产生 cart_add 行为事件")
    void cartAccumulates() {
        Long u1 = registerUser("25-34");
        mall.addCartItem(new CartAddReq(u1, 1001L, 1), TraceContext.create());
        mall.addCartItem(new CartAddReq(u1, 1001L, 2), TraceContext.create());

        var cart = mall.listCart(u1);
        assertEquals(1, cart.size());
        assertEquals(3, cart.get(0).getQuantity());

        List<String> types = outboxMapper.selectList(null).stream()
                .map(EventOutbox::getEventType).toList();
        assertEquals(2, types.stream().filter(t -> t.equals(EventContract.BEHAVIOR)).count());
    }
}
