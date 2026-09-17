# BATCH-L-WEB-INTERACTION-CONSISTENCY — Verification Plan

## 1. Purpose

Verify the next accumulated low-risk Web interaction-consistency cluster after accepted Batch K.

This batch focuses on two adjacent frontend concerns only:

- Ops page admin-action reentry plus per-export-subset eligibility/handler guards.
- AI Assistant mutual exclusion between question execution and decision-draft creation, without changing AI SQL, decision state-machine, auth, backend contract, or database semantics.

## 2. Exact tested commit

`395eead89d78d0a40665f2b985002371943f5eb0`

Branch context: `feature/v3-development`.

Accepted predecessor: `BATCH-K-WEB-CONSISTENCY-HARDENING` / tested SHA `bd7226b8e11e661b2b10a2cd37ab84eb7da98ddf` / PASS.

## 3. Scope

### Ops interaction hardening

1. `createUser`, `toggle`, and `resetPwd` must reject `busy` reentry at function entry; `resetPwd` must reject before opening `prompt()`.
2. Metrics export must have handler-level `metricExportable` fail-closed in addition to disabled UI and shared CSV download guard.
3. Pipeline export eligibility must require both page-level export-ready state and a non-empty pipeline subset; handler and button must use the same predicate.
4. AI query-audit and model-call-audit exports must each use their own non-empty subset eligibility, not merely whole-page readiness; handler must re-check the selected subset before download.

### AI query / draft concurrency hardening

5. `createDraft()` must reject `draftBusy` reentry before evaluating payload or calling `api.decisionCreate`.
6. `openDraft()` / `closeDraft()` must not mutate draft-form state while draft creation is in flight.
7. During `draftBusy`, main question input/button, recommended questions, history refill, `handleAskAction()`, `ask()` and `askPreset()` must all refuse starting or rewriting a new question interaction.
8. Existing S3-63 behavior remains intact: when the AI query itself is `busy`, the main button must still remain usable as explicit cancellation; cancellation must invalidate and abort the in-flight request.

No backend API, decision state-machine semantics, auth/security, AI SQL generation/validation, DB/Flyway, 3307, Spark/Hive/Flume, or real-browser E2E behavior is modified by this batch.

## 4. Commands

Run once, in this order, against the exact tested commit:

```powershell
git fetch origin
git checkout --detach 395eead89d78d0a40665f2b985002371943f5eb0
git rev-parse HEAD
git status --short
cd web
node --test tests/opsInteractionHardening.test.js
node --test tests/aiDraftQueryConcurrency.test.js
node --test tests/aiAskCancellation.test.js
node --test tests/exportFreshnessGuard.test.js
npm run verify
```

## 5. Expected targeted results

- `opsInteractionHardening.test.js`: 5/5 PASS.
- `aiDraftQueryConcurrency.test.js`: 5/5 PASS.
- `aiAskCancellation.test.js`: 5/5 PASS.
- `exportFreshnessGuard.test.js`: 6/6 PASS.

Targeted total: **21/21 PASS**.

## 6. Full gate

`npm run verify` must exit 0. All Node tests must pass with zero failed/cancelled/skipped tests, and Vite production build must actually execute and PASS.

Expected full Web test count: **271/271**.

Reason: accepted Batch K baseline was 261/261; this batch adds 10 new tests (`opsInteractionHardening` 5 + `aiDraftQueryConcurrency` 5) and updates, but does not add/remove, the existing 5 `aiAskCancellation` cases.

Any unexplained test-count drift is a failure signal.

## 7. Required semantic checks

Confirm from tested source:

1. `createUser`, `toggle`, `resetPwd` each begin with `if (busy.value) return`; for `resetPwd`, this guard appears before `prompt(...)`.
2. `exportMetrics()` checks `metricExportable.value` before constructing export data.
3. `pipelineExportable` requires `exportable.value && pipelineTable.value.rows.length > 0`; both button disabled state and `exportRuns()` use it.
4. `aiHistoryExportable` and `aiCallExportable` separately require page-ready plus their own non-empty row subsets; each audit button binds its matching predicate.
5. `exportAudit(which)` determines the selected audit kind and returns before `exportAnalysisCsv(...)` when that selected subset is not exportable.
6. `createDraft()` starts with `if (draftBusy.value) return` and only later calls `api.decisionCreate(...)`.
7. `openDraft()` rejects missing evidence anchor or `draftBusy`; `closeDraft()` rejects `draftBusy`.
8. Main question input/button, recommended questions, and history refill are all disabled during `draftBusy`.
9. `handleAskAction()`, `ask()`, and `askPreset()` all reject `draftBusy`; `ask()` still rejects ordinary `busy` reentry.
10. Existing explicit cancel behavior remains: during AI-query `busy`, `handleAskAction()` calls `cancelAsk()`, which increments `askSeq`, aborts the controller, clears busy/result, and prevents old response write-back.

## 8. Evidence boundary

A PASS proves Node unit/source-invariant tests plus Vite production build for the exact SHA.

It does **not** prove:

- real browser double-click timing;
- real prompt/dialog behavior;
- real HTTP race timing;
- admin permission enforcement;
- actual admin-user mutation persistence;
- decision state-machine/database writes;
- AI provider/Text-to-SQL runtime behavior;
- 3307 or Spark/Hive/Flume E2E.

## 9. Result persistence

Write complete result to:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-L-WEB-INTERACTION-CONSISTENCY-RESULT.md
```

Do not modify source, tests, plans, docs, Git configuration, `main`, or `feature/v3-development` during verification.

## 10. PASS rule

PASS only if:

- checked-out SHA exactly equals `395eead89d78d0a40665f2b985002371943f5eb0`;
- all four targeted suites pass with the expected counts;
- `npm run verify` exits 0 with **271/271** tests, zero failures/cancellations/skips;
- Vite production build actually executes and passes;
- all §7 semantic checks are satisfied;
- workspace is clean before and after execution.

Otherwise record FAIL with the exact new failure and do not repair it.