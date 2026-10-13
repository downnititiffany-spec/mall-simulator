# 当前源码 task-owned 隔离栈正常停启复验（2026-10-10）

## 变更前现场与身份核验

统一脚本登记旧 RunId `cloud_261010_004300_b0b2ab`。逐 PID 确认 MySQL `--defaults-file`、Metastore 的 Hive/Hadoop 配置、平台 metric-staging 目录、商城隔离 schema、生成器 jar 和 Flume RunId 配置；两个 Windows Vite PID 使用项目 `stop-task-vite-process.ps1 -Action verify` 核验 executable path、RunId marker、Vite 脚本及端口，均通过。旧栈 12 个目标端口在 stop 后全部 CLOSED，统一脚本报告数据库/HDFS 文件保留；对应 MySQL、Derby、配置、staging 和 RunId 标记目录仍存在。

## preflight 与恢复

旧栈停止后运行 `scripts/cloud-single-node.sh preflight`，工具、任务路径、已初始化 HDFS clusterID/config 与空闲端口均通过，脚本明确报告未创建资源。随后统一 `start` exit 0，注册新 RunId `cloud_261010_005249_1c73ec`，任务目录 `/home/asus/graduationproject-runs/cloud-e2e/cloud_261010_005249_1c73ec`。统一脚本 `status` 的 12/12 目标端口 LISTEN：3307、19000、19010、19870、19864、19866、19083、8091、8090、8092、5173、5174。

新栈公开探针：平台 `/api/v1/metrics/health`、商城 `/`、生成器 `/api/v1/scenarios`、分析登录页、商城登录页均 HTTP 200；匿名决策页 `/api/v1/decisions/page?page=0&size=10` 与采集页 `/api/v1/ingestion/batches/page?page=0&size=10&sort=id,desc` 均 HTTP 401。

## 数据保留与边界

停启前后 HDFS NameNode/DataNode `clusterID` 均为 `GP-SMOKE-20260924`；历史 Flume 样例 `events-.1790258690108` 仍可读且为 900 字节；新 RunId `/landing/cloud_261010_005249_1c73ec` 存在。没有格式化或清理 HDFS、删除 Derby/MySQL 数据目录，也未人工连接数据库；3306 未探测或访问。未进行管理员认证、旧库行级回读、业务中断/崩溃恢复、Spark 发布恢复或性能验证。该证据只覆盖 task-owned 单节点的正常 stop/preflight/start 和公开入口恢复；阶段 8、阶段 4 和总体验收均保持开放。隔离栈继续运行，未提交/推送。