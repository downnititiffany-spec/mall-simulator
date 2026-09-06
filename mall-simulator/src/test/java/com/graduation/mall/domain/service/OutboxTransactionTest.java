package com.graduation.mall.domain.service;

import com.graduation.mall.controller.MallDtos.CreateUserReq;
import com.graduation.mall.controller.MallDtos.OrderCreateReq;
import com.graduation.mall.controller.MallDtos.OrderItemReq;
import com.graduation.mall.domain.entity.EventOutbox;
import com.graduation.mall.domain.mapper.EventOutboxMapper;
import com.graduation.mall.domain.mapper.MallOrderMapper;
import com.graduation.mall.outbox.EventContract;
import com.graduation.mall.outbox.EventIdGenerator;
import com.graduation.mall.outbox.TraceContext;
import com.graduation.mall.support.MallTestSupport;
import com.graduation.mall.support.SeqEventIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Outbox 同事务性测试（§5.2.4 核心验收）：
 * 业务事务与 event_outbox 必须同时提交或同时回滚 ——
 * 预置同 event_id 冲突行 → 下单过程 outbox 写入失败 → 断言订单同样回滚（无残留）。
 *
 * 使用 NOT_SUPPORTED 让 createOrder 的 REQUIRED 事务独立开启并真正回滚，
 * 这样回滚断言看到的是提交后的真实数据库状态（同事务内无法观察到回滚）。
 */
class OutboxTransactionTest extends MallTestSupport {

    @TestConfiguration
    static class FixedIdConfig {
        @Bean
        @Primary
        EventIdGenerator fixedEventIdGenerator() {
            return new SeqEventIdGenerator();
        }
    }

    @Autowired
    private MallBusinessService mall;

    @Autowired
    private MallOrderMapper orderMapper;

    @Autowired
    private EventOutboxMapper outboxMapper;

    @Autowired
    private EventIdGenerator eventIdGenerator;

    @BeforeEach
    void resetSequence() {
        if (eventIdGenerator instanceof SeqEventIdGenerator seq) {
            seq.reset();
        }
    }

    @Test
    @DisplayName("outbox 插入失败 → 订单随事务回滚（同生共死）")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void outboxFailureRollsBackOrder() {
        // 注册用户：独立事务提交（REQUIRED），消耗 1 个 event_id（evt-00000001）
        Long u1 = mall.registerUser(new CreateUserReq("25-34", "tier1", "gold"), TraceContext.create());

        // createOrder 第一个 append（stock_reserved）将生成的下一个 event_id —— peek 不消耗序号
        String nextEventId = ((SeqEventIdGenerator) eventIdGenerator).peek(); // = evt-00000002
        EventOutbox conflict = new EventOutbox();
        conflict.setEventId(nextEventId);
        conflict.setAggregateType(EventContract.AGG_INVENTORY);
        conflict.setAggregateId("1001");
        conflict.setEventType(EventContract.STOCK_RESERVED);
        conflict.setTraceId("conflict");

        // 用合法 JSON 的 payload 占位（列校验），目的只是占据唯一键
        conflict.setPayload("{\"event_id\":\"" + nextEventId + "\",\"payload\":{}}");
        outboxMapper.insert(conflict);

        long ordersBefore = orderMapper.selectCount(null);

        // 下单：订单已插入 → 预扣库存 append 事件撞唯一键 → 异常 → 独立事务整体回滚
        assertThrows(DuplicateKeyException.class, () -> mall.createOrder(
                new OrderCreateReq(u1, List.of(new OrderItemReq(1001L, 1))), TraceContext.create()));

        long ordersAfter = orderMapper.selectCount(null);
        assertEquals(ordersBefore, ordersAfter, "订单必须随 outbox 失败一起回滚，不留半成品");
        // 已提交基线：registerUser 的 user_registered(1) + 预置冲突行(1)；createOrder 的 2 条事件必须回滚消失
        assertEquals(2, outboxMapper.selectCount(null),
                "本次下单产生的 outbox 事件必须一并回滚");
    }
}