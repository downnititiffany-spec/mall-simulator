# 阶段 5 数据源接入向导刷新安全修正（2026-10-08）

## 范围

修正来源配置列表刷新期间的预览报告与写操作状态。刷新可能读到管理员在其他会话中更新后的来源配置；旧 dry-run 报告不得在该状态尚未确认时继续用于映射/来源激活。

## 实现

- 每次来源列表刷新立即递增预览请求序号、停止当前预览状态并清空旧报告/激活结果；旧请求晚到不能回填报告。
- 来源列表加载期间，两类激活按钮均禁用；没有有效 sourceId 时也禁用。
- 增加源码守卫覆盖刷新作废和两个激活门控；未增加/改变后端 API、数据库结构或映射契约。

## 验证

在 `web/` 执行 `npm run verify`：

- Node test：402/402 PASS，0 fail、0 cancelled、0 skipped。
- Vite production build：成功，680 modules transformed。
- 构建输出中出现 `WebSocket server error: Port is already in use` 测试诊断，但测试最终 402/402 通过且 build 成功；需与后续完整浏览器证据区分。

该结果仅为 Web 离线测试和构建；未进行浏览器管理员认证、API 写入或数据库验证。运行栈仍按既有 RunId 运行；3306 未访问。

复核补充：刷新处理函数自身在 mapping/source activation busy 时 fail-closed，刷新/重试入口同步禁用，覆盖激活请求在途的状态竞态。此后再次执行 npm run verify，仍为 402/402 PASS、0 fail/cancelled/skipped，680 modules build 成功；该次输出无 WebSocket 端口诊断。
