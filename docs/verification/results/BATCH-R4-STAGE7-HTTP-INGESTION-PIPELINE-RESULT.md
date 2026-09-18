# BATCH-R4-STAGE7-HTTP-INGESTION-PIPELINE — Controller Review

> Overall: **PASS_BUSINESS_CHAIN / FAIL_TEST_HARNESS_CLEANUP**
> Exact tested business-chain SHA: `4ef4d93339638e32ff2cb2547f8551be6da42305`
> RunId: `stage7q1_20260918_152245`
> Attempt: `attempt-20260918_173825_934`

## 1. Business-chain result

The Stage 7 positive real chain completed successfully before the harness cleanup failure.

Runtime evidence:

- runtime-profile applicable checks: PASS;
- ingestion:
  - recordCount = **50**;
  - quarantineCount = **0**;
  - noNewData = **false**;
- Pipeline runId = `4`;
- Pipeline status = **SUCCESS**;
- evidence outcome = **PASS**.

All Pipeline stages reached SUCCESS:

1. WAIT_LANDING — 50;
2. INIT_SCHEMA — 37;
3. LOAD_ODS — 50;
4. BUILD_DWD — 28, rejected 0;
5. BUILD_DWS — 3;
6. BUILD_ADS — 22;
7. QUALITY_CHECK — 10;
8. PUBLISH_METRIC — 44.

The publish evidence also proved:

- ADS export rows = **22** across 8 tables;
- metric_value rows = **14**;
- active snapshot = `S20260901_4`;
- pre-publish blocking failures = none;
- metric publish result = ok;
- DB read-back checks for ADS rows and metric values passed.

No 3306 fallback occurred.

This is the first Batch R family run that proves the complete positive path:

`HTTP → ingestion → Spark ODS/DWD/DWS/ADS → quality gate → metric publish`.

## 2. Why the shell exit was 1

After the script had already printed:

`[PASS exit=0] Stage 7 isolated HTTP ingestion → pipeline 通过`

the `finally` cleanup function failed with:

`无法覆盖变量 PID，因为它是只读变量或常量。`

Root cause: PowerShell variable names are case-insensitive. The cleanup helper used local variable `$pid`, which collides with the read-only automatic variable `$PID`.

Therefore the outer pwsh process returned exit 1 even though the business chain had already persisted `outcome=PASS` and Pipeline `SUCCESS`.

This is a post-run harness cleanup defect, not a business-chain failure.

## 3. Residual-process check

After the R4 shell returned, the controller searched for Java/Spark processes whose command line matched:

- `attempt-20260918_173825_934`; or
- the Stage 7 platform JAR.

No matching process remained.

## 4. Classification

R4 is deliberately **not** rewritten as a clean harness PASS because its process exit was 1.

The correct split is:

- business chain: **PASS**;
- harness cleanup: **FAIL_TEST_HARNESS_CLEANUP**.

The business result is permanent evidence and does not need to be rerun merely to repair a post-run local variable collision.

## 5. Corrective commit

`3070911577f6f5054f8d0ff859a3b4fefec03cfa`:

- renames cleanup locals from `$pid` / `$ppid` to `$processId` / `$parentProcessId`;
- keeps cleanup scoped to the exact platform PID and captured descendants;
- adds contract assertions forbidding `$pid` reuse.

Targeted validation remains `AnalyticsIsolationScriptsContractTest 8/8 PASS` and DryRun remains zero-I/O.

Cleanup itself is verified separately in Batch R5 so R4's historical exit 1 is preserved.

