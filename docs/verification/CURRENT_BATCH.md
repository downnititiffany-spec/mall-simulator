# Current Verification Batch

> 状态：READY
> 已达到下一次功能簇级批量验证节点。用户只需把 `VERIFY_CURRENT_BATCH` 转发给 Code Agent；Code Agent 按永久计划一次执行整批测试，不修代码。

## Current batch

- **Batch ID**：`BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION`
- **Tested commit**：`61776daf52cfcd396325d7bbdf56e890f1731224`
- **Branch context**：`feature/v3-development`
- **Accepted predecessor**：`BATCH-L-WEB-INTERACTION-CONSISTENCY` / `395eead89d78d0a40665f2b985002371943f5eb0` / PASS
- **Permanent plan**：`docs/verification/batches/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-PLAN.md`
- **Expected accepted result**：`docs/verification/results/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`
- **Raw Code Agent result**：`verification-results:docs/verification/results/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`

## Scope summary

This batch verifies the accumulated low-risk Web interaction cluster after Batch L:

- Pipeline trigger inputs are frozen before the first async wait and reused when creating the pipeline run;
- Pipeline business-date/runtime-profile inputs are locked while busy, and refresh is blocked while loading or busy;
- Products current-page CSV eligibility requires ready state plus a non-empty current-page row set;
- Products export button and handler share `pageExportable`;
- Products page-size/filter/paging/sort interactions reject loading reentry;
- accepted Pipeline operation identity/retry and shared CSV freshness behavior remain green.

No backend API, DB/Flyway, auth/security, AI SQL, decision state-machine semantics, 3307, or Spark/Hive/Flume behavior changes are part of this batch.

## Execution rule

Code Agent must execute the permanent plan against the exact tested commit. Do not test branch HEAD by name, do not modify source/tests/docs, and do not repair failures during verification.

The required gate is the targeted suites plus `cd web && npm run verify`. Expected full Web count is **274/274** because accepted Batch L was 271/271 and this batch adds exactly three tests; any unexplained count drift is a failure signal.

## Acceptance boundary

A PASS here proves Node unit/source-invariant tests and Vite production build for the exact tested SHA. It does not elevate real browser timing, real HTTP races, actual ingestion/pipeline orchestration, backend idempotency/state-machine behavior, database writes, 3307, or Spark/Hive/Flume E2E to verified status.

## User action

`VERIFY_CURRENT_BATCH`
