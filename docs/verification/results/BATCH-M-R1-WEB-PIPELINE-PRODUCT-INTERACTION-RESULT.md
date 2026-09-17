# BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION — Accepted Result

- **Verdict**: PASS
- **Exact tested commit**: `7748caf8b2628bd47ed5075db62ec2cd26a42fe6`
- **Branch context**: `feature/v3-development`
- **Accepted predecessor**: `BATCH-L-WEB-INTERACTION-CONSISTENCY` @ `395eead89d78d0a40665f2b985002371943f5eb0` / PASS
- **Raw Code Agent result**: `verification-results:docs/verification/results/BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`
- **Raw result commit**: `6406880dd00ba13c769ba91a85a3a3a8b3464c35`

## Acceptance summary

- Targeted suites: **33/33 PASS**
  - `pipelineLocalBusinessDate.test.js`: 5/5
  - `pipelineOperationIdentity.test.js`: 6/6
  - `pipelinePage.test.js`: 6/6
  - `productServerPagination.test.js`: 6/6
  - `pipelineRetryHandling.test.js`: 4/4
  - `exportFreshnessGuard.test.js`: 6/6
- Full Web gate: `npm run verify` exit **0**, **274/274 PASS**, failed/cancelled/skipped = **0/0/0**.
- Vite production build actually executed and passed: Vite 5.4.21, 672 modules transformed, built in 4.55s.
- Workspace was clean before and after; the tester did not modify source/tests/docs/Git configuration.

## Regression resolution

Batch M first attempt at `61776daf52cfcd396325d7bbdf56e890f1731224` was retained as `FAIL_NEW_REGRESSION` because an already-accepted characterization test still required the old source text `businessDate.value + 'T00:00:00'` while production had correctly frozen the input before the first async wait.

R1 changes production code **not at all**. Relative to the failed attempt, only `web/tests/pipelineLocalBusinessDate.test.js` changed, replacing the obsolete variable-name assertion with the real invariant:

- `const requestedBusinessDate = businessDate.value`
- `businessTime: requestedBusinessDate + 'T00:00:00'`

This preserves both required semantics: the user-confirmed local business day is captured before async work, and the pipeline request still uses local midnight without UTC truncation.

## Semantic checks accepted

The R1 verification confirmed the complete Batch M interaction cluster:

1. `runOnce()` has a handler-level busy guard.
2. business date and runtime profile are frozen before the first await.
3. pipeline creation uses only frozen values after the async wait.
4. local business day still maps to literal `T00:00:00` without UTC `toISOString().slice(0,10)`.
5. Pipeline trigger inputs and refresh are locked during incompatible in-flight work.
6. Pipeline operation identity/retry invariants remain intact.
7. Products `pageExportable` requires ready state plus non-empty current-page rows and is shared by UI + handler.
8. Products filter/page-size/page/sort interactions reject loading reentry.
9. Products still delegates `page/size/sort` to the backend and does not reintroduce local full-dataset sorting/pagination.
10. Shared CSV freshness/empty-row fail-closed behavior remains green.

## Failed attempt retained

- Batch: `BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION`
- Tested commit: `61776daf52cfcd396325d7bbdf56e890f1731224`
- Verdict: `FAIL_NEW_REGRESSION`
- Raw result commit: `0c46b9c79ba82252ef1e8a961e9def0eb32fabba`
- Full gate: 273/274; Vite build did not execute because `npm test` failed first.

The failed result is intentionally preserved and not overwritten.

## Evidence boundary

This PASS proves Node unit/source-invariant tests and Vite production build for the exact tested SHA. It does **not** prove real browser timing/DOM behavior, real HTTP races, actual ingestion/pipeline orchestration, backend idempotency/state-machine behavior, database writes, 3307 runtime, or Spark/Hive/Flume E2E.
