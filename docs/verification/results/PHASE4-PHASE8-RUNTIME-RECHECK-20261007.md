# PHASE4-PHASE8-RUNTIME-RECHECK-20261007

## 范围与当前代码回归

- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `codex/cloud-v3-development`，HEAD `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f`；fetch 后与 `origin/codex/cloud-v3-development` ahead/behind 均为 0。
- 接手时工作树有 603 个状态条目；本次未重置、清理、批量暂存或覆盖这些在途内容。
- 按总控已选择的 G-03 方案 A（D-059）复跑 profile fail-closed、显式历史快照分析和决策分页排序的定向回归：JDK 17 七模块 Maven reactor BUILD SUCCESS，实际执行 **60 项，0 failure、0 error、0 skipped**。涵盖 ActiveRuntimeProfileIdProviderImplTest 4、RuntimeProfileServiceActiveProfileTest 4、RuntimeProfileSingleActiveMigrationScriptTest 2、AnalysisServiceTest 42、MySqlMetricStoreActiveProfileTest 4、DecisionServicePaginationTest 4。
- `git diff --check` 通过；仅有既有文件 LF/CRLF 转换警告。

## 当前隔离运行栈

- 使用任务根 `/home/asus/graduationproject-runs` 执行 `CLOUD_SINGLE_NODE_ROOT=... bash scripts/cloud-single-node.sh status`。当前标记仍为 RunId `cloud_261007_021316_0219d8`，但本次检查的 3307、19000、19010、19870、19864、19866、19083、8091、8090、8092、5173、5174 均为 `closed`。
- RunId 目录及 PID 登记文件仍存在。登记的 Linux PID 均不在 WSL `/proc`；Windows 登记 PID 270864 当前对应 Codex `node_repl.exe`，不是该 RunId 可验证的 Vite 进程，判为 PID 复用/身份不符并保留。没有按 PID 发送信号，也不把 PID 文件存在等同进程存活。未启动/停止服务、连接数据库、访问 3306 或触碰持久数据。
- 安全管理员变量 `GRADUATION_TEST_ADMIN_PASSWORD` 在当前 PowerShell Codex 任务环境不存在；没有读取或回显任何密码值。受保护分页和真实 profile 切换不能执行。
- 统一脚本的默认工具链根 `/workspace/toolchains` 不存在。已检查的 `/opt` JDK/MySQL/Hadoop/Hive 与历史 Hadoop 配置不等于脚本所要求的完整工具链与 HDFS 命名空间；此前记录的 preflight 对现存配置因 HDFS RPC/目录身份不匹配而 fail closed。本轮未改写旧 HDFS 配置，也未尝试新 RunId 启动。
- Google Drive 知识库搜索本轮连接失败，未读取或更新 Drive 索引/项目记录。

## 结论

离线 G-03 方案 A 与决策分页排序回归通过；这不是部署或服务端验收。阶段 4/8 与整体验收保持开放。恢复认证分页和 profile 切换前，需将管理员密码安全注入当前任务进程，并准备与统一脚本身份匹配的工具链/HDFS namespace；不得复用或改写未知持久数据。
