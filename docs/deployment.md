# 部署说明

> 依据文稿 §3.3（三种运行环境）与 §14.3（交付物）。LOCAL 模式**不需要** Hadoop/Hive/Spark/Flume，
> 由本机 MySQL + 文件 Landing 承担等价角色；集群模式沿用同一套代码与配置模板切换。

## 模式速览

| 模式 | 文件/数仓 | 提交 | 本仓库对应 |
|---|---|---|---|
| LOCAL（当前可用） | 本地 Landing + MySQL | 无 | mall-simulator 内置采集/计算 |
| SINGLE_NODE | 单机 HDFS + Hive | spark-submit（本机） | deploy/single-node 模板 |
| REMOTE_CLUSTER | node01-03 HDFS + Hive | SSH + spark-submit | deploy/cluster 模板 |

## LOCAL 一键部署（Windows 实测路径）

前置：JDK 17、Maven 3.9+、Node 18+、MySQL 8（服务运行）。

```bash
# 1. 数据库
#    MySQL 需存在可建库账号；库由应用自动创建（createDatabaseIfNotExist）+ Flyway 迁移
#    （含 V6：自动创建 AI 执行器专用只读账号 mall_reader，仅 SELECT 权限）
# 2. 后端
cd mall-simulator
set MALL_DB_PASSWORD=你的密码        # 或环境变量；绝不写进代码/配置库
set MALL_READER_PASSWORD=只读账号密码 # 生产必须覆盖默认值（V6 placeholder）
mvn spring-boot:run                 # :8090
# 可选 AI：set LLM_BASE_URL=... LLM_API_KEY=... LLM_MODEL=deepseek-chat
# 3. 前端
cd web
npm install && npm run dev          # :5173（/api 代理到 8090）
# 4. 测试/回归
mvn test                            # 88 项（含只读账号安全测试）
mvn -Dtest=GoldenE2ETest test       # 黄金端到端
```

数据位置（LOCAL）：`mall-simulator/landing/events/*.jsonl`（事件）、MySQL `mall_simulator` 库
（业务/出箱/快照/审计/决策）；清理演示数据：停服后删除上述目录与库表（或 `DELETE FROM event_outbox`）。

## 集群部署步骤（SINGLE_NODE / REMOTE_CLUSTER，待环境实跑）

1. 部署 Hadoop 3.3.x + Hive 3.1.x + Spark 3.5（建议基线，实测版本为准）→ `docs/compatibility-matrix.md`
2. 执行数仓 DDL：`warehouse/ddl/00-ods.sql` ~ `04-ads.sql`（含建库）
3. 配置 Flume：按 `ingestion/flume/flume-taildir.conf` 修改路径后启动（Taildir 断点采集 events → HDFS /landing）
4. 构建作业包：`cd spark-jobs && mvn package`（jar 不含 Spark 依赖）
5. 在后端环境配置中登记 RuntimeProfile（SINGLE_NODE/REMOTE_CLUSTER 类型、SSH 主机、提交用户、
   spark-submit 路径等）——后端通过 `SshSparkSubmitter`（阶段 6 预留接口）提交，作业入口契约见
   `spark-jobs/README.md`（--runtimeProfileId/--businessDate 等）
6. 指标服务仍为 MySQL（首版）；Doris 属第二阶段
7. 验证：黄金数据集全链路回归 + Flume 断点恢复（§5.2.9 验收）

## 常见问题

| 现象 | 原因/处理 |
|---|---|
| 端口 8090 占用 | 上一实例未退出；按 §3.5.5 先停旧进程 |
| MALL_DB_PASSWORD 未设置 | 后端启动即失败（Spring 报数据源错误）；演示脚本先 set |
| AI 页显示"规则回退模式" | 未配置 LLM key——符合设计（AI 不阻塞主链路） |
| landing 磁盘增长 | 演示数据按"保留 30 天"策略清理；生产接入 Flume 后 Landing 归 HDFS |