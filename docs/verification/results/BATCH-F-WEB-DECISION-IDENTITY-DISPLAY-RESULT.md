# BATCH-F-WEB-DECISION-IDENTITY-DISPLAY — Accepted Result

> Status: PASS
> Executor: Code Agent (Execution Tester)
> Tested commit: `1671d3a8b09c296d70e1dfb74098a3cb1132b977`
> Raw GitHub result branch: `verification-results`
> Raw GitHub result path: `docs/verification/agent-results/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-RESULT.md`
> Raw result commit: `9e10e4e47a1990029b69842f915a91ecce23cdb3`
> Permanent test plan: `docs/verification/batches/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-PLAN.md`

## Accepted evidence

- Exact tested SHA matched and workspace stayed clean.
- S3-67 / V-015: `decisionRowIdentity.test.js` = **3/3 PASS**.
- S3-68 / V-016: `decisionBaselineDisplay.test.js` = **2/2 PASS**.
- Decision regression set = **16/16 PASS**:
  - `decisionApprovalInput.test.js` 5/5;
  - `decisionSubmitOwner.test.js` 4/4;
  - `decisionCancelWiring.test.js` 3/3;
  - `decisionRequiredReason.test.js` 4/4.
- Web full gate: **217/217 PASS**, failed/cancelled/skipped/todo = 0.
- Vite production build: **PASS**, 671 modules transformed.
- New failures: **0**.

## Behavior established at this evidence level

- `decisionRows()` preserves the backend decision `id` without stringifying or fabricating missing identities.
- `Decisions.vue` can therefore use real `d.id` for row identity, evaluation lookup and decision action calls.
- Decision baseline values are formatted once in `decisionRows()` and displayed directly by the page; the old second formatting pass that could turn `2,042.00` into `—` is gone.
- Existing submit/approve/reject/cancel interaction guards remained green.

## Evidence boundary

This batch is Web unit/source-shape + production-build evidence only. It does **not** establish browser/HTTP/state-machine/database E2E acceptance. Still unverified here:

- real browser interaction and `window.prompt`;
- real `/decisions/{id}/submit|approve|reject|cancel|evaluate` HTTP calls;
- backend state transitions and request validation;
- decision/audit database persistence;
- Java default/spark/isolated/3307 and Spark/Hive/Flume chains.

## Canonical raw report

The full Code Agent execution report remains preserved in GitHub on the dedicated result branch at:

```text
verification-results:docs/verification/agent-results/BATCH-F-WEB-DECISION-IDENTITY-DISPLAY-RESULT.md
commit: 9e10e4e47a1990029b69842f915a91ecce23cdb3
```
