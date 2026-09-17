# BATCH-I-R2-WEB-PIPELINE-IDENTITY-RFM-WINDOW — Verification Plan

## 1. Purpose

Re-run Batch I after the second guard correction. R1 proved the first correction was checkout-platform-dependent under CRLF (`core.autocrlf=true`). R2 must verify that the guard is line-ending independent while production behavior remains unchanged.

## 2. Exact baseline

- Branch context: `feature/v3-development`
- Tested commit: `4ae3b4c72cea9f01f3880c085bacab75f541c2b6`
- Production code delta versus Batch I: **none**.
- Test-only delta: `web/tests/pipelineOperationIdentity.test.js` now normalizes LF/CRLF with `split(/\r?\n/)` before stripping `//` line comments.

## 3. Execution

```powershell
git fetch origin
git checkout --detach 4ae3b4c72cea9f01f3880c085bacab75f541c2b6
git rev-parse HEAD
git status --short
cd web
node --test tests/pipelineOperationIdentity.test.js
node --test tests/rfmObservationWindow.test.js
node --test tests/pipelineRetryHandling.test.js tests/pipelineLocalBusinessDate.test.js tests/rfmMatrixOwnership.test.js
npm run verify
```

Do not change source, tests, scripts, docs, or the tested branch. Execute the whole batch once.

## 4. Expected targeted results

- S3-75 `pipelineOperationIdentity.test.js`: **4/4 PASS**.
  - `runOnce` keeps the internal `busy` fail-closed guard.
  - Exactly one executable `Date.now()` is counted after line-ending normalization and line-comment stripping.
  - `sourceDataVersion` and `Idempotency-Key` reuse the same `operationId`.
  - success refresh / visible failure / finally release remain intact.
- S3-76 `rfmObservationWindow.test.js`: **4/4 PASS**.
- Regression set: **13/13 PASS**.

## 5. Full Web gate

`npm run verify` must complete both phases:

1. all Node tests green; expected total remains approximately **247**, but record the actual total;
2. Vite production build actually executes and **PASS**es.

`failed=0`, `cancelled=0`, and no new regression are required.

## 6. CRLF-specific evidence

Record the worktree line-ending context when available (`git ls-files --eol web/tests/pipelineOperationIdentity.test.js web/src/views/Pipeline.vue` and `git config --get core.autocrlf`). The test must pass regardless of LF or CRLF checkout. Do not alter Git configuration to make the test pass.

## 7. Unverified areas

This is still a Web source/test/build batch. It does not validate real browser interaction, real `/pipeline-runs` HTTP/idempotency, real `/analysis/rfm` HTTP/DOM, Java default/spark/isolated/3307, or Spark/Hive/Flume E2E.

## 8. Result persistence

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-I-R2-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md
```

## 9. PASS rule

PASS only when all of the following hold:

- exact SHA matches `4ae3b4c72cea9f01f3880c085bacab75f541c2b6`;
- S3-75 = 4/4;
- S3-76 = 4/4;
- regression = 13/13;
- full Web test suite has zero failures/cancellations;
- Vite production build was actually executed and passed;
- no tracked workspace changes were produced;
- result is persisted locally and to the authorized `verification-results` path.
