# BATCH-R5-STAGE7-HARNESS-CLEANUP — Verification Plan

> 状态：EXECUTED
> Exact SHA：`3070911577f6f5054f8d0ff859a3b4fefec03cfa`
> Predecessor：`BATCH-R4-STAGE7-HTTP-INGESTION-PIPELINE`

## 1. Purpose

Verify only the post-run process-tree cleanup defect found after R4's business-chain PASS.

This batch does **not** rerun MySQL, HTTP ingestion, Spark warehouse jobs, quality checks, or metric publish.
Those business semantics are already proven by R4 at `4ef4d93`.

## 2. Exact verification

From the exact committed Stage 7 script:

1. parse `scripts/stage7-http-isolated.ps1` with the PowerShell AST;
2. extract the real `Stop-OwnedProcessTree` function definition;
3. start a temporary parent PowerShell process that itself starts descendant PowerShell processes;
4. call the extracted cleanup function with the parent PID;
5. require:
   - at least one descendant PID was captured;
   - parent process no longer exists;
   - every captured descendant process no longer exists.

No analytics secret is needed.

## 3. PASS criteria

- exact tested HEAD is `3070911577f6f5054f8d0ff859a3b4fefec03cfa`;
- at least one descendant is captured;
- parent alive = false;
- alive descendants = empty;
- self-test exit = 0.

## 4. Boundary

R5 closes only the harness cleanup concern. It does not substitute for R4's business-chain evidence.

