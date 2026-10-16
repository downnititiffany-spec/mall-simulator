# BATCH-R1-STAGE7-HTTP-INGESTION-PIPELINE — Controller Review

> Overall: **FAIL_TEST_HARNESS**
> Exact tested SHA: `cf807865ac3656cd51d3dd03b0f17a2509362168`
> RunId: `stage7q1_20260918_152245`
> Attempt: `attempt-20260918_170258_708`

## 1. What R1 proved

The two Batch R corrections worked:

- a fresh attempt-scoped landing root was used;
- ingestion consumed a fresh input identity;
- batch 3 completed with `noNewData=false`;
- **51 accepted + 4 quarantined = 55 total input records**;
- runtime-profile applicable checks still passed;
- platform startup/login/activation still passed.

Therefore the attempt-isolation and Derby-directory corrections did not regress the HTTP/ingestion path.

## 2. Why the reported Pipeline failure was not a fresh Spark run

R1's pipeline response returned:

- `runId=1`;
- the old `WAIT_LANDING` stage timestamp from 16:21;
- the old `INIT_SCHEMA` failure;
- the old log URI under the pre-attempt-scoped landing root.

This proves the R1 POST did not create a new Pipeline run.

Root cause: the harness still sent a fixed header:

`Idempotency-Key: stage7-<RunId>`

Production `PipelineService` correctly implements the documented contract:

`same idempotency key -> return the existing run without re-execution`.

So the backend returned Batch R's old failed run exactly as designed.

## 3. Classification

Overall = **FAIL_TEST_HARNESS**.

This is not a production idempotency bug and not evidence of a new Spark failure. The verification harness failed to make its Pipeline identity attempt-scoped.

No 3306 fallback occurred.

## 4. Corrective commit

`37dae94c8acc124cfb6aecb86f18241d3b6a21d0` makes both:

- `Idempotency-Key`
- `sourceDataVersion`

derive from `stage7-<RunId>-<attemptId>`.

The structure guard remains **7/7 PASS** and DryRun remains zero-I/O. No production code or default test count changed.

The corrected behavior must be proven in a separate R2 batch.

