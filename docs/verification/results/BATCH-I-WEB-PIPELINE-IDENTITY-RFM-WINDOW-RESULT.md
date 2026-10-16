# BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW — Recorded Result

- Status: **FAIL_NEW_REGRESSION**
- Executor: Code Agent (Execution Tester)
- Tested commit: `b5e4972bdb272ca476be20b1638b0c78d905438b`
- Raw result branch: `verification-results`
- Raw result commit: `a60f5d67b4da26bfa3b81d40d38f9da6e88c6f29`
- Raw result path: `docs/verification/results/BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md`

## Verified evidence

### S3-75 — pipeline operation identity

- `pipelineOperationIdentity.test.js`: **3/4 PASS**.
- The failed assertion counted the literal `Date.now()` inside a `//` source comment together with executable code.
- Read-only diagnosis confirmed the executable `runOnce()` path contains exactly one `Date.now()` call and reuses one `operationId` for both `sourceDataVersion` and the `Idempotency-Key` argument.
- This is therefore a newly introduced guard-test false positive, not evidence of a production behavior regression.

### S3-76 — RFM observation window

- `rfmObservationWindow.test.js`: **4/4 PASS**.
- `periodStart` / `periodEnd` are consumed from the backend response, displayed only when both endpoints exist, and exported without frontend date reconstruction.

### Regression

- `pipelineRetryHandling`: 4/4 PASS
- `pipelineLocalBusinessDate`: 5/5 PASS
- `rfmMatrixOwnership`: 4/4 PASS
- Total: **13/13 PASS**

### Web full gate

- Node tests: **246/247 PASS**, 1 failed.
- `npm run verify`: exit 1.
- Vite production build: **NOT EXECUTED**, because `npm test` failed before `npm run build`.

## Decision

Batch I is not accepted as PASS because its explicit gate requires S3-75 4/4 and a successful full `npm run verify` including Vite build. The failure is classified as a test-guard defect. A corrected guard must be re-run on a new exact SHA before S3-75/S3-76 are marked fully verified.
