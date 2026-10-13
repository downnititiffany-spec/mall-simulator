-- The unique key serializes retries for every first-party MALL_API write operation.
-- A result row commits in the same transaction as the associated business changes and outbox events.
CREATE TABLE mall_request_idempotency (
    idempotency_key VARCHAR(128) NOT NULL,
    operation       VARCHAR(64)  NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    result_value    VARCHAR(128) NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (idempotency_key),
    KEY idx_mall_request_idempotency_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城写请求幂等结果账本';

-- Preserve keys created by V8 before the shared ledger existed. This also makes
-- order keys conflict with every other first-party write operation after upgrade.
INSERT INTO mall_request_idempotency
    (idempotency_key, operation, request_hash, result_value, created_at)
SELECT idempotency_key, 'createOrder', request_hash, CAST(order_id AS CHAR), created_at
FROM mall_order
WHERE idempotency_key IS NOT NULL;
