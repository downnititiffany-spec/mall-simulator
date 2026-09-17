# BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION — Verification Plan

## 1. Purpose

Re-verify the Batch M Web interaction-consistency cluster after fixing the single new-regression characterization test exposed by the first Batch M execution.

The original Batch M execution at `61776daf52cfcd396325d7bbdf56e890f1731224` is preserved as FAIL_NEW_REGRESSION evidence. Its production semantics were not rolled back: Pipeline trigger inputs remain frozen before asynchronous waits, and Products current-page export/interactions remain fail-closed.

This R1 changes only `web/tests/pipelineLocalBusinessDate.test.js` so the already-accepted local-business-date invariant follows the frozen input variable instead of requiring the old live `businessDate.value` identifier at the final request-body construction site.

## 2. Exact tested commit

`7748caf8b2628bd47ed5075db62ec2cd26a42fe6`

Branch context: `feature/v3-development`.

Accepted predecessor: `BATCH-L-WEB-INTERACTION-CONSISTENCY` @ `395eead89d78d0a40665f2b985002371943f5eb0` / PASS.

Failed attempt retained for audit:

- Batch: `BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION`
- Tested commit: `61776daf52cfcd396325d7bbdf56e890f1731224`
- Verdict: `FAIL_NEW_REGRESSION`
- Raw result: `verification-results:docs/verification/results/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`
- Raw result commit: `0c46b9c79ba82252ef1e8a961e9def0eb32fabba`

## 3. Scope

Production behavior under re-verification is unchanged from Batch M:

- `web/src/views/Pipeline.vue`
  - `runOnce()` freezes `businessDate` and `runtimeProfileId` before the first asynchronous wait;
  - `createPipelineRun(...)` uses only those frozen values;
  - trigger inputs are locked while `busy`;
  - manual refresh is disabled while `loading || busy`.
- `web/src/views/Products.vue`
  - current-page CSV eligibility requires ready state plus a non-empty current page;
  - button and handler share `pageExportable`;
  - page-size/filter/paging/sort interactions reject loading reentry.

R1 test-only repair:

- `web/tests/pipelineLocalBusinessDate.test.js`
  - keeps the existing invariant that Pipeline defaults its business date via the local-calendar helper;
  - verifies the user-confirmed business date is captured into `requestedBusinessDate`;
  - verifies `businessTime` is still formed as that captured local date plus `T00:00:00`;
  - no longer requires the obsolete final-body text `businessDate.value + 'T00:00:00'`, which would contradict the new freeze-before-await invariant.

No backend API, DB/Flyway, auth/security, AI SQL, decision state-machine semantics, 3307, or Spark/Hive/Flume behavior changes are part of this R1.

## 4. Commands

Run once, in this order, against the exact tested commit:

```powershell
git fetch origin
git checkout --detach 7748caf8b2628bd47ed5075db62ec2cd26a42fe6
git rev-parse HEAD
git status --short
cd web
node --test tests/pipelineLocalBusinessDate.test.js
node --test tests/pipelineOperationIdentity.test.js
node --test tests/pipelinePage.test.js
node --test tests/productServerPagination.test.js
node --test tests/pipelineRetryHandling.test.js
node --test tests/exportFreshnessGuard.test.js
npm run verify
```

## 5. Expected targeted results

- `pipelineLocalBusinessDate.test.js`: **5/5 PASS**.
- `pipelineOperationIdentity.test.js`: **6/6 PASS**.
- `pipelinePage.test.js`: **6/6 PASS**.
- `productServerPagination.test.js`: **6/6 PASS**.
- `pipelineRetryHandling.test.js`: **4/4 PASS**.
- `exportFreshnessGuard.test.js`: **6/6 PASS**.

Targeted total: **33/33 PASS**.

## 6. Full gate

`npm run verify` must exit 0 with expected **274/274** tests.

The expected count is unchanged from the failed Batch M attempt because R1 rewrites one existing assertion and adds/removes no test cases. Record actual totals, failed/cancelled/skipped counts, Vite version, transformed module count, build duration, and whether the production build actually executed.

Any unexplained count drift is a failure signal.

## 7. Required semantic checks

Confirm from the exact tested source:

1. `runOnce()` starts with handler-level `if (busy.value) return`.
2. `requestedBusinessDate = businessDate.value` and `requestedRuntimeProfileId = runtimeProfileId.value` are captured before the first `await api.ingestionRun()`.
3. `api.createPipelineRun(...)` uses `requestedBusinessDate` / `requestedRuntimeProfileId`, not live `.value` reads after the wait.
4. `businessTime` remains the user-confirmed local business date plus literal `T00:00:00`; R1 must not reintroduce UTC `toISOString().slice(0, 10)` behavior.
5. Pipeline business-date/runtime-profile inputs remain disabled while `busy`; manual refresh remains disabled while `loading || busy`.
6. Pipeline operation identity/retry invariants remain green: one `operationId`, shared sourceDataVersion/idempotency key, visible failure, successful refresh only on success, and `finally` releases busy.
7. Products still defines `pageExportable = exportable.value && rows.value.length > 0`, with both button and `doExport()` using it.
8. `applyFilters()` performs handler-level loading rejection before page reset/load; page-size, paging, and sorting remain loading-locked.
9. Products still sends backend `page/size/sort` and does not reintroduce local full-dataset pagination/sorting or unsupported sort fields.
10. Shared CSV freshness/empty-row fail-closed behavior remains green.

## 8. Evidence boundary

A PASS proves Node unit/source-invariant tests plus Vite production build for the exact R1 SHA. It does **not** prove:

- real browser timing/DOM interaction;
- real HTTP races;
- actual ingestion/pipeline orchestration;
- backend idempotency/state-machine behavior;
- database writes or 3307 runtime;
- Spark/Hive/Flume E2E.

Do not elevate those areas to verified status.

## 9. Result persistence

Write the complete R1 result to:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md
```

Do not overwrite or rewrite the original failed Batch M result.
Do not modify source, tests, plans, docs, Git configuration, `main`, or `feature/v3-development` during verification.

## 10. PASS rule

PASS only if:

- checked-out SHA exactly equals `7748caf8b2628bd47ed5075db62ec2cd26a42fe6`;
- all targeted suites pass with **33/33** and zero failed/cancelled tests;
- full Web gate exits 0 with **274/274**, zero failed/cancelled/skipped tests, and no unexplained count drift;
- Vite production build actually executes and passes;
- all §7 semantic checks are satisfied;
- workspace is clean before and after;
- no repair is performed during verification.

Otherwise record the exact failure and stop; do not repair it inside the verification run.
