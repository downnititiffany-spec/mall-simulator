# 阶段 4 流水线恢复操作者身份绑定（2026-10-08）

## 改动

- `PipelineAdminController` 的 resume、mark-failed、retry-from-stage 和 recalculate 操作现在从 `CallerContext.requireUserId()` 取操作者；缺少已认证会话时 fail-closed。
- resume/mark-failed/retry-from-stage 不再要求调用者在 query parameter 中自报 operator。多余的旧 query parameter 不参与服务调用。
- `RecalculateReq.operator` 为旧请求体兼容暂时保留，但不再要求或信任该字段；重算审计使用当前会话用户名。
- 未改变权限矩阵、流水线状态迁移、恢复策略、reason 审计文本或数据库结构。

## 验证

- Windows JDK 17.0.12 / Maven 3.9.14，离线 reactor 命令：
  `mvn -o -f analytics-server/pom.xml -pl platform-app -am '-Dtest=PipelineAdminControllerIdentityTest,ControllerPermissionCoverageTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- `PipelineAdminControllerIdentityTest` 2/2 PASS：四类写操作均将登录会话用户传入服务；缺少会话时四类操作均拒绝且不调用服务。
- `ControllerPermissionCoverageTest` 5/5 PASS；七模块 reactor BUILD SUCCESS，0 failure/error/skipped。
- 第一条未加引号的 PowerShell `-D...` 命令在参数解析阶段失败，未启动 Maven；使用引号包围属性后上述验证完整运行。

## 证据边界

- 这是离线单元/权限覆盖测试，不是 HTTP 认证过滤器或真实数据库审计回读证据。
- 未启动/停止隔离服务，未连接数据库，未触及 3306；没有使用管理员密码。
- 阶段 4 与总体验收仍开放。真实受保护 API 及恢复操作验收待后续安全认证条件具备后进行。
