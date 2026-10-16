# BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY — Verification Plan

## 1. Purpose

Repair Batch N's two stale source-characterization guards without changing production semantics, then re-run the original Batch N checks plus the post-N low-risk Web cluster that now exists on the development branch.

Batch N failed reproducibly because two previously accepted tests still required the old page-level `exportable` literal after production intentionally moved Overview/RFM export handlers to stricter actual-subset predicates. This R1 updates only those two tests.

## 2. Exact tested commit

`0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d`

Branch context: `feature/v3-development`.

Failed predecessor:
- `BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY`
- tested SHA `58411f92a8e5591c435f5597143896f4fadac020`
- result: FAIL_NEW_REGRESSION
- raw result: `verification-results:docs/verification/results/BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md`

Accepted predecessor before Batch N:
- `BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION`
- `7748caf8b2628bd47ed5075db62ec2cd26a42fe6`
- PASS

## 3. Repair scope

R1 production changes: **none**.

Only these two test files change relative to development HEAD `dcc15740a70bdeb12b65b18d44863b4807f702ca`:

- `web/tests/rfmMatrixOwnership.test.js`
  - keep proving chart and CSV share `segmentRows`;
  - explicitly prove `segmentExportable = exportable && segmentRows.length > 0`;
  - require `doExport()` to fail-closed on `segmentExportable`, not the obsolete page-level `exportable` literal.

- `web/tests/postJr1WebHardening.test.js`
  - Sales remains pinned to page-level `exportable`;
  - Overview now explicitly proves `metricExportable = exportable && cards.length > 0`;
  - Overview `doExport()` must fail-closed on `metricExportable`.

No `Rfm.vue`, `Overview.vue`, backend API, DB/Flyway, auth/security semantics, AI SQL, decision-state semantics, 3307, Spark/Hive/Flume code is changed by the R1 repair.

## 4. Why the full-gate expectation is 296, not 282

The development branch advanced after Batch N froze. The R1 repair is applied on top of the current branch rather than rewriting history.

Between Batch N tested SHA `58411f92...` and pre-repair HEAD `dcc15740...`, the following low-risk Web cluster was added:

- Login: lock username/password while login is in flight;
- AiAssistant: history refresh sequence/Abort protection against late-response overwrite;
- BaseChart: resize ECharts after reactive height changes;
- Ops: whole-page refresh and admin writes use loading/busy mutual exclusion;
- corresponding tests add **14** cases net.

Therefore:
- Batch N full count: 282;
- post-N added tests: +14;
- R1 modifies existing tests but does not add/remove test cases;
- expected R1 full count: **296**.

Any unexplained drift from 296 is a failure signal.

## 5. Commands

Run once, in this order, against the exact tested commit:

```powershell
git fetch origin
git checkout --detach 0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d
git rev-parse HEAD
git status --short
cd web

node --test tests/analysisFilterInteractionHardening.test.js
node --test tests/exportFreshnessGuard.test.js
node --test tests/rfmMatrixOwnership.test.js
node --test tests/rfmObservationWindow.test.js
node --test tests/rfmSnapshotPinning.test.js
node --test tests/metricDefinitionVersionDisplay.test.js
node --test tests/postJr1WebHardening.test.js

node --test tests/loginInteractionHardening.test.js
node --test tests/aiHistoryConcurrency.test.js
node --test tests/baseChartResize.test.js
node --test tests/opsInteractionHardening.test.js

npm run verify
```

## 6. Expected targeted results

Original Batch N group:
- `analysisFilterInteractionHardening.test.js`: 8/8
- `exportFreshnessGuard.test.js`: 6/6
- `rfmMatrixOwnership.test.js`: 4/4
- `rfmObservationWindow.test.js`: 4/4
- `rfmSnapshotPinning.test.js`: 4/4
- `metricDefinitionVersionDisplay.test.js`: 4/4
- `postJr1WebHardening.test.js`: 4/4

Post-N inherited cluster:
- `loginInteractionHardening.test.js`: 3/3
- `aiHistoryConcurrency.test.js`: 5/5
- `baseChartResize.test.js`: 4/4
- `opsInteractionHardening.test.js`: 7/7

Targeted total: **53/53 PASS**.

## 7. Full gate

`npm run verify` must exit 0.

Expected:
- total = **296**
- passed = **296**
- failed = 0
- cancelled = 0
- skipped = 0
- Vite production build actually executes and passes.

Record actual total/pass/fail/cancelled/skipped, Vite version, transformed module count, build duration, and whether `web/dist` was refreshed by this run.

## 8. Required semantic checks

Confirm from the exact tested source:

1. All original Batch N §7 checks 1–9 still hold.
2. RFM `segmentExportable = exportable && segmentRows.length > 0`; button and `doExport()` use that same predicate; CSV rows remain `segmentRows`.
3. Overview `metricExportable = exportable && cards.length > 0`; button and `doExport()` use that same predicate; CSV rows remain derived from `cards`.
4. Sales still uses page-level `exportable` for its primary trend dataset.
5. The two repaired tests no longer require obsolete literals, and instead pin the stricter current predicates.
6. Login in-flight input locking remains present.
7. AI history refresh keeps sequence + Abort protection and unload cancellation.
8. BaseChart reacts to height changes by resizing after DOM update.
9. Ops whole-page refresh and admin writes remain mutually exclusive through `loading || busy`.
10. Shared CSV freshness/stale/empty-row fail-closed behavior remains green.
11. Full Web gate is 296/296 and Vite production build passes.

## 9. Evidence boundary

A PASS proves source/unit invariants and Vite production build for the exact tested SHA. It does not prove real browser timing, real HTTP races, browser CSV behavior, backend runtime/DB writes, 3307, or Spark/Hive/Flume E2E.

## 10. Result persistence

Write the complete result to:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md
```

Do not repair code/tests/docs during verification.

## 11. PASS rule

PASS only if:
- checked-out SHA exactly equals `0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d`;
- targeted suites are **53/53**, zero failed/cancelled/skipped;
- full Web gate is **296/296**, exit 0, zero failed/cancelled/skipped;
- Vite production build actually executes and passes;
- semantic checks above pass;
- workspace is clean before and after;
- no repair is performed during verification.

Otherwise record FAIL with the exact failure and stop.
