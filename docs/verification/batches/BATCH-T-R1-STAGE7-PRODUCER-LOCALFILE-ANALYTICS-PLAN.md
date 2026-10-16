# BATCH-T-R1-STAGE7-PRODUCER-LOCALFILE-ANALYTICS — Verification Plan

> 状态：**READY**
> Exact source/test SHA：`81d8f938deb3a5e73e75e1e7bbc3082dd90585b8`
> Branch：`feature/v3-development`
> RunId：`stage7q1_20260918_152245`
> Predecessor：Batch T / `BLOCKED_PLATFORM_EXIT_UNDIAGNOSED`
> 修订（2026-09-19）：被测 SHA 由 `2acee3d` 更新为 `81d8f93`——T-R1 前置缺陷修复「质量规则 ADS_STAGING_PRESENT v2：合法空态暂存分区 ≠ 发布缺失」（追加式 V29，D-019）已提交并三档实测（spark 312/312；default fresh analytics 1036 / mall 14 / generator 111；isolated 60/60，fresh runId `tir1iso_20260919_093537`，其中 analytics-schema Flyway 在全新 3307 meta 库应用至 version v29 成功）。该修复属缺陷修复而非阈值放水，用户已批准；本计划其余契约不变。口令通道注记：原会话进程环境口令已丢失，执行时由幂等 prep 重跑（`it-prepare-isolation.ps1`，ALTER USER 语义）以进程内新生成口令重置 run 账号，口令仍只走 PowerShell Process env、不落盘不进命令行。

## 1. Purpose

Repeat the exact clean producer rolling → analytics chain with platform-process diagnostics enabled.

The purpose is twofold:

1. if the chain succeeds, close the transient platform-exit incident;
2. if 8091 disappears again, capture the actual platform exit code and last process-resource snapshot so the controller can classify the cause instead of guessing.

## 2. Pinned producer input

Do **not** use the mutable producer latest pointer for this rerun.

Pin:

`target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/stage7-producer-result.json`

That evidence already proves:

- lineCount = 1011;
- uniqueEventIdCount = 1011;
- duplicateEventIdCount = 0;
- physical rolling SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`;
- source_system = mock-mall;
- businessDate = 2026-09-18.

## 3. Safety and exact runtime

- 3307 only;
- no 3306 fallback;
- same Q-R1 run-scoped analytics meta/metric databases;
- secrets only from current PowerShell Process env;
- 8091 must be free;
- fresh LocalFile attempt / fresh HTTP attempt / fresh Pipeline idempotency key;
- exact analytics platform and Spark JARs rebuilt from the detached T-R1 SHA;
- no quality threshold change;
- no Fake executor;
- no reuse of Pipeline runId 5.

## 4. Exact execution

Run in the interactive PowerShell that still owns `V25_IT_META_PASSWORD` and `V25_IT_METRIC_PUBLISH_PASSWORD`:

~~~powershell
cd "D:\Develop_code\GraduationProject-wt\v3-dev"

git switch --detach 81d8f938deb3a5e73e75e1e7bbc3082dd90585b8
git rev-parse HEAD

mvn -f .\analytics-server\pom.xml -DskipTests package
mvn -f .\spark-jobs\pom.xml -DskipTests package

pwsh -NoProfile -File .\scripts\stage7-localfile-e2e.ps1 -RunId stage7q1_20260918_152245 -ProducerEvidence ".\target\v25-it\stage7q1_20260918_152245\producer\attempt-20260918_195657_077\stage7-producer-result.json" -Confirm

$BatchTR1Exit = $LASTEXITCODE

git switch feature/v3-development

"BATCH_T_R1_EXIT=$BatchTR1Exit"
~~~

## 5. PASS criteria

Same Batch T contract:

- physical producer input still 1011 unique / 0 duplicate;
- businessDate = 2026-09-18;
- ingestion recordCount = 1011;
- quarantineCount = 0;
- noNewData = false;
- fresh Pipeline run reaches SUCCESS;
- quality gate PASS;
- metric publish PASS;
- outer harness exit 0 / outcome PASS.

## 6. If platform exits again

Do not classify from the console line alone.

The HTTP evidence must now contain:

- platform.pid;
- platform.hasExited;
- platform.exitCode;
- platform.lastAliveAt;
- platform.lastWorkingSetBytes;
- platform.lastPrivateMemoryBytes;
- platform.lastHandleCount;
- platform.lastPollError;
- last known pipeline stage/status.

The controller will use those fields plus Spark/platform logs to decide production FAIL vs BLOCKED_ENV vs harness issue.

