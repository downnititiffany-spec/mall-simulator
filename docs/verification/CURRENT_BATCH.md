# Current Verification Batch

> 状态：READY
> Batch R / R1 / R2 均保留历史结果；当前唯一可执行批次是 R3。

## Current batch

- **Batch ID**：`BATCH-R3-STAGE7-HTTP-INGESTION-PIPELINE`
- **Exact source/test baseline**：`2765701c13b607cc1426024d7d4c948c4b99c365`
- **Branch context**：`feature/v3-development`
- **Predecessor**：`BATCH-R2-STAGE7-HTTP-INGESTION-PIPELINE` / FAIL_TEST_FIXTURE
- **Permanent plan**：`docs/verification/batches/BATCH-R3-STAGE7-HTTP-INGESTION-PIPELINE-PLAN.md`
- **Historical R result**：`docs/verification/results/BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **Historical R1 result**：`docs/verification/results/BATCH-R1-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **Historical R2 result**：`docs/verification/results/BATCH-R2-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **RunId**：`stage7q1_20260918_152245`
- **Overall**：`READY`

## Why R3 exists

R2 proved the fresh attempt-scoped Spark chain through ADS:

- runtime profile PASS;
- ingestion fresh;
- INIT_SCHEMA / LOAD_ODS / BUILD_DWD / BUILD_DWS / BUILD_ADS all SUCCESS;
- QUALITY_CHECK blocked only `EVENT_ID_UNIQUE` with one duplicate.

The original 55-line golden intentionally contains that duplicate as a negative DWD/quality fixture. V3.0 `12.4 requires the historical 0.0005 threshold to remain unchanged and requires positive/negative fixtures to be separated.

Corrective SHA `2765701` therefore introduces a separate 50-line positive fixture instead of weakening the quality gate.

## Scope

`8091 platform → login → runtime profile test/activate → fresh positive ingestion → fresh pipeline → quality → publish`

Default input is:

`tests/golden-dataset/events/golden-20260901-positive.jsonl`

The original 55-line golden remains unchanged for negative verification.

## Safety boundary

- no 3306 writes/fallback;
- only Q-R1 run-scoped 3307 analytics databases;
- analytics secrets only from current process environment;
- fresh attempt root / fresh Pipeline idempotency key;
- 8091 must be free;
- only harness-owned Java PID may be stopped;
- no quality threshold/severity relaxation.

## PASS

- ingestion = 50 accepted, 0 quarantine, noNewData=false;
- all Spark stages through BUILD_ADS SUCCESS;
- QUALITY_CHECK PASS;
- PUBLISH_METRIC SUCCESS;
- Pipeline SUCCESS;
- harness exit 0;
- evidence outcome=PASS;
- no 3306 fallback.

