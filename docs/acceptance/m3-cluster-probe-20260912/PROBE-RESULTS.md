# M3 接入 · 首个「集群写」作业实测记录（2026-09-12）

**用户授权**（2026-09-12，三问三答，原文选项）：A)「授权我以 root 身份在 `/graduation` 建目录」；B)「先提 1 个最小探针作业，再跑 E4」；C)「暂时不需要」SSH。
**发起方**：总控（本机 Windows 工作站），工具链 `D:\soft\hadoop\hadoop-3.3.4` + `D:\Develop\spark-3.5.1-bin-hadoop3`（Spark 3.5.1 / Scala 2.12.18）。
**隐私**：原始输出中的内网地址落库前掩码为 `192.168.18.10x`；主机名 `node01/02/03` 保留（见 `MASKING.md`）。
**与 §只读探测的关系**：`README.md`（只读探测）§6 写明「未提交任何 YARN 作业、未写任何字节」——那**在当时为真**；本文档是其后发生的**写操作**记录，冲突处以本文档为准（本文档更晚）。

---

## 1. 结论（全部为实测）

| # | 事项 | 手段 | 实测结果 | 判定 |
|---|---|---|---|---|
| 1 | 可写 HDFS 根 | `hdfs dfs -mkdir -p /graduation/{warehouse,jars,staging,probe}` | 全部 OK；`ls /` 由 8 项 → **9 项**（新增 `/graduation`，15:35） | 建立 ✓ |
| 2 | HDFS 写入闭环 | `-put` 3 字节文件 → `-cat` 回读 `/graduation/probe/write-probe.txt` | 回读得 `ok`；`ls` 显示 `-rw-r--r-- 3 root supergroup 2` | 客户端写入 ✓ |
| 3 | Spark 组装分发（一次性） | `hdfs dfs -put -f <本机 jars\*> /graduation/jars/spark-3.5.1/` | **252 个 jar / 337,677,190 B / 24.9 s**；抽样 `spark-core_2.12-3.5.1.jar` 远端 14,667,703 B = 本机 14,667,703 B | 可复用 ✓ |
| 4 | **YARN 提交通道** | `spark-submit --master yarn --deploy-mode cluster`（本机 Windows 客户端） | `application_1789195359269_0004`：ACCEPTED 15:41:21 → RUNNING 15:41:25 → **FINISHED / SUCCEEDED** 15:41:39（集群侧 `elapsedTime` 19.3 s） | **打通** ✓ |
| 5 | 容器内写 HDFS | 探针在容器里写路径并回读长度 | `/graduation/probe/from-container/container-node03.txt` `len=70` `replicas=3` | ✓ |
| 6 | 容器内 Spark 读回 | 同上，`spark.read.text(...).count()` | `hdfs.readback.lines=1` | ✓ |
| 7 | 容器内 Hive Metastore | `SHOW DATABASES`（**只读**） | `metastore.reachable=TRUE`；7 库 `default, exam, ods, scott, shop, tmp, yjxxt` | ✓ |
| 8 | **容器 JVM 版本** | `System.getProperty` 在容器内取 | `java.version=1.8.0_351`、`java.class.version=52.0`、`java.home=/usr/java/jdk1.8.0_351-amd64/jre` | **Java 8** ✓（关键约束，见 §4 F-50） |
| 9 | 运行环境其他实测值 | 同上 | `spark.version=3.5.1`、`scala.version=2.12.18`、`master=yarn`、`sparkUser=root`、`containerHost=node03`、`defaultFS=hdfs://node01:8020`、`defaultParallelism=2` | ✓ |
| 10 | Spark 事件日志 | `spark.eventLog.dir=hdfs://node01:8020/graduation/eventlog` | `application_1789195359269_0004_1` 182,846 B；首行 `{"Event":"SparkListenerLogStart","Spark Version":"3.5.1"}`（JSON lines，普通文本工具可读） | 新证据通道 ✓ |
| 11 | **容器 stdout 读取通道** | `yarn logs -applicationId …` | **2398 行文本**，含全部 19 行 `PROBE` 输出、`DAGScheduler` 调度细节、容器启动 `exec /bin/bash …` 行 | **可观测** ✓ |

探针原始输出（HDFS 回读，未经手工转录）：`yarn-probe-result.txt`；提交日志：`yarn-probe-submit.log`；容器日志：`yarn-logs-0004.log`；RM 元数据：`yarn-app-0004.json`。

## 2. 四次提交的失败→修复链（可复用的接入知识）

| 次序 | 现象（原文摘录） | 根因 | 修法 | 结果 |
|---|---|---|---|---|
| 1 | `SparkException: When running with master 'yarn' either HADOOP_CONF_DIR or YARN_CONF_DIR must be set in the environment.` | Spark 客户端**在联网之前**就要求 YARN 配置目录 | 新增最小客户端配置目录 `conf/`（3 个 xml），设 `HADOOP_CONF_DIR`/`YARN_CONF_DIR` | 进入提交阶段 |
| 2 | `application_…_0002` FAILED（AM exit **1**，重试 2 次）：`UnsupportedClassVersionError: probe/ClusterProbe has been compiled by a more recent version of the Java Runtime (class file version 61.0)` | 我用 JDK17 编译 ⇒ major 61，而**集群容器 JVM 是 Java 8**（上限 major 52） | `javac --release 8` 重编（实测产物 `major=52`） | 进入运行阶段 |
| 3 | `application_…_0003` FAILED（AM exit **13**）：`org.apache.spark.SparkException: Exception thrown in awaitResult: Caused by: java.io.FileNotFoundException: File does not exist: hdfs://node01:8020/graduation/eventlog` | **Spark 不会创建 `spark.eventLog.dir`**，仅使用已存在目录 | 先 `-mkdir -p /graduation/eventlog` | — |
| 4 | `application_…_0004` **SUCCEEDED** | — | — | ✓ |

**教训**：三次失败全部发生在「作业代码被执行之前」（客户端校验 → JVM 版本 → Spark 启动期 IO），说明**提交通道类问题必须用最小作业先证伪**，不要拿真实链路当探针。

## 3. 最小客户端配置（`conf/core-site.xml`、`conf/yarn-site.xml`、`conf/hive-site.xml`）

- 只写「客户端连出去」必需的地址：`fs.defaultFS=hdfs://node01:8020`；RM `node01` 的 8032/8030/8031/8033/8088；`hive.metastore.uris=thrift://node01:9083`。
- **刻意不写** `yarn.nodemanager.*`、`yarn.log-aggregation-enable`、队列与资源上限等集群侧参数，避免客户端配置反向覆盖集群行为。
- 该目录会被 Spark 打包成 `__spark_conf__.zip` 下发到 AM/Executor 容器（证据：`yarn-logs-0004.log` 中 `--properties-file $PWD/__spark_conf__/__spark_conf__.properties`），因此容器侧看到的就是这里的取值。

## 4. 对项目的影响（新登记发现）

- **F-50（约束级）集群容器 JVM = Java 8u351 ⇒ `spark-jobs` 产物必须 major ≤ 52**。实测现有产物**已满足**：`spark-jobs-0.1.0-SNAPSHOT.jar` 83 个 class 全 major=52；`p2-01-built/spark-jobs-0.1.0-SNAPSHOT-p2-01-e3.jar` 105 个 class 全 major=52；`predecessor-live-jar-20260912-133435.jar` 104 个 class 全 major=52。原因是 Scala 2.12 默认 `-target:jvm-1.8`，且 `spark-jobs` 当前 **0 个 Java 源文件**（pom 里的 `maven.compiler.target=17` 只作用于 Java 源）。
  ⇒ **本次不需要改构建**；但**若今后向 `spark-jobs` 加入 Java 源文件**，17 目标会让作业在集群上**直接 UnsupportedClassVersionError**——必须写成硬约束（或改 `--release 8`）。
- **F-51 `spark.eventLog.dir` 必须预先存在**：不存在 ⇒ AM exit 13（且会重试 2 次才判 FAILED），属"启动即失败"，不看日志极难定位。
- **F-52 Windows 客户端提交 YARN 必须先设 `HADOOP_CONF_DIR`/`YARN_CONF_DIR`**：这是 Spark 客户端侧校验，早于任何网络动作；本机没有 `*-site.xml`（全库检索只命中 `references/sample-projects/`）。
- **F-53 容器 stdout 不是可靠证据通道**：本集群日志聚合为 bucket TFile（`/tmp/logs/root/bucket-logs-tfile/<bucket>/<appId>/<node>_<port>`，二进制；**不存在** `/tmp/logs/root/logs` 标准布局）。正解二选一：① `yarn logs -applicationId <app>` 读取（本次验证 2398 行可读）；② 作业把结论**写进 HDFS**（探针即采用此约定）。
- 集群内部路径（只读观察所得，供排障）：JDK `/usr/java/jdk1.8.0_351-amd64`、Hadoop `/opt/yjx/hadoop-3.3.4`、NM local `/var/yjx/hadoop/ha/nm-local-dir`、容器 userlogs `/opt/yjx/hadoop-3.3.4/logs/userlogs/<appId>/<containerId>/{stdout,stderr}`。
- **集群非独占**：同一时段观测到他人应用（`com.yjxxt.exam.Job01` SUCCEEDED、`Thrift JDBC/ODBC Server` FAILED）与 `/user/root/.sparkStaging`（09-12 14:52）⇒ 本项目作业按最小资源提交（1 executor × 1 core × 1 GB，队列 `default`），不抢占。

## 5. 边界（禁止越界表述）

- 本文档只证明：**提交通道可用、容器内可写 HDFS、容器内 Metastore 只读可达、容器 JVM 为 Java 8**。
- **未**创建任何 Hive 库/表（Metastore 操作仅 `SHOW DATABASES`）；**未**提交本项目任何作业（`JobRunner` 一次都没跑过）；**E4（集群 1,000 行）仍是零证据**。
- 写入范围仅 `/graduation/**`：`jars/`（252 个 Spark jar）、`probe/`（3 个自建文件）、`eventlog/`（1 个应用事件日志）、`staging/`（Spark 自建自删）。**未触碰** `/hive`、`/user`、`/yjx`、`/exam`、`/dolphinscheduler`、`/logs`、`/history` 任何内容。
- 未使用 SSH；未读取他人目录内容。

## 6. 下一步（E4 真实链的前置已核实项）

- 入口：`com.graduation.analytics.job.JobRunner`（`--runtimeProfileId --jobCode --businessDate --attemptNo [--key=value…]`），链路 `sci → odl → bdw → dim → tdw → usw → fna → dqc → pub → mxp`（`JobRegistry`）。
- 作业 jar：`spark-jobs/target/p2-01-built/spark-jobs-0.1.0-SNAPSHOT-p2-01-e3.jar`（105 class 全 major=52；mtime 09-12 14:15:55 **晚于**最新源文件 14:05:46 ⇒ 无需重建）。`jackson-databind-2.15.2` 由 Spark 发行版自带且已上传 HDFS ⇒ 不需要额外 `--jars`。
- 观测：每个作业的 `JobResult` JSON 打在 stdout ⇒ 用 `yarn logs -applicationId` 回收。
- **仍待核实**：`sci`（表初始化自举）是否支持 HDFS 路径与命名空间前缀；1,000 行 landing 输入的来源与上传方式。
