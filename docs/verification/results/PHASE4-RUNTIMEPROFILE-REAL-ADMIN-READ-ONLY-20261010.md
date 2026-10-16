# 阶段4 RuntimeProfile 真实管理员只读浏览器复核（2026-10-10）

- 环境：task-owned RunId `cloud_261010_005249_1c73ec`，loopback 平台 UI `http://127.0.0.1:5173`。
- 按用户授权，验证期间隔离 MySQL 3307 当前 RunId meta 库的 `admin` 哈希重置为仓库既有隔离演示种子哈希；验证后恢复既有哈希状态。口令、token、数据库凭据均未写入或输出；真实浏览器退出成功。
- `scripts/verify-runtime-profile-authenticated-readonly.py` 真实 Chromium 登录成功（HTTP 200/OK），打开 `/runtime-profiles`；5 次 API 请求包含认证、RuntimeProfile 和来源只读列表。两个列表响应均为 2xx、`code=OK`、数组；显示 1 行 profile，其中 ACTIVE 1；logout 清除本地 token；页面错误0。`writes_performed=false`。
- 该结果仅证明受认证的列表/API与页面流程。未执行 profile connectivity test、编辑、激活或切换，未直接查询数据库。
- 重要边界：公开 `POST /api/v1/runtime-profiles/{id}/test` 调用 `RuntimeProfileServiceImpl.test()` 后会 `profileMapper.updateById(p)`；全部探测通过时还会将状态设为 `TESTING`。因此它不是无副作用的只读探测，本轮未调用。真实 Thrift HiveServer2 运行时健康结果仍未取得；既有服务层隔离 JDBC IT 不替代该证据。
- 未连接 MySQL 3306；阶段4整体与最终验收开放。
