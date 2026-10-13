package com.graduation.analytics.controller;

import com.graduation.analytics.ingestion.IngestionBatchQueryService;
import com.graduation.analytics.ingestion.IngestionService;
import com.graduation.analytics.ingestion.entity.IngestionBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionControllerPaginationTest {

    private IngestionBatchQueryService batchQueryService;
    private IngestionController controller;

    @BeforeEach
    void setUp() {
        batchQueryService = mock(IngestionBatchQueryService.class);
        controller = new IngestionController(mock(IngestionService.class), batchQueryService);
    }

    @Test
    void batchPageDelegatesQueryAndReturnsItsPageContract() {
        var expected = new IngestionBatchQueryService.BatchPage(
                List.of(new IngestionBatch()), 2, 10, 21, 3, "status,asc");
        when(batchQueryService.listPage(2, 10, "status,asc")).thenReturn(expected);

        var response = controller.batchPage(2, 10, "status,asc");

        assertEquals(expected, response.data());
        verify(batchQueryService).listPage(2, 10, "status,asc");
    }
}
