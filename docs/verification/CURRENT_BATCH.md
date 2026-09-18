# Current Verification Batch

> 状态：READY
> R4 已证明 Stage 7 analytics 正向业务链；R5 已证明修复后的 harness cleanup。当前唯一可执行批次是 producer Batch S。

## Current batch

- **Batch ID**：`BATCH-S-STAGE7-PRODUCER-MALL-OUTBOX`
- **Exact source/test baseline**：`b850493b11fb71b17db38a59b1d4196d519168a3`
- **Branch context**：`feature/v3-development`
- **RunId**：`stage7q1_20260918_152245`
- **Permanent plan**：`docs/verification/batches/BATCH-S-STAGE7-PRODUCER-MALL-OUTBOX-PLAN.md`
- **R4 result**：`docs/verification/results/BATCH-R4-STAGE7-HTTP-INGESTION-PIPELINE-RESULT.md`
- **R5 result**：`docs/verification/results/BATCH-R5-STAGE7-HARNESS-CLEANUP-RESULT.md`
- **Overall**：`READY`

## Accepted predecessor evidence

R4 exact business SHA `4ef4d93`:

- ingestion 50 accepted / 0 quarantine;
- Pipeline SUCCESS;
- INIT_SCHEMA / LOAD_ODS / BUILD_DWD / BUILD_DWS / BUILD_ADS / QUALITY_CHECK / PUBLISH_METRIC 全 SUCCESS;
- 22 ADS rows / 14 metric values / active snapshot `S20260901_4`;
- business evidence `outcome=PASS`.

R4 outer shell exit 1 was only a finally cleanup variable collision. R5 on `3070911` separately proved the corrected real process-tree cleanup PASS.

## Batch S scope

`generator :8092 → mall :8090 → order/pay/refund → Outbox → run-scoped rolling JSONL`

Safety:

- 3307 only, no 3306 fallback;
- mall/generator credentials from ignored credref only;
- Mall token process-only;
- 8090/8092 must be free;
- stock repeatability reset only through protected admin HTTP;
- no direct business DML.

PASS requires generation 600/0, operation journal real createOrder/pay/refund, Outbox pending=0, required rolling event types, and journal→rolling IDs correlated to the current run.

## Preflight on current exact baseline

- key generator targeted tests: PASS；
- producer harness DryRun: exit 0 / zero-I/O；
- mall package: BUILD SUCCESS；
- generator package: BUILD SUCCESS；
- default fresh: analytics **1034 MATCH** / mall **13 MATCH** / generator **111 MATCH**；
- only the historical manifest patrol remains red；
- 3307 LISTENING，8090/8092 在真实执行前必须 FREE。

Connected coding-tool command execution cannot be used as the final Batch S host because its managed console interrupts long-lived `Start-Process` child JVMs with Windows `0xC000013A`. The same mall JAR remained alive in a foreground startup probe, so this is an execution-host boundary rather than a service crash. Final Batch S must run from an interactive PowerShell host.

