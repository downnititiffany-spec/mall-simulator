# Current Verification Batch

> 状态：READY
> Batch R / R1 已永久保留为 FAIL_TEST_HARNESS；当前唯一可执行批次是 R2。

## Current batch

- **Batch ID**：`BATCH-R2-STAGE7-HTTP-INGESTION-PIPELINE`
- **Exact source/test baseline**：`37dae94c8acc124cfb6aecb86f18241d3b6a21d0`
- **Branch context**：`feature/v3-development`
- **Predecessor**：`BATCH-R1-STAGE7-HTTP-INGESTION-PIPELINE` / FAIL_TEST_HARNESS
- **Permanent plan**：`docs/verification/batches/BATCH-R2-STAGE7-HTTP-INGESTION-PIPELINE-PLAN.md`
- **Historical R result**：`docs/verification/results/BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **Historical R1 result**：`docs/verification/results/BATCH-R1-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **RunId**：`stage7q1_20260918_152245`
- **Overall**：`READY`

## Why R2 exists

R1 confirmed the attempt-scoped landing fix by consuming a fresh 55-row input
(51 accepted + 4 quarantined, `noNewData=false`), but its Pipeline POST still used the same fixed
`Idempotency-Key` as Batch R. Production correctly returned historical `runId=1` and did not re-execute Spark.

Corrective SHA `37dae94` makes Pipeline idempotency and `sourceDataVersion` attempt-scoped.

## Scope

`8091 platform → HTTP login → runtime profile test/activate → fresh ingestion → fresh pipeline`

R2 must return a newly created Pipeline run for the current attempt and exercise the current attempt's Derby/Spark path.

## Safety boundary

- no 3306 writes/fallback;
- only Q-R1 run-scoped 3307 meta/metric databases;
- secrets only from current process environment;
- no password CLI args or echo;
- 8091 must be free;
- only harness-owned platform PID may be stopped;
- no Fake executor / skipped Spark check.

## PASS

- applicable runtime-profile checks PASS;
- ingestion `recordCount>0` and `noNewData=false`;
- Pipeline run is fresh for this attempt;
- Pipeline reaches `SUCCESS`;
- harness exit 0;
- latest evidence `outcome=PASS`;
- no 3306 fallback.

