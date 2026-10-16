# PHASE4-LIVE-RUN-RECONCILE-20261007

## 核验范围

本次复核当前隔离单节点运行栈与阶段 4 的认证访问边界。没有停止服务、连接数据库、读取凭据值或运行测试，也未访问 3306；后续仅在确认 PID/Node 路径/RunId 标记/端口后，通过新增的 `recover-web` 动作恢复了任务自身已退出的 Web Vite 进程。

工作树为 `D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `codex/cloud-v3-development`，HEAD `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f`，fetch 后与 `origin/codex/cloud-v3-development` ahead/behind 均为 0。工作树有 437 个 status 条目，全部保留。

## 运行栈实况

统一脚本实际登记根目录为 `/home/asus/graduationproject-runs`，当前 RunId 为 `cloud_261007_021316_0219d8`。修复前 `scripts/cloud-single-node.sh status` 显示 3307、HDFS RPC/DataNode/HTTP/IPC、Hive Metastore 19083、平台 8091、商城 8090、生成器 8092 均为 LISTEN；5173 closed，5174 LISTEN。RunId 的 process ID 文件与仍存在的 `/proc` 命令对应，包括独立 MySQL、Metastore、平台、商城、生成器、Flume、Vite Mall；旧 Vite Web WSL proxy PID 1742 已不存在。

Vite Web 日志最初记录 Vite 5.4.21 已启动，但进程实际因 Node 24 将 `D:\...` 绝对路径当作 ESM URL 并拒绝 `d:` scheme 而退出。新增 `recover-web` 后，在确认当前 RunId、5173 空闲、Node 可执行路径后重新启动 Web Vite；Windows PID 270864 的可执行文件、RunId 标记、Vite 脚本路径和端口参数经 helper 校验成功，`GET /login` 为 HTTP 200。现 5173 与 5174 均 LISTEN。Mall Vite 属旧启动逻辑且缺新版 Windows PID 标记，本次未接管。

本次同时修正了 PowerShell helper 传参：`-File` 必须使用 `wslpath -w` 转换后的 Windows 路径；身份校验要求 helper 返回明确的 `Verified` 文本，避免 helper 启动失败被误判为成功。新启动及恢复路径共用 `pathToFileURL(path.resolve(vite)).href` 加载 ESM Vite。

实际配置与目录只读检查发现当前脚本默认的 `/workspace` 工具/运行根目录不存在；本机已有 JDK 17、Hadoop/Hive/Spark/MySQL/Flume 和 Windows Node。已有 HDFS smoke namespace 位于 `/home/asus/.cache/graduation-hdfs-smoke-20260924`，仓库留存的配置指向 RPC 19000 且数据目录匹配。由于当前运行栈已占用端口，本次没有运行 start/preflight，也没有将 smoke namespace 改作其它用途。

## HTTP 证据

- `GET http://127.0.0.1:8091/api/v1/health`：HTTP 200，平台返回 `code=OK`。
- `GET http://127.0.0.1:8091/api/v1/metrics/health`：HTTP 200，服务返回 `ok=true`、`mysql metric store ok (metric_read)`。这是经应用健康接口的只读检查，不是直接数据库客户端验收。
- 未带会话调用 `/api/v1/runtime-profiles/active`、`/api/v1/decisions/page?page=1&size=5&sort=id,desc`、`/api/v1/admin/users?page=1&size=5` 均返回 HTTP 401；因此本次没有取得受保护分页数据，也没有验证非空分页、排序或真实 RuntimeProfile 切换。
- `GET http://127.0.0.1:5174/` 与 `GET http://127.0.0.1:5173/login` 均返回 HTTP 200；这只验证前端静态服务可达，不是登录后的页面验收。
- 当前任务 Windows 环境和 Ubuntu WSL 均未发现 `GRADUATION_TEST_ADMIN_PASSWORD`（只检查变量是否存在，没有读取/回显其值）。
- WSL 未发现当前 HiveServer2 10015 监听；Metastore 19083 正在监听。此前 2026-10-06 的真实 HiveServer2 `SELECT 1` 证据仍是独立历史批次，本次未复跑。

## 结论与后续

本次确认隔离平台和只读指标存储健康，并通过任务专属入口恢复了 Web 前端；缺管理员认证导致真实受保护分页/G-03 切档读取仍不能验证。当前栈其它端口仍被占用，不能再启动一套统一运行栈；HiveServer2 10015 也未监听，需依其 task-owned 启停脚本单独验收。后续需恢复可信管理员变量注入，并处理旧 RunId 中未带身份标记的 Mall Vite 才能安全关闭整个运行栈。阶段 4、阶段 8 和总体验收均不关闭。

静态验证：`bash -n scripts/cloud-single-node.sh`、PowerShell parser 对 helper 的语法解析、`git diff --check` 均通过；本轮未运行自动化测试。
