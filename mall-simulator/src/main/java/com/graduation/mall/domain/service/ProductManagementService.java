package com.graduation.mall.domain.service;

import com.graduation.mall.common.MallBizException;
import com.graduation.mall.domain.entity.Category;
import com.graduation.mall.domain.entity.Inventory;
import com.graduation.mall.domain.entity.Product;
import com.graduation.mall.domain.enums.ProductStatus;
import com.graduation.mall.domain.mapper.CategoryMapper;
import com.graduation.mall.domain.mapper.InventoryMapper;
import com.graduation.mall.domain.mapper.ProductMapper;
import com.graduation.mall.outbox.EventClock;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.EventOutboxService;
import com.graduation.mall.outbox.EventPayloadFactory;
import com.graduation.mall.outbox.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 商品管理（生成器补建商品走此服务 + product_created 事件，§20.1/§2.2）。
 */
@Service
@RequiredArgsConstructor
public class ProductManagementService {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final InventoryMapper inventoryMapper;
    private final EventOutboxService outboxService;
    private final EventClock eventClock;

    @Transactional(rollbackFor = Exception.class)
    public Long createProduct(String name, Long categoryId, Long brandId,
                              BigDecimal price, BigDecimal cost, TraceContext trace) {
        if (price == null || cost == null || price.compareTo(cost) < 0) {
            throw new MallBizException("PRODUCT_PRICE_INVALID", "price 必须不小于 cost");
        }
        if (categoryMapper.selectById(categoryId) == null) {
            throw new MallBizException(MallBizException.PRODUCT_NOT_FOUND, "分类不存在: " + categoryId);
        }
        OffsetDateTime now = eventClock.now();
        Product product = new Product();
        product.setProductName(name);
        product.setCategoryId(categoryId);
        product.setBrandId(brandId);
        product.setPrice(price);
        product.setCost(cost);
        product.setStatus(ProductStatus.on_sale.name());
        product.setCreatedAt(now.toLocalDateTime());
        productMapper.insert(product);

        // 新商品必须有库存行（§20.1 库存不得为负；下单预扣依赖该行）
        Inventory inv = new Inventory();
        inv.setProductId(product.getProductId());
        inv.setAvailableQty(100);
        inv.setReservedQty(0);
        inv.setVersion(0);
        inventoryMapper.insert(inv);

        outboxService.append(trace, EventContract.AGG_PRODUCT, String.valueOf(product.getProductId()),
                EventContract.PRODUCT_CREATED, now,
                EventPayloadFactory.productCreated(product.getProductId(), name, categoryId,
                        brandId, price, cost, ProductStatus.on_sale.name()));
        return product.getProductId();
    }

    /**
     * 改价（price_increase 场景真实提价，§2.2 product_updated 事件）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePrice(Long productId, BigDecimal newPrice, TraceContext trace) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new MallBizException(MallBizException.PRODUCT_NOT_FOUND, "商品不存在: " + productId);
        }
        if (newPrice == null || newPrice.compareTo(product.getCost()) < 0) {
            throw new MallBizException("PRODUCT_PRICE_INVALID", "新价格必须不小于成本");
        }
        product.setPrice(newPrice);
        productMapper.updateById(product);

        OffsetDateTime now = eventClock.now();
        outboxService.append(trace, EventContract.AGG_PRODUCT, String.valueOf(productId),
                EventContract.PRODUCT_UPDATED, now,
                EventPayloadFactory.productUpdated(productId, product.getProductName(),
                        product.getCategoryId(), product.getBrandId(), newPrice, product.getCost(),
                        product.getStatus()));
    }
}