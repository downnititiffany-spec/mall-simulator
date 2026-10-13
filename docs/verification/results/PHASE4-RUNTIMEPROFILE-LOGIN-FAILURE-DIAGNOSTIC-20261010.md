# 阶段4 RuntimeProfile 认证入口失败诊断（2026-10-10）

## 现场结果

当前 task-owned Web 与平台服务仍运行。真实 loopback 浏览器只读验收器已发起一次登录请求，但未到达认证 overview；结果为 `login_succeeded=false`、1 次 API 请求、没有 RuntimeProfile/source 列表请求，页面错误数为 0。原验收器只报告泛化 `RuntimeError`，未输出登录 HTTP 状态或业务错误码，因此本次不能区分密码不匹配、账号禁用或其它认证失败原因。没有执行 profile 写入、切换、连通性测试或直接数据库访问。

## 诊断改进

`scripts/verify-runtime-profile-authenticated-readonly.py` 现在只收集登录响应 HTTP 状态和白名单业务码（`OK`、`BAD_CREDENTIALS`、`USER_DISABLED`）；任何未知码折叠为 `OTHER`。响应正文、token、口令和异常文本不保存、不输出。新增三项单元测试覆盖错误码白名单、未知码缩减和非登录端点忽略。`python -B scripts/test_verify_runtime_profile_authenticated_readonly.py`：8/8 PASS。

## 边界与后续

本地种子迁移文档包含演示管理员的初始化口令说明，并要求生产发布前修改或移除种子账号。当前隔离库是否仍使用种子口令未验证；本报告不记录任何口令。应由用户在安全提示中提供有效口令后重跑只读 verifier；若稳定错误码为 `BAD_CREDENTIALS`，需先确认是否授权对该隔离 task-owned 数据库执行管理员凭据重置。真实认证、RuntimeProfile/source API、数据库及 profile 切换均未通过，本阶段仍开放。MySQL 3306 未访问。

动态文档编辑前备份后缀：`bak-20261010-login-response-diagnostics`。
