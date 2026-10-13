# Analytics Server 七模块完整 Maven 回归（2026-10-10）

## 范围与命令

- 在 `analytics-server/pom.xml` 的完整 Maven Reactor 中运行 `-pl platform-app -am test`，覆盖 `platform-common`、`connection-ingestion`、`warehouse-pipeline`、`metric-analysis`、`ai-decision`、`platform-app` 六个代码模块及父 Reactor。
- 使用 Windows JDK 17 和仓库 Maven 设置。机器级 `HADOOP_HOME` 指向 Hadoop 3.3.4 且 `bin/winutils.exe` 存在；Codex 进程未继承该机器变量，因此仅为 Maven 子进程注入该既有路径及 `bin` PATH，没有持久化或改系统配置。

## 结果

| 模块 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| platform-common | 121 | 0 | 0 | 0 |
| connection-ingestion | 415 | 0 | 0 | 2 |
| warehouse-pipeline | 213 | 0 | 0 | 0 |
| metric-analysis | 153 | 0 | 0 | 0 |
| ai-decision | 235 | 0 | 0 | 0 |
| platform-app | 227 | 0 | 0 | 0 |
| **合计** | **1364** | **0** | **0** | **2** |

- Maven Reactor：`BUILD SUCCESS`，结束时间 2026-10-10 15:41:54 +08:00。
- 两项 skipped 均为 Windows 符号链接护栏：`SampleRefPolicyTest.symbolicLinkIsRejected`、`SourceProfileFileSymlinkTest.rejectsSymlinkedProfile`。
- `IngestionServiceStatusTest.reportsLandingErrorInsteadOfFallingBack` 原断言要求错误正文包含 `hdfs`，与当前源码为防止内部异常信息外泄而统一返回的稳定错误文本不一致。测试现改为核对稳定错误文本；同用例继续核验 `eventsDir=null`、`pendingFiles=0`，确保不回退默认目录。定向测试 11/11 PASS；完整七模块回归中此用例亦通过。

## 证据边界

- 这是源码单测证据，不是数据库、认证 HTTP、浏览器或跨模块实服务链路证据；Maven 测试没有连接 MySQL 3306。
- 该回归未尝试启动平台服务。RuntimeProfile / 报告 API 的当前 RunId 实服验收仍需平台 DB 凭据进入实际任务环境，见 `PHASE14-REPORT-INSTANCE-STARTUP-RECOVERY-20261010.md`。
