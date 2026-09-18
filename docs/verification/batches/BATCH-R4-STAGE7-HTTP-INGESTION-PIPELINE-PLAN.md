# BATCH-R4-STAGE7-HTTP-INGESTION-PIPELINE — Verification Plan

> 状态：READY
> Exact source/test SHA：`4ef4d93339638e32ff2cb2547f8551be6da42305`
> Branch：`feature/v3-development`
> Predecessor：`BATCH-R3-STAGE7-HTTP-INGESTION-PIPELINE` / FAIL_TEST_HARNESS_TIMEOUT

## 1. Purpose

Repeat the Stage 7 positive real chain with a bounded but realistic whole-Pipeline timeout and owned-process-tree cleanup:

`HTTP login → runtime-profile test/activate → 50-row positive ingestion → Spark warehouse chain → quality gate → metric publish`

## 2. Input

`tests/golden-dataset/events/golden-20260901-positive.jsonl`

The original 55-line negative fixture remains unchanged and is not used by R4.

## 3. Harness correction under test

- default `PipelineTimeoutSec = 600`;
- a deadline reached while Pipeline is RUNNING becomes `PIPELINE_TIMEOUT` evidence;
- harness exit cleanup stops only descendants of the exact platform PID started by this invocation;
- no broad Java process sweep.

## 4. Safety boundary

- no writes/fallback to 3306;
- only Q-R1 run-scoped 3307 analytics databases;
- secrets only from current PowerShell process environment;
- attempt-scoped landing/metastore/warehouse/staging/idempotency;
- 8091 must be free;
- no quality threshold/severity relaxation;
- no Fake executor / skipped Spark stages.

## 5. Exact execution

~~~powershell
cd "D:\Develop_code\GraduationProject-wt\v3-dev"

$RunId = "stage7q1_20260918_152245"

git switch --detach 4ef4d93339638e32ff2cb2547f8551be6da42305

pwsh -NoProfile -File .\scripts\stage7-http-isolated.ps1 -RunId $RunId -Confirm

$BatchR4Exit = $LASTEXITCODE
git switch feature/v3-development
"BATCH_R4_EXIT=$BatchR4Exit"
~~~

## 6. PASS criteria

- ingestion = 50 accepted / 0 quarantine / noNewData=false;
- fresh Pipeline run for the R4 attempt;
- INIT_SCHEMA / LOAD_ODS / BUILD_DWD / BUILD_DWS / BUILD_ADS SUCCESS;
- QUALITY_CHECK PASS;
- PUBLISH_METRIC SUCCESS;
- Pipeline final status SUCCESS;
- harness exit 0;
- evidence outcome PASS;
- no 3306 fallback;
- no residual process from this attempt after harness exits.

If 600s is exceeded while Pipeline is still non-terminal, classify from evidence; do not silently extend or call it production failure.

