package com.graduation.mall.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.mall.common.ApiResponse;
import com.graduation.mall.domain.entity.Inventory;
import com.graduation.mall.domain.entity.Product;
import com.graduation.mall.domain.mapper.InventoryMapper;
import com.graduation.mall.domain.mapper.ProductMapper;
import com.graduation.mall.domain.service.InventoryService;
import com.graduation.mall.domain.service.ProductManagementService;
import com.graduation.mall.outbox.TraceContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品管理（§2.2 商品生命周期；admin 专属，前缀 /api/v1/admin 已隔离）。
 * 域逻辑复用 ProductManagementService / InventoryService，事件与事务一致性不重实现。
 */
@RestController
@RequestMapping("/api/v1/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductManagementService productService;
    private final InventoryService inventoryService;
    private final ProductMapper productMapper;
    private final InventoryMapper inventoryMapper;

    public record CreateProductReq(
            @NotBlank String name,
            @NotNull Long categoryId,
            Long brandId,
            @NotNull BigDecimal price,
            @NotNull BigDecimal cost) {
    }

    public record PriceReq(@NotNull BigDecimal price) {
    }

    public record StockReq(@NotNull Integer quantity, String changeType) {
    }

    public record StatusReq(@NotBlank String status) {
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long categoryId) {
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(categoryId != null, Product::getCategoryId, categoryId)
                .orderByAsc(Product::getProductId));
        List<Map<String, Object>> result = products.stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("productId", p.getProductId());
            m.put("productName", p.getProductName());
            m.put("categoryId", p.getCategoryId());
            m.put("brandId", p.getBrandId());
            m.put("price", p.getPrice());
            m.put("cost", p.getCost());
            m.put("status", p.getStatus());
            m.put("createdAt", p.getCreatedAt());
            Inventory inv = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                    .eq(Inventory::getProductId, p.getProductId()));
            m.put("availableQty", inv == null ? 0 : inv.getAvailableQty());
            m.put("reservedQty", inv == null ? 0 : inv.getReservedQty());
            return m;
        }).toList();
        return ApiResponse.ok(result, TraceContext.create().traceId());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateProductReq req) {
        TraceContext trace = TraceContext.create();
        Long id = productService.createProduct(req.name(), req.categoryId(), req.brandId(),
                req.price(), req.cost(), trace);
        return ApiResponse.ok(Map.of("productId", String.valueOf(id)), trace.traceId());
    }

    @PostMapping("/{productId}/price")
    public ApiResponse<Void> updatePrice(@PathVariable Long productId, @RequestBody PriceReq req) {
        TraceContext trace = TraceContext.create();
        productService.updatePrice(productId, req.price(), trace);
        return ApiResponse.ok(null, trace.traceId());
    }

    @PostMapping("/{productId}/stock")
    public ApiResponse<Void> adjustStock(@PathVariable Long productId, @RequestBody StockReq req) {
        TraceContext trace = TraceContext.create();
        inventoryService.adjustStock(productId,
                req.changeType() == null ? "adjust" : req.changeType(), req.quantity(), trace);
        return ApiResponse.ok(null, trace.traceId());
    }

    @PostMapping("/{productId}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long productId, @RequestBody StatusReq req) {
        TraceContext trace = TraceContext.create();
        productService.updateStatus(productId, req.status(), trace);
        return ApiResponse.ok(null, trace.traceId());
    }
}