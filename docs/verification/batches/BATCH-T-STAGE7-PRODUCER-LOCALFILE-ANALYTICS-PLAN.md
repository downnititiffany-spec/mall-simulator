# BATCH-T-STAGE7-PRODUCER-LOCALFILE-ANALYTICS — Verification Plan

> 状态：**EXECUTED / BLOCKED_PLATFORM_EXIT_UNDIAGNOSED**
> Exact source/test SHA：`cbc41919df79bba20e1f91fe1724061ae151d12e`
> Branch：`feature/v3-development`
> RunId：`stage7q1_20260918_152245`
> Predecessor：`BATCH-S-R1-STAGE7-PRODUCER-ROLLING-UNIQUENESS`

## 1. Purpose

验证设计 V3 §8.1 的 LocalFile 三程序接力：

`generator HTTP → mall transaction/Outbox → completed rolling JSONL → LocalFile ingestion → Landing → Spark ODS/DWD/DWS/ADS → quality → metric publish`

本批**不使用 golden fixture**，也不重新生成“手工清洗后的测试文件”。输入必须是 S-R1 真跑留下的、证据已证明 event_id 唯一的完整 rolling 文件。

Flume/HDFS 不在本批范围，后续单独验证，避免把 LocalFile 三程序链与 Flume 环境故障域混在一起。

## 2. Hard prerequisite

S-R1 必须先 PASS，且 producer evidence 同时满足：

- outcome = PASS；
- duplicateEventIdCount = 0；
- uniqueEventIdCount = lineCount；
- rolling 文件存在且位于该 producer attempt 的 `mall-landing/events`；
- rolling 文件只有一个 source_system = `mock-mall`；
- Batch T 首版只接受一个完成 rolling 文件。

任一不满足，T harness 在启动 analytics 前 exit 5。

## 3. Business date

Batch T 禁止继续沿用 R4 golden fixture 的固定 `2026-09-01`。

`scripts/stage7-localfile-e2e.ps1` 会重新解析真实 rolling 文件的 `event_time`：

- 必须只有一个业务日；
- `businessTime = <实际业务日>T00:00:00`；
- 再把该值交给现有 `stage7-http-isolated.ps1`。

这避免“9 月 18 日真实商城事件却按 9 月 1 日 Pipeline 业务日计算”的假绿。

## 4. Handoff reconciliation

T harness 重新读取实文件，不只相信 producer JSON：

- 每行必须可解析；
- event_id 非空；
- 实文件不得出现重复 event_id；
- 实文件行数必须与 producer evidence lineCount 一致；
- SHA256 写入 T evidence。

analytics 侧完成后必须满足：

- child HTTP harness exit 0 / outcome PASS；
- ingestion noNewData = false；
- ingestion recordCount = producer sourceLineCount；
- ingestion quarantineCount = 0；
- Pipeline status = SUCCESS。

因此“producer 文件有 N 行、analytics 只吃了 N-k 行”不能静默通过。

## 5. Safety

- 3307 only；不回退 3306；
- analytics meta/metric 仍使用 Q-R1 run-scoped 双库；
- analytics 两个密码只从当前 PowerShell Process env 读取，不进命令行、不回显；
- 8091 已有监听时现有 HTTP harness 会拒绝复用；
- input 必须位于当前 worktree 与 exact producer attempt 下；
- 不改质量阈值，不跳过 Spark，不使用 Fake executor。

## 6. Prepared validation

在 exact T harness 提交前已验证：

- `stage7-localfile-e2e.ps1 -DryRun`：exit 0 / zero-I/O；
- `AnalyticsIsolationScriptsContractTest`：9/9 PASS；
- default fresh：analytics **1035 MATCH** / mall **14 MATCH** / generator **111 MATCH**；
- 唯一红仍是已登记 manifest patrol。

## 7. Execution

S-R1 已 PASS，CURRENT_BATCH 已可切到 T。

必须在仍持有 `V25_IT_META_PASSWORD` 与 `V25_IT_METRIC_PUBLISH_PASSWORD` 的交互式 PowerShell 中，针对 exact T SHA 执行。先在 detach 的 exact SHA 重新构建 analytics platform 与 Spark JAR，避免复用旧 target 运行物：

~~~powershell
cd "D:\Develop_code\GraduationProject-wt\v3-dev"

git switch --detach cbc41919df79bba20e1f91fe1724061ae151d12e
git rev-parse HEAD

mvn -f .\analytics-server\pom.xml -DskipTests package
mvn -f .\spark-jobs\pom.xml -DskipTests package

pwsh -NoProfile -File .\scripts\stage7-localfile-e2e.ps1 -RunId stage7q1_20260918_152245 -Confirm

$BatchTExit = $LASTEXITCODE

git switch feature/v3-development

"BATCH_T_EXIT=$BatchTExit"
~~~

T harness 默认读取 S-R1 的 producer latest evidence；执行前会重新核对 exact producer attempt、clean handoff 字段、物理文件行数/唯一性/source_system/业务日。

## 8. PASS boundary

PASS 证明当前本机：

`generator → mall → Outbox → duplicate-free rolling file → LocalFile ingestion → Spark → quality → metric publish`

它仍**不证明**：

- Flume Spooldir → File Channel → HDFS Sink；
- REMOTE_CLUSTER；
- 浏览器 E2E；
- 真实 LLM provider。

