package com.graduation.analytics.decision;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.decision.entity.DecisionTask;
import com.graduation.analytics.decision.mapper.DecisionEvaluationMapper;
import com.graduation.analytics.decision.mapper.DecisionTaskMapper;
import com.graduation.analytics.metric.MetricStore;
import com.graduation.analytics.runtime.RuntimeProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionServicePaginationTest {

    private DecisionTaskMapper taskMapper;
    private DecisionService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(DecisionTaskMapper.class);
        service = new DecisionService(taskMapper, mock(DecisionEvaluationMapper.class), mock(MetricStore.class),
                mock(RuntimeProfileService.class), mock(OperationAuditService.class));
    }

    @Test
    void returnsMetadataAndClampsPastEndPage() {
        when(taskMapper.selectCount(any())).thenReturn(41L);
        when(taskMapper.selectList(any())).thenReturn(List.of(new DecisionTask()));

        var result = service.listPage(99, 10, "createdAt,asc");

        assertEquals(5, result.page());
        assertEquals(10, result.size());
        assertEquals(41, result.total());
        assertEquals(5, result.totalPages());
        assertEquals("createdat,asc", result.sort());
        assertEquals(1, result.items().size());
    }

    @Test
    void handlesEmptyListsAndRejectsInvalidPagingOrSort() {
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectList(any())).thenReturn(List.of());

        var empty = service.listPage(1, 20, "id,desc");
        assertEquals(1, empty.totalPages());
        assertEquals(0, empty.total());
        assertEquals(List.of(), empty.items());
        assertThrows(PlatformBizException.class, () -> service.listPage(0, 20, "id,desc"));
        assertThrows(PlatformBizException.class, () -> service.listPage(1, 101, "id,desc"));
        assertThrows(PlatformBizException.class, () -> service.listPage(1, 20, "id;drop,desc"));
    }
}
