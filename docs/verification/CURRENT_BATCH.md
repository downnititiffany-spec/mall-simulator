# Current Verification Batch

> 状态：READY
> Batch R 是 Q-R1 60/60 PASS 后的下一道 Stage 7 真实门：run-scoped 3307 上的 HTTP ingestion → pipeline。

## Current batch

- **Batch ID**：`BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE`
- **Exact source/test baseline**：`2714ffb801d08c7046a61da4652f086910a59183`
- **Branch context**：`feature/v3-development`
- **Accepted predecessor**：`BATCH-Q-R1-STAGE7-ISOLATED-RUNTIME-PREFLIGHT` / PASS / 60/60
- **Permanent plan**：`docs/verification/batches/BATCH-R-STAGE7-HTTP-INGESTION-PIPELINE-PLAN.md`
- **RunId**：`stage7q1_20260918_152245`
- **Overall**：`READY`

## Scope

本批只验证 analytics platform 的最小真实 HTTP 链：

1. 在 `8091` 启动当前 platform JAR；
2. JDBC 仅指向 RunId 派生的 3307 analytics meta / metric 双库；
3. HTTP 登录；
4. runtime profile 更新到 run-scoped landing / Spark 路径；
5. runtime profile `test` + `activate`；
6. 把冻结 golden dataset 作为本批独立 landing 输入；
7. `POST /api/v1/ingestion/runs`；
8. `POST /api/v1/pipeline-runs` 并轮询终态；
9. 脱敏证据落在 `target/v25-it/<RunId>/http`。

不启动 8090 模拟商城，不启动 8092 生成器。三程序 E2E 属后续独立门。

## Safety boundary

- **No writes to 3306.**
- 不允许从 3307 回退 3306。
- analytics meta / metric URL 都由 `scripts/stage7-http-isolated.ps1` 强制构造为 3307 run-scoped 库。
- analytics 密码只读当前进程环境，不通过命令行参数，不回显。
- 8091 已有监听则拒绝，防止误打其它实例。
- 只停止本入口自己启动的 platform PID。
- 不修改正式 runtime/profile/source 数据；本批所有 DB 写入只发生在 Q-R1 的 run-scoped 3307 双库。

## Preflight evidence

在置 READY 前已确认：

- HTTP harness DryRun exit 0，零连接/零启动；
- MCP 无 analytics secrets 的真执行探针 exit 5，启动 Java 前 fail-closed；
- `AnalyticsIsolationScriptsContractTest` **7/7 PASS**；
- platform-app package BUILD SUCCESS；
- default fresh：analytics **1033 MATCH**、mall **13 MATCH**、generator **110 MATCH**；
  唯一红仍是既有 manifest patrol；
- 3307 LISTENING；
- 8091 FREE；
- platform JAR / spark-jobs JAR PRESENT；
- `spark-submit.cmd --version` exit 0；
- `HADOOP_HOME` / `winutils.exe` 当前 MISSING。

最后一项必须由真实执行决定影响：如果 pipeline 的真实 Spark 子进程因此失败，按计划分类并保留证据；
不得跳过 Spark check、伪造工具或改 Fake executor 换绿。

## Exact execution

命令及 SHA 切换纪律见永久计划 `5。执行必须来自仍持有
`V25_IT_META_PASSWORD` / `V25_IT_METRIC_PUBLISH_PASSWORD` 的用户 PowerShell。

## PASS criteria

- runtime profile applicable checks 全通过并 activate；
- ingestion `noNewData=false` 且 `recordCount>0`；
- pipeline 终态 `SUCCESS`；
- harness exit 0；
- evidence `outcome=PASS`；
- 无 3306 回退。

若外部 Windows Spark/Hadoop 运行依赖缺失导致真实子进程无法执行，可判 `BLOCKED_ENV`；
代码/API/契约自身失败则判 `FAIL`。

## Evidence boundary

本批不证明模拟商城/生成器三程序 E2E、Flume、REMOTE_CLUSTER、生产 Hive/HDFS、浏览器 E2E 或真实 LLM provider。

