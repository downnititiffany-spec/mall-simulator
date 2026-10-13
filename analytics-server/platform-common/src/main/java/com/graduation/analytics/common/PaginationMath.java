package com.graduation.analytics.common;

/** Shared overflow-safe arithmetic for one-based offset pagination. */
public final class PaginationMath {

    private PaginationMath() {
    }

    /**
     * Returns at least one page, preserving the API's empty-result convention.
     * Uses subtraction before division so a very large row count cannot overflow
     * while rounding up.
     */
    public static long totalPages(long total, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("page size must be positive");
        }
        return total <= 0 ? 1 : 1 + (total - 1) / size;
    }
}
