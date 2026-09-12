# M3 集群只读探测记录（2026-09-12）

**用户授权范围**：只读探测，不连接 SSH、不改集群、不提交作业（用户 2026-09-12 选择「先做只读集群探测（不需要你给任何凭据）」）。
**探测发起方**：总控（本机 Windows 工作站），使用本机工具链 `D:\soft\hadoop\hadoop-3.3.4` 与 `D:\Develop\spark-3.5.1-bin-hadoop3`。
**隐私处置**：原始输出中的完整内网地址已在落库前掩码为 `192.168.18.10x`（见同目录 `MASKING.md`）；主机名 `node01/02/03` 保留。

---

## 1. 结论（全部为实测，非推断）

| # | 探测项 | 手段 | 实测结果 | 判定 |
|---|---|---|---|---|
| 1 | HDFS NameNode | `GET http://node01:9870/jmx?qry=…NameNodeInfo` | HTTP 200；`Version=3.3.4, ra585a73c3…`；安全模式=空（即**未处于安全模式**）；容量 **293.10 GB**，已用 **1.09 GB** | 存活 ✓ |
| 2 | DataNode | `hdfs dfsadmin -fs hdfs://node01:8020 -report` | **Live datanodes (3)**：`node01`/`node02`/`node03`，各 97.70 GB，`DFS Used%` 0.49%/0.24%/0.49%，`Under replicated blocks: 0`，`Decommission Status: Normal` | 3 副本位正常 ✓ |
| 3 | HDFS RPC 直连 | `hdfs dfs -fs hdfs://node01:8020 -ls /` | **成功**，`Found 8 items`（见 §2） | **本机可读写通道存在** ✓ |
| 4 | YARN ResourceManager | `GET http://node01:8088/ws/v1/cluster/info` | HTTP 200；`hadoopVersion=3.3.4`，`resourceManagerVersion=3.3.4`，`state=STARTED`，`haState=ACTIVE` | 存活且 ACTIVE ✓ |
| 5 | YARN 提交端口 | TCP 连接 `node01` | **8032 OPEN**（RM RPC，作业提交必需）、8030/8031/8033 OPEN、**8042 OPEN**（NM Web）、19888/10020 closed（无 JobHistory Server） | **具备提交作业的网络条件** ✓ |
| 6 | Hive Metastore | `spark-sql --conf spark.hadoop.hive.metastore.uris=thrift://node01:9083 -e "show databases"` | **成功**：`Fetched 7 row(s)`，`Time taken: 1.08 seconds`；7 库＝`default, exam, ods, scott, shop, tmp, yjxxt` | **Metastore 可直连** ✓ |
| 7 | HiveServer2 | TCP 10000 | **closed** | 与预检一致：走 Metastore/Spark 直连，不走 `beeline -u jdbc:hive2://` |
| 8 | 认证方式 | 上述第 3、6 项在**未配置任何 Kerberos 票据/凭据**的情况下成功 | ⇒ HDFS RPC 与 Metastore 两条路径均为 **simple 认证（无 Kerberos）** | 接入复杂度显著下降 ✓ |
| 9 | 其他共驻服务 | TCP | **2181 OPEN**（ZooKeeper）；16020 closed（无 HBase RS） | 集群为多组件实训镜像 |
| 10 | 本项目是否已落地集群 | `hdfs dfs -ls /hive/warehouse` + 关键字检索 | 现有库 `exam.db / ods.db / scott.db / shop.db / tmp.db / yjxxt.db`；检索 `sci/dwd/ads/graduation/analytics/mock` **命中数全为 0** | **本项目在集群上尚无任何数据** ✓ |

## 2. HDFS 根目录现状（`ls /` 原文，只读）

```
drwxr-xr-x - root supergroup 0 2026-09-07 20:06 /dolphinscheduler
drwxr-xr-x - root supergroup 0 2026-09-02 09:28 /exam
drwxr-xr-x - root supergroup 0 2026-09-11 10:13 /history
drwxr-xr-x - root supergroup 0 2026-08-25 15:06 /hive
drwxr-xr-x - root supergroup 0 2026-08-19 15:37 /logs
drwxrwxrwx - root supergroup 0 2026-08-29 17:04 /tmp
drwxr-xr-x - root supergroup 0 2026-08-25 16:43 /user
drwxr-xr-x - root supergroup 0 2026-09-11 16:47 /yjx
```

**关键含义（必须写进接入决策）**：

1. 根目录下 8 个目录**全部属 `root:supergroup`，权限 `755`（仅 `/tmp` 为 `777`）** ⇒ **以非 root 身份无法在 `/` 下建目录**；本项目需要用户指定一个可写根（例如 `/user/<某用户>` 或新建 `/graduation`）。
2. `/dolphinscheduler`（2026-09-07）、`/exam`（2026-09-02）、`/yjx`（2026-09-11）表明这台集群**已被课程/他人使用**，不是本项目独占 ⇒ 任何写操作必须先确认路径与身份，避免覆盖他人数据。
3. `/hive/warehouse` 已存在（2026-09-11 20:12）且其下 6 个库均为 `root` 所有 ⇒ 本项目的库目录同样需要 root 或授权用户来创建。
4. `/tmp` 为 `777` ⇒ 可作为无需授权的临时落脚点（但**不适合**作为正式仓库根）。

## 3. 命名撞名检查

- 实测 Metastore 7 库：`default, exam, ods, scott, shop, tmp, yjxxt`。
- 本项目仓层命名规则为 `<source_prefix>_<layer>`（冻结决策④，例如 `dw_ods / dw_dwd / dw_dim / dw_dws / dw_ads`）⇒ 与上述 7 库**均不撞名** ✓。
- **注意**：已存在一个名为 `ods.db` 的库（他人所有）。虽然本项目用 `dw_ods` 不会撞名，但这说明「裸层名」在这个集群里不是安全命名 ⇒ 保留 `<source_prefix>_` 前缀的做法**同时具备防撞名价值**，不只是解耦价值。

## 4. 由此更新的 M3 接入清单（未取证 = 仍待用户/实测）

| # | 待办 | 为什么必须 | 状态 |
|---|---|---|---|
| A | 指定**可写 HDFS 根**（或授权我以 `HADOOP_USER_NAME=root` 在 `/user/graduation` 一类路径建目录） | 见 §2-1：`/` 由 root 拥有 | **待用户** |
| B | 授权**向 YARN 提交真实作业**（E4 需要） | 提交＝集群写操作，须 scoped 确认 | **待用户** |
| C | YARN **队列名**（`default`？）与是否设资源上限 | 提交流程需 `--queue` | **待用户** |
| D | Spark 作业 jar 的 HDFS 落点 | §6.4.1 缺口⑦：jar 单点可变 URI | **待用户** |
| E | 集群侧是否已装 Spark；若未装则用本机 `spark-submit`（Spark 随作业分发） | 决定提交流程 | 待探测（可自行只读探测） |
| F | SSH 用户/端口/凭据（若 Flume、迁移脚本需落到集群侧） | 群侧落地脚本需要 | **待用户**（本次未用） |
| G | 集群侧 Hive/Spark 版本与安装目录 | 版本对齐 | 部分已知：HDFS/YARN 均 **3.3.4**，与本机客户端**完全一致** ✓ |

## 5. 版本对齐（重要有利事实）

- 集群：Hadoop/HDFS/YARN **3.3.4**。
- 本机客户端：Hadoop **3.3.4**、Spark **3.5.1**（其内置 Hadoop client 为 3.3.4）。
- ⇒ 客户端与集群**主版本完全一致**，不需要为兼容性单独准备客户端；这也解释了第 3、6 项为何一次成功。

## 6. 未取证边界（禁止越界表述）

- 本文档**只**证明「只读通道可达 + 组件版本与认证方式」。**未**提交任何 YARN 作业，**未**在 HDFS 写入任何字节，**未**创建任何 Hive 库/表。
- **因此 E4（集群 1,000 行）仍然零证据**，不得引用本文档声称集群链路已跑通。
- 未使用 SSH（22 端口开放但无凭据）；`/dolphinscheduler`、`/yjx`、`/exam` 等他人目录**未读取内容**，只做了根目录列名。
- 本机 `show databases` 使用 `local[*]`（日志 `Spark master: local[*]`）⇒ 该次运行**只在客户端本地跑**，未占用集群资源。
