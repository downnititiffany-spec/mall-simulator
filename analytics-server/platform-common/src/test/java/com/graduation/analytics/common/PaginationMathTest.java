package com.graduation.analytics.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaginationMathTest {

    @Test
    void roundsUpWithoutOverflowAndKeepsOnePageForEmptyResults() {
        assertEquals(1, PaginationMath.totalPages(0, 20));
        assertEquals(1, PaginationMath.totalPages(1, 20));
        assertEquals(2, PaginationMath.totalPages(21, 20));
        assertEquals(1 + (Long.MAX_VALUE - 1) / 1_000,
                PaginationMath.totalPages(Long.MAX_VALUE, 1_000));
        assertEquals(1 + (Long.MAX_VALUE - 1) / 100,
                PaginationMath.totalPages(Long.MAX_VALUE, 100));
    }

    @Test
    void rejectsNonPositivePageSize() {
        assertThrows(IllegalArgumentException.class, () -> PaginationMath.totalPages(0, 0));
    }
}
