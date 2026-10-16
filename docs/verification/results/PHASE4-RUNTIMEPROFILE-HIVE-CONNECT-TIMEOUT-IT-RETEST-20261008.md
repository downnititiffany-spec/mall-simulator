# 阶段 4 RuntimeProfile Hive JDBC 停滞握手超时 IT 复测（2026-10-08）

## 复测变更

收紧 `RuntimeProfileHiveConnectTimeoutIT` 的时间断言：除要求当前探测调用少于 6 秒外，还要求调用至少持续 1 秒。这样本地 socket 被接受后立即拒绝连接的情况不能被误判为配置超时生效。原始测试与范围详见 `PHASE4-RUNTIMEPROFILE-HIVE-CONNECT-TIMEOUT-IT-20261008.md`。

## 执行结果

- Windows JDK 17.0.12/Maven 3.9.14，七模块离线 Maven reactor `BUILD SUCCESS`。
- `RuntimeProfileHiveConnectTimeoutIT` **1/1 PASS**，0 failure/error/skipped；Surefire 总测试时间 **3.673 秒**。测试中的 JDBC 探测调用另断言 `1 秒 ≤ elapsed < 6 秒`，并确认 loopback server 已接受该连接。
- 仍使用 Hive JDBC 3.1.3 对本地不响应握手的 socket fixture；检查失败结果脱敏和 `DriverManager` 登录超时恢复断言仍通过。

## 边界

仅是本地 driver/fixture 方法级复验，不覆盖真实 HiveServer2、Spring/API、DB-backed RuntimeProfile、WSL或其它 JDBC 调用方并发改写 `DriverManager` 全局超时。无数据库、3306或 task-owned 运行栈副作用；阶段 4 保持部分完成。
