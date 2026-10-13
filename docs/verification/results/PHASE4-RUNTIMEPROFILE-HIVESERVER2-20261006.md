# 阶段 4 RuntimeProfile → HiveServer2 JDBC 探测结果

## 结论

限定通过：当前 `RuntimeProfileServiceImpl.checkHive` 通过打包到 `platform-app` 的 Hive JDBC 3.1.3 驱动，连接本次任务启动的真实 HiveServer2，并执行 `SELECT 1` 成功。此结果只关闭该 RuntimeProfile 探测切片，不代表阶段 4 整体、平台 Web/API 全链或共享 Hive Metastore 生产验收。

## 任务与运行环境

- RunId：`rtprofile_20261006_01`
- 仓库分支 / HEAD：`codex/cloud-v3-development` / `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f`；运行前 fetch 后本地 HEAD 与 origin 分支无 ahead/behind。
- 应用测试：Windows JDK 17.0.12、Maven 3.9.14。
- Hive 服务：WSL Hive 3.1.3、Hive 服务专用 JDK 8u351；HDFS namespace Cluster ID `CID-c74c5a0e-ec0e-498e-b5f4-0e06eb0b949f`。
- 本任务端点：NameNode RPC `127.0.0.1:9100`、Thrift Metastore `127.0.0.1:19083`、HiveServer2 JDBC `jdbc:hive2://127.0.0.1:10015/default`。
- Metastore 为本 RunId 新建的嵌入式 Derby：`/home/asus/graduationproject-runs/rtprofile_20261006_01/hive-metastore/derby`。它与平台 Spark 使用的 embedded Derby、与共享 HMS 验收均分开。

## 执行和证据

1. 启动前确认 9100、19083、10015 未监听；RunId 目录不存在；Hive/Hadoop/JDK 工具存在；现有 NameNode/DataNode VERSION 的 clusterID 与预期相符。两个历史 HDFS 数据目录只核读 VERSION，没有格式化或删除。
2. 执行 `wsl.exe -e bash -lc 'bash /mnt/d/Develop_code/GraduationProject-wt/v3-dev/scripts/wsl-flume-to-hdfs.sh rtprofile_20261006_01 /mnt/d/Develop_code/GraduationProject-wt/v3-dev/tests/golden-dataset/events/golden-20260901-positive.jsonl'`。脚本将合成夹具写入独立 HDFS 路径 `/landing/rtprofile_20261006_01/raw/`；输入与回读均为 50 行，SHA-256 相同：`333f00078ee6bd72b9aea16c3da56ffcdd1e6df730929cec7147434d359025c0`。
3. 执行 `scripts/wsl-hive-thrift.sh rtprofile_20261006_01 start`；脚本启动任务专属 Derby Metastore 与 HiveServer2。
4. 新增显式集成用例 `analytics-server/platform-app/src/test/java/com/graduation/analytics/runtime/RuntimeProfileHiveServer2IT.java`，在测试中调用生产 `RuntimeProfileServiceImpl.checkHive` 方法（反射调用，未启动 Spring Web/API 或连接 MySQL），使用真实 Hive JDBC 驱动/真实 HiveServer2 执行 `SELECT 1`。定向命令：

   ```powershell
   mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=RuntimeProfileHiveServer2IT' '-Dsurefire.failIfNoSpecifiedTests=false' test
   ```

   结果：`RuntimeProfileHiveServer2IT` 1/1 PASS、0 failure / 0 error / 0 skipped；`platform-app` reactor BUILD SUCCESS。Surefire 报告位于 `analytics-server/platform-app/target/surefire-reports/com.graduation.analytics.runtime.RuntimeProfileHiveServer2IT.txt`。
5. 先用 `scripts/wsl-hive-thrift.sh rtprofile_20261006_01 stop` 停止 HiveServer2/Metastore，脚本确认服务已停且 Derby/HDFS 保留。随后对 HDFS PID 文件中的 NameNode/DataNode PID 核对命令标记和 `HADOOP_CONF_DIR`，用同一 RunId 配置执行 Hadoop daemon stop。停止后只读检查确认原 PID 均退出、9100/19083/10015 无监听；Derby 目录仍存在。停止后的 HDFS 网络回读因服务关闭而拒绝连接；数据内容一致性在关闭前已由 Flume 脚本完成验证。

## 限制与边界

- 此用例执行生产 Hive 探测方法，但没有启动平台 Spring 上下文，也没有经过 RuntimeProfile Controller、数据库配置读取、Landing/Spark/MetricStore 其它探测或真实 profile 激活。
- 此次 Hive Metastore 使用任务专属嵌入式 Derby。不能据此声称平台任务 Hive Metastore、共享 Thrift HMS 或多节点环境验收通过。
- 没有访问任何 MySQL，3306 零接触；没有真实凭据或外部服务。
- 没有格式化、删除或清理历史 HDFS 数据；新建的 task RunId 目录、HDFS 前缀和 Derby 数据均保留供审计。
- 一条本地停止后核验命令因 Hadoop daemon 正常删除其 PID 文件而以 1 退出；其后通过原 PID `kill -0` 只读核验和端口核验确认进程确已停止。没有按端口终止进程。
- 阶段 4 决策列表真实非空分页/排序及其它门槛仍待完成；阶段 4 整体不关闭。
