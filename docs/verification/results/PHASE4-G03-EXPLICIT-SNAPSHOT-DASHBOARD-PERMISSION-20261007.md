# 阶段 4 G-03 显式历史快照 dashboard:view 权限门

日期：2026-10-07（Asia/Shanghai）

## 范围

依 D-059 方案 A，跨 RuntimeProfile 历史快照只能通过精确 `snapshotId` 读取，并受 `dashboard:view` 权限控制。本轮补齐 AI 问数、分析和解释三个显式快照入口的服务层权限门；无显式 ID 的当前 profile 默认读取继续由原权限路径及 G-03 profile 作用域处理。

`CallerContext.requirePermission` 从当前认证会话解析角色权限；AI Controller 在查询、分析或证据读取前检查非空 snapshotId。拒绝使用 `FORBIDDEN_PERMISSION`，统一异常映射为 HTTP 403。没有增加逐次读取审计机制，也不声称该权限门就是读取审计。

## 验证

JDK 17，离线七模块 Maven reactor：

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=AiControllerIdentityTest,GlobalExceptionHandlerSourceStatusTest,RolePermissionsTest,AuthInterceptorTest,ControllerPermissionCoverageTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：`AiControllerIdentityTest` 9/9、`ControllerPermissionCoverageTest` 5/5、`GlobalExceptionHandlerSourceStatusTest` 7/7、`RolePermissionsTest` 11/11、`AuthInterceptorTest` 13/13；总计 45/45 PASS，0 failures/errors/skips，`BUILD SUCCESS`。负向测试使用仅有 `ai:query` 的未知角色，三个入口均在任何查询/审计服务调用前拒绝；正常现有权限矩阵由角色与 Controller 覆盖测试回归。

## 限制

未部署本轮代码，未登录真实 Web，未连接数据库或验证真实 RuntimeProfile 切换/历史快照读取。此前运行栈的受保护请求因安全密码变量未注入任务进程而返回 401，因此本轮不提供实服权限证据；无数据库写入。阶段 4 和项目总体验收保持未关闭。

动态文档编辑前备份：`docs/PROJECT_STATUS.md.bak-20261007-g03-explicit-dashboard-permission`、`docs/PROJECT_COMPLETION_CHECKLIST.md.bak-20261007-g03-explicit-dashboard-permission`、`docs/verification/CURRENT_BATCH.md.bak-20261007-g03-explicit-dashboard-permission`。
