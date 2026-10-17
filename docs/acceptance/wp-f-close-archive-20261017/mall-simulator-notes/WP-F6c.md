# WP-F6c 规约：GitHub Actions 首跑双红修复

## 背景（总控 2026-10-17 08:22 巡检亲查，非推测）
PAT 补齐 Workflows 权限后 tests.yml 已触发两轮（run 38981395202 @27537af、run 38986554847 @75d23a5），**两轮 Default 与 Spark 两档全红**。总控下载作业日志逐项定位，两档根因不同：

1. **Spark 档（环境缺件）**：日志实证 `HADOOP_HOME and hadoop.home.dir are unset`（winutils 缺失）→ SourceBChainExecSpec 等真跑 Spark 的套件大面积失败：Tests succeeded 151 / failed 73 / canceled 8，仅跑出 224（基线 380）记 DRIFT。windows-latest 镜像不自带 winutils，本机/用户 Windows 机靠既有 HADOOP_HOME 掩盖了该依赖。
2. **Default 档（巡检测试不可移植）**：全反应堆仅 1 例红——`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`，断言仓库根 `landing/manifests` 冻结名单恰 43 个文件。该目录是 .gitignore 忽略的本机生产落盘，fresh checkout 不存在/不全，断言必然红。其余计数全 MATCH（analytics 1399 / mall 26 / generator 134）。该测试的设计意图（见类注释）是**本机运行时巡检**，与 CI 的 fresh-checkout 语义冲突；其负例两条（新到只报告、目录缺失判红）不依赖真实目录、CI 可跑。

## 任务
1. **Spark 档**：在 `.github/workflows/tests.yml` 的 spark job 中、跑测试前增加 winutils 供给步：下载与 Spark 3.5.x 兼容的 Hadoop 3 winutils.exe 到固定目录，设置 `HADOOP_HOME` 并把其 bin 前置 PATH（PowerShell 步，版本/来源 URL 在注释中注明，需为公开可信来源如 cdarlint/winutils 的 hadoop-3.3.x 版）。只改 workflow，不改 run-tests.ps1 的 spark 口径。
2. **Default 档**：让 `realHistoryOnDiskIsUntouched` 在无真实历史目录的 CI 环境可跳过、本机有目录时仍严格执行——优先方案：以 JUnit 5 `Assumptions` 或 `@EnabledIf` 按「`landing/manifests` 目录存在且非空」门控该单例（目录在则原断言一字不改；在则不许放宽）；禁止改冻结名单数目、禁止删除/弱化断言、禁止把整个测试类排除出 CI（负例两条必须继续在 CI 跑）。若现有测试支持设施已有等价门控惯例（如 RepoRoot/条件注解），优先沿用并在报告中说明。
3. **交付**：改动 + `WP-F6c-REPORT.md`（两处改法、winutils 来源与版本、门控机制说明、为什么不损害本机巡检语义）。

## 边界
- 只用文件读写工具：不跑命令、不连数据库。
- 不改 run-tests.ps1 基线数字，不动 IngestionManifestRuntimePatrolTest 的另两条用例与冻结名单。
- 不碰 3306/Docker/DeepSeek。
- 完成标记：WPF6C-DONE
