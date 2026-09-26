package com.graduation.analytics.pipeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * G31-11（总控 D-048 §(5) M3 语义② / D-049a）：采集批次消费台账。
 *
 * <p><b>消费状态独立记录</b>：一批次是否已被流水线消费，既不是「manifest 是否 READY」
 * 的单独判定，也不是「输入值相等去重」，而是本表的独立事实。唯一写点在
 * {@code metricPublisher.publish} 返回 ok 之后（FAILED/未发布的 run 永不写行）——
 * 因此「无新输入不发布」的判定读的是这张表，而不是清单状态或指标值。</p>
 *
 * <p>一行 = 一个批次在一个源下的消费事实（UK {@code (source_id, batch_id)}）：
 * 显式重算再发布走 UPDATE（{@code publishCount}/{@code recalcCount}/{@code lastRecalc*} 递增），
 * {@code firstConsumedByRunId}/{@code consumedAt} 保留首次事实不改写。</p>
 *
 * <p>映射：无 XML，MyBatis-Plus 按 camelCase→snake_case 自动映射（同 {@link DataQualityResult}）。</p>
 */
@Data
@TableName("pipeline_batch_consumption")
public class PipelineBatchConsumption {

    /** 唯一状态：行存在即已消费（无「待处理/跳过」等其余态——不存在的事实在本表没有行） */
    public static final String STATUS_CONSUMED = "CONSUMED";

    /** created_via：流水线发布成功时写入 */
    public static final String VIA_PIPELINE = "PIPELINE";
    /** created_via：V33 对 SUCCESS+input_batch_id 存量 run 的一次性幂等回填 */
    public static final String VIA_BACKFILL_V33 = "BACKFILL_V33";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceId;

    private Long batchId;

    private String status;

    /** 最近一次消费（发布）本批次的 run；重算再发布时改写为最新 run */
    private Long consumedByRunId;

    /** 首次消费本批次的 run（重算不改写） */
    private Long firstConsumedByRunId;

    /** 最近一次消费产出的指标快照；重算再发布时改写 */
    private String targetSnapshotId;

    /** 累计发布次数（首次 = 1，每次重算再发布 +1） */
    private Integer publishCount;

    /** 显式重算次数 */
    private Integer recalcCount;

    private String lastRecalcReason;

    private String lastRecalcBy;

    private LocalDateTime lastRecalcAt;

    /** 首次消费时间（重算不改写） */
    private LocalDateTime consumedAt;

    private String createdVia;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
