# 阶段 4/5：运维中心采集状态与批次列表接线（2026-10-09）

## 实现

- 运维中心新增调用既有 `GET /api/v1/ingestion/status` 和 `GET /api/v1/ingestion/batches?limit=100`，请求随页面加载使用 AbortSignal。
- 显示服务端提供的 profile/source、输入目录文件总数与字节数（`pendingFiles`/`pendingBytes`）、已有 checkpoint 文件数、仍有可消费完整行的文件数（`newFileCount`）、最近接收时间、Landing 错误，以及最近批次身份、状态、记录/错误/隔离数和起止时间。字段缺失时明确显示“接口未提供/未知”，不补造数据。
- 批次接口仍是已有 `limit` 列表，不是全量服务端分页；页面请求最近最多 100 条后，在该有界集合内提供 10/20/50 条分页和 ID/开始时间/状态排序，界面明确标出范围限制。
- Web 权限对账将受 `ops:log:view` 保护的批次列表 GET 从未接线登记中移除。列表是后端 limit 列表接口；页面请求最多 100 条，不声称全量服务端分页或持续上游监控。

## 验证

- 定向 Node 测试 `ingestionBatches.test.js`、`opsPipelinePagination.test.js` 与 `permissionReconcile.test.js`：22/22 PASS；分页/排序纯函数覆盖默认排序、不改源数组、空值位置、稳定并列、无效排序回退、分页页元数据、非法页/页大小收敛以及小数/非有限页码归一。
- `npm run verify`：Web 测试 418/418 PASS，0 fail/cancelled/skipped；Vite 生产构建 684 modules 成功，命令 exit 0。
- 全量测试期间出现 WebSocket server port already in use 提示；测试与构建仍成功，未终止进程。

## 边界

- 未真实登录调用受保护接口，未验证数据库非空批次/权限拒绝/浏览器渲染，也未启动采集或检查真实上游。
- 这轮只证明现有只读接口的页面接线、源码契约测试与构建，不证明采集链路、实时性或阶段4/5验收；阶段4/5/7仍开放。
- 未连接数据库、未启动/停止服务、未接触 MySQL 3306。动态文档编辑前备份后缀：`bak-20261009-ingestion-batch-utils`。
