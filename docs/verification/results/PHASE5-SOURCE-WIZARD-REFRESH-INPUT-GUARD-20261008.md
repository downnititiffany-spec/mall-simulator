# 阶段 5 数据源列表刷新期间控件门控（2026-10-08）

## 修正

来源列表刷新时，旧的 sourceId 暂时保留以避免闪烁，但来源选择和预览仍可操作，可能在列表尚未确认时产生新报告。现将 `sourcesLoading` 纳入来源、画像/样本控件和预览按钮的禁用条件，并在 `runPreview` handler 内检查加载状态；处理器级保护不依赖按钮状态。

## 验证

Web `npm run verify`：Node test 403/403 PASS，0 failure/cancelled/skipped；Vite production build 成功，680 modules transformed。源码守卫覆盖控件和处理器门控。

仅为当前工作树离线 Web 验证；没有管理员浏览器、API 写操作、数据库或部署证据。阶段 5 与总体验收保持原状态；未访问 3306。
