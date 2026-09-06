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
import com.graduation.mall.ingestion.mapper.FileCheckpointMapper;
import com.graduation.mall.ingestion.mapper.IngestionBatchFileMapper;
import com.graduation.mall.ingestion.mapper.IngestionBatchMapper;
import com.graduation.mall.ingestion.mapper.QuarantineRecordMapper;
import com.graduation.mall.metric.mapper.MetricSnapshotMapper;
import com.graduation.mall.metric.mapper.MetricValueMapper;
import com.graduation.mall.pipeline.mapper.DataQualityResultMapper;
import com.graduation.mall.pipeline.mapper.PipelineRunMapper;
import com.graduation.mall.pipeline.mapper.PipelineStageRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * - 独立测试库 mall_simulator_test（application-test.yml，Flyway 自动建表）；
 * - 每次测试使用全新 Landing 临时目录（@DynamicPropertySource 覆盖 mall.landing.path，
 *   RollingJsonEventWriter 每次写入时才解析该属性，因此按测试方法生效）；
 * - @BeforeEach 清空业务表并恢复种子库存，保证每个测试从确定状态开始；
 * - 每类测试结束销毁 Spring 上下文，避免 Bean/路径串扰。
 */
@SpringBootTest
@ActiveProfiles("test")
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
    private IngestionBatchMapper ingestionBatchMapper;
    @Autowired
    private IngestionBatchFileMapper ingestionBatchFileMapper;
    @Autowired
    private FileCheckpointMapper fileCheckpointMapper;
    @Autowired
    private QuarantineRecordMapper quarantineRecordMapper;
    @Autowired
    private MetricValueMapper metricValueMapper;
    @Autowired
    private MetricSnapshotMapper metricSnapshotMapper;
    @Autowired
    private PipelineStageRunMapper pipelineStageRunMapper;
    @Autowired
    private PipelineRunMapper pipelineRunMapper;
    @Autowired
    private DataQualityResultMapper dataQualityResultMapper;

    @DynamicPropertySource
    static void landingProps(DynamicPropertyRegistry registry) {
        registry.add("mall.landing.path", () -> LANDING.get().toString());
    }

    @BeforeEach
    void freshState() throws IOException {
        LANDING.set(Files.createTempDirectory("mall-landing-"));
        eventOutboxMapper.delete(null);
        mallOrderMapper.delete(null);
        orderItemMapper.delete(null);
        paymentMapper.delete(null);
        refundMapper.delete(null);
        cartItemMapper.delete(null);
        mallUserMapper.delete(null);
        // 采集元数据表（批次/断点/隔离）也必须隔离，否则断点残留导致误判"无新内容"
        ingestionBatchFileMapper.delete(null);
        ingestionBatchMapper.delete(null);
        quarantineRecordMapper.delete(null);
        fileCheckpointMapper.delete(null);
        // 指标与流水线元数据
        metricValueMapper.delete(null);
        metricSnapshotMapper.delete(null);
        pipelineStageRunMapper.delete(null);
        dataQualityResultMapper.delete(null);
        pipelineRunMapper.delete(null);
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