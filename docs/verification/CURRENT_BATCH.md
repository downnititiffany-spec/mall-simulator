# Current Verification Batch

> 状态：READY
> Batch R 已永久记录为 FAIL_TEST_HARNESS；当前唯一可执行批次是 R1。

## Current batch

- **Batch ID**：`BATCH-R1-STAGE7-HTTP-INGESTION-PIPELINE`
- **Exact source/test baseline**：`cf807865ac3656cd51d3dd03b0f17a2509362168`
- **Branch context**：`feature/v3-development`
- **Predecessor**：`BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE` / FAIL_TEST_HARNESS
- **Permanent plan**：`docs/verification/batches/BATCH-R1-STAGE7-HTTP-INGESTION-PIPELINE-PLAN.md`
- **Historical Batch R result**：`docs/verification/results/BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **RunId**：`stage7q1_20260918_152245`
- **Overall**：`READY`

## Why R1 exists

Batch R proved platform startup/login/runtime-profile checks/activation and a real 55-row ingestion,
then exposed two bugs in the new verification harness:

1. the harness pre-created Derby's database directory even though production opens it with `create=true`;
2. retry reused the same landing/checkpoint identity, so the second ingestion legitimately returned `noNewData=true`.

Corrective SHA `cf80786` makes every invocation attempt-scoped and leaves the Derby database directory for Derby itself to create.

## Scope

Same minimal real Stage 7 lane:

`8091 platform → HTTP login → runtime profile test/activate → ingestion → pipeline`

No 8090/8092 processes are required in this batch.

## Safety boundary

- no 3306 writes/fallback;
- only Q-R1 run-scoped 3307 meta/metric databases;
- passwords only from current process environment;
- no secret CLI args or echo;
- 8091 must be free;
- only harness-owned platform PID may be stopped.

## Exact execution

See the permanent R1 plan. It must run from the user's existing PowerShell process that still has both analytics secret environment variables.

## PASS

- runtime profile all applicable checks pass;
- fresh attempt ingestion has `recordCount > 0` and `noNewData=false`;
- pipeline status `SUCCESS`;
- harness exit 0;
- latest evidence `outcome=PASS`;
- no 3306 fallback.

