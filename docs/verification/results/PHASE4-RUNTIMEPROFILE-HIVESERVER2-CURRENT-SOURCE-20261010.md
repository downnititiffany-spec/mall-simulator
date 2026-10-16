# 阶段4 RuntimeProfile 当前源码 HiveServer2 实际 JDBC 探测（2026-10-10）

## 现场与执行

- 使用当前隔离 HDFS 配置目录 `/home/asus/graduationproject-runs/cloud-e2e/cloud_261009_135713_996499/platform-spark-conf`，只读确认 `fs.defaultFS=hdfs://127.0.0.1:19000` 且 HDFS 根可访问。
- 为本次探测创建独立 RunId `hiveprobe_rt_20261010_v3dev_01`，Hive Metastore 使用空闲端口 19084、HiveServer2 使用空闲端口 10015；Derby catalog 与 warehouse 路径均独立于主隔离栈。启动通过 `scripts/wsl-hive-thrift.sh` 的配置、端口与既有数据守卫。
- WSL Maven 离线缓存缺少 Spring Boot parent，命令在 Maven project model 加载阶段终止，没有编译/测试。随后 Windows Maven 3.9.14 使用 JDK17、现有离线依赖成功执行：

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am `
  '-Dtest=RuntimeProfileHiveServer2IT' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 结果

- `RuntimeProfileHiveServer2IT`：1/1 PASS，0 failure/error/skipped；七模块 reactor `BUILD SUCCESS`。
- 当前工作树 `RuntimeProfileServiceImpl.checkHive()` 经 Hive JDBC 3.1.3 连接隔离 HiveServer2 并实际执行 `SELECT 1`；返回 detail 固定为 `SELECT 1 ok (HiveServer2)`，测试也断言不泄露 host/port/JDBC URL。
- 使用相同 RunId 和配置调用 `scripts/wsl-hive-thrift.sh ... stop`。脚本完成自身 PID/cwd/executable/class/配置身份核验后停止两服务；随后 status 为 STOPPED，Derby 目录和 HDFS warehouse 确认保留，主 RunId `cloud_261010_005249_1c73ec` 的 12 个目标端口仍 LISTEN。
- 3306 未访问；没有修改 MySQL，也未更改 ACTIVE RuntimeProfile。

## 证据边界

此项证明当前源码的 RuntimeProfile Hive 探测方法可对隔离 HiveServer2 执行真实 JDBC 查询。它不证明 Spring 管理 API、数据库-backed RuntimeProfile、凭据库引用、认证管理员会话、profile 激活/切换、平台实际运行档案探测、共享 Metastore 或完整产品链。当前 ACTIVE profile 的 JDBC URL/credentialRef 仍须通过受认证管理流程核验；阶段4和总体验收继续开放。
