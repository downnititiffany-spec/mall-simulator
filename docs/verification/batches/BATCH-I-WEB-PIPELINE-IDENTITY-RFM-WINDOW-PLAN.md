# BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW — Verification Plan

> 状态：READY
> Executor：Code Agent (Execution Tester)
> Tested commit：`b5e4972bdb272ca476be20b1638b0c78d905438b`
> Branch context：`feature/v3-development`
> Scope：S3-75 人工流水线操作身份一致性 + S3-76 RFM 观察窗口展示，以及相关 Web 回归。
> Risk：低—中；仅 Web 请求组装/展示/导出，不改后端 API、数据库、状态机、权限或 AI SQL。

## 1. Checkout discipline

```powershell
git fetch origin
git checkout --detach b5e4972bdb272ca476be20b1638b0c78d905438b
git rev-parse HEAD
git status --short
```

要求 HEAD 精确匹配；测试前后 tracked workspace clean；不得修改源码、测试、docs、脚本。

## 2. S3-75 — Pipeline 人工操作 identity

```powershell
cd web
node --test tests/pipelineOperationIdentity.test.js
```

预期：`4/4 PASS`。

必须确认：

1. `runOnce()` 自身先 `if (busy.value) return`，不能只依赖按钮 disabled；
2. 一次人工触发只生成一次 `Date.now()` / 一个 `operationId`；
3. `sourceDataVersion` 与 `api.createPipelineRun(..., idempotencyKey)` 复用同一个 `operationId`；
4. 不再出现两个独立 `manual- + Date.now()` 导致同一操作的源版本与幂等键毫秒级分叉；
5. 成功仍刷新列表，失败可见，`finally` 释放 busy。

## 3. S3-76 — RFM 观察窗口

```powershell
node --test tests/rfmObservationWindow.test.js
```

预期：`4/4 PASS`。

必须确认：

1. `periodStart` / `periodEnd` 只从 `/analysis/rfm` 的 `rfm.data` 搬运；不得从 `/analysis/users` 猜窗口；
2. 起止都存在才展示 `start 至 end`；任一缺失则显示 `未提供`；
3. defaults 显式为 null，不用当前日期等值制造默认窗口；
4. 页面顶部展示观察期；
5. CSV 同步增加「观察期开始/观察期结束」并使用后端原值；
6. 不通过 `new Date()` 重算观察期。

## 4. Regression

```powershell
node --test tests/pipelineRetryHandling.test.js tests/pipelineLocalBusinessDate.test.js tests/rfmMatrixOwnership.test.js
```

预期：

- pipelineRetryHandling：4/4；
- pipelineLocalBusinessDate：5/5；
- rfmMatrixOwnership：4/4；
- 合计：13/13 PASS。

不得回归 Batch H 的 retry fail-closed、Batch G 的本地业务日，以及后端唯一 RFM matrix 类目属主。

## 5. Web full gate

```powershell
npm run verify
```

上一已验证 Batch H：239 tests。
本批新增：`pipelineOperationIdentity` 4 + `rfmObservationWindow` 4 = 8。
预计总数：`247`；实际以 Node 汇总为准。

要求：Failed/Cancelled=0，全部测试通过，Vite production build PASS。

## 6. Not run

本批不运行 Java default/spark/isolated/3307，也不运行真实浏览器 / HTTP / Spark-Hive-Flume E2E。

## 7. Result persistence

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github: verification-results:docs/verification/results/BATCH-I-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md
```

GitHub 唯一写入例外仍只允许上述结果文件；不得 push main / feature/v3-development。

## 8. Result fields

至少包含：Tested commit、Git status before/after、S3-75 counts/result、S3-76 counts/result、Regression counts/result、Web verify total/passed/failed/cancelled/skipped、Vite build、Expected 247 confirmed、New failures、Unverified runtime areas、GitHub result commit、Overall。

## 9. PASS rule

仅当精确 SHA 正确、S3-75 4/4、S3-76 4/4、回归 13/13、Web full gate 全绿 + build PASS、无新增回归、结果本地/GitHub 双落盘时，Overall 才能为 `PASS`。
