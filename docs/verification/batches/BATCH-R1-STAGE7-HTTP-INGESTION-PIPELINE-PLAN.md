# BATCH-R1-STAGE7-HTTP-INGESTION-PIPELINE — Verification Plan

> 状态：READY
> Exact source/test SHA：`cf807865ac3656cd51d3dd03b0f17a2509362168`
> Branch：`feature/v3-development`
> Predecessor：`BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE` / FAIL_TEST_HARNESS

## 1. Purpose

Retest the exact same Stage 7 minimal real chain after correcting the Batch R harness defects:

`HTTP login → runtime-profile test/activate → HTTP ingestion → HTTP pipeline`

No production business semantics were relaxed.

## 2. Corrections under test

R1 must prove:

1. each harness invocation gets a fresh `attempt-<timestamp>` root;
2. the golden input is copied under that attempt, so prior file checkpoints cannot turn the retry into false `noNewData`;
3. Derby `metastoreDir` is not pre-created; embedded Derby must create it itself via `create=true`;
4. per-attempt evidence is retained and the stable latest evidence pointer is updated.

## 3. Safety boundary

Same as Batch R:

- no writes to 3306;
- meta/metric JDBC only to Q-R1 run-scoped 3307 databases;
- analytics passwords only from current process environment;
- no Password CLI parameters and no secret echo;
- 8091 must be free before startup;
- only the Java process started by the harness may be stopped;
- no Fake executor and no skipping the real runtime-profile Spark check.

## 4. Exact execution

Run from the same PowerShell process that still owns:

- `V25_IT_META_PASSWORD`
- `V25_IT_METRIC_PUBLISH_PASSWORD`

~~~powershell
cd "D:\Develop_code\GraduationProject-wt\v3-dev"

$RunId = "stage7q1_20260918_152245"

git switch --detach cf807865ac3656cd51d3dd03b0f17a2509362168

pwsh -NoProfile -File .\scripts\stage7-http-isolated.ps1 -RunId $RunId -Confirm

$BatchR1Exit = $LASTEXITCODE
git switch feature/v3-development
"BATCH_R1_EXIT=$BatchR1Exit"
~~~

## 5. PASS criteria

PASS only if:

- runtime-profile applicable checks all pass;
- ingestion consumes the fresh attempt input with `recordCount > 0` and `noNewData=false`;
- pipeline reaches `SUCCESS`;
- harness exits 0;
- latest evidence reports `outcome=PASS`;
- no 3306 fallback occurs.

## 6. Failure classification

- harness/test logic defect → FAIL_TEST_HARNESS;
- production/API/contract defect → FAIL;
- external local Spark/Hadoop runtime dependency prevents correct code from running → BLOCKED_ENV.

Do not rewrite Batch R. R1 is the only place a corrected run can become green.

