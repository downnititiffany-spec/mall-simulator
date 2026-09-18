# Current Verification Batch

> 状态：READY
> Batch N 已经由 Code Agent 两次一致复现为 FAIL_NEW_REGRESSION：新增 8 条目标测试全部通过，但两条历史源码守卫仍绑定旧的 `exportable` 字面量，导致定向 29/30、全量 280/282，Vite build 未执行。当前进入 **Batch N-R1**：只同步这两条陈旧测试守卫，不改变 Overview/RFM 生产语义。

## Current batch

- **Batch ID**：`BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY`
- **Tested commit**：`0c7af0dd0f09f4e5c0c097dc70fbe2647da7a54d`
- **Branch context**：`feature/v3-development`
- **Failed predecessor**：`BATCH-N-WEB-ANALYSIS-INTERACTION-CONSISTENCY` / `58411f92a8e5591c435f5597143896f4fadac020` / FAIL_NEW_REGRESSION
- **Accepted predecessor before N**：`BATCH-M-R1-WEB-PIPELINE-PRODUCT-INTERACTION` / `7748caf8b2628bd47ed5075db62ec2cd26a42fe6` / PASS
- **Permanent plan**：`docs/verification/batches/BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY-PLAN.md`
- **Expected accepted result**：`docs/verification/results/BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md`
- **Raw Code Agent result**：`verification-results:docs/verification/results/BATCH-N-R1-WEB-ANALYSIS-INTERACTION-CONSISTENCY-RESULT.md`

## Why R1 exists

Batch N itself implemented the intended stricter export predicates correctly:

- Overview: `metricExportable = exportable && cards.length > 0`;
- RFM: `segmentExportable = exportable && segmentRows.length > 0`.

The new Batch N tests already proved those semantics. The two failures were older characterization tests that still required `if (!exportable.value) return`.

R1 changes only:
- `web/tests/rfmMatrixOwnership.test.js`;
- `web/tests/postJr1WebHardening.test.js`.

Relative to pre-repair development HEAD `dcc15740a70bdeb12b65b18d44863b4807f702ca`, no production file changes are part of the R1 repair.

## Count boundary

The development branch legitimately advanced after Batch N froze. The exact R1 tested SHA includes the post-N low-risk Web cluster already committed before this repair:

- Login in-flight input locking;
- AI history late-response/Abort protection;
- BaseChart reactive height resize;
- Ops refresh/admin-write mutual exclusion;
- net +14 tests.

Therefore R1 expected full gate is **296/296**, not 282/282.

Required targeted suites total **53/53**. Full gate is `cd web && npm run verify`, expected:
- 296 total / 296 pass;
- 0 fail / 0 cancelled / 0 skipped;
- Vite production build actually executes and passes.

## Execution rule

Code Agent must execute the permanent plan against the exact tested commit. Do not test branch HEAD by name. Do not modify or repair source/tests/docs during verification.

The original Batch N raw FAIL result remains preserved on `verification-results` and must not be overwritten.

## User action

`VERIFY_CURRENT_BATCH`
