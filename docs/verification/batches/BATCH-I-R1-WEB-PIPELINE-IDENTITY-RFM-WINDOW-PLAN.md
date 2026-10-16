# BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW — Verification Plan

> 状态：READY
> Executor：Code Agent (Execution Tester)
> Tested commit：`2cf150b82168904fe6500089cf699d986c374514`
> Branch context：`feature/v3-development`
> Scope：Batch I 失败复测。仅修正 `pipelineOperationIdentity.test.js` 对 `//` 注释文本的误计数；同时复验 S3-75 / S3-76、关键回归与 Web full gate。
> Risk：低；生产代码相对 Batch I 被测提交未变，仅测试守卫修正。

## 1. Checkout discipline

```powershell
git fetch origin
git checkout --detach 2cf150b82168904fe6500089cf699d986c374514
git rev-parse HEAD
git status --short
```

要求 HEAD 精确匹配；tracked workspace 测试前后 clean；不得修改源码、测试、docs 或脚本。

## 2. S3-75 targeted rerun

```powershell
cd web
node --test tests/pipelineOperationIdentity.test.js
```

预期：`4/4 PASS`。

必须确认：

1. `runOnce()` 自身 busy fail-closed；
2. 测试只统计可执行文本中的 `Date.now()`，`//` 注释不参与调用次数判定；
3. 可执行 `Date.now()` 恰好 1 次；
4. `sourceDataVersion` 与 `Idempotency-Key` 复用同一个 `operationId`；
5. 成功刷新、失败可见、finally 释放 busy 不回归。

## 3. S3-76 rerun

```powershell
node --test tests/rfmObservationWindow.test.js
```

预期：`4/4 PASS`。

继续确认 RFM 观察窗口只消费后端 `periodStart/periodEnd`，缺失不猜，CSV 使用后端原值。

## 4. Key regression

```powershell
node --test tests/pipelineRetryHandling.test.js tests/pipelineLocalBusinessDate.test.js tests/rfmMatrixOwnership.test.js
```

预期：`13/13 PASS`。

## 5. Web full gate

```powershell
npm run verify
```

Batch I 实测总数 247，本复测没有增删测试用例，因此预计仍为 247；以实际汇总为准。

要求：

- Node tests 全部 PASS；
- Failed / Cancelled = 0；
- Vite production build 必须实际执行并 PASS；
- 任一新增 failure = `FAIL_NEW_REGRESSION`。

## 6. Result persistence

```text
local:  .verify/CURRENT_BATCH_RESULT.md
github branch: verification-results
github path: docs/verification/results/BATCH-I-R1-WEB-PIPELINE-IDENTITY-RFM-WINDOW-RESULT.md
```

只允许向上述结果路径写本批结果；禁止 push `main` 或 `feature/v3-development`。

## 7. PASS rule

`PASS` 仅当：

- 精确 SHA 正确；
- S3-75 4/4；
- S3-76 4/4；
- 回归 13/13；
- `npm run verify` 全绿且 Vite build PASS；
- 无新增回归；
- 结果本地/GitHub 双落盘。
