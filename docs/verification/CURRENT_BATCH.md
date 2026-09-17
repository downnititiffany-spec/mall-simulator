# Current Verification Batch

> 状态：READY
> Batch M-R1 已正式 PASS 并归档。当前又达到下一次功能簇级批量验证节点：Behavior / Sales / Overview / RFM 的 loading 交互锁与实际导出子集资格已统一收口。用户只需把 `VERIFY_CURRENT_BATCH` 转发给 Code Agent；Code Agent 按永久计划一次执行整批测试，不修代码。

## Current batch

- **Batch ID**：`BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY`
- **Tested commit**：`58411f92a8e5591c435f5597143896f4fadac020`
- **Branch context**：`feature/v3-development`
- **Accepted predecessor**：`BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION` / `7748caf8b2628bd47ed5075db62ec2cd26a42fe6` / PASS
- **Permanent plan**：`docs/verification/batches/BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY-PLAN.md`
- **Expected accepted result**：`docs/verification/results/BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md`
- **Raw Code Agent result**：`verification-results:docs/verification/results/BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md`

## Scope summary

This batch verifies one coherent low-risk Web interaction cluster:

- Behavior/Sales/Overview date inputs are locked while loading;
- Behavior/Sales/Overview `load()` handlers reject loading reentry themselves;
- Behavior funnel CSV is enabled only when the actual `stages` export subset is non-empty;
- Overview metrics CSV is enabled only when the actual `cards` export subset is non-empty;
- RFM refresh rejects loading reentry before mutating `usersError`;
- RFM CSV is enabled only when actual `segmentRows` are non-empty;
- button and handler predicates remain identical for each subset export;
- existing RFM primary-publisher/fallback ownership and shared CSV freshness semantics remain regression-covered.

No backend API, DB/Flyway, auth/security, AI SQL, decision state-machine semantics, 3307, or Spark/Hive/Flume behavior changes are part of this batch.

## Execution rule

Code Agent must execute the permanent plan against the exact tested commit. Do not test branch HEAD by name, do not modify source/tests/docs, and do not repair failures during verification.

Required targeted suites total **30/30**. Required full gate is `cd web && npm run verify` with expected **282/282** tests: accepted Batch M-R1 was 274/274 and this batch adds exactly eight tests in `analysisFilterInteractionHardening.test.js`.

## Acceptance boundary

A PASS proves Node unit/source-invariant tests and Vite production build for the exact tested SHA. It does not elevate real browser timing, real HTTP races, actual browser CSV download behavior, backend snapshot/runtime behavior, database writes, 3307, or Spark/Hive/Flume E2E to verified status.

## User action

`VERIFY_CURRENT_BATCH`
