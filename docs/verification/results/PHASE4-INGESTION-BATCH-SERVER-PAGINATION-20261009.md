# 阶段 4 采集批次服务端历史分页（2026-10-09）

## 实现

- 新增受 `OPS_LOG_VIEW` 保护的 `GET /api/v1/ingestion/batches/page`，支持 `page/size/sort`、白名单字段排序、稳定 ID 次排序、总数和总页数；对过大页码返回最后一页，空结果保留第 1 页。
- 保留既有 `/ingestion/batches?limit=` 最近批次数组端点以兼容旧客户端。Ops 采集批次表改为请求服务端分页，显示全量历史总数并将翻页、页大小和排序交由服务端处理。

## 验证

- Windows JDK 17 Maven 离线 reactor：`IngestionControllerPaginationTest` 与 `ControllerPermissionCoverageTest` 共 8/8 PASS，0 failure/error/skipped，`BUILD SUCCESS`。
- Web `npm run verify`：Node 测试 421/421 PASS，0 fail/cancelled/skipped；Vite production build 成功，684 modules。测试输出伴有 `WebSocket server error: Port is already in use`，但 npm 命令退出码为 0。
- `git diff --check` 对本轮指定源码/测试文件通过。
- 隔离 RunId `cloud_261009_051303_550c06` 运行中；该运行栈由旧构建启动，本轮未重建/部署新后端。因此没有新端点的真实 HTTP、认证非空历史分页、数据库或浏览器证据。阶段 4 和总体验收不关闭。

## 边界

未连接 3306；未触碰数据库持久目录；未提交或推送。动态状态编辑前备份后缀 `bak-20261009-ingestion-batch-server-page`。
