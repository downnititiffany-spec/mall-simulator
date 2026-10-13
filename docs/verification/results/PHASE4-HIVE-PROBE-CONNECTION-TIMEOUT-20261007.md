# 阶段 4 RuntimeProfile Hive JDBC 建连超时验证

日期：2026-10-07（Asia/Shanghai）

## 变更

`RuntimeProfileServiceImpl` 为 Hive 探测创建 JDBC 连接时，暂时将 `DriverManager` 登录超时设置为 `platform.query.read-timeout-seconds` 的统一值，再立即恢复 JVM 原值。连接建立后，`Statement.setQueryTimeout` 继续使用同一配置约束 `SELECT 1`。Hive JDBC 3.1.3 的 `HiveConnection` 在建连时读取 `DriverManager.getLoginTimeout()` 并将其作为 Thrift transport 连接超时，因此这覆盖了之前仅限制 SQL 执行、不限制建连的缺口。

同步处理宽回归发现的 AI 超时守卫失败：`OpenAiCompatLlmProvider` 两个供测试/非 Spring 调用的兼容构造器移除了 `@Value`，由带 `@Autowired` 的 Spring 构造器作为唯一配置注入点。业务调用者和测试仍可显式传值。

## 验证

命令：

```text
mvn -o -f analytics-server/pom.xml -pl ai-decision -am test
```

JDK 17，离线 Maven reactor：

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| platform-common | 118 | 0 | 0 | 0 |
| connection-ingestion | 400 | 0 | 0 | 2 |
| metric-analysis | 147 | 0 | 0 | 0 |
| ai-decision | 179 | 0 | 0 | 0 |
| 合计 | 844 | 0 | 0 | 2 |

`RuntimeProfileHiveProbeRedactionTest` 2/2 通过，覆盖默认 30 秒和显式 7 秒建连值，连接成功与失败时 DriverManager 原超时恢复。`PlatformOutboundClientTimeoutGuardTest` 与 `OpenAiCompatLlmProviderTest` 随 ai-decision 全模块通过。Maven reactor BUILD SUCCESS。

随后运行完整七模块后端 reactor：

```text
mvn -o -f analytics-server/pom.xml -pl platform-app -am test
```

各模块计数为 platform-common 118、connection-ingestion 400（2 skipped）、warehouse-pipeline 209、metric-analysis 147、ai-decision 179、platform-app 209；合计 **1,262 项，0 failure、0 error、2 skipped**，BUILD SUCCESS。`platform-app` 209/209 通过，含管理员、决策分页 HTTP harness 与权限/数据源守卫测试。该 HTTP harness 使用测试环境，不构成实服认证分页证据。

## 证据边界

- JDBC 替身验证了传入 DriverManager 的值与恢复行为；Apache Hive 3.1.3 源码确认该驱动在构造 Thrift transport 时读取登录超时。源码：[HiveConnection.java（Apache Hive 3.1.3）](https://github.com/apache/hive/blob/rel/release-3.1.3/jdbc/src/java/org/apache/hive/jdbc/HiveConnection.java)。
- 本轮没有 HiveServer2 监听（10015 未监听），因此未做真实连接或探测；没有部署新版、连接数据库或访问 3306，也没有运行 `platform-app` 测试。
- `DriverManager` 登录超时是 JVM 全局设置。类锁只序列化经过本方法的 Hive 探测；不经过该锁的其它直接 `DriverManager` 调用理论上可能在连接构造的短暂窗口观察该值。当前应用常规数据库访问使用 DataSource。应在后续评估 Hive driver 专用连接属性，避免依赖全局状态。
- 阶段 4 和项目整体验收保持未完成；该离线回归不替代实服、数据库、浏览器或总控签收证据。

动态文档编辑前备份：`docs/PROJECT_STATUS.md.bak-20261007-hive-connect-timeout`、`docs/PROJECT_COMPLETION_CHECKLIST.md.bak-20261007-hive-connect-timeout`、`docs/verification/CURRENT_BATCH.md.bak-20261007-hive-connect-timeout`。

## 真实 HiveServer2 方法级复验（2026-10-07 补记）

### 环境与范围

- 新增独立测试 RunId：`hive_rtprobe_20261007_120517`；运行 Hive 3.1.3 Metastore `19084` 和 HiveServer2 `10015`，Metastore 使用该 RunId 下嵌入式 Derby。连接配置复用当前登记 cloud RunId `cloud_261007_021316_0219d8` 的 Hadoop client 配置，`fs.defaultFS=hdfs://127.0.0.1:19000`；本次 warehouse 位于 `/warehouse/hive_rtprobe_20261007_120517/hive-warehouse`。未格式化、重启或清理已有 HDFS。
- `RuntimeProfileHiveServer2IT.runtimeProfileProbeExecutesSelectOneAgainstHiveServer2` 调用当前工作树 `RuntimeProfileServiceImpl.checkHive()`，Hive JDBC 3.1.3 实际连到 HS2 并执行 `SELECT 1`。命令：

```text
mvn -o -f analytics-server/pom.xml -pl platform-app -am "-Dtest=RuntimeProfileHiveServer2IT" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：1/1 PASS，0 failure/error/skipped；七模块 reactor BUILD SUCCESS。

### 服务生命周期与边界

- 辅助脚本首次试运行发现 warehouse URI 在解析 HDFS authority 之前生成，得到错误的 `hdfs:///...`。该次服务由其 RunId PID 文件确认进程组后定点停止，Derby/HDFS 数据保留；未执行该次 JDBC IT。修复初始化顺序后新 RunId 实测通过。
- 脚本现从指定 Hadoop 配置读取 `fs.defaultFS`，校验显式 authority、RPC 端口监听及只读访问 HDFS 根目录后才创建 Run 数据；不匹配负例 exit 3，确认未创建 Run 目录。可通过环境变量分别指定工具目录、配置目录、Run 根、Metastore/HiveServer2 端口，避免误用旧的 9100 和现有 19083。
- 通过脚本停止本轮 Metastore/HS2；随后确认 19084/10015 STOPPED，原 RunId 的 HDFS 19000/19010、Metastore 19083 与平台服务仍 LISTEN。Run 专属 Derby 和 warehouse 保留。
- 此为当前源码 `checkHive()` + 真实 Hive JDBC/HS2 的方法级证据，不含 Spring Web/API、DB-backed RuntimeProfile、真实 credential secret、其他探测项/激活，亦不含慢连接下 connect-timeout 的时限测量。没有连接 MySQL 或访问 3306。阶段 4 和项目验收不关闭。
- 动态文档更新前备份后缀：`bak-20261007-hive-server2-retest`。

## 当前工作树再次实服复验（2026-10-07）

- 使用独立 RunId hiveprobe_261007_a1；Hive 3.1.3 Metastore 19084、HiveServer2 10015，RunId 专属 embedded Derby，HDFS warehouse hdfs://127.0.0.1:19000/warehouse/hiveprobe_261007_a1/hive-warehouse。Hadoop client XML 来自当前主栈 RunId 的 hive-conf，复制到新的专属 RunId hadoop-conf 后使用；没有修改主栈配置。
- Windows JDK 17.0.12 / Maven 3.9.14 在当前工作树执行定向 Maven 测试：mvn -o -f analytics-server/pom.xml -pl platform-app -am "-Dtest=RuntimeProfileHiveServer2IT" "-Dsurefire.failIfNoSpecifiedTests=false" test
结果：RuntimeProfileHiveServer2IT 1/1 PASS，0 failure/error/skipped；七模块 reactor BUILD SUCCESS。该测试经当前 RuntimeProfileServiceImpl.checkHive() 和 Hive JDBC 3.1.3 对实时 HiveServer2 执行 SELECT 1。
- 首次误用 WSL 系统 Maven 离线仓库时因缺少 Spring Boot 3.2.5 parent POM 未进入测试；切换至成功构建当前源码的 Windows Maven/JDK17 后测试通过。Hive 启动打印 log4j.properties is not found. HADOOP_CONF_DIR may be incomplete. 警告；两个服务仍就绪，实际 JDBC 探测通过。
- 测试后通过同一辅助脚本停止本 RunId Metastore/HiveServer2，确认 19084/10015 不再监听且 Derby 目录保留。主栈 HDFS RPC/DataNode 19000/19010、Metastore 19083、应用 8090–8092 仍监听；5173/5174 未监听。没有停止主栈、访问 MySQL 或触碰 3306。
- 范围仅为当前代码方法级真实连接/查询复验；不含 Spring Web/API、从数据库加载 RuntimeProfile、真实凭据、其他探测项、profile 激活或慢连接 connect-timeout 时限证明。阶段 4 仍部分完成。