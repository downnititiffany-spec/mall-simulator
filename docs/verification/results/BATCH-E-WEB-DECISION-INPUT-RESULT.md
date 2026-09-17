# Batch E Execution Result — Web Decision Input

- **Batch ID**：`BATCH-E-WEB-DECISION-INPUT`
- **Role**：Code Agent / Execution Tester
- **Tested commit**：`c42ee34d4bfb2364c646f060f675af67974a6a6c`
- **Branch context**：`origin/feature/v3-development` 当时指向同一 SHA；detached 精确检出
- **Git status**：clean（测试前/中/后均为空）
- **Environment**：Node `v24.16.0`，npm `11.13.0`
- **Overall**：`PASS`

## 1. Web 全量

命令：

```bash
cd web
npm run verify
```

实际结果：

- Node tests：**212**
- Passed：**212**
- Failed：**0**
- Cancelled：0
- Skipped：0
- Todo：0
- Vite：`v5.4.21`
- production build：**PASS**
- modules transformed：671
- build time：2.68s
- exit：0

数量变化：上一批 203 + V-013 新增 5 + V-014 新增 4 = 212，实测与预期一致。

## 2. V-013 — S3-65 批准时显式负责人/截止日期

`decisionApprovalInput.test.js`：**5/5 PASS**。

已验证：

1. approve 不再硬编码默认负责人；
2. 不再 `Date.now()+3天` 自动生成 dueDate；
3. 负责人 prompt 取消 → API 前返回；
4. 负责人纯空白 → fail-closed；
5. dueDate 必须显式输入；
6. dueDate 必须符合 `YYYY-MM-DD`；
7. 使用 `Date.UTC` + 年/月/日回读校验真实日历日期；等价 Node 探针确认 `2026-02-30`、`2026-04-31`、`2026-13-01`、`2026-00-10`、`2026-02-29` 被拒绝，`2028-02-29` 与 `2026-09-20` 接受；
8. owner + dueDate 完成后才进入 busy；
9. 请求形状为 `{ owner, dueDate }`；
10. 成功后仍 `await flush()`。

证据边界：该测试为源码接线守卫 + Node test，不等同于真实浏览器 prompt/HTTP/后端状态机 E2E。

## 3. V-014 — S3-66 DRAFT 提交审核时补 owner

`decisionSubmitOwner.test.js`：**4/4 PASS**。

已验证：

1. DRAFT “提交审核”按钮调用 `submitDecision(d)`，不再走 `act(d,'submit')`；
2. 已有真实 owner 只作为可编辑初始值；
3. owner 缺失时初始值为空，不生成默认身份；
4. prompt 取消/纯空白在 busy/API 前 fail-closed；
5. 请求精确为 `api.decisionAction(d.id, 'submit', { owner })`，不再发 `{}`；
6. 成功后 `await flush()`。

额外核对：`act(d, action)` 的通用 `{}` 路径当前仅用于 `start` / `complete`；submit/reject/cancel/approve 均使用独立载荷路径。

## 4. 回归

- `aiAskCancellation.test.js`：**5/5 PASS**
- `decisionCancelWiring.test.js`：**3/3 PASS**
- `decisionRequiredReason.test.js`：**4/4 PASS**

V-013/V-014 未破坏 AI 取消、reject/cancel reason 采集和 `useAnalysis.cancel` 的页面请求取消职责。

## 5. 观察与未覆盖面

- `运营-小李` 仍出现于 approve 的 prompt **示例文案**，不是默认值/初始值；`admin` / `demo` 不存在于相关提交路径。
- `toISOString()` 在 Decisions 页仍用于 CSV `generatedAt`，与 dueDate 无关。
- `2026-02-30` 字面量不是由应用测试直接喂给 Vue helper，而是由测试确认日历校验结构、再由等价 Node 逻辑探针独立复算；真实浏览器级日期输入留 E2E。
- 未覆盖：真实 Vue 点击、真实 `window.prompt`、真实 `POST /decisions/{id}/approve|submit`、后端状态机流转、数据库落库/审计。

## 6. 判定

```text
Expected 212 confirmed: YES
Actual total: 212
New failures: none
Overall: PASS
```

本文件由 ChatGPT 根据 Code Agent 独立执行报告复核后永久归档；原始测试运行期间未修改 Git tracked 文件。