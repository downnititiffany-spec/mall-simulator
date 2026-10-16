# ADR-0003 — Verification artifact persistence

- Status: ACCEPTED
- Date: 2026-09-17
- Supersedes: only the transient-storage parts of the earlier verification workflow; does not relax Code Agent source-write restrictions.

## Context

The project already used `docs/verification/CURRENT_BATCH.md` as the current test entry and `.verify/CURRENT_BATCH_RESULT.md` as the local execution report. However, `CURRENT_BATCH.md` is overwritten by later batches and local `.verify` files can be lost. The user should not have to preserve long test plans or execution reports by copying them through chat.

## Decision

1. Every READY verification batch gets an immutable GitHub plan snapshot:

```text
docs/verification/batches/<Batch-ID>-PLAN.md
```

2. Code Agent reads the latest remote verification protocol before interpreting `VERIFY_CURRENT_BATCH`.

3. Code Agent remains an Execution Tester only. It must not modify source, tests, scripts, design/status docs, test plans, `CURRENT_BATCH.md`, `DEFERRED_TEST_PLAN.md`, `main`, or `feature/v3-development`.

4. Code Agent's only GitHub write exception remains isolated to the dedicated branch `verification-results`, with exactly one result file:

```text
docs/verification/results/<Batch-ID>-RESULT.md
```

5. Code Agent also writes the same report locally to `.verify/CURRENT_BATCH_RESULT.md`.

6. After the user says only `测试完成，测试结果已写入`, ChatGPT reads the GitHub result directly, reviews it, records the accepted result under `feature/v3-development:docs/verification/results/`, updates the verification ledger, closes the current batch, and continues development.

## Consequences

- Test requirements survive `CURRENT_BATCH.md` reuse.
- Test reports survive local workspace loss and chat truncation.
- The user only forwards `VERIFY_CURRENT_BATCH` to the Code Agent and later reports completion; long test instructions/results no longer need to pass through chat.
- Code Agent remains isolated from development branches while still being able to persist execution evidence.
- This remains a governance-level path restriction, not a credential-level GitHub directory ACL.
