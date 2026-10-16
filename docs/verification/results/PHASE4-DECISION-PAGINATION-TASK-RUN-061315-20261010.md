# 阶段4 当前 task-owned RunId 决策真实非空分页（2026-10-10）

- RunId：`cloud_261010_061315_23e7b3`，平台 `127.0.0.1:8091`。通过正式业务 API 创建两条唯一标记的分页合成用途 DRAFT；没有提交、审批、拒绝、启动、完成、取消或效果评价。
- 管理员分页验收器：`scripts/verify-decision-pagination.ps1 -BaseUrl http://127.0.0.1:8091 -PageSize 1`，结果 PASS；`total=2`、`totalPages=2`，ID升/降序、`createdAt`降序与跨页边界均通过，无重复。
- Playwright Chromium 真实管理员浏览器结果 PASS：登录和决策页打开成功；可见2条合成 DRAFT，分页摘要正确；API total/items 均为2；注销/token清理成功，页面错误0。只读检查 `writes_performed=false`。
- 范围限制：证明当前隔离 RunId 的认证列表 API 与页面呈现；不证明审批/执行/效果评价、决策业务完整生命周期或阶段4整体验收。未直连数据库/SQL，未连接3306。
