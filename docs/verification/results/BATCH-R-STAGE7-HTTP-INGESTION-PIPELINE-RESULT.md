# BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE — Controller Review

> Overall: **FAIL_TEST_HARNESS**
> Exact tested source/test SHA: `2714ffb801d08c7046a61da4652f086910a59183`
> RunId: `stage7q1_20260918_152245`

## 1. What actually passed

The real run proved the following against the Q-R1 run-scoped MySQL 3307 databases:

- analytics platform started on 8091;
- HTTP admin login succeeded;
- runtime profile was updated through the real HTTP API;
- runtime-profile applicable checks passed:
  - landing PASS;
  - Spark executable PASS (`spark-submit --version` exit 0);
  - metric store PASS;
  - Hive correctly SKIPPED for LOCAL;
- runtime profile activation succeeded;
- the first ingestion attempt consumed the frozen golden file:
  - manifest batch 1;
  - **55 input records = 51 accepted + 4 quarantined**;
  - source = `mock-mall`;
  - manifest status = READY.

This is real runtime evidence, not a unit-test simulation.

## 2. First runtime failure

The first attempt reached the real pipeline and started stage `INIT_SCHEMA`. The Spark child process then failed in embedded Hive/Derby initialization:

`ERROR XBM0J: Directory .../http/derby-metastore already exists.`

Root cause was the Batch R harness itself. It created `$metastoreDir` with `New-Item` before Spark started, while production `SparkStageExecutorFactory` intentionally configures:

`jdbc:derby:<metastoreDir>;create=true`

The Derby database directory must not exist before the first open. The production code and Spark job were following their existing contract; the test harness violated it.

## 3. Retry symptom

A later retry on the same SHA/runId again passed platform startup, login, runtime-profile test and activation, but ingestion returned:

- batchId 2;
- status SUCCESS;
- recordCount 0;
- fileCount 0;
- noNewData = true.

This was not a bad golden dataset. Batch 1 had already consumed the same physical landing file and committed its checkpoint. The original harness reused both the same landing root and the same input filename, so a rerun legitimately saw no new bytes.

Therefore Batch R exposed **two harness defects**:

1. pre-created Derby database directory;
2. non-repeatable reuse of landing/checkpoint identity across test attempts.

## 4. Classification

Overall = **FAIL_TEST_HARNESS**, not PASS and not BLOCKED_ENV.

The failure is attributable to the newly introduced verification harness, not to an external missing dependency and not to the platform business API contract. The batch must remain as permanent failure evidence and must not be rewritten green.

No 3306 fallback was used.

## 5. Corrective commit

`cf807865ac3656cd51d3dd03b0f17a2509362168` fixes only the Stage 7 harness:

- each invocation gets `attempt-<timestamp>` under `target/v25-it/<RunId>/http`;
- landing, Spark warehouse, metric staging and logs are attempt-scoped;
- the golden input filename is attempt-scoped;
- `derby-metastore` is passed as a path but is **not pre-created**;
- per-attempt evidence is preserved;
- `http/stage7-http-result.json` remains a latest evidence pointer.

Static guard remains **7/7 PASS** and default fresh remains **1033 / 13 / 110** with only the pre-existing manifest patrol failure.

The corrected behavior must be proven in a separate R1 batch.

