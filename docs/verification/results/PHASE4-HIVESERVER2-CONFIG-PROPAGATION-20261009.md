# 阶段 4 HiveServer2 隔离配置传递修复与当前源码探测（2026-10-09）

## 发现与修复

`scripts/wsl-hive-thrift.sh` 的 start preflight 使用 `CLOUD_HADOOP_CONF_DIR` 校验 Hadoop 配置，但 Metastore 和 HiveServer2 子进程启动参数仍硬编码为新 RunId 下的 `hadoop-conf`。因此 preflight 可读取有效的外部配置，而实际服务进程却可能因新目录不存在而无法加载 `core-site.xml`。

现将两个子进程都改为接收已校验的 `$HADOOP_CONF_DIR`。Hive 自身配置仍来自本 RunId 独立 `HIVE_CONF_DIR`，Derby catalog 与 warehouse 仍使用 RunId 专属目录。

## 验证

- `bash -n scripts/wsl-hive-thrift.sh`：PASS。
- 在现有单节点 HDFS `hdfs://127.0.0.1:19000` 上，以新隔离 RunId `hiveprobe_rt_20261009_a47d32` 启动 Metastore `19084` 与 HiveServer2 `10015`；专属 Derby 与 warehouse 路径均在该 RunId 下。
- 当前工作树 Maven 定向命令：`mvn -o -f analytics-server/pom.xml -pl platform-app -am "-Dtest=RuntimeProfileHiveServer2IT" "-Dsurefire.failIfNoSpecifiedTests=false" test`；**1/1 PASS**，0 failure/error/skipped，七模块 `BUILD SUCCESS`。生产 `RuntimeProfileServiceImpl.checkHive()` 通过 Hive JDBC 3.1.3 实际执行 `SELECT 1`。
- 仅用专用脚本停止该 Hive RunId；随后确认 Metastore/HS2 为 STOPPED，Derby 与日志目录保留，主 RunId `cloud_261009_053348_d6936a` 的 12 个目标端口仍 LISTEN。

## 限制

这是当前源码方法级真实 HiveServer2 JDBC 证据，不覆盖 Spring API、数据库-backed RuntimeProfile、凭据引用、profile 激活/切换、共享 Hive Metastore、建连超时边界或完整产品链。未连接 MySQL，尤其没有访问 3306；阶段 4 和总体验收保持未关闭。
