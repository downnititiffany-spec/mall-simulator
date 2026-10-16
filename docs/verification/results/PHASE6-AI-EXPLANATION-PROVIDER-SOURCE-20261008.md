# 阶段 6 AI 解释最终来源展示（离线 Web 验证）

日期：2026-10-08

## 实现

AI 助手在“建议与可能原因”区显示后端 `explanation.providerUsed`，明确标注为解释最终来源，并说明 `template` 表示模板/规则回退。若解释文字为空但后端给出来源，区块仍可见；缺失值显示“接口未提供”。展示直接消费后端来源字段，不依据文本差异推断模型调用。

## 验证

- `web/npm run verify`：Node 测试 404/404 通过，0 fail/cancelled/skipped；Vite production build 成功（680 modules transformed）。
- 测试包含新增 `aiExplanationProviderSource.test.js` 静态契约守卫。
- 过程出现 `WebSocket server error: Port is already in use` 诊断；测试与构建均成功。

## 证据边界

这是离线前端测试与构建证据；没有真实 Provider 调用、认证浏览器、数据库、部署或端到端验证。`providerUsed` 的实际运行值仍取决于后端该次请求结果。D-039 外部条件未齐，阶段 6 和最终验收不因此关闭。
