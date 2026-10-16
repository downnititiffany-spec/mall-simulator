# Mall cart idempotency empty-result replay regression

Date: 2026-10-09

## Change

Added a focused `MallBusinessServiceIdempotencyTest` case for replaying an `addCartItem` request whose ledger result is the valid empty string used by void write operations. The test verifies operation and request hash matching, accepts the stored empty result, and checks that the replay does not issue cart insert/update effects or rewrite the ledger result.

No production behavior, schema, migration, or test isolation configuration changed.

## Verification

- `mvn -o -f mall-simulator/pom.xml test -Dtest=MallBusinessServiceIdempotencyTest,MallIdempotencyMigrationScriptTest`: 9 tests, 0 failures/errors/skips; BUILD SUCCESS.
- `mvn -o -f mall-simulator/pom.xml test`: 23 tests, 0 failures/errors/skips; BUILD SUCCESS.
- Both commands used the default offline suite. The `@Tag("it")` MySQL tests, including concurrent same-key requests, were not executed.

## Limits

This is Mockito/offline behavior evidence only. V9 has not been applied to a database in this run; MySQL transaction/concurrency, HTTP behavior against the live mall, and target-side write verification remain unverified. This does not establish exactly-once delivery. No database was contacted and MySQL 3306 was not accessed.
