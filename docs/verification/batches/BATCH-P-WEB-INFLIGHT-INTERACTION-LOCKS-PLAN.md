# BATCH-P-WEB-INFLIGHT-INTERACTION-LOCKS — Verification Plan

## 1. Purpose

Verify the next coherent low-risk Web interaction cluster after accepted Batch O.

This batch closes three in-flight UI consistency gaps without changing backend/API/DB/state-machine semantics:

1. Sales local sort/pagination cannot mutate the visible page while a new date-range request is loading.
2. AI decision-draft form fields cannot continue changing while the already-frozen draft payload is being created.
3. Pipeline manual refresh, trigger and retry actions use the same read/write mutual-exclusion boundary while preserving internal post-write refresh.

## 2. Exact tested commit

`2f3e79f676e1b614fe9a57e71e7ecad68106a51f`

Branch context: `feature/v3-development`.

Accepted predecessor:
- `BATCH-O-WEB-SECONDARY-READ-CONCURRENCY`
- tested SHA `d4a53a08d3121ce2ce8de9ee4e0582b7230835ef`
- PASS
- Web full gate baseline: **305/305**.

## 3. Scope

Production changes:

- `web/src/views/Sales.vue`
  - pagination buttons are disabled while `loading`;
  - `toggleSort()` rejects `loading` before mutating sort/page state.

- `web/src/views/AiAssistant.vue`
  - draft title/action/metric/direction/owner fields are disabled while `draftBusy`;
  - existing pre-request payload freezing and AI query/draft mutual exclusion remain unchanged.

- `web/src/views/Pipeline.vue`
  - business-date/runtime-profile inputs and trigger/retry actions use `loading || busy`;
  - manual refresh uses a dedicated `refresh()` handler that rejects `loading || busy`;
  - `runOnce()` and `retry()` reject `busy || loading` before writes;
  - internal successful write refresh remains direct `load()`, so its own `busy=true` does not block result refresh.

Test changes:

- `analysisFilterInteractionHardening.test.js`: +1 test for Sales loading-time sort/page lock.
- `aiDraftQueryConcurrency.test.js`: +1 test for five draft fields locked during `draftBusy`.
- existing Pipeline characterization tests are updated to pin `loading || busy` and external `refresh()` without adding/removing test cases.

No backend API, Decision state-machine contract, DB/Flyway, auth/security, AI SQL, 3307, Spark/Hive/Flume code changes are part of this batch.

## 4. Count boundary

Accepted Batch O baseline: **305 tests**.

This batch adds exactly:
- Sales interaction guard: +1
- AI draft field-lock guard: +1

Expected full Web count: **307/307**.

Any unexplained count drift is a failure signal.

## 5. Commands

Run once, in this order, against the exact tested commit:

```powershell
git fetch origin
git checkout --detach 2f3e79f676e1b614fe9a57e71e7ecad68106a51f
git rev-parse HEAD
git status --short
cd web

node --test tests/analysisFilterInteractionHardening.test.js
node --test tests/aiDraftQueryConcurrency.test.js
node --test tests/aiAskCancellation.test.js
node --test tests/pipelineOperationIdentity.test.js
node --test tests/pipelineRetryHandling.test.js
node --test tests/pipelinePage.test.js
node --test tests/pipelineLocalBusinessDate.test.js

npm run verify
```

## 6. Expected targeted results

- `analysisFilterInteractionHardening.test.js`: **9/9**
- `aiDraftQueryConcurrency.test.js`: **6/6**
- `aiAskCancellation.test.js`: **5/5**
- `pipelineOperationIdentity.test.js`: **6/6**
- `pipelineRetryHandling.test.js`: **4/4**
- `pipelinePage.test.js`: **6/6**
- `pipelineLocalBusinessDate.test.js`: **5/5**

Targeted total: **41/41 PASS**.

## 7. Full gate

`npm run verify` must exit 0.

Expected:
- total = **307**
- passed = **307**
- failed = 0
- cancelled = 0
- skipped = 0
- Vite production build actually executes and passes.

Record actual total/pass/fail/cancelled/skipped, Vite version, transformed module count, build duration, and whether the production build actually ran.

## 8. Required semantic checks

Confirm from the exact tested source:

1. Sales `from/to` and load button remain disabled during `loading`; `load()` still rejects reentry before resetting page/requesting.
2. Sales `toggleSort()` rejects `loading` before changing sort/page.
3. Sales previous/next buttons are disabled by `loading || page-boundary`.
4. Sales export semantics remain unchanged: page-level `exportable` and `sortedRows`.
5. AI `createDraft()` still freezes `const payload = draftPayload.value` before setting `draftBusy=true` and before the API await.
6. AI draft title/action/metric/direction/owner fields are all disabled while `draftBusy`.
7. Existing AI query/draft mutual exclusion, explicit ask cancellation, and history concurrency guards remain green.
8. Pipeline business-date/runtime-profile inputs and trigger/retry/manual-refresh UI share `loading || busy`.
9. Pipeline external `refresh()` rejects `loading || busy` before `load()`.
10. Pipeline `runOnce()` and `retry()` reject `busy || loading` before entering write work.
11. Pipeline `runOnce()` still freezes business date/runtime profile before the first await and reuses one operationId for sourceDataVersion + idempotency key.
12. Pipeline successful trigger/retry still call internal `load()` directly while `busy=true`; no external-refresh guard blocks post-write refresh.
13. Existing Pipeline context/source mapping, retry failure handling, input batch display, and local business-date semantics remain green.
14. Full Web gate is 307/307 and Vite production build passes.

## 9. Evidence boundary

A PASS proves Node/source-invariant tests plus Vite production build for the exact tested SHA.

It does not prove:
- real browser click/typing timing;
- real HTTP races;
- actual Pipeline backend execution/idempotency;
- AI decision-draft backend state-machine/persistence;
- DB writes or 3307 runtime;
- Spark/Hive/Flume E2E.

## 10. Result persistence

Write the complete result to:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-P-WEB-INFLIGHT-INTERACTION-LOCKS-RESULT.md
```

Do not modify or repair source/tests/docs during verification.

## 11. PASS rule

PASS only if:
- checked-out SHA exactly equals `2f3e79f676e1b614fe9a57e71e7ecad68106a51f`;
- targeted suites are **41/41**, zero failed/cancelled/skipped;
- full Web gate is **307/307**, exit 0, zero failed/cancelled/skipped;
- Vite production build actually executes and passes;
- all semantic checks above pass;
- workspace is clean before and after;
- no repair is performed during verification.

Otherwise record FAIL with the exact failure and stop.
