# BATCH-S-STAGE7-PRODUCER-MALL-OUTBOX — Controller Review

> Overall: **PASS_DECLARED_SCOPE / DOWNSTREAM_HANDOFF_DEFECT_DISCOVERED**
> Exact tested SHA: `b850493b11fb71b17db38a59b1d4196d519168a3`
> RunId: `stage7q1_20260918_152245`
> Attempt: `attempt-20260918_193258_719`
> Harness exit: **0**

## 1. Declared Batch S result

The producer-side real chain passed its declared criteria:

`synthetic-data-generator :8092 → REFERENCE_MALL_HTTP → mall-simulator :8090 → order/pay/refund → Outbox → rolling JSONL`

Observed:

- generation run: `SUCCESS`;
- success_count = **600**;
- failed_count = **0**;
- real HTTP journal rows = **555**;
- operation-journal rows = **601**;
- real operations:
  - listProducts = 1;
  - createSyntheticUser = 50;
  - createOrder = **244**;
  - pay = **80**;
  - cancel = **164**;
  - refund = **16**.

Outbox:

- historical unpublishable baseline = 1 event (`evt-00000002`);
- pending before final explicit drain = 174;
- explicitly published = 171;
- pending after = 1, equal to the historical baseline;
- new failed event ids = **0**.

Current-run correlation:

- created orders = 244, missing in order_created = 0;
- paid orders = 80, missing in order_paid = 0;
- refunds = 16, missing in refund_created = 0;
- refunds = 16, missing in refund_completed = 0.

Therefore Batch S remains a genuine PASS at the boundary it declared.

## 2. Downstream handoff defect discovered after PASS

Before feeding the completed rolling file into analytics, the controller performed an additional handoff audit not present in the original Batch S PASS criteria.

Rolling file:

`target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_193258_719/mall-landing/events/2026091819.jsonl`

Audit:

- JSON lines = **1182**;
- malformed JSON = **0**;
- unique event_id = **1011**;
- duplicate event_id groups = **171**;
- source_system = `mock-mall` for all lines.

The **1011 unique events exactly equal the business events produced by this run**:

- stock_changed 33;
- user_registered 50;
- stock_reserved 244;
- order_created 244;
- stock_released 164;
- order_cancelled 164;
- order_paid 80;
- refund_created 16;
- refund_completed 16.

The extra 171 lines are therefore duplicate delivery, not additional business facts.

## 3. Root cause

`OutboxPublisher.publishOnce()` used:

1. select rows where published_at is null;
2. write event to rolling JSONL;
3. mark published_at.

The scheduler and manual `POST /api/v1/mall/outbox/publish` could enter `publishOnce()` concurrently.
Both callers could select the same row before either caller performed step 3. The database conditional update is idempotent, but it happens **after the file write**, so it cannot prevent the second write.

A deterministic concurrency regression test was added. On the old implementation it failed RED because the second publish completed while the first thread was blocked between write and markPublished.

## 4. Corrective commit

`5f20c37784a4d28e3e452a9ea5aa75cd7ac3447e`:

- serializes `OutboxPublisher.publishOnce()` within the Spring singleton JVM;
- adds `OutboxPublisherConcurrencyTest`;
- producer harness now records `uniqueEventIdCount` / `duplicateEventIdCount` / duplicate IDs;
- any duplicate event_id now makes the producer harness fail closed;
- default mall baseline is updated 13 → 14.

Validation:

- concurrency RED on old implementation: confirmed;
- concurrency GREEN after fix: 1/1 PASS;
- generator harness contract: PASS;
- producer DryRun: exit 0 / zero-I/O;
- default fresh:
  - analytics **1034 MATCH**;
  - mall **14 MATCH**;
  - generator **111 MATCH**;
  - only the historical manifest patrol remains red.

## 5. Boundary

This fix prevents avoidable same-JVM scheduler/manual-publish overlap.
It does **not** claim exactly-once delivery across process crashes in the write→markPublished window.
That at-least-once boundary remains intentional and downstream quality/dedup logic must still detect abnormal repetition.

The corrected rolling handoff must be proven in a separate S-R1 run before opening the real LocalFile → analytics Batch T.

