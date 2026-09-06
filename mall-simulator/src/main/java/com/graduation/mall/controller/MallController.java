package com.graduation.mall.controller;

import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.controller.MallDtos.CartAddReq;
import com.graduation.mall.controller.MallDtos.CreateUserReq;
import com.graduation.mall.controller.MallDtos.OrderCancelReq;
import com.graduation.mall.controller.MallDtos.OrderCreateReq;
import com.graduation.mall.controller.MallDtos.OrderPayReq;
import com.graduation.mall.controller.MallDtos.RefundApplyReq;
import com.graduation.mall.controller.MallDtos.RefundCompleteReq;
import com.graduation.mall.domain.entity.CartItem;
import com.graduation.mall.domain.entity.MallOrder;
import com.graduation.mall.domain.entity.OrderItem;
import com.graduation.mall.domain.entity.Product;
import com.graduation.mall.domain.entity.Refund;
import com.graduation.mall.domain.service.MallBusinessService;
import com.graduation.mall.outbox.TraceContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商城 REST API（首版）：人工操作入口，与自动生成器复用同一 MallBusinessService（§5.2.2）。
 * Controller 只做参数接收与结果组织，不直接操纵状态字段。
 */
@RestController
@RequestMapping("/api/v1/mall")
@RequiredArgsConstructor
public class MallController {

    private final MallBusinessService mallService;

    @PostMapping("/users")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody CreateUserReq req) {
        TraceContext trace = TraceContext.create();
        Long userId = mallService.registerUser(req, trace);
        return ApiResponse.ok(Map.of("userId", String.valueOf(userId)), trace.traceId());
    }

    @GetMapping("/products")
    public ApiResponse<List<Product>> listProducts(@RequestParam(required = false) Long categoryId) {
        return ApiResponse.ok(mallService.listProducts(categoryId), TraceContext.create().traceId());
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<Product> getProduct(@PathVariable Long productId) {
        return ApiResponse.ok(mallService.getProduct(productId), TraceContext.create().traceId());
    }

    @PostMapping("/cart/items")
    public ApiResponse<Void> addCartItem(@Valid @RequestBody CartAddReq req) {
        TraceContext trace = TraceContext.create();
        mallService.addCartItem(req, trace);
        return ApiResponse.ok(null, trace.traceId());
    }

    @GetMapping("/cart/items")
    public ApiResponse<List<CartItem>> listCart(@RequestParam Long userId) {
        return ApiResponse.ok(mallService.listCart(userId), TraceContext.create().traceId());
    }

    @PostMapping("/orders")
    public ApiResponse<Map<String, Object>> createOrder(@Valid @RequestBody OrderCreateReq req) {
        TraceContext trace = TraceContext.create();
        Long orderId = mallService.createOrder(req, trace);
        // ID 超 JS 安全整数，统一字符串返回（雪花 19 位）
        return ApiResponse.ok(Map.of("orderId", String.valueOf(orderId)), trace.traceId());
    }

    @PostMapping("/orders/{orderId}/pay")
    public ApiResponse<Void> payOrder(@PathVariable Long orderId, @Valid @RequestBody OrderPayReq req) {
        TraceContext trace = TraceContext.create();
        mallService.payOrder(orderId, req.userId(), trace);
        return ApiResponse.ok(null, trace.traceId());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ApiResponse<Void> cancelOrder(@PathVariable Long orderId, @Valid @RequestBody OrderCancelReq req) {
        TraceContext trace = TraceContext.create();
        mallService.cancelOrder(orderId, req.userId(), req.reason(), trace);
        return ApiResponse.ok(null, trace.traceId());
    }

    @PostMapping("/orders/{orderId}/refunds")
    public ApiResponse<Map<String, Object>> applyRefund(@PathVariable Long orderId,
                                                        @Valid @RequestBody RefundApplyReq req) {
        TraceContext trace = TraceContext.create();
        Long refundId = mallService.applyRefund(orderId, req.userId(), req.amount(), req.reason(), trace);
        return ApiResponse.ok(Map.of("refundId", String.valueOf(refundId)), trace.traceId());
    }

    @PostMapping("/refunds/{refundId}/complete")
    public ApiResponse<Void> completeRefund(@PathVariable Long refundId,
                                            @Valid @RequestBody RefundCompleteReq req) {
        TraceContext trace = TraceContext.create();
        mallService.completeRefund(refundId, req.userId(), trace);
        return ApiResponse.ok(null, trace.traceId());
    }

    @GetMapping("/orders")
    public ApiResponse<List<Map<String, Object>>> listOrders(@RequestParam(required = false) Long userId,
                                                             @RequestParam(required = false) String status) {
        TraceContext trace = TraceContext.create();
        List<Map<String, Object>> result = mallService.listOrders(userId, status).stream().map(o -> {
            Map<String, Object> m = new HashMap<>();
            // 雪花 ID 超 JS 安全整数：一律字符串返回（前端回传不再丢精度）
            m.put("orderId", String.valueOf(o.getOrderId()));
            m.put("userId", String.valueOf(o.getUserId()));
            m.put("status", o.getStatus());
            m.put("totalAmount", o.getTotalAmount());
            m.put("createdAt", o.getCreatedAt());
            m.put("items", mallService.listOrderItems(o.getOrderId()));
            m.put("refunds", mallService.listRefunds(o.getOrderId()).stream().map(r -> {
                Map<String, Object> rf = new HashMap<>();
                rf.put("refundId", String.valueOf(r.getRefundId()));
                rf.put("status", r.getStatus());
                rf.put("amount", r.getAmount());
                rf.put("reason", r.getReason());
                return rf;
            }).toList());
            return m;
        }).toList();
        return ApiResponse.ok(result, trace.traceId());
    }
}