# BATCH-R3-STAGE7-HTTP-INGESTION-PIPELINE — Controller Review

> Overall: **FAIL_TEST_HARNESS_TIMEOUT**
> Exact tested SHA: `2765701c13b607cc1426024d7d4c948c4b99c365`
> RunId: `stage7q1_20260918_152245`
> Attempt: `attempt-20260918_172719_960`

## 1. What R3 proved

The positive fixture correction worked:

- runtime-profile applicable checks PASS;
- ingestion batch 5 = **50 accepted / 0 quarantine / noNewData=false**;
- Pipeline runId = `3` with an attempt-scoped idempotency key;
- WAIT_LANDING SUCCESS;
- INIT_SCHEMA SUCCESS;
- LOAD_ODS SUCCESS;
- BUILD_DWD SUCCESS with **0 rejected**;
- the run then entered BUILD_DWS.

No quality rule or production Pipeline code was relaxed.

## 2. Why R3 is not a production failure

The harness had a fixed `PipelineTimeoutSec=180`. At the deadline the real Pipeline was still:

- status = `RUNNING`;
- currentStage = `BUILD_DWS`;
- errorCode = null.

The harness nevertheless treated this non-terminal state as a failure and immediately stopped the platform. This actively interrupted the verification before the Pipeline could reach a terminal state.

The controller later found one Spark child process still running for this exact attempt:

- jobCode = `usw`;
- command line contained `attempt-20260918_172719_960`.

That process was explicitly stopped by PID after verifying its command line belonged to this R3 attempt. No unrelated Java process was touched.

Therefore R3 cannot be used to conclude that BUILD_DWS or production Pipeline failed.

## 3. Classification

Overall = **FAIL_TEST_HARNESS_TIMEOUT**.

The batch failed because the verification harness:

1. used an insufficient whole-Pipeline timeout;
2. labelled a still-RUNNING Pipeline as failure;
3. stopped the platform without first stopping its descendant Spark process tree.

No 3306 fallback occurred.

## 4. Corrective commit

`4ef4d93339638e32ff2cb2547f8551be6da42305`:

- raises default full-Pipeline timeout from 180s to 600s;
- records non-terminal deadline as `PIPELINE_TIMEOUT` instead of pretending Pipeline FAILED;
- adds process-tree cleanup scoped only to the harness-owned platform PID and captured descendants;
- keeps broad Java process killing prohibited.

Validation before R4:

- `AnalyticsIsolationScriptsContractTest`: **8/8 PASS**;
- Stage 7 DryRun: exit 0 / zero-I/O;
- default fresh: analytics **1034 MATCH**, mall **13 MATCH**, generator **110 MATCH**;
- only the existing manifest patrol remains red.

