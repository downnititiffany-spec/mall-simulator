# 阶段14 task-owned RunId 启动与迁移（2026-10-12）

## 启动范围

通过 `scripts/cloud-single-node.sh`，使用独立 RunId `cloud_261012_012653_d3b0fc` 建立 task-owned 单节点栈。启动前 status 为目标端口全关闭；脚本 preflight 核验依赖、12个目标端口、已有 HDFS NameNode/DataNode clusterID 一致及配置路径正确，通过后才启动。复用既有 HDFS namespace `GP-SMOKE-20260924`，没有格式化或覆盖 HDFS。

## 现场结果

- 统一脚本 status：12/12 目标端口 LISTEN，包括独立 MySQL 3307、HDFS、共享既定 HMS 19083、平台8091、商城8090、生成器8092、前端5173/5174。
- 公开探活：平台 `/api/v1/metrics/health`、商城 `/`、生成器 `/api/v1/scenarios`、两个前端 `/login` 均 HTTP 200。
- 平台日志：meta 数据库验证37个 Flyway 迁移并执行至 V38；metric 数据库验证13个并执行至 V13。两库都属于本 RunId 的 MySQL 3307。
- 用户环境变量及口令未输出。3306 未访问。保留本 RunId 服务运行，以便后续认证验收。

## 限制

启动与公开 GET 只证明任务栈恢复和公开端点可用；未登录管理员、未读取真实报告API/浏览器页面、未确认管理员凭据，未产生分析快照，也不代表迁移业务完整性或阶段14验收。真实认证读路径仍待完成。旧 RunId 数据目录没有清理。