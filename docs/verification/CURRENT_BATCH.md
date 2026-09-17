# Current Verification Batch

> 状态：READY
> Batch M 首轮因一条过时 characterization test 出现 `FAIL_NEW_REGRESSION`；生产语义未回退，已以测试修复后的精确 SHA 建立 R1 复测批次。用户只需把 `VERIFY_CURRENT_BATCH` 转发给 Code Agent；Code Agent 按永久计划一次执行整批测试，不修代码。

## Current batch

- **Batch ID**：`BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION`
- **Tested commit**：`7748caf8b2628bd47ed5075db62ec2cd26a42fe6`
- **Branch context**：`feature/v3-development`
- **Accepted predecessor**：`BATCH-L-WEB-INTERACTION-CONSISTENCY` / `395eead89d78d0a40665f2b985002371943f5eb0` / PASS
- **Permanent plan**：`docs/verification/batches/BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION-PLAN.md`
- **Expected accepted result**：`docs/verification/results/BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`
- **Raw Code Agent result**：`verification-results:docs/verification/results/BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`

## Failed attempt retained

- **Batch**：`BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION`
- **Tested commit**：`61776daf52cfcd396325d7bbdf56e890f1731224`
- **Verdict**：`FAIL_NEW_REGRESSION`
- **Raw result**：`verification-results:docs/verification/results/BATCH-M-WEB-PIPELINE-PRODUCT-INTERACTION-RESULT.md`
- **Raw result commit**：`0c46b9c79ba82252ef1e8a961e9def0eb32fabba`
- **Failure**：`pipelineLocalBusinessDate.test.js` 仍要求旧的 `businessDate.value + 'T00:00:00'` 源码文本，而生产实现已先冻结为 `requestedBusinessDate`；Batch M 其余定向 28/28 与计划 §7 十项语义检查均满足，但 full gate 为 273/274，Vite build 因测试失败未执行。

原失败结果保持原样，不覆盖、不改写。

## R1 scope summary

R1 **不改生产代码**，只修正旧本地业务日期守卫，使其验证真实语义而不是旧变量名：

- Pipeline 仍在第一个异步等待前冻结 `businessDate` / `runtimeProfileId`；
- `businessTime` 仍由用户确认的本地业务日拼接 `T00:00:00`，不退回 UTC 日期截断；
- Pipeline busy 锁定、operation identity/retry 行为继续回归；
- Products 当前页 `pageExportable`、分页交互 loading 锁定继续回归；
- shared CSV freshness guard 继续回归。

No backend API, DB/Flyway, auth/security, AI SQL, decision state-machine semantics, 3307, or Spark/Hive/Flume behavior changes are part of R1.

## Execution rule

Code Agent must execute the R1 permanent plan against the exact tested commit. Do not test branch HEAD by name, do not modify source/tests/docs, and do not repair failures during verification.

Required targeted total: **33/33**. Required full gate: `cd web && npm run verify` with expected **274/274** tests and an actually executed successful Vite production build.

## Acceptance boundary

A PASS proves Node unit/source-invariant tests and Vite production build for the exact R1 SHA. It does not elevate real browser timing, real HTTP races, actual ingestion/pipeline orchestration, backend idempotency/state-machine behavior, database writes, 3307, or Spark/Hive/Flume E2E to verified status.

## User action

`VERIFY_CURRENT_BATCH`
