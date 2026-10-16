# N31-02 WAIT_LANDING 存储 URI 数据库实证

日期：2026-09-30（Asia/Shanghai）  
工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`  
分支 / HEAD：`feature/v3-development` / `300c4c9b7bfc25b8a058c62ad89f349bb15121b8`  
RunId：`n3102waituri_20260930_2042_a1`  
结论：本项窄判据 **PASS**；不等于整条流水线或 N31-02 批次完成，也不构成总控签收。

## 范围与安全边界

本次只验证：平台选用 HDFS Landing 后，真实 HTTP 摄取与流水线 `WAIT_LANDING` 阶段是否把完整、脱敏后的 `acceptedStorageUri` 与输入 `batchId` 写入 RunId 隔离 MySQL 的 `pipeline_stage_run.evidence`。不尝试完成后续 Spark 数仓计算。

- MySQL 目标为 WSL `127.0.0.1:3307`，服务指纹 `dahaishui:3307` / server UUID `de8ebbea-aff4-11f1-8037-00155d5dba47`。本 RunId 的新隔离 schema 为 `n3102waituri_20260930_2042_a1_analytics_meta` 与 `..._analytics_metric`；应用账户受限于本 RunId schema。测试凭据来自安全进程环境与仓库既有 credref 通道，未回显、未写入文档。
- Windows MySQL `3306` 未连接、未查询、未修改。平台日志对 `:3306` 的扫描为零命中。
- 使用已有 WSL Hadoop 3.3.4 单节点；未格式化或替换 HDFS 元数据目录。新 HDFS 根在写入前确认不存在，仅写入本 RunId 专属目录。
- 未删除 HDFS/MySQL 测试对象；RunId 隔离 schema、HDFS 原始夹具和本地日志保留供复核。平台进程已停止，8091 已释放；WSL 3307 与 HDFS 服务保留运行以便后续获批开发复用。
- V3.0 指导书与设计文档未修改；本次不改产品源码、不提交、不推送。

## 执行与结果

1. 正式隔离准备脚本在 3307 成功创建本 RunId 专属 schema 与受限账号。首次调用因调用侧未设置脚本要求的 `V25IT_ADMIN_PWD` 变量而认证失败；那次没有建库或执行 IT 写入。修正为脚本规定的环境变量转递后成功，未将口令放入参数或输出。
2. 在既有单节点 HDFS 新建 `/landing/n3102waituri_20260930_2042_a1/raw/dt=20260918/hour=20/`，放入夹具 `events.20260930_2042.jsonl`：99 行、34,192 字节。平台以重建后的 `platform-app` JAR 启动，SHA-256 为 `A55EB2C9D8C40E02DD0F96658C41E205996E55B421B0831624317179F5312F1D`。
3. Runtime Profile 1 配置为 `LOCAL` + `FLUME_RAW`，Landing 根为 `hdfs://127.0.0.1:19000/landing/n3102waituri_20260930_2042_a1`，来源 `sourceId=1`。环境测试结果：landing PASS、spark-submit PASS、metric PASS；Hive 对 LOCAL 环境为不适用（SKIPPED），随后 profile 激活成功。
4. 真实 HTTP 摄取返回 `batchId=1`、`recordCount=99`、`quarantineCount=0`、`noNewData=false`。
5. 新建流水线 `runId=1` 后，直接对 RunId 隔离库执行只读 SELECT，回读到：

   - `stage_code=WAIT_LANDING`、`status=SUCCESS`；
   - `evidence.batchId=1`，与本次摄取返回的 batchId 相同；
   - `evidence.acceptedUri=accepted/1`（原有相对 URI 契约保留）；
   - `evidence.acceptedStorageUri=hdfs://127.0.0.1:19000/landing/n3102waituri_20260930_2042_a1/accepted/1`；
   - URI 的 scheme、authority、端口与 HDFS 路径完整；不含 userinfo、query 或 fragment；
   - `acceptedRecords=99`、`sourceId=1`、`sourceCode=mock-mall`。

## 有意停止点与证据位置

为把验证限定在目标阶段，profile 的 Spark 作业包路径设为一个明确不存在的隔离探针路径。故下一阶段 `INIT_SCHEMA` 按预期失败；提交日志显示 Spark 找不到该 JAR，并因未产生 `JobResult` 结束。本结果**只认 `WAIT_LANDING` 成功及数据库回读判据**，不声称 Spark 计算、ADS 发布或流水线 SUCCESS。

可复核材料：

- 隔离运行目录：`target/v25-it/n3102waituri_20260930_2042_a1/`
- 平台启动日志：`target/v25-it/n3102waituri_20260930_2042_a1/platform/logs/platform.log`
- Spark 探针日志：`target/v25-it/n3102waituri_20260930_2042_a1/landing-local/logs/pipeline-1-INIT_SCHEMA-sci-a1__lp-1790773317347-b98efa.log`
- 回读对象：隔离 schema `n3102waituri_20260930_2042_a1_analytics_meta.pipeline_stage_run` 中 `run_id=1` 的 WAIT_LANDING 行。此结果形成时只读查询原始结果由终端记录保留；2026-09-30 已将回读行脱敏持久保存，见 `D:\Develop_code\GraduationProject-wt\v3-archive\n3102\waituri-20260930-a1\evidence\pipeline-stage-run-readback.tsv`。文档与归档均不保存凭据。

## 验收边界更新

此前记录的“尚未直接实测 `WAIT_LANDING.acceptedStorageUri` 落库值”已由本证据闭合。N31-02 腿③及整批状态**不因此改为 PASS**：分类名称字典、坏样本与 live unknown 覆盖、新 ADS 表失败注入等既有局限仍按 `BATCH-N31-02-LEGC-RESULT.md` 保留；整批仍待总控复核/签收。真实 LLM 与远程集群亦仍属未验收范围。
