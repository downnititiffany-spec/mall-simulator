# 阶段2/3/7 隔离 RunId Pipeline 首跑失败：Spark Hive Metastore 版本冲突（2026-10-10）

## 范围与调用

- RunId：`cloud_261010_040051_b2ab54`；平台 loopback `8091`；通过正式管理员登录/API 提交 `POST /api/v1/pipeline-runs`，pipelineCode=`ODS_TO_ADS`、runtimeProfileId=1、sourceDataVersion=`mall-hdfs-chain-cloud_261010_040051_b2ab54-20261010-a`，Idempotency-Key 唯一。没有直连数据库、没有连接 3306。
- API 创建 runId=1。`WAIT_LANDING` 成功，选择 batchId=1，`sourceId=1`，`acceptedUri=accepted/1`，`acceptedRecords=1`；随后 `INIT_SCHEMA` 失败，run 状态 `FAILED`、errorCode=`RUN_JOB_FAILED`。没有继续到 ODS/ADS 或指标发布。
- 当前批次只读 API 显示 batchId=1 为 `QUARANTINED`（recordCount=1、quarantineCount=1）；选择器按 READY 且 accepted+quarantined 非空选择批次。批次状态与 manifest 计数/输入含义需要结合既有契约继续核对，本次未修改选择器。

## 失败证据

- 受控作业日志：`/home/asus/graduationproject-runs/cloud-e2e/cloud_261010_040051_b2ab54/mall-landing/logs/pipeline-1-INIT_SCHEMA-sci-a1__lp-1791612018843-f68343.log`。
- Spark 作业端报错：`Builtin jars can only be used when hive execution version == hive metastore version. Execution: 2.3.9 != Metastore: 3.1.3.` Spark 3.5.1 driver 已启动并拉取作业 jar，约 2.5 秒后 `sci` 作业失败；不是 API 创建或 Spark-submit 可执行性失败。
- 失败后经真实只读 API 确认 profile ID 1 仍唯一 ACTIVE、version=2；`/api/v1/metrics/snapshots?limit=5` 返回空列表。ID 2/3 保持 TESTING；未激活或切换 profile。

## 结论与后续

- 真实链路尚未闭合：Pipeline 未进入 Hive ODS 读写或 ADS/质量/发布阶段；RuntimeProfile `/test` 的 Spark 项通过不能替代本 Pipeline 的 catalog/metastore 初始化验证。
- 下一步应查清 task-owned Hive 3.1.3 配置与 LOCAL/SINGLE_NODE 嵌入式 Hive 2.3.9 的边界，修复 Spark 作业实际加载版本/Metastore 的冲突，并先用定向测试验证配置；然后用新的 sourceDataVersion/幂等键处理未消费输入。不得对 run 1 盲目重复 POST，也不得切换 profile 或声称 ODS/ADS 成功。
- 本轮没有修改业务代码、没有新增/删除业务记录，没有切换或更新 profile，没有发布快照；3306 未访问。阶段2/3/7和项目验收保持开放。
