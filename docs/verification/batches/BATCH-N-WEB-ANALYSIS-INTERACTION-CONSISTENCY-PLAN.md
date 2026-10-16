# BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY — Verification Plan

## 1. Purpose

Verify the next accumulated low-risk Web interaction-consistency cluster after accepted Batch M-R1. This batch standardizes loading-time filter locking, handler-level load reentry protection, and export eligibility for the actual CSV subset across Behavior / Sales / Overview / RFM analysis pages.

## 2. Exact tested commit

`58411f92a8e5591c435f5597143896f4fadac020`

Branch context: `feature/v3-development`.

Accepted predecessor: `BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION` @ `7748caf8b2628bd47ed5075db62ec2cd26a42fe6` / PASS.

Batch M failed attempt remains retained separately and is not rewritten.

## 3. Scope

Production changes:

- `web/src/views/Behavior.vue`
  - lock `from` / `to` while loading;
  - `load()` itself rejects loading reentry;
  - define `funnelExportable = exportable && stages.length > 0`;
  - export button and `doExport()` share that exact predicate.
- `web/src/views/Sales.vue`
  - lock `from` / `to` while loading;
  - `load()` rejects loading reentry before resetting page / requesting.
  - sales CSV semantics remain unchanged and continue exporting the trend rows.
- `web/src/views/Overview.vue`
  - lock `from` / `to` while loading;
  - `load()` itself rejects loading reentry;
  - define `metricExportable = exportable && cards.length > 0`;
  - export button and `doExport()` share that exact predicate.
- `web/src/views/Rfm.vue`
  - `load()` rejects loading reentry before clearing `usersError`;
  - define `segmentExportable = exportable && segmentRows.length > 0`;
  - export button and `doExport()` share that exact predicate.

Tests:

- new `web/tests/analysisFilterInteractionHardening.test.js`: **8 tests**.

No backend API, DB/Flyway, auth/security, AI SQL, decision state-machine semantics, 3307, or Spark/Hive/Flume behavior changes are in this batch.

## 4. Commands

Run once, in this order, against the exact tested commit:

```powershell
git fetch origin
git checkout --detach 58411f92a8e5591c435f5597143896f4fadac020
git rev-parse HEAD
git status --short
cd web
node --test tests/analysisFilterInteractionHardening.test.js
node --test tests/exportFreshnessGuard.test.js
node --test tests/rfmMatrixOwnership.test.js
node --test tests/rfmObservationWindow.test.js
node --test tests/rfmSnapshotPinning.test.js
node --test tests/metricDefinitionVersionDisplay.test.js
npm run verify
```

## 5. Expected targeted results

- `analysisFilterInteractionHardening.test.js`: **8/8 PASS**.
- `exportFreshnessGuard.test.js`: **6/6 PASS**.
- `rfmMatrixOwnership.test.js`: **4/4 PASS**.
- `rfmObservationWindow.test.js`: **4/4 PASS**.
- `rfmSnapshotPinning.test.js`: **4/4 PASS**.
- `metricDefinitionVersionDisplay.test.js`: **4/4 PASS**.

Targeted total: **30/30 PASS**.

## 6. Full gate

`npm run verify` must exit 0.

Expected total: **282/282** because accepted Batch M-R1 was 274/274 and this batch adds exactly eight tests in one new test file. No existing test was added/removed in this batch.

Record actual total/pass/fail/cancelled/skipped, Vite version, transformed module count, build duration, and whether the production build actually executed.

Any unexplained test-count drift is a failure signal.

## 7. Required semantic checks

Confirm from the exact tested source:

1. Behavior `from` / `to` inputs are disabled while `loading`, and `load()` begins by rejecting `loading.value` reentry.
2. Behavior CSV uses `funnelExportable = exportable.value && stages.value.length > 0`; both button and `doExport()` use it, while rows still come from `stages`.
3. Sales `from` / `to` inputs are disabled while loading; `load()` rejects loading reentry before `page.value = 1` and before `analysis.load(...)`.
4. Sales export behavior is otherwise unchanged: it remains protected by page-level `exportable` and exports `sortedRows`, which is the page's primary trend dataset.
5. Overview `from` / `to` inputs are disabled while loading and `load()` rejects loading reentry.
6. Overview CSV uses `metricExportable = exportable.value && cards.value.length > 0`; both button and handler share it, and rows still derive from `cards`.
7. RFM `load()` rejects loading reentry before clearing `usersError` or requesting again.
8. RFM CSV uses `segmentExportable = exportable.value && segmentRows.value.length > 0`; both button and handler share it, and rows still derive from `segmentRows`.
9. Existing RFM ownership semantics remain unchanged: `rfmMatrix` remains the primary publisher, fallback uses real `rfmSegments`, and the frontend does not fabricate a second fixed zero-filled matrix.
10. Shared CSV freshness / stale / empty-row fail-closed behavior remains green, and the full Web gate/build succeeds with the expected 282 tests.

## 8. Evidence boundary

A PASS proves Node unit/source-invariant tests plus Vite production build for the exact tested SHA. It does **not** prove:

- real browser input-disable timing / click races;
- real HTTP concurrency;
- backend snapshot behavior beyond source contracts;
- real CSV download behavior in a browser;
- database writes or 3307 runtime;
- Spark/Hive/Flume E2E.

Do not elevate those areas to verified status.

## 9. Result persistence

Write the complete result to:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md
```

Do not modify source, tests, plans, docs, Git configuration, `main`, or `feature/v3-development` during verification.

## 10. PASS rule

PASS only if:

- checked-out SHA exactly equals `58411f92a8e5591c435f5597143896f4fadac020`;
- all targeted suites pass with **30/30**, zero failed/cancelled tests;
- full Web gate exits 0 with expected **282/282**, zero failed/cancelled/skipped tests and no unexplained count drift;
- Vite production build actually executes and passes;
- all §7 semantic checks are satisfied;
- workspace is clean before and after;
- no source/test/doc repair is performed during verification.

Otherwise record FAIL with the exact new failure and stop; do not repair it in the verification run.
