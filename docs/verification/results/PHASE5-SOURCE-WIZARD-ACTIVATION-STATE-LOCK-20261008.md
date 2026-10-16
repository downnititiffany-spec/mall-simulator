# 阶段 5 数据源向导激活期间状态锁定（2026-10-08）

## 缺陷

映射激活请求进行中时，页面仍可切换数据源或编辑画像/样本；这些输入变化会清空旧预览与 `mappingDone`。但旧激活请求成功返回后仍可能将 `mappingDone=true` 写回，界面会把针对旧 sourceId 的映射激活结果误关联到新选择的来源，随后允许用户发起来源激活。

## 修复

- 映射/来源激活期间禁用源选择、画像与样本输入、预览、刷新及重试。
- `runPreview` 在处理器内拒绝激活期间的新预览；`activateMapping` 和 `activateSource` 分别校验报告/sourceId、mappingDone 和所有 busy 状态，避免依赖 DOM 禁用作为唯一保护。
- 不更改后端接口、权限契约、数据库或映射语义。

## 验证

在 `web/` 执行 `npm run verify`：Node test **403/403 PASS**，0 failure/cancelled/skipped；Vite production build 成功，680 modules transformed。新增源码守卫覆盖激活忙碌状态的控件锁与处理器 fail-closed 条件。

这是当前工作树的离线 Web 测试/构建证据，不包含管理员浏览器、API 写入、数据库或新版部署验证；阶段 5 与总体验收仍保持原状态。3306 未访问。
