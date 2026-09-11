package com.graduation.analytics.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.metric.MetricQualityGate;
import com.graduation.analytics.pipeline.entity.DataQualityResult;
import com.graduation.analytics.pipeline.mapper.DataQualityResultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R7-4：质量门结论的唯一实现（analytics_meta.data_quality_result → PASS/FAIL/UNKNOWN）。
 *
 * <p>为什么由本模块实现：{@code data_quality_result} 的 mapper/实体在 warehouse-pipeline（§17.2 表所有权），
 * 分析服务只通过 platform-common 的 {@link MetricQualityGate} 接口取结论，不自己查这张表 ——
 * 避免出现第二个「质量结论所有者」。</p>
 *
 * <p>判定与解析一致：severity=BLOCKING 且 passed≠1 才算阻断（ERROR/INFO 只记录，§16.3）；
 * run 号为空或该 run 没有任何质量结果 → UNKNOWN（取不到就是取不到，不冒充 PASS）。</p>
 */
@Component
@RequiredArgsConstructor
public class DataQualityGate implements MetricQualityGate {

    private final DataQualityResultMapper qualityMapper;

    @Override
    public String statusForRun(Long pipelineRunId) {
        if (pipelineRunId == null) {
            return UNKNOWN;
        }
        List<DataQualityResult> results = qualityMapper.selectList(
                new LambdaQueryWrapper<DataQualityResult>().eq(DataQualityResult::getRunId, pipelineRunId));
        if (results == null || results.isEmpty()) {
            return UNKNOWN;
        }
        boolean blockingFailed = results.stream().anyMatch(r ->
                BLOCKING.equalsIgnoreCase(r.getSeverity()) && (r.getPassed() == null || r.getPassed() != 1));
        return blockingFailed ? FAIL : PASS;
    }
}
