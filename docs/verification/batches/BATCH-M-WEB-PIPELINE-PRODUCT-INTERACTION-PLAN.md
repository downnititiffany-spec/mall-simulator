# BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION — Verification Plan

## 1. Purpose

Verify the next accumulated low-risk Web interaction-consistency cluster after accepted Batch L. This batch covers Pipeline trigger-input stability while an operation is in flight and Products current-page interaction/export eligibility.

## 2. Exact tested commit

`61776daf52cfcd396325d7bbdf56e890f1731224`

Branch context: `feature/v3-development`.

Accepted predecessor: `BATCH-L-WEB-INTERACTION-CONSISTENCY` @ `395eead89d78d0a40665f2b985002371943f5eb0` / PASS.

## 3. Scope

Production changes in this batch:

- `web/src/views/Pipeline.vue`
  - freeze `businessDate` and `runtimeProfileId` before the first async wait in `runOnce()`;
  - use only the frozen values when creating the pipeline run;
  - lock the two trigger inputs while `busy`;
  - disable manual refresh while either read loading or write action busy.
- `web/src/views/Products.vue`
  - current-page CSV eligibility requires both page-level ready state and a non-empty current hot-page row set;
  - export button and handler share `pageExportable`;
  - page-size input is locked while loading;
  - `applyFilters()` itself rejects loading reentry.

Test updates:

- `web/tests/pipelineOperationIdentity.test.js`: +2 tests (4 -> 6).
- `web/tests/productServerPagination.test.js`: +1 test (5 -> 6), with export assertion upgraded to `pageExportable`.
- `web/tests/pipelinePage.test.js`: existing count unchanged; refresh characterization upgraded from `loading` to `loading || busy`.

No backend API, DB/Flyway, auth/security, AI SQL, decision state-machine semantics, 3307, or Spark/Hive/Flume behavior changes are in this batch.

## 4. Commands

Run in this order, once, against the exact commit:

```powershell
git fetch origin
git checkout --detach 61776daf52cfcd396325d7bbdf56e890f1731224
git rev-parse HEAD
git status --short
cd web
node --test tests/pipelineOperationIdentity.test.js
node --test tests/pipelinePage.test.js
node --test tests/productServerPagination.test.js
node --test tests/pipelineRetryHandling.test.js
node --test tests/exportFreshnessGuard.test.js
npm run verify
```

## 5. Expected targeted results

- `pipelineOperationIdentity.test.js`: **6/6 PASS**.
- `pipelinePage.test.js`: **6/6 PASS**.
- `productServerPagination.test.js`: **6/6 PASS**.
- `pipelineRetryHandling.test.js`: **4/4 PASS**.
- `exportFreshnessGuard.test.js`: **6/6 PASS**.

Targeted total: **28/28 PASS**.

## 6. Full gate

`npm run verify` must exit 0. Expected total is **274/274** because accepted Batch L was 271/271 and this batch adds exactly three tests: +2 in `pipelineOperationIdentity` and +1 in `productServerPagination`; `pipelinePage` only changes an existing assertion and does not add/remove a test.

Record actual test count, failures/cancellations/skips, Vite version, transformed module count, build duration, and whether the production build actually executed.

Any unexplained count drift is a failure signal.

## 7. Required semantic checks

Confirm from the exact tested source:

1. `runOnce()` begins with handler-level `if (busy.value) return` and does not rely only on button disabled state.
2. `requestedBusinessDate = businessDate.value` and `requestedRuntimeProfileId = runtimeProfileId.value` are captured before the first `await api.ingestionRun()`.
3. `api.createPipelineRun(...)` uses `requestedRuntimeProfileId` and `requestedBusinessDate`, not live `.value` reads after the async wait.
4. Pipeline business-date and runtime-profile inputs are disabled while `busy`; the manual refresh button is disabled while `loading || busy`.
5. Existing Pipeline operation identity/retry behavior remains: one `operationId`, shared sourceDataVersion/idempotency key, busy reentry guard, visible failure, finally release, successful refresh.
6. Products defines `pageExportable = exportable.value && rows.value.length > 0`; both the current-page CSV button and `doExport()` use that same predicate.
7. `applyFilters()` performs handler-level `if (loading.value) return` before resetting page and loading.
8. Products page-size input is disabled while loading; paging/sort handlers continue to reject loading reentry.
9. Products still sends backend `page/size/sort` and does not reintroduce local full-dataset pagination/sorting or unsupported sort fields.
10. Shared CSV freshness/empty-row fail-closed behavior remains green via `exportFreshnessGuard.test.js`.

## 8. Evidence boundary

A PASS proves Node unit/source-invariant tests plus Vite production build for the exact tested SHA. It does **not** prove:

- real browser timing or DOM interaction;
- real HTTP races;
- actual ingestion/pipeline orchestration behavior;
- backend idempotency/state-machine behavior;
- database writes or 3307 runtime;
- Spark/Hive/Flume E2E.

Do not elevate those areas to verified status.

## 9. Result persistence

Write the complete result to:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md
```

Do not modify source, tests, plans, docs, Git configuration, `main`, or `feature/v3-development` during verification.

## 10. PASS rule

PASS only if:

- checked-out SHA exactly equals `61776daf52cfcd396325d7bbdf56e890f1731224`;
- all targeted suites pass with **28/28** and zero failed/cancelled tests;
- full Web gate exits 0 with expected **274/274**, zero failed/cancelled/skipped tests, and no unexplained count drift;
- Vite production build actually executes and passes;
- all §7 semantic checks are satisfied;
- workspace is clean before and after;
- no source/test/doc repair is performed during verification.

Otherwise record FAIL with the exact new failure and stop; do not repair it in the verification run.
