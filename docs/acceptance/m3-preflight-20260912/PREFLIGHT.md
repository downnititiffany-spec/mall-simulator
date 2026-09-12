# M3 集群前置检查 · 本机可测部分实测报告（2026-09-12 13:51–13:56，总控直接测）

**性质**：只读取证。**未启停任何进程**、**未写库**、**未安装/未下载任何软件**、**未启动 Docker**、**未修改任何配置**。
**口径来源**：指导书 **V2.4** §6.4（八步预检顺序）、§6.4.1（接入集群前必须先修的八条代码缺口）、§6.5（需用户提供的信息 = `USER-INPUT` 阻塞项）。
**看板现状**：M3 = `BLOCKED`（缺 §6.5 `USER-INPUT`，看板 L56）。本报告的作用是把这条粗粒度 BLOCKED **拆成逐条可答的问题**，并把"本机能不能自己起集群"这件事**实测**清楚，而不是猜。

---

## 1. §6.4 八步预检：逐条判据、实测读数、结论

| # | §6.4 要求 | 本轮实测读数 | 结论 |
|---|---|---|---|
| 1 | 主机名、IP、端口、DNS、时间同步、Java/Spark/Hadoop/Hive 版本 | 主机 `dahaishui`；Windows 11 家庭版 build 26100；IPv4 = `192.168.18.1`、`192.168.43.1`（均为虚拟网卡网段）+ `169.254.105.212`（链路本地）⇒ **无常规局域网地址**；`w32time` = Running，时钟 2026-09-12 13:51:29；Java **17.0.12**（`JAVA_HOME=D:\Develop\JAVA17`）；Spark **3.5.1 built for Hadoop 3.3.4**（`D:\Develop\spark-3.5.1-bin-hadoop3`，RELEASE 含 `-Pyarn -Phive -Phive-thriftserver`）；Hadoop **3.3.4**（`HADOOP_HOME=D:\soft\hadoop\hadoop-3.3.4`，`hadoop/hdfs/yarn` 在 PATH，`winutils.exe` 在 system32）；**Hive 无独立安装**（`beeline`/`hive` 不在 PATH，`HIVE_HOME` 为空） | **部分通过**：版本面本机自洽且具备 YARN/Hive 能力的 Spark 发行版；**Hive 服务端缺失**、**无局域网地址** |
| 2 | HDFS 临时文件建/写/读/删 | `8020`（NameNode）**无监听**；`D:\soft\hadoop\hadoop-3.3.4\etc\hadoop` 下 `core-site.xml`/`hdfs-site.xml`/`mapred-site.xml`/`yarn-site.xml` **全部存在但 property 数 = 0**（默认空配置）；**无 NameNode 数据目录**（`D:\soft\hadoop\hadoop-3.3.4\data`、`D:\soft\hadoop\data`、`D:\hadoop-data` 均不存在） | **BLOCKED**（本机 HDFS 从未初始化：空配置 + 无元数据目录 + 无监听） |
| 3 | Hive 临时库/表建/写/查/删 | `9083`（Metastore）、`10000`（HiveServer2）**无监听**；无 `hive-site.xml`；无独立 Hive 安装 | **BLOCKED**（无 Hive 服务端） |
| 4 | 提交最小 Spark count 作业，取 externalJobId/最终状态/日志 | `8088`（YARN ResourceManager）**无监听** ⇒ 无 YARN 可提交；本机 Spark 仅可 `local[*]` 运行（真实链一直如此） | **BLOCKED**（集群档；本机 local 档已由既有真实链覆盖） |
| 5 | Flume 投递小文件到 HDFS 并校验 checksum | **Flume 未安装**（`D:\soft`、`D:\Develop` 下无任何 flume 目录/可执行文件；仅存在模板 `ingestion/flume/flume-taildir.conf`） | **BLOCKED**（无 Flume 运行体） |
| 6 | 55 条黄金数据跑集群小链 | 集群档不可达；**本地档已完成**（P1-06 T2，55 行黄金链，见 P1-06 验收目录） | **集群档 BLOCKED**；本地档已交付（另账） |
| 7 | 固定 seed 的 1,000 条数据跑集群链 | 数据侧本机可行（生成器 8092 在跑、支持固定 seed）；**集群侧不可达**（同 2/4） | **集群档 BLOCKED** |
| 8 | 同一输入比较本地与集群核心指标同值 | 依赖第 7 步产物 | **BLOCKED**（依赖 7） |

**附：本机为什么起不了集群 —— 实测而非推断**

- Hadoop 四个 `site.xml` 均为**空配置**（0 property）、无 `hive-site.xml`、无 NameNode 数据目录 ⇒ **从未做过 `hdfs namenode -format`，也从未配过伪分布式**。
- `D:\Develop\hive-docker` **是一个空目录**（`Get-ChildItem -Recurse -File` 零命中）⇒ 只有意图，没有可用的容器化 Hive。
- Docker **CLI 在**（`C:\Program Files\Docker\Docker\resources\bin\docker.exe`），但 **daemon 未运行**（`npipe:////./pipe/dockerDesktopLinuxEngine` 不存在）⇒ 容器化伪集群**本轮不可用**（未启停 Docker）。
- Kafka / ZooKeeper **未安装**（§6.5 末条与 Flume agent 位置相关时需要）。
- **一处必须先纠正的粗读数**：`beeline` **不在 PATH**，但 **Spark 发行版自带 `D:\Develop\spark-3.5.1-bin-hadoop3\bin\beeline.cmd`**（该发行版编译参数含 `-Phive -Phive-thriftserver`）⇒ 正确表述是"**未安装独立的 Hive 客户端，但 Spark 自带 beeline**"，**不得**写成"Hive 客户端完全缺失"。这对 F-39（`beeline --hivevar` 通道）的后续处置有直接影响：该通道的**客户端**本机可取得，缺的是**可连的服务端**。

---

## 2. §6.4.1 八条接入缺口：逐条现状（read-only，含 file:line 证据）

| # | 指导书要求 | 本轮实测现状 | 证据 |
|---|---|---|---|
| 1 | 生产采集不得把 `landingUri` 当本地路径，必须经 `LandingStorage`/连接器抽象 | **缺口仍存在** | `IngestionService.java:134-138`（`Files.createDirectories` accepted/quarantine、`Files.list(eventsDir)`）、`:311-313`（`Files.writeString` 写 manifest） |
| 2 | `HdfsLandingStorage` 健康检查必须在 `<base>/_health/<uuid>` 做 create/write/read/rename/delete | **缺口仍存在** | `HdfsLandingStorage.java:110-113` 的 `healthCheck()` 只做 `fs.listStatus(rootPath)`；同文件 `:66`、`:89` 证明其它方法有真实读写能力，唯独健康检查是"只列根目录" |
| 3 | Flume Taildir 无 `eventType` header，首版应按 source+采集时间写 raw | **缺口仍存在** | `ingestion/flume/flume-taildir.conf:41` `hdfs.path = /landing/events/%{eventType}/dt=%Y%m%d/hour=%H`（`:4` 注释同）；`:22` 为 TAILDIR position 文件，**未产生 `eventType` header** |
| 4 | SSH 提交不得返回伪 `ssh-*` externalJobId | **缺口仍存在** | `SshSparkSubmitter.java:42` `String jobId = "ssh-" + System.currentTimeMillis()` |
| 5 | 远端命令只允许固定 wrapper + 白名单；SSH 必须校验 `known_hosts`，禁止 `StrictHostKeyChecking=no` | **缺口仍存在（且违反明令）** | `SshSparkSubmitter.java:81` `config.put("StrictHostKeyChecking", "no")`；全仓 `analytics-server/**/*.java` 中 `known_hosts` 零命中；"白名单"命中全部属 **AI-SQL 层**（`SqlPolicy.java:65` 等），**无远端命令白名单** |
| 6 | 第一版远程执行优先 `master=yarn, deployMode=client` | **未强制（走配置）** | `JobCommandBuilder.java:95-97` 仅在非 local 时把 `profile.deployMode()` 写入命令；真实 `runtime_profile` 当前为 LOCAL/LOCAL ⇒ **不得写成"已支持集群"** |
| 7 | 版本化 jar 放 `/apps/graduation/spark-jobs/<gitCommit>/...jar` 并保存 SHA-256 | **缺口仍存在** | `git grep -e 'apps/graduation' -e 'sha256'` 在 `analytics-server/**` **零命中**（**正向对照**：同模式在 `docs/**` 463 行、`scripts/**` 3 行、`spark-jobs/**` 1 行 ⇒ 零命中是真实范围属性，不是空转 grep）；jar 仍是**单一可变路径** `runtime_profile.spark_job_jar_uri`（`JobCommandBuilder.java:145-147`、`RuntimeProfileSnapshot.java:35`） |
| 8 | Flume 是 at-least-once，平台只能靠 checkpoint/manifest/`(source_instance_id,event_id)` 去重提供幂等 | **未实测（本轮不判）** | 平台确有 `file_checkpoint`（105 行）与 manifest 机制，但"去重键是否就是该二元组、是否真幂等"需读 ODS/去重代码后才能结论 ⇒ **按纪律标 未实测**，不出结论 |

---

## 3. §6.5 九项 `USER-INPUT`：逐条「本机已确定的部分」+「仍需你回答」

> 指导书原话：以下内容**不得由 Agent 猜测**。本轮只填"本机实测已能确定"的一半，其余**留空待你回答**。

| # | §6.5 条目 | 本机已确定 | 仍需你提供 |
|---|---|---|---|
| 1 | node01–03 主机名/IP 与各角色 | 本机 = `dahaishui`（仅虚拟/链路本地地址） | 是否有真集群？若有：三节点主机名/IP 与 NameNode/DataNode/RM/NM/Hive/Spark 角色分布 |
| 2 | NameNode URI/端口、可写根目录、warehouse 路径、服务用户权限 | 本机 8020 无监听 | `hdfs://<host>:<port>`、可写根目录、warehouse 路径、提交用户与权限 |
| 3 | Spark 运行模式（yarn client / yarn cluster / standalone）、队列、提交命令路径 | 本机 Spark 3.5.1（含 YARN 支持），当前 profile 为 LOCAL | 模式、队列名、`spark-submit` 绝对路径 |
| 4 | HiveServer2 JDBC 或 Metastore URI、数据库名前缀 | 本机 9083/10000 无监听；库名前缀机制已由 P2-07 迁到源级 `source_registry.warehouse_prefix` | HS2/Metastore URI；集群侧是否复用同一前缀规则（F-39 的手工通道是否纳入强约束） |
| 5 | SSH 主机/端口/用户/密钥引用/是否跳板机 | 本机 `SshSparkSubmitter` 现为 `StrictHostKeyChecking=no`、无 `known_hosts` | 目标主机/端口/用户、凭据引用方式、是否经跳板机 |
| 6 | Hadoop/Spark/Hive/Java 版本与安装目录；jar 在提交节点还是 HDFS | 本机：Java 17.0.12 / Spark 3.5.1(Hadoop 3.3.4) / Hadoop 3.3.4；jar 现为本地绝对路径 | 集群侧版本与安装目录；jar 放提交节点还是 HDFS（`/apps/graduation/...`） |
| 7 | 是否启用 Kerberos 及 principal/keytab 提供方式 | 本机未启用 | 是否启用；若启用，keytab/principal 的安全提供方式 |
| 8 | Flume agent 运行位置、商城事件到 spool 的传输方式与监听目录 | 本机 Flume **未安装** | agent 主机、spool 目录、商城→spool 的传输方式 |
| 9 | 平台/商城/MySQL/集群之间实际开放的端口 | 本机开放：8090（商城）、8092（生成器）、3306（MySQL）；**8091 当前未运行**（见 F-07 补记） | 集群侧与跨机实际开放端口、是否需要跳板/隧道 |

---

## 4. 三条可选路径（需你裁决，三轮内不定则 M3 保持 `BLOCKED`）

| 路径 | 需要什么 | 代价/风险 |
|---|---|---|
| **A. 本机伪分布式**（Windows 上 Hadoop 3.3.4 单机伪分布 + Hive） | ① 你批准环境变更；② 格式化 NameNode 并写四个 `site.xml`；③ 引入 Hive 服务端（装独立 Hive 或启动 Docker Desktop 用容器）；④ 装 Flume（或按 §6.4.1-3 改走"本地等价实现"并如实标注） | 本机 Windows 跑 HDFS/Hive 落地成本高、`winutils` 兼容问题多；Docker 当前 daemon 未运行，需你启动 |
| **B. 外部真集群** | §6.5 九项信息（第 3 节表格右列） | 最贴近"基于 Spark 大数据平台"的论文口径；需要你提供环境 |
| **C. 不做集群档，M3 收敛为"本地 1,000 行链 + 集群口径全标未取证"** | 无（本机即可推进 55→1,000 的**本地**链） | 论文里"集群"只能写成设计/预检，**不得写成已实测**；E4 一级证据缺失 |

**总控建议**：先按 **B** 问你一轮（回答成本最低、最符合论文口径）；若集群确实不存在，再选 **C** 并把 §6.4.1 的八条缺口作为"接入前必改项"如实登记（本轮已逐条给出 file:line）。**路径 A 不建议在论文周期内冒险**——它会引入 Windows 上的 HDFS/Hive 兼容风险，且 Flume 仍需另装。

---

## 5. 本轮不声称 / 边界

- **不声称**：M3 的任何集群档已完成；HDFS/Hive/YARN/Flume 任一组件可用；§6.4.1 第 8 条已判定结论；1,000 行链已跑过；`beeline` 可连通任何服务端。
- **未做**：未启停进程（含未启动 Docker）、未格式化和配置 Hadoop、未安装任何软件、未写库、未跑 Maven、未改代码/契约/配置。
- **对既有结论的影响**：无。E4 仍为未取证；F-39 的处置不变（本轮仅补充"客户端可通过 Spark 自带 beeline 取得"这一事实）。
