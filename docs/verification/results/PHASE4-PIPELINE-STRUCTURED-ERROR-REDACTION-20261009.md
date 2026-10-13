# 阶段4 流水线结构化失败诊断脱敏

日期：2026-10-09

## 改动

- `PipelineService` 对通用 run 异常保留稳定错误码与固定摘要；阶段通用异常使用固定摘要。Spark 作业失败摘要只保留受控作业状态/错误码，不再把 `JobExecution.errorMessage` 原文写入 run、stage evidence 或日志。
- 指标发布失败证据采用字段白名单，跳过发布报告中未批准的自由文本；对外失败提示使用固定摘要。消费台账异常仅记录失败标志与异常类型，不保存驱动异常原文。
- `SparkStageExecutor` 对作业失败只记录受控阶段信息，持久化与返回值使用固定摘要。
- `MetricPublisher` 的写入、快照登记/激活和补偿异常保留稳定失败码，不持久化底层异常消息或输出 Throwable；`PipelineRecoveryService` 的错误报告与日志保留 run ID 和异常类型。
- `PipelineService` 证据解析、accepted 读取和坏行处理日志去掉底层异常文本及绝对目录，只留阶段/run、文件名（适用时）和异常类型。处理行为未改变。

## 验证

- 命令：`mvn -o -f analytics-server/pom.xml -pl warehouse-pipeline,metric-analysis -am test`
- Maven reactor：`platform-common`、`connection-ingestion`、`warehouse-pipeline`、`metric-analysis` 均 `BUILD SUCCESS`。
- Surefire：104 suites、899 tests、0 failures、0 errors、2 skipped（均位于 `connection-ingestion`）。
- 证据等级：Windows JDK 17 离线模块测试；没有连接 MySQL、未访问 3306、未启停现场服务，也没有真实 Spark/Hive/API/浏览器链路验证。阶段4继续开放。

## 约束与后续

这些改动不宣称 exactly-once，也不替代真实管理员分页或 RuntimeProfile/Thrift HiveServer2 集成验收。结构化证据字段应继续采用显式白名单；新增错误路径需覆盖持久化字段、API返回和日志三种出口。