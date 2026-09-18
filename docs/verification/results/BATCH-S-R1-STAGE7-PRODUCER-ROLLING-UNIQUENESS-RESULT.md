# BATCH-S-R1-STAGE7-PRODUCER-ROLLING-UNIQUENESS — Result

> Overall: **PASS**
> Exact tested SHA: `5f20c37784a4d28e3e452a9ea5aa75cd7ac3447e`
> RunId: `stage7q1_20260918_152245`
> Attempt: `attempt-20260918_195657_077`
> Harness exit: **0**

## 1. Exact runtime chain

The exact SHA was detached and both executable Spring Boot JARs were rebuilt before execution:

- `mall-simulator` package: BUILD SUCCESS;
- `synthetic-data-generator` package: BUILD SUCCESS.

Therefore this result actually exercised the `5f20c37` Outbox serialization fix instead of reusing a stale target JAR.

Real chain:

`generator :8092 → REFERENCE_MALL_HTTP → mall :8090 → order/pay/refund → Outbox → run-scoped rolling JSONL`

## 2. Producer result

- generation status = `SUCCESS`;
- success_count = **600**;
- failed_count = **0**;
- operation journal rows = **601**;
- real HTTP OK = **555**;
- createOrder = **244**;
- pay = **80**;
- cancel = **164**;
- refund = **16**.

Outbox:

- historical residual failed/pending baseline remains exactly one event: `evt-00000002`;
- pending before explicit final drain = 160;
- explicit published count = 159;
- pending after = 1;
- new failed event ids = **0**.

## 3. Rolling uniqueness gate

Completed file:

`target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/mall-landing/events/2026091819.jsonl`

Producer evidence:

- lineCount = **1011**;
- uniqueEventIdCount = **1011**;
- duplicateEventIdCount = **0**;
- duplicateEventIds = empty.

Independent controller re-read of the physical file:

- JSON lines = **1011**;
- malformed JSON = **0**;
- unique event_id = **1011**;
- business date = `2026-09-18`;
- source_system = `mock-mall`;
- SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`.

Event type counts:

- stock_changed = 33;
- user_registered = 50;
- stock_reserved = 244;
- order_created = 244;
- stock_released = 164;
- order_cancelled = 164;
- order_paid = 80;
- refund_created = 16;
- refund_completed = 16.

The sum is exactly **1011**, matching lineCount and uniqueEventIdCount.

## 4. Current-run correlation

- created orders = 244; missing order_created = 0;
- paid orders = 80; missing order_paid = 0;
- refunds = 16; missing refund_created = 0;
- refunds = 16; missing refund_completed = 0.

## 5. Verdict

S-R1 proves the previously observed same-JVM scheduler/manual-publish duplication has been removed for this real producer run:

- original Batch S: 1182 lines / 1011 unique / 171 duplicate event IDs;
- S-R1 after fix: 1011 lines / 1011 unique / 0 duplicate event IDs.

This closes the rolling-handoff defect discovered after Batch S.

The next gate may now consume this exact completed file through the LocalFile/HTTP ingestion → Spark pipeline path.

The result still does not claim end-to-end exactly-once semantics across a process crash in the write→markPublished window; that remains an intentional at-least-once boundary.

