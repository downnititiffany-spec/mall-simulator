# BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW — Accepted Result

- **Overall**: `FAIL_NEW_REGRESSION`
- **Tested commit**: `2cf150b82168904fe6500089cf699d986c374514`
- **Raw Code Agent result**: `verification-results:docs/verification/results/BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md`
- **Raw result commit**: `dd25fe6974c09c49287b99fe9ec5d33ca0dafeae`

## Summary

- S3-75 `pipelineOperationIdentity.test.js`: **3/4 PASS, 1 FAIL**.
- S3-76 `rfmObservationWindow.test.js`: **4/4 PASS**.
- Regression set: **13/13 PASS**.
- Web full test count: **247 total / 246 pass / 1 fail**.
- `npm test` returned non-zero, therefore `npm run build` was not executed and Vite production build is **unverified for this batch**.

## Failure classification

The failing assertion is the same S3-75 guard introduced in Batch I. Production behavior is not the failing surface: `runOnce()` has one executable `Date.now()` call and reuses one `operationId` for both `sourceDataVersion` and `Idempotency-Key`.

The R1 test correction remained checkout-platform-dependent. On the execution machine `core.autocrlf=true`, the worktree uses CRLF. The helper split only on `\n`, leaving `\r` at the end of each line; the line-comment regex `/\/\/.*$/` therefore did not reliably strip the comment before the `Date.now()` text count. This made the guard pass on LF-only text but fail on CRLF checkout.

## Disposition

- Keep this batch as **FAIL**; do not rewrite it as PASS.
- No production rollback is required because the failure is in the new textual guard, not the S3-75 production behavior.
- Follow-up R2 must make the guard line-ending independent and re-run S3-75, S3-76, the regression set, the full Web gate, and Vite production build.
