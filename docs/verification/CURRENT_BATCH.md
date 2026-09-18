# Current Verification Batch

> 状态：READY
> Batch S-R1 已在 exact SHA 上真实 PASS，rolling handoff 已证明无重复 event_id。当前唯一可执行批次是 Batch T。

## Current batch

- **Batch ID**：`BATCH-T-STAGE7-PRODUCER-LOCALFILE-ANALYTICS`
- **Exact source/test baseline**：`cbc41919df79bba20e1f91fe1724061ae151d12e`
- **Branch context**：`feature/v3-development`
- **RunId**：`stage7q1_20260918_152245`
- **Permanent plan**：`docs/verification/batches/BATCH-T-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-PLAN.md`
- **S-R1 result**：`docs/verification/results/BATCH-S-R1-STAGE7-PRODUCER-ROLLING-UNIQUENESS-RESULT.md`
- **Overall**：`READY`

## Accepted S-R1 facts

Exact SHA `5f20c37784a4d28e3e452a9ea5aa75cd7ac3447e` / attempt `attempt-20260918_195657_077`:

- exact mall/generator executable JARs rebuilt before run;
- harness exit 0 / outcome PASS;
- generation SUCCESS 600 / failed 0;
- createOrder 244 / pay 80 / cancel 164 / refund 16;
- operation journal 601 / real HTTP OK 555;
- no new Outbox failed IDs;
- pending after final drain equals the one historical residual baseline;
- order/pay/refund current-run correlation missing = 0.

Rolling:

- lineCount = **1011**;
- uniqueEventIdCount = **1011**;
- duplicateEventIdCount = **0**;
- duplicateEventIds = empty;
- malformed JSON = 0;
- business date = `2026-09-18`;
- source_system = `mock-mall`;
- SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`.

This closes the 171-row duplicate-delivery defect discovered after Batch S.

## Batch T objective

Consume that exact clean completed rolling file through:

`producer rolling → LocalFile/HTTP ingestion → Landing → Spark ODS/DWD/DWS/ADS → quality → metric publish`

Batch T must prove:

- the producer file is re-read and still clean;
- business date is derived from real event_time, not the old golden-fixture date;
- ingestion recordCount = 1011;
- quarantineCount = 0;
- noNewData = false;
- Pipeline status = SUCCESS;
- harness exit 0 / outcome PASS.

Flume/HDFS remains out of scope and will be a later dedicated batch.

