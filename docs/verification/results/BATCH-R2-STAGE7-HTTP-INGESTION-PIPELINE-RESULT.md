# BATCH-R2-STAGE7-HTTP-INGESTION-PIPELINE — Controller Review

> Overall: **FAIL_TEST_FIXTURE**
> Exact tested SHA: `37dae94c8acc124cfb6aecb86f18241d3b6a21d0`
> RunId: `stage7q1_20260918_152245`
> Attempt: `attempt-20260918_170850_665`

## 1. What R2 proved

R2 finally created a fresh Pipeline run:

- pipeline runId = `2`;
- idempotency key is attempt-scoped;
- runtime-profile applicable checks PASS;
- ingestion batch 4 consumed fresh input with `noNewData=false`;
- ingestion = **51 accepted + 4 quarantined = 55 total**;
- `WAIT_LANDING` SUCCESS;
- `INIT_SCHEMA` SUCCESS;
- `LOAD_ODS` SUCCESS;
- `BUILD_DWD` SUCCESS;
- `BUILD_DWS` SUCCESS;
- `BUILD_ADS` SUCCESS.

The attempt-scoped Derby/Spark path therefore works. R2 is the first Batch R family run that truly crossed the old Derby failure and executed the full Spark warehouse chain through ADS staging.

## 2. Exact quality failure

`QUALITY_CHECK` failed with:

- errorCode = `PIPELINE_QUALITY_FAILED`;
- AMOUNT_RECONCILE: PASS;
- REQUIRED_FIELD_NULL_RATE: PASS;
- ENUM_WHITELIST: PASS;
- EVENT_ID_UNIQUE: **FAIL**;
- EVENT_ID_UNIQUE checkCount = 49;
- EVENT_ID_UNIQUE errorCount = 1.

The original 55-line golden fixture intentionally contains a second `golden-evt-008`
(`golden-trace-037`). Its own `GoldenDatasetTest` explicitly asserts that duplicate must remain so DWD deterministic dedup/reject behavior can be tested.

## 3. Why this is not a production quality bug

V3.0 design `12.4 states:

- EVENT_ID_UNIQUE historical dupRateMax = 0.0005;
- the threshold must not be enlarged merely to make a golden test pass;
- over-threshold duplicate rate must block;
- positive and negative test fixtures must be separated.

R2 supplied a deliberately negative duplicate fixture to a batch whose PASS criterion required full Pipeline SUCCESS. The platform correctly blocked it.

Therefore overall classification is **FAIL_TEST_FIXTURE**, not production FAIL and not BLOCKED_ENV.

No quality threshold or severity is changed.

## 4. Fixture correction

Commit `2765701c13b607cc1426024d7d4c948c4b99c365` adds:

- original `golden-20260901.jsonl` unchanged as the negative/dirty golden fixture;
- new `golden-20260901-positive.jsonl` with exactly 50 lines;
- the positive fixture is exactly the original 55 lines minus only five registered negative samples:
  1. second duplicate `golden-trace-037`;
  2. non-whitelist purchase `golden-trace-038`;
  3. malformed JSON line;
  4. schema-v2 `golden-trace-054`;
  5. missing-event-id `golden-trace-055`.

`GoldenDatasetTest` guards that:

- positive fixture remains exactly 50 rows;
- all rows parse;
- schema_version is 1.0;
- event_id is present and unique;
- behavior_type is in the DWD whitelist;
- no normal business event may be removed to obtain a green run.

The Stage 7 positive harness now defaults to the positive fixture while still allowing explicit `-GoldenDataset` override for negative tests.

## 5. Validation before R3

- `GoldenDatasetTest`: **6/6 PASS**;
- `AnalyticsIsolationScriptsContractTest`: **7/7 PASS**;
- Stage 7 DryRun: exit 0 / zero-I/O;
- no default test-count change;
- no production code change;
- no 3306 fallback.

R2 remains permanent negative evidence that the duplicate quality gate is active.

