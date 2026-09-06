package com.graduation.mall.domain.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.graduation.mall.common.MallBizException;
import com.graduation.mall.domain.entity.Inventory;
import com.graduation.mall.domain.mapper.InventoryMapper;
import com.graduation.mall.outbox.EventClock;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.EventOutboxService;
import com.graduation.mall.outbox.EventPayloadFactory;
import com.graduation.mall.outbox.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 库存管理（生成器场景注入/补货走此服务 + stock_changed 事件，§2.10）。
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryMapper inventoryMapper;
    private final EventOutboxService outboxService;
    private final EventClock eventClock;

    /**
     * 调整库存：inbound=入库，outbound=出库，adjust=直接设定可售数量。
     * 原子条件更新保证可售库存不为负。
     */
    @Transactional(rollbackFor = Exception.class)
    public void adjustStock(Long productId, String changeType, int quantity, TraceContext trace) {
        int rows;
        switch (changeType) {
            case "inbound" -> rows = inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                    .setSql("available_qty = available_qty + " + quantity
                            + ", version = version + 1")
                    .eq(Inventory::getProductId, productId));
            case "outbound" -> rows = inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                    .setSql("available_qty = available_qty - " + quantity
                            + ", version = version + 1")
                    .eq(Inventory::getProductId, productId)
                    .ge(Inventory::getAvailableQty, quantity));
            case "adjust" -> rows = inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                    .set(Inventory::getAvailableQty, quantity)
                    .setSql("version = version + 1")
                    .eq(Inventory::getProductId, productId));
            default -> throw new MallBizException("STOCK_CHANGE_INVALID", "changeType 必须为 inbound/outbound/adjust");
        }
        if (rows == 0) {
            throw new MallBizException(MallBizException.INSUFFICIENT_STOCK, "库存调整失败(可能不足): " + productId);
        }
        Inventory after = inventoryMapper.selectById(productId);
        OffsetDateTime now = eventClock.now();
        outboxService.append(trace, EventContract.AGG_INVENTORY, String.valueOf(productId),
                EventContract.STOCK_CHANGED, now,
                EventPayloadFactory.stockChanged(productId, changeType, quantity, after.getAvailableQty()));
    }
}