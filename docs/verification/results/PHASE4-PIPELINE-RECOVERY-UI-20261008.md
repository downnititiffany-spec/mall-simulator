# 阶段 4/8 流水线恢复管理页面接线（2026-10-08）

## 实现

- Pipeline 页面接入启动恢复报告读取，以及 `resume`、`mark-failed`、`retry-from-stage` 三种已有受保护操作。
- 操作前加载最新 run 详情；提交前再次读取并比较 status/currentStage/updatedAt，变化时拒绝使用陈旧表单。续跑/阶段重试只允许当前 FAILED；标记失败只允许 PENDING/RUNNING。
- 所有人工恢复都要求非空原因和显式勾选确认。阶段重试明确提示会删除所选阶段及之后的阶段记录；标记失败明确提示它不会停止进程或从队列移除任务，要求操作者核实队列、提交进程和外部 Spark 作业均无待运行任务。
- 前端仅为 admin/data_dev/operator 显示写操作；服务端既有 `pipeline:run` / `ops:log:view` 权限仍是最终边界。API 不发送自报 operator，后端从认证会话取操作者。
- Web 权限对账中上述 4 个端点由“未接线”改为实际调用。

## 验证

- 定向 Node 测试：`pipelineRecovery.test.js`、`permissionReconcile.test.js`、`pipelinePage.test.js`、`pipelineRetryHandling.test.js`，**22/22 PASS**。
- `npm run verify`：**410/410 Web tests PASS**，Vite production build 成功，**683 modules**。测试日志有 `WebSocket server error: Port is already in use` 提示，测试与构建仍以 exit 0 完成；本轮未停止任何进程。

## 边界

- 未使用真实登录会话调用恢复 API，未在真实数据库写入或执行恢复操作，也未做浏览器实服验证。
- 状态对比有助于拒绝陈旧 UI 表单，不构成数据库事务锁或执行队列/外部 Spark 存活证明；标记失败仍需操作者先按页面说明核验现场。
- 没有启动/停止服务、连接数据库或访问 3306；阶段4/8和总体验收仍未关闭。
