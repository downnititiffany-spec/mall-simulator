package com.graduation.mall.support;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.graduation.mall.domain.entity.CartItem;
import com.graduation.mall.domain.entity.EventOutbox;
import com.graduation.mall.domain.entity.Inventory;
import com.graduation.mall.domain.entity.MallOrder;
import com.graduation.mall.domain.entity.MallUser;
import com.graduation.mall.domain.entity.OrderItem;
import com.graduation.mall.domain.entity.Payment;
import com.graduation.mall.domain.entity.Refund;
import com.graduation.mall.domain.mapper.CartItemMapper;
import com.graduation.mall.domain.mapper.EventOutboxMapper;
import com.graduation.mall.domain.mapper.InventoryMapper;
import com.graduation.mall.domain.mapper.MallOrderMapper;
import com.graduation.mall.domain.mapper.MallUserMapper;
import com.graduation.mall.domain.mapper.OrderItemMapper;
import com.graduation.mall.domain.mapper.PaymentMapper;
import com.graduation.mall.domain.mapper.RefundMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 集成测试基类：
 * - 独立测试库（库名由隔离档案提供，必须以本次 testRunId 为前缀；见 application-test.yml）；
 * - 每次测试使用全新 Landing 临时目录（@DynamicPropertySource 覆盖 mall.landing.path，
 *   RollingJsonEventWriter 每次写入时才解析该属性，因此按测试方法生效）；
 * - @BeforeEach 清空业务表并恢复种子库存，保证每个测试从确定状态开始；
 * - 每类测试结束销毁 Spring 上下文，避免 Bean/路径串扰。
 *
 * <p><b>V25-S03 R-1 整改</b>：本基类的 {@code delete(null)} 全表清空是 mall 测试里最危险的写入面。
 * 现在 {@link #freshState()} 的第一步就是 {@link MallIsolationGuard#verifyBeforeWrite}——
 * 目标不是登记的隔离实例/库（例如宿主 3306 或真实业务库 {@code mall_simulator}）时，
 * 在任何 DML 之前抛异常拒绝，<b>不会</b>先删一行再报错。</p>
 *
 * 边界说明（§5.2）：本基类只清理商城责任范围内的表（商品/库存/交易/Outbox/账号会话）。
 * 采集(ingestion)/指标(metric)/流水线(pipeline)/AI(ai)/决策(decision) 的表与 Mapper
 * 属于 analytics-server，已随平台复制代码移出本模块，故此处不再引用。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MallIsolationTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class MallTestSupport {

    protected static final AtomicReference<Path> LANDING = new AtomicReference<>(
            Path.of(System.getProperty("java.io.tmpdir"), "mall-landing-default"));

    @Autowired
    private EventOutboxMapper eventOutboxMapper;
    @Autowired
    private MallOrderMapper mallOrderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private PaymentMapper paymentMapper;
    @Autowired
    private RefundMapper refundMapper;
    @Autowired
    private CartItemMapper cartItemMapper;
    @Autowired
    private MallUserMapper mallUserMapper;
    @Autowired
    private InventoryMapper inventoryMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 注入本上下文实际使用的数据源：写前校验要拿它核对真实库名/账号/实例端口。 */
    @Autowired
    private javax.sql.DataSource dataSource;

    @DynamicPropertySource
    static void landingProps(DynamicPropertyRegistry registry) {
        registry.add("mall.landing.path", () -> LANDING.get().toString());
    }

    @BeforeEach
    void freshState() throws IOException {
        // V25-S03 R-1：先过门禁，再动任何一行。
        // 顺序很关键——这里如果放到 delete 之后，就变成"先删正式库再报错"。
        MallIsolationGuard.verifyBeforeWrite(dataSource, "MallTestSupport.freshState");
        LANDING.set(Files.createTempDirectory("mall-landing-"));
        eventOutboxMapper.delete(null);
        mallOrderMapper.delete(null);
        orderItemMapper.delete(null);
        paymentMapper.delete(null);
        refundMapper.delete(null);
        cartItemMapper.delete(null);
        mallUserMapper.delete(null);
        // 会话表清空（sys_user 保留 V7 种子账号供认证测试）
        jdbc.update("DELETE FROM user_session");
        // 恢复种子库存（商品/分类由 Flyway 种子固定）
        for (Long productId : List.of(1001L, 1002L, 1003L, 1004L)) {
            inventoryMapper.update(null, new LambdaUpdateWrapper<Inventory>()
                    .set(Inventory::getAvailableQty, 100)
                    .set(Inventory::getReservedQty, 0)
                    .set(Inventory::getVersion, 0)
                    .eq(Inventory::getProductId, productId));
        }
    }
}
