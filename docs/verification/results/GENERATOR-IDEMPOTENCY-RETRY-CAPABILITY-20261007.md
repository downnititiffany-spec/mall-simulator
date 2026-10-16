# Generator write retry capability guard — 2026-10-07

## Change

`SafeHttpRetry` previously treated any nonblank `Idempotency-Key` header as sufficient reason to retry a POST after a transient status or network failure. A request header alone does not prove the target mall implements deduplication.

The retry API now defaults to safe read methods only (`GET`, `HEAD`, `OPTIONS`). A caller must explicitly declare that the target honors the idempotency-key protocol before a POST is retryable; the stable nonblank key is still required. `ReferenceMallHttpAdapter` opts in because its paired first-party mall implements the request ledger. Other adapters keep the safe default unless their contract is verified.

## Verification

- `mvn -o -f synthetic-data-generator/pom.xml -Dtest=SafeHttpRetryTest test`: **4/4 PASS**, 0 failures/errors/skips.
- `mvn -o -f synthetic-data-generator/pom.xml test`: **131/131 PASS**, 0 failures/errors/skips, `BUILD SUCCESS`.
- Tests prove that a key header alone does not trigger POST replay, explicit protocol support plus stable key permits bounded replay, GET retry still works, and ordinary writes remain single-attempt.

## Evidence boundary

Tests use a local HTTP test server and the generator's default suite. No external mall or database was accessed, and no business writes were sent. This does not establish that an arbitrary `base_url` configured with the reference adapter honors the protocol, nor does it establish end-to-end exactly-once delivery. The Stage 2/7 live mall and transaction verification remains open. Frozen V3.0 guidance/design were not changed.
