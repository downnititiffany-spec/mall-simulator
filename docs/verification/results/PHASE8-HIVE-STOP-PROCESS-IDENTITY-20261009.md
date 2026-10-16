# Hive Thrift 停止前进程身份校验（2026-10-09）

## 背景

`wsl-hive-thrift.sh` 既有 stop 分支仅凭 PID 文件、`kill -0` 和 PGID==PID 就向整个进程组发信号。若 PID 被系统复用，或 PID 文件与监听端口所属服务不一致，可能误停无关进程。该脚本在本工作树中原先是未跟踪文件，因此本报告同时记录其工作树归属；修改前副本保留为 `scripts/wsl-hive-thrift.sh.bak-20261009-hive-stop-identity`，此前配置传递修复前副本也保留。

## 修复

stop 现在先对 Metastore 和 HiveServer2 两个 PID 文件做完整预检，再统一发送信号。活动 PID 必须同时满足：

- `/proc/<pid>/exe` 指向本次配置的 Java；
- 工作目录解析为该 RunId 根目录；
- cmdline 包含该服务应有的 Hive 主类；
- 环境中的 `HIVE_CONF_DIR` 和 `HADOOP_CONF_DIR` 与本次 RunId/已验证配置完全一致；
- PGID 等于已登记 PID。

若 PID 文件无效、进程身份不匹配、缺少 PID 但端口仍监听，或 PID 已失效但端口仍监听，则 fail-closed，且在预检阶段不向任何服务发信号。正常停止不删除 RunId 的 Derby/HDFS 数据。

## 验证

- `bash -n scripts/wsl-hive-thrift.sh`：PASS。
- 新隔离 RunId `hiveprobe_rt_20261009_b91e25` 启动：Metastore PID 11591、HiveServer2 PID 11734；分别核对实际 Java 路径、RunId 工作目录、Hive 主类、两项配置环境及 PGID：均 PASS。
- 经新 stop 分支停止：PASS；19084/10015 释放，RunId Derby 目录保留。
- 负向测试：专属 PID 文件指向独立测试 `sleep` PID，stop 返回身份拒绝码 9，测试进程保持存活；随后仅由测试脚本按已知 PID 结束该临时进程并清理其 `/tmp` 测试目录：PASS。
- 主服务端口复核：19083 仍由 PID 9084 的 JDK 17 HiveMetaStore 监听，cwd 为当前仓库；没有对其发信号。使用 `CLOUD_SINGLE_NODE_ROOT=/home/asus/graduationproject-runs` 核对后，RunId `cloud_261009_053348_d6936a` 的12个登记端口均 LISTEN；本轮未停止或连接该服务。3306未连接；WSL内3307只观察到监听。

## 范围

这是 stop 身份守卫与两个隔离实例的一次运行验证，不是完整单节点栈 RunId 生命周期验收、崩溃恢复、数据库验证或阶段 4/8 验收。阶段 4 和最终总控验收仍开放。

