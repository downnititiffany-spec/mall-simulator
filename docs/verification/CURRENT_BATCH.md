# Current Verification Batch

> 状态：READY
> Batch S 在其原声明范围内 PASS；下游 handoff 审计发现 rolling JSONL 有 171 个重复 event_id。当前唯一可执行批次是 S-R1。

## Current batch

- **Batch ID**：`BATCH-S-R1-STAGE7-PRODUCER-ROLLING-UNIQUENESS`
- **Exact source/test baseline**：`5f20c37784a4d28e3e452a9ea5aa75cd7ac3447e`
- **Branch context**：`feature/v3-development`
- **RunId**：`stage7q1_20260918_152245`
- **Permanent plan**：`docs/verification/batches/BATCH-S-R1-STAGE7-PRODUCER-ROLLING-UNIQUENESS-PLAN.md`
- **Batch S result**：`docs/verification/results/BATCH-S-STAGE7-PRODUCER-MALL-OUTBOX-RESULT.md`
- **Overall**：`READY`

## Accepted Batch S facts

Exact SHA `b850493` / attempt `attempt-20260918_193258_719`:

- harness exit 0;
- generation SUCCESS 600 / failed 0;
- real HTTP: createOrder 244 / pay 80 / cancel 164 / refund 16;
- operation journal 601 rows / 555 successful real HTTP rows;
- no current-run new Outbox failed IDs;
- pending after drain returned to the one historical baseline failure;
- current-run order/pay/refund correlation missing = 0.

## Newly discovered handoff defect

The completed rolling file had:

- 1182 JSON lines;
- 0 malformed JSON;
- only 1011 unique event_id;
- 171 duplicate event_id groups.

1011 is exactly the current-run business event total, so the extra 171 lines are duplicate delivery.

Root cause: scheduler and manual publish could concurrently execute `OutboxPublisher.publishOnce()` and both write the same pending event before either caller marked it published.

Corrective SHA `5f20c37`:

- serializes `publishOnce()` inside the singleton JVM;
- deterministic concurrency test RED→GREEN;
- producer harness now fails on any duplicate event_id and persists uniqueness evidence;
- default fresh baseline = analytics 1034 / mall 14 / generator 111, only historical manifest patrol red.

## S-R1 PASS

- all original Batch S producer criteria remain PASS;
- `duplicateEventIdCount = 0`;
- `uniqueEventIdCount = lineCount`;
- no empty event_id;
- harness exit 0 / outcome PASS.

Only after S-R1 may Batch T consume that exact completed file via LocalFile/HTTP ingestion → Spark pipeline.

