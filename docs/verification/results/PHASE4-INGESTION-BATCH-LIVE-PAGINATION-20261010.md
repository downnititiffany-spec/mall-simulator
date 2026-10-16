# 阶段4 采集批次真实非空分页与采集链路验证（2026-10-10）

## 范围与身份

- 当前 task-owned 单节点 RunId：`cloud_261010_005249_1c73ec`。API 为 loopback `127.0.0.1:8091`；登录凭据重置范围仅为该 RunId 的隔离 MySQL 3307 元数据库 `cloud_261010_005249_1c73ec_meta.sys_user.admin`，并恢复为仓库现有隔离演示种子哈希；未连接 3306。
- 用户已授权仅重置隔离库管理员凭据，并授权经正式业务 API/流程生成少量隔离测试数据。无 profile 创建、编辑、激活或切换。
- 密码/数据库凭据/token 未写入报告、环境快照或日志；登录会话在验证后调用 logout。

## 输入与采集

- 通过商城真实登录及用户注册 API 建立两条隔离合成用户事件。Outbox JSONL：`/home/asus/graduationproject-runs/cloud-e2e/cloud_261010_005249_1c73ec/mall-landing/events/2026101011.jsonl`，2 行，895 bytes，SHA-256 `af307a8948f5ec4d93bde08a39fd029d7993892d19c7e0e0037273003ae53bd5`。
- 同一文件以复制方式进入当前 RunId 的 Flume spool，完成标记生成；现存 Flume agent（当前配置 `a1.channels.c.type=memory`）将它投递到 HDFS Landing：`hdfs://127.0.0.1:19000/landing/cloud_261010_005249_1c73ec/raw/dt=20261010/hour=11/events-.1791603773853.jsonl`。HDFS 输出 2 行，SHA-256 与 Outbox 输入一致。未停止或重启 Flume/HDFS。
- 平台正式 `POST /api/v1/ingestion/runs`：第1轮 HTTP 200、`SUCCESS`、`noNewData=false`、recordCount=2、quarantineCount=0；第2轮 HTTP 200、`SUCCESS`、`noNewData=true`、recordCount=0、quarantineCount=0。
- 边界说明：平台采集 API 使用当前配置的 RunId 本地 Mall Landing 输入目录；本证据同时证明 Mall→Flume→HDFS 文件链路和平台对该本地 Outbox 输入的采集/断点行为，不声称平台从 HDFS 回读。

## 真实管理员分页 API

- 使用真实管理员登录会话只读请求 `/api/v1/ingestion/batches/page`，`size=1`。
- 真实响应 `total=2`、`totalPages=2`。升序第1页 ID=1；降序第1页 ID=2、第2页 ID=1；页间无重复且边界严格递减，排序元数据与请求一致。
- 结果：仓库验收器 `scripts/verify-ingestion-batch-pagination.ps1 -PageSize 1` 实际 loopback 运行 PASS；total、两页 ID 排序与跨页边界检查通过。未直接 SQL 写入任何业务数据。

## 限制

- 本结果闭合阶段4采集批次真实非空分页这一具体门槛，不代表阶段4整体、阶段6真实 LLM、阶段8最终复现或 N31-05 验收完成。
- 本次 Flume 使用 memory channel，因此不证明 File Channel 持久性或 Flume 进程故障恢复。未验证 RuntimeProfile 管理员列表/Thrift HS2 管理流程、数据库业务表回读、HDFS 到 Hive ODS 的完整链路、第二批不同输入、失败恢复或最终浏览器 Ops 页面；不得扩展本结论。
- 原始 Mall JSONL、Flume spool 的 `.COMPLETED` 标记、HDFS 文件及隔离测试记录均留在 task-owned RunId 目录。服务未停止，数据未清理。
