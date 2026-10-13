# 阶段 4 流水线后台执行器拒绝处理

## 缺陷与修复

流水线线程池为有界队列。若线程池关闭或队列饱和，`Executor.execute` 抛出 `RejectedExecutionException`；原逻辑已先插入 `PENDING` run，拒绝后不会有后台任务更新它，页面会长期显示运行中。

所有创建、普通重试、显式重算和运维恢复入队现在经过同一处理：拒绝时把 run 更新为 `FAILED` / `RUN_EXECUTOR_REJECTED`，设置完成时间并返回可读的“稍后重试”信息；不在 HTTP 请求线程同步执行 Spark，也不创建虚构的阶段明细。run 仍可使用既有失败重试或恢复入口重新入队。

## 验证

- `mvn -o -pl warehouse-pipeline -am '-Dtest=PipelineServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- `PipelineServiceTest` **53/53 PASS**，0 failure/error/skipped；四模块 Maven reactor `BUILD SUCCESS`。随后 `mvn -o -pl platform-app -am -DskipTests package` 七模块 reactor `BUILD SUCCESS`，`platform-app` Boot JAR 重打包成功；该次 package 命令跳过测试。
- 新增测试覆盖首次提交与失败后的重试都被后台 Executor 拒绝时，run 以稳定错误码终止而非残留 PENDING。

## 边界

这是本地服务单测，不模拟真实线程池饱和压力或数据库不可用窗口，也没有启动平台/Spark/数据库。阶段 4 整体仍部分完成，真实平台异步轮询证据待补。