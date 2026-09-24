# G31-03 当前工作树 E3 LOCAL 小样本链路结果

> 结论：**本次 LOCAL E3 真实链路 PASS**。该结论只适用于下述工作树、构建产物、隔离数据库和单机 LOCAL 路径，不代表 G31-03 或指导书阶段 7 完成。

## 1. 执行范围

- 执行时间：2026-09-23 19:50–19:53（Asia/Shanghai）。
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `feature/v3-development`，代码基线 HEAD `2a11b60a24332e02fc3b4927dbef5e524a3b5b15`；工作树包含未提交开发改动，本次 JAR 从该工作树打包。
- RunId：`g3103y_20260923_193657_b1`；HTTP attempt：`attempt-20260923_195036_488`。
- 输入：`fixtures/source-a-e3/e3-events.jsonl`，99 行，SHA-256 `47F09C3DBF6C85C37699311E8F6DA6C4C6FE08B5B4690F1F7DDEB7270B3F7092`；数据来自 `mock-mall` canonical E3 夹具，**不覆盖异构字段映射执行验证**。
- JAR：`platform-app-0.1.0-SNAPSHOT.jar` SHA-256 `7A725AC2430DA7EA9B9AAF6E4A44A7A8455E7BBFEA1FE615EB2B8E322580B756`；`spark-jobs-0.1.0-SNAPSHOT.jar` SHA-256 `B5554D7E93426A4D8D2658E2A07C9CDE898F3F5CAC0A20512155AB9E356C1EE8`。

## 2. 隔离与运行形态

- MySQL 8.0.41 在 WSL 新建的独立数据目录运行，监听 `127.0.0.1:3307`；只创建带 RunId 的测试 schema。Windows MySQL `3306` 未访问。
- 平台进程在 Windows/JDK 17 运行；Spark 使用 Windows Spark 3.5.1 `spark-submit.cmd`，`master=local[1]`，本地文件 `file:` 路径、单机 Derby metastore 和本地 warehouse。输出路径是本地文件系统，不是 HDFS。
- 采集和 pipeline 完成后，平台进程及其 Spark 子进程已由脚本按 PID 树停止；随后以准确数据目录/端口核验并正常关闭本次 WSL MySQL。当前 8091 与 WSL 3307 均无监听。

## 3. 结果

- 运行环境检查与 LOCAL profile 激活成功。
- 采集批次 `1`：`SUCCESS`，1 个文件、99 条记录、0 条隔离、0 条系统错误。
- Pipeline run `1`：`SUCCESS`，8/8 阶段成功；10/10 Spark job 成功，均有 `externalJobId` 和非空日志文件。
- 发布快照：`S20260918_1`，来源 `source_id=1`、环境 `runtime_profile_id=1`，MySQL 中为唯一 `ACTIVE` 快照。
- 8 张 ADS 导出/数据库行数一致：overview 1、sale trend 1、behavior funnel 4、active trend 1、hot product 5、product conversion 5、user profile 6、data quality 4；合计 27 行。
- MySQL `metric_value` 有 14 项。使用独立 `fixtures/source-a-e3/oracle.py --machine` 对照 `EXPECTED_A`，**14/14 的指标码、period 与数值完全一致**。
- 指标发布证据含 16 项检查，16 项均通过；证据 JSON 内 8 阶段、10 作业、快照身份、输出分区与日志均已回读核验。

## 4. 验证命令与边界

- DB-free HTTP 契约补测：`RuntimeProfileControllerHttpTest` 3/3、`DecisionHttpLifecycleOfflineTest` 1/1；合并单 reactor fresh 运行 4/4，BUILD SUCCESS。
- 两个当前工作树产物均以 `mvn -DskipTests package` 重打包成功；Spark POM 声明 Scala 2.12.19，而 Spark 3.5.1 运行包声明 Scala 2.12.18，本次链路虽成功，版本差异仍应后续统一或形成兼容性说明。
- 原始隔离 HTTP 结果及作业日志位于 `target/v25-it/g3103y_20260923_193657_b1/http/attempt-20260923_195036_488/`（本地 ignored 运行产物）；本文件记录可追溯的摘要与指纹。
- **不代表**：HiveServer2/HDFS、Flume HDFS sink、远程/多节点集群、第二商城异构映射、浏览器看板/AI/决策在此新快照上的完整联调，或 G31-03 完成。这些仍需各自证据。
