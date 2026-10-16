# BATCH-K-WEB-CONSISTENCY-HARDENING — Verification Plan

## 1. Purpose

Verify the complete low-risk Web consistency hardening accumulated after accepted J-R1: same-snapshot analysis composition, publisher/context preservation, export freshness fail-closed, empty-export prevention, and decision action reentry protection.

## 2. Exact tested commit

`bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf`

Branch context: `feature/v3-development`.

Accepted predecessor: `BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING` at `30d5713edae8c90e8849ea51cccaa269d80d3f00`.

## 3. Scope

This batch covers the Web code/test commits accumulated after J-R1 through the exact tested commit:

- RFM keeps the publisher/source from the primary `/analysis/rfm` response.
- Behavior selects the primary snapshot from `/analysis/funnel`, pins `/analysis/overview` to the same `snapshotId`, and rejects a mismatched snapshot echo.
- Sales and Overview export handlers fail closed in code, not only through disabled buttons.
- RFM `/analysis/users` aggregation remains pinned to the primary RFM snapshot and rejects any mismatched snapshot echo.
- Decision write actions (`submit`, `approve`, `reject`, `start/complete`, `cancel`, `evaluate`) reject reentry while another write action is busy.
- Shared CSV download layer receives `useAnalysis` view state and rejects `loading`, `stale`, `error`, and `empty` exports before creating a Blob.
- Shared CSV download layer rejects empty export subsets even if another part of the page makes the overall view `ready`.
- Legacy/standalone export callers without `viewState` remain compatible when they provide real rows.
- `exportCsv.js` uses an explicit `.js` import so the helper is directly loadable by the repository's native Node ESM test runner.
- New source/invariant tests cover the complete post-J-R1 hardening cluster.

Regression scope also includes the already accepted J-R1 RFM observation-window and matrix-ownership behavior.

No backend API, database, Flyway, state-machine, auth/security, AI SQL, formal shared-contract semantics, Spark, Hive, or Flume changes are in this batch.

## 4. Commands

Run in this order, once, against the exact commit:

```powershell
git fetch origin
git checkout --detach bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf
git rev-parse HEAD
git status --short
cd web
node --test tests/postJr1WebHardening.test.js
node --test tests/exportFreshnessGuard.test.js
node --test tests/rfmSnapshotPinning.test.js
node --test tests/rfmObservationWindow.test.js
node --test tests/rfmMatrixOwnership.test.js
npm run verify
```

## 5. Expected targeted results

- `postJr1WebHardening.test.js`: 4/4 PASS.
- `exportFreshnessGuard.test.js`: 6/6 PASS.
- `rfmSnapshotPinning.test.js`: 4/4 PASS.
- `rfmObservationWindow.test.js`: 4/4 PASS.
- `rfmMatrixOwnership.test.js`: 4/4 PASS.

## 6. Full gate

`npm run verify` must exit 0. All Node tests must pass with zero failed/cancelled tests, and the Vite production build must actually execute and PASS.

The accepted J-R1 baseline was 251/251 tests. This batch adds exactly 10 new tests and removes no tests, so the expected full-suite count is **261/261**. If the discovered count differs, record the exact reason and treat an unexplained discrepancy as FAIL.

Record the actual Node test count, failed/cancelled/skipped counts, Vite version/module count/build duration, and command exit status.

## 7. Required semantic checks

Confirm from the tested source and test output:

1. Behavior requests Overview with `snapshotId: funnel.snapshotId`; it does not independently resolve another ACTIVE snapshot for the same page composition.
2. Behavior rejects a non-empty Overview `snapshotId` that differs from the Funnel snapshot.
3. RFM page context keeps `source: rfm.source`; the secondary users response cannot replace the primary publisher/source.
4. RFM users aggregation uses `api.users({ snapshotId: rfm.snapshotId }, { signal })`, and a missing or mismatched primary/secondary snapshot fails closed as implemented.
5. The accepted RFM observation-window derivation and backend-owned matrix behavior remain unchanged and green.
6. Sales and Overview `doExport()` handlers independently check `exportable.value` before invoking the shared export helper.
7. `useAnalysis.exportContext` carries the current `viewState`, and `exportAnalysisCsv()` rejects non-ready state or zero-row export data before creating a Blob/download URL.
8. A ready export with real rows remains allowed, including compatible callers that do not use `useAnalysis` and therefore do not provide `viewState`.
9. All six Decision write-action handlers reject `busy` reentry at function entry before prompting or issuing a write API call.
10. `web/src/utils/exportCsv.js` imports `./csv.js`, so `node --test` can import the module under package `type=module` without Vite resolution.

## 8. Evidence boundary

This batch proves Node unit/source-invariant tests and Vite production build only. It does **not** claim browser interaction E2E, real HTTP race behavior, backend decision state-machine behavior, database writes, 3307 behavior, or Spark/Hive/Flume E2E.

## 9. Result persistence

Write the complete result to:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-K-WEB-CONSISTENCY-HARDENING-RESULT.md
```

Do not modify source, tests, plans, docs, Git configuration, `main`, or `feature/v3-development` while executing this verification batch.

## 10. PASS rule

PASS only if the checked-out SHA exactly equals `bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf`, all targeted suites pass, the complete Web suite has zero failures/cancellations with the expected count or a fully explained non-regressive count, Vite build executes and passes, and the verification workspace remains clean. Otherwise record FAIL with the exact new failure; do not repair it.
