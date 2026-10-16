# 阶段 4 当前 RunId 决策真实分页与浏览器复核（2026-10-10）

## 合成数据

- RunId：`cloud_261010_040051_b2ab54`，平台 `http://127.0.0.1:8091`，平台 Web `http://127.0.0.1:5173`。
- 管理员 API 创建前，`GET /api/v1/decisions/page` 只读回读 `total=0`。使用正式 `POST /api/v1/decisions` 创建两条明确标记 `PAGINATION-SYNTH-CURRENT-20261010-A/B` 的合成 DRAFT（ID 1、2；decisionNo `DC-20261010124850-db9e` 与 `DC-20261010124850-aed6`）。内容注明仅作分页验收，不得提交审批或执行。
- 两条记录均保持 `DRAFT`；未调用 submit/approve/reject/start/complete/cancel/evaluate，也未直接连接数据库或运行 SQL。写入通过正式隔离业务 API 完成。

## 分页 API 验收

运行仓库验收器：`./scripts/verify-decision-pagination.ps1 -BaseUrl http://127.0.0.1:8091 -PageSize 1`。

结果 `PASS`：total=2、totalPages=2；ID 升序/降序首屏契约有效，降序 ID 第1/2页边界严格递减且无重复；`createdAt DESC` 排序和跨页边界也通过；`PageSize=1`；验收器注销成功。

## 浏览器复核

Playwright Chromium 通过真实管理员账号登录 `http://127.0.0.1:5173`，等待页面 `networkidle` 后打开决策中心。页面渲染两行合成记录且两行都是 DRAFT，分页摘要为“共 2 条 · 第 1 / 1 页”；对应决策分页 API HTTP 2xx、`code=OK`、total=2；logout HTTP 成功且 localStorage token 已清除；pageerror=0。没有点击任何草稿操作，也没有保存截图/trace/响应体/凭据。浏览器结果 `PASS`。

未连接 MySQL 3306。证据覆盖当前 RunId 的真实管理员决策创建/分页 API 与只读浏览器列表；不证明决策审批/执行/效果闭环、数据库并发一致性或阶段4/项目整体验收。动态文档编辑前备份后缀 `bak-20261010-decision-pagination-current-run`。