# BATCH-T-STAGE7-PRODUCER-LOCALFILE-ANALYTICS — Controller Review

> Overall: **BLOCKED_PLATFORM_EXIT_UNDIAGNOSED**
> Exact tested SHA: `cbc41919df79bba20e1f91fe1724061ae151d12e`
> RunId: `stage7q1_20260918_152245`
> LocalFile attempt: `attempt-20260918_200117_762`
> HTTP/analytics attempt: `attempt-20260918_200118_208`
> Harness exit: **7**

## 1. Exact input handoff passed

Batch T consumed the exact S-R1 producer result:

- producer attempt = `attempt-20260918_195657_077`;
- producer rolling SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`;
- sourceLineCount = **1011**;
- uniqueEventIdCount = **1011**;
- duplicateEventIdCount = **0**;
- source_system = `mock-mall`;
- businessDate derived from real event_time = `2026-09-18`.

The LocalFile/HTTP ingestion then succeeded:

- batchId = 7;
- status = `SUCCESS`;
- recordCount = **1011**;
- quarantineCount = **0**;
- errorCount = **0**;
- fileCount = 1;
- noNewData = **false**;
- acceptedBytes = 455814.

Therefore producer rolling → LocalFile ingestion handoff is proven for this exact physical file.

## 2. Pipeline actually started

Pipeline `runId=5` was created with a fresh attempt-scoped identity.

Platform log proves real Spark execution progressed through:

- INIT_SCHEMA → spark-submit pid 69888;
- LOAD_ODS → pid 77116;
- BUILD_DWD / dim → pid 38036;
- BUILD_DWD / behavior → pid 85588;
- BUILD_DWD / trade → pid 69236;
- BUILD_DWS / usw → pid 47328.

The first five Spark log files were physically produced. The platform reached BUILD_DWS and logged the USW child launch at 20:03:06.

## 3. Failure observed

The next HTTP poll failed with:

`由于目标计算机积极拒绝，无法连接。 (127.0.0.1:8091)`

Observed evidence:

- platform Spring Boot PID = 65220;
- platform started normally and listened on 8091;
- platform stderr file is empty;
- no orderly Spring shutdown appears in platform stdout;
- no Java `hs_err_pid*.log` was found in the worktree;
- no Windows crash dump for this PID was found in the accessible user crash-dump location;
- BUILD_DWS Spark native temp artifacts continued to appear for several seconds after the USW launch, proving the child JVM actually started.

The old harness only persisted the HTTP exception. It did **not** persist the platform process `HasExited / ExitCode` or last resource snapshot.

Therefore this batch cannot distinguish:

- external console/process termination such as Windows `0xC000013A`;
- platform JVM explicit exit;
- native/process-level failure;
- another process-level environment termination.

It is not valid to label BUILD_DWS production logic FAILED from this evidence.

## 4. Classification

Overall = **BLOCKED_PLATFORM_EXIT_UNDIAGNOSED**.

Already accepted:

- S-R1 producer rolling uniqueness PASS;
- physical rolling file re-read PASS;
- business-date derivation PASS;
- LocalFile ingestion 1011/0/noNewData=false PASS;
- real Pipeline creation and Spark execution through BUILD_DWS start proven.

Still unresolved:

- why platform PID 65220 disappeared while BUILD_DWS was running;
- whether the same exit is reproducible.

## 5. Diagnostic correction

Successor code SHA `2acee3d5f34c4ee4734a88e940f29ee0021def85` enhances only the Stage 7 verification harness:

- persists platform PID / HasExited / ExitCode;
- records last-alive timestamp;
- records last working-set/private-memory/handle-count snapshot;
- persists pipeline state after every successful poll;
- distinguishes `PLATFORM_EXITED_DURING_PIPELINE` from generic HTTP exception;
- retries up to three poll errors only when the platform process is still alive.

No production Pipeline/Spark/quality logic is changed.

Validation:

- Stage 7 HTTP DryRun: exit 0 / zero-I/O;
- `AnalyticsIsolationScriptsContractTest` remains PASS;
- default fresh: analytics **1035 MATCH** / mall **14 MATCH** / generator **111 MATCH**;
- only the historical manifest patrol remains red.

