# 阶段 4 采集分页当前源码隔离部署与认证边界复验（2026-10-09）

## 操作与结果

- 当前工作树 analytics-server、mall-simulator、synthetic-data-generator Maven `package -DskipTests` 均 BUILD SUCCESS；测试证据见各模块全量离线回归报告。
- 统一单节点脚本正常停止旧 RunId `cloud_261009_051303_550c06`，保留旧 MySQL/HDFS/Derby运行目录数据；安全 preflight 通过后启动新 RunId `cloud_261009_053348_d6936a`，新任务使用独立 MySQL schema、运行目录、HDFS Landing 子路径及 Derby。未访问 3306。
- 脚本启动成功；12 个目标端口 LISTEN（3307、5 个 HDFS 端口、19083、8091/8090/8092、5173/5174）。平台 health HTTP 200，Web `/login` HTTP 200。
- 对 `GET /api/v1/ingestion/batches/page?page=1&size=10&sort=id,desc` 的匿名请求返回 HTTP 401；这只证实未认证访问被拒绝，不证明 controller 已处理新路由、响应分页数据或服务端排序。

## 未完成验证

当前任务和 WSL 均未见 `GRADUATION_TEST_ADMIN_PASSWORD` 环境变量。没有尝试默认口令或绕过认证。管理员登录、非空分页、排序/翻页、API响应体、浏览器 UI 仍待安全凭据在当前任务环境可见后验证。阶段4与总体验收不关闭。

## 安全边界

没有访问 3306；没有读取/输出任何凭据；没有清理/格式化数据库、HDFS 或旧 RunId 数据；没有提交/推送。动态文档编辑前备份后缀 `bak-20261009-ingestion-page-live-deploy`。
