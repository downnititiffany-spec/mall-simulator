# 阶段 4：流水线分页读取快照一致性修正

日期：2026-10-07

## 发现与修改

流水线运行记录分页先查询 `COUNT(*)`，再取排序后的当前页。该路径原先未声明事务，因此两个语句可能观察到不同的提交状态，导致 `total` 与 `items` 在并发写入期间不一致。

`PipelineController.page` 现使用 `metaTransactionManager`、只读 `REPEATABLE_READ` 事务，将计数和分页读取放入同一个元数据数据库快照。现有分页参数限制、排序规则、响应结构与权限要求未改变。

## 验证与边界

- `git diff --check`：通过；输出只有工作树既有 LF/CRLF 转换警告。
- 自动化测试：JDK 17，`mvn -o -f analytics-server/pom.xml -pl platform-app -am "-Dtest=PipelineControllerPaginationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`；七模块 reactor `BUILD SUCCESS`，该测试类 4/4 PASS，0 failure/error/skipped。新增反射断言核对事务管理器、隔离级别和只读标记；原分页边界/排序行为用例仍通过。
- 服务/API/数据库：未部署、未访问。
- 此证据确认离线分页用例和事务注解契约，不证明真实数据库并发插入时 count/page 共享快照；阶段 4 未关闭。

动态文档修改前备份后缀：`bak-20261007-pipeline-page-test`（前次实现记录备份：`bak-20261007-pipeline-page-snapshot`）。
