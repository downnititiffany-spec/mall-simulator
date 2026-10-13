# 阶段 4：流水线未预期异常诊断文本脱敏（2026-10-09）

## 实现

- `PipelineService.execute` 对未预期异常继续记录稳定码 `RUN_INTERNAL` 与失败状态，但持久化用户可见错误改为固定安全摘要，不再写异常原文。
- 异常日志改为记录运行 ID 和异常类型，不附完整 Throwable/堆栈，避免 JDBC/运行环境诊断内容进入普通日志。
- 阶段执行通用异常保留 `STAGE_INTERNAL` 和阶段代码，仅提供固定摘要。
- `PipelineController` 的 `/pipeline-runs` 和 `/pipeline-runs/page` 直接序列化 `PipelineRun`，因此上述字段会通过运维列表接口暴露；测试用执行器异常覆盖了这条持久化边界。

## 验证

- 定向：`mvn -o -f analytics-server/pom.xml -pl warehouse-pipeline -am -Dtest=PipelineServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，`PipelineServiceTest` 54/54，0 failures/errors/skips，BUILD SUCCESS。
- 依赖模块全回归：`mvn -o -f analytics-server/pom.xml -pl warehouse-pipeline -am test`，platform-common、connection-ingestion、warehouse-pipeline 合计 743 项，0 failures/errors、2 skipped，全部 BUILD SUCCESS。fresh Surefire XML 总计 743/0/0/2。
- 回归期间 Hadoop Windows 本地环境有 `HADOOP_HOME` 缺失诊断日志；相关套件结果仍为通过。此回归不构成 HDFS 服务验收。

## 证据边界与后续

- 这是 Windows JDK 17 离线单测/模块回归；没有连接数据库、访问 3306、调用真实 Spark/Hive、验证 HTTP 或浏览器，也没有重启隔离服务。
- 本次只收紧未预期 Java 异常分支。结构化 Spark JobResult 的 `errorMessage`、发布器返回诊断和消费台账错误证据仍需按 API/存储暴露逐项审计；不得将本报告描述为全链路脱敏。
- 阶段 4 和产品总体验收保持开放。管理员环境变量不可见，真实认证分页及 RuntimeProfile 管理验收仍待完成。
