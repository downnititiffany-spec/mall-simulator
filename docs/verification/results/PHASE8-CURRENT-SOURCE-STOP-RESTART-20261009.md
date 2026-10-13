# 阶段 8 当前工作树 task-owned 停启恢复复验（2026-10-09）

## 范围

验证当前工作树统一单节点脚本和 Windows Vite 身份守卫在已有隔离 RunId 上的正常停止、保留数据、preflight 与新 RunId 启动。没有尝试业务写入或最终产品验收。

## 执行与证据

- 开始时 `scripts/cloud-single-node.sh status` 识别 RunId `cloud_261009_050925_daf5af`，12 个登记端口全部 LISTEN。仅读取其已登记目录和启动配置，没有读取凭据。
- 通过统一脚本 `stop` 正常停止任务拥有的进程，exit 0；脚本报告 Windows Vite PID 281200（5174）和 283580（5173）身份匹配并停止，数据库/HDFS 文件保留。没有手动按端口杀进程。
- 第一次 stop 后 preflight 因传入的 Hadoop 配置目录多了错误的 `conf` 路径而 fail-closed，exit 4；没有创建资源。只读发现原 RunId 实际配置目录为 `.../cloud_261009_050925_daf5af/platform-spark-conf`，同时核实 NameNode/DataNode VERSION 文件和配置中 19000 RPC、已初始化 name/data 目录对应。
- 使用核实的配置路径重新运行 preflight，exit 0：依赖、任务路径、HDFS 身份/配置、目标端口通过；输出确认未创建目录、凭据、数据库、HDFS 路径或进程。
- 统一脚本 `start` exit 0，登记新 RunId `cloud_261009_051303_550c06`。`status` 显示 12 个目标端口全部 LISTEN：3307、19000、19010、19870、19864、19866、19083、8091、8090、8092、5173、5174。
- 当前工作树实服 GET：平台 `/api/v1/metrics/health` 200（148 bytes）；生成器 `/api/v1/scenarios` 200（1117 bytes）；Web 与商城 `/login` 分别 200（849/389 bytes）。匿名商城 `/api/v1/mall/products` 401；源码 `AuthInterceptor` 对除商城登录入口外的请求均要求有效 Bearer，故该响应符合现有策略。未尝试默认密码或绕开认证。
- `GRADUATION_TEST_ADMIN_PASSWORD` 在当前 PowerShell 任务进程、用户/系统环境检查结果及 WSL 子进程均不可用；没有认证请求、真实分页或 RuntimeProfile API 操作。

## 结论与限制

当前工作树 task-owned 单节点正常 stop → preflight → 新 RunId start 生命周期复验通过，持久数据路径保留，服务已恢复运行。第一次 preflight 的路径错误是本轮调用参数失误，已记录并在不绕过脚本守卫下更正。

此证据不证明旧 MySQL 行级回读、崩溃/中断恢复、Spark 发布恢复、认证分页/排序、RuntimeProfile 切换、浏览器业务流程、性能、完整 Stage 7 数据链或 V3.0 最终验收。未访问 3306，未格式化/删除 HDFS 数据，无提交或推送。后续认证验证需安全变量实际出现在启动此任务的进程环境后再做。
