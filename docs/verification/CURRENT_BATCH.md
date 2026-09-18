# Current Verification Batch

> 状态：READY
> Batch R / R1 / R2 / R3 均保留历史结果；当前唯一可执行批次是 R4。

## Current batch

- **Batch ID**：`BATCH-R4-STAGE7-HTTP-INGESTION-PIPELINE`
- **Exact source/test baseline**：`4ef4d93339638e32ff2cb2547f8551be6da42305`
- **Branch context**：`feature/v3-development`
- **Predecessor**：`BATCH-R3-STAGE7-HTTP-INGESTION-PIPELINE` / FAIL_TEST_HARNESS_TIMEOUT
- **Permanent plan**：`docs/verification/batches/BATCH-R4-STAGE7-HTTP-INGESTION-PIPELINE-PLAN.md`
- **Historical R result**：`docs/verification/results/BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **Historical R1 result**：`docs/verification/results/BATCH-R1-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **Historical R2 result**：`docs/verification/results/BATCH-R2-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **Historical R3 result**：`docs/verification/results/BATCH-R3-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **RunId**：`stage7q1_20260918_152245`
- **Overall**：`READY`

## Why R4 exists

R3 proved the 50-row positive fixture is accepted cleanly:

- ingestion = 50 accepted / 0 quarantine;
- INIT_SCHEMA / LOAD_ODS / BUILD_DWD succeeded;
- BUILD_DWD rejected 0 rows;
- Pipeline then entered BUILD_DWS.

But the verification harness had a 180-second whole-Pipeline deadline. At that deadline Pipeline was still RUNNING with no error code. The harness stopped 8091 and left one owned Spark child process behind, so R3 was interrupted by the harness itself and cannot classify BUILD_DWS as production failure.

Corrective SHA `4ef4d93` raises the bounded deadline to 600s, separates `PIPELINE_TIMEOUT` from Pipeline FAILED, and cleans only the process tree descended from the platform PID started by this invocation.

## Safety boundary

- no 3306 writes/fallback;
- only Q-R1 run-scoped 3307 analytics databases;
- 50-row positive fixture only;
- secrets only from current process environment;
- fresh attempt directories and idempotency;
- 8091 must be free;
- no quality-rule weakening;
- no broad Java process kill.

## Validation before READY

- Stage 7 DryRun exit 0 / zero-I/O;
- `AnalyticsIsolationScriptsContractTest` **8/8 PASS**;
- default fresh analytics **1034 MATCH**;
- mall **13 MATCH**;
- generator **110 MATCH**;
- only pre-existing manifest patrol remains red.

## PASS

- ingestion 50/0/noNewData=false;
- all Spark stages through BUILD_ADS SUCCESS;
- QUALITY_CHECK PASS;
- PUBLISH_METRIC SUCCESS;
- Pipeline SUCCESS;
- harness exit 0;
- no residual R4-owned process after exit;
- no 3306 fallback.

