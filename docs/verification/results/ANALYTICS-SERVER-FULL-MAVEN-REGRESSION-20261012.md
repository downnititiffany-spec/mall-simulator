# 2026-10-12 Analytics Server 全模块离线 Maven 回归

## 执行

- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `codex/cloud-v3-development`，HEAD `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f`。
- 命令：在 `analytics-server` 执行 `mvn -o test`。
- Reactor：`platform-common`、`connection-ingestion`、`warehouse-pipeline`、`metric-analysis`、`ai-decision`、`platform-app`，全部 `BUILD SUCCESS`。
- Surefire 汇总：1381 tests，0 failures，0 errors，2 skipped。跳过项：`SampleRefPolicyTest.symbolicLinkIsRejected`、`SourceProfileFileSymlinkTest.rejectsSymlinkedProfile`。
- `git diff --check` 未发现空白错误；输出包含既有 LF→CRLF 警告。

## 观察与边界

- `IngestionServiceStatusTest.reportsLandingErrorInsteadOfFallingBack` 使用 HDFS URI，当前 Windows 环境缺少 `HADOOP_HOME`/`hadoop.home.dir`，Hadoop 客户端输出环境错误堆栈；用例仍通过，服务按契约返回受控 `landingError`，没有回退到本地默认目录。
- 这是离线单测证据，不包含 `*IT` 真服务/数据库/浏览器验收，也不证明真实 HDFS 可用、端到端链路、迁移现场或生产部署。
- 本轮 Maven 测试未连接数据库、未启停服务、未触碰 3306；没有修改业务代码。既有工作树改动均保留。
- 项目阶段门及总控验收仍按 V3.0 指导书和动态清单逐项开放/签收，本回归不代表产品验收。