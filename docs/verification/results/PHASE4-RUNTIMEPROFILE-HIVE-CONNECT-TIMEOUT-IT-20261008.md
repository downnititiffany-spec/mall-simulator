# 阶段 4 RuntimeProfile Hive JDBC 建连超时实测（2026-10-08）

## 目的与范围

验证当前 `RuntimeProfileServiceImpl.checkHive()` 在 Hive JDBC 3.1.3 Thrift 握手停滞时能否按配置超时返回。测试使用本地 loopback `ServerSocket` 接受连接但不完成 Thrift 握手，由真实 Hive JDBC driver 发起 JDBC 连接；不启动 HiveServer2。

## 执行与结果

- 新增显式 `RuntimeProfileHiveConnectTimeoutIT`，将 `hiveProbeQueryTimeoutSeconds` 设为 2 秒，调用当前 `checkHive()`。
- Windows JDK 17.0.12/Maven 3.9.14，七模块离线 reactor：`RuntimeProfileHiveConnectTimeoutIT` **1/1 PASS**，0 failure/error/skipped，`BUILD SUCCESS`；Surefire 测试执行时间 3.484 秒。测试断言检查调用耗时小于 6 秒。
- Hive JDBC 连接失败后，探测返回 `SELECT 1 failed (...)`；结果不包含 loopback 地址、JDBC URL 或 fixture 密码；`DriverManager.getLoginTimeout()` 恢复到调用前值。
- watchdog 最迟 8 秒关闭 fixture socket，确保回归时本地测试也能结束；fixture 线程为 daemon，测试 finally 关闭 socket、线程池并恢复全局超时。

## 结论边界

本次证明当前 JDK/驱动/代码组合能对本地停滞 Thrift 握手施加有界超时，并在当前进程内恢复 `DriverManager` 登录超时。它不证明真实 HiveServer2 慢连接、Spring/API、DB-backed RuntimeProfile、WSL 运行时或跨线程安全。Hive JDBC 3.1.3 仍读取 JVM 全局 `DriverManager` 超时；本服务的类锁不能约束其它调用方改写该全局值。未连接数据库、未访问 MySQL 3306、未启停现有运行栈。阶段 4 保持部分完成。
