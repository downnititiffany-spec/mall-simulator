package com.graduation.analytics.controller;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.pipeline.PipelineService;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineControllerPaginationTest {

    private PipelineRunMapper mapper;
    private PipelineController controller;

    @BeforeEach
    void setUp() {
        mapper = mock(PipelineRunMapper.class);
        controller = new PipelineController(mock(PipelineService.class), mapper);
    }

    @Test
    void returnsPageMetadataAndClampsPagePastEnd() {
        when(mapper.selectCount(any())).thenReturn(41L);
        when(mapper.selectList(any())).thenReturn(List.of(new PipelineRun()));

        var response = controller.page(99, 10, "createdAt,asc");

        assertEquals("OK", response.code());
        assertEquals(5, response.data().page());
        assertEquals(10, response.data().size());
        assertEquals(41, response.data().total());
        assertEquals(5, response.data().totalPages());
        assertEquals("createdat,asc", response.data().sort());
        assertEquals(1, response.data().items().size());
    }

    @Test
    void acceptsEmptyResultAsOneEmptyPage() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(List.of());

        var response = controller.page(1, 20, "id,desc");

        assertEquals(1, response.data().page());
        assertEquals(1, response.data().totalPages());
        assertEquals(0, response.data().total());
        assertEquals(List.of(), response.data().items());
    }

    @Test
    void rejectsInvalidPageSizeAndUntrustedSortFields() {
        assertThrows(PlatformBizException.class, () -> controller.page(0, 20, "id,desc"));
        assertThrows(PlatformBizException.class, () -> controller.page(1, 101, "id,desc"));
        assertThrows(PlatformBizException.class, () -> controller.page(1, 20, "id desc;drop table pipeline_run"));
        assertThrows(PlatformBizException.class, () -> controller.page(1, 20, "unknown,asc"));
    }
}
