package com.graduation.mall.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.graduation.mall.common.MallBizException;
import com.graduation.mall.controller.MallDtos.CartAddReq;
import com.graduation.mall.controller.MallDtos.CreateUserReq;
import com.graduation.mall.controller.MallDtos.OrderCreateReq;
import com.graduation.mall.controller.MallDtos.OrderItemReq;
import com.graduation.mall.domain.entity.CartItem;
import com.graduation.mall.domain.entity.Inventory;
import com.graduation.mall.domain.entity.MallOrder;
import com.graduation.mall.domain.entity.MallUser;
import com.graduation.mall.domain.entity.OrderItem;
import com.graduation.mall.domain.entity.Payment;
import com.graduation.mall.domain.entity.Product;
import com.graduation.mall.domain.entity.Refund;
import com.graduation.mall.domain.enums.BehaviorType;
import com.graduation.mall.domain.enums.OrderStatus;
import com.graduation.mall.domain.enums.PaymentStatus;
import com.graduation.mall.domain.enums.ProductStatus;
import com.graduation.mall.domain.enums.RefundStatus;
import com.graduation.mall.domain.mapper.CartItemMapper;
import com.graduation.mall.domain.mapper.InventoryMapper;
import com.graduation.mall.domain.mapper.MallOrderMapper;
import com.graduation.mall.domain.mapper.MallUserMapper;
import com.graduation.mall.domain.mapper.OrderItemMapper;
import com.graduation.mall.domain.mapper.PaymentMapper;
import com.graduation.mall.domain.mapper.ProductMapper;
import com.graduation.mall.domain.mapper.RefundMapper;
import com.graduation.mall.domain.state.OrderStateMachine;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.EventClock;
import com.graduation.mall.outbox.EventOutboxService;
import com.graduation.mall.outbox.EventPayloadFactory;
import com.graduation.mall.outbox.EventPayloadFactory.OrderItemVo;
import com.graduation.mall.outbox.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 商城核心业务 Service（§20.1/§5.2.4）：
 * - 与 event_outbox 同库同事务（调用方不可绕过，Controller/生成器禁止直接改状态）；
 * - 订单金额由订单项计算，禁止随机金额；
 * - 库存预扣/释放使用原子 SQL + 条件更新，库存不得为负。
 * 每个业务方法产生对应类型事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MallBusinessService {

    private final MallUserMapper userMapper;
    private final ProductMapper productMapper;
    private final InventoryMapper inventoryMapper;
    private final CartItemMapper cartItemMapper;
    private final MallOrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentMapper paymentMapper;
    private final RefundMapper refundMapper;
    private final EventOutboxService outboxService;
    private final EventClock eventClock;

    // ── 用户 ────────────────────────────────────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public Long registerUser(CreateUserReq req, TraceContext trace) {
        OffsetDateTime now = eventClock.now();
        MallUser user = new MallUser();
        user.setAgeGroup(req.ageGroup());
        user.setCityLevel(req.cityLevel());
        user.setMemberLevel(req.memberLevel());
        user.setRegisterTime(now.toLocalDateTime());
        user.setCreatedAt(now.toLocalDateTime());
        userMapper.insert(user);

        outboxService.append(trace, EventContract.AGG_USER, String.valueOf(user.getUserId()),
                EventContract.USER_REGISTERED, now,
                EventPayloadFactory.userRegistered(user.getUserId(), req.ageGroup(), req.cityLevel(),
                        req.memberLevel(), now));
        return user.getUserId();
    }

    // ── 商品（首版种子数据来自 Flyway；查询接口直读） ─────────────────────

    public List<Product> listProducts(Long categoryId) {
        if (categoryId == null) {
            return productMapper.selectList(new LambdaQueryWrapper<Product>()
                    .eq(Product::getStatus, ProductStatus.on_sale.name())
                    .orderByAsc(Product::getProductId));
        }
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getCategoryId, categoryId)
                .eq(Product::getStatus, ProductStatus.on_sale.name())
                .orderByAsc(Product::getProductId));
    }

    public Product getProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new MallBizException(MallBizException.PRODUCT_NOT_FOUND, "商品不存在: " + productId);
        }
        return product;
    }

    // ── 购物车 ──────────────────────────────────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public void addCartItem(CartAddReq req, TraceContext trace) {
        requireUser(req.userId());
        Product product = requireOnSale(req.productId());

        CartItem existing = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, req.userId())
                .eq(CartItem::getProductId, req.productId()));
        if (existing == null) {
            CartItem item = new CartItem();
            item.setUserId(req.userId());
            item.setProductId(req.productId());
            item.setQuantity(req.quantity());
            cartItemMapper.insert(item);
        } else {
            cartItemMapper.update(null, new LambdaUpdateWrapper<CartItem>()
                    .setSql("quantity = quantity + " + req.quantity())
                    .eq(CartItem::getId, existing.getId()));
        }

        OffsetDateTime now = eventClock.now();
        outboxService.append(trace, EventContract.AGG_USER, String.valueOf(req.userId()),
                EventContract.BEHAVIOR, now,
                EventPayloadFactory.behavior(req.userId(), req.productId(),
                        "session-" + trace.traceId(), BehaviorType.cart_add.name(), "app", now));
    }

    public List<CartItem> listCart(Long userId) {
        return cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .orderByDesc(CartItem::getUpdatedAt));
    }

    // ── 订单 ────────────────────────────────────────────────────────────────

    /**
     * 下单（核心事务）：校验用户/商品/库存 → 订单+订单项+库存预扣+order_created/stock_reserved 事件，
     * 全部在同一事务。金额 = Σ(quantity×unit_price−discount)，不落随机金额。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(OrderCreateReq req, TraceContext trace) {
        requireUser(req.userId());
        OffsetDateTime now = eventClock.now();
        List<OrderItemVo> itemVos = new ArrayList<>();

        for (OrderItemReq itemReq : req.items()) {
            Product product = requireOnSale(itemReq.productId());
            BigDecimal unitPrice = product.getPrice();
            BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(itemReq.quantity()));
            itemVos.add(new OrderItemVo(product.getProductId(), itemReq.quantity(),
                    unitPrice, BigDecimal.ZERO.setScale(2), amount));
        }
        BigDecimal total = itemVos.stream().map(OrderItemVo::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        MallOrder order = new MallOrder();
        order.setUserId(req.userId());
        order.setStatus(OrderStatus.CREATED.name());
        order.setTotalAmount(total);
        order.setCreatedAt(now.toLocalDateTime());
        orderMapper.insert(order);

        for (OrderItemVo vo : itemVos) {
            OrderItem item = new OrderItem();
            item.setOrderId(order.getOrderId());
            item.setProductId(vo.productId());
            item.setQuantity(vo.quantity());
            item.setUnitPrice(vo.unitPrice());
            item.setDiscount(vo.discount());
            item.setAmount(vo.amount());
            orderItemMapper.insert(item);

            reserveStock(vo.productId(), vo.quantity(), order.getOrderId(), trace, now);
        }

        outboxService.append(trace, EventContract.AGG_ORDER, String.valueOf(order.getOrderId()),
                EventContract.ORDER_CREATED, now,
                EventPayloadFactory.orderCreated(order.getOrderId(), req.userId(), itemVos, total, now));
        return order.getOrderId();
    }

    /** 原子预扣库存：available>=q 才允许，返回受影响行数 */
    private void reserveStock(Long productId, int quantity, Long orderId, TraceContext trace, OffsetDateTime now) {
        int rows = inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                .setSql("available_qty = available_qty - " + quantity
                        + ", reserved_qty = reserved_qty + " + quantity
                        + ", version = version + 1")
                .eq(Inventory::getProductId, productId)
                .ge(Inventory::getAvailableQty, quantity));
        if (rows == 0) {
            throw new MallBizException(MallBizException.INSUFFICIENT_STOCK, "库存不足: " + productId);
        }
        Inventory inv = inventoryMapper.selectById(productId);
        outboxService.append(trace, EventContract.AGG_INVENTORY, String.valueOf(productId),
                EventContract.STOCK_RESERVED, now,
                EventPayloadFactory.stockReserved(productId, quantity, orderId,
                        inv.getReservedQty(), inv.getAvailableQty()));
    }

    /** 原子释放预留库存（取消订单） */
    private void releaseStock(Long productId, int quantity, Long orderId, TraceContext trace, OffsetDateTime now) {
        inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                .setSql("available_qty = available_qty + " + quantity
                        + ", reserved_qty = reserved_qty - " + quantity
                        + ", version = version + 1")
                .eq(Inventory::getProductId, productId)
                .ge(Inventory::getReservedQty, quantity));
        Inventory inv = inventoryMapper.selectById(productId);
        outboxService.append(trace, EventContract.AGG_INVENTORY, String.valueOf(productId),
                EventContract.STOCK_RELEASED, now,
                EventPayloadFactory.stockReleased(productId, quantity, orderId,
                        inv.getReservedQty(), inv.getAvailableQty()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long orderId, Long userId, TraceContext trace) {
        OffsetDateTime now = eventClock.now();
        MallOrder order = requireOwnOrder(orderId, userId);
        transition(order, OrderStatus.PAID);

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setUserId(userId);
        payment.setAmount(order.getTotalAmount());
        payment.setStatus(PaymentStatus.SUCCESS.name());
        payment.setPaidAt(now.toLocalDateTime());
        paymentMapper.insert(payment);

        order.setStatus(OrderStatus.PAID.name());
        order.setPaidAt(now.toLocalDateTime());
        orderMapper.updateById(order);

        outboxService.append(trace, EventContract.AGG_ORDER, String.valueOf(orderId),
                EventContract.ORDER_PAID, now,
                EventPayloadFactory.orderPaid(orderId, userId, payment.getPaymentId(),
                        order.getTotalAmount(), now));
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId, Long userId, String reason, TraceContext trace) {
        OffsetDateTime now = eventClock.now();
        MallOrder order = requireOwnOrder(orderId, userId);
        transition(order, OrderStatus.CANCELLED);

        for (OrderItem item : listOrderItems(orderId)) {
            releaseStock(item.getProductId(), item.getQuantity(), orderId, trace, now);
        }
        order.setStatus(OrderStatus.CANCELLED.name());
        order.setCancelledAt(now.toLocalDateTime());
        order.setCancelReason(reason);
        orderMapper.updateById(order);

        outboxService.append(trace, EventContract.AGG_ORDER, String.valueOf(orderId),
                EventContract.ORDER_CANCELLED, now,
                EventPayloadFactory.orderCancelled(orderId, userId, reason, now));
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeOrder(Long orderId, TraceContext trace) {
        OffsetDateTime now = eventClock.now();
        MallOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new MallBizException(MallBizException.ORDER_NOT_FOUND, "订单不存在: " + orderId);
        }
        transition(order, OrderStatus.COMPLETED);
        order.setStatus(OrderStatus.COMPLETED.name());
        order.setCompletedAt(now.toLocalDateTime());
        orderMapper.updateById(order);
    }

    // ── 退款 ────────────────────────────────────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public Long applyRefund(Long orderId, Long userId, BigDecimal amount, String reason, TraceContext trace) {
        OffsetDateTime now = eventClock.now();
        MallOrder order = requireOwnOrder(orderId, userId);
        transition(order, OrderStatus.REFUNDING);

        BigDecimal refunded = refundMapper.selectList(new LambdaQueryWrapper<Refund>()
                        .eq(Refund::getOrderId, orderId))
                .stream().map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.compareTo(order.getTotalAmount().subtract(refunded)) > 0) {
            throw new MallBizException(MallBizException.REFUND_EXCEEDS_PAID,
                    "退款金额超过已付未退金额: " + amount);
        }

        Refund refund = new Refund();
        refund.setOrderId(orderId);
        refund.setUserId(userId);
        refund.setAmount(amount);
        refund.setReason(reason);
        refund.setStatus(RefundStatus.CREATED.name());
        refund.setCreatedAt(now.toLocalDateTime());
        refundMapper.insert(refund);

        order.setStatus(OrderStatus.REFUNDING.name());
        orderMapper.updateById(order);

        outboxService.append(trace, EventContract.AGG_REFUND, String.valueOf(refund.getRefundId()),
                EventContract.REFUND_CREATED, now,
                EventPayloadFactory.refundCreated(refund.getRefundId(), orderId, userId, amount, reason, now));
        return refund.getRefundId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeRefund(Long refundId, Long userId, TraceContext trace) {
        OffsetDateTime now = eventClock.now();
        Refund refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw new MallBizException(MallBizException.REFUND_NOT_FOUND, "退款单不存在: " + refundId);
        }
        if (!refund.getUserId().equals(userId)) {
            throw new MallBizException(MallBizException.ORDER_OWNER_MISMATCH, "退款单不属于该用户");
        }
        MallOrder order = orderMapper.selectById(refund.getOrderId());
        if (order == null) {
            throw new MallBizException(MallBizException.ORDER_NOT_FOUND, "订单不存在: " + refund.getOrderId());
        }
        transition(order, OrderStatus.REFUNDED);

        refund.setStatus(RefundStatus.COMPLETED.name());
        refund.setCompletedAt(now.toLocalDateTime());
        refundMapper.updateById(refund);
        order.setStatus(OrderStatus.REFUNDED.name());
        orderMapper.updateById(order);

        outboxService.append(trace, EventContract.AGG_REFUND, String.valueOf(refundId),
                EventContract.REFUND_COMPLETED, now,
                EventPayloadFactory.refundCompleted(refundId, refund.getOrderId(), userId,
                        refund.getAmount(), now));
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    public List<MallOrder> listOrders(Long userId, String status) {
        LambdaQueryWrapper<MallOrder> w = new LambdaQueryWrapper<MallOrder>()
                .eq(userId != null, MallOrder::getUserId, userId)
                .eq(status != null && !status.isBlank(), MallOrder::getStatus, status)
                .orderByDesc(MallOrder::getCreatedAt);
        return orderMapper.selectList(w);
    }

    public List<OrderItem> listOrderItems(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
    }

    public List<Refund> listRefunds(Long orderId) {
        return refundMapper.selectList(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getOrderId, orderId));
    }

    // ── 内部校验 ────────────────────────────────────────────────────────────

    /** 状态机校验（§20.1）：非法流转包装为稳定业务错误码，避免把 IllegalOrderStateException 泄露到接口层 */
    private void transition(MallOrder order, OrderStatus to) {
        try {
            OrderStateMachine.validateTransition(OrderStatus.valueOf(order.getStatus()), to);
        } catch (OrderStateMachine.IllegalOrderStateException e) {
            throw new MallBizException(MallBizException.ORDER_STATE_ILLEGAL,
                    "订单状态不允许: " + e.getMessage());
        }
    }

    private void requireUser(Long userId) {
        if (userMapper.selectById(userId) == null) {
            throw new MallBizException(MallBizException.USER_NOT_FOUND, "用户不存在: " + userId);
        }
    }

    private Product requireOnSale(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new MallBizException(MallBizException.PRODUCT_NOT_FOUND, "商品不存在: " + productId);
        }
        if (!ProductStatus.on_sale.name().equals(product.getStatus())) {
            throw new MallBizException(MallBizException.PRODUCT_OFF_SALE, "商品已下架: " + productId);
        }
        return product;
    }

    private MallOrder requireOwnOrder(Long orderId, Long userId) {
        MallOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new MallBizException(MallBizException.ORDER_NOT_FOUND, "订单不存在: " + orderId);
        }
        if (!order.getUserId().equals(userId)) {
            throw new MallBizException(MallBizException.ORDER_OWNER_MISMATCH, "订单不属于该用户");
        }
        return order;
    }
}