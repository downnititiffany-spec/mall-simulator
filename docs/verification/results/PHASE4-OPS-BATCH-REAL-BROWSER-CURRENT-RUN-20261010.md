# 阶段 4 当前 RunId Ops 采集批次真实管理员浏览器（2026-10-10）

- RunId：`cloud_261010_040051_b2ab54`；Playwright Chromium 登录 `http://127.0.0.1:5173` 后打开 `/ops`。
- 运维页显示 3 条批次，状态为 `SUCCESS`、`SUCCESS`、`QUARANTINED`；摘要 `共 3 条 · 第 1 / 1 页` 与实际分页 API `total=3` 一致。`/api/v1/ingestion/status` 和 `/api/v1/ingestion/batches/page` 均 HTTP 2xx、`code=OK`；pageerror=0，注销成功且 localStorage token 清除。
- 首次浏览器脚本因 `get_by_text(exact=True)` 未匹配 Vue 中摘要 `span` 产生一次检查器 FAIL；只读 DOM 确认实际文本与预期完全一致，改用批次区块 span 文本重新执行，最终 PASS。页面无故障；未进行写操作或直接数据库访问。

证据限于当前 RunId 的管理员只读 Ops 页面/API；不替代服务端分页排序/跨页边界验收器结果（见 `PHASE4-INGESTION-BATCH-LIVE-PAGINATION-CURRENT-RUN-20261010.md`），不代表阶段4或项目验收完成。未触碰3306。动态文档编辑前备份后缀：`bak-20261010-ops-batch-browser-current-run`。