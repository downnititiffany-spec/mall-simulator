# 阶段 4/8 隔离单节点运行栈复核（2026-10-07）

## 核查范围

只读复核 Windows WSL 中已存在的隔离运行栈登记、相关监听端口、可识别的监听进程可执行文件/工作目录，以及当前管理员密码环境变量是否存在。未启动或停止服务、未访问业务数据端点、未连接数据库。

## 结果

- 使用脚本要求的任务根 `/home/asus/graduationproject-runs` 执行 `CLOUD_SINGLE_NODE_ROOT=... bash scripts/cloud-single-node.sh status`，确认登记 RunId 为 `cloud_261007_021316_0219d8`。此前将 E2E 子目录误作任务根时的 “No task run registered” 不是当前正确状态。
- 脚本状态检查显示 3307、19000、19010、19870、19864、19866、19083、8090、8091、8092、5173、5174 可连接/监听。Linux `ss` 可见 3307、19000、19010、19083、8090–8092；未见 HiveServer2 10015。5173/5174 的脚本判定还包含 WSL localhost TCP connect，因为 Windows Node listener 不一定出现在 Linux `ss` 表中。
- MySQL PID 584 的可执行文件为 `/opt/mysql-8.0.41/bin/mysqld`，工作目录位于该 RunId 的 `mysql-data`。应用 PID 1540/1739/1740/1741 为 `/opt/jdk-17.0.12/bin/java`，工作目录均为当前项目工作树。仅记录进程身份；未向其发送信号。
- `GRADUATION_TEST_ADMIN_PASSWORD` 在当前 Codex 任务进程不可见；没有尝试登录受保护分页端点。
- 未连接 3306、未访问数据库、未读取或输出凭据值。阶段 4/8 验收仍未关闭。

## 动态文档备份

编辑前备份：`docs/PROJECT_STATUS.md.bak-20261007-phase4-runroot-recheck`、`docs/PROJECT_COMPLETION_CHECKLIST.md.bak-20261007-phase4-runroot-recheck`。
