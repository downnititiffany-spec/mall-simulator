# 阶段4 RuntimeProfile 服务流程中的真实 HiveServer2 探测（2026-10-10）

## 实施

扩展 `analytics-server/platform-app/src/test/java/com/graduation/analytics/runtime/RuntimeProfileHiveServer2IT.java`。除原来反射调用私有 `checkHive()` 的真实 JDBC 探测外，新增服务级场景，通过公开 `RuntimeProfileServiceImpl.test(profileId)` 运行 Landing/Hive/Spark/Metric 汇总，并断言结果中的 Hive 项由隔离 HiveServer2 返回 `SELECT 1 ok (HiveServer2)`。

profile mapper、MetricStore、凭据服务使用 Mockito；Landing 使用临时目录。LOCAL profile 配置真实 Hive JDBC URL，HiveServer2 来自独立 RunId；Spark 路径有意不配置，因此全体结果 `allPassed=false`，profile 保持 DRAFT。该用例不声称完整 RuntimeProfile 测试通过或 profile 已激活。

## 实际服务与结果

- RunId：`hiveprofileflow_rt_20261010_v3dev_01`；Metastore 19084、HiveServer2 10015；独立 Derby catalog 与 HDFS warehouse。
- HiveServer2 使用 Hive JDBC 3.1.3；Maven `-o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=RuntimeProfileHiveServer2IT' '-Dsurefire.failIfNoSpecifiedTests=false' test`：2/2 PASS，0 failures/errors/skipped，七模块 `BUILD SUCCESS`。
- 测试后通过同 RunId 的 `scripts/wsl-hive-thrift.sh stop` 身份守卫停止；19084/10015 均 STOPPED，Derby 与 HDFS 数据目录保留。主隔离栈未停止。
- 未访问 MySQL 3306，未连接任何 MySQL，未调用 Spring HTTP/认证 API，也未做 DB-backed RuntimeProfile 或 profile 激活/切换。

## 证据边界

此项比私有方法反射测试多覆盖一层 RuntimeProfile 服务结果聚合，证明真实 Hive JDBC 检查进入公开服务流程。mapper/MetricStore/credential 仍为 mock，且故意未配置 Spark；不是整个 profile 检查通过、认证 API、真实数据库状态、激活切换或阶段4验收证据。阶段4与总验收保持开放。
