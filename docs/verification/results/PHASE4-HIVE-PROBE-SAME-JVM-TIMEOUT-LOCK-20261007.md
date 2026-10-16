# 阶段 4 RuntimeProfile Hive JDBC 同类探测并发超时回归

日期：2026-10-07（Asia/Shanghai）

## 验证内容

`RuntimeProfileHiveProbeRedactionTest.concurrentHiveProbesKeepTheirOwnDriverManagerTimeouts` 通过 fake JDBC driver 让第一条连接在 `DriverManager.getConnection` 内等待，再并发启动第二条探测。测试确认第二条探测等待类内共享锁，第一条使用 3 秒、第二条使用 7 秒，各自完成后 `DriverManager` 原超时恢复。

同类测试还覆盖查询默认/显式超时、成功与连接失败后的全局超时恢复、凭据映射及错误信息脱敏。

## 结果

- 命令：`mvn -o -f analytics-server/pom.xml -pl connection-ingestion -am "-Dtest=RuntimeProfileHiveProbeRedactionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- JDK 17，Maven reactor：`analytics-server`、`platform-common`、`connection-ingestion` BUILD SUCCESS。
- 测试类：**3/3 PASS**，0 failure、0 error、0 skipped。

## 证据边界

- 并发用例只证明经 `RuntimeProfileServiceImpl` 类内锁的 Hive 探测彼此不会覆盖超时值；fake JDBC 不建立网络连接。
- `DriverManager.setLoginTimeout` 仍是 JVM 全局设置。不经过该类锁的其它直接 JDBC 调用仍可能在短暂设置窗口观察此值；该风险未解决。
- 本轮未连接真实 HiveServer2、未运行平台 API、未加载数据库 RuntimeProfile、未部署新版，也未连接 MySQL；阶段 4 与总体验收不关闭。

动态文档编辑前备份后缀：`bak-20261007-hive-same-jvm-timeout-lock`。
