-- Order creation retries reuse one key. NULL preserves legacy/manual orders.
ALTER TABLE mall_order
    ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER cancel_reason,
    ADD COLUMN request_hash CHAR(64) NULL AFTER idempotency_key,
    ADD UNIQUE KEY uk_mall_order_idempotency_key (idempotency_key);
