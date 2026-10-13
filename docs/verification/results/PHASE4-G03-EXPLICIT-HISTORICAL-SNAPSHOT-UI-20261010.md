# 阶段4 G-03 显式历史快照读取入口（2026-10-10）

## 变更

依 D-059 方案 A，默认快照选择器和列表仍限定当前 ACTIVE RuntimeProfile；但原 Web 分析页没有跨 profile 归档快照的显式入口，`useAnalysis` 也会覆盖调用方的显式 `snapshotId`。现于共享快照工具栏增加精确 ID 输入：不枚举历史 ID、不写入 localStorage；明确指定后，分析页与 AI 基线/问答使用该 ID，清除指定值后回到当前档案选择。服务端仍负责 `dashboard:view` 权限和快照发布状态校验；响应快照 ID 不匹配或未知 ID 不回退当前 ACTIVE。

## 验证

- 定向 Node 回归：13/13 PASS，覆盖当前快照回显、归档 ID 优先级、输入边界、清除行为及错配/缺失拒绝写入。
- Web 全量 `npm test`：442/442 PASS，0 fail/cancelled/skipped。
- Vite 5.4.21 production build：690 modules 成功。
- headless Chromium 本地 mock：当前 ACTIVE → 显式跨 profile 归档 ID → 未知 ID 失败关闭 → 返回当前档案；6 次 API 请求全部拦截，意外请求 0、页面异常 0。验证脚本为 `scripts/verify-explicit-snapshot-browser.py`。
- mock 浏览器使用合成 analyst 身份；测试服务在验证后停止，端口 5191 已确认关闭。

## 证据边界

浏览器 API 全部为 mock，不证明真实 `dashboard:view` 权限、MySQL 查询、跨 profile 归档行读取、真实服务/浏览器或 RuntimeProfile 切换。阶段4与总验收保持开放；MySQL 3306 未访问。

动态文档编辑前备份后缀：`bak-20261010-g03-explicit-history-ui`。
