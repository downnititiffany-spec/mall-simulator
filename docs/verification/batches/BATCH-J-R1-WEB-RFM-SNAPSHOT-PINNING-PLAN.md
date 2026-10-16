# BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING — Verification Plan

## 1. Purpose

Retest Batch J after restoring the already accepted S3-76 observation-window source contract while retaining S3-77 RFM snapshot pinning.

## 2. Exact tested commit

`30d5713edae8c90e8849ea51cccaa269d80d3f00`

Branch context: `feature/v3-development`.

## 3. Scope

- S3-77: `/analysis/users` must be pinned to the `snapshotId` selected by the primary `/analysis/rfm` response.
- S3-76 regression: observation-window display and CSV must continue using the accepted named `periodStart` / `periodEnd` / `periodText` derivation and backend-provided values only.
- RFM matrix ownership must remain unchanged.

No backend API, DB, state-machine, auth, AI SQL, Spark, Hive or Flume changes are in this batch.

## 4. Commands

Run in this order, once, against the exact commit:

```powershell
git fetch origin
git checkout --detach 30d5713edae8c90e8849ea51cccaa269d80d3f00
git rev-parse HEAD
git status --short
cd web
node --test tests/rfmSnapshotPinning.test.js
node --test tests/rfmObservationWindow.test.js
node --test tests/rfmMatrixOwnership.test.js
npm run verify
```

## 5. Expected targeted results

- `rfmSnapshotPinning.test.js`: 4/4 PASS.
- `rfmObservationWindow.test.js`: 4/4 PASS.
- `rfmMatrixOwnership.test.js`: 4/4 PASS.

## 6. Full gate

`npm run verify` must exit 0. All Node tests must pass with zero failed/cancelled tests, and the Vite production build must actually execute and PASS. Record actual test count and Vite version/module count/build duration.

## 7. Required semantic checks

Confirm from the tested source:

1. `api.users({ snapshotId: rfm.snapshotId }, { signal })` is used and the old `api.users({}` form is absent.
2. When the primary RFM response has no `snapshotId`, the secondary users request is skipped.
3. The returned page context `snapshotId` remains owned by the primary RFM response.
4. `periodStart` and `periodEnd` are copied from the RFM response only.
5. `periodText` shows `start 至 end` only when both values exist; otherwise `未提供`.
6. CSV uses `periodStart.value || ''` and `periodEnd.value || ''`; no current-date calculation is introduced.
7. RFM matrix categories remain backend-owned; the frontend must not reintroduce a fixed eight-category list.

## 8. Result persistence

Write the complete result to:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-J-R1-WEB-RFM-SNAPSHOT-PINNING-RESULT.md
```

Do not modify source, tests, plans, docs, Git configuration, `main`, or `feature/v3-development`.

## 9. PASS rule

PASS only if all targeted suites pass, the complete Web test suite has zero failures/cancellations, Vite build actually executes and passes, the exact SHA matches, and the workspace remains clean. Otherwise record FAIL with the exact new failure; do not repair it.
