# BATCH-J-WEB-RFM-SNAPSHOT-PINNING — Verification Plan

## 1. Batch identity

- Batch ID: `BATCH-J-WEB-RFM-SNAPSHOT-PINNING`
- Branch context: `feature/v3-development`
- Exact tested commit: `172d80b06ac45c0c27942a4e11007916090c4254`
- Scope: S3-77 RFM 页面跨接口 snapshot 一致性；验证 `/analysis/rfm` 先固定快照后，`/analysis/users` 必须携带相同 `snapshotId`，缺主快照时跳过第二请求，避免 ACTIVE 切换造成混快照。
- Risk: low-medium, Web request wiring only; no backend/API semantics, DB, state machine, auth, AI SQL, or migration changes.

## 2. Preconditions

Code Agent must:

1. `git fetch origin`;
2. checkout detached exact SHA `172d80b06ac45c0c27942a4e11007916090c4254`;
3. verify `git rev-parse HEAD` exactly matches;
4. record `git status --short` before and after;
5. never modify source/tests/docs/scripts or any development branch.

## 3. S3-77 targeted verification

Run:

```powershell
cd web
node --test tests/rfmSnapshotPinning.test.js
```

Expected: **4/4 PASS**.

Confirm all of the following:

- `/analysis/users` receives `{ snapshotId: rfm.snapshotId }` from the already-decoded RFM response;
- old unpinned `api.users({}, { signal })` wiring is absent;
- if RFM response has no `snapshotId`, lifecycle/preference request is skipped rather than independently resolving ACTIVE;
- page response context remains owned by the primary RFM response; lifecycle/preference only fill their own aggregate sections.

## 4. RFM regression

Run:

```powershell
node --test tests/rfmMatrixOwnership.test.js tests/rfmObservationWindow.test.js
```

Expected: **8/8 PASS**.

This must prove the snapshot-pinning change did not regress:

- backend-owned eight-group `rfmMatrix` category ownership;
- fallback to real `rfmSegments` only;
- observation-window display and CSV fields from backend `periodStart/periodEnd`.

## 5. Web full gate

Run:

```powershell
npm run verify
```

Expected test count: approximately **251** (actual runner count is authoritative); require:

- failed = 0;
- cancelled = 0;
- Vite production build actually executes and PASSes;
- no new tracked workspace changes after execution.

## 6. Explicitly unverified

This batch does not prove:

- real browser rendering;
- real HTTP race during ACTIVE snapshot switch;
- real DB snapshot content;
- Java default/spark/isolated/3307 gates;
- Spark/Hive/Flume E2E.

Do not claim those as accepted from this batch.

## 7. Result persistence

Write complete result to both:

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-J-WEB-RFM-SNAPSHOT-PINNING-RESULT.md
```

The GitHub result commit may contain only that single allowed result file.

## 8. PASS rule

Overall PASS only if:

- exact SHA matches;
- S3-77 = 4/4 PASS;
- RFM regression = 8/8 PASS;
- Web full gate has zero failure/cancellation;
- Vite build actually executes and PASSes;
- no new regression or tracked workspace change appears.
