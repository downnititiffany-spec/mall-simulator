# WP-F6c-FIX 规约：winutils 下载 URL 版本勘误（总控亲验抓获，2026-10-17 巡检）

## 不合格事实（总控亲验，非推测）

WP-F6c 首轮交付中 `.github/workflows/tests.yml` 的 winutils 供给步选用
`https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.4/bin/` 下载
`winutils.exe` 与 `hadoop.dll`。总控 2026-10-17 亲验：

1. 对上述两个 URL 做 HEAD 请求，**均返回 HTTP 404**；
2. 经 GitHub API 列 `cdarlint/winutils` 仓库根目录：**该仓库根本没有 `hadoop-3.3.4` 目录**，
   3.3.x 线只有 `hadoop-3.3.5` 与 `hadoop-3.3.6` 两个目录；
3. 两目录的 `bin/` 均含 `winutils.exe` 与 `hadoop.dll`；`hadoop-3.3.6` 的两个 raw URL
   HEAD 实测 **200 OK**（winutils.exe Content-Length 119296；hadoop.dll 78848）。

结论：按首轮形态推送，Spark 档会在下载步因 404 直接失败，WP-F6c 不合格。
Default 档的 Java 门控改动已经总控亲跑验收通过（定向 3/3 PASS、零跳过、本机严格执行；
脚本契约 13/13 PASS），**不在本修复轮范围**。

## 修复任务（仅一处，仅一个文件）

只改仓库文件 `.github/workflows/tests.yml` 中 `Set up Hadoop winutils for Spark` 步骤：

1. 版本由 Hadoop **3.3.4 改为 3.3.6**：`$baseUrl` 改为
   `https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.6/bin`，
   注释中的版本与 Source URL 同步改为 3.3.6。
2. 注释勘误（必须写清，供后人核对）：cdarlint/winutils 无 hadoop-3.3.4 目录
   （3.3.x 线仅 3.3.5/3.3.6）；winutils.exe/hadoop.dll 为独立原生辅助二进制，
   与工程 `hadoop-client:3.3.4`（spark-jobs/pom.xml）同属 3.3.x 线，
   取该线最高补丁版 3.3.6 为兼容供给（此为业界通行做法）；Spark 版本仍为 3.5.1。
3. 其余一字不动：步骤位置（JDK8 之后、spark 测试之前）、落盘 `C:\hadoop\bin`、
   `HADOOP_HOME` 经 `$env:GITHUB_ENV` 导出、`bin` 经 `$env:GITHUB_PATH` 前置、
   下载后存在性断言（缺一即 throw）、`Run spark test suite` 行。

## 交付

- 上述改动（`git diff` 应仅含 tests.yml 且仅限该步骤内部）。
- 报告 `WP-F6c-FIX-REPORT.md`（notes 目录）：写明不合格事实（404 实证、无 3.3.4 目录）、
  改前/改后片段、为何 3.3.6 与 hadoop-client 3.3.4 兼容、以及「总控将以 HEAD 200 复验两个 URL」。

## 边界

- 只用文件读写工具：不跑命令、不连数据库。
- 不动 `IngestionManifestRuntimePatrolTest.java`（已验收）、不动 `run-tests.ps1` 与任何基线数字。
- 不碰 3306/Docker/DeepSeek。
- 完成标记：WPF6C-FIX-DONE
