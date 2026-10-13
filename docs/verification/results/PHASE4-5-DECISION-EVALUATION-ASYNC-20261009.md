# Decision page asynchronous evaluation reads - 2026-10-09

## Change

- The decision page previously waited for every completed decision's evaluation endpoint sequentially before returning the primary decision page to `useAnalysis`. A page with many completed rows could therefore delay its list state on many secondary requests.
- The page now returns the server-paginated decision rows without awaiting evaluation reads. Evaluation reads run as a bounded secondary task with a maximum of five active requests, show a per-row loading hint, and retain the partial-failure banner.
- Request sequence and AbortSignal checks prevent stale or unmounted pages from writing evaluation state. Completed write-action results are preserved when the background read batch merges.
- A small reusable `forEachConcurrent` utility provides the worker ceiling.

## Verification

- Targeted Node tests: `decisionEvaluationConcurrency.test.js`, `secondaryReadConcurrency.test.js`, and `decisionDraft.test.js`: **23/23 PASS**.
- `npm run verify`: Web tests **421/421 PASS**, 0 failures/cancellations/skips; Vite production build **685 modules** succeeded, exit 0.
- Playwright + mock API 浏览器切片：分页主列表首次渲染时6行立即可见、6行均显示评价加载态；延迟响应后6项评价全部显示；6个请求均完成、峰值并发5、页面错误0。此测试只使用本地 mock API，不连接后端或数据库。
- Playwright 部分失败切片：6个评价接口中5项成功、1项返回 HTTP 500；成功项仍展示，失败横幅出现，6行加载态全部结束；峰值并发5、页面错误0。仍仅使用本地 mock API。
- `git diff --check` passed; frozen V3.0 guidance and design documents remain unchanged.

## Limits

- No authenticated API or database session was available. Mock browser evidence proves rendered ordering and the bounded client-side scheduling behavior only; it does not prove request latency against a live API or close Phase 4/5.
- No service/database was started or stopped; MySQL port 3306 was not contacted.