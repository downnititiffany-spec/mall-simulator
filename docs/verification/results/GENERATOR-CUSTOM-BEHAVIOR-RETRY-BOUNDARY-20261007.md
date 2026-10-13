# Configurable behavior route retry boundary — 2026-10-07

## Finding and change

`ReferenceMallHttpAdapter` allows `behavior_path` to be supplied by deployment configuration. Such a path can point to a custom endpoint whose implementation is outside the reference mall's built-in request ledger. Treating every write from this adapter as idempotent would therefore make a transient POST failure eligible for duplicate replay without protocol evidence.

The adapter now declares idempotency support per call site. Built-in first-party user/order/pay/cancel/refund routes opt into bounded POST retry with their stable key. The configurable behavior route still sends its key but does not automatically replay the POST. The generic retry helper also remains opt-in for POST.

## Verification

- Targeted: `mvn -o -f synthetic-data-generator/pom.xml -Dtest=SafeHttpRetryTest,ReferenceMallHttpAdapterTest test` — **15/15 PASS**, 0 failures/errors/skips.
- Generator default suite: `mvn -o -f synthetic-data-generator/pom.xml test` — **132/132 PASS**, 0 failures/errors/skips.
- Reference mall default suite: `mvn -o -f mall-simulator/pom.xml test` — **18/18 PASS**, 0 failures/errors/skips.
- New localhost HTTP test returns 503 from a configured behavior route and confirms exactly one request, with the supplied idempotency key present.

## Evidence boundary

These are local test-server, mock/unit, and default module-suite results. They do not prove a deployed custom behavior endpoint's idempotency semantics or external mall/database behavior. The first-party service has transaction-scoped key/result records, but no real HTTP/database retry or end-to-end generator transaction run was performed here. This is not an exactly-once claim; Stage 2/7 live verification remains open. Frozen V3.0 guidance/design were not changed.
