# BATCH-O-WEB-SECONDARY-READ-CONCURRENCY — Verification Plan

## 1. Purpose

Verify the next coherent low-risk Web concurrency cluster after accepted Batch N-R1.

This batch closes two classes of frontend race that are not covered by the shared `useAnalysis` main-response sequence guard:

1. secondary/side-channel state written inside composite fetchers must not be overwritten by an older request that finishes late;
2. Decision manual refresh and Decision state-write actions must not run concurrently from the UI.

No backend state-machine transition, API payload contract, DB/Flyway, auth/security, AI SQL, 3307, Spark/Hive/Flume code is changed.

## 2. Exact tested commit

`d4a53a08d3121ce2ce8de9ee4e0582b7230835ef`

Branch context: `feature/v3-development`.

Accepted predecessor:
- `BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY`
- tested SHA `0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d`
- PASS
- Web full gate baseline: 296/296.

## 3. Scope

Production changes:

- `web/src/views/Rfm.vue`
  - add independent `rfmFetchSeq` for the RFM + users composite fetch;
  - capture `mySeq` before the first request;
  - only the latest fetch may write `usersError` for missing snapshot or secondary-read failure;
  - component unmount invalidates the secondary sequence before `analysis.cancel()`.

- `web/src/views/Decisions.vue`
  - add independent `decisionFetchSeq` for decisions + evaluation composite reads;
  - only the latest fetch may commit `evaluations` and `evaluationError`;
  - unmount invalidates the secondary sequence before cancelling `useAnalysis`;
  - manual refresh uses `refresh()` and rejects `loading || busy`;
  - Decision state-write handlers reject `busy || loading`;
  - refresh and state-action buttons use the same read/write mutual-exclusion boundary;
  - internal post-write `flush()` still calls `load({})` directly, so a successful write can refresh while its own `busy` flag remains true.

Tests:

- new `web/tests/secondaryReadConcurrency.test.js`: **9 tests**;
- update `web/tests/postJr1WebHardening.test.js` to pin `busy || loading` on Decision write handlers;
- update `web/tests/decisionCancelWiring.test.js` to pin the new unmount wrapper while preserving business-cancel ownership.

## 4. Count boundary

Accepted Batch N-R1 baseline: **296 tests**.

This batch adds exactly **9** tests in `secondaryReadConcurrency.test.js`.
Existing tests are modified but not added/removed.

Expected full Web count: **305/305**.

Any unexplained count drift is a failure signal.

## 5. Commands

Run once, in this order, against the exact tested commit:

```powershell
git fetch origin
git checkout --detach d4a53a08d3121ce2ce8de9ee4e0582b7230835ef
git rev-parse HEAD
git status --short
cd web

node --test tests/secondaryReadConcurrency.test.js
node --test tests/analysisFilterInteractionHardening.test.js
node --test tests/postJr1WebHardening.test.js
node --test tests/decisionCancelWiring.test.js
node --test tests/decisionApprovalInput.test.js
node --test tests/decisionSubmitOwner.test.js
node --test tests/decisionRequiredReason.test.js
node --test tests/rfmSnapshotPinning.test.js
node --test tests/rfmObservationWindow.test.js
node --test tests/rfmMatrixOwnership.test.js
node --test tests/exportFreshnessGuard.test.js

npm run verify
```

## 6. Expected targeted results

- `secondaryReadConcurrency.test.js`: **9/9**
- `analysisFilterInteractionHardening.test.js`: **8/8**
- `postJr1WebHardening.test.js`: **4/4**
- `decisionCancelWiring.test.js`: **3/3**
- `decisionApprovalInput.test.js`: **5/5**
- `decisionSubmitOwner.test.js`: **4/4**
- `decisionRequiredReason.test.js`: **4/4**
- `rfmSnapshotPinning.test.js`: **4/4**
- `rfmObservationWindow.test.js`: **4/4**
- `rfmMatrixOwnership.test.js`: **4/4**
- `exportFreshnessGuard.test.js`: **6/6**

Targeted total: **55/55 PASS**.

## 7. Full gate

`npm run verify` must exit 0.

Expected:
- total = **305**
- passed = **305**
- failed = 0
- cancelled = 0
- skipped = 0
- Vite production build actually executes and passes.

Record actual total/pass/fail/cancelled/skipped, Vite version, transformed module count, build duration, and whether the production build actually ran.

## 8. Required semantic checks

Confirm from the exact tested source:

1. RFM declares `rfmFetchSeq`; every `fetchRfm` captures `const mySeq = ++rfmFetchSeq` before `api.rfm(...)`.
2. RFM missing-snapshot `usersError` write is guarded by `mySeq === rfmFetchSeq`.
3. RFM secondary users-request failure writes `usersError` only when it is non-abort **and** `mySeq === rfmFetchSeq`.
4. RFM unmount increments `rfmFetchSeq` before `analysis.cancel()`.
5. Decisions declares `decisionFetchSeq`; every `fetchDecisions` captures `const mySeq = ++decisionFetchSeq` before `api.decisions(...)`.
6. `evaluations` and `evaluationError` are committed together only inside `if (mySeq === decisionFetchSeq)`.
7. Decision unmount increments `decisionFetchSeq` before calling `cancel()`; business cancel still has exactly one explicit `decisionAction(..., 'cancel', ...)` owner.
8. Manual Decision refresh button uses `refresh`, is disabled by `loading || busy`, and `refresh()` itself rejects that same condition before `load({})`.
9. Internal `flush()` continues to call `load({})` directly and does not route through external `refresh()`; write-success refresh therefore still works while `busy=true`.
10. All Decision state-action buttons are disabled by `loading || busy`; all six state-write handlers reject `busy || loading` before prompt/API.
11. Existing Decision business semantics remain intact: submit owner collection, approve owner/dueDate validation, reject/cancel required reason, evaluation wiring, and payload shapes remain green.
12. Existing RFM snapshot pinning, observation-window, matrix-owner, load reentry and export-subset semantics remain green.
13. Shared CSV stale/empty-subset fail-closed behavior remains green.
14. Full Web gate is 305/305 and Vite production build passes.

## 9. Evidence boundary

A PASS proves Node/source-invariant tests and Vite production build for the exact tested SHA.

It does not prove:
- actual browser click timing or DOM races;
- real HTTP abort/late-response timing;
- actual backend Decision state-machine execution;
- Decision DB persistence/evaluation persistence;
- browser CSV behavior;
- 3307 runtime;
- Spark/Hive/Flume E2E.

## 10. Result persistence

Write the complete result to:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-O-WEB-SECONDARY-READ-CONCURRENCY-RESULT.md
```

Do not modify or repair source/tests/docs during verification.

## 11. PASS rule

PASS only if:
- checked-out SHA exactly equals `d4a53a08d3121ce2ce8de9ee4e0582b7230835ef`;
- targeted suites are **55/55**, zero failed/cancelled/skipped;
- full Web gate is **305/305**, exit 0, zero failed/cancelled/skipped;
- Vite production build actually executes and passes;
- all semantic checks above pass;
- workspace is clean before and after;
- no repair is performed during verification.

Otherwise record FAIL with the exact failure and stop.
