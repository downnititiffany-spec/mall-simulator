# Current Verification Batch

> 状态：READY
> Batch P 已正式 PASS 并归档，Web 基线为 307/307。当前进入 Stage 7 的第一道真实运行时门：先恢复并验证 3307 隔离运行能力，再进入真实 HTTP ingestion → pipeline 链。

## Current batch

- **Batch ID**：`BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT`
- **Exact code baseline**：`2f3e79f676e1b614fe9a57e71e7ecad68106a51f`
- **Branch context**：`feature/v3-development`
- **Accepted predecessor**：`BATCH-P-WEB-INFLIGHT-INTERACTION-LOCKS` / `2f3e79f676e1b614fe9a57e71e7ecad68106a51f` / PASS
- **Permanent plan**：`docs/verification/batches/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-PLAN.md`
- **Expected result**：`docs/verification/results/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md`
- **Raw Code Agent result**：`verification-results:docs/verification/results/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md`
- **RunId**：`stage7q_20260918_1100`

## Why this batch exists

The project is now crossing from source/unit/build hardening into Stage 7 runtime integration.

The repository already has governed isolation tooling:
- `scripts/it-prepare-isolation.ps1`;
- `scripts/run-tests.ps1 -Suite isolated`;
- `scripts/run-isolated-tests.ps1`.

Before running the longer real HTTP/pipeline chain, Batch Q first proves that the current environment can safely create a fresh run-scoped target on **WSL MySQL 3307** and execute the current isolated integration lane.

## Safety boundary

- **No writes to 3306.**
- Never fall back from 3307 to 3306.
- Fresh run-scoped databases/accounts only.
- Root/admin use is allowed only inside the governed isolation-preparation script for 3307 object creation.
- Restricted generated accounts are used by the tests.
- No manual DROP/CREATE/GRANT outside the script.
- No historical 3307 cleanup in this batch.
- No source/test repair during verification.

If 3307/runtime administration is unavailable, record `BLOCKED_ENV` and stop. That is an environment block, not permission to weaken guards.

## Expected runtime result

Current registered isolated baseline:

- mall **30/30**
- generator **19/19**
- analytics **6/6**
- total **55/55**
- runner exit 0
- `IsolationGuardMySqlIT` must actually execute
- live guard facts must identify port 3307 / isolated instance
- workspace clean before/after

No Web 307/307 rerun is required in Batch Q; Batch P already accepted that source/build baseline.

## Evidence boundary

Batch Q PASS proves current real-MySQL-3307 isolation readiness only.

It does not yet prove:
- three-program HTTP E2E;
- ingestion → pipeline → Spark → publish;
- real Hive/HDFS;
- browser E2E;
- real LLM provider.

Those are later Stage 7 gates.

## User action

`VERIFY_CURRENT_BATCH`
