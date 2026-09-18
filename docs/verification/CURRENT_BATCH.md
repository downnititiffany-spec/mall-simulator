# Current Verification Batch

> 状态：BLOCKED_ENV
> Batch Q 已执行并由总控复核为环境阻塞；在 3307 管理员认证恢复并完成 Q-R1 之前，不开放真实 HTTP ingestion → pipeline 链。

## Current batch

- **Batch ID**：`BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT`
- **Exact code baseline**：`2f3e79f676e1b614fe9a57e71e7ecad68106a51f`
- **Branch context**：`feature/v3-development`
- **Accepted predecessor**：`BATCH-P-WEB-INFLIGHT-INTERACTION-LOCKS` / `2f3e79f676e1b614fe9a57e71e7ecad68106a51f` / PASS
- **Permanent plan**：`docs/verification/batches/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-PLAN.md`
- **Expected result**：`docs/verification/results/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md`
- **Raw Code Agent result**：`verification-results:docs/verification/results/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md`
- **Raw result commit**：`2c32a45fedfba65ab7c5510b1662631b2d0adce2`
- **Accepted controller review**：`docs/verification/results/BATCH-Q-STAGE7-ISOLATED-RUNTIME-PREFLIGHT-RESULT.md`
- **RunId**：`stage7q_20260918_1100`
- **Overall**：`BLOCKED_ENV`

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

## Controller review / current blocker

- 原始执行在真实 preparation 前发现 3307 不可用，未运行 isolated 55 条，因此不能 PASS。
- 后续总控复查已确认本机仍有既有 MySQL 8.0.41 binary 与独立 datadir，且 3307 可被诊断性启动；原始报告中“server/datadir 不存在”属于较早时点的环境观察，不再作为当前权威根因。
- 当前治理脚本的实际阻塞点是：启动 coding-tools-mcp 的进程环境中 `V25IT_ADMIN_PWD` 未设置，root TCP 免密认证被正确拒绝。
- 不允许把管理员密码写进 Git/命令日志，也不允许回退到 3306。
- 当前开发 HEAD 已因不依赖 3307 的并行测试加固前进到 `0f773220ad6a29d4b839e52d29b5b5fb6124d94b`；该提交不是本 Batch Q 的被测基线。

环境恢复后，不改写本历史批次；创建 `BATCH-Q-R1`，固定届时最新基线重新验证 3307 isolated 55/55。
