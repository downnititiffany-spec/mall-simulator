# Current Verification Batch

> 状态：READY
> Batch T 已证明 1011 行真实 producer rolling 完整进入 LocalFile ingestion，并真实推进 Spark 到 BUILD_DWS；但 platform 在 DWS 期间从 8091 消失，旧 harness 未记录退出码。当前唯一可执行批次是 T-R1。

## Current batch

- **Batch ID**：`BATCH-T-R1-STAGE7-PRODUCER-LOCALFILE-ANALYTICS`
- **Exact source/test baseline**：`81d8f938deb3a5e73e75e1e7bbc3082dd90585b8`（2026-09-19 修订：T-R1 前置缺陷修复 ADS_STAGING_PRESENT v2 / D-019）
- **Branch context**：`feature/v3-development`
- **RunId**：`stage7q1_20260918_152245`
- **Permanent plan**：`docs/verification/batches/BATCH-T-R1-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-PLAN.md`
- **Batch T result**：`docs/verification/results/BATCH-T-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`
- **Pinned S-R1 producer evidence**：`target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/stage7-producer-result.json`
- **Overall**：`READY`

## Accepted Batch T facts

Exact tested SHA `cbc41919df79bba20e1f91fe1724061ae151d12e`:

- producer input physical SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`;
- sourceLineCount = 1011;
- uniqueEventIdCount = 1011;
- duplicateEventIdCount = 0;
- businessDate = 2026-09-18;
- source_system = mock-mall;
- ingestion SUCCESS;
- ingestion recordCount = 1011;
- quarantineCount = 0;
- errorCount = 0;
- noNewData = false.

Pipeline `runId=5`真实启动并推进：

- INIT_SCHEMA launched;
- LOAD_ODS launched;
- BUILD_DWD dim launched;
- BUILD_DWD behavior launched;
- BUILD_DWD trade launched;
- BUILD_DWS usw launched.

随后 8091 连接被拒绝。旧 evidence 只有 HTTP exception，没有 platform ExitCode，因此 Batch T 记为：

`BLOCKED_PLATFORM_EXIT_UNDIAGNOSED`

这不是 BUILD_DWS 业务失败结论。

## T-R1 diagnostic gate

Successor SHA `2acee3d` 增强验证 harness；被测 SHA 现为 `81d8f93`（在 harness 诊断之上追加 T-R1 前置缺陷修复「质量规则 ADS_STAGING_PRESENT v2：合法空态暂存分区 ≠ 发布缺失」，追加式 V29，见 D-019）：

- platform PID / HasExited / ExitCode；
- lastAliveAt；
- last working-set / private-memory / handle count；
- 每次成功 poll 的最新 Pipeline stage/status；
- platform 已退出时明确写 `PLATFORM_EXITED_DURING_PIPELINE`；
- platform 仍存活时才允许最多三次瞬态 poll 重试。

Default fresh 收口为：

- analytics **1036 MATCH**（基线 1035→1036）；
- mall **14 MATCH**；
- generator **111 MATCH**；
- spark 档 **312/312**（JDK8，基线 308→312 补记）；
- isolated 档 fresh runId `tir1iso_20260919_093537` **60/60**（mall 30 + generator 19 + analytics 11），analytics-schema Flyway 在全新 3307 meta 库上应用至 version v29 成功；
- 唯一红为既有 manifest patrol。

## PASS

若 T-R1 直接 PASS，则关闭本次 platform-exit 事件并继续后续 Stage 7。

若 platform 再次退出，则必须以新 evidence 的 exitCode/resource snapshot 分类，不再猜测。

