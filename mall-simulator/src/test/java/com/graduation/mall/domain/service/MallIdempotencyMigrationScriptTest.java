package com.graduation.mall.domain.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MallIdempotencyMigrationScriptTest {

    @Test
    void sharedLedgerBackfillsExistingOrderKeysFromV8() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V9__mall_write_idempotency_ledger.sql"));

        assertTrue(migration.contains("INSERT INTO mall_request_idempotency"));
        assertTrue(migration.contains("SELECT idempotency_key, 'createOrder', request_hash"));
        assertTrue(migration.contains("FROM mall_order"));
        assertTrue(migration.contains("WHERE idempotency_key IS NOT NULL"));
        assertTrue(migration.contains("CAST(order_id AS CHAR)"));
    }
}
