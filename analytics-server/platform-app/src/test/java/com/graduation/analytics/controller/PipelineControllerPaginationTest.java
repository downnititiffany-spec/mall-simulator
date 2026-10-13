package com.graduation.analytics.controller;

import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.pipeline.PipelineService;
import com.graduation.analytics.pipeline.entity.PipelineRun;
import com.graduation.analytics.pipeline.mapper.PipelineRunMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class PipelineControllerPaginationTest {

    private PipelineRunMapper mapper;
    private PipelineController controller;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PipelineRun.class);
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

    @Test
    void idAscendingSortDoesNotAppendConflictingDescendingIdOrder() {
        when(mapper.selectCount(any())).thenReturn(2L);
        when(mapper.selectList(any())).thenReturn(List.of());

        controller.page(1, 10, "id,asc");

        ArgumentCaptor<Wrapper<PipelineRun>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(query.capture());
        String sql = query.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("ORDER BY id ASC"), sql);
        org.junit.jupiter.api.Assertions.assertFalse(sql.contains("id DESC"), sql);
    }

    @Test
    void pageReadUsesRepeatableReadMetadataTransaction() throws Exception {
        Transactional tx = PipelineController.class
                .getMethod("page", long.class, int.class, String.class)
                .getAnnotation(Transactional.class);

        org.junit.jupiter.api.Assertions.assertNotNull(tx);
        assertEquals("metaTransactionManager", tx.transactionManager());
        assertEquals(Isolation.REPEATABLE_READ, tx.isolation());
        org.junit.jupiter.api.Assertions.assertTrue(tx.readOnly());
    }
}
