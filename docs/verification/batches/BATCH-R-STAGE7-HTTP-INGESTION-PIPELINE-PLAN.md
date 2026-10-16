# BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE — Verification Plan

> 状态：READY
> Tested source/test commit：`2714ffb801d08c7046a61da4652f086910a59183`
> Branch：`feature/v3-development`
> Predecessor：`BATCH-Q-R1-STAGE7-ISOLATED-RUNTIME-PREFLIGHT` / PASS / isolated 60/60

## 1. Purpose

在 Q-R1 已证明真实 WSL MySQL 3307 双库隔离与 MySQL 写入 IT 后，推进 Stage 7 的下一道最小真实链：

`HTTP login → runtime-profile test/activate → HTTP ingestion → HTTP pipeline`

本批只启动 analytics platform `:8091`。输入使用仓库冻结的
`tests/golden-dataset/events/golden-20260901.jsonl`，复制到本 runId 的独立 landing。
不启动模拟商城 `:8090`、不启动生成器 `:8092`，避免把“三程序可用性”与“平台 HTTP/pipeline
链是否可跑”混成一个故障域。

## 2. Exact baseline

- Exact source/test SHA：`2714ffb801d08c7046a61da4652f086910a59183`
- RunId：`stage7q1_20260918_152245`
- 入口：`scripts/stage7-http-isolated.ps1`
- 3307 run-scoped databases：
  - `stage7q1_20260918_152245_analytics_meta`
  - `stage7q1_20260918_152245_analytics_metric`
- analytics accounts 继续使用 Q-R1 已创建的 run-scoped 受限账号。

后续仅文档提交不得被冒充为本批被测代码 SHA。

## 3. Safety boundary

1. **绝不写 3306。**
2. 平台 meta / metric publish / metric read 三个 JDBC URL 全部强制 `127.0.0.1:3307`。
3. 只允许 RunId 派生的 analytics meta / metric 库。
4. analytics 密码只从当前 PowerShell 进程环境
   `V25_IT_META_PASSWORD` / `V25_IT_METRIC_PUBLISH_PASSWORD` 读取；
   无 Password CLI 参数、不回显。
5. landing / Spark warehouse / Derby metastore / metric staging 全部位于
   `target/v25-it/<RunId>/http`。
6. `8091` 若已有监听，入口拒绝复用，避免误打其它平台实例。
7. 入口只停止它自己通过 `Start-Process -PassThru` 启动的 platform PID，不扫杀其它 Java。
8. HTTP 业务写入只发生在本 run-scoped meta/metric 库；失败不得回退正式库。

## 4. Preflight already proven before READY

`2714ffb` 上已完成：

- `stage7-http-isolated.ps1 -DryRun`：exit 0，明确“不建目录、不启动 Java、不发 HTTP、不连接数据库”。
- 在 MCP secret 环境缺失时真执行探针：exit 5，在启动 Java 前 fail-closed。
- `AnalyticsIsolationScriptsContractTest`：**7/7 PASS**。
- platform-app 当前 worktree package：**BUILD SUCCESS**。
- default 量数：analytics `1030 → 1033` 的 +3 全部来自上述脚本结构守卫 `4 → 7`；
  更新基线后 fresh 收口 `1033 MATCH / mall 13 MATCH / generator 110 MATCH`，
  唯一失败仍是既有 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`。
- 当前环境只读检查：3307 LISTENING、8091 FREE、platform JAR PRESENT、spark-jobs JAR PRESENT。
- `spark-submit.cmd --version`：exit 0。
- `HADOOP_HOME` / `winutils.exe`：当前仍 MISSING。此事实不提前改判；若真实 pipeline 因此失败，
  应记录为 Batch R 的真实运行时结果，不得补假工具或跳过 Spark 判据。

## 5. Exact execution

必须在**仍持有 Q-R1 analytics 两个密码环境变量的同一个 PowerShell**执行。

为保证精确被测 SHA，先把工作树临时切到被测 commit；执行后恢复分支：

~~~powershell
git status --short
git switch --detach 2714ffb801d08c7046a61da4652f086910a59183

pwsh -NoProfile -File .\scripts\stage7-http-isolated.ps1 -RunId $RunId -Confirm

$BatchRExit = $LASTEXITCODE
git switch feature/v3-development
"BATCH_R_EXIT=$BatchRExit"
~~~

执行期间不得打印两个 analytics 密码；入口自身只打印 SET/MISSING。

## 6. PASS / FAIL / BLOCKED_ENV

### PASS

仅当：

1. platform 从 3307 run-scoped 双库启动成功；
2. admin HTTP login 成功；
3. runtime profile 的所有 applicable check 通过并成功 activate；
4. HTTP ingestion `noNewData=false` 且 `recordCount>0`；
5. HTTP pipeline 到达 `SUCCESS`；
6. 入口 exit 0；
7. `target/v25-it/<RunId>/http/stage7-http-result.json` 的 `outcome=PASS`；
8. 无 3306 回退。

### FAIL

代码/API/契约/测试逻辑导致的真实失败记 FAIL，并保留 stage/error/evidence。

### BLOCKED_ENV

仅当外部运行环境本身阻断已正确配置的真实链，例如：

- `spark-submit` 子进程因当前 Windows Hadoop 环境缺失而不能运行；
- 必须的本地 Spark/Hadoop 运行依赖不存在。

不得通过跳过 runtime-profile Spark check、伪造 winutils、回退 3306 或改成 Fake executor 来换绿。

## 7. Evidence boundary

即使 PASS，本批也只证明：

- analytics platform 的真实 HTTP 登录/运行档案链；
- run-scoped landing 的真实 ingestion；
- 当前本机 LOCAL pipeline 的真实提交与完成。

它不证明：

- 模拟商城/生成器三程序 E2E；
- Flume；
- REMOTE_CLUSTER；
- 生产 Hive metastore/HDFS；
- 浏览器 E2E；
- 真实 LLM provider。

