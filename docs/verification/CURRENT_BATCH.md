# Current Verification Batch

> 状态：READY
> 已达到功能簇级批量验证节点。用户只需把 `VERIFY_CURRENT_BATCH` 转发给 Code Agent；Code Agent 按永久计划一次执行整批测试，不修代码。

## Current batch

- **Batch ID**：`BATCH-K-WEB-CONSISTENCY-HARDENING`
- **Tested commit**：`bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf`
- **Branch context**：`feature/v3-development`
- **Accepted predecessor**：`BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING` / `30d5713edae8c90e8849ea51cccaa269d80d3f00` / PASS
- **Permanent plan**：`docs/verification/batches/BATCH-K-WEB-CONSISTENCY-HARDENING-PLAN.md`
- **Expected accepted result**：`docs/verification/results/BATCH-K-WEB-CONSISTENCY-HARDENING-RESULT.md`
- **Raw Code Agent result**：`verification-results:docs/verification/results/BATCH-K-WEB-CONSISTENCY-HARDENING-RESULT.md`

## Scope summary

This batch verifies the accumulated low-risk Web consistency cluster after J-R1:

- Behavior same-snapshot composition and mismatch rejection;
- RFM primary publisher preservation plus secondary snapshot mismatch rejection;
- Sales/Overview handler-level export fail-closed;
- shared CSV freshness/empty-subset fail-closed before browser download;
- Decision write-action reentry guards;
- regression of accepted RFM snapshot pinning / observation window / matrix ownership;
- native Node ESM loadability of the shared CSV helper.

No backend API, DB/Flyway, security/auth, AI SQL, formal shared-contract semantics, state-machine semantics, 3307, or Spark/Hive/Flume E2E changes are part of this batch.

## Execution rule

Code Agent must execute the permanent plan against the exact tested commit. Do not test branch HEAD by name, do not modify source/tests/docs, and do not repair failures during verification.

The required gate is the targeted suites plus `cd web && npm run verify`. Expected full Web count is 261/261 because accepted J-R1 was 251/251 and this batch adds 10 tests with no removals; any unexplained count drift is a failure signal.

## Acceptance boundary

A PASS here means Node unit/source-invariant tests and Vite production build are green for the exact tested SHA. It does not elevate browser interaction, real HTTP concurrency, backend state-machine behavior, database writes, or cluster E2E to verified status.

## User action

`VERIFY_CURRENT_BATCH`
