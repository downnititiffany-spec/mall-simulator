# BATCH-R2-STAGE7-HTTP-INGESTION-PIPELINE — Verification Plan

> 状态：READY
> Exact source/test SHA：`37dae94c8acc124cfb6aecb86f18241d3b6a21d0`
> Branch：`feature/v3-development`
> Predecessor：`BATCH-R1-STAGE7-HTTP-INGESTION-PIPELINE` / FAIL_TEST_HARNESS

## 1. Purpose

Run the same minimal Stage 7 real chain again after making Pipeline identity attempt-scoped:

`HTTP login → runtime-profile test/activate → fresh ingestion → fresh pipeline`

R2 must create a **new** Pipeline run and therefore finally exercise the corrected attempt-scoped Derby/Spark path rather than reusing Batch R's failed run.

## 2. Corrections under test

In addition to R1's attempt-scoped directories/input:

- `Idempotency-Key = stage7-<RunId>-<attemptId>`;
- `sourceDataVersion = stage7-<RunId>-<attemptId>`.

The returned pipeline run must not be the historical `runId=1` from Batch R.

## 3. Safety boundary

Unchanged:

- no writes/fallback to 3306;
- only Q-R1 run-scoped analytics meta/metric databases on 3307;
- passwords only from current process environment;
- no secret CLI parameters or echo;
- 8091 must be free;
- only harness-owned Java PID may be stopped;
- no Fake executor or skipped Spark check.

## 4. Exact execution

Run in the same PowerShell process that still contains the analytics secrets:

~~~powershell
cd "D:\Develop_code\GraduationProject-wt\v3-dev"

$RunId = "stage7q1_20260918_152245"

git switch --detach 37dae94c8acc124cfb6aecb86f18241d3b6a21d0

pwsh -NoProfile -File .\scripts\stage7-http-isolated.ps1 -RunId $RunId -Confirm

$BatchR2Exit = $LASTEXITCODE
git switch feature/v3-development
"BATCH_R2_EXIT=$BatchR2Exit"
~~~

## 5. PASS criteria

- runtime-profile applicable checks PASS;
- fresh ingestion has `recordCount>0` and `noNewData=false`;
- returned Pipeline run is newly created for this attempt;
- Pipeline reaches `SUCCESS`;
- harness exits 0;
- latest evidence reports `outcome=PASS`;
- no 3306 fallback.

## 6. Failure classification

- another harness identity/orchestration defect → FAIL_TEST_HARNESS;
- production/API/contract defect → FAIL;
- correctly configured code reaches an unavailable local external dependency → BLOCKED_ENV.

Batch R and R1 remain permanent failure records.

