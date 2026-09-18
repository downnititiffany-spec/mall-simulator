# Current Verification Batch

> 状态：READY
> Batch N-R1 已正式 PASS 并归档。当前又达到下一次功能簇级批量验证节点：RFM / Decision 组合读取的旁路状态不再允许旧请求晚到覆盖，Decision 手工刷新与状态写动作也已做读写互斥。

## Current batch

- **Batch ID**：`BATCH-O-WEB-SECONDARY-READ-CONCURRENCY`
- **Tested commit**：`d4a53a08d3121ce2ce8de9ee4e0582b7230835ef`
- **Branch context**：`feature/v3-development`
- **Accepted predecessor**：`BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY` / `0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d` / PASS
- **Permanent plan**：`docs/verification/batches/BATCH-O-WEB-SECONDARY-READ-CONCURRENCY-PLAN.md`
- **Expected accepted result**：`docs/verification/results/BATCH-O-WEB-SECONDARY-READ-CONCURRENCY-RESULT.md`
- **Raw Code Agent result**：`verification-results:docs/verification/results/BATCH-O-WEB-SECONDARY-READ-CONCURRENCY-RESULT.md`

## Scope summary

This batch verifies one coherent low-risk Web concurrency cluster:

- RFM composite fetch owns a separate request sequence for `usersError` side effects;
- stale/aborted RFM secondary reads cannot overwrite the newest user-aggregate error state;
- Decision composite fetch owns a separate request sequence for `evaluations/evaluationError`;
- stale Decision evaluation reads cannot overwrite newer side-channel state;
- RFM/Decision unmount invalidates those secondary sequences before cancelling the shared analysis request;
- Decision manual refresh and state-write actions are mutually exclusive through `loading || busy`;
- internal post-write `flush()` still bypasses the external refresh guard so successful writes can refresh the list;
- existing Decision payload/state-action wiring and existing RFM snapshot/export semantics remain regression-covered.

No backend API/state-machine transition, DB/Flyway, auth/security, AI SQL, 3307, or Spark/Hive/Flume behavior changes are part of this batch.

## Execution rule

Code Agent must execute the permanent plan against the exact tested commit. Do not test branch HEAD by name, do not modify source/tests/docs, and do not repair failures during verification.

Required targeted suites total **55/55**.

Accepted Batch N-R1 full gate was 296/296. This batch adds exactly 9 tests in `secondaryReadConcurrency.test.js`, so required full gate is:

- **305/305**;
- 0 fail / 0 cancelled / 0 skipped;
- Vite production build must actually execute and pass.

## Acceptance boundary

A PASS proves Node/source-invariant tests and Vite production build for the exact tested SHA. It does not elevate real browser timing, real HTTP races, backend Decision state-machine execution/persistence, browser CSV behavior, 3307, or Spark/Hive/Flume E2E to verified status.

## User action

`VERIFY_CURRENT_BATCH`
