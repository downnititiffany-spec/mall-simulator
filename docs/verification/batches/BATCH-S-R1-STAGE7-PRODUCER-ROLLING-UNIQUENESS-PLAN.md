# BATCH-S-R1-STAGE7-PRODUCER-ROLLING-UNIQUENESS — Verification Plan

> 状态：READY
> Exact source/test SHA：`5f20c37784a4d28e3e452a9ea5aa75cd7ac3447e`
> Branch：`feature/v3-development`
> RunId：`stage7q1_20260918_152245`
> Predecessor：Batch S declared-scope PASS + downstream rolling duplicate defect

## 1. Purpose

Re-run the exact producer chain after the Outbox same-JVM serialization fix and require a clean completed rolling file:

`generator :8092 → mall :8090 → order/pay/refund → Outbox → rolling JSONL`

This batch is not allowed to pass merely because generation_run is SUCCESS.

## 2. Mandatory PASS criteria

All original Batch S criteria still apply:

- generation SUCCESS;
- success_count = 600;
- failed_count = 0;
- real createOrder / pay / refund exist;
- no current-run journal FAILED rows;
- no new Outbox failed IDs;
- no new pending above the historical baseline;
- current-run order/pay/refund IDs all correlate to rolling events.

New handoff criteria:

- rolling `duplicateEventIdCount = 0`;
- rolling `uniqueEventIdCount = lineCount`;
- every JSON line has a non-empty event_id;
- harness exit 0;
- evidence outcome = PASS.

## 3. Safety

Unchanged:

- 3307 only; no 3306 fallback;
- mall/generator credentials only from ignored credref files;
- token process-only;
- stock reset only through protected admin HTTP;
- no direct business DML;
- 8090/8092 must be free.

## 4. Exact execution

Run from interactive PowerShell because the managed coding-tool console cannot keep both long-lived child JVMs alive:

~~~powershell
cd "D:\Develop_code\GraduationProject-wt\v3-dev"

git switch --detach 5f20c37784a4d28e3e452a9ea5aa75cd7ac3447e

git rev-parse HEAD

mvn -f .\mall-simulator\pom.xml -DskipTests package
mvn -f .\synthetic-data-generator\pom.xml -DskipTests package

pwsh -NoProfile -File .\scripts\stage7-producer-isolated.ps1 -RunId stage7q1_20260918_152245 -Confirm

$BatchSR1Exit = $LASTEXITCODE

git switch feature/v3-development

"BATCH_S_R1_EXIT=$BatchSR1Exit"
~~~

两次 package 都必须在 detach 的 exact SHA 上成功后才能进入真跑；这用于保证 `target/*.jar`
确实包含 `5f20c37` 的 Outbox 串行化修复，而不是沿用前一批次残留的旧可执行 JAR。

## 5. Next gate

Only after S-R1 proves a duplicate-free completed rolling file may Batch T consume that exact file through the existing LocalFile/HTTP ingestion → Spark pipeline path.

Flume/HDFS remains a separate later batch.

