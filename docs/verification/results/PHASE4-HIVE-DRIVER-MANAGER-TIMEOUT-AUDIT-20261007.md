# 阶段 4：Hive JDBC 3.1.3 DriverManager 超时作用域审计

日期：2026-10-07

## 核验结论

Apache Hive `rel/release-3.1.3` 的 `HiveConnection.setupLoginTimeout()` 从 `DriverManager.getLoginTimeout()` 读取秒数并转为毫秒。该值随后传入 `HiveAuthUtils.getSocketTransport()`，再用于构造 Thrift `TSocket`。这意味着同一值并非只约束初始建连；socket 读操作也可能受它影响。

Hive Jira HIVE-22196 说明 Hive JDBC 同 JVM 的其它 JDBC driver 修改 `DriverManager.loginTimeout` 时，可能造成 Hive Thrift socket read timeout。`RuntimeProfileServiceImpl` 当前仅以类内锁串行化自身的 Hive 探测，并在 `finally` 恢复原值；不遵循该锁的其它调用者无法被它隔离。

对 Hive 3.1.3 的 `HiveConnection.java`、`Utils.java` 进行源码检查，没有发现可按连接设置的 `socketTimeout` URL/property；连接超时由全局 loginTimeout 导入。不能靠猜测添加 JDBC URL 参数来宣称已移除全局耦合。

## 处理

本次不改探测代码：它目前确实以统一值为 Hive socket 提供了有界超时，但该实现的全局副作用仍存在。消除该限制需要单独验证兼容方案，例如更换或修补驱动，或把 Hive 探测隔离到独立进程；方案选择、TLS/认证/Thrift 兼容和 timeout 语义均需实证后再实施。

## 证据与边界

- Apache Hive 3.1.3 源码：[HiveConnection.java](https://github.com/apache/hive/blob/rel/release-3.1.3/jdbc/src/java/org/apache/hive/jdbc/HiveConnection.java)、[HiveAuthUtils.java](https://github.com/apache/hive/blob/rel/release-3.1.3/common/src/java/org/apache/hadoop/hive/common/auth/HiveAuthUtils.java)。
- Apache Hive 缺陷记录：[HIVE-22196](https://issues.apache.org/jira/browse/HIVE-22196)。
- 本轮为源码与缺陷记录核查，没有代码变更、测试、数据库连接或服务操作。阶段 4 和产品验收不因此关闭。

动态文档编辑前备份后缀：`bak-20261007-hive-driver-timeout-audit`。
