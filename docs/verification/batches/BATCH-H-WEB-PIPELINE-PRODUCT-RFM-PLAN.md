# BATCH-H-WEB-PIPELINE-PRODUCT-RFM — Verification Plan

> 状态：READY
> Executor：Code Agent (Execution Tester)
> Tested commit：`20db9072c37e20ebecf6648f55d002d36aa452b0`
> Branch context：`feature/v3-development`
> Scope：S3-72 Pipeline retry 失败处理 + S3-73 商品页服务端分页/排序 + S3-74 RFM matrix 类目唯一属主，以及相关 Web 回归。
> Risk：低—中；仅 Web 交互/展示/请求参数，不改后端 API、数据库、状态机、权限或 AI SQL。

## 1. Checkout discipline

```powershell
git fetch origin
git checkout --detach 20db9072c37e20ebecf6648f55d002d36aa452b0
git rev-parse HEAD
git status --short
```

要求：

- HEAD 精确等于 `20db9072c37e20ebecf6648f55d002d36aa452b0`；
- tracked workspace 测试前后必须 clean；
- 不修改源码、测试、docs、脚本；
- gitignored build artifacts 允许由测试自然产生。

## 2. 定向验证 — S3-72：Pipeline retry 失败处理

```powershell
cd web
node --test tests/pipelineRetryHandling.test.js
```

预期：`4/4 PASS`。

必须确认：

1. FAILED run 的重试按钮受 `busy` 保护，防止重复点击产生并发 retry；
2. `retry()` 自身也 fail-closed：busy 时直接返回；
3. retry 开始时清理旧 `runResult`，成功后刷新列表；
4. retry 失败被 catch 并转成可见 `FAILED: ...` 结果，不产生未处理 Promise；
5. finally 恢复 busy；
6. 无 runId 的失败结果不得显示 `run#undefined`。

## 3. 定向验证 — S3-73：商品页服务端分页/排序

```powershell
node --test tests/productServerPagination.test.js
```

预期：`5/5 PASS`。

必须确认：

1. 商品请求显式发送 `page` / `size` / `sort`；
2. 页面不再对单页数据调用本地 `sortRows` / `paginate`；
3. 上一页/下一页由后端返回的 `page` / `hasMore` 驱动；
4. 可排序字段严格限于契约白名单：`rank/heat/pv/fav/cart/buy`；
5. 商品名称与转化率不得伪装成服务端全量排序；
6. 切换排序回到第 1 页并重新请求后端；
7. 导出明确是当前页，且仍受 `exportable` 门禁保护。

契约依据：`analysis-viewmodel-r7-4.md` §3.3 已冻结 `page/size/sort` 真分页与稳定排序；旧 Web 仅传 `topN` 后在当前窗口内本地排序/分页，无法代表全量排行。

## 4. 定向验证 — S3-74：RFM matrix 类目唯一属主

```powershell
node --test tests/rfmMatrixOwnership.test.js
```

预期：`4/4 PASS`。

必须确认：

1. `rfmMatrix` 存在时直接消费后端矩阵；
2. 前端不再维护 `SEGMENTS` 并把另一套固定类目追加到真实返回；
3. 旧响应缺 `rfmMatrix` 时仅展示真实 `rfmSegments`，不制造额外 0 人分组；
4. 图表与 CSV 均消费同一个 `segmentRows`；
5. 字段只做搬运/空值处理，不重算 RFM 口径。

契约依据：§3.6 明确 `rfmMatrix` 是后端八类全量矩阵；`RfmService.VALUE_GROUPS` 才是“缺失类目补 0”的唯一口径所有者。

## 5. 关键回归

```powershell
node --test tests/pipelineLocalBusinessDate.test.js tests/decisionExecutionContextDisplay.test.js
```

预期：

- `pipelineLocalBusinessDate.test.js`：5/5 PASS；
- `decisionExecutionContextDisplay.test.js`：4/4 PASS；
- 合计：9/9 PASS。

确保 Pipeline retry 改动不破坏本地业务日修复；其余上一批关键决策展示行为保持稳定。

## 6. Web 完整门禁

```powershell
npm run verify
```

上一已验证 Batch G：226 tests。

本批新增：

- `pipelineRetryHandling.test.js`：4；
- `productServerPagination.test.js`：5；
- `rfmMatrixOwnership.test.js`：4。

预计总数：`226 + 4 + 5 + 4 = 239`。

239 只是推导值，最终必须以实际 Node test 汇总为准。

要求：

- Node tests 全部 PASS；
- Failed / Cancelled = 0；
- Vite production build PASS；
- 任一新增 failure = `FAIL_NEW_REGRESSION`。

本批无 Java 生产代码变更，不运行 default/spark/isolated/3307；不运行真实浏览器 E2E。

## 7. 未覆盖边界

本批 PASS 不能宣称以下内容已验收：

- 真实浏览器 retry 双击/网络异常行为；
- `/pipeline-runs/{id}/retry` 真实 HTTP 与后端状态机；
- 商品分页/排序真实 HTTP 数据顺序与数据库结果；
- RFM 真 HTTP 返回矩阵与页面 DOM；
- Java default/spark/isolated/3307；
- Spark/Hive/Flume E2E。

## 8. 结果保存

完整结果必须同时保存：

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github branch: verification-results
github path: docs/verification/results/BATCH-H-WEB-PIPELINE-PRODUCT-RFM-RESULT.md
```

GitHub 写入必须使用隔离 worktree/temp clone；唯一 staged 文件必须是上述结果文件；禁止 push `main` 或 `feature/v3-development`。

## 9. Result fields

至少包含：

```text
Batch ID:
Tested commit:
Git status before/after:
Environment: Node/npm
S3-72: counts + result
S3-73: counts + result
S3-74: counts + result
Key regression: counts + result
Web verify: total/passed/failed/cancelled/skipped + Vite build + exit
Expected 239 confirmed: YES/NO + actual total
New failures:
Unverified runtime areas:
Local result path:
GitHub result branch/path/commit:
Overall: PASS | FAIL_NEW_REGRESSION | PARTIAL
```

## 10. PASS rule

`PASS` 仅当：

- 精确 SHA 正确；
- S3-72 4/4；
- S3-73 5/5；
- S3-74 4/4；
- 关键回归 9/9；
- `npm run verify` 全绿且 Vite build PASS；
- 无新增回归；
- 结果同时落本地与 GitHub 结果文件。
