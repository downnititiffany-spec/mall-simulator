package com.graduation.analytics.ingestion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.graduation.analytics.common.PlatformBizException;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import com.graduation.analytics.ingestion.mapper.IngestionBatchMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IngestionBatchQueryServiceTest {

    private IngestionBatchMapper mapper;
    private IngestionBatchQueryService service;

    @BeforeAll
    static void initMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                IngestionBatch.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(IngestionBatchMapper.class);
        service = new IngestionBatchQueryService(mapper);
    }

    @Test
    void pagesByRequestedStatusWithStableIdTieBreaker() {
        when(mapper.selectCount(any())).thenReturn(21L);
        when(mapper.selectList(any())).thenReturn(List.of(new IngestionBatch()));

        var result = service.listPage(2, 10, "status,asc");

        assertEquals(2, result.page());
        assertEquals(10, result.size());
        assertEquals(21, result.total());
        assertEquals(3, result.totalPages());
        assertEquals("status,asc", result.sort());
        ArgumentCaptor<Wrapper<IngestionBatch>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment().trim();
        assertEquals("ORDER BY status ASC,id DESC LIMIT 10 OFFSET 10", sql);
    }

    @Test
    void normalizesOutOfRangePageAndEmptyResultToPageOne() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(List.of());

        var result = service.listPage(99, 10, "id,desc");

        assertEquals(1, result.page());
        assertEquals(0, result.total());
        assertEquals(1, result.totalPages());
        assertEquals(List.of(), result.items());
        ArgumentCaptor<Wrapper<IngestionBatch>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        assertEquals("ORDER BY id DESC LIMIT 10 OFFSET 0", captor.getValue().getSqlSegment().trim());
    }

    @Test
    void mapsStartTimeSortAndAddsStableIdOrder() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(List.of());

        var result = service.listPage(1, 10, "startTime,DESC");

        assertEquals("startTime,desc", result.sort());
        ArgumentCaptor<Wrapper<IngestionBatch>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        assertEquals("ORDER BY start_time DESC,id DESC LIMIT 10 OFFSET 0",
                captor.getValue().getSqlSegment().trim());
    }

    @Test
    void rejectsInvalidPageAndSortBeforeMapperAccess() {
        assertThrows(PlatformBizException.class, () -> service.listPage(0, 10, "id,asc"));
        assertThrows(PlatformBizException.class, () -> service.listPage(1, 101, "id,asc"));
        assertThrows(PlatformBizException.class, () -> service.listPage(1, 10, "id desc;drop table ingestion_batch"));
        verifyNoInteractions(mapper);
    }
}
