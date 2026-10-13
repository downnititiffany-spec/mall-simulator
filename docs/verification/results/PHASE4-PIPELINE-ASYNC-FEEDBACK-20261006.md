# PHASE 4 Pipeline Async Feedback — 2026-10-06

## 实现

- `web/src/views/Pipeline.vue` 在创建或重试得到 RunId 后轮询 `GET /api/v1/pipeline-runs/{id}`，初次间隔 800ms，之后 1500ms；仅对 `PENDING`/`RUNNING` 继续轮询。
- 页面显示当前阶段、阶段行状态/记录数/失败码以及任务级失败码。轮询读取暂时失败时提示稍后重试，不把读取错误改写为流水线失败。
- 任务进入终态、启动另一项运行或组件卸载时停止旧轮询；卸载同时取消分析列表请求。
- 更新流水线页守卫，使输入批次/空态列数断言只针对实例表，避免新增阶段明细表改变页面表格数量后误判。

## 验证

- `node --test tests/pipelineAsyncFeedback.test.js tests/pipelinePage.test.js tests/pipelineRetryHandling.test.js`：14/14 PASS。
- `npm run build`：Vite production build PASS，680 modules transformed。
- 首次测试因旧测试正则命中了新增的阶段明细表而出现 13/14；收窄到流水线实例表后复跑全绿。

## 边界

这是源码、静态页面契约测试和前端构建证据。本轮未做真实浏览器运行、API/DB 实服轮询验证或新版部署；不能据此声称真实长任务链路验收通过。阶段 4 仍部分完成。
