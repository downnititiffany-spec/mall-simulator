# 阶段 4 Hive 探测查询时限：独立编译补充

日期：2026-10-07

## 编译检查

- 使用 JDK 17（`/opt/jdk-17.0.12`）从既有 `platform-app-0.1.0-SNAPSHOT.jar` 提取临时 classpath 依赖，并结合 `platform-common/target/classes`、`connection-ingestion/target/classes`，通过 `javac -source 17 -target 17` 编译修改后的 `RuntimeProfileServiceImpl.java`。
- 结果：编译通过。临时解包目录由命令自动清理；没有修改业务数据或启动服务。
- 这只是单个 Java 类的编译检查；它不等于完整 Maven reactor，也没有验证新的 JDBC 查询超时行为。

## Maven reactor 状态

在线 Maven 编译尝试停在项目读取前：WSL 本地仓库没有 `spring-boot-starter-parent:3.2.5`，Maven Central 请求最终 `Connection reset`。未运行测试，未下载依赖。Maven 编译失败记录仍见 `PHASE4-HIVE-PROBE-QUERY-TIMEOUT-20261007.md`。
