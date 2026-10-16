# V3.0 当前源码打包与隔离栈健康复核（2026-10-10）

## 范围

复核分支 `codex/cloud-v3-development`、HEAD `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f` 与 `origin/codex/cloud-v3-development` 一致。工作树有 1030 个既存状态条目，均保留；本次只新增本报告和动态状态记录，没有改业务源码或冻结文档。

## 当前构建证据

在 WSL 预检通过后，以当前工作树源码重打包：

- `mvn -o -f analytics-server/pom.xml package`：BUILD SUCCESS；六模块 1335 tests，0 failures，0 errors，2 skipped（connection-ingestion 的 Windows/POSIX 相关跳过项）。
- `mvn -o -f mall-simulator/pom.xml package`：BUILD SUCCESS；23 tests，0 failures/errors/skips。
- `mvn -o -f synthetic-data-generator/pom.xml package`：BUILD SUCCESS；134 tests，0 failures/errors/skips。
- 合计 1492 tests，0 failures，0 errors，2 skipped。未把旧 Surefire XML 的聚合数当成本轮测试数；控制台构建汇总为计数依据。

## 隔离栈健康证据

统一脚本 `scripts/cloud-single-node.sh` 的 preflight 通过，`start` exit 0，注册新 RunId `cloud_261010_004300_b0b2ab`，任务目录 `/home/asus/graduationproject-runs/cloud-e2e/cloud_261010_004300_b0b2ab`。脚本 `status` 对登记的 12 个目标端口均报告 LISTEN：3307、19000、19010、19870、19864、19866、19083、8091、8090、8092、5173、5174。

当前工作树部署后的公开探针：平台 `/api/v1/metrics/health`、商城根页、生成器 `/api/v1/scenarios`、分析登录页和商城登录页均 HTTP 200。匿名 `GET /api/v1/decisions/page?page=0&size=10` 与 `GET /api/v1/ingestion/page?page=0&size=10` 均 HTTP 401，符合管理端认证门。此前一次探测用了错误的 `/actuator/health` 和决策路由，得到 404；这些错误路径已纠正，不作为产品失败证据。统一脚本启动期间曾有工具会话断开；通过 live process/port 与最终脚本退出码重新确认后才登记本结论。

## 未闭合项与边界

- 当前任务进程未提供 `GRADUATION_TEST_ADMIN_PASSWORD`；本次未发送认证请求。因此决策非空页实际排序/跨页、采集管理页真实分页、RuntimeProfile 管理/切换均未验收。
- 本栈没有启动 HiveServer2 10015；RuntimeProfile 对 Thrift HiveServer2 的平台管理面实际健康探测仍待证据。已有方法级 Hive JDBC SELECT 1 证据不等于该验收。
- 阶段4/8和项目总体验收仍未关闭；没有执行全业务数据链或声明部署恢复/性能结论。
- 3306 未访问。隔离 RunId 数据目录保留；本次没有人工连接数据库或清理持久目录。不要对运行进程仅凭端口执行 kill。
- 代码仍处于未提交工作树；未提交、未推送。

## 复现入口

先为 WSL 设置经预检确认的工具链变量，再用统一脚本 `preflight`、`start`、`status`。当前服务健康与匿名权限响应应按上述精确 URL 复核。认证验证须由管理员密码在本机安全提示中注入，禁止将凭据放入聊天、命令行参数、文件或日志。
