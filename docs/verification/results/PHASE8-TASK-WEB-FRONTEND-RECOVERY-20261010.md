# 阶段8 task-owned 平台前端恢复（2026-10-10）

## 发现与处置

隔离任务 RunId `cloud_261010_005249_1c73ec` 的平台前端端口 5173 已关闭，商城前端 5174 和后端/数据服务仍运行。该 RunId 的平台 Vite PID 登记文件存在，但 Windows PID 已不存在；身份 helper 的只读 `verify` 正确拒绝将其认作有效进程，Linux 包装 PID 也不在运行。

使用当前工作树 `scripts/cloud-single-node.sh recover-web` 分支恢复平台前端。该分支只清理失效的任务专属 PID 登记并启动平台 Vite，不触碰数据库、HDFS 或其它服务。随后统一 `status` 确认 12/12 端口 LISTEN；平台真实配置健康端点 `/api/v1/metrics/health`、分析登录页 `/login`、商城登录页 `/login` 均 HTTP 200。

## 证据边界

这是当前隔离 RunId 下的 Web 前端进程恢复及公开 GET 健康证据；没有执行管理员认证、受保护 API、数据库、RuntimeProfile 写入/切换或业务链验证。MySQL 3306 未访问。恢复脚本自身是工作树在途改动，本报告不表示整栈故障恢复或阶段8/最终验收通过。

## 2026-10-10 PID 复用后的恢复补充

首次 `recover-web` 因任务记录的 Linux 包装 PID 9991 仍存在而安全拒绝。只读检查显示其 `comm=node.exe`、`exe=/init`、工作目录为本工作树 `web`，但该 PID 命令行不满足 Vite/端口身份条件；5173 当时无监听。恢复路径已调整为：Windows Vite 身份 helper 确认任务 Windows 进程已停止后，遇到不匹配的 Linux PID 时只删除该 RunId 的 `vite-web.pid` 登记，不向该 PID 发送信号，并继续执行 5173 占用检查。

使用隔离根目录 `/home/asus/graduationproject-runs` 与该 RunId 已登记的 Windows Node 路径重试，`recover-web` 成功。`status` 显示 12/12 端口 LISTEN；5173 登录页和平台 metrics health 均 HTTP 200。`bash -n scripts/cloud-single-node.sh` 通过；`scripts/test-stop-task-vite-process.ps1` 通过（五种不匹配身份拒绝、无模拟停止调用）。本次未终止 PID 9991，未触碰数据库/HDFS；3306 未访问。

该补充只证明失效/复用 PID 登记的安全恢复及公开 GET，不代表认证或阶段8闭合。编辑动态文档前备份后缀：`bak-20261010-vite-pid-reuse-recovery`。
