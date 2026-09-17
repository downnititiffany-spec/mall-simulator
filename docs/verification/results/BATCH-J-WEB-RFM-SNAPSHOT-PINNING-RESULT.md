# BATCH-J-WEB-RFM-SNAPSHOT-PINNING — Accepted Result

- Overall: `FAIL_NEW_REGRESSION`
- Tested commit: `172d80b06ac45c0c27942a4e11007916090c4254`
- Raw Code Agent result: `verification-results:docs/verification/results/BATCH-J-WEB-RFM-SNAPSHOT-PINNING-RESULT.md`
- Raw result commit: `d28676d6a808e5e7db2f86594a9589106e87b71b`

## Accepted facts

- S3-77 snapshot pinning: 4/4 PASS.
- RFM regression: 6/8; `rfmObservationWindow.test.js` 2 failures.
- Full Web gate: 251 total / 249 pass / 2 fail; `npm run verify` exit 1.
- Vite build was not executed because `npm test` failed.
- No tracked workspace changes after execution.

## Failure classification

The two failures are regressions against the already accepted S3-76 source guards, not evidence that the displayed observation-window semantics changed. Batch J renamed/inlined the previously accepted `periodStart` / `periodEnd` / `periodText` derivation in `Rfm.vue`, so the source-level regression guards no longer matched even though the runtime intent remained equivalent.

The chosen correction is to preserve the already accepted named observation-window derivation while retaining S3-77 snapshot pinning. We do not weaken or rewrite the prior S3-76 guards merely to make the batch green.

Batch J therefore remains a permanent FAIL record. A separate R1 batch must prove both S3-77 and the restored S3-76 regression contract, and must execute the full Web gate including Vite build.
