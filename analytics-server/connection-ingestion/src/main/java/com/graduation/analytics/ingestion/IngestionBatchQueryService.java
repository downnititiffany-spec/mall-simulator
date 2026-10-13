package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.graduation.analytics.common.PaginationMath;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/** Read-only historical ingestion batch queries, including stable server-side pagination. */
@Service
@RequiredArgsConstructor
public class IngestionBatchQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final List<String> SORT_FIELDS = List.of("id", "starttime", "status");

    public record BatchPage(List<IngestionBatch> items, long page, int size, long total,
                            long totalPages, String sort) {
    }

    private final IngestionBatchMapper batchMapper;

    @Transactional(transactionManager = "metaTransactionManager", readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public BatchPage listPage(long page, int size, String sort) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID,
                    "page 必须大于等于 1，size 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        String[] parts = sort == null ? new String[0] : sort.trim().toLowerCase(Locale.ROOT).split(",", -1);
        if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 格式应为 字段[,asc|desc]");
        }
        String field = parts[0];
        String direction = parts.length == 1 ? "asc" : parts[1];
        if (!SORT_FIELDS.contains(field) || !("asc".equals(direction) || "desc".equals(direction))) {
            throw new PlatformBizException(PlatformBizException.PARAM_INVALID, "sort 字段或方向不受支持");
        }

        LambdaQueryWrapper<IngestionBatch> query = new LambdaQueryWrapper<>();
        boolean ascending = "asc".equals(direction);
        switch (field) {
            case "starttime" -> query.orderBy(true, ascending, IngestionBatch::getStartTime);
            case "status" -> query.orderBy(true, ascending, IngestionBatch::getStatus);
            default -> query.orderBy(true, ascending, IngestionBatch::getId);
        }
        if (!"id".equals(field)) query.orderByDesc(IngestionBatch::getId);

        long total = batchMapper.selectCount(new LambdaQueryWrapper<>());
        long totalPages = PaginationMath.totalPages(total, size);
        long actualPage = Math.min(page, totalPages);
        long offset = Math.multiplyExact(actualPage - 1, (long) size);
        List<IngestionBatch> items = batchMapper.selectList(query.last("LIMIT " + size + " OFFSET " + offset));
        String sortField = "starttime".equals(field) ? "startTime" : field;
        return new BatchPage(items, actualPage, size, total, totalPages, sortField + "," + direction);
    }
}
