# 阶段 4 RuntimeProfile Hive 探测查询超时行为回归

日期：2026-10-07

## 验证内容

- 扩展 `RuntimeProfileHiveProbeRedactionTest` 的 fake JDBC `Statement`，记录 `setQueryTimeout` 实参。
- 默认配置（手工构造服务、配置值为空）执行 `SELECT 1` 前传入 30 秒；显式配置 `7` 时传入 7 秒。
- 既有用例继续核验 SQL、固定用户名 `hive`、凭据仅作为 JDBC password 传递，以及成功/失败响应不回显 JDBC URL、token、密码或驱动异常细节。

## 结果

- 命令：`mvn -o -f analytics-server/pom.xml -pl platform-app -am "-Dtest=RuntimeProfileHiveProbeRedactionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- JDK 17，七模块 Maven reactor `BUILD SUCCESS`。
- `RuntimeProfileHiveProbeRedactionTest`：2/2 PASS，0 failure、0 error、0 skipped。其它模块按 `failIfNoSpecifiedTests=false` 跳过该定向类，不代表全套回归。

## 边界

- 这是 fake JDBC 行为验证，不验证 Hive JDBC 驱动是否尊重查询超时，也不覆盖 `DriverManager.getConnection()` 建连时间。
- 未部署新版、未连真实 HiveServer2 或数据库；阶段 4 与最终验收仍未完成。
