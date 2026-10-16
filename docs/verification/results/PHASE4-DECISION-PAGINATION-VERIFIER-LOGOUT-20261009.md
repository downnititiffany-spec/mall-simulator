# 阶段 4 决策分页验收器注销响应确认

日期：2026-10-09  
范围：验证分页检查结束时必须收到成功注销响应，否则不得输出通过结果。

## 改动与验证

- 验收器检查 logout 统一响应信封 code=OK；HTTP 200 但业务码失败时，警告只说明会话未能关闭，并在输出 PASS 前以通用错误终止。
- 清除脚本中保留认证 token 的请求头、响应对象和闭包变量引用。
- loopback 正常路径：25 条合成记录双向排序、3 页跨页边界检查与成功 logout 后输出 PASS；请求序列 login/page-desc-1/page-asc-1/page-desc-2/logout。
- loopback 负例：分页成功后 logout 返回 HTTP 200 + MOCK_LOGOUT_ERROR，验收器不输出 PASS，通用错误未泄露业务响应内容；请求序列含 logout 且 mock 正常结束。
- PowerShell AST 和 Node 语法检查通过。

## 证据边界

这只验证 loopback 验收工具的注销响应处理，不是当前隔离服务上的真实管理员认证/数据库分页证据。阶段4仍开放，未访问3306。
