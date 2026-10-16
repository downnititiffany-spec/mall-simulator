# BATCH-R3-STAGE7-HTTP-INGESTION-PIPELINE — Verification Plan

> 状态：READY
> Exact source/test SHA：`2765701c13b607cc1426024d7d4c948c4b99c365`
> Branch：`feature/v3-development`
> Predecessor：`BATCH-R2-STAGE7-HTTP-INGESTION-PIPELINE` / FAIL_TEST_FIXTURE

## 1. Purpose

Run the same minimal Stage 7 real chain with an explicit **positive** fixture:

`HTTP login → runtime-profile test/activate → fresh ingestion → fresh pipeline → quality gate → publish`

No production quality threshold, severity, Spark SQL, or Pipeline state machine behavior is relaxed.

## 2. Input

Default input:

`tests/golden-dataset/events/golden-20260901-positive.jsonl`

It contains exactly 50 valid/unique business events and is guarded as the original 55-line golden minus only the five registered negative samples.

The original 55-line golden remains available for negative quality testing and must not be modified to make R3 green.

## 3. Safety boundary

- no writes/fallback to 3306;
- only Q-R1 run-scoped 3307 analytics meta/metric databases;
- analytics secrets only from current process environment;
- no password CLI args or echo;
- each invocation gets a fresh attempt root and fresh Pipeline idempotency key;
- 8091 must be free;
- only harness-owned platform PID may be stopped;
- no Fake executor, no skipped Spark/quality gate, no threshold widening.

## 4. Exact execution

Run in the same PowerShell process that still owns the analytics secrets:

~~~powershell
cd "D:\Develop_code\GraduationProject-wt\v3-dev"

$RunId = "stage7q1_20260918_152245"

git switch --detach 2765701c13b607cc1426024d7d4c948c4b99c365

pwsh -NoProfile -File .\scripts\stage7-http-isolated.ps1 -RunId $RunId -Confirm

$BatchR3Exit = $LASTEXITCODE
git switch feature/v3-development
"BATCH_R3_EXIT=$BatchR3Exit"
~~~

## 5. PASS criteria

- runtime-profile applicable checks PASS;
- fresh ingestion has `recordCount=50`, `quarantineCount=0`, `noNewData=false`;
- Pipeline run is fresh for the R3 attempt;
- INIT_SCHEMA / LOAD_ODS / BUILD_DWD / BUILD_DWS / BUILD_ADS succeed;
- QUALITY_CHECK passes all blocking rules;
- PUBLISH_METRIC succeeds;
- final Pipeline status = `SUCCESS`;
- harness exit 0;
- latest evidence `outcome=PASS`;
- no 3306 fallback.

## 6. Failure classification

- another verification harness/fixture defect → FAIL_TEST_HARNESS / FAIL_TEST_FIXTURE;
- production/API/contract defect → FAIL;
- correctly configured code reaches unavailable external local dependency → BLOCKED_ENV.

R/R1/R2 remain permanent evidence and are never rewritten.

