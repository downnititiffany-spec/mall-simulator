package com.graduation.analytics.runtime;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class HiveJdbcDriverAvailabilityTest {

    @Test
    void hiveServer2DriverIsAvailableForRuntimeProfileConnectivityProbe() {
        assertDoesNotThrow(() -> DriverManager.getDriver("jdbc:hive2://127.0.0.1:10015/default"));
    }
}
