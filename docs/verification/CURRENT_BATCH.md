# Current Verification Batch

> 状态：PASS
> 历史 Batch Q 保持 `BLOCKED_ENV`；新的 Q-R1 已在真实 WSL MySQL 3307 上取得 **60/60 PASS**。Stage 7 的下一道真实 HTTP ingestion → pipeline 门可以进入单独验证，但本批本身不证明该链已通过。

## Current batch

- **Batch ID**：`BATCH-Q-R1-STAGE7-ISOLATED-RUNTIME-PREFLIGHT`
- **Exact tested code baseline**：`9b2f18faf00872f364e26a9980b767854e96f9fa`
- **Branch context**：`feature/v3-development`
- **Accepted predecessor**：historical `BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT` / `BLOCKED_ENV`
- **Permanent plan**：`docs/verification/batches/BATCH-Q-R1-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-PLAN.md`
- **Accepted controller review**：`docs/verification/results/BATCH-Q-R1-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md`
- **RunId**：`stage7q1_20260918_152245`
- **Overall**：`PASS`
- **Post-verification baseline registration**：`a496434`

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

Current registered isolated baseline after Q-R1:

- mall **30/30**
- generator **19/19**
- analytics **11/11** = IsolationGuard 6 + MetricAds 2 + MetricPublisher 3
- total **60/60**
- runner exit 0
- all three analytics IT classes must actually execute
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

## Controller review / current status

- Historical Batch Q remains unchanged as `BLOCKED_ENV`; its result file is still the authority for that earlier run.
- Q-R1 recovered the 3307 environment without falling back to 3306 and completed governed run-scoped preparation.
- Q-R1 real evidence: schema 1/1, mall 30/30, generator 19/19, analytics 11/11, total 60/60, all runner exits 0.
- The connected MCP still intentionally filters the interactive shell's analytics secret environment variables. Therefore Q-R1 used two governed lower-level runner invocations against the same SHA/runId/instance; the controller then updated the top-level unified entrypoint in `a496434` so future isolated/all runs include the 11-test analytics lane automatically.
- The next Stage 7 HTTP ingestion → pipeline chain is now eligible for its own batch; it is not included in Q-R1 PASS.
